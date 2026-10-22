param(
    [string]$WorkRoot='D:/MCRF/Work/Windows/csqtt25-20261021',
    [string]$ToolsRoot='D:/MCRF/Archive/Windows-original/work/toolchains',
    [string]$CargoHome='D:/MCRF/Projects/MCRF/work/toolchains/cargo-home',
    [string]$TargetRoot=''
)
$ErrorActionPreference='Stop'
if($args.Count){throw 'Unknown CSQTT Windows build arguments'}
$taskRoot=[IO.Path]::GetFullPath($WorkRoot)
if(!$taskRoot.StartsWith('D:\MCRF\Work\Windows\',[StringComparison]::OrdinalIgnoreCase)){throw 'Windows build root must be inside D:/MCRF/Work/Windows'}
$taskUpstream=Join-Path $taskRoot 'upstream'
$taskPin='d02da9bd9d8926969b766e197c434d830a83dbd8'
if(!(Test-Path -LiteralPath $taskUpstream)){throw 'Fetch the pinned pristine upstream source before building'}
if((& git -C $taskUpstream rev-parse HEAD) -ne $taskPin -or ((& git -C $taskUpstream status --porcelain | Out-String).Trim())){throw 'CSQTT upstream pin or pristine source mismatch'}
$taskStage=Join-Path $taskRoot ('build-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskStage | Out-Null
foreach($taskDir in @('rust-client','shared')){Copy-Item -LiteralPath (Join-Path $taskUpstream $taskDir) -Destination $taskStage -Recurse}
Copy-Item -LiteralPath (Join-Path $taskUpstream 'domains.txt') -Destination $taskStage
& git -C $taskStage apply (Join-Path $PSScriptRoot 'windows.patch')
if($LASTEXITCODE -ne 0){throw 'Pinned Windows startup patch does not apply'}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'mcrf_startup.rs') -Destination (Join-Path $taskStage 'rust-client/mcrf_startup.rs')
$taskCompiler=Join-Path $ToolsRoot 'rustup/toolchains/1.99.0-x86_64-pc-windows-gnullvm/bin'
$taskLlvm=Join-Path $ToolsRoot 'llvm-mingw/llvm-mingw-20260922-ucrt-x86_64/bin'
$taskCmake=Join-Path $ToolsRoot 'cmake/cmake-4.4.3-windows-x86_64/bin'
$taskNasm=Join-Path $ToolsRoot 'nasm/nasm-2.16.03'
$env:PATH="$taskCompiler;$taskLlvm;$taskCmake;$taskNasm;$(Join-Path $ToolsRoot 'ninja');$env:PATH"
$env:RUSTC=Join-Path $taskCompiler 'rustc.exe'
$env:CARGO_HOME=$CargoHome
$env:CARGO_TARGET_X86_64_PC_WINDOWS_GNULLVM_LINKER='x86_64-w64-mingw32-clang'
$env:CC='x86_64-w64-mingw32-clang';$env:CXX='x86_64-w64-mingw32-clang++';$env:AR='llvm-ar'
$env:CMAKE_GENERATOR='Ninja';$env:CARGO_HTTP_MULTIPLEXING='false'
$taskLog=Join-Path $taskStage 'build.log'
$taskTarget=Join-Path $taskStage 'target'
if($TargetRoot){$taskTarget=[IO.Path]::GetFullPath($TargetRoot);if(!$taskTarget.StartsWith('D:\MCRF\Work\Windows\',[StringComparison]::OrdinalIgnoreCase)){throw 'Reusable target must be Windows-task-owned'}}
Write-Output "CSQTT25_BUILD=$taskStage"
& (Join-Path $taskCompiler 'cargo.exe') build --locked --manifest-path (Join-Path $taskStage 'rust-client/Cargo.toml') --target x86_64-pc-windows-gnullvm --release --target-dir $taskTarget 2>&1 | Tee-Object -FilePath $taskLog
if($LASTEXITCODE -ne 0){throw "CSQTT 2.5 Windows build failed; retained log: $taskLog"}
$taskExe=Join-Path $taskStage 'csqtt25-client.exe'
Copy-Item -LiteralPath (Join-Path $taskTarget 'x86_64-pc-windows-gnullvm/release/client.exe') -Destination $taskExe
$taskUnwind=Join-Path $ToolsRoot 'llvm-mingw/llvm-mingw-20260922-ucrt-x86_64/bin/libunwind.dll'
if((Get-FileHash -LiteralPath $taskUnwind).Hash -ne '5DE310284E361C8D10A92E0A18F8B17D2D5A0DE1D091D9E3C0B92D86F9F6C7C2'){throw 'LLVM libunwind runtime hash changed'}
Copy-Item -LiteralPath $taskUnwind -Destination (Join-Path $taskStage 'libunwind.dll')
Get-FileHash -LiteralPath $taskExe
