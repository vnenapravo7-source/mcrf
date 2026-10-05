param([switch]$LocalRegistry)
$ErrorActionPreference='Stop'
$project=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$toolchains=Join-Path $project 'work/toolchains'
$env:RUSTUP_HOME=Join-Path $toolchains 'rustup'
$env:CARGO_HOME=Join-Path $toolchains 'cargo'
$llvm=(Get-ChildItem (Join-Path $toolchains 'llvm-mingw') -Directory).FullName
$cmake=(Get-ChildItem (Join-Path $toolchains 'cmake') -Directory).FullName
$nasm=(Get-ChildItem (Join-Path $toolchains 'nasm') -Directory).FullName
$env:PATH="$cmake\bin;$llvm\bin;$nasm;$(Join-Path $toolchains 'ninja');$env:PATH"
$env:CARGO_TARGET_X86_64_PC_WINDOWS_GNULLVM_LINKER='x86_64-w64-mingw32-clang'
$env:CC='x86_64-w64-mingw32-clang'
$env:CXX='x86_64-w64-mingw32-clang++'
$env:AR='llvm-ar'
$env:CMAKE_GENERATOR='Ninja'
$env:AWS_LC_SYS_NO_ASM='0'
$env:CARGO_HTTP_MULTIPLEXING='false'
$cargoArguments=@('+1.99.0-x86_64-pc-windows-gnullvm')
if($LocalRegistry){$cargoArguments+=@('--config',"source.crates-io.replace-with='verified-local'",'--config',"source.verified-local.registry='sparse+http://127.0.0.1:19389/index/'")}
$cargoArguments+=@('build','--locked','--manifest-path',(Join-Path $PSScriptRoot 'csqtt-bridge/Cargo.toml'),'--target','x86_64-pc-windows-gnullvm','--release','--target-dir',(Join-Path $project 'work/turn-engines/rust-target'))
& (Join-Path $toolchains 'cargo/bin/cargo.exe') @cargoArguments
if($LASTEXITCODE -ne 0){throw 'CSQTT Windows build failed'}
Copy-Item -LiteralPath (Join-Path $project 'work/turn-engines/rust-target/x86_64-pc-windows-gnullvm/release/csqtt-bridge.exe') -Destination (Join-Path $project 'work/turn-engines/csqtt-client.exe')
$env:GOCACHE=Join-Path $toolchains 'go-build-cache'
$env:GOMODCACHE=Join-Path $toolchains 'go-cache'
$env:CGO_ENABLED='0'
$env:GOOS='windows'
$env:GOARCH='amd64'
Push-Location (Join-Path $project 'work/turn-engines/WDTT-Plus/Ivan4537-WDTT-Plus-68bc4af/go_client')
try{
 & (Join-Path $toolchains 'go1.26.8/go/bin/go.exe') build -trimpath -ldflags '-s -w' -o (Join-Path $project 'work/turn-engines/wdtt-client.exe') .
 if($LASTEXITCODE -ne 0){throw 'WDTT Windows build failed'}
}finally{Pop-Location}
