$ErrorActionPreference='Stop'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation build
$root=[IO.Path]::GetFullPath("$PSScriptRoot/../../..")
$source="$root/ports/android/transports/swg/mcrf_historical_tunnel.rs"
$stage="D:/MCRF/Builds/Android/historical-tunnel-checks-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $stage|Out-Null
$rust='D:/MCRF/Builds/Tools/rust-1.97.1-gnu/toolchain'
$bin="$rust/lib/rustlib/x86_64-pc-windows-gnu/bin"
$oldPath=$env:PATH
$receipt=[ordered]@{state='PREPARING';source=$source;sourceSha256=(Get-FileHash $source).Hash.ToLowerInvariant();cases=0;executed=$false}
try{
    $env:PATH="$rust/bin;$bin;$bin/self-contained;"+$oldPath
    & "$rust/bin/rustc.exe" --edition=2021 --test $source -C "linker=$bin/self-contained/x86_64-w64-mingw32-gcc.exe" -o "$stage/checks.exe" *> "$stage/compile.log"
    if($LASTEXITCODE){throw 'Historical local-event adapter test compilation failed'}
    & "$stage/checks.exe" *> "$stage/test.log";$code=$LASTEXITCODE
    $output=[IO.File]::ReadAllText("$stage/test.log")
    if($code -ne 0 -or $output -notmatch 'test result: ok\. 4 passed; 0 failed; 0 ignored'){throw 'Four new local-event adapter cases must pass'}
    if((Get-FileHash $source).Hash.ToLowerInvariant() -cne $receipt.sourceSha256){throw 'Historical event source changed during test'}
    $receipt.state='PASS';$receipt.cases=4;$receipt.executed=$true
    $receipt.logSha256=(Get-FileHash "$stage/test.log").Hash.ToLowerInvariant()
    Write-Output 'HISTORICAL_TUNNEL_NEW=4'
}catch{$receipt.state='FAILED_PRESERVED';throw}
finally{
    $env:PATH=$oldPath
    [IO.File]::WriteAllText("$stage/receipt.json",($receipt|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
    Write-Output "HISTORICAL_TUNNEL_CHECK_STAGE=$stage"
}
