param([Parameter(Mandatory=$true)][string]$SourceSnapshot,
      [ValidateSet('qwdtt','csqtt','csqtt25','wdtt')][string]$Kind='qwdtt',
      [string]$TransportPath='',
      [string]$LegacyTransportStage='',
      [string]$HostEvidence='',
      [string]$ProcessGroupEvidence='',
      [string]$RetainedCsqtt25AffectedStage='',
      [string]$RetainedFailureStage='',
      [string]$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018')
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot '../../maintenance/Assert-HumanBatchReady.ps1') -Operation native-build
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$taskCurrent=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../../coordination/current.json') -Raw|ConvertFrom-Json
$taskP2=$taskCurrent.activeBatch -ceq 'p2-progress-2026-10-20.json'
$taskExpansion=$taskCurrent.activeBatch -ceq 'p2-expansion-2026-10-21.json'
$taskExpansionSha=if($taskExpansion){(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot '../../coordination/p2-expansion-2026-10-21.json')).Hash.ToLowerInvariant()}else{$null}
if($taskExpansion -and ($HostEvidence -or $ProcessGroupEvidence)){throw 'Expanded P2 uses its own affected checks and explicit unchanged-owner retention; old batch inputs cannot authorize it'}
$taskP2Sha=if($taskP2){(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot '../../coordination/p2-progress-2026-10-20.json')).Hash.ToLowerInvariant()}else{$null}
if($taskP2 -and ($Kind -cnotin @('csqtt','csqtt25') -or !$HostEvidence -or !$ProcessGroupEvidence)){throw 'P2 requires retained unchanged waiter evidence plus exact NEW owned Linux group evidence; no unchanged native/suite rerun'}
$taskSource=[IO.Path]::GetFullPath($SourceSnapshot)
$taskAllowed=Join-Path $taskRoot 'work/ports-test-2026-10-14'
if(!$taskSource.StartsWith($taskAllowed+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
   [IO.Path]::GetFileName($taskSource) -notmatch '^swg-mobile-[0-9a-f]{32}$' -or
   (Get-Item -LiteralPath $taskSource).LinkType){throw 'Use an existing isolated SWG source snapshot, never the pristine checkout'}
$taskModule=Join-Path $taskSource $(if($Kind -eq 'wdtt'){'wdtt/go_client'}else{'qwdtt/qwdtt'})
if($taskP2 -or $taskExpansion){
    $prepared=Get-Content -LiteralPath (Join-Path $taskSource $(if($taskExpansion){'prepared-expansion-source.json'}else{'prepared-p2-source.json'})) -Raw|ConvertFrom-Json
    if($prepared.priority -ne 2 -or $prepared.kind -cne $Kind -or $prepared.state -cne 'PREPARED_NOT_COMPILED' -or !$prepared.sourceStable -or
       $prepared.sourceReadySha256 -cne $(if($taskExpansion){$taskExpansionSha}else{$taskP2Sha}) -or $prepared.preparerSha256 -cne (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'build.ps1')).Hash.ToLowerInvariant()){
        throw 'Exact source-only P2 snapshot origin required; do not relabel an old source stage'
    }
    $actual=@(Get-ChildItem -LiteralPath $taskModule -Recurse -File|Where-Object {$_.Extension -eq '.go' -or $_.Name -in @('go.mod','go.sum')})
    if($actual.Count -ne @($prepared.inputs).Count){throw 'P2 prepared source set changed'}
    foreach($row in $prepared.inputs){
        if([IO.Path]::IsPathRooted($row.path) -or '..' -in ($row.path -split '[/\\]')){throw 'Unsafe prepared source path'}
        $file=Get-Item -LiteralPath (Join-Path $taskModule $row.path)
        if($file.LinkType -or $file.PSIsContainer -or (Get-FileHash -LiteralPath $file.FullName).Hash.ToLowerInvariant() -cne $row.sha256){throw 'Prepared P2 source changed'}
    }
    if(!$prepared.canonicalInputs -or @($prepared.canonicalInputs.PSObject.Properties).Count -lt 10){throw 'Canonical P2 preparation inventory missing'}
    foreach($property in $prepared.canonicalInputs.PSObject.Properties){
        if([IO.Path]::IsPathRooted($property.Name) -or '..' -in ($property.Name -split '[/\\]')){throw 'Unsafe canonical P2 source path'}
        if((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $property.Name)).Hash.ToLowerInvariant() -cne $property.Value){throw 'Canonical P2 preparation input changed'}
    }
}
$taskRequiredInputs=if($Kind -eq 'wdtt'){@('mcrf_wdtt.go','mcrf_wdtt_test.go','go.mod','go.sum')}else{@('mcrf_captcha.go','mcrf_captcha_test.go','go.mod','go.sum')}
foreach($taskRequired in $taskRequiredInputs){
    if(!(Test-Path -LiteralPath (Join-Path $taskModule $taskRequired) -PathType Leaf)){throw 'Snapshot lacks exact-waiter source/checks'}
}
$taskDeadlineHelper=if($Kind -eq 'wdtt'){'mcrf_wdtt.go'}else{'mcrf_captcha.go'}
if((Get-FileHash -LiteralPath (Join-Path $taskModule $taskDeadlineHelper) -Algorithm SHA256).Hash -cne
   (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $taskDeadlineHelper) -Algorithm SHA256).Hash){throw 'Old frozen helper cannot be relabelled as a new deadline-fixed package'}
