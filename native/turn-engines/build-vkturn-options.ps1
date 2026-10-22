param([string]$WorkRoot='D:/MCRF/Work/Windows/vkturn-options-20261022',[switch]$LegacyOnly)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath($WorkRoot)
if(!$taskRoot.StartsWith('D:\MCRF\Work\Windows\',[StringComparison]::OrdinalIgnoreCase)){throw 'Windows-owned work root required'}
New-Item -ItemType Directory -Path $taskRoot -Force|Out-Null
$taskTools='D:/MCRF/Archive/Windows-original/work/toolchains'
$taskCompiler=Join-Path $taskTools 'rustup/toolchains/1.99.0-x86_64-pc-windows-gnullvm/bin'
$taskLlvm=Join-Path $taskTools 'llvm-mingw/llvm-mingw-20260922-ucrt-x86_64/bin'
$env:PATH="$taskCompiler;$taskLlvm;$(Join-Path $taskTools 'cmake/cmake-4.4.3-windows-x86_64/bin');$(Join-Path $taskTools 'ninja');$(Join-Path $taskTools 'nasm/nasm-2.16.03');$env:PATH"
$env:RUSTC=Join-Path $taskCompiler 'rustc.exe';$env:CARGO_HOME=Join-Path $taskTools 'cargo';$env:CARGO_TARGET_X86_64_PC_WINDOWS_GNULLVM_LINKER='x86_64-w64-mingw32-clang';$env:CC='x86_64-w64-mingw32-clang';$env:CXX='x86_64-w64-mingw32-clang++';$env:AR='llvm-ar';$env:CMAKE_GENERATOR='Ninja'
foreach($taskGeneration in @('2.0','2.1.5')){
 $taskStage='D:/MCRF/Work/Windows/csqtt-generations-20261022/stage-'+$taskGeneration
 Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'csqtt-generations/legacy_startup.rs') -Destination (Join-Path $taskStage 'rust-client/mcrf_startup.rs')
 & (Join-Path $taskCompiler 'cargo.exe') build --locked --manifest-path (Join-Path $taskStage 'rust-client/Cargo.toml') --target x86_64-pc-windows-gnullvm --release --target-dir 'D:/MCRF/Work/Windows/csqtt-generations-20261022/target' 2>&1 | Tee-Object -FilePath (Join-Path $taskRoot ('build-'+$taskGeneration+'.log')) | Select-Object -Last 3
 if($LASTEXITCODE){throw "Legacy build failed: $taskGeneration"}
 $taskName=if($taskGeneration -eq '2.0'){'csqtt20-client.exe'}else{'csqtt215-client.exe'}
 Copy-Item -LiteralPath 'D:/MCRF/Work/Windows/csqtt-generations-20261022/target/x86_64-pc-windows-gnullvm/release/client.exe' -Destination (Join-Path $taskRoot $taskName)
}
if($LegacyOnly){return}
$taskBridge=Join-Path $taskRoot 'csqtt-bridge';New-Item -ItemType Directory -Path (Join-Path $taskBridge 'src') -Force|Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'csqtt-bridge/src/main.rs') -Destination (Join-Path $taskBridge 'src/main.rs')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'csqtt-bridge/Cargo.lock') -Destination $taskBridge
$taskBackend=Join-Path $taskRoot 'portable-backend';if(!(Test-Path -LiteralPath $taskBackend)){Copy-Item -LiteralPath 'D:/MCRF/Archive/Windows-original/work/turn-engines/focsq/luminescq-focsq-bfa176f/backend' -Destination $taskBackend -Recurse}
& git -C $taskBackend apply --unidiff-zero (Join-Path $PSScriptRoot 'portable-turn-options.patch');if($LASTEXITCODE){throw 'Portable TURN patch mismatch'}
$taskManifest=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'csqtt-bridge/Cargo.toml')).Replace('../../../work/turn-engines/focsq/luminescq-focsq-bfa176f/backend',$taskBackend.Replace('\','/'))
[IO.File]::WriteAllText((Join-Path $taskBridge 'Cargo.toml'),$taskManifest,[Text.UTF8Encoding]::new($false))
& (Join-Path $taskCompiler 'cargo.exe') build --locked --manifest-path (Join-Path $taskBridge 'Cargo.toml') --target x86_64-pc-windows-gnullvm --release --target-dir 'D:/MCRF/Work/Windows/vkturn-workers-20261022/target21' 2>&1 | Tee-Object -FilePath (Join-Path $taskRoot 'build-2.1.9.log') | Select-Object -Last 3
if($LASTEXITCODE){throw 'CSQTT 2.1.9 build failed'}
Copy-Item -LiteralPath 'D:/MCRF/Work/Windows/vkturn-workers-20261022/target21/x86_64-pc-windows-gnullvm/release/csqtt-bridge.exe' -Destination (Join-Path $taskRoot 'csqtt-client.exe')
& (Join-Path $PSScriptRoot 'csqtt25/build.ps1') -CargoHome (Join-Path $taskTools 'cargo') -TargetRoot 'D:/MCRF/Work/Windows/csqtt25-20261021/build-42b55816d58b4cf2bb796cb02253f36f/target' 2>&1 | Tee-Object -FilePath (Join-Path $taskRoot 'build-2.5.log') | Select-Object -Last 4
if($LASTEXITCODE){throw 'CSQTT 2.5 build failed'}
$taskBuildLine=Select-String -LiteralPath (Join-Path $taskRoot 'build-2.5.log') -Pattern '^CSQTT25_BUILD=' | Select-Object -First 1
$task25=$taskBuildLine.Line.Substring('CSQTT25_BUILD='.Length)
Copy-Item -LiteralPath (Join-Path $task25 'csqtt25-client.exe') -Destination (Join-Path $taskRoot 'csqtt25-client.exe')
$taskQ=Join-Path $taskRoot 'qwdtt';if(Test-Path -LiteralPath $taskQ){throw 'QWDTT stage exists; preserve it and resume the documented command'}
Copy-Item -LiteralPath 'D:/MCRF/Work/Windows/qwdtt-raw-20261022/go_client' -Destination $taskQ -Recurse
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'qwdtt-fingerprint.go') -Destination (Join-Path $taskQ 'mcrf_fingerprint.go')
foreach($taskFile in @('creds.go','creds_vkcalls.go')){$taskPath=Join-Path $taskQ $taskFile;$taskSource=[IO.File]::ReadAllText($taskPath);if(!$taskSource.Contains('profiles.Chrome_146')){throw 'QWDTT TLS anchor changed'};$taskSource=$taskSource.Replace('profiles.Chrome_146','mcrfTLSProfile()').Replace('"github.com/bogdanfinn/tls-client/profiles"','');[IO.File]::WriteAllText($taskPath,$taskSource,[Text.UTF8Encoding]::new($false))}
$taskPath=Join-Path $taskQ 'main.go';$taskSource=[IO.File]::ReadAllText($taskPath);if(!$taskSource.Contains('maxWorkers := 108')-or !$taskSource.Contains('flag.Parse()')){throw 'QWDTT startup anchors changed'};$taskSource=$taskSource.Replace('maxWorkers := 108','maxWorkers := 162').Replace('flag.Parse()','flag.Parse(); mcrfSetFingerprint()').Replace('TLS: Chrome 146 fingerprint','TLS: configured browser fingerprint');[IO.File]::WriteAllText($taskPath,$taskSource,[Text.UTF8Encoding]::new($false))
$env:CGO_ENABLED='0';$env:GOOS='windows';$env:GOARCH='amd64';$env:GOMODCACHE=Join-Path $taskTools 'go-cache';$env:GOCACHE='D:/MCRF/Work/Windows/csqtt-netstack-go-cache'
$taskSource=[IO.File]::ReadAllText($taskPath).Replace('activeConnMode :=','mcrfValidateOnly(map[string]interface{}{"obfs":*obfsMode,"fingerprint":*mcrfFingerprint,"turn_tcp":*turnTCP,"dns":*goDNS,"auth":*vkAnonPath,"captcha":*captchaMode,"workers":*numW}); activeConnMode :=');[IO.File]::WriteAllText($taskPath,$taskSource,[Text.UTF8Encoding]::new($false))
Push-Location $taskQ
try{& (Join-Path $taskTools 'go1.26.8/go/bin/go.exe') build -mod=readonly -trimpath -ldflags '-s -w -H=windowsgui' -o (Join-Path $taskRoot 'qwdtt-client.exe') . 2>&1 | Tee-Object -FilePath (Join-Path $taskRoot 'build-qwdtt.log');if($LASTEXITCODE){throw 'QWDTT options build failed'}}finally{Pop-Location}
Get-ChildItem -LiteralPath $taskRoot -Filter '*client.exe'|ForEach-Object { '{0} {1}' -f $_.Name,(Get-FileHash -LiteralPath $_.FullName).Hash }
