param([Parameter(Mandatory)][string]$Stage,
 [string]$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-build
$stagePath=[IO.Path]::GetFullPath($Stage)
if($stagePath -cnotmatch '^D:\\MCRF\\Builds\\Android\\csqtt25-negotiated-[0-9a-f]{32}$' -or (Get-Item -LiteralPath $stagePath).LinkType){throw 'Exact new owned CSQTT25 stage required'}
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
$receipt=Get-Content "$stagePath/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
$source=Join-Path $stagePath 'source'
function Assert-Source{
 if($receipt.source -cne $source -or $receipt.revision -cne 'd02da9bd9d8926969b766e197c434d830a83dbd8' -or
    $receipt.preparerSha256 -cne (Hash "$PSScriptRoot/Prepare-NegotiatedSource.ps1") -or
    $receipt.transformSha256 -cne (Hash "$PSScriptRoot/prepare-negotiated-workers.py") -or
    $receipt.transformSha256 -cne (Hash "$stagePath/prepare-negotiated-workers.py") -or
    $receipt.swgPatchSha256 -cne (Hash "$stagePath/swg-2.5.1.patch") -or
    $receipt.sourceManifestSha256 -cne (Hash "$stagePath/source-manifest.json") -or
    $receipt.negotiationSha256 -cne (Hash "$stagePath/negotiation.json") -or $receipt.workerNegotiationContract -ne 1 -or
    $receipt.serverTunTxQueueContract -ne 1){throw 'Negotiated Rust source provenance changed'}
 & python "$PSScriptRoot/prepare-negotiated-workers.py" --verify $source
 if($LASTEXITCODE){throw 'Frozen negotiated Rust source changed'}
}
if($receipt.state -cne 'SOURCE_PREPARED' -or !$receipt.sourceStable -or @($receipt.artifacts).Count){throw 'Native producer already started or source not frozen'}
Assert-Source
$rust='D:/MCRF/Builds/Tools/rust-1.97.1-gnu/toolchain'
$cargoNdk='D:/MCRF/Builds/Tools/cargo-ndk-4.1.2/bin'
$compiler="$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin"
$cmake='C:/Users/dieonfire/AppData/Local/Android/Sdk/cmake/3.22.1/bin'
foreach($tool in @("$rust/bin/cargo.exe","$rust/bin/rustc.exe","$cargoNdk/cargo-ndk.exe","$compiler/llvm-readelf.exe","$compiler/llvm-dlltool.exe","$cmake/cmake.exe","$cmake/ninja.exe")){if(!(Test-Path -LiteralPath $tool -PathType Leaf)){throw 'Existing Rust/NDK/CMake prerequisite missing'}}
$null=New-Item -ItemType Directory -Path "$stagePath/host-tools","$stagePath/tmp"
Copy-Item -LiteralPath "$compiler/llvm-dlltool.exe" -Destination "$stagePath/host-tools/dlltool.exe"
$names=@('PATH','RUSTC','CARGO_HOME','CARGO_TARGET_DIR','RUSTFLAGS','TEMP','TMP','ANDROID_NDK_HOME','ANDROID_NDK_ROOT','LIBCLANG_PATH','CMAKE_GENERATOR');$old=@{}
foreach($name in $names){$old[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
function Save{[IO.File]::WriteAllText("$stagePath/receipt.json",($receipt|ConvertTo-Json -Depth 9),[Text.UTF8Encoding]::new($false))}
$receipt.state='BUILDING_NATIVE';$receipt.sourceStable=$false;$receipt.producerSha256=Hash $PSCommandPath;Save
Push-Location "$source/rust-client"
try{
 $env:PATH="$stagePath/host-tools;$rust/bin;$rust/lib/rustlib/x86_64-pc-windows-gnu/bin;$rust/lib/rustlib/x86_64-pc-windows-gnu/bin/self-contained;$cargoNdk;$cmake;$compiler;"+$old.PATH
 $env:RUSTC="$rust/bin/rustc.exe";$env:CARGO_HOME='D:/MCRF/Projects/MCRF/work/toolchains/cargo-home'
 $env:CARGO_TARGET_DIR="$stagePath/target";$env:TEMP="$stagePath/tmp";$env:TMP=$env:TEMP
 $env:ANDROID_NDK_HOME=$Ndk;$env:ANDROID_NDK_ROOT=$Ndk;$env:LIBCLANG_PATH=$compiler;$env:CMAKE_GENERATOR='Ninja'
 $env:RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
 foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){
  Assert-Source;$receipt.currentABI=$abi;Save
  $triple=switch($abi){'arm64-v8a'{'aarch64-linux-android'}'armeabi-v7a'{'armv7-linux-androideabi'}'x86_64'{'x86_64-linux-android'}}
  & "$rust/bin/cargo.exe" ndk -t $abi -P 26 build --release --frozen *> "$stagePath/build-$abi.log"
  if($LASTEXITCODE){throw "Negotiated Rust build failed: $abi"}
  Assert-Source
  $null=New-Item -ItemType Directory -Path "$stagePath/$abi"
  $output=[IO.Path]::GetFullPath("$stagePath/$abi/libcsqtt25_transport.so")
  Copy-Item -LiteralPath "$env:CARGO_TARGET_DIR/$triple/release/client" -Destination $output
  $header=@(& "$compiler/llvm-readelf.exe" --file-header --program-headers $output)
  if($LASTEXITCODE -or !($header -match 'Type:.*DYN')){throw 'Rust transport is not PIE'}
  $loads=@($header|Where-Object {$_ -match '^\s+LOAD\s'})
  if(!$loads.Count -or @($loads|Where-Object {$_ -notmatch '\s0x4000\s*$'}).Count){throw 'Rust transport lacks 16KiB LOAD alignment'}
  $receipt.artifacts+=@{abi=$abi;file=$output;bytes=(Get-Item -LiteralPath $output).Length;sha256=Hash $output;executed=$false};Save
 }
 Assert-Source;Copy-Item -LiteralPath "$source/LICENSE" -Destination "$stagePath/CSQTT-LICENSE"
 $receipt.sourceStable=$true;$receipt.state='COMPILED_NOT_EXECUTED';$receipt.currentABI=$null;Save
 & python "$PSScriptRoot/prepare-negotiated-workers.py" --verify-built $source
 if($LASTEXITCODE){throw 'Negotiated Rust artifact closure failed'}
}catch{$receipt.state='FAILED_NATIVE_BUILD';$receipt.sourceStable=$false;Save;throw}
finally{Pop-Location;foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$old[$name],'Process')}}
Write-Output "CSQTT25_NEGOTIATED_NATIVE=$stagePath"
