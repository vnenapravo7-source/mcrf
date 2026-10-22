param([string]$ResumeStage='')
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$taskTools='D:/MCRF/Builds/Tools/rust-1.97.1-gnu'
$taskToolReceipt=Get-Content -LiteralPath (Join-Path $taskTools 'receipt.json') -Raw | ConvertFrom-Json
if($taskToolReceipt.state -ne 'READY_PORTABLE'){throw 'Resume the existing Rust producer; do not start another setup'}
$taskPrefix=Join-Path $taskTools 'toolchain'
if($ResumeStage){
    $taskStage=[IO.Path]::GetFullPath($ResumeStage)
    $taskAllowed=[IO.Path]::GetFullPath('D:/MCRF/Builds/Android')
    if(!$taskStage.StartsWith($taskAllowed+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($taskStage) -notmatch '^csqtt-waiters-host-[0-9a-f]{32}$' -or (Get-Item -LiteralPath $taskStage).LinkType){throw 'Unexpected owned waiter stage'}
    $taskPrevious=Get-Content -LiteralPath (Join-Path $taskStage 'receipt.json') -Raw | ConvertFrom-Json
    if($taskPrevious.state -ne 'FAILED'){throw 'Only a finished failed check can resume after an actual prerequisite correction'}
}else{
$taskStage=Join-Path 'D:/MCRF/Builds/Android' ('csqtt-waiters-host-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $taskStage 'src') | Out-Null
New-Item -ItemType Directory -Path (Join-Path $taskStage 'tmp') | Out-Null
$taskWaiterSource=Join-Path $PSScriptRoot 'mcrf_captcha_waiters.rs'
$taskWaiterHash=(Get-FileHash -LiteralPath $taskWaiterSource -Algorithm SHA256).Hash.ToLowerInvariant()
Copy-Item -LiteralPath $taskWaiterSource -Destination (Join-Path $taskStage 'src/lib.rs')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'csqtt-waiters-host/Cargo.toml') -Destination (Join-Path $taskStage 'Cargo.toml')
}
if($ResumeStage){$taskWaiterHash=$taskPrevious.sourceSha256}
if((Get-FileHash -LiteralPath (Join-Path $taskStage 'src/lib.rs') -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskWaiterHash){throw 'Rust source capture changed'}
$taskReceipt=[ordered]@{state='ACTIVE';sourceSha256=$taskWaiterHash;sourceStable=$false;stage=$taskStage;toolchain=$taskPrefix;scope='Only independent waiter registry host fixtures; no supplied native/client/provider/Android execution';apkBuilt=$false}
if($ResumeStage){$taskReceipt.priorFailure='Initial36244 ended1: bundled dlltool absent from process PATH. Corrected-path run ended1: GNU dlltool needs an assembler absent from minimal bundle. Both logs preserved; use existing NDK LLVM-dlltool through the documented rustc option, no RNG/proof/guard fallback.'}
function Save-WaiterReceipt {[IO.File]::WriteAllText((Join-Path $taskStage 'receipt.json'),($taskReceipt|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))}
Save-WaiterReceipt
Write-Output "CSQTT_WAITER_HOST_STAGE=$taskStage"
$taskSavedEnv=@{}
foreach($taskKey in @('PATH','RUSTC','CARGO_HOME','CARGO_TARGET_DIR','RUSTFLAGS','TEMP','TMP')){$taskSavedEnv[$taskKey]=[Environment]::GetEnvironmentVariable($taskKey,'Process')}
try{
    $env:PATH=(Join-Path $taskPrefix 'bin')+';'+(Join-Path $taskPrefix 'lib/rustlib/x86_64-pc-windows-gnu/bin')+';'+(Join-Path $taskPrefix 'lib/rustlib/x86_64-pc-windows-gnu/bin/self-contained')+';'+$taskSavedEnv.PATH
    $env:RUSTC=Join-Path $taskPrefix 'bin/rustc.exe'
    $env:CARGO_HOME=Join-Path $taskRoot 'work/toolchains/cargo-home'
    $env:CARGO_TARGET_DIR=Join-Path $taskStage 'target'
    $taskDlltool='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-dlltool.exe'
    if(!(Test-Path -LiteralPath $taskDlltool -PathType Leaf)){throw 'Existing NDK LLVM-dlltool required for Windows GNU raw-dylib imports'}
    $env:RUSTFLAGS="-C dlltool=$taskDlltool"
    $env:TEMP=Join-Path $taskStage 'tmp';$env:TMP=$env:TEMP
    $taskLog=if($ResumeStage){'host-tests-corrected-'+[guid]::NewGuid().ToString('N')+'.log'}else{'host-tests.log'}
    $taskReceipt.currentLog=$taskLog;Save-WaiterReceipt
    & (Join-Path $taskPrefix 'bin/cargo.exe') test --manifest-path (Join-Path $taskStage 'Cargo.toml') -- --nocapture 2>&1 | Tee-Object -FilePath (Join-Path $taskStage $taskLog)
    if($LASTEXITCODE -ne 0){throw 'Independent native waiter host checks failed'}
    if((Get-FileHash -LiteralPath (Join-Path $taskStage 'src/lib.rs') -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskWaiterHash){throw 'Frozen waiter source changed during host checks'}
    $taskReceipt.sourceStable=$true;$taskReceipt.state='HOST_CHECKS_PASSED';Save-WaiterReceipt
}catch{$taskReceipt.state='FAILED';Save-WaiterReceipt;throw}
finally{foreach($taskKey in $taskSavedEnv.Keys){[Environment]::SetEnvironmentVariable($taskKey,$taskSavedEnv[$taskKey],'Process')}}
