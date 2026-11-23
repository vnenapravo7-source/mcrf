param(
    [string]$SourceRoot='',
    [string]$AndroidSdk="$env:LOCALAPPDATA/Android/Sdk",
    [string]$Ndk="$env:LOCALAPPDATA/Android/Sdk/ndk/28.2.13676358",
    [string]$JavaHome="$env:ProgramFiles/Java/jdk-17",
    [string]$RawOverlaySha256='',
    [string]$RawEvidenceRoot='',
    [ValidateSet('p3','p4')][string]$RawOverlayKind='p3',
    [ValidateRange(1,1000)][int]$CarrierStackRevision=1
)
$ErrorActionPreference='Stop'
if($CarrierStackRevision -ne 1 -and $RawOverlayKind -ne 'p4'){throw 'Carrier revision is only valid for the p4 native stack'}
$taskWorkspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
if(!$SourceRoot){$SourceRoot=Join-Path $taskWorkspace 'work/android-upstream/sing-box-lx-mcrf-clash-20261014'}
$SourceRoot=[IO.Path]::GetFullPath($SourceRoot)
$taskCommit='3d3d3db3a2ccef3b1a8eb71b831ed2461c54b94d'
if((& git -C $SourceRoot rev-parse HEAD).Trim() -ne $taskCommit){throw 'Unexpected libbox source commit'}
if([bool]$RawOverlaySha256 -ne [bool]$RawEvidenceRoot){throw 'Raw candidate requires both pinned overlay SHA and executed evidence root'}
if($RawOverlayKind -ne 'p3' -and !$RawOverlaySha256){throw 'Explicit candidate requires its pinned source/evidence receipt'}
$taskRawProof=$null
$taskRawVerifier=Join-Path $PSScriptRoot 'raw-overlay.py'
$taskRawVerifierSha=if($RawOverlaySha256){(Get-FileHash -LiteralPath $taskRawVerifier).Hash.ToLowerInvariant()}else{''}
function Assert-RawOverlay([string]$CandidateSource=$SourceRoot) {
    if((Get-FileHash -LiteralPath $taskRawVerifier).Hash.ToLowerInvariant() -cne $taskRawVerifierSha){throw 'Overlay verifier changed during native build'}
    $proof=& python $taskRawVerifier verify --source $CandidateSource --sha256 $RawOverlaySha256 --evidence $RawEvidenceRoot --overlay-kind $RawOverlayKind
    if($LASTEXITCODE){throw 'Recorded native overlay/source/evidence verification failed'}
    return $proof|ConvertFrom-Json
}
if($RawOverlaySha256){$taskRawProof=Assert-RawOverlay}
elseif((& git -C $SourceRoot diff --name-only --ignore-submodules=all)){throw 'Core tracked files changed; refuse unrecorded build'}
$taskSubmodules=@{
    'submodules/gvisor'='117243aa02fa2915cb53d1d4549bfca3a3ebf238'
    'submodules/sing-tun'='6f56eca9cb2e81d1c233b6104618ff30500e9f39'
    'submodules/utls'='59e89bb121d8cc7a0a1c87a930864122b5685d00'
    'submodules/wireguard-go'='64065e6826ad82aa28429dd829c1c65f29b7f6c9'
}
foreach($entry in $taskSubmodules.GetEnumerator()){
    $sub=Join-Path $SourceRoot $entry.Key
    if((& git -C $sub rev-parse HEAD).Trim() -ne $entry.Value){throw "Unexpected submodule $($entry.Key)"}
    if((& git -C $sub diff --name-only)){throw "Modified native submodule $($entry.Key)"}
}
$taskGoBin=Join-Path $taskWorkspace 'work/toolchains/go1.26.8/go/bin'
$taskMobileBin=Join-Path $taskWorkspace 'work/toolchains/gomobile-v0.1.13'
if((& (Join-Path $taskGoBin 'go.exe') version) -notmatch 'go1\.26\.8 windows/amd64'){throw 'Wrong Go toolchain'}
foreach($file in @((Join-Path $taskMobileBin 'gomobile.exe'),(Join-Path $taskMobileBin 'gobind.exe'),(Join-Path $Ndk 'source.properties'),(Join-Path $JavaHome 'bin/javac.exe'))){if(!(Test-Path -LiteralPath $file)){throw "Missing build prerequisite: $file"}}
if((Get-Content -LiteralPath (Join-Path $Ndk 'source.properties') -Raw) -notmatch 'Pkg.Revision\s*=\s*28\.2\.13676358'){throw 'Pinned NDK r28c is required for the upstream Cronet relocation format'}
$taskOutput=Join-Path $taskWorkspace ('work/android-core-clash-build-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskOutput | Out-Null
$taskTags='with_gvisor,with_quic,with_wireguard,with_utls,with_naive_outbound,badlinkname,tfogo_checklinkname0,with_xhttp,with_awg,with_lx_command,with_lx_chain,with_lx_idle_suspend,with_openvpn,with_openconnect,with_tailscale,ts_omit_logtail,ts_omit_ssh,ts_omit_drive,ts_omit_taildrop,ts_omit_webclient,ts_omit_doctor,ts_omit_capture,ts_omit_kube,ts_omit_aws,ts_omit_synology,ts_omit_bird,with_clash_api'
$taskVersion='1.14.2-lx.11-mcrf-clash.3.socks1.owner2'
if($taskRawProof){
    $taskTags+=','+($taskRawProof.tags -join ',')
    $taskVersion=if($RawOverlayKind -eq 'p4'){"1.14.2-lx.11-mcrf-carrier-stack.$CarrierStackRevision.socks1.owner2"}else{'1.14.2-lx.11-mcrf-raw-port.3.socks1.owner2'}
}
$taskOwnerProducer=Join-Path $PSScriptRoot 'owner-overlay.py'
$taskAarMerger=Join-Path $PSScriptRoot 'merge-owner-aars.py'
$taskSocksProducer=Join-Path $PSScriptRoot '../transports/socks-owner/overlay.py'
$taskSingVersion='v0.9.7-0.20260929150544-0ad23b637bd4'
$taskOwnerProof=& python -B $taskOwnerProducer $SourceRoot $taskOutput
if($LASTEXITCODE){throw 'Android VPN owner admission overlay could not be staged'}
$taskOwnerSources=@(($taskOwnerProof|ConvertFrom-Json -AsHashtable).Keys)
$taskCompileSource=Join-Path $taskOutput 'source'
$taskSingSource=Join-Path $taskOutput 'sing-source'
$taskTemp=Join-Path $taskOutput 'tmp'
$taskGoCache=Join-Path $taskWorkspace 'work/toolchains/go-cache-primary'
New-Item -ItemType Directory -Path $taskTemp,$taskGoCache -Force | Out-Null
$taskEnvironment=@('PATH','GOPATH','GOTOOLCHAIN','JAVA_HOME','ANDROID_HOME','ANDROID_NDK_HOME','GOFLAGS','GOCACHE','GOTMPDIR','TMP','TEMP')
$taskSavedEnvironment=@{}
foreach($name in $taskEnvironment){$taskSavedEnvironment[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$taskReceipt=[ordered]@{
    state='building';repository='Leadaxe/sing-box-lx';commit=$taskCommit;submodules=$taskSubmodules
    go='1.26.8';gomobile='github.com/sagernet/gomobile v0.1.13';java=$JavaHome;ndk=$Ndk
    ndk_note='Pinned upstream NDK r28c; validate ELF16KiB alignment and runtime separately. Adding Clash changes the distributed native binary; no byte-identical upstream claim.'
    androidApi=24;targets=@('arm','arm64','amd64');tags=$taskTags;sourceModified=$true
    version=$taskVersion;promoted=$false;productionIntegration=$false
    rawOverlay=$taskRawProof;rawVerifierSha256=$taskRawVerifierSha;producerSha256=(Get-FileHash -LiteralPath $PSCommandPath).Hash.ToLowerInvariant()
    vpnOwnerIsolation=($taskOwnerProof|ConvertFrom-Json)
    aarMergerSha256=(Get-FileHash -LiteralPath $taskAarMerger).Hash.ToLowerInvariant()
    socksProducerSha256=(Get-FileHash -LiteralPath $taskSocksProducer).Hash.ToLowerInvariant()
    goModSha256=(Get-FileHash -LiteralPath (Join-Path $SourceRoot 'go.mod')).Hash.ToLowerInvariant()
    goSumSha256=(Get-FileHash -LiteralPath (Join-Path $SourceRoot 'go.sum')).Hash.ToLowerInvariant()
}
$taskReceiptPath=Join-Path $taskOutput 'receipt.json'
function Save-TaskReceipt { [IO.File]::WriteAllText($taskReceiptPath,($taskReceipt|ConvertTo-Json -Depth 5),(New-Object Text.UTF8Encoding($false))) }
Save-TaskReceipt
Write-Output "CORE_CANDIDATE=$taskOutput"
$taskLocationPushed=$false
function Copy-TaskCheckout([string]$original,[string]$target,[string]$commit) {
    & git -c core.longpaths=true clone --quiet --shared --no-checkout -- $original $target
    if($LASTEXITCODE){throw "Pinned source clone failed: $commit"}
    & git -C $target -c core.longpaths=true checkout --quiet --detach $commit
    if($LASTEXITCODE){throw "Pinned source checkout failed: $commit"}
}
function Assert-TaskOwnerSources {
    foreach($name in $taskOwnerSources){
        $path=Join-Path $taskCompileSource $name
        if((Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -cne $taskReceipt.vpnOwnerIsolation.$name){
            throw "Compiled Android owner admission source changed: $name"
        }
    }
}
function Get-TaskSingSources {
    @(Get-ChildItem -LiteralPath $taskSingSource -File -Recurse | Sort-Object FullName | ForEach-Object {
        if($_.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked Sing client input refused'}
        [pscustomobject]@{file=[IO.Path]::GetRelativePath($taskSingSource,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
    })
}
function Protect-TaskSingAuthLogs([string]$relative,[string]$sourceSha,[hashtable]$replacements) {
    $path=Join-Path $taskSingSource $relative
    if((Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -cne $sourceSha){throw "Pinned Sing authentication source changed: $relative"}
    $content=[IO.File]::ReadAllText($path)
    foreach($entry in $replacements.GetEnumerator()){
        if($content.Split([string[]]@($entry.Key),[StringSplitOptions]::None).Count -ne 2){throw "Pinned Sing authentication diagnostic changed: $relative"}
        $content=$content.Replace($entry.Key,$entry.Value)
    }
    [IO.File]::WriteAllText($path,$content,[Text.UTF8Encoding]::new($false))
}
try {
    # Gobind analyzes Go source files directly. Use a real isolated checkout so
    # its AST sees exactly the same interface as the Go type checker.
    Copy-TaskCheckout $SourceRoot $taskCompileSource $taskCommit
    foreach($entry in $taskSubmodules.GetEnumerator()){
        Copy-TaskCheckout (Join-Path $SourceRoot $entry.Key) (Join-Path $taskCompileSource $entry.Key) $entry.Value
    }
    if($taskRawProof){
        $null=Assert-RawOverlay
        foreach($line in (& git -C $SourceRoot -c core.quotepath=false status --porcelain --untracked-files=all)){
            if($line -cnotmatch '^( M|\?\?) [A-Za-z0-9_.\-/]+$'){throw 'Unexpected raw candidate path'}
            $relative=$line.Substring(3)
            $target=Join-Path $taskCompileSource $relative
            New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($target)) -Force|Out-Null
            Copy-Item -LiteralPath (Join-Path $SourceRoot $relative) -Destination $target -Force
        }
        $null=Assert-RawOverlay $taskCompileSource
    }
    foreach($name in $taskOwnerSources){
        Copy-Item -LiteralPath (Join-Path $taskOutput "owner-sources/$name") -Destination (Join-Path $taskCompileSource $name) -Force
    }
    Assert-TaskOwnerSources
    Push-Location $taskCompileSource
    $taskLocationPushed=$true
    $env:PATH=$taskGoBin+';'+$taskMobileBin+';'+(Join-Path $JavaHome 'bin')+';'+$env:PATH
    $env:GOPATH=Join-Path $taskWorkspace 'work/toolchains/go-home'
    $env:GOTOOLCHAIN='local'
    $env:JAVA_HOME=$JavaHome
    $env:ANDROID_HOME=$AndroidSdk
    $env:ANDROID_NDK_HOME=$Ndk
    $env:GOFLAGS='-p=4'
    $env:GOCACHE=$taskGoCache
    $env:GOTMPDIR=$taskTemp
    $env:TMP=$taskTemp
    $env:TEMP=$taskTemp
    & (Join-Path $taskGoBin 'go.exe') mod verify
    if($LASTEXITCODE -ne 0){throw 'Module integrity check failed'}
    $taskSing=& (Join-Path $taskGoBin 'go.exe') mod download -json "github.com/sagernet/sing@$taskSingVersion" | ConvertFrom-Json
    if($LASTEXITCODE -or $taskSing.Version -cne $taskSingVersion -or !$taskSing.Dir -or !$taskSing.Sum){throw 'Pinned Sing client unavailable'}
    if(Get-ChildItem -LiteralPath $taskSing.Dir -Recurse | Where-Object {$_.Attributes -band [IO.FileAttributes]::ReparsePoint}){throw 'Linked Sing module cache input refused'}
    Copy-Item -LiteralPath $taskSing.Dir -Destination $taskSingSource -Recurse
    Get-ChildItem -LiteralPath $taskSingSource -File -Recurse | ForEach-Object {$_.IsReadOnly=$false}
    $taskSocksResult=& python -B $taskSocksProducer sing-client $taskSingSource | ConvertFrom-Json
    if($LASTEXITCODE -or $taskSocksResult.contract -ne 1){throw 'Verified local SOCKS UDP client overlay failed'}
    Protect-TaskSingAuthLogs 'protocol/http/handshake.go' 'fa5cf907d7a50a29ee9292cf56d6aa9fb1a4b3a82de2bc233b43653cb9cbb7e9' @{
        'E.New("http: authentication failed, username=", username, ", password=", password)'='E.New("http: authentication failed")'
        'E.New("http: authentication failed, Proxy-Authorization=", authorization)'='E.New("http: invalid proxy authorization")'
    }
    Protect-TaskSingAuthLogs 'protocol/socks/handshake.go' $taskSocksResult.files.'protocol/socks/handshake.go' @{
        'E.New("socks4: authentication failed, username=", request.Username)'='E.New("socks4: authentication failed")'
        'E.New("socks5: authentication failed, username=", usernamePasswordAuthRequest.Username, ", password=", usernamePasswordAuthRequest.Password)'='E.New("socks5: authentication failed")'
    }
    $taskReceipt.pinnedSocksUdpClient=[ordered]@{contract=1;singVersion=$taskSingVersion;singModuleSum=$taskSing.Sum;files=Get-TaskSingSources}
    & (Join-Path $taskGoBin 'go.exe') mod edit "-replace=github.com/sagernet/sing=../sing-source"
    if($LASTEXITCODE){throw 'Unable to bind isolated Sing SOCKS client'}
    $taskReceipt.compiledGoModSha256=(Get-FileHash -LiteralPath (Join-Path $taskCompileSource 'go.mod')).Hash.ToLowerInvariant()
    $taskSocksLog=Join-Path $taskOutput 'socks-udp-client-tests.log'
    & (Join-Path $taskGoBin 'go.exe') test -count=1 -v -run '^TestMcrfPrivateUDP(PrebindAndControlEOF|RelayReply)$' github.com/sagernet/sing/protocol/socks 2>&1|Tee-Object -FilePath $taskSocksLog
    if($LASTEXITCODE -or !(Select-String -LiteralPath $taskSocksLog -Pattern '^--- PASS: TestMcrfPrivateUDPPrebindAndControlEOF ' -Quiet) -or
       !(Select-String -LiteralPath $taskSocksLog -Pattern '^--- PASS: TestMcrfPrivateUDPRelayReply ' -Quiet)){
        throw 'Pinned SOCKS UDP client regression failed'
    }
    $taskReceipt.pinnedSocksUdpClient.testLogSha256=(Get-FileHash -LiteralPath $taskSocksLog).Hash.ToLowerInvariant()
    $taskListenerLog=Join-Path $taskOutput 'owner-listener-tests.log'
    & (Join-Path $taskGoBin 'go.exe') test -count=1 -v -run '^TestMcrfOwner' ./common/listener 2>&1|Tee-Object -FilePath $taskListenerLog
    if($LASTEXITCODE){throw 'Owner listener regression failed'}
    foreach($case in @('TestMcrfOwnerLocalTCPDeniesMissingAndRejectsUnscopedAssociation','TestMcrfOwnerSOCKSDatagramVerifiesOriginalSenderAndSameUID','TestMcrfOwnerAuthenticatedLANUsesAndroidOnLinkSnapshot')){
        if(!(Select-String -LiteralPath $taskListenerLog -Pattern "^--- PASS: $case " -Quiet)){throw "Owner listener regression missing: $case"}
    }
    $taskReceipt.ownerListenerTestsSha256=(Get-FileHash -LiteralPath $taskListenerLog).Hash.ToLowerInvariant()
    $taskAar=Join-Path $taskOutput 'libbox-mcrf-clash.aar'
    $taskClang=(Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe').Replace('\','/')
    $taskReceipt.clangSha256=(Get-FileHash -LiteralPath $taskClang).Hash.ToLowerInvariant()
    $taskAbiTargets=@(
        @{abi='armeabi-v7a';go='arm';clang='armv7a-linux-androideabi24'},
        @{abi='arm64-v8a';go='arm64';clang='aarch64-linux-android24'},
        @{abi='x86_64';go='amd64';clang='x86_64-linux-android24'}
    )
    $taskReceipt.abiArchives=@()
    foreach($target in $taskAbiTargets){
        # Call the NDK's Clang driver directly for the final link. Its .cmd
        # wrapper cannot accommodate the full armv7 external link command.
        $single=Join-Path $taskOutput "libbox-$($target.abi).aar"
        $taskLdFlags="-X github.com/sagernet/sing-box/constant.Version=$taskVersion -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid= -extld='$taskClang' -extldflags=--target=$($target.clang)"
        $taskArguments=@('bind','-o',$single,'-target',"android/$($target.go)",'-androidapi','24','-javapkg=io.nekohasekai','-libname=box','-trimpath','-buildvcs=false','-ldflags',$taskLdFlags,'-tags',$taskTags,'./experimental/libbox')
        & (Join-Path $taskMobileBin 'gomobile.exe') @taskArguments
        if($LASTEXITCODE -ne 0){throw "Native candidate build failed for $($target.abi)"}
        $taskReceipt.abiArchives+=@{abi=$target.abi;sha256=(Get-FileHash -LiteralPath $single).Hash.ToLowerInvariant()}
        Save-TaskReceipt
    }
    & python -B $taskAarMerger $taskOutput $taskAar
    if($LASTEXITCODE -or (Get-FileHash -LiteralPath $taskAarMerger).Hash.ToLowerInvariant() -cne $taskReceipt.aarMergerSha256){throw 'Verified ABI AAR merge failed'}
    if($taskRawProof){$null=Assert-RawOverlay}
    $taskOwnerPost=& python -B $taskOwnerProducer $SourceRoot $taskOutput --verify
    if($LASTEXITCODE -or $taskOwnerPost -cne $taskOwnerProof){throw 'Android VPN owner admission source changed during build'}
    Assert-TaskOwnerSources
    if((Get-FileHash -LiteralPath $taskSocksProducer).Hash.ToLowerInvariant() -cne $taskReceipt.socksProducerSha256 -or
       ($taskReceipt.pinnedSocksUdpClient.files|ConvertTo-Json -Compress) -cne ((Get-TaskSingSources)|ConvertTo-Json -Compress) -or
       (Get-FileHash -LiteralPath (Join-Path $taskCompileSource 'go.mod')).Hash.ToLowerInvariant() -cne $taskReceipt.compiledGoModSha256){
        throw 'Pinned SOCKS UDP client source changed during build'
    }
    $taskReceipt.state='built_awaiting_abi_api_and_device_gates'
    $taskReceipt.aarSha256=(Get-FileHash -LiteralPath $taskAar).Hash.ToLowerInvariant()
    $taskReceipt.aarBytes=(Get-Item -LiteralPath $taskAar).Length
    Copy-Item -LiteralPath (Join-Path $SourceRoot 'LICENSE') -Destination (Join-Path $taskOutput 'CORE-LICENSE')
    Save-TaskReceipt
}catch{
    $taskReceipt.state='failed';$taskReceipt.error=$_.Exception.Message;Save-TaskReceipt;throw
}finally{
    if($taskLocationPushed){Pop-Location}
    foreach($name in $taskEnvironment){[Environment]::SetEnvironmentVariable($name,$taskSavedEnvironment[$name],'Process')}
}
