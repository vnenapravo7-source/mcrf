param([Parameter(Mandatory=$true)][string]$Stage,[string]$Go='D:/MCRF/Projects/MCRF/work/toolchains/go1.27.2/go/bin/go.exe',[string]$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018')
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot '../../maintenance/Assert-HumanBatchReady.ps1') -Operation native-build
$taskStage=[IO.Path]::GetFullPath($Stage)
if([IO.Path]::GetDirectoryName($taskStage) -ine [IO.Path]::GetFullPath('D:/MCRF/Builds/Android') -or
   [IO.Path]::GetFileName($taskStage) -cnotmatch '^s3bypass-native-[0-9a-f]{32}$' -or (Get-Item -LiteralPath $taskStage).LinkType){throw 'Exact owned immutable S3 source stage required'}
$taskReceipt=Get-Content -LiteralPath (Join-Path $taskStage 'receipt.json') -Raw|ConvertFrom-Json -AsHashtable
if($taskReceipt.state -cne 'SOURCE_PREPARED' -or $taskReceipt.commit -cne 'b54ed13e3d7473ad8c25b5c39dfe127b004db540' -or $taskReceipt.kind -cne 's3bypass'){throw 'Inspect existing producer; do not restart or substitute another Xray'}
$taskSource=Join-Path $taskStage 'source'
$taskControlPath=$PSCommandPath
$taskControlSha=(Get-FileHash -LiteralPath $taskControlPath -Algorithm SHA256).Hash.ToLowerInvariant()
$taskControlSnapshot=Join-Path $taskStage 'producer-control.ps1'
if(Test-Path -LiteralPath $taskControlSnapshot){
    if((Get-Item -LiteralPath $taskControlSnapshot).LinkType -or (Get-FileHash -LiteralPath $taskControlSnapshot -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskControlSha){throw 'Existing producer snapshot differs; preserve it and prepare a new stage for changed source'}
}else{Copy-Item -LiteralPath $taskControlPath -Destination $taskControlSnapshot}
if((Get-FileHash -LiteralPath $taskControlSnapshot -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskControlSha){throw 'Producer snapshot changed while copied'}
$taskManifest=Join-Path $taskStage 'source-manifest.json'
$taskInputs=Get-Content -LiteralPath $taskManifest -Raw|ConvertFrom-Json
$taskUnderlay=$taskReceipt.s3UnderlayContract -eq 1
$taskPinnedUdp=$taskReceipt.socksUdpSource.s3bypass -eq 1
function Assert-S3Source {
    if((Get-FileHash -LiteralPath $taskControlPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskControlSha){throw 'Active native producer script changed; outputs are not admitted'}
    if((Get-Item -LiteralPath $taskSource).LinkType){throw 'Linked source root rejected'}
    if($taskUnderlay -and ((Get-FileHash -LiteralPath $taskManifest -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskReceipt.sourceManifestSha256 -or
        @(Get-ChildItem -LiteralPath $taskSource -Recurse -Force -File|Where-Object {!$_.FullName.StartsWith((Join-Path $taskSource '.git')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)}).Count -ne @($taskInputs).Count)){
        throw 'S3 underlay source manifest changed'
    }
    foreach($taskInput in $taskInputs){
        $taskFile=[IO.Path]::GetFullPath((Join-Path $taskSource $taskInput.path))
        if(!$taskFile.StartsWith($taskSource+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Get-Item -LiteralPath $taskFile).LinkType -or
           (Get-FileHash -LiteralPath $taskFile -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskInput.sha256){throw 'Frozen S3 source changed'}
        $taskParent=[IO.DirectoryInfo]::new([IO.Path]::GetDirectoryName($taskFile))
        while($taskParent.FullName -ine $taskSource){
            if((Get-Item -LiteralPath $taskParent.FullName).LinkType){throw 'Linked source parent rejected'}
            $taskParent=$taskParent.Parent
        }
    }
}
Assert-S3Source
$taskGo=[IO.Path]::GetFullPath($Go)
$taskCompiler=Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
foreach($taskTool in @($taskGo,(Join-Path $taskCompiler 'clang.exe'),(Join-Path $taskCompiler 'llvm-readelf.exe'))){if(!(Test-Path -LiteralPath $taskTool -PathType Leaf)){throw 'Existing Go/NDK prerequisite unavailable'}}
$taskReceipt.state='BUILDING_NATIVE'
$taskReceipt.controlSha256=$taskControlSha
$taskReceipt.controlFile='producer-control.ps1'
$taskReceipt.producerPid=$PID;$taskReceipt.producerStartTicks=[Diagnostics.Process]::GetCurrentProcess().StartTime.ToUniversalTime().Ticks;$taskReceipt.producerComplete=$false
function Save-S3NativeReceipt {[IO.File]::WriteAllText((Join-Path $taskStage 'receipt.json'),($taskReceipt|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))}
Save-S3NativeReceipt
$taskSaved=@{}
foreach($taskKey in @('PATH','GOOS','GOARCH','GOARM','CGO_ENABLED','CC','GOTMPDIR','GOCACHE','GOPATH','GOMODCACHE','GOTOOLCHAIN','GOROOT','GOENV','GOFLAGS','GOWORK','GOEXPERIMENT','GOPROXY','TMP','TEMP','CGO_CFLAGS','CGO_CPPFLAGS','CGO_CXXFLAGS','CGO_LDFLAGS')){$taskSaved[$taskKey]=[Environment]::GetEnvironmentVariable($taskKey,'Process')}
Push-Location $taskSource
try {
    $env:PATH=$taskCompiler+';'+$taskSaved.PATH;$env:GOOS='android';$env:CGO_ENABLED='1';$env:GOTOOLCHAIN='local'
    Remove-Item Env:GOROOT -ErrorAction SilentlyContinue
    $env:GOENV='off';$env:GOFLAGS='';$env:GOWORK='off';$env:GOEXPERIMENT='';$env:GOPROXY='off'
    $env:GOTMPDIR=Join-Path $taskStage 'tmp';New-Item -ItemType Directory -Path $env:GOTMPDIR|Out-Null
    $env:TMP=$env:GOTMPDIR;$env:TEMP=$env:GOTMPDIR
    $env:GOCACHE='D:/MCRF/Projects/MCRF/work/toolchains/go-cache';$env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-path';$env:GOMODCACHE=Join-Path $env:GOPATH 'pkg/mod'
    $env:CGO_CFLAGS='';$env:CGO_CPPFLAGS='';$env:CGO_CXXFLAGS='';$env:CGO_LDFLAGS=''
    $taskVersion=(& $taskGo env GOVERSION)
    if($LASTEXITCODE -or $taskVersion -cne 'go1.27.2'){throw 'Pinned Go1.27.2 required; do not alter frozen go.mod or auto-download another compiler'}
    $taskReceipt.toolchainVersion=$taskVersion;$taskReceipt.toolchainSha256=(Get-FileHash -LiteralPath $taskGo -Algorithm SHA256).Hash.ToLowerInvariant();Save-S3NativeReceipt
    & $taskGo mod verify
    if($LASTEXITCODE){throw 'Cached dependency hashes changed; no native compilation admitted'}
    if($taskUnderlay){
        $env:GOOS='windows';$env:GOARCH='amd64';$env:CGO_ENABLED='0'
        $taskHostLog=Join-Path $taskStage 's3-underlay-host-tests.log'
        & $taskGo test -mod=readonly -count=1 -timeout 45s -v -run '^TestMcrf' ./proxy/fedarisha/storage/s3 *> $taskHostLog
        $taskHostExit=$LASTEXITCODE
        $taskHostTests=@(Get-Content -LiteralPath $taskHostLog | ForEach-Object {if($_ -cmatch '^--- PASS: (TestMcrf\w+) '){$Matches[1]}})
        $taskExpected=@('TestMcrfUnderlayRejectsInvalidOrUnavailableProcessNetwork','TestMcrfS3HTTPClientRejectsUnavailableSocketBinding')
        if($taskHostExit -or $taskHostTests.Count -ne $taskExpected.Count -or @($taskExpected|Where-Object {$_ -cnotin $taskHostTests}).Count){
            throw 'Source-bound S3 underlay host tests did not pass'
        }
        $taskReceipt.underlayHostTests=@{cases=$taskHostTests;sha256=(Get-FileHash -LiteralPath $taskHostLog -Algorithm SHA256).Hash.ToLowerInvariant()}
        $env:GOOS='android';$env:CGO_ENABLED='1'
    }
    foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
        $taskReceipt.currentABI=$taskAbi;Save-S3NativeReceipt
        switch($taskAbi){
            'arm64-v8a'{$env:GOARCH='arm64';Remove-Item Env:GOARM -ErrorAction SilentlyContinue;$taskTriple='aarch64-linux-android29'}
            'armeabi-v7a'{$env:GOARCH='arm';$env:GOARM='7';$taskTriple='armv7a-linux-androideabi29'}
            'x86_64'{$env:GOARCH='amd64';Remove-Item Env:GOARM -ErrorAction SilentlyContinue;$taskTriple='x86_64-linux-android29'}
        }
        $env:CC="clang --target=$taskTriple"
        $taskDirectory=Join-Path $taskStage $taskAbi;New-Item -ItemType Directory -Path $taskDirectory|Out-Null
        $taskBinary=Join-Path $taskDirectory 'libs3bypass.so'
        # Exact pinned upstream Android release uses checklinkname=0 for anet's
        # Android-only net.zoneCache reference (.github/workflows/release.yml).
        # Keep that platform build contract; do not patch dependencies or downgrade Go.
        & $taskGo build -mod=readonly -trimpath -buildvcs=false -buildmode=pie '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o $taskBinary ./main 2>&1|Tee-Object -FilePath (Join-Path $taskStage "build-$taskAbi.log")
        if($LASTEXITCODE){throw 'S3 native compilation failed; inspect this receipt, do not duplicate producer'}
        Assert-S3Source
        $taskHeaders=@(& (Join-Path $taskCompiler 'llvm-readelf.exe') --wide --file-header --program-headers $taskBinary)
        $taskMachine=switch($taskAbi){'arm64-v8a'{'AArch64'}'armeabi-v7a'{'ARM'}'x86_64'{'Advanced Micro Devices X86-64'}}
        if($LASTEXITCODE -or !($taskHeaders -match 'Type:.*DYN') -or !($taskHeaders -match ('Machine:\s+'+[regex]::Escape($taskMachine)))){throw 'S3 native ELF identity mismatch'}
        $taskLoads=@($taskHeaders|Where-Object {$_ -match '^\s+LOAD\s'})
        if(!$taskLoads.Count -or @($taskLoads|Where-Object {$_ -notmatch '\s0x4000\s*$'}).Count){throw 'S3 native lacks16KiB LOAD alignment'}
        $taskArtifact=@{abi=$taskAbi;file="$taskAbi/libs3bypass.so";bytes=(Get-Item -LiteralPath $taskBinary).Length;sha256=(Get-FileHash -LiteralPath $taskBinary -Algorithm SHA256).Hash.ToLowerInvariant();elfChecked=$true}
        $taskBinaryText=if($taskUnderlay -or $taskPinnedUdp){[Text.Encoding]::Latin1.GetString([IO.File]::ReadAllBytes($taskBinary))}
        if($taskPinnedUdp -and !$taskBinaryText.Contains('MCRF_SOCKS_UDP_SOURCE_V1_s3bypass')){throw 'S3 private SOCKS UDP source marker missing'}
        if($taskUnderlay){
            $taskMarker='MCRF_S3BYPASS_UNDERLAY'
            $taskImports=@('android_setprocnetwork','android_setsocknetwork')
            $taskSymbols=@(& (Join-Path $taskCompiler 'llvm-readelf.exe') --wide --dyn-syms $taskBinary)
            if($LASTEXITCODE -or !$taskBinaryText.Contains($taskMarker)){throw 'S3 physical binding marker missing'}
            foreach($taskSymbol in $taskImports){
                if(!($taskSymbols -match "\bUND\s+$taskSymbol(?:@|\s|$)")){throw "S3 physical binding import missing: $taskSymbol"}
            }
            $taskArtifact.underlayMarker=$taskMarker;$taskArtifact.underlayImports=$taskImports
        }
        $taskReceipt.artifacts+=$taskArtifact
        Save-S3NativeReceipt;Write-Output "S3_BYPASS_COMPILED=$taskAbi"
    }
    & $taskGo mod verify
    if($LASTEXITCODE){throw 'Dependency hashes changed during native compilation; artifacts retained, not admitted'}
    $taskReceipt.state='COMPILED_NOT_EXECUTED';$taskReceipt.sourceStable=$true;$taskReceipt.currentABI=$null
    $taskReceipt.packageVersionCode=if($taskPinnedUdp){3}elseif($taskUnderlay){2}else{1}
    $taskReceipt.packageVersionName="0.1.0-s3bypass.$($taskReceipt.packageVersionCode)";Save-S3NativeReceipt
    & (Join-Path $PSScriptRoot 'collect-notices.ps1') -Stage $taskStage -Go $taskGo
    $taskReceipt=Get-Content -LiteralPath (Join-Path $taskStage 'receipt.json') -Raw|ConvertFrom-Json -AsHashtable
    Assert-S3Source
}catch{$taskReceipt.state=if(@($taskReceipt.artifacts).Count -eq 3){'COMPILED_NOTICES_PENDING'}else{'FAILED_NATIVE_BUILD'};Save-S3NativeReceipt;throw}
finally{Pop-Location;foreach($taskKey in $taskSaved.Keys){[Environment]::SetEnvironmentVariable($taskKey,$taskSaved[$taskKey],'Process')};$taskReceipt.producerComplete=$true;$taskReceipt.producerFinishedUtc=[DateTime]::UtcNow.ToString('o');Save-S3NativeReceipt}
