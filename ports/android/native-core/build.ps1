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
function Assert-RawOverlay {
    if((Get-FileHash -LiteralPath $taskRawVerifier).Hash.ToLowerInvariant() -cne $taskRawVerifierSha){throw 'Overlay verifier changed during native build'}
    $proof=& python $taskRawVerifier verify --source $SourceRoot --sha256 $RawOverlaySha256 --evidence $RawEvidenceRoot --overlay-kind $RawOverlayKind
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
$taskVersion='1.14.2-lx.11-mcrf-clash.1'
if($taskRawProof){
    $taskTags+=','+($taskRawProof.tags -join ',')
    $taskVersion=if($RawOverlayKind -eq 'p4'){"1.14.2-lx.11-mcrf-carrier-stack.$CarrierStackRevision"}else{'1.14.2-lx.11-mcrf-raw-port.1'}
}
$env:PATH=$taskGoBin+';'+$taskMobileBin+';'+(Join-Path $JavaHome 'bin')+';'+$env:PATH
$env:GOPATH=Join-Path $taskWorkspace 'work/toolchains/go-home'
$env:GOTOOLCHAIN='local'
$env:JAVA_HOME=$JavaHome
$env:ANDROID_HOME=$AndroidSdk
$env:ANDROID_NDK_HOME=$Ndk
$env:GOFLAGS='-p=4'
$taskReceipt=[ordered]@{
    state='building';repository='Leadaxe/sing-box-lx';commit=$taskCommit;submodules=$taskSubmodules
    go='1.26.8';gomobile='github.com/sagernet/gomobile v0.1.13';java=$JavaHome;ndk=$Ndk
    ndk_note='Pinned upstream NDK r28c; validate ELF16KiB alignment and runtime separately. Adding Clash changes the distributed native binary; no byte-identical upstream claim.'
    androidApi=24;targets=@('arm','arm64','amd64');tags=$taskTags;sourceModified=[bool]$taskRawProof
    version=$taskVersion;promoted=$false;productionIntegration=$false
    rawOverlay=$taskRawProof;rawVerifierSha256=$taskRawVerifierSha;producerSha256=(Get-FileHash -LiteralPath $PSCommandPath).Hash.ToLowerInvariant()
    goModSha256=(Get-FileHash -LiteralPath (Join-Path $SourceRoot 'go.mod')).Hash.ToLowerInvariant()
    goSumSha256=(Get-FileHash -LiteralPath (Join-Path $SourceRoot 'go.sum')).Hash.ToLowerInvariant()
}
$taskReceiptPath=Join-Path $taskOutput 'receipt.json'
function Save-TaskReceipt { [IO.File]::WriteAllText($taskReceiptPath,($taskReceipt|ConvertTo-Json -Depth 5),(New-Object Text.UTF8Encoding($false))) }
Save-TaskReceipt
Write-Output "CORE_CANDIDATE=$taskOutput"
Push-Location $SourceRoot
try {
    & (Join-Path $taskGoBin 'go.exe') mod verify
    if($LASTEXITCODE -ne 0){throw 'Module integrity check failed'}
    $taskAar=Join-Path $taskOutput 'libbox-mcrf-clash.aar'
    $taskLdFlags="-X github.com/sagernet/sing-box/constant.Version=$taskVersion -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid="
    $taskArguments=@('bind','-o',$taskAar,'-target','android/arm,android/arm64,android/amd64','-androidapi','24','-javapkg=io.nekohasekai','-libname=box','-trimpath','-buildvcs=false','-ldflags',$taskLdFlags,'-tags',$taskTags,'./experimental/libbox')
    & (Join-Path $taskMobileBin 'gomobile.exe') @taskArguments
    if($LASTEXITCODE -ne 0){throw 'Native candidate build failed'}
    if($taskRawProof){$null=Assert-RawOverlay}
    $taskReceipt.state='built_awaiting_abi_api_and_device_gates'
    $taskReceipt.aarSha256=(Get-FileHash -LiteralPath $taskAar).Hash.ToLowerInvariant()
    $taskReceipt.aarBytes=(Get-Item -LiteralPath $taskAar).Length
    Copy-Item -LiteralPath (Join-Path $SourceRoot 'LICENSE') -Destination (Join-Path $taskOutput 'CORE-LICENSE')
    Save-TaskReceipt
}catch{
    $taskReceipt.state='failed';$taskReceipt.error=$_.Exception.Message;Save-TaskReceipt;throw
}finally{Pop-Location}
