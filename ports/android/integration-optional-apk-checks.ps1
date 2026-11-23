param([Parameter(Mandatory=$true)][string]$Snapshot)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskSnapshot=[IO.Path]::GetFullPath((Join-Path $taskRoot $Snapshot))
$taskWork=Join-Path $taskRoot 'work'
if(!$taskSnapshot.StartsWith($taskWork+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($taskSnapshot) -notmatch '^android-integration-[0-9a-f]{32}$'){throw 'Invalid frozen snapshot'}
$build=Get-Content -LiteralPath (Join-Path $taskSnapshot 'build-result.json') -Raw|ConvertFrom-Json
if($build.exitCode -ne 0 -or !$build.sourceStable){throw 'Frozen build is not green'}
$manifest=Get-Content -LiteralPath (Join-Path $taskSnapshot 'source-manifest.json') -Raw|ConvertFrom-Json
$receiptInput=@($manifest|Where-Object {$_.path -match 'optional-cores/build/attempt-[^/]+/receipt.json$'})
if($receiptInput.Count -ne 1){throw 'Exactly one frozen optional worker receipt required'}
$receipt=Get-Content -LiteralPath (Join-Path $taskSnapshot $receiptInput[0].path) -Raw|ConvertFrom-Json
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
$rows=@()
foreach($flavor in @('xray','tlscore')){
    $kind=if($flavor -eq 'tlscore'){'tls-core'}else{'xray'}
    $library=if($flavor -eq 'tlscore'){'libtls_core.so'}else{'libxray.so'}
    foreach($variant in @('arm64-v8a','armeabi-v7a','x86_64','universal')){
        $apk=Join-Path $taskSnapshot "ports/android/engines/build/outputs/apk/$flavor/debug/engines-$flavor-$variant-debug.apk"
        $signature=(& 'C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0/apksigner.bat' verify --print-certs $apk 2>&1|Out-String)
        if($LASTEXITCODE -ne 0 -or $signature -notmatch 'Signer #1 certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Unexpected module signer'}
        $abis=if($variant -eq 'universal'){@('arm64-v8a','armeabi-v7a','x86_64')}else{@($variant)}
        $zip=[IO.Compression.ZipFile]::OpenRead($apk)
        try{
            $native=@($zip.Entries|Where-Object {$_.FullName -like 'lib/*/*.so'})
            if($native.Count -ne $abis.Count){throw 'Unexpected native module entries'}
            foreach($abi in $abis){
                $entry=$zip.GetEntry("lib/$abi/$library");$expected=@($receipt.files|Where-Object {$_.kind -eq $kind -and $_.abi -eq $abi})
                if($null -eq $entry -or $expected.Count -ne 1 -or $entry.Length -ne $expected[0].bytes){throw 'Module native size/ABI mismatch'}
                $stream=$entry.Open();$hash=[Security.Cryptography.SHA256]::Create()
                try{$digest=[Convert]::ToHexString($hash.ComputeHash($stream)).ToLowerInvariant()}finally{$stream.Dispose();$hash.Dispose()}
                if($digest -ne $expected[0].sha256){throw 'Module native SHA mismatch'}
            }
            foreach($license in @('Xray-MPL-2.0.txt','sing-box-LICENSE.txt','qThrone-GPL-3.0.txt')){if($null -eq $zip.GetEntry("assets/$license")){throw 'Missing module license'}}
        }finally{$zip.Dispose()}
        $rows+=[pscustomobject]@{kind=$kind;variant=$variant;bytes=(Get-Item -LiteralPath $apk).Length;sha256=(Get-FileHash -LiteralPath $apk).Hash.ToLowerInvariant();native_matches_frozen_receipt=$true;signer_verified=$true;licenses=$true}
    }
}
$release=Get-ChildItem -LiteralPath (Join-Path $taskSnapshot 'ports/android/app/build/outputs/apk/release') -Filter '*.apk'
if(@($release).Count -ne 4){throw 'Expected all four release variants'}
foreach($apk in $release){
    $zip=[IO.Compression.ZipFile]::OpenRead($apk.FullName)
    try{foreach($entry in $zip.Entries|Where-Object {$_.FullName -like 'lib/*/*.so'}){if([IO.Path]::GetFileName($entry.FullName) -notin @('libbox.so','libtgws.so')){throw "Optional native payload leaked into base release: $($entry.FullName)"}}}finally{$zip.Dispose()}
}
$evidence=Join-Path $taskRoot ('work/ports-test-2026-10-14/integration-'+[IO.Path]::GetFileName($taskSnapshot).Substring(20,4)+'-device')
New-Item -ItemType Directory -Path $evidence -Force|Out-Null
$result=[pscustomobject]@{snapshot=$taskSnapshot;module_apks=$rows;release_variants=4;base_release_optional_or_debug_peer_embedded=$false;state='passed';scope='Static exact signature/ABI/native hash/license checks only; no Android traffic claim'}
[IO.File]::WriteAllText((Join-Path $evidence 'optional-apk-static-gate.json'),($result|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
Write-Output 'PASS8 independent module APKs; all four base release APKs contain no optional worker/debug peer'
