param(
    [string[]]$Task=@(':app:testDebugUnitTest'),
    [string]$JavaHome="$env:ProgramFiles/Java/jdk-17",
    [string]$AndroidSdk="$env:LOCALAPPDATA/Android/Sdk",
    [string]$OptionalCoreStage='',
    [string]$OptionalFixtureStage='',
    [string]$DnsttFixtureStage='',
    [string]$WarpScannerStage='',
    [string]$WgBackendStage='',
    [string]$PrimaryCoreStage='',
    [string]$DispatcherNativeStage='',
    [string]$OpenFluxPromotionStage='',
    [string]$OpenFluxAckStage='',
    [string]$OpenFluxProviderStage='',
    [string]$OpenFluxProviderV2Stage='',
    [string]$OpenFluxProviderV3Stage='',
    [switch]$TelegramModule,
    [switch]$PreparePrimaryStage,
    [string]$PrimaryStageSteer='outputs/steer-android/primary-stage-dev.5',
    [int]$PrimaryStageVersionCode=6,
    [switch]$ExportEngineApks,
    [switch]$ExportWgBackend,
    [string]$BuildScratchRoot='',
    [string]$Csqtt25Stage='',
    [string]$S3BypassStage='',
    [string]$CsqttLegacyStage='',
    [hashtable]$SwgNativeStages=@{},
    [hashtable]$CsqttHistoricalStages=@{},
    [switch]$TestRelease,
    [switch]$ReleaseSmoke,
    [switch]$BothTestRunners,
    [switch]$ExportTestRunner,
    [switch]$FirstLaunchFixture,
    [switch]$P1OwnerFixture,
    [switch]$P1WgCandidate,
    [switch]$P1SteerCandidate,
    [switch]$P2SteerCandidate,
    [switch]$P2WgCandidate,
    [switch]$P2DispatcherCandidate,
    [string]$RetainedP2JvmSnapshot='',
    [string]$RetainedExpansionJvmSnapshot='',
    [switch]$P2Expansion,
    [hashtable]$RetainedCarrierOrigins=@{}
)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'maintenance/Assert-HumanBatchReady.ps1') -Operation build
if($args.Count){throw 'Unknown integration build arguments; do not silently ignore fixture or safety options'}
$taskP1BatchRelative=$null
$taskP2BatchRelative=$null
$taskP2Candidate=$P2SteerCandidate -or $P2WgCandidate -or $P2DispatcherCandidate
$taskActiveForBuild=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/current.json') -Raw|ConvertFrom-Json
if(($taskActiveForBuild.activeBatch -ceq 'p2-expansion-2026-10-21.json') -ne [bool]$P2Expansion){throw 'Expanded P2 requires its own explicit common recipe; do not reuse a legacy build'}
if($P2Expansion){
    $required=@(':app:testDebugUnitTest',':app:assembleDebug',':app:assembleDebugAndroidTest',':engines:assembleCsqttDebug',':engines:assembleCsqtt25Debug',':engines:assembleQwdttDebug',':engines:assembleWdttDebug')
    if($CsqttHistoricalStages.Count){
        if((($CsqttHistoricalStages.Keys|Sort-Object) -join ',') -cne 'csqtt20,csqtt215'){throw 'Both historical CSQTT families belong to the same common batch'}
        $required+=@(':engines:assembleCsqtt20Debug',':engines:assembleCsqtt215Debug')
    }
    if($taskP2Candidate -or $P1OwnerFixture -or $P1WgCandidate -or $P1SteerCandidate -or $RetainedP2JvmSnapshot -or
       $TestRelease -or $ReleaseSmoke -or $BothTestRunners -or $PreparePrimaryStage -or $ExportEngineApks -or $ExportWgBackend -or $FirstLaunchFixture -or
       $PrimaryCoreStage -or $WgBackendStage -or $DispatcherNativeStage -or !$CsqttLegacyStage -or !$Csqtt25Stage -or
       (($SwgNativeStages.Keys|Sort-Object) -join ',') -cne 'qwdtt,wdtt' -or
       @($required|Where-Object {$_ -cnotin $Task}).Count -or @($Task|Where-Object {$_ -cnotin $required}).Count){
        throw 'Expanded P2 requires one complete app/runner + explicitly staged BS modules batch'
    }
    $taskExpansionRelative='ports/android/coordination/p2-expansion-2026-10-21.json'
    $taskExpansionSha=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'coordination/p2-expansion-2026-10-21.json')).Hash.ToLowerInvariant()
}
if($CsqttHistoricalStages.Count -and !$P2Expansion){throw 'Historical CSQTT candidates require the explicit whole expanded-P2 common batch'}
if($taskActiveForBuild.activeBatch -ceq 'p2-progress-2026-10-20.json' -and !$taskP2Candidate){throw 'Whole-P2 common build requires its explicit candidate/affected-test recipe, not an unfiltered legacy build'}
if($taskP2Candidate){
    if(!$P2SteerCandidate -or !$P2WgCandidate -or !$P2DispatcherCandidate -or !$DispatcherNativeStage -or !$CsqttLegacyStage -or !$Csqtt25Stage){throw 'The whole-P2 common package includes all changed Steer/WG/dispatcher/CSQTT candidates; no partial APK micro-batch'}
    $taskP2Required=@(':app:testDebugUnitTest',':app:assembleDebug',':app:assembleDebugAndroidTest')
    $taskP2Required+=':p2-route-dispatcher:assembleDebug'
    $taskP2Required+=@(':engines:assembleCsqttDebug',':engines:assembleCsqtt25Debug')
    if($P2SteerCandidate){$taskP2Required+=':engines:assembleSteerDebug'}
    if($P2WgCandidate){$taskP2Required+=':wg-module:assembleDebug'}
    if($P1OwnerFixture -or $P1WgCandidate -or $P1SteerCandidate -or $TestRelease -or $ReleaseSmoke -or $BothTestRunners -or $PreparePrimaryStage -or $ExportEngineApks -or $ExportWgBackend -or
        @($Task|Where-Object {$_ -match 'Release'}).Count -or
        ($P2SteerCandidate -and !$PrimaryCoreStage) -or ($P2WgCandidate -and !$WgBackendStage) -or
        @($taskP2Required|Where-Object {$_ -notin $Task}).Count){throw 'P2 native candidates belong to one common debug app/test/module batch, never P1 or standalone release builds'}
    $taskP2Current=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/current.json') -Raw|ConvertFrom-Json
    if($taskP2Current.activeBatch -cne 'p2-progress-2026-10-20.json'){throw 'Exact active whole-P2 source batch required'}
    $taskP2BatchRelative='ports/android/coordination/p2-progress-2026-10-20.json'
    $taskP2Ready=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/p2-progress-2026-10-20.json') -Raw|ConvertFrom-Json
    if($taskP2Ready.priority -ne 2 -or $taskP2Ready.verificationAuthority -cne 'human-20261020-p2-whole-source-before-common-verification'){throw 'Exact common-P2 source authority required'}
    foreach($taskFlag in @('sourceReady','readyForBuild','allowCommonVerification','nativeSourceReady','allowNativeCandidateBuild','allowApkBuild')){
        if($taskP2Ready.$taskFlag -isnot [bool] -or !$taskP2Ready.$taskFlag){throw 'Finish ALL P2 source before the common build'}
    }
    $taskP2ReadySha=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'coordination/p2-progress-2026-10-20.json')).Hash.ToLowerInvariant()
}
if($P1WgCandidate -and (!$P1OwnerFixture -or !$WgBackendStage -or ':wg-module:assembleDebug' -notin $Task)){throw 'P1 WG candidate is part of the SAME common debug app/runner/helper build with an explicit WG native stage'}
if($P1SteerCandidate -and (!$P1OwnerFixture -or !$PrimaryCoreStage -or ':engines:assembleSteerDebug' -notin $Task)){throw 'P1 Steer candidate requires the SAME common debug app/runner/helper batch and explicit external primary stage'}
if($P1OwnerFixture){
    if($TestRelease -or $ReleaseSmoke -or $BothTestRunners -or $FirstLaunchFixture -or
       @(':app:assembleDebug',':app:assembleDebugAndroidTest',':p1-owner-probe:assembleDebug',':p1-owner-peer:assembleDebug' | Where-Object {$_ -notin $Task}).Count){throw 'P1 owner helpers require COMMON debug app + runner + shared helper pair, never a standalone/release build'}
    $taskP1Current=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/current.json') -Raw|ConvertFrom-Json
    if($taskP1Current.activeBatch -cnotmatch '^p1-progress-[0-9]{4}-[0-9]{2}-[0-9]{2}\.json$'){throw 'P1 must be the active whole source-ready batch'}
    $taskP1BatchRelative="ports/android/coordination/$($taskP1Current.activeBatch)"
    $taskP1Ready=Get-Content -LiteralPath (Join-Path $PSScriptRoot "coordination/$($taskP1Current.activeBatch)") -Raw|ConvertFrom-Json
    if($taskP1Ready.priority -ne 1 -or $taskP1Ready.readyForBuild -isnot [bool] -or !$taskP1Ready.readyForBuild){throw 'Finish ALL P1 source work before any helper compilation'}
    if($P1WgCandidate -or $P1SteerCandidate){foreach($taskFlag in @('sourceReady','allowCommonVerification','nativeSourceReady','allowNativeCandidateBuild','allowApkBuild')){if($taskP1Ready.$taskFlag -isnot [bool] -or !$taskP1Ready.$taskFlag){throw 'Common P1 native/packaging gate closed'}}}
}
if($ExportWgBackend -and (!$WgBackendStage -or ':wg-module:assembleRelease' -notin $Task -or !$TestRelease)){throw 'WG export requires explicit candidate and signed release build task'}
if($FirstLaunchFixture -and ($TestRelease -or $ReleaseSmoke -or $BothTestRunners -or @($Task|Where-Object {$_ -notin @(':app:testDebugUnitTest',':app:assembleDebug',':app:assembleDebugAndroidTest')}).Count -gt 0)){throw 'First-launch fixture permits debug UI build tasks only'}
if($BothTestRunners -and ($ReleaseSmoke -or ':app:assembleDebugAndroidTest' -notin $Task)){throw 'Both runners require default debug runner and assembleDebugAndroidTest'}
if($ExportTestRunner -and (':app:assembleDebugAndroidTest' -notin $Task -or $BothTestRunners)){throw 'Runner export requires one explicitly selected instrumentation runner'}
if($PreparePrimaryStage -and ':primary-adapter:verifyReleaseAdapter' -notin $Task){throw 'Primary stage export requires verified adapter task'}
$taskWorkspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskRetention=Join-Path $PSScriptRoot 'coordination/build-retention.json'
if(Test-Path -LiteralPath $taskRetention){
    $taskPolicy=Get-Content -LiteralPath $taskRetention -Raw|ConvertFrom-Json
    foreach($taskActive in @($taskPolicy.activeSnapshots)){
        if(!(Test-Path -LiteralPath (Join-Path $taskWorkspace "work/$taskActive/build-result.json"))){throw 'A registered build has not completed; do not start a duplicate build'}
    }
    $taskPolicy.activeSnapshots=@()
    [IO.File]::WriteAllText($taskRetention,($taskPolicy|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
    if(!$taskP2Candidate -and !$P2Expansion){& (Join-Path $PSScriptRoot 'cleanup-builds.ps1') -Apply}
}
$taskOptionalRelative=$null
$taskOptionalFixtureRelative=$null
$taskPrimaryRelative=$null
$taskP2SteerOrigin=$null
$taskWarpRelative=$null
$taskWgRelative=$null
if($WgBackendStage){
    $taskWgRoot=[IO.Path]::GetFullPath($(if([IO.Path]::IsPathRooted($WgBackendStage)){$WgBackendStage}else{Join-Path $taskWorkspace $WgBackendStage}))
    $taskWgAllowed=Join-Path $taskWorkspace 'ports/android/transports/wg-backend/build'
    if(!$taskWgRoot.StartsWith($taskWgAllowed+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'WG candidate outside dedicated Android build directory'}
    if($P2WgCandidate){
        if((Get-Item -LiteralPath $taskWgRoot).LinkType){throw 'Ordinary P2 WG stage required'}
        $taskWgReceipt=Get-Content -LiteralPath (Join-Path $taskWgRoot 'receipt.json') -Raw|ConvertFrom-Json
        if($taskWgReceipt.state -cne 'built_awaiting_native_and_device_gates' -or $taskWgReceipt.priority -ne 2 -or
            $taskWgReceipt.sourceReadySha256 -cne $taskP2ReadySha -or $taskWgReceipt.packetPipeContract -cne 'owned_unix_dgram_v1' -or
            $taskWgReceipt.executedPipeCases -ne 6 -or ((@($taskWgReceipt.encryptedPipeProtocols)|Sort-Object)-join ',') -cne 'AWG3,AWG3_1,WG' -or
            $taskWgReceipt.aarSha256 -cne (Get-FileHash -LiteralPath (Join-Path $taskWgRoot 'wg-backend.aar')).Hash.ToLowerInvariant()){
            throw 'Exact completed source-bound P2 WG native stage required; do not relabel a P1 AAR'
        }
        if($taskWgReceipt.kernelEvidence){
            $kernel=& (Join-Path $PSScriptRoot 'maintenance/Read-P2KernelEvidence.ps1') -Path $taskWgReceipt.kernelEvidence -Component wg
            if($kernel.sha256 -cne $taskWgReceipt.kernelEvidenceSha256){throw 'WG Android kernel evidence changed since native compilation'}
        }
    }
    $taskWgRelative=[IO.Path]::GetRelativePath($taskWorkspace,$taskWgRoot) -replace '\\','/'
}
if($WarpScannerStage){
    $warpRoot=[IO.Path]::GetFullPath((Join-Path $taskWorkspace $WarpScannerStage))
    $warpAllowed=Join-Path $taskWorkspace 'ports/android/transports/warp/build'
    if(!$warpRoot.StartsWith($warpAllowed+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Warp candidate must stay in its Android build directory'}
    $taskWarpRelative=[IO.Path]::GetRelativePath($taskWorkspace,$warpRoot) -replace '\\','/'
}
$taskOpenFluxRelative=$null
$taskOpenFluxAckRelative=$null
$taskOpenFluxProviderRelative=$null
$taskOpenFluxProviderV2Relative=$null
$taskOpenFluxProviderV3Relative=$null
$taskExternalInputs=@{}
function Add-P2ProcessInputs([string]$Stage,$Receipt,[string]$Relative){
    if($Receipt.processGroupContract -ne 1){if($taskP2Candidate){throw 'P2 CSQTT requires actual before-child owned group contract'};return}
    $expected=@('mcrf_process_group.go','mcrf_process_group_linux.go','mcrf_process_group_other.go','mcrf_child_input.go','csqtt_bridge.go')
    if((@($Receipt.processGroupInputs.file|Sort-Object)-join ',') -cne (($expected|Sort-Object)-join ',')){throw 'Exact process-group source proof required'}
    foreach($row in $Receipt.processGroupInputs){
        $path=Join-Path $Stage ('process-group-inputs/'+$row.file);$file=Get-Item -LiteralPath $path
        if($file.LinkType -or $file.PSIsContainer -or (Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -cne $row.sha256){throw 'Compiled CSQTT group source changed'}
        $taskExternalInputs["$Relative/process-group-inputs/$($row.file)"]=$file.FullName
        if($row.file -cne 'csqtt_bridge.go' -and $taskP2Candidate -and
           (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot ('transports/swg/'+$row.file))).Hash.ToLowerInvariant() -cne $row.sha256){throw 'Compiled group helper is not current P2 source'}
    }
    if(!$taskP2Candidate){return}
    $prepared=Get-Content -LiteralPath (Join-Path $Stage 'prepared-p2-source.json') -Raw|ConvertFrom-Json
    if($prepared.sourceReadySha256 -cne $taskP2ReadySha -or !$prepared.canonicalInputs -or
       $prepared.preparerSha256 -cne (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'transports/swg/build.ps1')).Hash.ToLowerInvariant()){
        throw 'Exact prepared P2 source origin required'
    }
    foreach($property in $prepared.canonicalInputs.PSObject.Properties){
        if([IO.Path]::IsPathRooted($property.Name) -or '..' -in ($property.Name -split '[/\\]')){throw 'Unsafe canonical group source path'}
        if((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot ('transports/swg/'+$property.Name))).Hash.ToLowerInvariant() -cne $property.Value){throw 'Canonical CSQTT source changed since native compilation'}
    }
    $proofPath=Join-Path $Stage 'process-group-evidence.json';$proof=Get-Content -LiteralPath $proofPath -Raw|ConvertFrom-Json
    if($Receipt.priority -ne 2 -or $Receipt.sourceReadySha256 -cne $taskP2ReadySha -or
       $Receipt.producerSha256 -cne (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'transports/swg/build-captcha-native.ps1')).Hash.ToLowerInvariant() -or
       $Receipt.producerSha256 -cne (Get-FileHash -LiteralPath (Join-Path $Stage 'native-producer-control.ps1')).Hash.ToLowerInvariant() -or
       $Receipt.processGroupEvidenceSha256 -cne (Get-FileHash -LiteralPath $proofPath).Hash.ToLowerInvariant() -or
       $proof.priority -ne 2 -or !$proof.passed -or !$proof.sourceStable -or $proof.sourceReadySha256 -cne $taskP2ReadySha -or
       $proof.state -cnotin @('OWNED_LINUX_GROUP_AND_INPUT_PASS_NOT_ANDROID','OWNED_ANDROID_KERNEL_PASS_APP_UNVERIFIED') -or @($proof.cases).Count -ne 8 -or
       $proof.logSha256 -cne (Get-FileHash -LiteralPath (Join-Path $Stage 'process-group-tests.log')).Hash.ToLowerInvariant()){
        throw 'Actual NEW source-bound P2 Linux group/STOP proof required, not old waiter acceptance'
    }
    if($proof.state -ceq 'OWNED_ANDROID_KERNEL_PASS_APP_UNVERIFIED'){
        $kernel=& (Join-Path $PSScriptRoot 'maintenance/Read-P2KernelEvidence.ps1') -Path $proof.kernelEvidence -Component group
        if($kernel.sha256 -cne $proof.kernelEvidenceSha256){throw 'Android kernel evidence changed since CSQTT compilation'}
    }
    foreach($name in @('process-group-evidence.json','process-group-tests.log','prepared-p2-source.json','native-producer-control.ps1')){
        $path=Join-Path $Stage $name;$file=Get-Item -LiteralPath $path
        if($file.LinkType -or $file.PSIsContainer){throw 'Ordinary P2 CSQTT evidence file required'}
        $taskExternalInputs["$Relative/$name"]=$file.FullName
    }
    if($Receipt.kind -ceq 'csqtt25'){
        $path=Join-Path $Stage 'affected-bridge-tests.jsonl'
        if(@($Receipt.affectedBridgeCases).Count -ne 2 -or $Receipt.affectedBridgeLogSha256 -cne (Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()){throw 'Affected actual bridge retirement cases required'}
        $taskExternalInputs["$Relative/affected-bridge-tests.jsonl"]=$path
    }
}
$taskDispatcherRelative=$null
if($DispatcherNativeStage){
    if(!$P2DispatcherCandidate){throw 'Dispatcher native stage is private whole-P2 only'}
    $dispatcherRoot=[IO.Path]::GetFullPath($DispatcherNativeStage)
    if($dispatcherRoot -cnotmatch '^D:\\MCRF\\Builds\\Android\\p2-singbox-route-source-[0-9a-f]{32}$' -or (Get-Item -LiteralPath $dispatcherRoot).LinkType){throw 'Exact ordinary completed native dispatcher stage required'}
    $dispatcherBuilt=Get-Content -LiteralPath (Join-Path $dispatcherRoot 'build-receipt.json') -Raw|ConvertFrom-Json
    $dispatcherSource=Get-Content -LiteralPath (Join-Path $dispatcherRoot 'receipt.json') -Raw|ConvertFrom-Json
    if($dispatcherBuilt.tests.group -ceq 'P2_PIPE_ANDROID_KERNEL'){
        $kernel=& (Join-Path $PSScriptRoot 'maintenance/Read-P2KernelEvidence.ps1') -Path $dispatcherBuilt.kernelEvidence -Component dispatcher
        if($kernel.sha256 -cne $dispatcherBuilt.kernelEvidenceSha256 -or $kernel.package.dispatcherSourceReceiptSha256 -cne $dispatcherBuilt.sourceReceiptSha256){throw 'Dispatcher kernel origin changed'}
    }
    if($dispatcherBuilt.priority -ne 2 -or $dispatcherBuilt.state -cne 'COMPILED_API_ABI_PASS_RUNTIME_OPEN' -or !$dispatcherBuilt.sourceStable -or !$dispatcherBuilt.producerComplete -or
        $dispatcherBuilt.sourceReadySha256 -cne $taskP2ReadySha -or $dispatcherSource.readinessSha256 -cne $taskP2ReadySha -or $dispatcherBuilt.tests.passed -ne 14 -or
        $dispatcherBuilt.aarSha256 -cne (Get-FileHash -LiteralPath (Join-Path $dispatcherRoot 'runtime.aar')).Hash.ToLowerInvariant() -or
        $dispatcherBuilt.apiSha256 -cne (Get-FileHash -LiteralPath (Join-Path $dispatcherRoot 'api-signatures.txt')).Hash.ToLowerInvariant() -or
        $dispatcherBuilt.retainedApiPreserved -ne $true -or $dispatcherBuilt.apiCompatibilitySha256 -cne (Get-FileHash -LiteralPath (Join-Path $dispatcherRoot 'api-compatibility.json')).Hash.ToLowerInvariant() -or
        $dispatcherBuilt.tests.logSha256 -cne (Get-FileHash -LiteralPath (Join-Path $dispatcherRoot 'pipe-tests.log')).Hash.ToLowerInvariant() -or
        $dispatcherBuilt.sourceReceiptSha256 -cne (Get-FileHash -LiteralPath (Join-Path $dispatcherRoot 'receipt.json')).Hash.ToLowerInvariant() -or
        $dispatcherSource.sourceManifestSha256 -cne (Get-FileHash -LiteralPath (Join-Path $dispatcherRoot 'source-manifest.json')).Hash.ToLowerInvariant()){
        throw 'Exact whole-P2 dispatcher native/API/ABI/affected Linux evidence required'
    }
    foreach($patch in $dispatcherSource.patches){
        $area=if($patch.area -ceq 'direct-family'){'p044-direct-family'}elseif($patch.area -ceq 'route-pipes'){'p2-route-pipes'}else{throw 'Unknown native patch area'}
        $canonical=Join-Path $taskWorkspace "ports/android/primary-cores/singbox/native-patches/$area/$($patch.file)"
        if((Get-FileHash -LiteralPath $canonical).Hash.ToLowerInvariant() -cne $patch.sha256){throw 'Dispatcher native source changed after exact native capture'}
    }
    if($dispatcherBuilt.producerSha256 -cne (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'maintenance/Build-P2RouteNative.ps1')).Hash.ToLowerInvariant() -or
        $dispatcherSource.prepareSha256 -cne (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'maintenance/Prepare-P2RouteNative.ps1')).Hash.ToLowerInvariant()){
        throw 'Dispatcher native producer recipe changed; review evidence rather than silently reusing it'
    }
    $taskDispatcherRelative='work/p2-dispatcher-native-'+[IO.Path]::GetFileName($dispatcherRoot)
    foreach($name in @('runtime.aar','build-receipt.json','receipt.json','source-manifest.json','api-signatures.txt','api-compatibility.json','pipe-tests.log')){
        $input=Get-Item -LiteralPath (Join-Path $dispatcherRoot $name)
        if($input.LinkType -or $input.PSIsContainer){throw 'Ordinary dispatcher native input required'}
        $taskExternalInputs["$taskDispatcherRelative/$name"]=$input.FullName
    }
}
$taskP1ExternalPrimary=$false
$taskP1OriginalReadyRelative=$null
if(@($OpenFluxPromotionStage,$OpenFluxAckStage,$OpenFluxProviderStage,$OpenFluxProviderV2Stage,$OpenFluxProviderV3Stage|Where-Object {$_}).Count -gt 1){throw 'OpenFlux stages cannot be combined'}
foreach($stageName in @('PrimaryCoreStage','OpenFluxPromotionStage','OpenFluxAckStage','OpenFluxProviderStage','OpenFluxProviderV2Stage','OpenFluxProviderV3Stage')) {
    $stageValue=Get-Variable -Name $stageName -ValueOnly
    if(!$stageValue){continue}
    $stageRoot=[IO.Path]::GetFullPath($(if([IO.Path]::IsPathRooted($stageValue)){$stageValue}else{Join-Path $taskWorkspace $stageValue}))
    if($stageName -ceq 'PrimaryCoreStage' -and $P2SteerCandidate){
        $parent=Get-Item -LiteralPath ([IO.Path]::GetDirectoryName($stageRoot))
        if($parent.Parent.FullName -ine [IO.Path]::GetFullPath('D:/MCRF/Builds/Android') -or $parent.Name -cnotmatch '^p2-steer-candidate-[0-9a-f]{32}$' -or
            $parent.LinkType -or [IO.Path]::GetFileName($stageRoot) -cne 'primary-stage' -or (Get-Item -LiteralPath $stageRoot).LinkType){throw 'Exact ordinary common-P2 external primary stage required'}
        $producer=Get-Content -LiteralPath (Join-Path $parent.FullName 'producer-receipt.json') -Raw|ConvertFrom-Json
        if($producer.priority -ne 2 -or $producer.state -cne 'compiled_candidate_not_runtime_accepted' -or $producer.sourceStable -isnot [bool] -or !$producer.sourceStable -or
            $producer.readinessSha256 -cne $taskP2ReadySha -or
            $producer.primaryStageReceiptSha256 -cne (Get-FileHash -LiteralPath (Join-Path $stageRoot 'receipt.json')).Hash.ToLowerInvariant()){throw 'Completed exact source-bound P2 producer required'}
        if(!$producer.inputs -or !@($producer.inputs.PSObject.Properties).Count){throw 'P2 producer has no source inventory'}
        foreach($input in $producer.inputs.PSObject.Properties){
            $relative=$input.Name.Replace('\','/')
            if($relative -ceq 'ports/android/coordination/current.json'){continue} # Runtime job/grant metadata, never a native input.
            if([IO.Path]::IsPathRooted($relative) -or '..' -cin $relative.Split('/')){throw 'P2 native source inventory escaped workspace'}
            $source=Get-Item -LiteralPath (Join-Path $taskWorkspace $relative)
            if($source.LinkType -or $source.PSIsContainer){throw 'Ordinary P2 native source required'}
            $sourceHash=(Get-FileHash -LiteralPath $source.FullName).Hash.ToLowerInvariant()
            if($sourceHash -cne $input.Value.sha256){
                # Retain the already accepted fixture-only continuation, not an
                # arbitrary native source change. Its reference relink reproduced
                # the original executable and all production objects unchanged.
                $wire=Get-Content 'D:/MCRF/Builds/Android/p2-native-wire-8bc2e5769ef4479f954e9dc64aec0f30/receipt.json' -Raw|ConvertFrom-Json
                $fixturePath='D:/MCRF/Builds/Android/p2-probe-fixture-1b99c87cfe5545859bb8ff835490b694/receipt.json'
                $fixture=Get-Content $fixturePath -Raw|ConvertFrom-Json
                if(!$wire.passed -or !$wire.sourceStable -or @($wire.cases).Count -ne 18 -or $wire.cleanupPending -or
                    $wire.producerReceiptSha256 -cne (Get-FileHash (Join-Path $parent.FullName 'producer-receipt.json')).Hash.ToLowerInvariant() -or
                    $wire.fixtureRevisionSha256 -cne (Get-FileHash $fixturePath).Hash.ToLowerInvariant() -or
                    !$fixture.productionUnchanged -or $fixture.referenceSha256 -cne $fixture.originalNativeSha256 -or
                    !(($relative -ceq 'work/steer-android-port/android-vpn/tests/host_probe_tls_test.c' -and $sourceHash -ceq $fixture.sourceSha256) -or
                      ($relative -ceq 'work/steer-android-port/android-vpn/tests/p2_probe_native_android.py' -and $sourceHash -ceq $wire.runnerSha256))){throw "Unreviewed P2 native source change: $relative"}
            }
            $ancestor=$source.Directory
            while($ancestor.FullName -ine $taskWorkspace){
                if($ancestor.LinkType -or !$ancestor.FullName.StartsWith($taskWorkspace+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Redirected P2 native source refused'}
                $ancestor=$ancestor.Parent
            }
        }
        $taskPrimaryRelative='work/p2-primary-'+$parent.Name
        $taskP2SteerOrigin=[ordered]@{producerReceiptSha256=(Get-FileHash -LiteralPath (Join-Path $parent.FullName 'producer-receipt.json')).Hash.ToLowerInvariant();primaryStageReceiptSha256=$producer.primaryStageReceiptSha256}
        foreach($input in Get-ChildItem -LiteralPath $stageRoot -Recurse -Force){
            if($input.LinkType){throw 'Linked P2 primary stage refused'}
            if(!$input.PSIsContainer){$relative=[IO.Path]::GetRelativePath($stageRoot,$input.FullName) -replace '\\','/';$taskExternalInputs["$taskPrimaryRelative/$relative"]=$input.FullName}
        }
        $taskP1ExternalPrimary=$true # Shared inventory rule: external inputs are already captured above.
        continue
    }
    if($stageName -ceq 'PrimaryCoreStage' -and $P1SteerCandidate){
        $parent=Get-Item -LiteralPath ([IO.Path]::GetDirectoryName($stageRoot))
        if($parent.Parent.FullName -ine [IO.Path]::GetFullPath('D:/MCRF/Builds/Android') -or $parent.Name -cnotmatch '^p1-steer-candidate-[0-9a-f]{32}$' -or
            $parent.LinkType -or [IO.Path]::GetFileName($stageRoot) -cne 'primary-stage' -or (Get-Item -LiteralPath $stageRoot).LinkType){throw 'Exact ordinary common-P1 external primary stage required'}
        $producer=Get-Content -LiteralPath (Join-Path $parent.FullName 'producer-receipt.json') -Raw|ConvertFrom-Json
        $readinessMatches=$producer.readinessSha256 -ceq (Get-FileHash -LiteralPath (Join-Path $taskWorkspace $taskP1BatchRelative)).Hash.ToLowerInvariant()
        if(!$readinessMatches){
            # A later device grant is metadata, not a native source change.
            # Retain the exact old native origin only if ALL binary inputs and
            # source-ready gates are unchanged; never rewrite its old receipt.
            $frozenReady=Join-Path $producer.frozenSourceDirectory $taskP1BatchRelative
            if((Get-FileHash -LiteralPath $frozenReady).Hash.ToLowerInvariant() -cne $producer.readinessSha256){throw 'Original native readiness proof changed'}
            $oldReady=Get-Content -LiteralPath $frozenReady -Raw|ConvertFrom-Json
            $metadata=@('allowCommonDeviceVerification','commonVerificationReceipt','deviceVerificationAuthority','humanDeviceReply','phase')
            foreach($name in @($oldReady.PSObject.Properties.Name+$taskP1Ready.PSObject.Properties.Name|Sort-Object -Unique)){
                if($name -cnotin $metadata -and ($oldReady.$name|ConvertTo-Json -Depth 30 -Compress) -cne ($taskP1Ready.$name|ConvertTo-Json -Depth 30 -Compress)){throw 'Native source readiness semantics changed'}
            }
            foreach($input in $producer.inputs.PSObject.Properties){
                if($input.Name.Replace('\','/') -cin @($taskP1BatchRelative,'ports/android/coordination/current.json')){continue}
                # Independent traffic peer is NOT a C/Java/JNI artifact input.
                # It is frozen/verified separately in the current common batch.
                if($input.Name.Replace('\','/') -cin @('work/steer-android-port/android-vpn/tests/p1_owned_peer.py','work/steer-android-port/android-vpn/tests/test_p1_owned_peer.py')){continue}
                if((Get-FileHash -LiteralPath (Join-Path $taskWorkspace $input.Name)).Hash.ToLowerInvariant() -cne $input.Value.sha256){throw 'Retained primary native source changed'}
            }
            $readinessMatches=$true
        }
        if($producer.state -cne 'compiled_candidate_not_runtime_accepted' -or !$producer.sourceStable -or
            !$readinessMatches -or
            $producer.primaryStageReceiptSha256 -cne (Get-FileHash -LiteralPath (Join-Path $stageRoot 'receipt.json')).Hash.ToLowerInvariant()){throw 'Completed source-bound P1 primary producer required'}
        $taskPrimaryRelative='work/p1-primary-'+$parent.Name
        if($frozenReady){
            $taskP1OriginalReadyRelative="$taskPrimaryRelative/original-p1-readiness.json"
            $taskExternalInputs[$taskP1OriginalReadyRelative]=$frozenReady
        }
        foreach($input in Get-ChildItem -LiteralPath $stageRoot -Recurse -Force){
            if($input.LinkType){throw 'Linked external primary input refused'}
            if(!$input.PSIsContainer){$relative=[IO.Path]::GetRelativePath($stageRoot,$input.FullName) -replace '\\','/';$taskExternalInputs["$taskPrimaryRelative/$relative"]=$input.FullName}
        }
        $taskP1ExternalPrimary=$true
        continue
    }
    if(!$stageRoot.StartsWith($taskWorkspace+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {throw 'Native stage must stay inside the canonical workspace'}
    if(!(Test-Path -LiteralPath $stageRoot -PathType Container)){throw "Missing native stage: $stageName"}
    $relative=[IO.Path]::GetRelativePath($taskWorkspace,$stageRoot) -replace '\\','/'
    if($stageName -eq 'PrimaryCoreStage'){$taskPrimaryRelative=$relative}elseif($stageName -eq 'OpenFluxAckStage'){$taskOpenFluxAckRelative=$relative}elseif($stageName -eq 'OpenFluxProviderStage'){$taskOpenFluxProviderRelative=$relative}elseif($stageName -eq 'OpenFluxProviderV2Stage'){$taskOpenFluxProviderV2Relative=$relative}elseif($stageName -eq 'OpenFluxProviderV3Stage'){$taskOpenFluxProviderV3Relative=$relative}else{$taskOpenFluxRelative=$relative}
}
if($OptionalCoreStage){
    $taskOptionalRoot=[IO.Path]::GetFullPath((Join-Path $taskWorkspace $OptionalCoreStage))
    $taskOptionalAllowed=Join-Path $taskWorkspace 'ports/android/optional-cores/build'
    if(!$taskOptionalRoot.StartsWith($taskOptionalAllowed+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Optional native stage must be an isolated build under Android optional-cores/build'}
    $taskOptionalRelative=[IO.Path]::GetRelativePath($taskWorkspace,$taskOptionalRoot) -replace '\\','/'
}
if($OptionalFixtureStage){
    $taskFixtureRoot=[IO.Path]::GetFullPath((Join-Path $taskWorkspace $OptionalFixtureStage))
    $taskFixtureAllowed=Join-Path $taskWorkspace 'ports/android/optional-cores/build'
    if(!$taskFixtureRoot.StartsWith($taskFixtureAllowed+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Debug fixture stage must be isolated under Android optional-cores/build'}
    $taskOptionalFixtureRelative=[IO.Path]::GetRelativePath($taskWorkspace,$taskFixtureRoot) -replace '\\','/'
}
if($DnsttFixtureStage){
    $taskDnsttRoot=[IO.Path]::GetFullPath((Join-Path $taskWorkspace $DnsttFixtureStage))
    $taskDnsttAllowed=Join-Path $taskWorkspace 'ports/android/dnstt-fixtures/build'
    if(!$taskDnsttRoot.StartsWith($taskDnsttAllowed+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'DNSTT fixture stage must remain under Android dnstt-fixtures/build'}
    $taskDnsttFixtureRelative=[IO.Path]::GetRelativePath($taskWorkspace,$taskDnsttRoot) -replace '\\','/'
}
$taskCsqtt25Relative=$null
function Add-CarrierFeatureInputs([string]$Stage,$Receipt,[string]$Relative){
    $files=@()
    if($Receipt.fingerprintContract -eq 1){$files+=@($Receipt.fingerprintInputs|ForEach-Object {'fingerprint-inputs/'+$_.file})}
    if($Receipt.turnHashPoolContract -eq 1){$files+=@($Receipt.turnFeatureInputs|ForEach-Object {'turn-feature-inputs/'+$_.file})}
    if($Receipt.accountOptionsContract -eq 1){$files+='account-options-inputs/prepare_csqtt.py';$files+=@($Receipt.accountBridgeInputs|ForEach-Object {'account-options-inputs/'+$_.file})}
    if($Receipt.providerOptionsContract -eq 1){$files+='provider-options-input.go'}
    if($Receipt.masqueContract -eq 1){$files+='masque-main-input.go'}
    if($Receipt.retainedOwnerClosureSha256){$files+=@('retained-owned-closure.json','retained-owned-bridge.go','retained-owned-evidence.json')}
    if($Receipt.kind -ceq 'csqtt25'){$files+='licenses/swg-panel-reference.json'}
    if($P2Expansion){
        $proofPath=if($RetainedCarrierOrigins.ContainsKey($Receipt.kind)){$RetainedCarrierOrigins[$Receipt.kind]}else{Join-Path $Stage 'prepared-expansion-source.json'}
        $proof=Get-Content -LiteralPath $proofPath -Raw|ConvertFrom-Json
        if($Receipt.expansionAuthority -cne 'human-20261021-expanded-p2-whole-source-before-common-verification' -or
           (!$RetainedCarrierOrigins.ContainsKey($Receipt.kind) -and $Receipt.expansionReadinessSha256 -cne $taskExpansionSha) -or $proof.sourceReadySha256 -cne $taskExpansionSha -or
           $proof.kind -cne $Receipt.kind -or !$proof.canonicalInputs -or
           $proof.preparerSha256 -cne (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'transports/swg/build.ps1')).Hash.ToLowerInvariant()){
            throw 'Every changed carrier must derive from this NEW expanded-P2 source snapshot'
        }
        foreach($property in $proof.canonicalInputs.PSObject.Properties){
            if([IO.Path]::IsPathRooted($property.Name) -or '..' -cin ($property.Name -split '[/\\]')){throw 'Unsafe expansion source inventory'}
            $input=Get-Item -LiteralPath (Join-Path $PSScriptRoot ('transports/swg/'+$property.Name))
            if($input.LinkType -or $input.PSIsContainer -or (Get-FileHash -LiteralPath $input.FullName).Hash.ToLowerInvariant() -cne $property.Value){throw 'Expanded carrier canonical source changed'}
        }
        if($RetainedCarrierOrigins.ContainsKey($Receipt.kind)){
            $compiled=Get-Content -LiteralPath (Join-Path $Stage 'source-manifest.json') -Raw|ConvertFrom-Json
            $oldRows=(@($compiled|ForEach-Object {$_.path.Replace('\','/')+'='+$_.sha256})|Sort-Object) -join "`n"
            $newRows=(@($proof.inputs|ForEach-Object {$_.path.Replace('\','/')+'='+$_.sha256})|Sort-Object) -join "`n"
            if($oldRows -cne $newRows){throw 'Retained native carrier differs from newly prepared full Go module inputs'}
            $taskExternalInputs["$Relative/prepared-expansion-source.json"]=[IO.Path]::GetFullPath($proofPath)
        }else{$files+='prepared-expansion-source.json'}
        if($Receipt.kind -cin @('csqtt','csqtt25')){
            $retained=& (Join-Path $PSScriptRoot 'maintenance/Read-ExpandedP2GroupRetention.ps1') -Module (Join-Path $Stage 'process-group-inputs') -Kind $Receipt.kind
            $closurePath=Join-Path $Stage 'retained-owned-closure.json'
            $closure=Get-Content -LiteralPath $closurePath -Raw|ConvertFrom-Json
            if($closure.newExecution -ne $false -or
                (Get-FileHash -LiteralPath $closurePath).Hash.ToLowerInvariant() -cne $Receipt.retainedOwnerClosureSha256 -or
                $closure.retainedReceiptSha256 -cne $retained.retainedReceiptSha256 -or
                $closure.oldBridgeSha256 -cne $retained.oldBridgeSha256){throw 'Expanded carrier must retain only the exact unchanged accepted owner closure'}
        }
        $affectedPath=Join-Path $Stage 'expansion-affected-tests.jsonl'
        if((Get-FileHash -LiteralPath $affectedPath).Hash.ToLowerInvariant() -cne $Receipt.affectedLogSha256 -or !@($Receipt.affectedCases).Count){throw 'Expanded carrier affected native evidence missing'}
        $files+='expansion-affected-tests.jsonl'
        if($Receipt.retainedAffectedLogSha256){
            $retainedLog=Join-Path $Stage 'retained-expansion-affected-tests.jsonl'
            if((Get-FileHash -LiteralPath $retainedLog).Hash.ToLowerInvariant() -cne $Receipt.retainedAffectedLogSha256){throw 'Retained affected native log changed'}
            $files+='retained-expansion-affected-tests.jsonl'
        }
        if($Receipt.retainedPriorLogSha256){
            if((Get-FileHash -LiteralPath (Join-Path $Stage 'retained-expansion-prior-tests.jsonl')).Hash.ToLowerInvariant() -cne $Receipt.retainedPriorLogSha256){throw 'Retained prior affected log changed'}
            $files+='retained-expansion-prior-tests.jsonl'
        }
    }
    $base=[IO.Path]::GetFullPath($Stage)+[IO.Path]::DirectorySeparatorChar
    foreach($name in $files){
        if([IO.Path]::IsPathRooted($name) -or '..' -cin ($name -split '[/\\]')){throw 'Carrier feature proof path escaped stage'}
        $input=Get-Item -LiteralPath (Join-Path $Stage $name)
        if($input.PSIsContainer -or $input.LinkType -or !$input.FullName.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)){throw 'Ordinary carrier capability input required'}
        $taskExternalInputs["$Relative/$name"]=$input.FullName
    }
}
if($Csqtt25Stage){
    $taskCsqtt25Root=[IO.Path]::GetFullPath($Csqtt25Stage)
    $taskCsqtt25Allowed=[IO.Path]::GetFullPath('D:/MCRF/Builds/Android')
    if(!$taskCsqtt25Root.StartsWith($taskCsqtt25Allowed+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
       [IO.Path]::GetFileName($taskCsqtt25Root) -notmatch '^csqtt25-captcha-[0-9a-f]{32}$' -or
       (Get-Item -LiteralPath $taskCsqtt25Root).LinkType){throw 'Use a completed isolated CSQTT2.5 stage, never a live source directory'}
    $taskCsqtt25Receipt=Get-Content -LiteralPath (Join-Path $taskCsqtt25Root 'receipt.json') -Raw | ConvertFrom-Json
    if($taskCsqtt25Receipt.state -ne 'COMPILED_NOT_EXECUTED' -or $taskCsqtt25Receipt.kind -ne 'csqtt25' -or !$taskCsqtt25Receipt.sourceStable){throw 'CSQTT2.5 native producer has not completed'}
    $taskCsqtt25Relative='ports/android/native-stage-csqtt25'
    Add-CarrierFeatureInputs $taskCsqtt25Root $taskCsqtt25Receipt $taskCsqtt25Relative
    Add-P2ProcessInputs $taskCsqtt25Root $taskCsqtt25Receipt $taskCsqtt25Relative
    foreach($taskFile in @('receipt.json','source-manifest.json','arm64-v8a/libcsqtt25.so','arm64-v8a/libcsqtt25_transport.so','licenses/CSQTT-LICENSE','licenses/qwdtt-LICENSE')){
        $taskInput=Get-Item -LiteralPath (Join-Path $taskCsqtt25Root $taskFile)
        if($taskInput.PSIsContainer -or $taskInput.LinkType){throw 'Native stage must contain regular immutable inputs'}
        $taskExternalInputs["$taskCsqtt25Relative/$taskFile"]=$taskInput.FullName
    }
}
$taskCsqttLegacyRelative=$null
if($CsqttLegacyStage){
    $taskCsqttLegacyRoot=[IO.Path]::GetFullPath($CsqttLegacyStage)
    if([IO.Path]::GetDirectoryName($taskCsqttLegacyRoot) -ine [IO.Path]::GetFullPath('D:/MCRF/Builds/Android') -or
       [IO.Path]::GetFileName($taskCsqttLegacyRoot) -notmatch '^csqtt-captcha-[0-9a-f]{32}$' -or
       (Get-Item -LiteralPath $taskCsqttLegacyRoot).LinkType){throw 'Explicit completed paired legacy CSQTT stage required'}
    $taskCsqttLegacyReceipt=Get-Content -LiteralPath (Join-Path $taskCsqttLegacyRoot 'receipt.json') -Raw|ConvertFrom-Json
    if($taskCsqttLegacyReceipt.kind -ne 'csqtt' -or $taskCsqttLegacyReceipt.state -ne 'COMPILED_NOT_EXECUTED' -or
       !$taskCsqttLegacyReceipt.sourceStable -or $taskCsqttLegacyReceipt.captchaWaiterCapability -ne 'csqtt-id48-v1'){throw 'Paired legacy producer has not completed'}
    $taskCsqttLegacyRelative='ports/android/native-stage-csqtt-legacy'
    Add-CarrierFeatureInputs $taskCsqttLegacyRoot $taskCsqttLegacyReceipt $taskCsqttLegacyRelative
    Add-P2ProcessInputs $taskCsqttLegacyRoot $taskCsqttLegacyReceipt $taskCsqttLegacyRelative
    $taskStageFiles=@('receipt.json','source-manifest.json','licenses/CSQTT-LICENSE','licenses/qwdtt-LICENSE')
    foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){$taskStageFiles+=@("$taskAbi/libqwdtt.so","$taskAbi/libcsqtt_transport.so")}
    foreach($taskFile in $taskStageFiles){
        $taskInput=Get-Item -LiteralPath (Join-Path $taskCsqttLegacyRoot $taskFile)
        if($taskInput.PSIsContainer -or $taskInput.LinkType){throw 'Legacy stage input must be a regular immutable file'}
        $taskExternalInputs["$taskCsqttLegacyRelative/$taskFile"]=$taskInput.FullName
    }
}
$taskHistoricalRelative=@{}
foreach($taskKind in $CsqttHistoricalStages.Keys){
    if($taskKind -cnotin @('csqtt20','csqtt215')){throw 'Unknown historical native kind'}
    $taskHistoricalRoot=[IO.Path]::GetFullPath($CsqttHistoricalStages[$taskKind])
    if([IO.Path]::GetDirectoryName($taskHistoricalRoot) -ine [IO.Path]::GetFullPath('D:/MCRF/Builds/Android') -or
       [IO.Path]::GetFileName($taskHistoricalRoot) -cnotmatch '^csqtt-historical-package-[0-9a-f]{32}$' -or
       (Get-Item -LiteralPath $taskHistoricalRoot).LinkType){throw 'Exact completed historical package stage required'}
    $taskHistorical=Get-Content -LiteralPath "$taskHistoricalRoot/receipt.json" -Raw|ConvertFrom-Json
    $taskGeneration=if($taskKind -ceq 'csqtt20'){'2.0'}else{'2.1.5'}
    $taskRevision=if($taskKind -ceq 'csqtt20'){'2ff8a1a4fbe031fb76b799e172dce25cfe1ca942'}else{'de7afc23cec7481f98845bf24fc8943af19b4cf8'}
    if($taskHistorical.state -cne 'HISTORICAL_ENGINE_INPUTS_READY' -or !$taskHistorical.sourceStable -or
       $taskHistorical.kind -cne $taskKind -or $taskHistorical.generation -cne $taskGeneration -or
       $taskHistorical.revision -cne $taskRevision -or @($taskHistorical.artifacts).Count -ne 6){throw 'Historical package descriptor mismatch'}
    $taskHistoricalRelative[$taskKind]="ports/android/native-stage-$taskKind"
    $taskHistoricalFiles=@('receipt.json','native-receipt.json','wrapper-receipt.json','licenses/CSQTT-LICENSE','licenses/qwdtt-LICENSE')
    foreach($taskReceiptName in @('native','wrapper')){
        $taskReceiptPath="$taskHistoricalRoot/$taskReceiptName-receipt.json"
        if((Get-FileHash -LiteralPath $taskReceiptPath).Hash.ToLowerInvariant() -cne $taskHistorical."$($taskReceiptName)ReceiptSha256"){throw 'Historical producer receipt changed'}
    }
    foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
        foreach($taskLibrary in @("lib$taskKind.so",'libcsqtt_transport.so')){
            $taskName="$taskAbi/$taskLibrary"
            $taskRow=@($taskHistorical.artifacts|Where-Object {$_.path -ceq $taskName -and $_.abi -ceq $taskAbi})
            $taskArtifact=Get-Item -LiteralPath "$taskHistoricalRoot/$taskName"
            if($taskRow.Count -ne 1 -or $taskArtifact.LinkType -or $taskArtifact.PSIsContainer -or
               $taskArtifact.Length -ne $taskRow[0].bytes -or (Get-FileHash -LiteralPath $taskArtifact.FullName).Hash.ToLowerInvariant() -cne $taskRow[0].sha256){throw 'Historical native artifact identity changed'}
            $taskHistoricalFiles+=$taskName
        }
    }
    foreach($taskFile in $taskHistoricalFiles){
        $taskInput=Get-Item -LiteralPath "$taskHistoricalRoot/$taskFile"
        if($taskInput.LinkType -or $taskInput.PSIsContainer){throw 'Historical inputs must be ordinary immutable files'}
        $taskExternalInputs["$($taskHistoricalRelative[$taskKind])/$taskFile"]=$taskInput.FullName
    }
}
$taskSwgRelative=@{}
$taskS3Relative=$null
if($S3BypassStage){
    $taskS3Root=[IO.Path]::GetFullPath($S3BypassStage)
    if([IO.Path]::GetDirectoryName($taskS3Root) -ine [IO.Path]::GetFullPath('D:/MCRF/Builds/Android') -or
       [IO.Path]::GetFileName($taskS3Root) -cnotmatch '^s3bypass-native-[0-9a-f]{32}$' -or (Get-Item -LiteralPath $taskS3Root).LinkType){throw 'Exact completed S3 native stage required'}
    $taskS3Receipt=Get-Content -LiteralPath (Join-Path $taskS3Root 'receipt.json') -Raw|ConvertFrom-Json
    if($taskS3Receipt.state -cne 'COMPILED_NOT_EXECUTED' -or !$taskS3Receipt.sourceStable -or $taskS3Receipt.producerComplete -ne $true -or $taskS3Receipt.kind -cne 's3bypass' -or
       $taskS3Receipt.commit -cne 'b54ed13e3d7473ad8c25b5c39dfe127b004db540'){throw 'S3 producer/source mismatch'}
    $taskS3Relative='ports/android/native-stage-s3bypass'
    $taskS3Notices=Get-Content -LiteralPath (Join-Path $taskS3Root 'notice-manifest.json') -Raw|ConvertFrom-Json
    if(@($taskS3Notices.missingModules).Count){throw 'S3 dependency notices need review; keep existing compiled binaries, do not rebuild them'}
    if($taskS3Receipt.controlFile -cne 'producer-control.ps1' -or (Get-FileHash -LiteralPath (Join-Path $taskS3Root 'producer-control.ps1') -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskS3Receipt.controlSha256){throw 'Native producer snapshot differs from its receipt'}
    $taskS3Files=@('receipt.json','source-manifest.json','notice-manifest.json','producer-control.ps1')
    foreach($taskNotice in $taskS3Notices.files){
        $taskRelative='licenses/'+$taskNotice.file
        $taskNoticeFile=[IO.Path]::GetFullPath((Join-Path $taskS3Root $taskRelative))
        if(!$taskNoticeFile.StartsWith((Join-Path $taskS3Root 'licenses')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
            (Get-FileHash -LiteralPath $taskNoticeFile -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskNotice.sha256){throw 'S3 notice path/digest mismatch'}
        $taskS3Files+=$taskRelative
    }
    foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){$taskS3Files+="$taskAbi/libs3bypass.so"}
    foreach($taskFile in $taskS3Files){
        $taskInput=Get-Item -LiteralPath (Join-Path $taskS3Root $taskFile)
        if($taskInput.PSIsContainer -or $taskInput.LinkType){throw 'S3 input must be a regular immutable file'}
        $taskExternalInputs["$taskS3Relative/$taskFile"]=$taskInput.FullName
    }
}
foreach($taskKind in $SwgNativeStages.Keys){
    if($taskKind -notin @('qwdtt','wdtt')){throw 'Only WDTT/QWDTT standalone stages are accepted here'}
    $taskStageRoot=[IO.Path]::GetFullPath($SwgNativeStages[$taskKind])
    if([IO.Path]::GetDirectoryName($taskStageRoot) -ine [IO.Path]::GetFullPath('D:/MCRF/Builds/Android') -or
       [IO.Path]::GetFileName($taskStageRoot) -notmatch "^$taskKind-captcha-[0-9a-f]{32}$" -or
       (Get-Item -LiteralPath $taskStageRoot).LinkType){throw 'Explicit completed standalone native stage required'}
    $taskStageReceipt=Get-Content -LiteralPath (Join-Path $taskStageRoot 'receipt.json') -Raw|ConvertFrom-Json
    if($taskStageReceipt.kind -ne $taskKind -or $taskStageReceipt.state -ne 'COMPILED_NOT_EXECUTED' -or !$taskStageReceipt.sourceStable){throw 'Standalone native producer has not completed'}
    $taskSwgRelative[$taskKind]="ports/android/native-stage-$taskKind"
    Add-CarrierFeatureInputs $taskStageRoot $taskStageReceipt $taskSwgRelative[$taskKind]
    $taskStageFiles=@('receipt.json','source-manifest.json',"licenses/$taskKind-LICENSE")
    foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){$taskStageFiles+="$taskAbi/lib$taskKind.so"}
    foreach($taskFile in $taskStageFiles){
        $taskInput=Get-Item -LiteralPath (Join-Path $taskStageRoot $taskFile)
        if($taskInput.PSIsContainer -or $taskInput.LinkType){throw 'Standalone stage contains a linked/non-file input'}
        $taskExternalInputs["$($taskSwgRelative[$taskKind])/$taskFile"]=$taskInput.FullName
    }
}
function Resolve-TaskSource([string]$Relative){if($taskExternalInputs.ContainsKey($Relative)){return $taskExternalInputs[$Relative]};return Join-Path $taskWorkspace $Relative}
$taskInputs=@(
    'ports/android/settings.gradle.kts','ports/android/build.gradle.kts','ports/android/gradle.properties',
    'ports/android/gradle/wrapper/gradle-wrapper.jar','ports/android/gradle/wrapper/gradle-wrapper.properties',
    'ports/android/app/build.gradle.kts','ports/android/app/proguard-rules.pro',
    'ports/android/engines/build.gradle.kts','ports/android/engines/EngineUpdateOptionalManifest.xml','ports/android/engines/EngineUpdatePrimaryManifest.xml','ports/android/engines/EngineUpdateTelegramManifest.xml','ports/android/engines/EngineUpdateTelegramStage.json','ports/android/transports/transport-lock.json',
    'ports/android/android-core-lock.json'
    'ports/android/primary-spi/build.gradle.kts','ports/android/primary-spi/contract-lock.json',
    'ports/android/primary-adapter/build.gradle.kts','ports/android/primary-adapter/src/main/AndroidManifest.xml',
    'ports/android/primary-cores/singbox/consumer-rules.pro',
    'work/steer-android-port/android-vpn/module-contract/src/main/java/org/mcrf/engine/spi/v1/Engine.java',
    'work/steer-android-port/android-vpn/module-contract/src/main/java/org/mcrf/engine/spi/v1/Platform.java'
    'work/steer-android-port/android-vpn/integration-helper/src/main/java/org/mcrf/steerprep/SteerMcrfSnapshotAdapter.java'
    'work/steer-android-port/android-vpn/integration-helper/src/main/java/org/mcrf/steerprep/SteerGeoProjection.java'
    'work/steer-android-port/android-vpn/integration-helper/src/main/java/org/mcrf/steerprep/json/SteerSnapshotJson.java'
    'work/steer-android-port/android-vpn/integration-helper/src/main/java/org/mcrf/steerprep/json/SteerNativeFlowShape.java'
)
$taskInputs+=@($taskExternalInputs.Keys)
if($P2Expansion){
    $taskInputs+=@('ports/android/coordination/current.json',$taskExpansionRelative,
        'ports/android/coordination/p2-expansion-jvm-affected-2026-10-21.txt',
        'ports/android/integration-build.ps1','ports/android/maintenance/Assert-HumanBatchReady.ps1',
        'ports/android/maintenance/Read-ExpandedP2GroupRetention.ps1')
    Push-Location $taskWorkspace
    try{
        $taskInputs+=@(& rg --files ports/android/transports/swg -g '!**/build/**' -g '!**/*.exe')
        if($LASTEXITCODE){throw 'Unable to capture expanded-P2 transport inputs'}
    }finally{Pop-Location}
}
if($taskP2Candidate){
    $taskInputs+=@('ports/android/coordination/current.json',$taskP2BatchRelative,'ports/android/coordination/p2-jvm-affected-2026-10-20.txt','ports/android/integration-build.ps1','ports/android/maintenance/Assert-HumanBatchReady.ps1',
        'ports/android/maintenance/Build-P2RouteNative.ps1','ports/android/maintenance/Prepare-P2RouteNative.ps1',
        'ports/android/maintenance/Read-P2KernelEvidence.ps1','ports/android/maintenance/Project-P2KernelEvidence.ps1',
        'ports/android/maintenance/Prepare-P2KernelAndroid.ps1','ports/android/maintenance/Run-P2KernelAndroid.py','ports/android/maintenance/p2-kernel-owner.go.in','ports/android/maintenance/Read-P2RetainedJvm.ps1','ports/android/maintenance/Read-P2SuccessfulJvm.ps1','ports/android/maintenance/Read-ExpandedP2RetainedJvm.ps1')
    Push-Location $taskWorkspace
    try{
        $taskInputs+=@(& rg --files ports/android/p2-route-dispatcher ports/android/primary-cores/singbox/native-patches/p2-route-pipes ports/android/primary-cores/singbox/native-patches/p044-direct-family ports/android/transports/swg -g '!**/build/**' -g '!**/*.exe')
        if($LASTEXITCODE){throw 'Unable to capture exact P2 dispatcher sources'}
    }finally{Pop-Location}
}
if($P1OwnerFixture){$taskInputs+=@('ports/android/p1-owner-probe/build.gradle.kts','ports/android/coordination/current.json',$taskP1BatchRelative,'ports/android/integration-build.ps1','ports/android/integration-device-checks.ps1','ports/android/integration-p1-steer.ps1','ports/android/maintenance/Assert-HumanBatchReady.ps1','work/steer-android-port/android-vpn/tests/p1_owned_peer.py','work/steer-android-port/android-vpn/tests/test_p1_owned_peer.py')}
if($P1OwnerFixture){$taskInputs+='ports/android/p1-owner-peer/build.gradle.kts'}
if($P1OwnerFixture){$taskInputs+=@('ports/android/integration-packet-phone.ps1','ports/android/WireGuardFixtures/go.mod','ports/android/WireGuardFixtures/go.sum','ports/android/WireGuardFixtures/Build-PacketAuditPeer.ps1')}
$taskInputs+='ports/android/engines/EngineUpdateCsqtt25Manifest.xml'
$taskInputs+='ports/android/engines/EngineUpdateCsqttHistoricalManifest.xml'
$taskInputs+='ports/android/engines/EngineUpdateS3BypassManifest.xml'
$taskInputs+='ports/android/engines/EngineUpdateCsqttLegacyManifest.xml'
$taskInputs+='ports/android/engines/EngineUpdateSwgManifest.xml'
foreach($stageRelative in @($taskPrimaryRelative,$taskOpenFluxRelative,$taskOpenFluxAckRelative,$taskOpenFluxProviderRelative,$taskOpenFluxProviderV2Relative,$taskOpenFluxProviderV3Relative)) {
    if($taskP1ExternalPrimary -and $stageRelative -ceq $taskPrimaryRelative){continue}
    if($stageRelative) {
        Push-Location $taskWorkspace
        try {$taskInputs+=@(& rg --files --hidden $stageRelative);if($LASTEXITCODE -ne 0){throw 'Unable to inventory native stage'}}finally{Pop-Location}
    }
}
if($taskWarpRelative){
    $taskInputs+="$taskWarpRelative/receipt.json"
    foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){$taskInputs+="$taskWarpRelative/candidates/$abi/libwarpscout.so"}
}
if($taskOptionalRelative){
    $taskInputs+="$taskOptionalRelative/receipt.json"
    foreach($kind in @('xray','tls-core')){foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){
        $library=if($kind -eq 'xray'){'libxray.so'}else{'libtls_core.so'}
        $taskInputs+="$taskOptionalRelative/candidates/$kind/$abi/$library"
    }}
}
if($taskOptionalFixtureRelative){
    $taskInputs+="$taskOptionalFixtureRelative/receipt.json"
    foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){$taskInputs+="$taskOptionalFixtureRelative/fixtures/$abi/liboptional_fixture.so"}
}
if($taskDnsttFixtureRelative){
    $taskInputs+="$taskDnsttFixtureRelative/receipt.json"
    foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){$taskInputs+="$taskDnsttFixtureRelative/fixtures/$abi/libdnstt_testserver.so"}
}
function Get-TaskInputList {
    Push-Location $taskWorkspace
    try {
        $files=@($taskInputs)+@(& rg --files --hidden ports/android/app/src ports/android/app/libs ports/android/engines/src ports/android/primary-spi/src ports/android/wg-module ports/android/transports/wg-backend/android/src ports/android/optional-cores/licenses ports/android/primary-cores/singbox/src ports/catalogue ports/fixtures |
            Where-Object {$_ -notmatch '^ports[/\\]android[/\\]engines[/\\]src[/\\].*[/\\]jniLibs[/\\]'})
        if($LASTEXITCODE -ne 0){throw 'Unable to inventory integration inputs'}
        if($P1OwnerFixture){$files+=@(& rg --files --hidden ports/android/p1-owner-probe/src ports/android/p1-owner-peer/src ports/android/WireGuardFixtures/cmd/WireGuardPeer);if($LASTEXITCODE -ne 0){throw 'Unable to inventory P1 helper pair/independent packet peer sources'}}
        if($taskWgRelative){$files+=@("$taskWgRelative/receipt.json","$taskWgRelative/wg-backend.aar")}
        @($files | Where-Object {$_ -notmatch '(?:^|[/\\])__pycache__(?:[/\\]|$)|\.pyc$'} | ForEach-Object {$_ -replace '\\','/'} | Sort-Object -Unique)
    }finally{Pop-Location}
}
function Get-TaskManifest {
    $rows=foreach($relative in Get-TaskInputList){
        $source=Resolve-TaskSource $relative
        [pscustomobject]@{path=$relative;bytes=(Get-Item -LiteralPath $source).Length;sha256=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
    @($rows)
}
$taskSnapshot=$null
for($attempt=1;$attempt -le 3;$attempt++){
    $taskBefore=@(Get-TaskManifest)
    $taskCandidate=Join-Path $taskWorkspace ('work/android-integration-'+[guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $taskCandidate | Out-Null
    foreach($entry in $taskBefore){
        $source=Resolve-TaskSource $entry.path
        $destination=[IO.Path]::GetFullPath((Join-Path $taskCandidate $entry.path))
        if(!$destination.StartsWith($taskCandidate+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Input escaped integration snapshot'}
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
        # Copy, rather than link, so the frozen build can never alter or reread a live source input.
        [IO.File]::Copy($source,$destination,$false)
    }
    $taskAfter=@(Get-TaskManifest)
    $taskStable=($taskBefore|ConvertTo-Json -Compress -Depth 3) -ceq ($taskAfter|ConvertTo-Json -Compress -Depth 3)
    if($taskStable){
        foreach($entry in $taskBefore){
            if((Get-FileHash -LiteralPath (Join-Path $taskCandidate $entry.path)).Hash.ToLowerInvariant() -ne $entry.sha256){$taskStable=$false;break}
        }
    }
    if($taskStable){$taskSnapshot=$taskCandidate;break}
    Write-Output "Inputs changed during capture; retained unused owned snapshot and retrying ($attempt/3)."
}
if(!$taskSnapshot){throw 'Sources changed during all three snapshot attempts; no build started'}
function Assert-FrozenTaskInputs {
    if($taskManifestSha -and (Get-FileHash -LiteralPath (Join-Path $taskSnapshot 'source-manifest.json') -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskManifestSha){throw 'Frozen source manifest changed; no artifact export'}
    foreach($taskInputRow in $taskBefore){
        $taskFrozenFile=Get-Item -LiteralPath (Join-Path $taskSnapshot $taskInputRow.path)
        if($taskFrozenFile.PSIsContainer -or $taskFrozenFile.LinkType -or $taskFrozenFile.Length -ne $taskInputRow.bytes -or
           (Get-FileHash -LiteralPath $taskFrozenFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskInputRow.sha256){throw 'Immutable integration source changed; no artifact may be accepted/exported'}
    }
}
if(Test-Path -LiteralPath $taskRetention){
    $taskPolicy=Get-Content -LiteralPath $taskRetention -Raw|ConvertFrom-Json
    $taskPolicy.activeSnapshots=@([IO.Path]::GetFileName($taskSnapshot))
    [IO.File]::WriteAllText($taskRetention,($taskPolicy|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
}
$taskAndroid=Join-Path $taskSnapshot 'ports/android'
$taskScratch=$null
if($BuildScratchRoot){
    $taskScratchRoot=[IO.Path]::GetFullPath($BuildScratchRoot)
    if($taskScratchRoot -ine [IO.Path]::GetFullPath('D:/MCRF/Builds/Android')){throw 'Build scratch must use the dedicated D:/MCRF/Builds/Android directory'}
    if($taskScratchRoot -ieq $taskWorkspace -or $taskScratchRoot.StartsWith($taskWorkspace+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Build scratch must stay outside the source workspace'}
    New-Item -ItemType Directory -Path $taskScratchRoot -Force|Out-Null
    $taskScratch=Join-Path $taskScratchRoot ([IO.Path]::GetFileName($taskSnapshot))
    if(Test-Path -LiteralPath $taskScratch){throw 'Build scratch already exists'}
    New-Item -ItemType Directory -Path $taskScratch|Out-Null
    foreach($taskModule in @('app','engines','primary-spi','primary-adapter','wg-module')){
        $taskOutput=Join-Path $taskScratch $taskModule
        New-Item -ItemType Directory -Path $taskOutput|Out-Null
        $taskLink=Join-Path $taskAndroid "$taskModule/build"
        if(Test-Path -LiteralPath $taskLink){throw 'Snapshot build output already exists'}
        New-Item -ItemType Junction -Path $taskLink -Target $taskOutput|Out-Null
    }
    Write-Output "GENERATED_BUILD_SCRATCH=$taskScratch"
}
if((Get-FileHash -LiteralPath (Join-Path $taskAndroid 'gradle/wrapper/gradle-wrapper.jar')).Hash.ToLowerInvariant() -ne '76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3'){throw 'Gradle wrapper SHA256 mismatch'}
$taskJavaTemp=Join-Path $taskWorkspace 'work/toolchains/java-tmp'
New-Item -ItemType Directory -Path $taskJavaTemp -Force | Out-Null
$taskShortTemp=(New-Object -ComObject Scripting.FileSystemObject).GetFolder($taskJavaTemp).ShortPath
if($taskShortTemp.Length -gt 85){throw 'Integration JDK socket path too long'}
$taskPreviousOptions=$env:JAVA_TOOL_OPTIONS
$env:JAVA_TOOL_OPTIONS=($taskPreviousOptions+' "-Djdk.net.unixdomain.tmpdir='+$taskShortTemp+'"').Trim()
$env:JAVA_HOME=$JavaHome
$env:ANDROID_HOME=$AndroidSdk
$env:GRADLE_USER_HOME=Join-Path $taskWorkspace 'work/toolchains/gradle-home'
$taskExtra=@()
if($taskP2Candidate){
    $taskP2FrozenReady=Join-Path $taskSnapshot $taskP2BatchRelative
    $taskP2FrozenCurrent=Get-Content -LiteralPath (Join-Path $taskAndroid 'coordination/current.json') -Raw|ConvertFrom-Json
    if($taskP2FrozenCurrent.activeBatch -cne 'p2-progress-2026-10-20.json' -or (Get-FileHash -LiteralPath $taskP2FrozenReady).Hash.ToLowerInvariant() -cne $taskP2ReadySha){throw 'Whole-P2 gate changed during capture'}
    $taskExtra+=@("-Pp2SourceReadyReceipt=$taskP2FrozenReady","-Pp2SourceReadySha256=$taskP2ReadySha",'--no-daemon')
    $taskP2JvmSelection=Join-Path $taskAndroid 'coordination/p2-jvm-affected-2026-10-20.txt'
    $taskExtra+="-Pp2JvmSelectionSha256=$((Get-FileHash -LiteralPath $taskP2JvmSelection).Hash.ToLowerInvariant())"
    if($RetainedP2JvmSnapshot){
        $retainedJvm=& "$PSScriptRoot/maintenance/Read-P2RetainedJvm.ps1" -Snapshot $RetainedP2JvmSnapshot -CurrentManifest $taskBefore -NativeStage $stageRoot
        $retainedJvm.sourceReadySha256=$taskP2ReadySha
        $retainedJvmPath=Join-Path $taskSnapshot 'p2-jvm-retained.json'
        [IO.File]::WriteAllText($retainedJvmPath,($retainedJvm|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
        $taskExtra+=@("-Pp2JvmRetainedProof=$retainedJvmPath","-Pp2JvmRetainedSha256=$((Get-FileHash -LiteralPath $retainedJvmPath).Hash.ToLowerInvariant())")
    }
    if($P2SteerCandidate){$taskExtra+='-Pp2SteerCandidate=true'}
    if($P2WgCandidate){$taskExtra+='-Pp2WgCandidate=true'}
    if($P2DispatcherCandidate){$taskExtra+=@('-Pp2DispatcherCandidate=true',"-Pp2DispatcherStage=$(Join-Path $taskSnapshot $taskDispatcherRelative)")}
}
if($P2Expansion){
    $frozenReady=Join-Path $taskSnapshot $taskExpansionRelative
    if((Get-FileHash -LiteralPath $frozenReady).Hash.ToLowerInvariant() -cne $taskExpansionSha){throw 'Expanded-P2 source gate changed during capture'}
    $taskP2JvmSelection=Join-Path $taskAndroid 'coordination/p2-expansion-jvm-affected-2026-10-21.txt'
    if($RetainedExpansionJvmSnapshot){
        $retentionReader=if($taskHistoricalRelative.Count){'Read-CsqttGenerationsRetainedJvm.ps1'}else{'Read-ExpandedP2RetainedJvm.ps1'}
        $retainedJvm=& "$PSScriptRoot/maintenance/$retentionReader" -Snapshot $RetainedExpansionJvmSnapshot -CurrentManifest $taskBefore
        $retainedJvm.sourceReadySha256=$taskExpansionSha
        $retainedJvmPath=Join-Path $taskSnapshot 'p2-expansion-jvm-retained.json'
        [IO.File]::WriteAllText($retainedJvmPath,($retainedJvm|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
        if($retainedJvm.allRetained){$taskExtra+=@('-x',':app:testDebugUnitTest')}
        else{$taskExtra+=@("-Pp2ExpansionJvmRetainedProof=$retainedJvmPath","-Pp2ExpansionJvmRetainedSha256=$((Get-FileHash -LiteralPath $retainedJvmPath).Hash.ToLowerInvariant())")}
    }
    $taskExtra+=@("-Pp2ExpansionSourceReadyReceipt=$frozenReady","-Pp2ExpansionSourceReadySha256=$taskExpansionSha",
        "-Pp2ExpansionJvmSelectionSha256=$((Get-FileHash -LiteralPath $taskP2JvmSelection).Hash.ToLowerInvariant())",'--no-daemon')
}
if($taskCsqtt25Relative){$taskExtra+="-Pcsqtt25Stage=$(Join-Path $taskSnapshot $taskCsqtt25Relative)";$taskExtra+='-Pcsqtt25CandidateGate=true'}
foreach($taskKind in $taskHistoricalRelative.Keys){$taskExtra+="-P$($taskKind)Stage=$(Join-Path $taskSnapshot $taskHistoricalRelative[$taskKind])"}
if($taskHistoricalRelative.Count){$taskExtra+='-PhistoricalCsqttCandidateGate=true'}
if($taskS3Relative){$taskExtra+="-Ps3BypassStage=$(Join-Path $taskSnapshot $taskS3Relative)";$taskExtra+='-Ps3BypassCandidateGate=true'}
if($taskCsqttLegacyRelative){$taskExtra+="-PcsqttLegacyStage=$(Join-Path $taskSnapshot $taskCsqttLegacyRelative)";$taskExtra+='-PcsqttLegacyCandidateGate=true'}
foreach($taskKind in $taskSwgRelative.Keys){$taskExtra+="-Pswg$($taskKind.Substring(0,1).ToUpperInvariant()+$taskKind.Substring(1))Stage=$(Join-Path $taskSnapshot $taskSwgRelative[$taskKind])"}
if($taskSwgRelative.Count){$taskExtra+='-PswgNativeCandidateGate=true'}
if($taskWarpRelative){$taskExtra+="-PwarpScannerStage=$(Join-Path $taskSnapshot $taskWarpRelative)";$taskExtra+='-PwarpScannerCandidateGate=true'}
if($taskWgRelative){$taskExtra+="-PwgBackendStage=$(Join-Path $taskSnapshot $taskWgRelative)";$taskExtra+='-PwgBackendCandidateGate=true'}
if($TestRelease){$taskExtra+='-PtestRelease=true'}
if($P1OwnerFixture){
    $taskP1FrozenReady=Join-Path $taskSnapshot $taskP1BatchRelative
    $taskP1FrozenCurrent=Get-Content -LiteralPath (Join-Path $taskAndroid 'coordination/current.json') -Raw|ConvertFrom-Json
    $taskP1FrozenBatch=Get-Content -LiteralPath $taskP1FrozenReady -Raw|ConvertFrom-Json
    if($taskP1FrozenCurrent.activeBatch -cne [IO.Path]::GetFileName($taskP1BatchRelative) -or $taskP1FrozenBatch.priority -ne 1 -or $taskP1FrozenBatch.readyForBuild -isnot [bool] -or !$taskP1FrozenBatch.readyForBuild){throw 'Frozen common-P1 readiness changed during capture'}
    $taskP1ReadySha=(Get-FileHash -LiteralPath $taskP1FrozenReady -Algorithm SHA256).Hash.ToLowerInvariant()
    $taskExtra+=@('-Pp1OwnerFixture=true',"-Pp1SourceReadyReceipt=$taskP1FrozenReady","-Pp1SourceReadySha256=$taskP1ReadySha")
    if($P1WgCandidate){$taskExtra+='-Pp1WgCandidate=true'}
    if($P1SteerCandidate){$taskExtra+='-Pp1SteerCandidate=true'}
    if($taskP1OriginalReadyRelative){
        $oldReadyPath=Join-Path $taskSnapshot $taskP1OriginalReadyRelative
        $taskExtra+=@("-Pp1NativeSourceReadyReceipt=$oldReadyPath","-Pp1NativeSourceReadySha256=$((Get-FileHash -LiteralPath $oldReadyPath).Hash.ToLowerInvariant())")
    }
    $taskExtra+='--no-daemon'
}
if($FirstLaunchFixture){$taskExtra+='-PisolatedFirstLaunchFixture=true';$taskExtra+='-PisolatedFirstLaunchFixtureGate=true'}
if($ReleaseSmoke){$taskExtra+='-PreleaseSmoke=true'}
if($taskOptionalRelative){$taskExtra+="-PoptionalCoreStage=$(Join-Path $taskSnapshot $taskOptionalRelative)";$taskExtra+='-PoptionalCoreNativeGate=true'}
if($taskOptionalFixtureRelative){$taskExtra+="-PoptionalDebugFixtureStage=$(Join-Path $taskSnapshot $taskOptionalFixtureRelative)";$taskExtra+='-PoptionalDebugFixtureNativeGate=true'}
if($taskDnsttFixtureRelative){$taskExtra+="-PdnsttDebugFixtureStage=$(Join-Path $taskSnapshot $taskDnsttFixtureRelative)";$taskExtra+='-PdnsttDebugFixtureNativeGate=true'}
if($taskPrimaryRelative){$taskExtra+="-PprimaryCoreStage=$(Join-Path $taskSnapshot $taskPrimaryRelative)";$taskExtra+='-PprimaryCoreNativeGate=true'}
if($taskOpenFluxRelative){$taskExtra+="-PopenfluxPromotionStage=$(Join-Path $taskSnapshot $taskOpenFluxRelative)";$taskExtra+='-PopenfluxPromotionGate=true'}
if($taskOpenFluxAckRelative){$taskExtra+="-PopenfluxAckStage=$(Join-Path $taskSnapshot $taskOpenFluxAckRelative)";$taskExtra+='-PopenfluxAckGate=true'}
if($taskOpenFluxProviderRelative){$taskExtra+="-PopenfluxProviderStage=$(Join-Path $taskSnapshot $taskOpenFluxProviderRelative)";$taskExtra+='-PopenfluxProviderGate=true'}
if($taskOpenFluxProviderV2Relative){$taskExtra+="-PopenfluxProviderV2Stage=$(Join-Path $taskSnapshot $taskOpenFluxProviderV2Relative)";$taskExtra+='-PopenfluxProviderV2Gate=true'}
if($taskOpenFluxProviderV3Relative){$taskExtra+="-PopenfluxProviderV3Stage=$(Join-Path $taskSnapshot $taskOpenFluxProviderV3Relative)";$taskExtra+='-PopenfluxProviderV3Gate=true'}
if($TelegramModule){$taskExtra+='-PtgwsNativeGate=true'}
# Generated evidence only: no live source file or platform artifact is overwritten.
[IO.File]::WriteAllText((Join-Path $taskSnapshot 'source-manifest.json'),($taskBefore|ConvertTo-Json -Depth 3),(New-Object Text.UTF8Encoding($false)))
$taskManifestSha=(Get-FileHash -LiteralPath (Join-Path $taskSnapshot 'source-manifest.json') -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Output "INTEGRATION_SNAPSHOT=$taskSnapshot"
Push-Location $taskAndroid
try{
    Assert-FrozenTaskInputs
    & (Join-Path $JavaHome 'bin/java.exe') -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain $Task '--console=plain' @taskExtra 2>&1 | Tee-Object -FilePath (Join-Path $taskSnapshot 'build.log')
    $taskExit=$LASTEXITCODE
    $taskP2JvmEvidenceSha=$null
    if($taskExit -eq 0 -and ($taskP2Candidate -or $P2Expansion)){
        try {
            $selection=@(Get-Content -LiteralPath $taskP2JvmSelection|Where-Object {$_ -cne ''})
            $reports=if($retainedJvm.allRetained){@()}else{@(Get-ChildItem -LiteralPath (Join-Path $taskAndroid 'app/build/test-results/testDebugUnitTest') -File -Filter 'TEST-*.xml')}
            if((!$reports.Count -and !$retainedJvm.allRetained) -or $reports.Count -gt 256){throw 'Missing/bounded affected test XML required'}
            $rows=@();$reportRows=@();$seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
            foreach($report in $reports){
                if($report.LinkType -or $report.Length -gt 33554432){throw 'Ordinary bounded affected test XML required'}
                $options=[Xml.XmlReaderSettings]::new();$options.DtdProcessing=[Xml.DtdProcessing]::Prohibit;$options.XmlResolver=$null
                $reader=[Xml.XmlReader]::Create($report.FullName,$options)
                try{$xml=[Xml.XmlDocument]::new();$xml.XmlResolver=$null;$xml.Load($reader)}finally{$reader.Dispose()}
                foreach($case in @($xml.SelectNodes('/testsuite/testcase'))){
                    $class=$case.GetAttribute('classname');$name=$case.GetAttribute('name');$identity=$class+'.'+$name
                    if(!$seen.Add($identity) -or $case.SelectNodes('failure|error|skipped').Count -or
                        !@($selection|Where-Object {$_ -ceq $class -or $_ -ceq $identity}).Count){throw 'Failed/skipped/duplicate or unselected JVM case executed'}
                    $rows+=[pscustomobject]@{class=$class;name=$name}
                }
                $reportRows+=[pscustomobject]@{file=$report.Name;bytes=$report.Length;sha256=(Get-FileHash -LiteralPath $report.FullName).Hash.ToLowerInvariant()}
            }
            $newCaseCount=$rows.Count
            if($retainedJvm){foreach($case in $retainedJvm.cases){if(!$seen.Add($case.class+'.'+$case.name)){throw 'Fresh/retained JVM overlap'};$rows+=$case}}
            foreach($pattern in $selection){
                if(!@($rows|Where-Object {$_.class -ceq $pattern -or ($_.class+'.'+$_.name) -ceq $pattern}).Count){throw 'A selected affected JVM class/method did not execute'}
            }
            $evidence=[ordered]@{priority=2;passed=$true;scope='Affected JVM only, not native/Android acceptance';selectionSha256=(Get-FileHash -LiteralPath $taskP2JvmSelection).Hash.ToLowerInvariant();cases=$rows;reports=$reportRows;count=$rows.Count}
            $evidence.newExecutionCount=$newCaseCount
            if($retainedJvm){$evidence.retained=$retainedJvm;$evidence.retainedProofSha256=(Get-FileHash -LiteralPath $retainedJvmPath).Hash.ToLowerInvariant()}
            if($P2Expansion){$evidence.verificationAuthority='human-20261021-expanded-p2-whole-source-before-common-verification';$evidence.sourceReadySha256=$taskExpansionSha}
            $evidencePath=Join-Path $taskSnapshot $(if($P2Expansion){'p2-expansion-jvm-evidence.json'}else{'p2-jvm-evidence.json'})
            [IO.File]::WriteAllText($evidencePath,($evidence|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
            $taskP2JvmEvidenceSha=(Get-FileHash -LiteralPath $evidencePath).Hash.ToLowerInvariant()
        }catch{$taskExit=1;Write-Output 'Affected JVM evidence failed/incomplete; retain XML, no candidate export'}
    }
    $taskRunnerArtifacts=@()
    if($taskExit -eq 0 -and $BothTestRunners){
        $taskTestOutput=Join-Path $taskAndroid 'app/build/outputs/apk/androidTest/debug'
        $taskDefaultTest=Join-Path $taskTestOutput 'app-debug-androidTest.apk'
        $taskDeviceTest=Join-Path $taskTestOutput 'app-device-smoke-androidTest.apk'
        $taskReleaseTest=Join-Path $taskTestOutput 'app-release-smoke-androidTest.apk'
        Copy-Item -LiteralPath $taskDefaultTest -Destination $taskDeviceTest
        & (Join-Path $JavaHome 'bin/java.exe') -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain ':app:assembleDebugAndroidTest' '--console=plain' @taskExtra '-PreleaseSmoke=true' 2>&1 | Tee-Object -FilePath (Join-Path $taskSnapshot 'release-runner-build.log')
        $taskExit=$LASTEXITCODE
        if($taskExit -eq 0){
            Copy-Item -LiteralPath $taskDefaultTest -Destination $taskReleaseTest
            Copy-Item -LiteralPath $taskDeviceTest -Destination $taskDefaultTest -Force
            Remove-Item -LiteralPath $taskDeviceTest
            $taskRunnerArtifacts=@($taskDefaultTest,$taskReleaseTest)|ForEach-Object {[pscustomobject]@{path=[IO.Path]::GetRelativePath($taskSnapshot,$_);sha256=(Get-FileHash -LiteralPath $_).Hash.ToLowerInvariant()}}
        }
    }
    $taskFrozenStable=$false
    try{Assert-FrozenTaskInputs;$taskFrozenStable=$true}catch{Write-Output 'Frozen source verification FAILED; no artifact export'}
    [IO.File]::WriteAllText((Join-Path $taskSnapshot 'build-result.json'),([pscustomobject]@{task=$Task;exitCode=$taskExit;snapshot=$taskSnapshot;inputCount=$taskBefore.Count;sourceStable=$taskFrozenStable;sourceManifestSha256=$taskManifestSha;generatedBuildScratch=$taskScratch;firstLaunchFixture=[bool]$FirstLaunchFixture;p1OwnerFixture=[bool]$P1OwnerFixture}|ConvertTo-Json),(New-Object Text.UTF8Encoding($false)))
    if(!$taskFrozenStable){throw 'Immutable source changed during build; failed evidence retained, no export'}
    if($taskExit -ne 0){throw "Integration build failed; retain the exact build log and source snapshot: $taskSnapshot"}
    if($P2Expansion){
        $artifacts=@()
        $paths=@('app/build/outputs/apk/debug/app-universal-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
        foreach($kind in (@('csqtt','csqtt25','qwdtt','wdtt')+@($taskHistoricalRelative.Keys|Sort-Object))){
            $modules=@(Get-ChildItem -LiteralPath (Join-Path $taskAndroid "engines/build/outputs/apk/$kind/debug") -File -Filter '*.apk')
            if(!$modules.Count){throw 'Missing changed BS module in expanded common package'}
            $paths+=@($modules|ForEach-Object {[IO.Path]::GetRelativePath($taskAndroid,$_.FullName)})
        }
        foreach($path in $paths){
            $artifact=Get-Item -LiteralPath (Join-Path $taskAndroid $path)
            if($artifact.LinkType -or $artifact.PSIsContainer){throw 'Ordinary expanded-P2 artifact required'}
            if($artifact.Name -ceq 'app-universal-debug.apk'){
                $archive=[IO.Compression.ZipFile]::OpenRead($artifact.FullName)
                try {if(@($archive.Entries|Where-Object {$_.FullName -match '(?:^|/)__pycache__/|\.pyc$'}).Count){throw 'Generated Python bytecode must never enter the main APK'}}finally{$archive.Dispose()}
            }
            $artifacts+=[ordered]@{path=[IO.Path]::GetRelativePath($taskSnapshot,$artifact.FullName);sha256=(Get-FileHash -LiteralPath $artifact.FullName).Hash.ToLowerInvariant();bytes=$artifact.Length}
        }
        [IO.File]::WriteAllText((Join-Path $taskSnapshot 'p2-expansion-artifacts.json'),([ordered]@{
            authority='human-20261021-expanded-p2-whole-source-before-common-verification';readinessSha256=$taskExpansionSha;
            sourceManifestSha256=$taskManifestSha;jvmEvidenceSha256=$taskP2JvmEvidenceSha;artifacts=$artifacts;
            installed=$false;runtimeVerified=$false;published=$false
        }|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
    }
    if($taskP2Candidate){
        $taskP2Artifacts=@('app/build/outputs/apk/debug/app-universal-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')|ForEach-Object {
            $artifact=Get-Item -LiteralPath (Join-Path $taskAndroid $_)
            if($artifact.PSIsContainer -or $artifact.LinkType){throw 'Invalid common-P2 app/runner artifact'}
            [pscustomobject]@{kind='host';path=[IO.Path]::GetRelativePath($taskSnapshot,$artifact.FullName);sha256=(Get-FileHash -LiteralPath $artifact.FullName).Hash.ToLowerInvariant();bytes=$artifact.Length}
        }
        if($P2DispatcherCandidate){
            $artifact=Get-Item -LiteralPath (Join-Path $taskAndroid 'p2-route-dispatcher/build/outputs/apk/debug/p2-route-dispatcher-debug.apk')
            if($artifact.LinkType -or $artifact.PSIsContainer){throw 'Ordinary common-P2 dispatcher APK required'}
            $taskP2Artifacts+=[pscustomobject]@{kind='sing-box-dispatcher';path=[IO.Path]::GetRelativePath($taskSnapshot,$artifact.FullName);sha256=(Get-FileHash -LiteralPath $artifact.FullName).Hash.ToLowerInvariant();bytes=$artifact.Length}
        }
        foreach($taskKind in @('steer','wg','csqtt','csqtt25')){
            if(($taskKind -ceq 'steer' -and !$P2SteerCandidate) -or ($taskKind -ceq 'wg' -and !$P2WgCandidate)){continue}
            $taskDir=if($taskKind -ceq 'wg'){'wg-module/build/outputs/apk/debug'}else{"engines/build/outputs/apk/$taskKind/debug"}
            $moduleApks=@(Get-ChildItem -LiteralPath (Join-Path $taskAndroid $taskDir) -File -Filter '*.apk')
            if(!$moduleApks.Count -or ($taskKind -ceq 'wg' -and $moduleApks.Count -ne 3)){throw 'Common-P2 debug module artifacts incomplete'}
            foreach($artifact in $moduleApks){
                if($artifact.LinkType){throw 'Linked P2 module artifact refused'}
                $taskP2Artifacts+=[pscustomobject]@{kind=$taskKind;path=[IO.Path]::GetRelativePath($taskSnapshot,$artifact.FullName);sha256=(Get-FileHash -LiteralPath $artifact.FullName).Hash.ToLowerInvariant();bytes=$artifact.Length}
            }
        }
        [IO.File]::WriteAllText((Join-Path $taskSnapshot 'p2-candidate-artifacts.json'),([ordered]@{
            priority=2;readinessSha256=$taskP2ReadySha;sourceManifestSha256=$taskManifestSha;jvmEvidenceSha256=$taskP2JvmEvidenceSha;artifacts=$taskP2Artifacts;
            steerOrigin=$taskP2SteerOrigin;
            installed=$false;runtimeVerified=$false;published=$false
        }|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
    }
    if($P1OwnerFixture){
        $taskP1Apk=Join-Path $taskAndroid 'p1-owner-probe/build/outputs/apk/debug/p1-owner-probe-debug.apk'
        if(!(Test-Path -LiteralPath $taskP1Apk -PathType Leaf) -or (Get-Item -LiteralPath $taskP1Apk).LinkType){throw 'Expected ordinary common-P1 helper APK missing'}
        $taskP1Artifacts=@('app/build/outputs/apk/debug/app-universal-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk','p1-owner-probe/build/outputs/apk/debug/p1-owner-probe-debug.apk','p1-owner-peer/build/outputs/apk/debug/p1-owner-peer-debug.apk')|ForEach-Object {
            $taskArtifact=Get-Item -LiteralPath (Join-Path $taskAndroid $_)
            if($taskArtifact.PSIsContainer -or $taskArtifact.LinkType){throw 'Invalid common-P1 artifact'}
            [pscustomobject]@{path=[IO.Path]::GetRelativePath($taskSnapshot,$taskArtifact.FullName);sha256=(Get-FileHash -LiteralPath $taskArtifact.FullName -Algorithm SHA256).Hash.ToLowerInvariant();bytes=$taskArtifact.Length}
        }
        $taskP1Candidates=@()
        foreach($taskCandidateKind in @('steer','wg')){
            if(($taskCandidateKind -ceq 'steer' -and !$P1SteerCandidate) -or ($taskCandidateKind -ceq 'wg' -and !$P1WgCandidate)){continue}
            $taskCandidateOutput=if($taskCandidateKind -ceq 'steer'){'engines/build/outputs/apk/steer/debug'}else{'wg-module/build/outputs/apk/debug'}
            $taskCandidateApks=@(Get-ChildItem -LiteralPath (Join-Path $taskAndroid $taskCandidateOutput) -File -Filter '*.apk')
            if(!$taskCandidateApks.Count){throw 'Expected external common-P1 candidate output missing'}
            foreach($taskCandidateApk in $taskCandidateApks){
                if($taskCandidateApk.LinkType){throw 'Linked candidate artifact refused'}
                $taskP1Candidates+=[pscustomobject]@{kind=$taskCandidateKind;path=[IO.Path]::GetRelativePath($taskSnapshot,$taskCandidateApk.FullName);sha256=(Get-FileHash -LiteralPath $taskCandidateApk.FullName -Algorithm SHA256).Hash.ToLowerInvariant();bytes=$taskCandidateApk.Length}
            }
        }
        [IO.File]::WriteAllText((Join-Path $taskSnapshot 'p1-owner-artifacts.json'),([ordered]@{priority=1;readinessSha256=$taskP1ReadySha;sourceManifestSha256=$taskManifestSha;artifacts=$taskP1Artifacts;externalCandidates=$taskP1Candidates;installed=$false;runtimeVerified=$false}|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
    }
    if($BothTestRunners){[IO.File]::WriteAllText((Join-Path $taskSnapshot 'test-runner-artifacts.json'),($taskRunnerArtifacts|ConvertTo-Json -Depth 3),(New-Object Text.UTF8Encoding($false)))}
    if($ExportTestRunner){
        $taskRunner=Join-Path $taskAndroid 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
        $taskRunnerExport=Join-Path $taskWorkspace ('work/android-runner-artifacts-'+[guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $taskRunnerExport|Out-Null
        $taskRunnerTarget=Join-Path $taskRunnerExport 'runner.apk'
        [IO.File]::Copy($taskRunner,$taskRunnerTarget,$false)
        $taskRunnerHash=(Get-FileHash -LiteralPath $taskRunner).Hash.ToLowerInvariant()
        if((Get-FileHash -LiteralPath $taskRunnerTarget).Hash.ToLowerInvariant() -cne $taskRunnerHash){throw 'Runner export changed'}
        foreach($taskReceipt in @('source-manifest.json','build-result.json')){[IO.File]::Copy((Join-Path $taskSnapshot $taskReceipt),(Join-Path $taskRunnerExport $taskReceipt),$false)}
        [IO.File]::WriteAllText((Join-Path $taskRunnerExport 'artifact-receipt.json'),([ordered]@{snapshot=$taskSnapshot;path='runner.apk';sha256=$taskRunnerHash;bytes=(Get-Item $taskRunner).Length;releaseSmoke=[bool]$ReleaseSmoke;appApkBuilt=(':app:assembleRelease' -in $Task -or ':app:assembleDebug' -in $Task);runtimeVerified=$false}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
        Write-Output "TEST_RUNNER_ARTIFACTS=$taskRunnerExport"
    }
    if($PreparePrimaryStage){
        # Stage verified module artifacts before the bounded retention removes this
        # module-only snapshot. No old generated tree is a permanent dependency.
        & (Join-Path $PSScriptRoot 'prepare-primary-stage.ps1') -AdapterSnapshot ([IO.Path]::GetRelativePath($taskWorkspace,$taskSnapshot)) -SteerStage $PrimaryStageSteer -SingboxVersionCode $PrimaryStageVersionCode
    }
    if($ExportWgBackend){
        $taskWgOutput=Join-Path $taskAndroid 'wg-module/build/outputs/apk/release'
        $taskWgApks=@(Get-ChildItem -LiteralPath $taskWgOutput -File -Filter '*.apk')
        if($taskWgApks.Count -ne 3){throw 'Expected exactly3 WG backend ABI candidates'}
        $taskWgExport=Join-Path $taskWorkspace ('work/android-wg-artifacts-'+[guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $taskWgExport|Out-Null
        $taskWgRows=foreach($taskApk in $taskWgApks){
            if($taskApk.Name -notmatch '^wg-module-(arm64-v8a|armeabi-v7a|x86_64)-release\.apk$'){throw 'Unexpected WG artifact name'}
            $taskTo=Join-Path $taskWgExport $taskApk.Name
            [IO.File]::Copy($taskApk.FullName,$taskTo,$false)
            $taskHash=(Get-FileHash -LiteralPath $taskApk.FullName).Hash.ToLowerInvariant()
            if((Get-FileHash -LiteralPath $taskTo).Hash.ToLowerInvariant() -cne $taskHash){throw 'WG export hash mismatch'}
            [pscustomobject]@{path=$taskApk.Name;bytes=$taskApk.Length;sha256=$taskHash}
        }
        foreach($taskReceipt in @('source-manifest.json','build-result.json')){[IO.File]::Copy((Join-Path $taskSnapshot $taskReceipt),(Join-Path $taskWgExport $taskReceipt),$false)}
        [IO.File]::WriteAllText((Join-Path $taskWgExport 'artifact-receipt.json'),([pscustomobject]@{files=$taskWgRows;runtimeVerified=$false;published=$false}|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
        Write-Output "WG_BACKEND_APKS=$taskWgExport"
    }
    if($ExportEngineApks){
        # Keep the tiny deliverable set, not an otherwise unnecessary app build.
        $taskEngineOutputs=Join-Path $taskAndroid 'engines/build/outputs/apk'
        $taskEngineApks=@(Get-ChildItem -LiteralPath $taskEngineOutputs -Recurse -File -Filter '*.apk')
        if(!$taskEngineApks.Count){throw 'No engine APK artifacts to export'}
        $taskExport=Join-Path $taskWorkspace ('work/android-module-artifacts-'+[guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $taskExport|Out-Null
        $taskExportRows=@()
        foreach($taskApk in $taskEngineApks){
            $taskArtifactRelative=[IO.Path]::GetRelativePath($taskEngineOutputs,$taskApk.FullName) -replace '\\','/'
            if($taskArtifactRelative -notmatch '^(singbox|steer|tgws|openflux|s3bypass|csqtt|csqtt25|qwdtt|wdtt|olcrtc|dnstt|warpscout|xray|tlscore)/(release|debug)/[A-Za-z0-9._-]+\.apk$'){throw 'Unexpected engine artifact path'}
            $taskTo=Join-Path $taskExport $taskArtifactRelative
            New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($taskTo)) -Force|Out-Null
            [IO.File]::Copy($taskApk.FullName,$taskTo,$false)
            $taskHash=(Get-FileHash -LiteralPath $taskApk.FullName).Hash.ToLowerInvariant()
            if((Get-FileHash -LiteralPath $taskTo).Hash.ToLowerInvariant() -cne $taskHash){throw 'Engine artifact export changed'}
            $taskExportRows+=[pscustomobject]@{path=$taskArtifactRelative;bytes=$taskApk.Length;sha256=$taskHash}
        }
        foreach($taskReceipt in @('source-manifest.json','build-result.json')){[IO.File]::Copy((Join-Path $taskSnapshot $taskReceipt),(Join-Path $taskExport $taskReceipt),$false)}
        [IO.File]::WriteAllText((Join-Path $taskExport 'artifact-receipt.json'),([pscustomobject]@{files=$taskExportRows;snapshot=$taskSnapshot;primaryStage=$PrimaryCoreStage;runtimeVerified=$false}|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
        Write-Output "ENGINE_APK_ARTIFACTS=$taskExport"
    }
}finally{
    Pop-Location;$env:JAVA_TOOL_OPTIONS=$taskPreviousOptions
    if(Test-Path -LiteralPath (Join-Path $taskSnapshot 'build-result.json')){
        if(Test-Path -LiteralPath $taskRetention){
            $taskPolicy=Get-Content -LiteralPath $taskRetention -Raw|ConvertFrom-Json
            $taskPolicy.activeSnapshots=@($taskPolicy.activeSnapshots|Where-Object {$_ -ne [IO.Path]::GetFileName($taskSnapshot)})
            [IO.File]::WriteAllText($taskRetention,($taskPolicy|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
        }
        if(!$taskP2Candidate -and !$P2Expansion){& (Join-Path $PSScriptRoot 'cleanup-builds.ps1') -Apply}
    }
}
