$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$candidate=Join-Path $PSScriptRoot 'transports/warp/build/candidate-2bfb9554fadc43d8972e76382bb4cbae'
$receipt=Get-Content (Join-Path $candidate 'receipt.json') -Raw|ConvertFrom-Json
if($receipt.stage -ne 'android-warp-fast-candidate' -or !$receipt.hostTestsPassed -or $receipt.durabilityEchoes -ne 10){throw 'Unverified candidate'}
$logs=@(Get-ChildItem (Join-Path $root 'work/ports-test-2026-10-14/integration-0080-device') -Filter 'warp-authorized*.log' -File)
if($logs.Count -lt 2){throw 'Repeated actual WARP gate required'}
foreach($log in $logs){$text=Get-Content -LiteralPath $log.FullName -Raw;if($text -notmatch 'INSTRUMENTATION_RESULT: passed=2' -or $text -notmatch 'foreign exit NL/AMS; 1 attempts' -or $text -match '\bFAIL\b'){throw 'Foreign WARP gate incomplete'}}
$stage=Join-Path $root 'outputs/android-warp-fast-20261015-5'
if(Test-Path -LiteralPath $stage){throw 'Existing stage; inspect before retry'}
New-Item -ItemType Directory -Path $stage|Out-Null
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
$tools='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$rows=@()
foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){
    $apk=Join-Path $root "work/android-module-artifacts-e2331ffae796409083934b1a9e7f4052/warpscout/release/engines-warpscout-$abi-release.apk"
    $signer=(& "$tools/apksigner.bat" verify --print-certs $apk|Out-String)
    if($LASTEXITCODE -ne 0 -or $signer -notmatch 'fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Scanner signer mismatch'}
    $badging=(& "$tools/aapt2.exe" dump badging $apk|Out-String)
    if($LASTEXITCODE -ne 0 -or $badging -notmatch "package: name='org.mcrf.engines.warpscout' versionCode='5'"){throw 'Wrong scanner package/version'}
    $zip=[IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $native=@($zip.Entries|Where-Object {$_.FullName -match '^lib/.*\.so$'})
        if($native.Count -ne 1 -or $native[0].FullName -cne "lib/$abi/libwarpscout.so"){throw 'Wrong native payload'}
        $stream=$native[0].Open();try{$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()}finally{$stream.Dispose()}
        $expected=@($receipt.files|Where-Object abi -eq $abi)
        if($expected.Count -ne 1 -or $expected[0].sha256 -cne $hash){throw 'Packaged native differs from tested candidate'}
    }finally{$zip.Dispose()}
    $name="mcrf-engine-warpscout-$abi-release.apk"
    [IO.File]::Copy($apk,(Join-Path $stage $name),$false)
    $rows+=[ordered]@{name=$name;abi=$abi;bytes=(Get-Item $apk).Length;sha256=(Get-FileHash $apk).Hash.ToLowerInvariant();versionCode=5}
}
[IO.File]::WriteAllText((Join-Path $stage 'packages.json'),([ordered]@{schema=1;files=$rows;durabilityEchoes=10;quickCandidates=2;extendedCandidates=8;searchAttempts=3;actualArm64ForeignRuns=2;firstWithAppInstallSeconds=22.24;secondWithoutInstallSeconds=29.54;notUniversalSpeedGuarantee=$true}|ConvertTo-Json -Depth 5))
[IO.File]::WriteAllLines((Join-Path $stage 'SHA256SUMS.txt'),@($rows|ForEach-Object {"$($_.sha256)  $($_.name)"}))
$zip=[IO.Compression.ZipFile]::Open((Join-Path $stage 'warp-mobile-source.zip'),[IO.Compression.ZipArchiveMode]::Create)
try {foreach($input in Get-ChildItem (Join-Path $PSScriptRoot 'transports/warp') -File|Where-Object {$_.Extension -in @('.go','.ps1','.patch')}){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$input.FullName,$input.Name,[IO.Compression.CompressionLevel]::Optimal)|Out-Null}}finally{$zip.Dispose()}
& git -C (Join-Path $root 'work/android-upstream/warpscout-android') archive --format=zip --prefix=warpscout/ -o (Join-Path $stage 'warpscout-upstream-source.zip') d35fa797e702cc1e930701568c184aa99459f692
if($LASTEXITCODE -ne 0){throw 'Pinned scanner source archive failed'}
Write-Output "WARP_PUBLICATION_STAGE=$stage"
