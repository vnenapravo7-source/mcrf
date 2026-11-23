param([switch]$HostTest,[switch]$HostBinary,[switch]$Linux,[ValidateSet('linux-x64','linux-arm64')][string]$Runtime='linux-x64')
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$taskCommit='d35fa797e702cc1e930701568c184aa99459f692';$taskPristine=Join-Path $taskRoot 'work/android-upstream/warpscout-android'
if(!(Test-Path -LiteralPath $taskPristine)){
 & git clone --filter=blob:none --no-checkout https://github.com/openwarpkit/warpscout-android.git $taskPristine
 if($LASTEXITCODE -ne 0){throw 'WarpScout source fetch failed'}
 & git -C $taskPristine checkout --detach android-v1.3.1
 if($LASTEXITCODE -ne 0){throw 'WarpScout source checkout failed'}
}
if((& git -C $taskPristine rev-parse HEAD) -ne $taskCommit -or ((& git -C $taskPristine status --porcelain | Out-String).Trim())){throw 'WarpScout source revision mismatch or local edits'}
$taskSource=Join-Path $taskRoot 'work/ports-test-2026-10-14/warp-mobile-build'
New-Item -ItemType Directory -Path $taskSource -Force | Out-Null
Get-ChildItem -LiteralPath $taskPristine -Force | Where-Object Name -ne '.git' | Copy-Item -Destination $taskSource -Recurse -Force
$taskGo=Join-Path $taskRoot 'work/toolchains/go1.26.8/go/bin/go.exe';$env:GOPATH=Join-Path $taskRoot 'work/toolchains/go-home';$taskOldPath=$env:PATH
Push-Location $taskSource
try{
 & git apply --recount --unidiff-zero --ignore-space-change (Join-Path $PSScriptRoot 'mobile.patch');if($LASTEXITCODE -ne 0){throw 'WarpScout patch failed'}
 New-Item -ItemType Directory -Path 'cmd/mcrf' -Force | Out-Null
 Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'main.go'),(Join-Path $PSScriptRoot 'main_test.go') -Destination 'cmd/mcrf' -Force
 Copy-Item -Path (Join-Path $PSScriptRoot 'network_*.go') -Destination 'internal/warpscout' -Force
 Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'i1.go') -Destination 'internal/warpscout/mcrf_i1.go' -Force
 Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'mcrf_fast.go'),(Join-Path $PSScriptRoot 'mcrf_fast_test.go') -Destination 'internal/warpscout' -Force
 Remove-Item Env:GOOS,Env:GOARCH,Env:GOARM,Env:CC -ErrorAction SilentlyContinue;$env:CGO_ENABLED='0'
 & $taskGo mod download;if($LASTEXITCODE -ne 0){throw 'WarpScout dependency download failed'}
 & $taskGo mod verify;if($LASTEXITCODE -ne 0){throw 'WarpScout dependency checksums failed'}
 if($HostTest){& $taskGo test '-ldflags=-checklinkname=0' -timeout 60s ./cmd/mcrf ./core ./mobileapi ./internal/warpscout;if($LASTEXITCODE -ne 0){throw 'WarpScout tests failed'};return}
 if($HostBinary -or $Linux){
    $taskOutput=Join-Path $taskRoot 'work/ports-test-2026-10-14/warp-host/warpscout.exe'
    if($Linux){$env:GOOS='linux';$env:GOARCH=if($Runtime -eq 'linux-arm64'){'arm64'}else{'amd64'};$taskOutput=Join-Path $taskRoot "work/ports-test-2026-10-14/warp-linux/$Runtime/warpscout"}
    New-Item -ItemType Directory -Path (Split-Path $taskOutput) -Force | Out-Null
    & $taskGo build -trimpath '-ldflags=-s -w -checklinkname=0' -o $taskOutput ./cmd/mcrf;if($LASTEXITCODE -ne 0){throw 'WarpScout CLI build failed'};return
 }
 $env:PATH=(Join-Path $taskRoot 'work/toolchains/ndk-r27/android-ndk-r27/toolchains/llvm/prebuilt/windows-x86_64/bin')+';'+$taskOldPath;$env:GOOS='android';$env:CGO_ENABLED='1'
 foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
  switch($taskAbi){'arm64-v8a'{$env:GOARCH='arm64';$taskTriple='aarch64-linux-android29'}'armeabi-v7a'{$env:GOARCH='arm';$env:GOARM='7';$taskTriple='armv7a-linux-androideabi29'}'x86_64'{$env:GOARCH='amd64';$taskTriple='x86_64-linux-android29'}}
  $env:CC="clang --target=$taskTriple";$taskOutput=Join-Path $taskRoot "ports/android/engines/src/main/jniLibs/$taskAbi/libwarpscout.so"
  & $taskGo build -trimpath -buildmode=pie '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o $taskOutput ./cmd/mcrf
  if($LASTEXITCODE -ne 0){throw "WarpScout Android build failed: $taskAbi"}
 }
 Copy-Item -LiteralPath (Join-Path $taskPristine 'LICENSE') -Destination (Join-Path $taskRoot 'ports/android/engines/src/main/assets/licenses/warpscout-LICENSE') -Force
}finally{Pop-Location;$env:PATH=$taskOldPath;Remove-Item Env:GOOS,Env:GOARCH,Env:GOARM,Env:CGO_ENABLED,Env:CC -ErrorAction SilentlyContinue}
