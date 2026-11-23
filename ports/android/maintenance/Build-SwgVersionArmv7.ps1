param([Parameter(Mandatory)][string]$UpstreamStage,[Parameter(Mandatory)][string]$UpstreamReceiptSha256,
      [Parameter(Mandatory)][string]$SourceRepository,
      [Parameter(Mandatory)][string]$OutputDirectory,
      [string]$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018')
$ErrorActionPreference='Stop'
function Hash([string]$Path){$item=Get-Item -LiteralPath $Path;if($item.LinkType -or $item.PSIsContainer){throw 'Regular source input required'};(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
if((Hash "$UpstreamStage/receipt.json") -cne $UpstreamReceiptSha256){throw 'Pinned upstream input stage required'}
& py -3.14 "$PSScriptRoot/../transports/swg/csqtt25/versioned_inputs.py" --verify $UpstreamStage
if($LASTEXITCODE){throw 'Upstream stage verification failed'}
if(Test-Path $OutputDirectory){throw 'Fresh aligned source/native stage required'}
& py -3.14 "$PSScriptRoot/prepare_swg_armv7.py" --repo $SourceRepository --upstream $UpstreamStage --sha256 $UpstreamReceiptSha256 --output $OutputDirectory
if($LASTEXITCODE){throw 'Canonical release source recovery failed'}
$rows=@(Get-Content "$OutputDirectory/source-manifest.json" -Raw|ConvertFrom-Json)
$sourceProofSha256=Hash "$OutputDirectory/source-proof.json"
function Assert-Source {
 if((Hash "$UpstreamStage/receipt.json") -cne $UpstreamReceiptSha256 -or (Hash "$OutputDirectory/source-proof.json") -cne $sourceProofSha256){throw 'Source/upstream receipt changed'}
 $actual=@(Get-ChildItem "$OutputDirectory/source" -Recurse -File)
 if($actual.Count -ne $rows.Count){throw 'Aligned source inventory changed'}
 foreach($row in $rows){if([IO.Path]::IsPathRooted($row.path) -or '..' -in ($row.path -split '/') -or (Hash "$OutputDirectory/source/$($row.path)") -cne $row.sha256){throw 'Aligned source differs from verified upstream'}}
}
Assert-Source
$rust='D:/MCRF/Builds/Tools/rust-1.97.1-gnu/toolchain';$cargoNdk='D:/MCRF/Builds/Tools/cargo-ndk-4.1.2/bin'
$compiler="$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin";$cmake='C:/Users/dieonfire/AppData/Local/Android/Sdk/cmake/3.22.1/bin'
New-Item -ItemType Directory -Path "$OutputDirectory/host-tools","$OutputDirectory/tmp"|Out-Null
Copy-Item "$compiler/llvm-dlltool.exe" "$OutputDirectory/host-tools/dlltool.exe"
$names=@('PATH','RUSTC','CARGO_HOME','CARGO_TARGET_DIR','RUSTFLAGS','TEMP','TMP','ANDROID_NDK_HOME','ANDROID_NDK_ROOT','LIBCLANG_PATH','CMAKE_GENERATOR');$old=@{}
foreach($name in $names){$old[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$receipt=@{state='BUILDING';kind='csqtt252-armv7-aligned';upstreamReceiptSha256=$UpstreamReceiptSha256;sourceProofSha256=$sourceProofSha256;sourceManifestSha256=Hash "$OutputDirectory/source-manifest.json";producerSha256=Hash $PSCommandPath;toolchainSha256=Hash "$rust/bin/rustc.exe";sourceStable=$false;nativeExecuted=$false;artifacts=@()}
try {
 $env:PATH="$OutputDirectory/host-tools;$rust/bin;$rust/lib/rustlib/x86_64-pc-windows-gnu/bin;$rust/lib/rustlib/x86_64-pc-windows-gnu/bin/self-contained;$cargoNdk;$cmake;$compiler;"+$old.PATH
 $env:RUSTC="$rust/bin/rustc.exe";$env:CARGO_HOME='D:/MCRF/Projects/MCRF/work/toolchains/cargo-home'
 $env:CARGO_TARGET_DIR="$OutputDirectory/target";$env:TEMP="$OutputDirectory/tmp";$env:TMP=$env:TEMP
 $env:ANDROID_NDK_HOME=$Ndk;$env:ANDROID_NDK_ROOT=$Ndk;$env:LIBCLANG_PATH=$compiler;$env:CMAKE_GENERATOR='Ninja'
 $env:RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
 Push-Location "$OutputDirectory/source/rust-client"
 try {& "$rust/bin/cargo.exe" ndk -t armeabi-v7a -P 26 build --release --frozen *> "$OutputDirectory/build-armeabi-v7a.log";if($LASTEXITCODE){throw 'Aligned ARMv7 Rust build failed'}}finally{Pop-Location}
 Assert-Source
 New-Item -ItemType Directory -Path "$OutputDirectory/armeabi-v7a"|Out-Null
 $native="$OutputDirectory/armeabi-v7a/libcsqtt252_transport.so"
 Copy-Item "$OutputDirectory/target/armv7-linux-androideabi/release/client" $native
 & py -3.14 -c 'import sys; sys.path.insert(0,sys.argv[2]); from bs_engine_inputs import elf; elf(sys.argv[1],"armeabi-v7a")' $native "$PSScriptRoot/../transports/swg"
 if($LASTEXITCODE){throw 'Aligned ARMv7 ELF verification failed'}
 $receipt.artifacts=@(@{abi='armeabi-v7a';file='armeabi-v7a/libcsqtt252_transport.so';bytes=(Get-Item $native).Length;sha256=Hash $native;elfChecked=$true;nativeExecuted=$false})
 Assert-Source;$receipt.sourceStable=$true;$receipt.state='COMPILED_NOT_EXECUTED'
}catch{$receipt.state='FAILED_PRESERVED';$receipt.error=$_.Exception.Message;throw}
finally{foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$old[$name],'Process')};Save $receipt "$OutputDirectory/receipt.json"}
