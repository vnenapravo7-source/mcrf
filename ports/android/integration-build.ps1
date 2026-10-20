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
    [switch]$TestRelease,
    [switch]$ReleaseSmoke,
    [switch]$BothTestRunners,
    [switch]$ExportTestRunner,
    [switch]$FirstLaunchFixture,
    [switch]$P1OwnerFixture,
    [switch]$P1WgCandidate,
    [switch]$P1SteerCandidate
)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'maintenance/Assert-HumanBatchReady.ps1') -Operation build
if($args.Count){throw 'Unknown integration build arguments; do not silently ignore fixture or safety options'}
$taskP1BatchRelative=$null
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
    & (Join-Path $PSScriptRoot 'cleanup-builds.ps1') -Apply
}
$taskOptionalRelative=$null
$taskOptionalFixtureRelative=$null
$taskPrimaryRelative=$null
$taskWarpRelative=$null
$taskWgRelative=$null
if($WgBackendStage){
    $taskWgRoot=[IO.Path]::GetFullPath((Join-Path $taskWorkspace $WgBackendStage))
    $taskWgAllowed=Join-Path $taskWorkspace 'ports/android/transports/wg-backend/build'
    if(!$taskWgRoot.StartsWith($taskWgAllowed+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'WG candidate outside dedicated Android build directory'}
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
$taskP1ExternalPrimary=$false
$taskP1OriginalReadyRelative=$null
if(@($OpenFluxPromotionStage,$OpenFluxAckStage,$OpenFluxProviderStage,$OpenFluxProviderV2Stage,$OpenFluxProviderV3Stage|Where-Object {$_}).Count -gt 1){throw 'OpenFlux stages cannot be combined'}
foreach($stageName in @('PrimaryCoreStage','OpenFluxPromotionStage','OpenFluxAckStage','OpenFluxProviderStage','OpenFluxProviderV2Stage','OpenFluxProviderV3Stage')) {
    $stageValue=Get-Variable -Name $stageName -ValueOnly
    if(!$stageValue){continue}
    $stageRoot=[IO.Path]::GetFullPath((Join-Path $taskWorkspace $stageValue))
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
if($Csqtt25Stage){
    $taskCsqtt25Root=[IO.Path]::GetFullPath($Csqtt25Stage)
    $taskCsqtt25Allowed=[IO.Path]::GetFullPath('D:/MCRF/Builds/Android')
    if(!$taskCsqtt25Root.StartsWith($taskCsqtt25Allowed+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
       [IO.Path]::GetFileName($taskCsqtt25Root) -notmatch '^csqtt25-captcha-[0-9a-f]{32}$' -or
       (Get-Item -LiteralPath $taskCsqtt25Root).LinkType){throw 'Use a completed isolated CSQTT2.5 stage, never a live source directory'}
    $taskCsqtt25Receipt=Get-Content -LiteralPath (Join-Path $taskCsqtt25Root 'receipt.json') -Raw | ConvertFrom-Json
    if($taskCsqtt25Receipt.state -ne 'COMPILED_NOT_EXECUTED' -or $taskCsqtt25Receipt.kind -ne 'csqtt25' -or !$taskCsqtt25Receipt.sourceStable){throw 'CSQTT2.5 native producer has not completed'}
    $taskCsqtt25Relative='ports/android/native-stage-csqtt25'
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
    $taskStageFiles=@('receipt.json','source-manifest.json','licenses/CSQTT-LICENSE','licenses/qwdtt-LICENSE')
    foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){$taskStageFiles+=@("$taskAbi/libqwdtt.so","$taskAbi/libcsqtt_transport.so")}
    foreach($taskFile in $taskStageFiles){
        $taskInput=Get-Item -LiteralPath (Join-Path $taskCsqttLegacyRoot $taskFile)
        if($taskInput.PSIsContainer -or $taskInput.LinkType){throw 'Legacy stage input must be a regular immutable file'}
        $taskExternalInputs["$taskCsqttLegacyRelative/$taskFile"]=$taskInput.FullName
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
if($P1OwnerFixture){$taskInputs+=@('ports/android/p1-owner-probe/build.gradle.kts','ports/android/coordination/current.json',$taskP1BatchRelative,'ports/android/integration-build.ps1','ports/android/integration-device-checks.ps1','ports/android/integration-p1-steer.ps1','ports/android/maintenance/Assert-HumanBatchReady.ps1','work/steer-android-port/android-vpn/tests/p1_owned_peer.py','work/steer-android-port/android-vpn/tests/test_p1_owned_peer.py')}
if($P1OwnerFixture){$taskInputs+='ports/android/p1-owner-peer/build.gradle.kts'}
if($P1OwnerFixture){$taskInputs+=@('ports/android/integration-packet-phone.ps1','ports/android/WireGuardFixtures/go.mod','ports/android/WireGuardFixtures/go.sum','ports/android/WireGuardFixtures/Build-PacketAuditPeer.ps1')}
$taskInputs+='ports/android/engines/EngineUpdateCsqtt25Manifest.xml'
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
        @($files | ForEach-Object {$_ -replace '\\','/'} | Sort-Object -Unique)
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
if($taskCsqtt25Relative){$taskExtra+="-Pcsqtt25Stage=$(Join-Path $taskSnapshot $taskCsqtt25Relative)";$taskExtra+='-Pcsqtt25CandidateGate=true'}
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
    if($taskExit -ne 0){throw "Integration build failed; small evidence is preserved in coordination/build-history/$([IO.Path]::GetFileName($taskSnapshot)); the failed generated snapshot is removed by retention"}
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
        & (Join-Path $PSScriptRoot 'cleanup-builds.ps1') -Apply
    }
}
