param([ValidateSet('2.0','2.1.5')][string]$Generation,[string]$WorkRoot='D:/MCRF/Work/Windows/csqtt-generations-20261022',[switch]$Resume)
$ErrorActionPreference='Stop'
$pins=@{'2.0'='2ff8a1a4fbe031fb76b799e172dce25cfe1ca942';'2.1.5'='de7afc23cec7481f98845bf24fc8943af19b4cf8'}
$base=[IO.Path]::GetFullPath($WorkRoot);if(!$base.StartsWith('D:\MCRF\Work\Windows\',[StringComparison]::OrdinalIgnoreCase)){throw 'Windows-owned build root required'}
New-Item -ItemType Directory -Path $base -Force|Out-Null
$pin=$pins[$Generation];$archive=Join-Path $base ($pin+'.zip');$pristine=Join-Path $base ('csqtt-'+$pin)
if(!(Test-Path -LiteralPath $pristine)){if(!(Test-Path -LiteralPath $archive)){Invoke-WebRequest "https://codeload.github.com/amurcanov/csqtt/zip/$pin" -OutFile $archive};Expand-Archive -LiteralPath $archive -DestinationPath $base}
$stage=Join-Path $base ('stage-'+$Generation);if((Test-Path -LiteralPath $stage)-and !$Resume){throw 'Existing stage retained; resume cargo directly instead of replacing it'}
if(!$Resume){New-Item -ItemType Directory -Path $stage|Out-Null
foreach($name in @('rust-client','shared')){if(Test-Path -LiteralPath (Join-Path $pristine $name)){Copy-Item -LiteralPath (Join-Path $pristine $name) -Destination $stage -Recurse}}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'legacy_startup.rs') -Destination (Join-Path $stage 'rust-client/mcrf_startup.rs')
# Mechanical host-entry patch only, checked once against immutable upstream.
$main=Join-Path $stage 'rust-client/main.rs';$source=[IO.File]::ReadAllText($main);$needle='let arguments = Arguments::parse_from(normalized_arguments());';if(!$source.Contains($needle)){throw 'Upstream startup boundary changed'}
$source=$source.Replace($needle,'let arguments = match mcrf_startup::read() {Ok(value)=>value,Err(message)=>{eprintln!("{message}");std::process::exit(2)}};')
$source=$source.Replace('mod auth;',"mod mcrf_startup;`nmod auth;")
# Inner crate attributes must remain before module declarations.
$source=$source.Replace("mod mcrf_startup;`n#![allow(linker_messages)]","#![allow(linker_messages)]`nmod mcrf_startup;")
[IO.File]::WriteAllText($main,$source,[Text.UTF8Encoding]::new($false))
}
$tools='D:/MCRF/Archive/Windows-original/work/toolchains';$compiler=Join-Path $tools 'rustup/toolchains/1.99.0-x86_64-pc-windows-gnullvm/bin';$llvm=Join-Path $tools 'llvm-mingw/llvm-mingw-20260922-ucrt-x86_64/bin'
$env:PATH="$compiler;$llvm;$(Join-Path $tools 'cmake/cmake-4.4.3-windows-x86_64/bin');$(Join-Path $tools 'ninja');$(Join-Path $tools 'nasm/nasm-2.16.03');$env:PATH"
$env:RUSTC=Join-Path $compiler 'rustc.exe';$env:CARGO_HOME=Join-Path $tools 'cargo';$env:CARGO_TARGET_X86_64_PC_WINDOWS_GNULLVM_LINKER='x86_64-w64-mingw32-clang';$env:CC='x86_64-w64-mingw32-clang';$env:CXX='x86_64-w64-mingw32-clang++';$env:AR='llvm-ar';$env:CMAKE_GENERATOR='Ninja'
if(!(Test-Path -LiteralPath (Join-Path $stage 'rust-client/Cargo.lock'))){& (Join-Path $compiler 'cargo.exe') generate-lockfile --manifest-path (Join-Path $stage 'rust-client/Cargo.toml');if($LASTEXITCODE){throw 'Resolve legacy dependencies before locked build'}}
& (Join-Path $compiler 'cargo.exe') build --locked --manifest-path (Join-Path $stage 'rust-client/Cargo.toml') --target x86_64-pc-windows-gnullvm --release --target-dir (Join-Path $base 'target') 2>&1|Tee-Object -FilePath (Join-Path $stage 'build.log')
if($LASTEXITCODE){throw 'Pinned legacy Windows build failed; preserve stage and log'}
$name=if($Generation -eq '2.0'){'csqtt20-client.exe'}else{'csqtt215-client.exe'};Copy-Item -LiteralPath (Join-Path $base 'target/x86_64-pc-windows-gnullvm/release/client.exe') -Destination (Join-Path $stage $name)
Copy-Item -LiteralPath (Join-Path $llvm 'libunwind.dll') -Destination $stage
Write-Output "CSQTT_GENERATION_BUILD=$stage"
