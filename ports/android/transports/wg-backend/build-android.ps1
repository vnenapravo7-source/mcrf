param([string]$HostEvidence='',[switch]$P2Candidate,[switch]$OwnerIsolationCandidate,[string]$ResumeStage='')
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
if(!$OwnerIsolationCandidate){& (Join-Path $root 'ports/android/maintenance/Assert-HumanBatchReady.ps1') -Operation native-build}
if($OwnerIsolationCandidate -and ($P2Candidate -or $ResumeStage)){throw 'Owner admission uses a fresh source-bound candidate'}
if($args.Count){throw 'Unknown WG native build arguments'}
if(!$HostEvidence){throw 'Provide the exact completed common WG host receipt via -HostEvidence; native compilation never reruns tests implicitly'}
$taskHostPath=[IO.Path]::GetFullPath($HostEvidence)
if(!$taskHostPath.StartsWith((Join-Path $root 'work')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName([IO.Path]::GetDirectoryName($taskHostPath)) -cnotmatch '^wg-host-[0-9a-f]{32}$' -or [IO.Path]::GetFileName($taskHostPath) -cne 'receipt.json'){throw 'Exact owned WG host receipt required'}
$taskHost=Get-Content -LiteralPath $taskHostPath -Raw|ConvertFrom-Json
$taskAndroidKernel=$taskHost.state -ceq 'OWNED_ANDROID_KERNEL_PASS_APP_UNVERIFIED'
$taskKernelProof=$null
if($taskAndroidKernel){
    if(!$P2Candidate){throw 'Android kernel proof belongs to whole P2 only'}
    $taskKernelProof=& "$root/ports/android/maintenance/Read-P2KernelEvidence.ps1" -Path $taskHost.kernelEvidence -Component wg
    if($taskHost.kernelEvidenceSha256 -cne $taskKernelProof.sha256 -or $taskHost.kernelFrozenUpstream -cne "$($taskKernelProof.executedSourceStage)/wg-upstream"){throw 'Actual tested frozen WG origin differs'}
}
if((Test-Path -LiteralPath (Join-Path $PSScriptRoot 'route_pipe.go')) -and !$P2Candidate -and !$OwnerIsolationCandidate){throw 'Current private packet-pipe source requires an explicit source-bound native recipe'}
$taskP2GateSha=$null
if($P2Candidate){
    $taskCurrent=Get-Content -LiteralPath (Join-Path $root 'ports/android/coordination/current.json') -Raw|ConvertFrom-Json
    $taskGate=Join-Path $root 'ports/android/coordination/p2-progress-2026-10-20.json'
    $taskP2GateSha=(Get-FileHash -LiteralPath $taskGate).Hash.ToLowerInvariant()
    if($taskCurrent.activeBatch -cne 'p2-progress-2026-10-20.json' -or $taskHost.priority -ne 2 -or $taskHost.cleanupPending -or
        $taskHost.sourceReadySha256 -cne $taskP2GateSha -or $taskHost.target -notin @('linux/amd64','linux/arm64') -or
        $taskHost.producerSha256 -cne (Get-FileHash -LiteralPath $(if($taskAndroidKernel){"$root/ports/android/maintenance/Project-P2KernelEvidence.ps1"}else{Join-Path $PSScriptRoot 'test-p2-pipe.ps1'})).Hash.ToLowerInvariant() -or
        $taskHost.testLogSha256 -cne (Get-FileHash -LiteralPath (Join-Path ([IO.Path]::GetDirectoryName($taskHostPath)) 'pipe-tests.log')).Hash.ToLowerInvariant()){
        throw 'Exact completed whole-P2 Linux packet-pipe evidence required; Windows host tests do not cover it'
    }
    $taskRequiredPipe=@('TestPacketPipeOwnershipAndBothIpFamilies','TestPacketPipeRefusesOtherOwnerTypeAndSharedBlockingFlag','TestPacketPipeNeverAdmitsTruncatedOrNonIpDatagram','TestPacketPipeCloseUnblocksActualPendingRead','TestPacketPipeCloseUnblocksActualBackpressuredWrite','TestPacketPipeEncryptedDualStack')
    if(((@($taskHost.executedTests)|Sort-Object)-join ',') -cne (($taskRequiredPipe|Sort-Object)-join ',') -or
        ((@($taskHost.encryptedPipeProtocols)|Sort-Object)-join ',') -cne 'AWG3,AWG3_1,WG'){throw 'All six packet-pipe cases and three actual encrypted protocols required'}
}
if($OwnerIsolationCandidate){
    $testProducer=Join-Path $PSScriptRoot 'test-owner-isolation.ps1'
    $testLog=Join-Path ([IO.Path]::GetDirectoryName($taskHostPath)) 'pipe-tests.log'
    if($taskHost.state -cne 'OWNED_ANDROID_OWNER_ISOLATION_PASS' -or $taskHost.ownerIsolationBatch -ne $true -or
       $taskHost.cleanupPending -ne $false -or $taskHost.environment -cne 'android-shell-kernel' -or
       $taskHost.target -cnotin @('linux/amd64','linux/arm64') -or
       $taskHost.producerSha256 -cne (Get-FileHash -LiteralPath $testProducer).Hash.ToLowerInvariant() -or
       $taskHost.testLogSha256 -cne (Get-FileHash -LiteralPath $testLog).Hash.ToLowerInvariant() -or
       (@($taskHost.encryptedPipeProtocols|Sort-Object)-join ',') -cne 'AWG3,AWG3_1,WG' -or
       @($taskHost.executedTests).Count -ne 6){throw 'Fresh complete Android kernel owner/pipe WG proof is required'}
    foreach($required in @('TestOwnerGateChecksTcpReuseUdpAndUnknownOwner','TestOwnerGateRejectsFragmentsAndOtherProtocols','TestOwnerGateIpv6UdpWithDestinationOptionsAndNewGeneration')){
        if($required -cnotin @($taskHost.tests)){throw 'Missing WG owner gate case'}
    }
}
if($taskHost.passed -isnot [bool] -or !$taskHost.passed -or $taskHost.sourceStable -isnot [bool] -or !$taskHost.sourceStable -or $taskHost.upstreamCommit -cne '64065e6826ad82aa28429dd829c1c65f29b7f6c9'){throw 'WG host gate is not a completed source-bound PASS; do not rerun it implicitly'}
foreach($taskRequired in @('TestAmneziaVariantsEncryptedDualStack/AWG2','TestAmneziaVariantsEncryptedDualStack/AWG3','TestAmneziaVariantsEncryptedDualStack/AWG3_1')){
    if($taskRequired -cnotin @($taskHost.tests)){throw 'WG native producer requires actual AWG2/3/3.1 dual-stack results, not an old aggregate test count'}
}
foreach($taskRequired in @('TestManagedDnsNatDualStackKernelRoundTrip','TestManagedDnsNatOutOfOrderFragments','TestManagedDnsNatBoundsExpiryAndStop','TestManagedDnsNatNewSynRetiresOldOriginalTupleLease','TestManagedDnsTunNoAliasPeerLeakAndPermittedAfter','TestManagedDnsTunAcceptsBindBatchCapacity')){
    if($taskRequired -cnotin @($taskHost.tests)){throw 'WG native producer requires source-bound managed DNS results, not legacy AWG-only evidence'}
}
$taskGo=Join-Path $root 'work/toolchains/go1.26.8/go/bin/go.exe'
if((Get-FileHash -LiteralPath $taskGo).Hash.ToLowerInvariant() -cne $taskHost.goSha256){throw 'WG compiler differs from host gate'}
$taskLock=[Threading.Mutex]::new($false,'Local\MCRF-WG-Pipeline')
if(!$taskLock.WaitOne(0)){throw 'WG native producer already active; continue existing process'}
try {
$upstream=Join-Path $root 'work/android-upstream/sing-box-lx-mcrf-clash-20261014/submodules/wireguard-go'
if(((& git -C $upstream rev-parse HEAD|Out-String).Trim()) -cne $taskHost.upstreamCommit -or ((& git -C $upstream status --porcelain|Out-String).Trim())){throw 'Pinned WG upstream differs from host proof'}
$canonicalUpstream=$upstream
if($taskAndroidKernel){$upstream=$taskHost.kernelFrozenUpstream}
if($ResumeStage){
    if(!$taskAndroidKernel -or !$P2Candidate){throw 'Only completed P2 kernel-bound compilation can resume finalization'}
    $stage=[IO.Path]::GetFullPath($ResumeStage)
    if([IO.Path]::GetDirectoryName($stage) -cne [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build')) -or [IO.Path]::GetFileName($stage) -cnotmatch '^candidate-[a-f0-9]{32}$' -or (Get-Item -LiteralPath $stage).LinkType){throw 'Exact ordinary existing candidate stage required'}
    $state=Get-Content -LiteralPath (Join-Path $stage 'receipt.json') -Raw|ConvertFrom-Json -AsHashtable
    if($state.state -cne 'failed' -or $state.error -cne 'WG upstream commit changed' -or $state.hostEvidenceSha256 -cne (Get-FileHash -LiteralPath $taskHostPath).Hash.ToLowerInvariant() -or $state.sourceReadySha256 -cne $taskP2GateSha -or $state.kernelEvidenceSha256 -cne $taskKernelProof.sha256){throw 'Resume requires exact post-compiler Git-only failure and unchanged proof'}
    if(Test-Path -LiteralPath (Join-Path $stage 'pre-finalization-receipt.json')){throw 'Finalization already attempted; inspect rather than duplicate'}
    Copy-Item -LiteralPath (Join-Path $stage 'receipt.json') -Destination (Join-Path $stage 'pre-finalization-receipt.json')
    $source=Join-Path $stage 'source';$manifest=@($state.source)
    $state.finalizationOnly=$true
    $state.originalReceiptSha256=(Get-FileHash -LiteralPath (Join-Path $stage 'pre-finalization-receipt.json')).Hash.ToLowerInvariant()
    $state.buildLogSha256=if(Test-Path -LiteralPath (Join-Path $stage 'build.log')){(Get-FileHash -LiteralPath (Join-Path $stage 'build.log')).Hash.ToLowerInvariant()}else{$null}
    $state.originalCompilerSilent=($null -eq $state.buildLogSha256)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive=[IO.Compression.ZipFile]::OpenRead((Join-Path $stage 'wg-backend.aar'))
    try{foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){if(@($archive.Entries|Where-Object FullName -ceq "jni/$abi/libmcrfwg.so").Count -ne 1){throw 'Completed three-ABI AAR required'}}}finally{$archive.Dispose()}
}else{
$stage=Join-Path $PSScriptRoot ('build/candidate-'+[guid]::NewGuid().ToString('N'))
$source=Join-Path $stage 'source';New-Item -ItemType Directory -Path $source -Force|Out-Null
$inputs=@(Get-ChildItem -LiteralPath $PSScriptRoot -File|Where-Object {$_.Extension -eq '.go' -or $_.Name -in @('go.mod','go.sum')})
$manifest=@()
if($inputs.Count -ne @($taskHost.inputs).Count){throw 'WG source set differs from host evidence'}
foreach($file in $inputs){$sha=(Get-FileHash -LiteralPath $file.FullName).Hash;$bound=@($taskHost.inputs|Where-Object {$_.name -ceq $file.Name});if($bound.Count -ne 1 -or $bound[0].sha256 -cne $sha.ToLowerInvariant() -or $file.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'WG source differs from host evidence or is linked'};[IO.File]::Copy($file.FullName,(Join-Path $source $file.Name),$false);if((Get-FileHash (Join-Path $source $file.Name)).Hash -cne $sha){throw 'Source capture changed'};$manifest+=[ordered]@{name=$file.Name;sha256=$sha.ToLowerInvariant()}}
$names=@('PATH','GOPATH','GOCACHE','GOTMPDIR','GOTOOLCHAIN','JAVA_HOME','ANDROID_HOME','ANDROID_NDK_HOME','GOFLAGS','GOWORK','GOENV','TMP','TEMP')
$saved=@{};foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$state=[ordered]@{state='building';upstreamCommit='64065e6826ad82aa28429dd829c1c65f29b7f6c9';source=$manifest;hostTests=@($taskHost.tests).Count;hostEvidence=$taskHostPath;hostEvidenceSha256=(Get-FileHash -LiteralPath $taskHostPath).Hash.ToLowerInvariant();managedDnsContract='owned_kernel_dns_nat_v1';fullSingboxDependency=$false;gvisorDependency=$false;deviceTested=$false;published=$false;targets=@('arm64-v8a','armeabi-v7a','x86_64')}
}
function SaveReceipt{[IO.File]::WriteAllText((Join-Path $stage 'receipt.json'),($state|ConvertTo-Json -Depth 5))}
if($P2Candidate){$state.priority=2;$state.sourceReadySha256=$taskP2GateSha;$state.packetPipeContract='owned_unix_dgram_v1';$state.retainedHostCases=@($taskHost.retainedTests).Count;$state.executedPipeCases=@($taskHost.executedTests).Count;$state.encryptedPipeProtocols=@($taskHost.encryptedPipeProtocols)}
if($OwnerIsolationCandidate){$state.vpnOwnerIsolation=1;$state.packetPipeContract='owned_unix_dgram_v1';$state.executedPipeCases=@($taskHost.executedTests).Count;$state.encryptedPipeProtocols=@($taskHost.encryptedPipeProtocols)}
if($taskAndroidKernel){$state.kernelEvidence=$taskHost.kernelEvidence;$state.kernelEvidenceSha256=$taskKernelProof.sha256;$state.kernelExecutionEnvironment='android-shell-kernel';$state.androidAppAccepted=$false}
SaveReceipt
try {
    if(!$ResumeStage){
    $goBin=Join-Path $root 'work/toolchains/go1.26.8/go/bin';$mobile=Join-Path $root 'work/toolchains/gomobile-v0.1.13'
    $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:PATH=$goBin+';'+$mobile+';'+(Join-Path $env:JAVA_HOME 'bin')+';'+$env:PATH
    $env:GOPATH=Join-Path $root 'work/toolchains/go-home';$env:GOCACHE=Join-Path $root 'work/toolchains/go-cache-wgbackend';$env:GOTMPDIR=Join-Path $stage 'tmp';New-Item -ItemType Directory -Path $env:GOTMPDIR|Out-Null
    $env:TMP=$env:GOTMPDIR;$env:TEMP=$env:GOTMPDIR
    $env:GOTOOLCHAIN='local';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:ANDROID_NDK_HOME=Join-Path $env:ANDROID_HOME 'ndk/27.2.12479018';$env:GOFLAGS='-mod=readonly -p=4';$env:GOWORK='off';$env:GOENV='off'
    Push-Location $source
    try {
        & (Join-Path $goBin 'go.exe') mod edit "-replace=github.com/sagernet/wireguard-go=$upstream";if($LASTEXITCODE -ne 0){throw 'Frozen source pin preparation failed'}
        $state.frozenInputs=@($inputs|ForEach-Object {[pscustomobject]@{name=$_.Name;sha256=(Get-FileHash -LiteralPath (Join-Path $source $_.Name)).Hash.ToLowerInvariant()}})
        foreach($row in $state.frozenInputs){$bound=@($taskHost.frozenInputs|Where-Object {$_.name -ceq $row.name});if($bound.Count -ne 1 -or $bound[0].sha256 -cne $row.sha256){throw 'Actual WG compiler inputs differ from proven host snapshot'}}
        SaveReceipt
        & (Join-Path $goBin 'go.exe') mod verify;if($LASTEXITCODE -ne 0){throw 'Frozen module verification failed'}
        $arguments=@('bind','-o',(Join-Path $stage 'wg-backend.aar'),'-target','android/arm64,android/arm,android/amd64','-androidapi','29','-javapkg=org.mcrf.wgbackend','-libname=mcrfwg','-trimpath','-buildvcs=false','-ldflags','-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384','.')
        & (Join-Path $mobile 'gomobile.exe') @arguments 2>&1|Tee-Object -FilePath (Join-Path $stage 'build.log')
        if($LASTEXITCODE -ne 0){throw 'Standalone WG/AWG Android binding build failed'}
    }finally{Pop-Location}
    }
    if(((& git -C $canonicalUpstream status --porcelain)|Out-String).Trim()){throw 'Upstream input changed during build'}
    if(((& git -C $canonicalUpstream rev-parse HEAD|Out-String).Trim()) -cne $state.upstreamCommit){throw 'WG upstream commit changed'}
    if($taskAndroidKernel){$null=& "$root/ports/android/maintenance/Read-P2KernelEvidence.ps1" -Path $taskHost.kernelEvidence -Component wg}
    foreach($row in $state.frozenInputs){if((Get-FileHash -LiteralPath (Join-Path $source $row.name)).Hash.ToLowerInvariant() -cne $row.sha256){throw 'Frozen native WG input changed'}}
    foreach($row in $manifest){if((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $row.name)).Hash.ToLowerInvariant() -cne $row.sha256){throw 'Canonical WG source changed'}}
    if(@(Get-ChildItem -LiteralPath $source -File).Count -ne $manifest.Count -or @(Get-ChildItem -LiteralPath $PSScriptRoot -File|Where-Object {$_.Extension -ceq '.go' -or $_.Name -in @('go.mod','go.sum')}).Count -ne $manifest.Count){throw 'WG native compiler source set changed'}
    if($P2Candidate -and (Get-FileHash -LiteralPath $taskGate).Hash.ToLowerInvariant() -cne $taskP2GateSha){throw 'Whole-P2 readiness changed during native compilation'}
    $aar=Get-Item (Join-Path $stage 'wg-backend.aar');$state.state='built_awaiting_native_and_device_gates';$state.aarBytes=$aar.Length;$state.aarSha256=(Get-FileHash -LiteralPath $aar.FullName).Hash.ToLowerInvariant();SaveReceipt
}catch{$state.state='failed';$state.error=$_.Exception.Message;SaveReceipt;throw}
finally{foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}}
Write-Output "WG_ANDROID_CANDIDATE=$stage"
}finally{$taskLock.ReleaseMutex();$taskLock.Dispose()}