if($Kind -eq 'wdtt' -and !(Get-Content -LiteralPath (Join-Path $taskModule 'creds.go') -Raw).Contains('mcrfWdttCaptchaActive(waitCtx)')){throw 'WDTT snapshot lacks the deadline delivery guard'}
$taskGroupContract=0
$taskGroupInputs=@()
if($Kind -in @('csqtt','csqtt25')){
    foreach($taskGroupName in @('mcrf_process_group.go','mcrf_process_group_linux.go','mcrf_process_group_other.go','mcrf_child_input.go')){
        $taskGroupFile=Join-Path $taskModule $taskGroupName
        if(!(Test-Path -LiteralPath $taskGroupFile -PathType Leaf) -or (Get-Item -LiteralPath $taskGroupFile).LinkType -or
           (Get-FileHash -LiteralPath $taskGroupFile -Algorithm SHA256).Hash -cne
           (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $taskGroupName) -Algorithm SHA256).Hash){throw 'Frozen CSQTT process-group helper missing or changed'}
        $taskGroupInputs+=@{file=$taskGroupName;sha256=(Get-FileHash -LiteralPath $taskGroupFile -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
    $taskGroupBridge=Join-Path $taskModule 'csqtt_bridge.go'
    $taskGroupBody=Get-Content -LiteralPath $taskGroupBridge -Raw
    if(!$taskGroupBody.Contains('mcrfPrepareProcessGroup()') -or !$taskGroupBody.Contains('processOwner.await(ctx)')){throw 'Frozen CSQTT wrapper lacks the before-child ownership barrier'}
    $taskGroupInputs+=@{file='csqtt_bridge.go';sha256=(Get-FileHash -LiteralPath $taskGroupBridge -Algorithm SHA256).Hash.ToLowerInvariant()}
    $taskGroupContract=1
}
$taskHostReceipt=$null
if($HostEvidence){
    $taskHostEvidence=[IO.Path]::GetFullPath($HostEvidence)
    $taskEvidenceRoot=[IO.Path]::GetDirectoryName($taskHostEvidence)
    if([IO.Path]::GetFileName($taskHostEvidence) -cne 'receipt.json' -or
       [IO.Path]::GetDirectoryName($taskEvidenceRoot) -ne [IO.Path]::GetFullPath($taskAllowed) -or
       [IO.Path]::GetFileName($taskEvidenceRoot) -cnotmatch '^(wdtt-captcha-clock|captcha-delivery-clock)-[0-9a-f]{32}$' -or
       (Get-Item -LiteralPath $taskEvidenceRoot).LinkType){throw 'Explicit owned scoped host receipt required'}
    $taskHostReceipt=Get-Content -LiteralPath $taskHostEvidence -Raw|ConvertFrom-Json
    if($taskHostReceipt.state -ne 'HOST_CHECKS_PASSED' -or !$taskHostReceipt.sourceStable -or $taskHostReceipt.passedTests -le 0 -or
       (!$taskP2 -and [IO.Path]::GetFullPath($taskHostReceipt.stage) -ne $taskSource)){throw 'Host evidence does not own this frozen source snapshot'}
    foreach($taskProofFile in @($taskDeadlineHelper,($taskDeadlineHelper -replace '\.go$','_test.go'))){
        if((Get-FileHash -LiteralPath (Join-Path $taskModule $taskProofFile) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskHostReceipt.inputs.$taskProofFile){throw 'Host evidence source identity mismatch'}
    }
    if($Kind -eq 'wdtt' -and
       (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'wdtt-mobile.patch') -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskHostReceipt.inputs.'wdtt-mobile.patch'){
        throw 'WDTT mobile patch changed after host evidence; include it in the next common verification batch'
    }
}
$taskProcessProof=$null
if($taskP2){
    $taskProcessPath=[IO.Path]::GetFullPath($ProcessGroupEvidence)
    $taskProcessRoot=[IO.Path]::GetDirectoryName($taskProcessPath)
    if([IO.Path]::GetDirectoryName($taskProcessRoot) -ine [IO.Path]::GetFullPath('D:/MCRF/Builds/Android') -or
       [IO.Path]::GetFileName($taskProcessRoot) -cnotmatch '^p2-process-group-[0-9a-f]{32}$' -or
       [IO.Path]::GetFileName($taskProcessPath) -cne 'receipt.json' -or (Get-Item -LiteralPath $taskProcessRoot).LinkType){throw 'Exact new Linux group receipt required'}
    $taskProcessProof=Get-Content -LiteralPath $taskProcessPath -Raw|ConvertFrom-Json
    $taskAndroidKernel=$taskProcessProof.state -ceq 'OWNED_ANDROID_KERNEL_PASS_APP_UNVERIFIED'
    if($taskAndroidKernel){
        $kernel=& "$taskRoot/ports/android/maintenance/Read-P2KernelEvidence.ps1" -Path $taskProcessProof.kernelEvidence -Component group
        if($kernel.sha256 -cne $taskProcessProof.kernelEvidenceSha256 -or ((@($kernel.cases)|Sort-Object)-join ',') -cne ((@($taskProcessProof.cases)|Sort-Object)-join ',')){throw 'Actual Android kernel group proof differs'}
    }
    if($taskProcessProof.priority -ne 2 -or !$taskProcessProof.passed -or !$taskProcessProof.sourceStable -or
       $taskProcessProof.state -cnotin @('OWNED_LINUX_GROUP_AND_INPUT_PASS_NOT_ANDROID','OWNED_ANDROID_KERNEL_PASS_APP_UNVERIFIED') -or $taskProcessProof.sourceReadySha256 -cne $taskP2Sha -or
       @($taskProcessProof.cases).Count -ne 8 -or @($taskProcessProof.cases|Where-Object {$_ -ceq 'TestMcrfProcessGroupLinuxRealAckAndWholeTreeRetirement'}).Count -ne 1 -or
       $taskProcessProof.producerSha256 -cne (Get-FileHash -LiteralPath $(if($taskAndroidKernel){"$taskRoot/ports/android/maintenance/Project-P2KernelEvidence.ps1"}else{Join-Path $PSScriptRoot 'test-p2-process-group.ps1'})).Hash.ToLowerInvariant() -or
       $taskProcessProof.logSha256 -cne (Get-FileHash -LiteralPath (Join-Path $taskProcessRoot 'tests.log')).Hash.ToLowerInvariant() -or
       $taskProcessProof.testBinarySha256 -cne (Get-FileHash -LiteralPath (Join-Path $taskProcessRoot 'group-tests-linux')).Hash.ToLowerInvariant()){
        throw 'Actual source-bound new Linux group/retirement result missing'
    }
    foreach($property in $taskProcessProof.inputs.PSObject.Properties){
        if($property.Name -cnotmatch '^mcrf_(process_group|child_input)(_linux)?(_test)?\.go$'){throw 'Unexpected group source inventory'}
        foreach($base in @($taskModule,$PSScriptRoot,$taskProcessRoot)){
            if((Get-FileHash -LiteralPath (Join-Path $base $property.Name)).Hash.ToLowerInvariant() -cne $property.Value){throw 'Executed group/input source differs from compiled wrapper source'}
        }
    }
    if(@($taskProcessProof.inputs.PSObject.Properties).Count -ne 6){throw 'Complete six-file executed group/input source required'}
}
if($Kind -eq 'csqtt25'){
    if(!(Test-Path -LiteralPath (Join-Path $taskModule 'mcrf_csqtt25.go') -PathType Leaf)){throw 'Separate2.5 adapter source required'}
    if(!$TransportPath -or (Get-Item -LiteralPath $TransportPath).Length -ne 9306144 -or
       (Get-FileHash -LiteralPath $TransportPath -Algorithm SHA256).Hash -ne '9DAD51554BAFB2E6CF386A48CC278FB08C434A78ECE40BAFF00FB51F203B237C'){throw 'Exact swg-panel csqtt-app-2.5.1 arm64 artifact required; never substitute legacy/withdrawn prerelease'}
}
$taskLegacy=$null
if($Kind -eq 'csqtt'){
    $taskLegacyRoot=[IO.Path]::GetFullPath($LegacyTransportStage)
    if([IO.Path]::GetDirectoryName($taskLegacyRoot) -ne [IO.Path]::GetFullPath('D:/MCRF/Builds/Android') -or
       [IO.Path]::GetFileName($taskLegacyRoot) -notmatch '^csqtt-legacy-captcha-[0-9a-f]{32}$' -or
       (Get-Item -LiteralPath $taskLegacyRoot).LinkType){throw 'Explicit completed legacy transport stage required'}
    $taskLegacy=Get-Content -LiteralPath (Join-Path $taskLegacyRoot 'receipt.json') -Raw|ConvertFrom-Json
    if($taskLegacy.state -ne 'COMPILED_NOT_EXECUTED' -or !$taskLegacy.sourceStable -or !$taskLegacy.transportOnly -or
       $taskLegacy.revision -ne '71712b07dbabe16fa43f5a248dc69e1ab9f3a270' -or
       @($taskLegacy.artifacts).Count -ne 3){throw 'Legacy native identity/state mismatch'}
    if($taskLegacy.helperSha256 -cne (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'mcrf_captcha_waiters.rs') -Algorithm SHA256).Hash.ToLowerInvariant()){throw 'Legacy transport must contain the current deadline-fixed waiter; do not reuse the published old transport'}
    $taskAccountAdapter=Join-Path $taskRoot 'ports/android/optional-cores/upstream-throne/script/prepare_csqtt.py'
    if($taskLegacy.accountOptionsContract -ne 1 -or
       $taskLegacy.privateAdapterSha256 -cne (Get-FileHash -LiteralPath $taskAccountAdapter -Algorithm SHA256).Hash.ToLowerInvariant() -or
       (Get-FileHash -LiteralPath (Join-Path $taskLegacyRoot 'account-options-adapter.py') -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskLegacy.privateAdapterSha256){throw 'Fresh native CSQTT account/fingerprint adapter required; old compiled transports are not compatible'}
    foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
        $taskRows=@($taskLegacy.artifacts|Where-Object abi -eq $taskAbi)
        if($taskRows.Count -ne 1){throw 'Legacy native ABI set mismatch'}
        $taskNative=Join-Path $taskLegacyRoot "candidates/$taskAbi/libcsqtt_transport.so"
        if((Get-Item -LiteralPath $taskNative).LinkType -or
           (Get-Item -LiteralPath $taskNative).Length -ne $taskRows[0].bytes -or
           (Get-FileHash -LiteralPath $taskNative -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskRows[0].sha256 -or
           !$taskRows[0].elfPIE -or $taskRows[0].alignment -ne 16384){throw 'Legacy native digest/ELF mismatch'}
    }
}
$taskStage=Join-Path 'D:/MCRF/Builds/Android' ($Kind+'-captcha-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskStage | Out-Null
$taskGo=Join-Path $taskRoot 'work/toolchains/go1.26.8/go/bin/go.exe'
$taskClang=Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
if(!(Test-Path -LiteralPath $taskClang -PathType Leaf)){throw 'Existing Android NDK required'}
function Get-InputManifest {
    @(Get-ChildItem -LiteralPath $taskModule -Recurse -File | Where-Object {$_.Extension -eq '.go' -or $_.Name -in @('go.mod','go.sum')} |
      Sort-Object FullName | ForEach-Object {
        if($_.LinkType){throw 'Linked source file rejected'}
        [ordered]@{path=[IO.Path]::GetRelativePath($taskModule,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
      }) | ConvertTo-Json -Depth 4 -Compress
}
$taskManifest=Get-InputManifest
[IO.File]::WriteAllText((Join-Path $taskStage 'source-manifest.json'),$taskManifest,[Text.UTF8Encoding]::new($false))
if($taskGroupContract -eq 1){
    $taskGroupArchive=Join-Path $taskStage 'process-group-inputs';New-Item -ItemType Directory -Path $taskGroupArchive|Out-Null
    foreach($taskGroupInput in $taskGroupInputs){Copy-Item -LiteralPath (Join-Path $taskModule $taskGroupInput.file) -Destination (Join-Path $taskGroupArchive $taskGroupInput.file)}
}
$taskReceipt=[ordered]@{state='ACTIVE';kind=$Kind;source=$taskSource;stage=$taskStage;hostTests='not run';currentABI=$null;artifacts=@();sourceStable=$false;nativeExecuted=$false;apkBuilt=$false;legacyTransportStage=$LegacyTransportStage;captchaWaiterCapability=$(if($Kind -eq 'csqtt'){'csqtt-id48-v1'}else{''});packageVersionCode=$(if($Kind -eq 'csqtt25'){2}else{6});packageVersionName=$(if($Kind -eq 'csqtt25'){'2.5.0-mcrf-candidate.2'}elseif($Kind -eq 'csqtt'){'2.1.9-mcrf-captcha.6'}else{'0.1.0-captcha.6'})}
$taskReceipt.processGroupContract=$taskGroupContract
$taskReceipt.processGroupInputs=$taskGroupInputs
if($taskExpansion){
    $taskReceipt.expansionAuthority='human-20261021-expanded-p2-whole-source-before-common-verification'
    $taskReceipt.expansionReadinessSha256=$taskExpansionSha
    Copy-Item -LiteralPath (Join-Path $taskSource 'prepared-expansion-source.json') -Destination (Join-Path $taskStage 'prepared-expansion-source.json')
    if($taskGroupContract -eq 1){
        $retained=& (Join-Path $PSScriptRoot '../../maintenance/Read-ExpandedP2GroupRetention.ps1') -Module $taskModule -Kind $Kind
        Copy-Item -LiteralPath $retained.oldBridge -Destination (Join-Path $taskStage 'retained-owned-bridge.go')
        Copy-Item -LiteralPath $retained.retainedReceipt -Destination (Join-Path $taskStage 'retained-owned-evidence.json')
        $retained.Remove('oldBridge');$retained.Remove('retainedReceipt')
        [IO.File]::WriteAllText((Join-Path $taskStage 'retained-owned-closure.json'),($retained|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
        $taskReceipt.retainedOwnerClosureSha256=(Get-FileHash -LiteralPath (Join-Path $taskStage 'retained-owned-closure.json')).Hash.ToLowerInvariant()
    }
}
if($Kind -eq 'qwdtt'){
    $taskReceipt.fingerprintContract=1
    $taskReceipt.fingerprintInputs=@()
    $taskFingerprintArchive=Join-Path $taskStage 'fingerprint-inputs'
    New-Item -ItemType Directory -Path $taskFingerprintArchive | Out-Null
    foreach($taskFingerprintName in @('mcrf_qwdtt_fingerprint.go','bridge_config.go','creds.go','creds_vkcalls.go','profiles.go','captcha_v2.go','main.go')){
        $taskFingerprintInput=Join-Path $taskModule $taskFingerprintName
        if(!(Test-Path -LiteralPath $taskFingerprintInput -PathType Leaf)){throw 'QWDTT fingerprint source missing; old stages must not claim the new capability'}
        $taskReceipt.fingerprintInputs+=@{file=$taskFingerprintName;sha256=(Get-FileHash -LiteralPath $taskFingerprintInput -Algorithm SHA256).Hash.ToLowerInvariant()}
        Copy-Item -LiteralPath $taskFingerprintInput -Destination (Join-Path $taskFingerprintArchive $taskFingerprintName)
    }
    $taskReceipt.packageVersionCode=7
    $taskReceipt.packageVersionName='0.1.0-qwdtt-fingerprint.7'
}
if($Kind -eq 'csqtt'){
    $taskReceipt.accountOptionsContract=1
    $taskReceipt.accountAdapterSha256=$taskLegacy.privateAdapterSha256
    $taskReceipt.accountBridgeInputs=@()
    $taskAccountArchive=Join-Path $taskStage 'account-options-inputs'
    New-Item -ItemType Directory -Path $taskAccountArchive | Out-Null
    Copy-Item -LiteralPath (Join-Path $taskLegacyRoot 'account-options-adapter.py') -Destination (Join-Path $taskAccountArchive 'prepare_csqtt.py')
    foreach($taskName in @('csqtt_bridge.go','bridge_config.go')){
        $taskInput=Join-Path $taskModule $taskName
        $taskReceipt.accountBridgeInputs+=@{file=$taskName;sha256=(Get-FileHash -LiteralPath $taskInput -Algorithm SHA256).Hash.ToLowerInvariant()}
        Copy-Item -LiteralPath $taskInput -Destination (Join-Path $taskAccountArchive $taskName)
    }
    $taskReceipt.packageVersionCode=7
    $taskReceipt.packageVersionName='2.1.9-mcrf-account.7'
}
if($Kind -eq 'wdtt'){
    $taskProviderInput=Join-Path $taskModule 'mcrf_wdtt.go'
    $taskReceipt.providerOptionsContract=1
    $taskReceipt.masqueContract=1
    $taskMasqueMain=Join-Path $taskModule 'main.go'
    $taskReceipt.masqueMainInputSha256=(Get-FileHash -LiteralPath $taskMasqueMain -Algorithm SHA256).Hash.ToLowerInvariant()
    Copy-Item -LiteralPath $taskMasqueMain -Destination (Join-Path $taskStage 'masque-main-input.go')
    $taskReceipt.providerOptionsInputSha256=(Get-FileHash -LiteralPath $taskProviderInput -Algorithm SHA256).Hash.ToLowerInvariant()
    Copy-Item -LiteralPath $taskProviderInput -Destination (Join-Path $taskStage 'provider-options-input.go')
    $taskReceipt.packageVersionCode=7
    $taskReceipt.packageVersionName='0.1.0-wdtt-provider.7'
}
if($Kind -eq 'csqtt25'){
    $taskReceipt.packageVersionCode=4
    $taskReceipt.packageVersionName='2.5.1-swgpanel-mcrf.4'
}
if($taskP2){
    $taskReceipt.priority=2;$taskReceipt.sourceReadySha256=$taskP2Sha
    $taskReceipt.producerSha256=(Get-FileHash -LiteralPath $PSCommandPath).Hash.ToLowerInvariant()
    [IO.File]::Copy($PSCommandPath,(Join-Path $taskStage 'native-producer-control.ps1'),$false)
    $taskReceipt.processGroupEvidenceSha256=(Get-FileHash -LiteralPath $taskProcessPath).Hash.ToLowerInvariant()
    $taskReceipt.retainedWaiterEvidenceSha256=(Get-FileHash -LiteralPath $taskHostEvidence).Hash.ToLowerInvariant()
    [IO.File]::Copy($taskProcessPath,(Join-Path $taskStage 'process-group-evidence.json'),$false)
    [IO.File]::Copy((Join-Path $taskProcessRoot 'tests.log'),(Join-Path $taskStage 'process-group-tests.log'),$false)
    [IO.File]::Copy((Join-Path $taskSource 'prepared-p2-source.json'),(Join-Path $taskStage 'prepared-p2-source.json'),$false)
}
if($taskGroupContract -eq 1){
    $taskReceipt.packageVersionCode=if($Kind -eq 'csqtt25'){4}else{7}
    $taskReceipt.packageVersionName=if($Kind -eq 'csqtt25'){'2.5.1-swgpanel-mcrf.4'}else{'2.1.9-mcrf-process.7'}
}
function Save-Receipt {[IO.File]::WriteAllText((Join-Path $taskStage 'receipt.json'),($taskReceipt|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))}
if($taskExpansion){
    # Capabilities are bound to the exact compiled Go ingress, not a version label.
    $taskFeatureFiles=switch($Kind){
        'qwdtt' {@('bridge_config.go')}
        'csqtt' {@('csqtt_bridge.go','internal/csqtt/config.go')}
        'csqtt25' {@('csqtt_bridge.go','internal/csqtt/config.go','internal/csqtt25/profile.go')}
        'wdtt' {@('mcrf_wdtt.go','socks5_server.go','http_connect_server.go')}
    }
    $taskFeatureArchive=Join-Path $taskStage 'turn-feature-inputs'
    New-Item -ItemType Directory -Path $taskFeatureArchive|Out-Null
    $taskReceipt.turnFeatureInputs=@(foreach($name in $taskFeatureFiles){
        $inputFile=Join-Path $taskModule $name
        $archiveFile=Join-Path $taskFeatureArchive $name
        New-Item -ItemType Directory -Path (Split-Path $archiveFile) -Force|Out-Null
        Copy-Item -LiteralPath $inputFile -Destination $archiveFile
        [ordered]@{file=$name;sha256=(Get-FileHash -LiteralPath $inputFile -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
    $taskReceipt.turnHashPoolContract=1
    $taskReceipt.wdttDualFamilyContract=if($Kind -ceq 'wdtt'){1}else{0}
    $taskReceipt.packageVersionCode=if($Kind -ceq 'csqtt25'){5}else{8}
    $taskReceipt.packageVersionName=if($Kind -ceq 'csqtt25'){'2.5.1-swgpanel-mcrf.5'}else{'0.1.0-turn-pool.8'}
}
Save-Receipt
Write-Output "QWDTT_CAPTCHA_STAGE=$taskStage"
$taskSavedEnv=@{}
foreach($taskKey in @('GOPATH','GOCACHE','GOTMPDIR','CGO_ENABLED','GOOS','GOARCH','GOARM','CC')){$taskSavedEnv[$taskKey]=[Environment]::GetEnvironmentVariable($taskKey,'Process')}
Push-Location $taskModule
try{
    $env:GOPATH=Join-Path $taskRoot 'work/toolchains/go-home'
    $env:GOCACHE=Join-Path $taskRoot 'work/toolchains/go-build-cache'
    $env:GOTMPDIR=Join-Path $taskRoot 'work/toolchains/go-tmp'
    New-Item -ItemType Directory -Path $env:GOTMPDIR -Force|Out-Null
    Remove-Item Env:GOOS,Env:GOARCH,Env:GOARM,Env:CC -ErrorAction SilentlyContinue
    $env:CGO_ENABLED='0'
    # Reuse already closed scoped checks only with exact snapshot/source binding.
    if($taskExpansion){
        # One affected native selection per changed module. Never fall through
        # into the old complete captcha/process-group suites.
        $expected=switch($Kind){
            'csqtt' {@('TestCSQTTAccountOptionsRequirePrivateMatchingSession')}
            'qwdtt' {@('TestMcrfQwdttFingerprintHasMatchingBrowserIdentity')}
            'wdtt' {@('TestMcrfMasqueTransportCannotEnrollAndExplicitEnrollmentNeedsConsent','TestMcrfWdttProviderFlagsKeepDefaultsAndExplicitTransport','TestMcrfWdttProviderOptionsRejectUnsafeNamesAndClientLists')}
            'csqtt25' {@('Test25BridgePreservesExactWorkersAndNewSettingsWithoutLegacyRounding','TestRuntimeCannotAcceptMissingOrLegacyOrWrongArtifact','Test25OwnMaskingAndServerGenerationAreNotDropped','Test25UnifiedMaskingAndEmptyFingerprintRetainNewNativeDefaults','Test25AuthenticatedBridgeFieldsNeverBecomeNativeArguments','Test25ExplicitVersionAndWorkersNeverUseLegacyRounding','Test25CaptchaAndOwnTurnSettingsReachNativeSpec','Test25TokenBootstrapDoesNotTruncateOrMixManualHashPool','Test25MalformedUnknownOrUnsafeDataNeverLeaksInErrors')}
        }
        if($Kind -in @('csqtt','csqtt25','qwdtt')){
            $expected+=@('TestMcrfChildFailurePreservesReasonWithoutSecrets','TestMcrfChildFailureRejectsPrivateControlAndOversizedOutput','TestMcrfChildFailureStructuredErrorAndTerminalControls')
        }
        $taskSupplement=Get-Content -LiteralPath (Join-Path $taskRoot 'ports/android/coordination/p2-expansion-2026-10-21.json') -Raw|ConvertFrom-Json
        if($taskSupplement.sourceRevision -ceq 'p2-supplement-20261022.1'){
            # Compile the new failure boundary against each changed wrapper's
            # actual bridgeConfig; do not rerun the unchanged profile suites.
            $expected=if($Kind -in @('csqtt','csqtt25','qwdtt')){@('TestMcrfChildFailurePreservesReasonWithoutSecrets','TestMcrfChildFailureRejectsPrivateControlAndOversizedOutput','TestMcrfChildFailureStructuredErrorAndTerminalControls')}else{$expected}
            if($Kind -ceq 'csqtt25'){$expected+=@('Test25SharedBridgeWaitsForOwnedTransportAndGrandchildCleanupAfterStop','Test25SharedBridgeSendsPrivateBootstrapAndHonorsOwnedStopCleanup','Test25TunnelOptionalPolicyDoesNotBecomeRevisionOrRouting','Test25TunnelIPv6DnsAndMalformedFramesAreHandledWithoutSecrets')}
        }
        $retainedCases=@()
        if($taskSupplement.sourceRevision -ceq 'p2-turn-pool-20261022.2'){
            # New ingress/family cases only. Accepted diagnostic/owned execution
            # remains source-bound by Read-ExpandedP2GroupRetention, not rerun.
            $expected=switch($Kind){
                'csqtt' {@('TestCSQTTExtendedHashPoolSurvivesConfigAndLink','TestExtendedHashesKeepCompletePool')}
                'csqtt25' {@('Test25ExtendedHashPoolReachesNativeArguments','TestCSQTTExtendedHashPoolSurvivesConfigAndLink','TestExtendedHashesKeepCompletePool')}
                'qwdtt' {@('TestMcrfQwdttExtendedHashPool','TestMcrfSixCallBridge')}
                'wdtt' {@('TestMcrfWdttExtendedHashPool','TestMcrfWdttIPv6TCPAddressAndFamily','TestMcrfWdttIPv6UDPReplyAndRequestRoundTrip')}
            }
            if($RetainedFailureStage -or $RetainedCsqtt25AffectedStage){throw 'Old selective ingress retention cannot authorize the new hash pool scope'}
        }
        if($RetainedFailureStage){
            if($Kind -cnotin @('csqtt','csqtt25','qwdtt')){throw 'Failure evidence only covers CS wrappers'}
            $oldStage=[IO.Path]::GetFullPath($RetainedFailureStage)
            if([IO.Path]::GetDirectoryName($oldStage) -cne [IO.Path]::GetFullPath('D:/MCRF/Builds/Android') -or [IO.Path]::GetFileName($oldStage) -cnotmatch ('^'+$Kind+'-captcha-[0-9a-f]{32}$') -or (Get-Item -LiteralPath $oldStage).LinkType){throw 'Exact owned failure stage required'}
            $old=Get-Content "$oldStage/receipt.json" -Raw|ConvertFrom-Json
            if(!$old.sourceStable -or $old.state -cne 'COMPILED_NOT_EXECUTED' -or $old.kind -cne $Kind){throw 'Completed matching failure evidence required'}
            $oldManifest=Get-Content "$oldStage/source-manifest.json" -Raw|ConvertFrom-Json
            foreach($name in @('mcrf_child_failure.go','mcrf_child_failure_test.go','bridge_config.go')){
                $row=@($oldManifest|Where-Object {$_.path.Replace('\','/') -ceq $name})
                $previous=Join-Path (Join-Path $old.source 'qwdtt/qwdtt') $name
                if($row.Count -ne 1 -or (Get-FileHash $previous).Hash.ToLowerInvariant() -cne $row[0].sha256){throw 'Original failure source changed'}
                $prior=[IO.File]::ReadAllText($previous).Replace("`r`n","`n")
                $now=[IO.File]::ReadAllText((Join-Path $taskModule $name)).Replace("`r`n","`n")
                if($name -ceq 'bridge_config.go'){
                    $pattern='(?m)^\s*NormalizeTunnel\s+func\(string\) \(string, error\) `json:"-"`\n'
                    if([regex]::Matches($now,$pattern).Count -ne 1){throw 'Unexpected tunnel hook declaration'}
                    $now=[regex]::Replace($now,$pattern,'')
                }
                if($prior -cne $now){throw 'Failure helper/dependencies changed; retention refused'}
            }
            $oldLog="$oldStage/expansion-affected-tests.jsonl"
            if((Get-FileHash $oldLog).Hash.ToLowerInvariant() -cne $old.affectedLogSha256){throw 'Original failure results changed'}
            $events=@(Get-Content $oldLog|ForEach-Object {$_|ConvertFrom-Json})
            $failureCases=@('TestMcrfChildFailurePreservesReasonWithoutSecrets','TestMcrfChildFailureRejectsPrivateControlAndOversizedOutput','TestMcrfChildFailureStructuredErrorAndTerminalControls')
            if(@($events|Where-Object {$_.Action -cin @('fail','skip')}).Count){throw 'Failed original evidence'}
            foreach($name in $failureCases){if(@($events|Where-Object {$_.Action -ceq 'pass' -and $_.Test -ceq $name}).Count -ne 1){throw 'Missing original failure case'}}
            Copy-Item $oldLog "$taskStage/retained-failure-tests.jsonl"
            $retainedCases=$failureCases
            $taskReceipt.retainedFailure=@{stage=$oldStage;logSha256=$old.affectedLogSha256;newExecution=$false;cases=$failureCases}
            $expected=@($expected|Where-Object {$_ -cnotin $failureCases})
        }
        if($RetainedCsqtt25AffectedStage){
            if($Kind -cne 'csqtt25'){throw 'CS25 fixture retention cannot authorize other modules'}
            $oldStage=[IO.Path]::GetFullPath($RetainedCsqtt25AffectedStage)
            if([IO.Path]::GetDirectoryName($oldStage) -cne [IO.Path]::GetFullPath('D:/MCRF/Builds/Android') -or [IO.Path]::GetFileName($oldStage) -cnotmatch '^csqtt25-captcha-[0-9a-f]{32}$' -or (Get-Item -LiteralPath $oldStage).LinkType){throw 'Exact original affected CS25 stage required'}
            $old=Get-Content -LiteralPath "$oldStage/receipt.json" -Raw|ConvertFrom-Json
            $oldInputs=Get-Content -LiteralPath "$oldStage/source-manifest.json" -Raw|ConvertFrom-Json
            $newInputs=$taskManifest|ConvertFrom-Json
            $changed='internal/csqtt25/profile_test.go'
            $left=(@($oldInputs|Where-Object {$_.path.Replace('\','/') -cne $changed}|ForEach-Object {$_.path.Replace('\','/')+'='+$_.sha256})|Sort-Object) -join "`n"
            $right=(@($newInputs|Where-Object {$_.path.Replace('\','/') -cne $changed}|ForEach-Object {$_.path.Replace('\','/')+'='+$_.sha256})|Sort-Object) -join "`n"
            if($left -cne $right){throw 'Passed CS25 tests cannot be retained after production/helper/other test changes'}
            $oldTest=[IO.File]::ReadAllText((Join-Path $old.source 'qwdtt/qwdtt/internal/csqtt25/profile_test.go'))
            $newTest=[IO.File]::ReadAllText((Join-Path $taskModule $changed))
            function UnchangedFixture([string]$body){
                $start=$body.IndexOf('func Test25ExplicitVersionAndWorkersNeverUseLegacyRounding(',[StringComparison]::Ordinal)
                $end=$body.IndexOf('func Test25CaptchaAndOwnTurnSettingsReachNativeSpec(',[StringComparison]::Ordinal)
                if($start -lt 0 -or $end -le $start){throw 'Unexpected CS25 fixture structure; cannot retain tests'}
                $body.Substring(0,$start)+$body.Substring($end)
            }
            if((UnchangedFixture $oldTest) -cne (UnchangedFixture $newTest)){throw 'Other CS25 fixture/helper changed; do not retain it'}
            $oldEvents=@(Get-Content -LiteralPath "$oldStage/expansion-affected-tests.jsonl"|ForEach-Object {$_|ConvertFrom-Json})
            $failed='Test25ExplicitVersionAndWorkersNeverUseLegacyRounding'
            $retainedCases=@($oldEvents|Where-Object {$_.Action -ceq 'pass' -and $_.Test}|ForEach-Object {$_.Test})
            $fullOld=(@($oldInputs|ForEach-Object {$_.path.Replace('\','/')+'='+$_.sha256})|Sort-Object) -join "`n"
            $fullNew=(@($newInputs|ForEach-Object {$_.path.Replace('\','/')+'='+$_.sha256})|Sort-Object) -join "`n"
            $allRetained=$fullOld -ceq $fullNew -and @($old.retainedAffectedCases).Count -gt 0 -and
                ((@($old.affectedCases)|Sort-Object)-join ',') -ceq (($expected|Sort-Object)-join ',')
            if($allRetained){
                $prior="$oldStage/retained-expansion-affected-tests.jsonl"
                if((Get-FileHash -LiteralPath $prior).Hash.ToLowerInvariant() -cne $old.retainedAffectedLogSha256){throw 'Retained original CS25 log changed'}
                $priorEvents=@(Get-Content -LiteralPath $prior|ForEach-Object {$_|ConvertFrom-Json})
                $priorPassed=@($priorEvents|Where-Object {$_.Action -ceq 'pass' -and $_.Test}|ForEach-Object {$_.Test})
                if((($priorPassed|Sort-Object)-join ',') -cne ((@($old.retainedAffectedCases)|Sort-Object)-join ',') -or
                   ((@($retainedCases)+@($priorPassed)|Sort-Object)-join ',') -cne (($expected|Sort-Object)-join ',') -or
                   @($oldEvents|Where-Object {$_.Action -cin @('fail','skip')}).Count){throw 'Source-identical CS25 retained chain is incomplete'}
                Copy-Item -LiteralPath $prior -Destination "$taskStage/retained-expansion-prior-tests.jsonl"
                $taskReceipt.retainedPriorLogSha256=(Get-FileHash -LiteralPath "$taskStage/retained-expansion-prior-tests.jsonl").Hash.ToLowerInvariant()
                $retainedCases=@($expected)
            }elseif((($retainedCases|Sort-Object)-join ',') -cne ((@($expected|Where-Object {$_ -cne $failed})|Sort-Object)-join ',') -or
                @($oldEvents|Where-Object {$_.Test -and $_.Action -ceq 'fail' -and $_.Test -cne $failed}).Count){throw 'Original affected CS25 results do not match the retained selection'}
            Copy-Item -LiteralPath "$oldStage/expansion-affected-tests.jsonl" -Destination "$taskStage/retained-expansion-affected-tests.jsonl"
            $taskReceipt.retainedAffectedCases=$retainedCases
            $taskReceipt.retainedAffectedLogSha256=(Get-FileHash -LiteralPath "$taskStage/retained-expansion-affected-tests.jsonl").Hash.ToLowerInvariant()
            $expected=if($allRetained){@()}else{@($failed)}
        }
        $filter='^('+($expected -join '|')+')$'
        if($expected.Count){
        & $taskGo test -mod=readonly '-ldflags=-checklinkname=0' -json -run $filter ./... |
            Tee-Object -FilePath (Join-Path $taskStage 'expansion-affected-tests.jsonl') | ForEach-Object {if($_ -match '"Action":"(pass|fail)"'){Write-Output $_}}
        if($LASTEXITCODE -ne 0){throw 'Expanded-P2 affected native checks failed'}
        }else{
            [IO.File]::WriteAllText("$taskStage/expansion-affected-tests.jsonl",'{"Action":"output","Output":"All affected methods retained by full source equality; zero new test execution"}',[Text.UTF8Encoding]::new($false))
        }
        $events=@(Get-Content -LiteralPath (Join-Path $taskStage 'expansion-affected-tests.jsonl')|ForEach-Object {$_|ConvertFrom-Json})
        $passed=@($events|Where-Object {$_.Action -ceq 'pass' -and $_.Test}|ForEach-Object {$_.Test})
        if(@($events|Where-Object {$_.Action -cin @('skip','fail')}).Count -or (($passed|Sort-Object)-join ',') -cne (($expected|Sort-Object)-join ',')){throw 'Exact affected native selection incomplete, duplicated or skipped'}
        $taskReceipt.affectedCases=@($retainedCases)+@($passed)
        $taskReceipt.newAffectedExecutionCount=$passed.Count
        $taskReceipt.affectedLogSha256=(Get-FileHash -LiteralPath (Join-Path $taskStage 'expansion-affected-tests.jsonl')).Hash.ToLowerInvariant()
        $taskReceipt.hostTests="PASS $($passed.Count) newly executed affected cases; $($retainedCases.Count) source-exact cases retained; unchanged ownership evidence separate"
        Save-Receipt
    }elseif($taskHostReceipt){
        $taskReceipt.hostTests="REUSED $($taskHostReceipt.passedTests) scoped host checks; not native/device acceptance"
        $taskReceipt.hostEvidence=$taskHostEvidence;Save-Receipt
        if($taskGroupContract -eq 1 -and !$taskP2){
            & $taskGo test -mod=readonly '-ldflags=-checklinkname=0' -json -run '^(TestMcrfProcessGroup|TestMcrfChildInput|Test25SharedBridge)' ./... |
                Tee-Object -FilePath (Join-Path $taskStage 'process-group-host-tests.jsonl') | ForEach-Object {if($_ -match '"Action":"(pass|fail)"'){Write-Output $_}}
            if($LASTEXITCODE -ne 0){throw 'New CSQTT process group/FIFO/owned child checks failed; retained waiter proof is not sufficient'}
        }
        if($taskP2){
            $taskReceipt.processGroupTests='REUSED exact 8 owned kernel group/input cases, retained cases explicit; not rerun per module'
            if($Kind -ceq 'csqtt25'){
                & $taskGo test -mod=readonly '-ldflags=-checklinkname=0' -json -run '^Test25SharedBridge' ./... |
                    Tee-Object -FilePath (Join-Path $taskStage 'affected-bridge-tests.jsonl') | ForEach-Object {if($_ -match '"Action":"(pass|fail)"'){Write-Output $_}}
                if($LASTEXITCODE -ne 0){throw 'Affected actual bridge STOP/bootstrap checks failed'}
                $events=@(Get-Content -LiteralPath (Join-Path $taskStage 'affected-bridge-tests.jsonl')|ForEach-Object {$_|ConvertFrom-Json})
                $passed=@($events|Where-Object {$_.Action -ceq 'pass' -and $_.Test}|ForEach-Object {$_.Test})
                $expected=@('Test25SharedBridgeWaitsForOwnedTransportAndGrandchildCleanupAfterStop','Test25SharedBridgeSendsPrivateBootstrapAndHonorsOwnedStopCleanup')
                if(@($events|Where-Object {$_.Action -cin @('skip','fail')}).Count -or (($passed|Sort-Object)-join ',') -cne (($expected|Sort-Object)-join ',')){throw 'Exact two affected bridge cases incomplete/skipped'}
                $taskReceipt.affectedBridgeCases=$passed
                $taskReceipt.affectedBridgeLogSha256=(Get-FileHash -LiteralPath (Join-Path $taskStage 'affected-bridge-tests.jsonl')).Hash.ToLowerInvariant()
            }
            Save-Receipt
        }
    }else{
    # No proof was supplied: reproduce targeted checks before cross-compiling.
    $taskHostFilter=if($Kind -eq 'wdtt'){'^(TestMcrf|TestCaptchaResult|TestCaptchaRequest|TestCaptchaSolveStage)'}elseif($Kind -in @('csqtt','csqtt25')){'^(Test25|TestCSQTT|TestMcrfCaptcha|TestMcrfProcessGroup|TestMcrfChildInput)'}else{'^TestMcrfCaptcha'}
    if($Kind -eq 'wdtt'){
        $taskHostExe=Join-Path $taskStage 'owned-host-tests.exe'
        & $taskGo test -mod=readonly -c '-ldflags=-checklinkname=0' -o $taskHostExe .
        if($LASTEXITCODE -ne 0){throw 'Owned WDTT host test compilation failed'}
        & $taskHostExe '-test.v' '-test.timeout=30s' "-test.run=$taskHostFilter" | Tee-Object -FilePath (Join-Path $taskStage 'host-tests.log')
    }else{
        & $taskGo test -mod=readonly '-ldflags=-checklinkname=0' -json -run $taskHostFilter ./... |
            Tee-Object -FilePath (Join-Path $taskStage 'host-tests.jsonl') | ForEach-Object {if($_ -match '"Action":"(pass|fail)"'){Write-Output $_}}
    }
    if($LASTEXITCODE -ne 0){throw 'Exact-waiter host checks failed'}
    $taskReceipt.hostTests="PASS exact $taskHostFilter subset on this snapshot";Save-Receipt
    }
    if($Kind -eq 'csqtt25' -and !$taskP2){
        & $taskGo test -mod=readonly -json -run '^TestSuppliedActualRuntimeStaticGate$' ./internal/csqtt25 -args "-csqtt25-native=$TransportPath" |
            Tee-Object -FilePath (Join-Path $taskStage 'supplied-artifact-static-test.jsonl') | ForEach-Object {if($_ -match '"Action":"(pass|fail)"'){Write-Output $_}}
        if($LASTEXITCODE -ne 0){throw 'Supplied2.5 static runtime/CLI gate failed'}
    }
    $env:GOOS='android';$env:CGO_ENABLED='1'
    foreach($taskAbi in $(if($Kind -eq 'csqtt25'){@('arm64-v8a')}else{@('arm64-v8a','armeabi-v7a','x86_64')})){
        $taskReceipt.currentABI=$taskAbi;Save-Receipt
        switch($taskAbi){
            'arm64-v8a'{$env:GOARCH='arm64';$taskTriple='aarch64-linux-android29';$taskMachine=183;$taskClass=2}
            'armeabi-v7a'{$env:GOARCH='arm';$env:GOARM='7';$taskTriple='armv7a-linux-androideabi29';$taskMachine=40;$taskClass=1}
            'x86_64'{$env:GOARCH='amd64';Remove-Item Env:GOARM -ErrorAction SilentlyContinue;$taskTriple='x86_64-linux-android29';$taskMachine=62;$taskClass=2}
        }
        $env:CC="$taskClang --target=$taskTriple"
        $taskAbiDir=Join-Path $taskStage $taskAbi;New-Item -ItemType Directory -Path $taskAbiDir | Out-Null
        $taskOutput=Join-Path $taskAbiDir $(if($Kind -eq 'csqtt'){'libqwdtt.so'}else{"lib$Kind.so"})
        & $taskGo build -mod=readonly -trimpath -buildmode=pie '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o $taskOutput .
        if($LASTEXITCODE -ne 0){throw "Candidate cross-compile failed: $taskAbi"}
        $taskStream=[IO.File]::OpenRead($taskOutput)
        try{$taskHeader=New-Object byte[] 20;if($taskStream.Read($taskHeader,0,20) -ne 20){throw 'Truncated ELF'}}finally{$taskStream.Dispose()}
        if([BitConverter]::ToString($taskHeader[0..3]) -ne '7F-45-4C-46' -or $taskHeader[4] -ne $taskClass -or $taskHeader[5] -ne 1 -or [BitConverter]::ToUInt16($taskHeader,18) -ne $taskMachine){throw 'Candidate ELF/ABI mismatch'}
        $taskReceipt.artifacts+=@{abi=$taskAbi;file=$taskOutput;bytes=(Get-Item -LiteralPath $taskOutput).Length;sha256=(Get-FileHash -LiteralPath $taskOutput -Algorithm SHA256).Hash.ToLowerInvariant();elfChecked=$true}
        Save-Receipt
        Write-Output "QWDTT_CAPTCHA_COMPILED=$taskAbi"
        if($Kind -eq 'csqtt'){
            $taskNative=Join-Path $taskLegacyRoot "candidates/$taskAbi/libcsqtt_transport.so"
            $taskRow=@($taskLegacy.artifacts|Where-Object abi -eq $taskAbi)[0]
            $taskDestination=Join-Path $taskAbiDir 'libcsqtt_transport.so'
            Copy-Item -LiteralPath $taskNative -Destination $taskDestination
            if((Get-FileHash -LiteralPath $taskDestination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskRow.sha256){throw 'Legacy native changed during pairing'}
            $taskReceipt.artifacts+=@{abi=$taskAbi;file=$taskDestination;bytes=$taskRow.bytes;sha256=$taskRow.sha256;elfChecked=$true;alignment=16384;executed=$false}
            Save-Receipt
        }
    }
    if($Kind -eq 'csqtt'){
        $taskNotices=Join-Path $taskStage 'licenses';New-Item -ItemType Directory -Path $taskNotices|Out-Null
        Copy-Item -LiteralPath (Join-Path $taskModule 'LICENSE') -Destination (Join-Path $taskNotices 'qwdtt-LICENSE')
        Copy-Item -LiteralPath (Join-Path $taskLegacyRoot 'CSQTT-LICENSE') -Destination (Join-Path $taskNotices 'CSQTT-LICENSE')
    }
    if($Kind -eq 'wdtt'){
        $taskNotices=Join-Path $taskStage 'licenses';New-Item -ItemType Directory -Path $taskNotices|Out-Null
        Copy-Item -LiteralPath (Join-Path $taskSource 'wdtt/LICENSE') -Destination (Join-Path $taskNotices 'wdtt-LICENSE')
    }
    if($Kind -eq 'qwdtt'){
        $taskNotices=Join-Path $taskStage 'licenses';New-Item -ItemType Directory -Path $taskNotices|Out-Null
        Copy-Item -LiteralPath (Join-Path $taskModule 'LICENSE') -Destination (Join-Path $taskNotices 'qwdtt-LICENSE')
    }
    if($Kind -eq 'csqtt25'){
        $taskTransport=Join-Path $taskStage 'arm64-v8a/libcsqtt25_transport.so'
        Copy-Item -LiteralPath $TransportPath -Destination $taskTransport
        $taskReceipt.artifacts+=@{abi='arm64-v8a';file=$taskTransport;bytes=9306144;sha256=(Get-FileHash -LiteralPath $taskTransport -Algorithm SHA256).Hash.ToLowerInvariant();supplied=$true;executed=$false}
        $taskNotices=Join-Path $taskStage 'licenses';New-Item -ItemType Directory -Path $taskNotices | Out-Null
        Copy-Item -LiteralPath (Join-Path $taskModule 'LICENSE') -Destination (Join-Path $taskNotices 'qwdtt-LICENSE')
        Copy-Item -LiteralPath 'D:/MCRF/Builds/Android/csqtt25-native-b2049cb1/CSQTT-LICENSE' -Destination (Join-Path $taskNotices 'CSQTT-LICENSE')
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'csqtt25/swg-panel-reference.json') -Destination (Join-Path $taskNotices 'swg-panel-reference.json')
    }
    if((Get-InputManifest) -ne $taskManifest){throw 'Frozen source inputs changed during native build'}
    if($taskExpansion){
        if((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot '../../coordination/p2-expansion-2026-10-21.json')).Hash.ToLowerInvariant() -cne $taskExpansionSha){throw 'Expanded source gate changed during native build'}
        foreach($property in $prepared.canonicalInputs.PSObject.Properties){
            if((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $property.Name)).Hash.ToLowerInvariant() -cne $property.Value){throw 'Canonical expanded source changed during native build'}
        }
    }
    if($taskP2){
        if((Get-FileHash -LiteralPath $PSCommandPath).Hash.ToLowerInvariant() -cne $taskReceipt.producerSha256){throw 'P2 native producer changed during its own run'}
        if((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot '../../coordination/p2-progress-2026-10-20.json')).Hash.ToLowerInvariant() -cne $taskP2Sha){throw 'Whole P2 source gate changed during native build'}
        foreach($property in $prepared.canonicalInputs.PSObject.Properties){
            if((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $property.Name)).Hash.ToLowerInvariant() -cne $property.Value){throw 'Canonical P2 source changed during native build'}
        }
    }
    $taskReceipt.sourceStable=$true;$taskReceipt.state='COMPILED_NOT_EXECUTED';$taskReceipt.currentABI=$null;Save-Receipt
}catch{
    $taskReceipt.state='FAILED';Save-Receipt
    throw
}finally{
    Pop-Location
    foreach($taskKey in $taskSavedEnv.Keys){[Environment]::SetEnvironmentVariable($taskKey,$taskSavedEnv[$taskKey],'Process')}
}
