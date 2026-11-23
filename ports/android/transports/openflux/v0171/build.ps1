param([string]$Ndk='', [switch]$TestServer, [switch]$HostOnly)
$ErrorActionPreference='Stop'
if($args.Count){throw 'Unknown OpenFlux build arguments'}
$taskCanonical=Split-Path $PSScriptRoot
$taskProject=[IO.Path]::GetFullPath((Join-Path $taskCanonical '../../../..'))
$taskPin=(Get-Content "$taskCanonical/../transport-lock.json" -Raw|ConvertFrom-Json).openflux
if($taskPin.repository -cne 'p1neappleXpress/OpenFlux' -or $taskPin.version -cne 'v0.17.1' -or $taskPin.commit -cne 'f492a1a33b7581ad8f88118f97e0b866adda3cdc'){throw 'Official v0.17.1 pin required'}
$taskPristine="$taskProject/work/android-upstream/openflux-core-v0.17.1"
if(!(Test-Path -LiteralPath $taskPristine)){
    & git clone --filter=blob:none --no-checkout "https://github.com/$($taskPin.repository).git" $taskPristine
    if($LASTEXITCODE){throw 'Source clone failed'}
    & git -C $taskPristine checkout --detach $taskPin.commit
    if($LASTEXITCODE){throw 'Pinned source checkout failed'}
}
if((& git -C $taskPristine rev-parse HEAD) -cne $taskPin.commit -or ((& git -C $taskPristine status --porcelain|Out-String).Trim())){throw 'Exact clean pinned source required'}
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Write-GitBlob([string]$Relative,[string]$Destination){
    $taskStart=New-Object Diagnostics.ProcessStartInfo
    $taskStart.FileName='git'
    $taskStart.Arguments="-C `"$taskPristine`" cat-file blob `"$($taskPin.commit):$Relative`""
    $taskStart.WorkingDirectory=$taskPristine;$taskStart.UseShellExecute=$false;$taskStart.RedirectStandardOutput=$true
    $taskProcess=[Diagnostics.Process]::Start($taskStart)
    $taskStream=[IO.File]::Open($Destination,[IO.FileMode]::Create,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$taskProcess.StandardOutput.BaseStream.CopyTo($taskStream)}finally{$taskStream.Dispose();$taskProcess.WaitForExit()}
    if($taskProcess.ExitCode){throw "Pinned blob extraction failed: $Relative"}
}
$taskDeps=Get-Content "$PSScriptRoot/dependencies-lock.json" -Raw|ConvertFrom-Json
foreach($taskBlob in $taskDeps.gitBlobs.PSObject.Properties){
    if((& git -C $taskPristine hash-object -- $taskBlob.Name) -cne $taskBlob.Value){throw "Upstream dependency lock differs: $($taskBlob.Name)"}
}
$taskId=[guid]::NewGuid().ToString('N')
$taskSource="$taskProject/work/openflux0171-source-$taskId"
$taskOutput="D:/MCRF/Builds/Android/openflux0171-native-$taskId"
$null=New-Item -ItemType Directory $taskSource,$taskOutput
$taskInputNames=@('main.go','main_test.go','mcrf_proxy.go','v0171/build.ps1','v0171/mcrf_scripts.go','v0171/script_update.go','v0171/script_update_cli.go','v0171/script_lock_unix.go','v0171/script_lock_windows.go','v0171/script_update_test.go','v0171/provider_state.go','v0171/bridge_test.go','v0171/scripts-lock.json','v0171/dependencies-lock.json','../transport-lock.json')
$taskInputNames+=@('v0171/provider_underlay.go','v0171/provider_underlay_android.go','v0171/provider_underlay_cli.go')
$taskInputNames+=@('../socks-owner/overlay.py','../socks-owner/worker_test.go.txt')
$taskInputs=@{};foreach($taskName in $taskInputNames){$taskInputs[$taskName]=Hash "$taskCanonical/$taskName"}
$taskResult=[ordered]@{state='BUILDING';repository=$taskPin.repository;version=$taskPin.version;commit=$taskPin.commit;officialScriptUpdater=2;officialScriptUpdateCli='official-pinned-v1';sourceStable=$false;source=$taskSource;inputs=$taskInputs;files=@();tests=@();runtimeAccepted=$false;published=$false}
$taskEnvironment=@{};foreach($taskKey in @('GOPATH','GOOS','GOARCH','GOARM','CGO_ENABLED','CC','PATH')){$taskEnvironment[$taskKey]=[Environment]::GetEnvironmentVariable($taskKey)}
try{
    # Archive the pinned tree, then rematerialize signed fixtures from raw blobs.
    # Upstream v0.17.1 omits mobile/scriptassets from its -text rule, so both a
    # Windows worktree and git archive may CRLF-convert echo.js and invalidate
    # its detached Ed25519 signature before the host API is exercised.
    $taskArchive="$taskOutput/pinned-source.tar"
    & git -C $taskPristine archive --format=tar --output=$taskArchive $taskPin.commit
    if($LASTEXITCODE){throw 'Pinned source archive failed'}
    & tar -xf $taskArchive -C $taskSource
    if($LASTEXITCODE){throw 'Pinned source extraction failed'}
    Remove-Item -LiteralPath $taskArchive
    foreach($taskSigned in @('mobile/scriptassets/echo.js','mobile/scriptassets/echo.js.sig')){
        Write-GitBlob $taskSigned (Join-Path $taskSource $taskSigned)
        if((& git -C $taskPristine hash-object --no-filters -- (Join-Path $taskSource $taskSigned)) -cne $taskDeps.gitBlobs.$taskSigned){throw "Signed fixture bytes differ: $taskSigned"}
    }
    $taskSocks=(& python -B "$taskCanonical/../socks-owner/overlay.py" openflux $taskSource | ConvertFrom-Json)
    if($LASTEXITCODE -ne 0 -or $taskSocks.contract -ne 1){throw 'Pinned OpenFlux SOCKS UDP source overlay failed'}
    $taskResult.socksUdpSource=@{openflux=1}
    $taskResult.socksUdpSourceOverlay=$taskSocks
    $null=New-Item -ItemType Directory "$taskSource/mobile/cmd/mcrf-openflux","$taskSource/mobile/mcrf-packages"
    foreach($taskName in @('main.go','main_test.go')){Copy-Item "$taskCanonical/$taskName" "$taskSource/mobile/cmd/mcrf-openflux/$taskName"}
    Copy-Item "$taskCanonical/mcrf_proxy.go" "$taskSource/mobile/mcrf_proxy.go"
    Copy-Item "$PSScriptRoot/mcrf_scripts.go" "$taskSource/mobile/mcrf_scripts.go"
    foreach($taskName in @('script_update.go','script_lock_unix.go','script_lock_windows.go','script_update_test.go')){
        Copy-Item "$PSScriptRoot/$taskName" "$taskSource/mobile/mcrf_$taskName"
    }
    Copy-Item "$PSScriptRoot/script_update_cli.go" "$taskSource/mobile/cmd/mcrf-openflux/mcrf_script_update.go"
    Copy-Item "$PSScriptRoot/bridge_test.go" "$taskSource/mobile/mcrf0171_test.go"
    Copy-Item "$PSScriptRoot/provider_state.go" "$taskSource/mobile/cmd/mcrf-openflux/mcrf_provider.go"
    Copy-Item "$PSScriptRoot/provider_underlay_cli.go" "$taskSource/mobile/cmd/mcrf-openflux/mcrf_provider_underlay.go"
    foreach($taskName in @('provider_underlay.go','provider_underlay_android.go')){
        Copy-Item "$PSScriptRoot/$taskName" "$taskSource/netbind/mcrf_$taskName"
    }
    Copy-Item "$PSScriptRoot/scripts-lock.json" "$taskSource/mobile/mcrf-packages/manifest.json"
    $taskScripts=Get-Content "$PSScriptRoot/scripts-lock.json" -Raw|ConvertFrom-Json
    foreach($taskPackage in $taskScripts.packages){
        if($taskPackage.id -notmatch '^[a-z][a-z0-9-]+$' -or $taskPackage.sha256 -notmatch '^[a-f0-9]{64}$' -or $taskPackage.api -ne 1 -or !($taskPackage.url.StartsWith('https://github.com/p1neappleXpress/OpenFluxTransports/releases/download/'))){throw 'Invalid official package pin'}
        $taskFile="$taskSource/mobile/mcrf-packages/$($taskPackage.id).flux"
        Invoke-WebRequest -Uri $taskPackage.url -OutFile $taskFile
        if((Hash $taskFile) -cne $taskPackage.sha256){throw "Official script digest differs: $($taskPackage.id)"}
    }
    $taskFrozen=@(Get-ChildItem $taskSource -Recurse -File|ForEach-Object {@{path=$_.FullName.Substring($taskSource.Length).TrimStart([char[]]@([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar));sha256=(Hash $_.FullName)}})
    $taskGo="$taskProject/work/toolchains/go1.26.8/go/bin/go.exe"
    $env:GOPATH="$taskProject/work/toolchains/go-home";$env:GOOS='windows';$env:GOARCH='amd64';$env:CGO_ENABLED='0'
    function Suite([string]$Directory,[string]$Package,[string]$Filter,[string]$Name){
        Push-Location $Directory
        try{
            & $taskGo mod verify *> "$taskOutput/$Name-dependencies.log";if($LASTEXITCODE){throw "Dependency verification failed: $Name"}
            $taskJson=@(& $taskGo test -mod=readonly -short '-ldflags=-checklinkname=0' -json $Package -run $Filter 2>&1)
            $taskExit=$LASTEXITCODE
            [IO.File]::WriteAllLines("$taskOutput/$Name.jsonl",[string[]]$taskJson,[Text.UTF8Encoding]::new($false))
            if($taskExit){throw "Host suite failed: $Name"}
            $taskResult.tests+=@{file="$Name.jsonl";sha256=(Hash "$taskOutput/$Name.jsonl")}
        }finally{Pop-Location}
    }
    Suite $taskSource './share' '^Test' 'share'
    Suite $taskSource './transport/registry' '^Test' 'registry'
    Suite $taskSource './transport/script' '^Test' 'scripts'
    Suite $taskSource './socks5' '^TestMcrfSocksUdpSourcePinAndControlEOF$' 'socks-udp-source'
    Suite "$taskSource/mobile" '.' '^TestMcrf0171' 'bridge'
    Suite "$taskSource/mobile" './cmd/mcrf-openflux' '^TestMcrfStartup|^TestPrivateConfiguration' 'cli'
    if(!$HostOnly){
        if(!$Ndk){$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018'}
        $taskCompiler="$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin"
        if(!(Test-Path "$taskCompiler/clang.exe")){throw 'Android NDK compiler missing'}
        $env:PATH="$taskCompiler;"+$taskEnvironment.PATH;$env:GOOS='android';$env:CGO_ENABLED='1'
        Push-Location "$taskSource/mobile"
        try{foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
            switch($taskAbi){'arm64-v8a'{$env:GOARCH='arm64';$taskTriple='aarch64-linux-android29'}'armeabi-v7a'{$env:GOARCH='arm';$env:GOARM='7';$taskTriple='armv7a-linux-androideabi29'}'x86_64'{$env:GOARCH='amd64';$taskTriple='x86_64-linux-android29'}}
            $env:CC="clang --target=$taskTriple";$null=New-Item -ItemType Directory "$taskOutput/$taskAbi"
            & $taskGo build -mod=readonly -trimpath -buildmode=pie '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o "$taskOutput/$taskAbi/libopenflux.so" ./cmd/mcrf-openflux *> "$taskOutput/build-$taskAbi.log"
            if($LASTEXITCODE){throw "Native build failed: $taskAbi"}
            $taskResult.files+=@{abi=$taskAbi;sha256=(Hash "$taskOutput/$taskAbi/libopenflux.so");bytes=(Get-Item "$taskOutput/$taskAbi/libopenflux.so").Length}
        }}finally{Pop-Location}
    }
    foreach($taskRow in $taskFrozen){if((Hash (Join-Path $taskSource $taskRow.path)) -cne $taskRow.sha256){throw 'Frozen source/dependencies changed during producer'}}
    foreach($taskName in $taskInputs.Keys){if((Hash "$taskCanonical/$taskName") -cne $taskInputs[$taskName]){throw 'Canonical inputs changed during producer'}}
    [IO.File]::WriteAllText("$taskOutput/source-manifest.json",($taskFrozen|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
    $taskResult.sourceManifestSha256=Hash "$taskOutput/source-manifest.json"
    $taskResult.sourceStable=$true
    $taskResult.state=if($HostOnly){'HOST_VERIFIED_NATIVE_PENDING'}else{'BUILT_HOST_VERIFIED_NOT_PHONE_ACCEPTED'}
    if($TestServer){$taskResult.fixtureState='PENDING_SEPARATE_0171_OWNED_FIXTURE'}
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{
    foreach($taskKey in $taskEnvironment.Keys){[Environment]::SetEnvironmentVariable($taskKey,$taskEnvironment[$taskKey])}
    [IO.File]::WriteAllText("$taskOutput/receipt.json",($taskResult|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    Write-Output "OPENFLUX0171=$($taskResult.state);STAGE=$taskOutput"
}
