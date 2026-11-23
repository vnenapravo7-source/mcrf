param([switch]$Android,[switch]$TestPeer,[string]$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018',[switch]$Isolated,[switch]$X64Only)
$ErrorActionPreference='Stop'
if($X64Only -and (!$Android -or !$Isolated)){throw 'X64Only requires isolated Android verification'}
$taskAbis=if($X64Only){@('x86_64')}else{@('arm64-v8a','armeabi-v7a','x86_64')}
$taskAndroidRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskProject=[IO.Path]::GetFullPath((Join-Path $taskAndroidRoot '../..'))
$taskSocksOverlay=Join-Path $taskAndroidRoot 'transports/socks-owner/overlay.py'
$taskGo=Join-Path $taskProject 'work/toolchains/go1.26.8/go/bin/go.exe'
$env:GOPATH=Join-Path $taskProject 'work/toolchains/go-home'
$taskAttempt=Join-Path $PSScriptRoot ('build/attempt-'+[guid]::NewGuid().ToString('N'))
$env:GOCACHE=Join-Path $PSScriptRoot 'build/go-cache'
New-Item -ItemType Directory -Force "$taskAttempt/transports","$taskAttempt/optional-cores/build" | Out-Null
foreach($taskKind in @('xray','tls-core')) {Copy-Item -LiteralPath "$taskAndroidRoot/transports/$taskKind" -Destination "$taskAttempt/transports/$taskKind" -Recurse}
Copy-Item -LiteralPath "$PSScriptRoot/bridge" -Destination "$taskAttempt/optional-cores/bridge" -Recurse
$taskOldPath=$env:PATH
$taskModules=@{}
$taskFiles=@()
try {
    $env:GOOS='windows';$env:GOARCH='amd64';$env:CGO_ENABLED='0'
    Push-Location "$taskAttempt/transports/tls-core"
    $taskUpstream=(& $taskGo mod download -json github.com/throneproj/sing-box@v1.11.16-0.20261003011458-ebe0747b4182 | ConvertFrom-Json)
    if($LASTEXITCODE -ne 0){throw 'Pinned TLS source download failed'}
    $taskOverlay=Join-Path $taskAttempt 'sing-box-overlay'
    $taskPatch=(& python "$PSScriptRoot/prepare_overlay.py" $taskUpstream.Dir $taskOverlay | ConvertFrom-Json)
    if($LASTEXITCODE -ne 0){throw 'Pinned TLS overlay failed'}
    $taskTlsSocks=(& python -B $taskSocksOverlay tls $taskOverlay | ConvertFrom-Json)
    if($LASTEXITCODE -ne 0 -or $taskTlsSocks.contract -ne 1){throw 'Pinned TCP-only TLS ingress overlay failed'}
    $taskPatchHash=$taskPatch.patched_sha256
    $taskModFile=Join-Path $taskAttempt 'tls-overlay.mod'
    $taskMod=Get-Content go.mod -Raw
    $taskMod=$taskMod.Replace('replace github.com/sagernet/sing-box => github.com/throneproj/sing-box v1.11.16-0.20261003011458-ebe0747b4182',"replace github.com/sagernet/sing-box => $($taskOverlay.Replace('\','/'))")
    $taskMetaOverlay=([string]$taskPatch.metacubex_private).Replace('\','/')
    $taskMod+="`nreplace github.com/metacubex/utls => $taskMetaOverlay`n"
    [IO.File]::WriteAllText($taskModFile,$taskMod)
    Copy-Item go.sum (Join-Path $taskAttempt 'tls-overlay.sum')
    Pop-Location
    Push-Location "$taskAttempt/transports/xray"
    $taskXrayUpstream=(& $taskGo mod download -json github.com/throneproj/xray-core@v1.251015.1-0.20260926005000-e662c22ab109 | ConvertFrom-Json)
    if($LASTEXITCODE -ne 0){throw 'Pinned Xray source download failed'}
    $taskXrayOverlay=Join-Path $taskAttempt 'xray-overlay'
    $taskXrayPatchHash=& python "$PSScriptRoot/prepare_xray_overlay.py" $taskXrayUpstream.Dir $taskXrayOverlay
    if($LASTEXITCODE -ne 0 -or $taskXrayPatchHash -cnotmatch '^[0-9a-f]{64}$'){throw 'Pinned Xray dialer overlay failed'}
    $taskXraySocks=(& python -B $taskSocksOverlay xray $taskXrayOverlay | ConvertFrom-Json)
    if($LASTEXITCODE -ne 0 -or $taskXraySocks.contract -ne 1){throw 'Pinned Xray SOCKS UDP source overlay failed'}
    $taskXrayModFile=Join-Path $taskAttempt 'xray-overlay.mod'
    $taskXrayMod=Get-Content go.mod -Raw
    $taskXrayPin='replace github.com/xtls/xray-core => github.com/throneproj/xray-core v1.251015.1-0.20260926005000-e662c22ab109'
    if(!$taskXrayMod.Contains($taskXrayPin)){throw 'Pinned Xray replacement differs'}
    $taskXrayMod=$taskXrayMod.Replace($taskXrayPin,"replace github.com/xtls/xray-core => $($taskXrayOverlay.Replace('\','/'))")
    [IO.File]::WriteAllText($taskXrayModFile,$taskXrayMod)
    Copy-Item go.sum (Join-Path $taskAttempt 'xray-overlay.sum')
    Pop-Location
    Push-Location "$taskAttempt/optional-cores/bridge"
    & $taskGo test -count=1 -v . 2>&1 | Tee-Object -FilePath "$taskAttempt/bridge-tests.txt"
    if($LASTEXITCODE -ne 0){throw 'Private bridge tests failed'}
    Pop-Location
    foreach($taskKind in @('xray','tls-core')) {
        Push-Location "$taskAttempt/transports/$taskKind"
        try {
            $taskExtra=@();$taskTags=@();$taskLd='-s -w -checklinkname=0'
            if($taskKind -eq 'tls-core'){$taskExtra=@("-modfile=$taskModFile");$taskTags=@('-tags=with_utls,with_purego');$taskLd+=" -X main.fragmentPatch=$taskPatchHash -X main.socksIngressPatch=tcp-only-v1"}
            else {$taskExtra=@("-modfile=$taskXrayModFile");$taskLd+=' -X main.physicalDialerPatch=fail-closed-v1 -X main.socksIngressPatch=exact-udp-source-v1'}
            & $taskGo mod verify @taskExtra
            if($LASTEXITCODE -ne 0){throw "Dependency checksums failed: $taskKind"}
            $taskHost="$taskAttempt/optional-cores/build/$taskKind.exe"
            & $taskGo build @taskExtra @taskTags -trimpath "-ldflags=$taskLd" -o $taskHost .
            if($LASTEXITCODE -ne 0){throw "Host build failed: $taskKind"}
            if($TestPeer -and $taskKind -eq 'xray'){
                & $taskGo build @taskExtra -trimpath "-ldflags=$taskLd" -o "$taskAttempt/optional-fixture.exe" ./testpeer
                if($LASTEXITCODE -ne 0){throw 'Host feature peer build failed'}
            }
            & $taskGo test @taskExtra @taskTags '-ldflags=-checklinkname=0' -count=1 -v . 2>&1 | Tee-Object -FilePath "$taskAttempt/$taskKind-tests.txt"
            if($LASTEXITCODE -ne 0){throw "Host validation failed: $taskKind"}
            if($taskKind -eq 'xray') {
                & $taskGo test @taskExtra '-ldflags=-checklinkname=0' -run '^TestMcrfControllerRejectionStopsTCPAndUDP$' -count=1 -v github.com/xtls/xray-core/transport/internet 2>&1 | Tee-Object -FilePath "$taskAttempt/xray-controller-tests.txt"
                if($LASTEXITCODE -ne 0){throw 'Xray socket-control regression failed'}
            }
            if($taskKind -eq 'tls-core') {
                & $taskGo test @taskExtra @taskTags '-ldflags=-checklinkname=0' -run '^TestMCRF' -count=1 -v github.com/sagernet/sing-box/common/dialer 2>&1 | Tee-Object -FilePath "$taskAttempt/fragment-tests.txt"
                if($LASTEXITCODE -ne 0){throw 'Fragment wire contract/timing regression failed'}
                & $taskGo test @taskExtra @taskTags '-ldflags=-checklinkname=0' -run '^TestMCRFTLSHybridRetry49$' -count=1 -v github.com/sagernet/sing-box/common/tls 2>&1 | Tee-Object -FilePath "$taskAttempt/tls-hybrid-retry-tests.txt"
                if($LASTEXITCODE -ne 0){throw 'TLS hybrid HelloRetryRequest regression failed'}
            }
            $taskModules[$taskKind]=(& $taskHost --mcrf-describe | ConvertFrom-Json)
            if($LASTEXITCODE -ne 0 -or $taskModules[$taskKind].api -ne 1){throw 'Descriptor validation failed'}
            if($Android) {
                $taskCompiler=Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
                if(!(Test-Path -LiteralPath "$taskCompiler/clang.exe")){throw 'NDK missing'}
                $env:PATH=$taskCompiler+';'+$taskOldPath;$env:GOOS='android';$env:CGO_ENABLED='1'
                foreach($taskAbi in $taskAbis) {
                    Remove-Item Env:GOARM -ErrorAction SilentlyContinue
                    switch($taskAbi){'arm64-v8a'{$env:GOARCH='arm64';$taskTriple='aarch64-linux-android29'}'armeabi-v7a'{$env:GOARCH='arm';$env:GOARM='7';$taskTriple='armv7a-linux-androideabi29'}'x86_64'{$env:GOARCH='amd64';$taskTriple='x86_64-linux-android29'}}
                    $env:CC="clang --target=$taskTriple"
                    $taskOutput="$taskAttempt/candidates/$taskKind/$taskAbi/lib$($taskKind.Replace('-','_')).so"
                    New-Item -ItemType Directory -Force (Split-Path $taskOutput) | Out-Null
                    & $taskGo build @taskExtra @taskTags -trimpath -buildmode=pie "-ldflags=$taskLd -linkmode=external -extldflags=-Wl,-z,max-page-size=16384" -o $taskOutput .
                    if($LASTEXITCODE -ne 0){throw "Android candidate build failed: $taskKind/$taskAbi"}
                    if($TestPeer -and $taskKind -eq 'xray'){
                        $taskPeerOutput="$taskAttempt/fixtures/$taskAbi/liboptional_fixture.so"
                        New-Item -ItemType Directory -Force (Split-Path $taskPeerOutput)|Out-Null
                        & $taskGo build @taskExtra -trimpath -buildmode=pie "-ldflags=$taskLd -linkmode=external -extldflags=-Wl,-z,max-page-size=16384" -o $taskPeerOutput ./testpeer
                        if($LASTEXITCODE -ne 0){throw "Android feature peer build failed: $taskAbi"}
                    }
                    $taskFiles+=@{kind=$taskKind;abi=$taskAbi;file=$taskOutput;bytes=(Get-Item $taskOutput).Length;sha256=(Get-FileHash -Algorithm SHA256 $taskOutput).Hash.ToLowerInvariant()}
                    if($Isolated -and $taskAbi -eq 'x86_64') {
                        $taskRuntimeTests="$taskAttempt/runtime-tests/$taskKind.test"
                        New-Item -ItemType Directory -Force (Split-Path $taskRuntimeTests) | Out-Null
                        & $taskGo test -c @taskExtra @taskTags -trimpath "-ldflags=$taskLd -linkmode=external -extldflags=-Wl,-z,max-page-size=16384" -o $taskRuntimeTests .
                        if($LASTEXITCODE -ne 0){throw "Android runtime fixture build failed: $taskKind"}
                    }
                }
                $env:GOOS='windows';$env:GOARCH='amd64';$env:CGO_ENABLED='0';$env:PATH=$taskOldPath;Remove-Item Env:CC,Env:GOARM -ErrorAction SilentlyContinue
            }
        } finally {Pop-Location}
    }
    $taskReceipt=@{api=1;stage='host-tested-candidates-only';toolchain='go1.26.8';ndk=$Ndk;fragmentPatch=$taskPatchHash;xrayPhysicalDialerPatch=$taskXrayPatchHash;tlsPhysicalDialerPatch=$taskPatch.physical_dialer_sha256;tlsHybridRetryPatch=$taskPatch.metacubex_hrr_patched_sha256;descriptors=$taskModules;files=$taskFiles;androidDeviceTested=$false;signed=$false}
    $taskReceipt.socksUdpSource=@{xray=1;'tls-core'=1}
    $taskReceipt.socksUdpSourceOverlay=@{xray=$taskXraySocks;'tls-core'=$taskTlsSocks}
    $taskReceipt | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$taskAttempt/receipt.json" -Encoding utf8
    & python "$PSScriptRoot/verify_candidates.py" "$taskAttempt/receipt.json"
    if($LASTEXITCODE -ne 0){throw 'Candidate ELF identity/page-alignment check failed'}
    & python "$PSScriptRoot/verify_cli.py" $taskAttempt
    if($LASTEXITCODE -ne 0){throw 'Native CLI preflight/redaction check failed'}
    if(!$Isolated){@{attempt=$taskAttempt;receipt="$taskAttempt/receipt.json"}|ConvertTo-Json|Set-Content -LiteralPath "$PSScriptRoot/current-candidate.json" -Encoding utf8}
    Write-Output "Candidate receipt: $taskAttempt/receipt.json"
} finally {$env:PATH=$taskOldPath;Remove-Item Env:GOOS,Env:GOARCH,Env:GOARM,Env:CGO_ENABLED,Env:CC -ErrorAction SilentlyContinue}
