param([Parameter(Mandatory)][ValidateSet('csqtt','qwdtt','wdtt')][string]$Kind,
      [string]$AcceptedAndroidSource='D:/MCRF/Builds/Android/next-independent-20d0f883b4cf419c8d155969a0f54408/source/ports/android')
$ErrorActionPreference='Stop'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
$taskKindFolder=if($Kind -eq 'csqtt'){'csqtt-legacy'}else{$Kind}
$taskReceiptPath="$AcceptedAndroidSource/native-stage-$taskKindFolder/receipt.json"
$taskReceipt=Get-Content -LiteralPath $taskReceiptPath -Raw|ConvertFrom-Json
if($taskReceipt.kind -cne $Kind -or $taskReceipt.state -cne 'COMPILED_NOT_EXECUTED' -or !$taskReceipt.sourceStable){throw 'Exact accepted native source required'}
$taskOrigin=[IO.Path]::GetFullPath($taskReceipt.source)
if($taskOrigin -cnotmatch '^D:\\MCRF\\Projects\\MCRF\\work\\ports-test-2026-10-14\\swg-mobile-[0-9a-f]{32}$'){throw 'Unexpected accepted source boundary'}
$taskPreparedPath="$taskOrigin/prepared-expansion-source.json"
$taskPrepared=Get-Content -LiteralPath $taskPreparedPath -Raw|ConvertFrom-Json
if($taskPrepared.kind -cne $Kind -or !$taskPrepared.sourceStable){throw 'Accepted preparation manifest required'}
$taskOriginalModule=if($Kind -eq 'wdtt'){"$taskOrigin/wdtt/go_client"}else{"$taskOrigin/qwdtt/qwdtt"}
$taskFiles=@(Get-ChildItem -LiteralPath $taskOriginalModule -Recurse -File|Where-Object {$_.Extension -eq '.go' -or $_.Name -in @('go.mod','go.sum')})
if($taskFiles.Count -ne @($taskPrepared.inputs).Count){throw 'Accepted source set changed'}
$taskStage="D:/MCRF/Builds/Android/p3-turn-source-$([guid]::NewGuid().ToString('N'))";$taskModule="$taskStage/module"
New-Item -ItemType Directory -Path $taskModule|Out-Null
foreach($taskRow in $taskPrepared.inputs){
 if([IO.Path]::IsPathRooted($taskRow.path) -or '..' -in ($taskRow.path -split '[/\\]')){throw 'Unsafe accepted source path'}
 $taskFrom=Join-Path $taskOriginalModule $taskRow.path
 if((Get-Item -LiteralPath $taskFrom).LinkType -or (Hash $taskFrom) -cne $taskRow.sha256){throw 'Accepted source hash changed'}
 $taskTo=Join-Path $taskModule $taskRow.path;New-Item -ItemType Directory -Path (Split-Path $taskTo) -Force|Out-Null;Copy-Item -LiteralPath $taskFrom -Destination $taskTo
}
if($Kind -eq 'wdtt'){
 $taskDependencySource="$taskOrigin/wdtt/pathprobe";$taskDependencyTarget="$taskStage/pathprobe"
 Copy-Item -LiteralPath $taskDependencySource -Destination $taskDependencyTarget -Recurse
 $taskDependencyRows=@(Get-ChildItem -LiteralPath $taskDependencyTarget -Recurse -File|ForEach-Object {@{path=[IO.Path]::GetRelativePath($taskDependencyTarget,$_.FullName).Replace([char]92,[char]47);sha256=Hash $_.FullName;bytes=$_.Length}}|Sort-Object path)
 [IO.File]::WriteAllText("$taskStage/DEPENDENCY_MANIFEST.json",($taskDependencyRows|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
}
& "$PSScriptRoot/Apply-PerformanceSource.ps1" -ModuleSource $taskModule -Kind $Kind
$taskSocks=$null
if($Kind -in @('qwdtt','wdtt')){
 $taskSocksOverlay=Join-Path $PSScriptRoot '../../socks-owner/overlay.py'
 $taskSocks=(& python -B $taskSocksOverlay $Kind $taskModule | ConvertFrom-Json)
 if($LASTEXITCODE -ne 0 -or $taskSocks.contract -ne 1){throw 'Pinned TURN SOCKS UDP source overlay failed'}
}
$taskInputs=@(Get-ChildItem -LiteralPath $taskModule -Recurse -File|ForEach-Object {@{path=[IO.Path]::GetRelativePath($taskModule,$_.FullName).Replace('\','/');sha256=Hash $_.FullName}}|Sort-Object path)
[IO.File]::WriteAllText("$taskStage/SOURCE_MANIFEST.json",($taskInputs|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$taskProof=@{state='P3_TURN_SOURCE_PREPARED_NOT_COMPILED';kind=$Kind;module=$taskModule;sourceStable=$true;baselineReceipt=$taskReceiptPath;baselineReceiptSha256=Hash $taskReceiptPath;baselinePreparedSha256=Hash $taskPreparedPath;manifestSha256=Hash "$taskStage/SOURCE_MANIFEST.json";performanceSourceSha256=Hash "$PSScriptRoot/Apply-PerformanceSource.ps1";compiled=$false;executed=$false}
if($Kind -eq 'wdtt'){$taskProof.dependencyManifestSha256=Hash "$taskStage/DEPENDENCY_MANIFEST.json"}
if($taskSocks){$taskProof.socksUdpSource=@{$Kind=1};$taskProof.socksUdpSourceOverlay=$taskSocks}
[IO.File]::WriteAllText("$taskStage/receipt.json",($taskProof|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
Write-Output "P3_TURN_SOURCE=$taskStage"
