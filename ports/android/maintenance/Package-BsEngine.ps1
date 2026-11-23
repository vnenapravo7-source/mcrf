param([Parameter(Mandatory)][string]$Gate,[Parameter(Mandatory)][string]$GateSha256,
      [Parameter(Mandatory)][string]$MainApk,[Parameter(Mandatory)][string]$ApkDirectory,
      [Parameter(Mandatory)][string]$OutputDirectory,
      [string]$AndroidSdk="$env:LOCALAPPDATA/Android/Sdk")
$ErrorActionPreference='Stop'
$taskAndroid=Split-Path $PSScriptRoot
# Verify the exact two-file multi-call candidate and source identities before APK work.
& python "$taskAndroid/transports/swg/bs_engine_inputs.py" --gate $Gate --sha256 $GateSha256
if($LASTEXITCODE -ne 0){throw 'BS layout/source gate refused'}
$taskGate=Get-Content -LiteralPath $Gate -Raw|ConvertFrom-Json
if($taskGate.schema -ne 3 -or $taskGate.layout -cne 'rust-multicall-v1'){throw 'Current multi-call gate required'}
# Package assembly does not promote the unmeasured profile to release acceptance.
$taskTools=Join-Path $AndroidSdk 'build-tools/36.0.0'
function Signer([string]$Path){
 $result=& "$taskTools/apksigner.bat" verify --print-certs $Path
 if($LASTEXITCODE -ne 0){throw 'Invalid APK signature'}
 $signers=@($result|Select-String 'certificate SHA-256 digest:'|ForEach-Object{($_.Line -split ': ')[-1]})
 if($signers.Count -ne 1 -or $signers[0] -notmatch '^[0-9a-f]{64}$'){throw 'Single signer required'}
 return $signers[0]
}
$taskSigner=Signer $MainApk
$taskMetadata=Get-Content -LiteralPath "$ApkDirectory/output-metadata.json" -Raw|ConvertFrom-Json
if($taskMetadata.applicationId -cne 'org.mcrf.engines.bs'){throw 'BS package identity mismatch'}
if(@($taskMetadata.elements).Count -ne 3){throw 'Exactly three ABI-specific BS outputs required'}
if(Test-Path -LiteralPath $OutputDirectory){throw 'Use a fresh output directory'}
$taskRows=@()
foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
 $element=@($taskMetadata.elements|Where-Object{@($_.filters).Count -eq 1 -and $_.filters[0].filterType -ceq 'ABI' -and $_.filters[0].value -ceq $taskAbi})
 if($element.Count -ne 1){throw 'Wrong BS output ABI'}
 $name=$element[0].outputFile
 if([IO.Path]::GetFileName($name) -cne $name){throw 'Unsafe APK output path'}
 $apk=Join-Path $ApkDirectory $name
 if((Signer $apk) -cne $taskSigner){throw 'BS/main signers differ'}
 & "$taskTools/zipalign.exe" -c -P 16 4 $apk
 if($LASTEXITCODE -ne 0){throw 'BS APK ZIP alignment failed'}
 $badging=& "$taskTools/aapt.exe" dump badging $apk
 if($LASTEXITCODE -ne 0 -or !($badging|Select-String "^package: name='org.mcrf.engines.bs' ")){throw 'BS manifest package mismatch'}
 $taskXml=& "$taskTools/aapt.exe" dump xmltree $apk AndroidManifest.xml
 if($LASTEXITCODE -ne 0){throw 'BS manifest metadata inspection failed'}
 $taskXml | & python "$taskAndroid/transports/swg/bs_engine_inputs.py" --gate $Gate --sha256 $GateSha256 --verify-apk $apk --abi $taskAbi --manifest-xmltree-stdin
 if($LASTEXITCODE -ne 0){throw 'BS source-bound APK verification refused'}
 $taskRows+=@{abi=$taskAbi;source=$apk;name="mcrf-engine-bs-$taskAbi-release.apk";bytes=(Get-Item -LiteralPath $apk).Length;sha256=(Get-FileHash -LiteralPath $apk).Hash.ToLowerInvariant()}
}
New-Item -ItemType Directory -Path $OutputDirectory|Out-Null
foreach($row in $taskRows){Copy-Item -LiteralPath $row.source -Destination (Join-Path $OutputDirectory $row.name)}
$taskRows|ConvertTo-Json -Depth 4|Set-Content -LiteralPath "$OutputDirectory/packages.json" -Encoding utf8
