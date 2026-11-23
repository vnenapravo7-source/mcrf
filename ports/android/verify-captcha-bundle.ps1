param([Parameter(Mandatory=$true)][ValidatePattern('^android-integration-[a-f0-9]{32}$')][string]$Snapshot)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$frozen=Join-Path "$root/work" $Snapshot
$build=Get-Content "$frozen/build-result.json" -Raw|ConvertFrom-Json
if($build.exitCode -ne 0 -or !$build.sourceStable -or $build.firstLaunchFixture){throw 'Requires completed stable main bundle'}
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
$sdk='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
$signer='fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'
function Identity([string]$path,[string]$package,[int]$version){
    $cert=(& "$sdk/apksigner.bat" verify --print-certs $path 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $cert -notmatch "Signer #1 certificate SHA-256 digest: $signer"){throw 'Unexpected signer'}
    $manifest=(& "$sdk/aapt2.exe" dump badging $path 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $manifest -notmatch ('(?m)^package: name='''+[regex]::Escape($package)+''' versionCode='''+$version+''' ')){throw "Unexpected package/version: $package"}
    [ordered]@{path=$path;package=$package;versionCode=$version;bytes=(Get-Item -LiteralPath $path).Length;sha256=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant();signer=$signer}
}
$rows=@()
foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64','universal')){
    $apk="$frozen/ports/android/app/build/outputs/apk/release/app-$abi-release.apk"
    $row=Identity $apk 'org.mcrf.client' 10005
    $zip=[IO.Compression.ZipFile]::OpenRead($apk)
    try{if(@($zip.Entries|Where-Object {$_.FullName.StartsWith('lib/')})){throw 'Base APK contains native payload'}}finally{$zip.Dispose()}
    $row.abi=$abi;$row.kind='app';$row.nativeCount=0;$rows+=$row
}
$stages=@{wdtt='native-stage-wdtt';qwdtt='native-stage-qwdtt';csqtt='native-stage-csqtt-legacy';csqtt25='native-stage-csqtt25'}
# Require the actual frozen mapping; never fall back to canonical/native inputs.
$inputs=Get-Content "$frozen/source-manifest.json" -Raw|ConvertFrom-Json
foreach($kind in @('wdtt','qwdtt','csqtt','csqtt25')){
    $stage=Join-Path "$frozen/ports/android" $stages[$kind]
    if(!(Test-Path "$stage/receipt.json")){throw "Missing frozen candidate receipt: $kind"}
    $native=Get-Content "$stage/receipt.json" -Raw|ConvertFrom-Json
    if(!$native.sourceStable -or $native.kind -cne $kind -or $native.state -cne 'COMPILED_NOT_EXECUTED'){throw 'Candidate producer mismatch'}
    $abis=if($kind -eq 'csqtt25'){@('arm64-v8a')}else{@('arm64-v8a','armeabi-v7a','x86_64','universal')}
    foreach($abi in $abis){
        $apk="$frozen/ports/android/engines/build/outputs/apk/$kind/release/engines-$kind-$abi-release.apk"
        $row=Identity $apk "org.mcrf.engines.$kind" $(if($kind -eq 'csqtt25'){1}else{5})
        $zip=[IO.Compression.ZipFile]::OpenRead($apk)
        try{
            $expected=@($native.artifacts|Where-Object {$abi -eq 'universal' -or $_.abi -ceq $abi})
            $entries=@($zip.Entries|Where-Object {$_.FullName.StartsWith('lib/') -and !$_.FullName.EndsWith('/')})
            if($entries.Count -ne $expected.Count -or !$expected.Count){throw 'Native ABI/library count mismatch'}
            foreach($item in $expected){
                $name=($item.file -replace '\\','/').Split('/')[-1]
                $entry=$zip.GetEntry("lib/$($item.abi)/$name")
                if(!$entry -or $entry.Length -ne $item.bytes){throw 'Native package size mismatch'}
                $hash=[Security.Cryptography.SHA256]::Create();$stream=$entry.Open()
                try{$digest=[Convert]::ToHexString($hash.ComputeHash($stream)).ToLowerInvariant()}finally{$stream.Dispose();$hash.Dispose()}
                if($digest -cne $item.sha256){throw 'Native package SHA mismatch'}
            }
            $licenses=@(Get-ChildItem -LiteralPath "$stage/licenses" -File)
            if(!$licenses.Count){throw 'Missing notices'}
            foreach($license in $licenses){
                # Windows asset merging can preserve qWDTT capitalization from the
                # common notices directory. Require exactly one matching notice
                # and its exact upstream bytes, not a platform-dependent spelling.
                $matches=@($zip.Entries|Where-Object {[string]::Equals($_.FullName,'assets/licenses/'+$license.Name,[StringComparison]::OrdinalIgnoreCase)})
                if($matches.Count -ne 1){throw 'Original notice absent or ambiguous'}
                $entry=$matches[0]
                if(!$entry -or $entry.Length -ne $license.Length){throw 'Packaged notice missing/size mismatch'}
                $hash=[Security.Cryptography.SHA256]::Create();$stream=$entry.Open()
                try{$digest=[Convert]::ToHexString($hash.ComputeHash($stream)).ToLowerInvariant()}finally{$stream.Dispose();$hash.Dispose()}
                if($digest -cne (Get-FileHash $license.FullName).Hash.ToLowerInvariant()){throw 'Packaged notice SHA mismatch'}
            }
        }finally{$zip.Dispose()}
        $row.kind=$kind;$row.abi=$abi;$row.nativeCount=$expected.Count;$rows+=$row
    }
}
if($rows.Count -ne 17){throw 'Incomplete bundle'}
$evidence=Join-Path "$root/work/ports-test-2026-10-14" ('captcha-bundle-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $evidence|Out-Null
$receipt=[ordered]@{snapshot=$Snapshot;state='PASS_PACKAGING_NOT_RUNTIME';utc=[DateTime]::UtcNow.ToString('o');files=$rows;published=$false;installed=$false;limit='Actual APK signer/package/version/native ABI bytes and original notices only; not provider CAPTCHA or Internet acceptance'}
[IO.File]::WriteAllText("$evidence/receipt.json",($receipt|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
Write-Output "BUNDLE_RECEIPT=$evidence/receipt.json"
$rows|Select-Object kind,abi,bytes,nativeCount,sha256|Format-Table -AutoSize
