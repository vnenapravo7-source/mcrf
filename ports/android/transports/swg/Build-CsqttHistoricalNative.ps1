param([Parameter(Mandatory=$true)][string]$Stage,
      [string]$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018')
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot '../../maintenance/Assert-HumanBatchReady.ps1') -Operation build
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$taskStage=[IO.Path]::GetFullPath($Stage)
$taskAllowed=[IO.Path]::GetFullPath('D:/MCRF/Builds/Android')
if(!$taskStage.StartsWith($taskAllowed+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($taskStage) -notmatch '^csqtt-historical-[0-9a-f]{32}$' -or (Get-Item -LiteralPath $taskStage).LinkType){throw 'Use the exact owned native source stage'}
$taskReceipt=Get-Content -LiteralPath (Join-Path $taskStage 'receipt.json') -Raw | ConvertFrom-Json -AsHashtable
if($taskReceipt.state -ne 'SOURCE_PREPARED'){throw 'Native producer already started or source is not prepared; inspect existing process/receipt'}
$taskSource=Join-Path $taskStage 'source'
$taskInputs=Get-Content -LiteralPath (Join-Path $taskStage 'source-manifest.json') -Raw | ConvertFrom-Json
function Assert-FrozenCsqttSource {
    $generation=[string]$taskReceipt.generation
    $revision=if($generation -ceq '2.0'){'2ff8a1a4fbe031fb76b799e172dce25cfe1ca942'}elseif($generation -ceq '2.1.5'){'de7afc23cec7481f98845bf24fc8943af19b4cf8'}else{throw 'Unknown historical generation'}
    if($taskReceipt.revision -cne $revision -or
       (Get-FileHash -LiteralPath (Join-Path $taskStage 'prepare-csqtt-historical.py')).Hash.ToLowerInvariant() -cne $taskReceipt.adapterSha256 -or
       (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'prepare-csqtt-historical.py')).Hash.ToLowerInvariant() -cne $taskReceipt.adapterSha256){throw 'Frozen historical generation/adapter identity changed'}
    foreach($taskInput in $taskInputs){
        $taskFile=[IO.Path]::GetFullPath((Join-Path $taskSource $taskInput.path))
        if(!$taskFile.StartsWith($taskSource+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
           (Get-Item -LiteralPath $taskFile).LinkType -or (Get-FileHash -LiteralPath $taskFile -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskInput.sha256){throw 'Frozen native input changed'}
    }
}
Assert-FrozenCsqttSource
$taskRust='D:/MCRF/Builds/Tools/rust-1.97.1-gnu/toolchain'
$taskNdkTool='D:/MCRF/Builds/Tools/cargo-ndk-4.1.2/bin'
$taskCompiler=Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$taskCmake='C:/Users/dieonfire/AppData/Local/Android/Sdk/cmake/3.22.1/bin'
foreach($taskTool in @("$taskRust/bin/rustc.exe","$taskNdkTool/cargo-ndk.exe","$taskCompiler/llvm-readelf.exe","$taskCompiler/llvm-dlltool.exe","$taskCmake/cmake.exe","$taskCmake/ninja.exe")){if(!(Test-Path -LiteralPath $taskTool -PathType Leaf)){throw 'Existing portable compiler/Android NDK/CMake prerequisite missing'}}
# rustc may build host raw-dylib dependencies while cross-compiling. The minimal
# bundled GNU dlltool needs an assembler; use the existing NDK LLVM implementation
# under its standard tool name in a PRIVATE PATH, not the SDK/system directories.
$taskHostTools=Join-Path $taskRust 'mcrf-host-tools'
if(!(Test-Path -LiteralPath $taskHostTools)){New-Item -ItemType Directory -Path $taskHostTools | Out-Null}
$taskDlltool=Join-Path $taskHostTools 'dlltool.exe'
$taskDlltoolHash=(Get-FileHash -LiteralPath "$taskCompiler/llvm-dlltool.exe" -Algorithm SHA256).Hash
if(Test-Path -LiteralPath $taskDlltool){if((Get-FileHash -LiteralPath $taskDlltool -Algorithm SHA256).Hash -ne $taskDlltoolHash){throw 'Private host tool identity changed'}}else{Copy-Item -LiteralPath "$taskCompiler/llvm-dlltool.exe" -Destination $taskDlltool}
$taskReceipt.state='BUILDING_NATIVE';$taskReceipt.currentABI=$null;$taskReceipt.transportOnly=$true
function Save-NativeWaiterReceipt {[IO.File]::WriteAllText((Join-Path $taskStage 'receipt.json'),($taskReceipt|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))}
Save-NativeWaiterReceipt
$taskSavedEnv=@{}
foreach($taskKey in @('PATH','RUSTC','CARGO_HOME','CARGO_TARGET_DIR','RUSTFLAGS','TEMP','TMP','ANDROID_NDK_HOME','ANDROID_NDK_ROOT','LIBCLANG_PATH','CMAKE_GENERATOR')){$taskSavedEnv[$taskKey]=[Environment]::GetEnvironmentVariable($taskKey,'Process')}
New-Item -ItemType Directory -Path (Join-Path $taskStage 'tmp') -Force | Out-Null
Push-Location (Join-Path $taskSource 'rust-client')
try{
    $env:PATH="$taskHostTools;$taskRust/bin;$taskRust/lib/rustlib/x86_64-pc-windows-gnu/bin;$taskRust/lib/rustlib/x86_64-pc-windows-gnu/bin/self-contained;$taskNdkTool;$taskCmake;$taskCompiler;"+$taskSavedEnv.PATH
    $env:RUSTC="$taskRust/bin/rustc.exe";$env:CARGO_HOME=Join-Path $taskRoot 'work/toolchains/cargo-home'
    $env:CARGO_TARGET_DIR=Join-Path $taskStage 'target';$env:TEMP=Join-Path $taskStage 'tmp';$env:TMP=$env:TEMP
    $env:ANDROID_NDK_HOME=$Ndk;$env:ANDROID_NDK_ROOT=$Ndk;$env:LIBCLANG_PATH=$taskCompiler;$env:CMAKE_GENERATOR='Ninja'
    $env:RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
    foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
        $taskReceipt.currentABI=$taskAbi;Save-NativeWaiterReceipt
        $taskTarget=switch($taskAbi){'arm64-v8a'{'aarch64-linux-android'}'armeabi-v7a'{'armv7-linux-androideabi'}'x86_64'{'x86_64-linux-android'}}
        & "$taskRust/bin/cargo.exe" ndk -t $taskAbi -P 26 build --release --locked 2>&1 | Tee-Object -FilePath (Join-Path $taskStage "build-$taskAbi.log")
        if($LASTEXITCODE -ne 0){throw "Historical native build failed: $taskAbi. Inspect this finished producer, never duplicate a still-running step."}
        Assert-FrozenCsqttSource
        $taskOutputDirectory=Join-Path $taskStage "candidates/$taskAbi";New-Item -ItemType Directory -Path $taskOutputDirectory -Force | Out-Null
        $taskOutput=Join-Path $taskOutputDirectory 'libcsqtt_transport.so'
        Copy-Item -LiteralPath (Join-Path $env:CARGO_TARGET_DIR "$taskTarget/release/client") -Destination $taskOutput
        $taskHeader=@(& "$taskCompiler/llvm-readelf.exe" --file-header --program-headers $taskOutput)
        if($LASTEXITCODE -ne 0 -or !($taskHeader -match 'Type:.*DYN')){throw 'Native output is not a PIE executable'}
        $taskLoads=@($taskHeader | Where-Object {$_ -match '^\s+LOAD\s'})
        if(!$taskLoads.Count -or @($taskLoads|Where-Object {$_ -notmatch '\s0x4000\s*$'}).Count){throw 'Native output lacks verified16KiB LOAD alignment'}
        $taskReceipt.artifacts+=@{abi=$taskAbi;file=$taskOutput;bytes=(Get-Item -LiteralPath $taskOutput).Length;sha256=(Get-FileHash -LiteralPath $taskOutput -Algorithm SHA256).Hash.ToLowerInvariant();elfPIE=$true;alignment=16384}
        Save-NativeWaiterReceipt
        Write-Output "CSQTT_HISTORICAL_NATIVE_COMPILED=$taskAbi"
    }
    Copy-Item -LiteralPath (Join-Path $taskSource 'LICENSE') -Destination (Join-Path $taskStage 'CSQTT-LICENSE')
    $taskReceipt.sourceStable=$true;$taskReceipt.state='COMPILED_NOT_EXECUTED';$taskReceipt.currentABI=$null;Save-NativeWaiterReceipt
}catch{$taskReceipt.state='FAILED_NATIVE_BUILD';Save-NativeWaiterReceipt;throw}
finally{Pop-Location;foreach($taskKey in $taskSavedEnv.Keys){[Environment]::SetEnvironmentVariable($taskKey,$taskSavedEnv[$taskKey],'Process')}}
