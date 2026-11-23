param([Parameter(Mandatory=$true)][string]$ReceiptPath)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation native-build
$taskRoot='D:/MCRF/Projects/MCRF'
$taskReceiptPath=[IO.Path]::GetFullPath($ReceiptPath)
if($taskReceiptPath -cnotmatch '^D:\\MCRF\\Builds\\Android\\p044-native-source-[a-f0-9]{32}\\receipt.json$'){throw 'Exact frozen native receipt required'}
$taskState=Get-Content -LiteralPath $taskReceiptPath -Raw|ConvertFrom-Json
if($taskState.state -cne 'SOURCE_FROZEN_UNCOMPILED_UNTESTED' -or !$taskState.producerComplete){throw 'Do not duplicate/resume a completed or failed producer'}
$taskLedger=Get-Content "$taskRoot/ports/android/coordination/p0-network-42-45-2026-10-19.json" -Raw|ConvertFrom-Json
if(!$taskLedger.sourceReady -or !$taskLedger.nativeDirectSourceReady -or !$taskLedger.allowNativeCandidateBuild -or $taskLedger.allowApkBuild){throw 'Whole P0 source-ready and narrow native candidate gate required; no main APK build'}
$taskStage=$taskState.stage
$taskCurrentPath="$taskRoot/ports/android/coordination/current.json"
$taskCurrent=Get-Content $taskCurrentPath -Raw|ConvertFrom-Json
if($taskCurrent.jobs.build -or $taskCurrent.jobs.device -or $taskCurrent.jobs.hostTest -or $taskCurrent.jobs.emulator){throw 'Existing job; continue it instead of duplicating'}
$taskSource="$taskStage/source"
$taskPatch="$taskRoot/ports/android/primary-cores/singbox/native-patches/p044-direct-family"
foreach($row in $taskState.patches){
    $frozen=if($row.file.EndsWith('.go')){"$taskSource/route/$($row.file)"}else{"$taskStage/$($row.file)"}
    if((Get-FileHash $frozen -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row.sha256 -or (Get-FileHash "$taskPatch/$($row.file)" -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row.sha256){throw 'Frozen/canonical patch changed'}
}
if((Get-FileHash "$taskSource/route/route.go" -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskState.routeSha256){throw 'Frozen route seam changed'}
function AssertNativeInputs {
    if((Get-FileHash "$taskStage/source-manifest.json" -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskState.sourceManifestSha256){throw 'Source manifest changed'}
    $manifest=@(Get-Content "$taskStage/source-manifest.json" -Raw|ConvertFrom-Json)
    if($manifest.Count -ne $taskState.sourceFiles){throw 'Source manifest incomplete'}
    foreach($row in $manifest){
        $path=[IO.Path]::GetFullPath((Join-Path $taskSource $row.path))
        if(!$path.StartsWith([IO.Path]::GetFullPath($taskSource)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Source manifest path escape'}
        if((Get-Item -LiteralPath $path).Length -ne $row.bytes -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row.sha256){throw 'Immutable native input changed'}
    }
}
AssertNativeInputs
$taskGoBin="$taskRoot/work/toolchains/go1.26.8/go/bin"
$taskMobileBin="$taskRoot/work/toolchains/gomobile-v0.1.13"
$taskSdk='C:/Users/dieonfire/AppData/Local/Android/Sdk'
$taskNdk="$taskSdk/ndk/28.2.13676358"
$taskJava='C:/Program Files/Java/jdk-17'
$taskNames=@('PATH','GOPATH','GOTOOLCHAIN','GOPROXY','GOSUMDB','JAVA_HOME','ANDROID_HOME','ANDROID_NDK_HOME','GOFLAGS')
$taskSaved=@{};foreach($name in $taskNames){$taskSaved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$taskBuild=[ordered]@{schema=1;state='STARTING';sourceReceipt=$taskReceiptPath;sourceStable=$false;mainApkBuilt=$false;moduleInstalled=$false;published=$false;producerComplete=$false;startedUtc=[DateTime]::UtcNow.ToString('o');version='1.14.2-lx.11-mcrf-direct-family.1';moduleVersionCode=7;tests=@()}
function SaveBuild {$taskBuild|ConvertTo-Json -Depth 9|Set-Content -LiteralPath "$taskStage/build-receipt.json" -Encoding utf8}
function GoStep([string]$Name,[string[]]$Arguments){
    $taskBuild.state=$Name;SaveBuild
    & "$taskGoBin/go.exe" @Arguments 2>&1|Tee-Object -FilePath "$taskStage/$Name.log"|Out-Null
    if($LASTEXITCODE){throw "Native step failed: $Name"}
}
try{
    $taskCurrent.jobs.build="P044_NATIVE:$taskStage";$taskCurrent|ConvertTo-Json -Depth 20|Set-Content $taskCurrentPath -Encoding utf8
    [IO.File]::Copy($PSCommandPath,"$taskStage/build-control.ps1",$false)
    $env:PATH=$taskGoBin+';'+$taskMobileBin+';'+"$taskJava/bin;"+$env:PATH
    $env:GOPATH="$taskRoot/work/toolchains/go-home";$env:GOTOOLCHAIN='local';$env:GOPROXY='off';$env:GOSUMDB='off'
    $env:JAVA_HOME=$taskJava;$env:ANDROID_HOME=$taskSdk;$env:ANDROID_NDK_HOME=$taskNdk;$env:GOFLAGS='-mod=readonly -p=4'
    if((& "$taskGoBin/go.exe" version) -notmatch '^go version go1\.26\.8 windows/amd64'){throw 'Wrong pinned Go version'}
    if((Get-Content "$taskNdk/source.properties" -Raw) -notmatch 'Pkg.Revision\s*=\s*28\.2\.13676358'){throw 'Wrong pinned NDK'}
    Push-Location $taskSource
    try{
        GoStep 'MOD_VERIFY' @('mod','verify')
        GoStep 'HOST_TAGGED' @('test','-ldflags=-checklinkname=0','-tags=with_mcrf_direct_family','./route','-run','^TestMcrfDirectFamily','-count=1','-json')
        GoStep 'HOST_STUB' @('test','-ldflags=-checklinkname=0','./route','-run','^TestMcrfDirectFamilyStubPreservesOriginal$','-count=1','-json')
        foreach($name in @('HOST_TAGGED','HOST_STUB')){
            $events=@(Get-Content "$taskStage/$name.log"|ForEach-Object {try{$_|ConvertFrom-Json}catch{}})
            $passes=@($events|Where-Object {$_.Action -eq 'pass' -and $_.Test})
            if(!$passes.Count -or @($events|Where-Object {$_.Action -eq 'fail'}).Count){throw 'Missing/failed native host evidence'}
            $taskBuild.tests+=@{group=$name;passed=$passes.Count;logSha256=(Get-FileHash "$taskStage/$name.log" -Algorithm SHA256).Hash.ToLowerInvariant()}
        }
        $taskBuild.state='AAR_BUILD';SaveBuild
        $taskTags='with_gvisor,with_quic,with_wireguard,with_utls,with_naive_outbound,badlinkname,tfogo_checklinkname0,with_xhttp,with_awg,with_lx_command,with_lx_chain,with_lx_idle_suspend,with_openvpn,with_openconnect,with_tailscale,ts_omit_logtail,ts_omit_ssh,ts_omit_drive,ts_omit_taildrop,ts_omit_webclient,ts_omit_doctor,ts_omit_capture,ts_omit_kube,ts_omit_aws,ts_omit_synology,ts_omit_bird,with_clash_api,with_mcrf_direct_family'
        $taskBuild.tags=$taskTags
        $taskLd='-X github.com/sagernet/sing-box/constant.Version=1.14.2-lx.11-mcrf-direct-family.1 -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid='
        & "$taskMobileBin/gomobile.exe" bind -o "$taskStage/runtime.aar" -target 'android/arm,android/arm64,android/amd64' -androidapi 24 -javapkg io.nekohasekai -libname box -trimpath -buildvcs=false -ldflags $taskLd -tags $taskTags ./experimental/libbox 2>&1|Tee-Object -FilePath "$taskStage/aar-build.log"|Out-Null
        if($LASTEXITCODE){throw 'Native AAR build failed'}
    }finally{Pop-Location}
    foreach($row in $taskState.patches){$frozen=if($row.file.EndsWith('.go')){"$taskSource/route/$($row.file)"}else{"$taskStage/$($row.file)"};if((Get-FileHash $frozen -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row.sha256){throw 'Frozen patch changed during producer'}}
    if((Get-FileHash "$taskSource/route/route.go" -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskState.routeSha256){throw 'Frozen seam changed during producer'}
    AssertNativeInputs
    $taskBuild.sourceStable=$true;$taskBuild.aarSha256=(Get-FileHash "$taskStage/runtime.aar" -Algorithm SHA256).Hash.ToLowerInvariant();$taskBuild.aarBytes=(Get-Item "$taskStage/runtime.aar").Length
    $taskBuild.state='COMPILED_UNINSTALLED_API_ABI_GATES_OPEN';$taskBuild.producerComplete=$true;SaveBuild
    Write-Output "P044_NATIVE_RESULT=$taskStage/build-receipt.json"
}catch{
    $taskBuild.state='FAILED';$taskBuild.error=$_.Exception.Message;$taskBuild.producerComplete=$true;SaveBuild;throw
}finally{
    foreach($name in $taskNames){[Environment]::SetEnvironmentVariable($name,$taskSaved[$name],'Process')}
    $taskLatest=Get-Content $taskCurrentPath -Raw|ConvertFrom-Json
    if($taskLatest.jobs.build -ceq "P044_NATIVE:$taskStage"){$taskLatest.jobs.build=$null;$taskLatest|ConvertTo-Json -Depth 20|Set-Content $taskCurrentPath -Encoding utf8}
}
