param([switch]$HostTest, [switch]$HostBinary,[switch]$Linux,[ValidateSet('linux-x64','linux-arm64')][string]$Runtime='linux-x64')
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$taskCommit='cc6b7928e7306509bdeefbb6e0e29f2a982bdb48'
$taskPristine=Join-Path $taskRoot 'work/android-upstream/telegram-proxy'
if(!(Test-Path -LiteralPath $taskPristine)) {
    & git clone --filter=blob:none --no-checkout https://github.com/y0sy4/telegram-proxy.git $taskPristine
    if($LASTEXITCODE -ne 0){throw 'Telegram source fetch failed'}
    & git -C $taskPristine checkout --detach $taskCommit
    if($LASTEXITCODE -ne 0){throw 'Telegram source checkout failed'}
}
if((& git -C $taskPristine rev-parse HEAD) -ne $taskCommit -or ((& git -C $taskPristine status --porcelain | Out-String).Trim())) {throw 'Telegram pristine source revision mismatch or local changes'}
$taskSource=Join-Path $taskRoot 'work/ports-test-2026-10-14/telegram-mobile-build'
New-Item -ItemType Directory -Path $taskSource -Force | Out-Null
Get-ChildItem -LiteralPath $taskPristine -Force | Where-Object Name -ne '.git' | Copy-Item -Destination $taskSource -Recurse -Force
$taskGo=Join-Path $taskRoot 'work/toolchains/go1.26.8/go/bin/go.exe'
$env:GOPATH=Join-Path $taskRoot 'work/toolchains/go-home'
$taskOldPath=$env:PATH
Push-Location $taskSource
try {
    & git apply --recount --unidiff-zero --ignore-space-change (Join-Path $PSScriptRoot 'mobile.patch')
    if($LASTEXITCODE -ne 0){throw 'Telegram mobile patch failed'}
    & git apply (Join-Path $PSScriptRoot 'diagnostics.patch')
    if($LASTEXITCODE -ne 0){throw 'Telegram diagnostics patch failed'}
    New-Item -ItemType Directory -Path 'cmd/mcrf' -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'main.go') -Destination 'cmd/mcrf/main.go' -Force
    New-Item -ItemType Directory -Path 'internal/mcrfnet' -Force | Out-Null
    Copy-Item -Path (Join-Path $PSScriptRoot 'net_*.go') -Destination 'internal/mcrfnet' -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'wire_test.go') -Destination 'internal/mtproto/mcrf_wire_test.go' -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'socks_test.go') -Destination 'internal/socks5/socks5_test.go' -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'websocket_test.go') -Destination 'internal/websocket/mcrf_mobile_test.go' -Force
    Remove-Item Env:GOOS,Env:GOARCH,Env:GOARM,Env:CC -ErrorAction SilentlyContinue
    $env:CGO_ENABLED='0'
    & $taskGo mod download; if($LASTEXITCODE -ne 0){throw 'Telegram dependencies failed'}
    & $taskGo mod verify; if($LASTEXITCODE -ne 0){throw 'Telegram dependency checksums failed'}
    if($HostTest) { & $taskGo test -timeout 45s ./internal/...; if($LASTEXITCODE -ne 0){throw 'Telegram host tests failed'}; return }
    if($HostBinary){
        $taskOutput=Join-Path $taskRoot 'work/ports-test-2026-10-14/telegram-host/tgws.exe'
        New-Item -ItemType Directory -Path (Split-Path $taskOutput) -Force | Out-Null
        & $taskGo build -trimpath '-ldflags=-s -w' -o $taskOutput ./cmd/mcrf
        if($LASTEXITCODE -ne 0){throw 'Telegram host build failed'};return
    }
    if($Linux) {
        $env:GOOS='linux';$env:GOARCH=if($Runtime -eq 'linux-arm64'){'arm64'}else{'amd64'}
        $taskOutput=Join-Path $taskRoot "work/ports-test-2026-10-14/telegram-linux/$Runtime/tgws"
        New-Item -ItemType Directory -Path (Split-Path $taskOutput) -Force | Out-Null
        & $taskGo build -trimpath '-ldflags=-s -w' -o $taskOutput ./cmd/mcrf
        if($LASTEXITCODE -ne 0){throw 'Telegram Linux build failed'}
    }else {
        $env:PATH=(Join-Path $taskRoot 'work/toolchains/ndk-r27/android-ndk-r27/toolchains/llvm/prebuilt/windows-x86_64/bin')+';'+$taskOldPath
        $env:GOOS='android';$env:CGO_ENABLED='1'
        foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')) {
            switch($taskAbi) {'arm64-v8a'{$env:GOARCH='arm64';$taskTriple='aarch64-linux-android29'} 'armeabi-v7a'{$env:GOARCH='arm';$env:GOARM='7';$taskTriple='armv7a-linux-androideabi29'} 'x86_64'{$env:GOARCH='amd64';$taskTriple='x86_64-linux-android29'}}
            $env:CC="clang --target=$taskTriple"
            $taskOutput=Join-Path $taskRoot "ports/android/app/src/main/jniLibs/$taskAbi/libtgws.so"
            New-Item -ItemType Directory -Path (Split-Path $taskOutput) -Force | Out-Null
            & $taskGo build -trimpath -buildmode=pie '-ldflags=-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o $taskOutput ./cmd/mcrf
            if($LASTEXITCODE -ne 0){throw "Telegram Android build failed: $taskAbi"}
        }
        Copy-Item -LiteralPath (Join-Path $taskPristine 'LICENSE') -Destination (Join-Path $taskRoot 'ports/android/app/src/main/assets/licenses/tgws-LICENSE') -Force
        Copy-Item -LiteralPath (Join-Path $env:GOPATH 'pkg/mod/golang.org/x/net@v0.52.0/LICENSE') -Destination (Join-Path $taskRoot 'ports/android/app/src/main/assets/licenses/tgws-x-net-LICENSE') -Force
        Copy-Item -LiteralPath (Join-Path $taskRoot 'work/toolchains/go1.26.8/go/LICENSE') -Destination (Join-Path $taskRoot 'ports/android/app/src/main/assets/licenses/tgws-go-LICENSE') -Force
    }
}finally {Pop-Location;$env:PATH=$taskOldPath;Remove-Item Env:GOOS,Env:GOARCH,Env:GOARM,Env:CGO_ENABLED,Env:CC -ErrorAction SilentlyContinue}
