param([Parameter(Mandatory)][string]$RetainedPrimaryStage,
 [Parameter(Mandatory)][ValidatePattern('^emulator-[0-9]+$')][string]$EmulatorSerial,
 [string]$RetainedP3JvmSnapshot='',
 [switch]$HistoricalRecoveryRecipe,
 [string]$RetainedSocksStage='D:/MCRF/Builds/Android/p3-steer-native-a6413bb4c75042f0ae414ee384e4483c')
$ErrorActionPreference='Stop'
$taskAndroid='D:/MCRF/Projects/MCRF/ports/android'
$taskBase='D:/MCRF/Builds/Android/next-independent-20d0f883b4cf419c8d155969a0f54408/source/ports/android'
$taskSteps=[Collections.Generic.List[object]]::new()
function Stage([string]$Id,[string]$Suffix=''){@{stage=$Id;suffix=$Suffix}}
function Add-Step([string]$Id,[string]$Script,[string]$Operation,[string[]]$Families,[hashtable]$Parameters,[string]$OutputKey,[string[]]$States,$InPlace=$null){
 $deps=[Collections.Generic.HashSet[string]]::new()
 function Collect($Value){
  if($Value -is [Collections.IDictionary]){
   if($Value.Contains('stage')){[void]$deps.Add($Value.stage)}else{foreach($v in $Value.Values){Collect $v}}
  }elseif($Value -is [array]){foreach($v in $Value){Collect $v}}
 }
 Collect $Parameters;if($InPlace){Collect $InPlace}
 $taskSteps.Add(@{id=$Id;script=$Script;operation=$Operation;families=$Families;parameters=$Parameters;dependsOn=@($deps|Sort-Object);outputKey=$OutputKey;receiptSuffix='/receipt.json';receiptStates=$States;outputStage=$InPlace;replayAccepted=$false})
}
# Original-source paths are exact pinned checkouts; each producer independently
# verifies its commit, copied-source closure and original licence provenance.
$upstreams=@{
 masterdnsvpn='D:/MCRF/Builds/Android/p4-masterdnsvpn-upstream-706600d4dff84e39b6f2db26312f92e9'
 sstp='D:/MCRF/Builds/Android/p4-sstp-upstream-6e1468e8703d45288eb8447b9da3965b'
 olcrtc='D:/MCRF/Builds/Android/p3-olcrtc-upstream-200aaff0533b44309221a3752e6510ae'
}
$hostStates=@{vaydns='PASS_P3_VAYDNS_HOST';dnstt='PASS_P3_DNSTT_HOST';olcrtc='PASS_P3_OLCRTC_HOST';masterdnsvpn='PASS_P4_MASTERDNS_HOST';sstp='PASS_P4_SSTP_HOST'}
$prefixes=@{vaydns='P3_VAYDNS';dnstt='P3_DNSTT';olcrtc='P3_OLCRTC';masterdnsvpn='P4_MASTERDNS';sstp='P4_SSTP'}
foreach($kind in @('vaydns','dnstt','olcrtc','masterdnsvpn','sstp')){
 $argsMap=@{};if($upstreams.ContainsKey($kind)){$argsMap.Upstream=$upstreams[$kind]}
 if($kind -ceq 'olcrtc'){$argsMap.AdapterUpstream=$upstreams.sstp}
 Add-Step "$kind-source" "transports/$kind/Prepare-Source.ps1" source @($kind) $argsMap "$($prefixes[$kind])_SOURCE" @('SOURCE_PREPARED_NOT_COMPILED')
 Add-Step "$kind-host" "transports/$kind/Invoke-HostChecks.ps1" host @($kind) @{PreparedSource=(Stage "$kind-source")} "$($prefixes[$kind])_HOST" @($hostStates[$kind])
 Add-Step "$kind-native" "transports/$kind/Build-Native.ps1" native @($kind) @{PreparedSource=(Stage "$kind-source");HostEvidence=(Stage "$kind-host" '/receipt.json')} "$($prefixes[$kind])_NATIVE" @('COMPILED_NOT_EXECUTED')
}
foreach($kind in @('csqtt','qwdtt','wdtt')){
 if($kind -ceq 'wdtt'){
  Add-Step "$kind-source" 'maintenance/Invoke-P3WdttPerformance.ps1' source @($kind) @{Operation='Source'} 'P3_TURN_SOURCE' @('P3_TURN_SOURCE_PREPARED_NOT_COMPILED')
 }else{Add-Step "$kind-source" 'transports/swg/performance/Prepare-TurnSource.ps1' source @($kind) @{Kind=$kind} 'P3_TURN_SOURCE' @('P3_TURN_SOURCE_PREPARED_NOT_COMPILED')}
 Add-Step "$kind-host" 'transports/swg/performance/Invoke-HostChecks.ps1' host @($kind) @{PreparedSource=(Stage "$kind-source")} 'P3_TURN_HOST' @('PASS_P3_TURN_PERFORMANCE_HOST')
 if($kind -ceq 'wdtt'){
  Add-Step "$kind-native" 'maintenance/Invoke-P3WdttPerformance.ps1' native @($kind) @{Operation='Native';PreparedSource=(Stage "$kind-source");HostEvidence=(Stage "$kind-host" '/receipt.json')} 'P3_TURN_NATIVE' @('COMPILED_NOT_EXECUTED')
 }else{Add-Step "$kind-native" 'transports/swg/performance/Build-TurnNative.ps1' native @($kind) @{PreparedSource=(Stage "$kind-source");HostEvidence=(Stage "$kind-host" '/receipt.json')} 'P3_TURN_NATIVE' @('COMPILED_NOT_EXECUTED')}
}
foreach($kind in @('csqtt20','csqtt215')){
 $historicalNative=if($kind -ceq 'csqtt20'){'D:/MCRF/Builds/Android/csqtt-historical-9037b6e108d84e17bd68e228b3d3f2dd'}else{'D:/MCRF/Builds/Android/csqtt-historical-533a6b5342d0408bacd44d6497f6c23c'}
 Add-Step "$kind-package" 'transports/swg/Prepare-CsqttHistoricalPackage.ps1' native @($kind) @{NativeStage=$historicalNative;WrapperStage=(Stage 'csqtt-native')} 'HISTORICAL_PACKAGE_STAGE' @('HISTORICAL_ENGINE_INPUTS_READY')
}
Add-Step 'csqtt25-rust-source' 'transports/swg/csqtt25/Prepare-NegotiatedSource.ps1' source @('csqtt25') @{} 'CSQTT25_NEGOTIATED_SOURCE' @('SOURCE_PREPARED')
Add-Step 'csqtt25-rust-native' 'transports/swg/csqtt25/Build-NegotiatedNative.ps1' native @('csqtt25') @{Stage=(Stage 'csqtt25-rust-source')} 'CSQTT25_NEGOTIATED_NATIVE' @('COMPILED_NOT_EXECUTED')
Add-Step 'csqtt25-source' 'transports/swg/csqtt25/Prepare-P3AbiSource.ps1' source @('csqtt25') @{NegotiatedTransportStage=(Stage 'csqtt25-rust-native')} 'P3_CS25_SOURCE' @('P3_ABI_SOURCE_PREPARED_NOT_COMPILED')
Add-Step 'csqtt25-host' 'transports/swg/csqtt25/Invoke-P3HostChecks.ps1' host @('csqtt25') @{PreparedSource=(Stage 'csqtt25-source')} 'P3_CS25_HOST' @('PASS_P3_CS25_ABI_HOST')
Add-Step 'csqtt25-native' 'transports/swg/csqtt25/Build-P3AbiNative.ps1' native @('csqtt25') @{PreparedSource=(Stage 'csqtt25-source');HostEvidence=(Stage 'csqtt25-host' '/receipt.json')} 'P3_CS25_NATIVE' @('COMPILED_NOT_EXECUTED')
Add-Step 'steer-codecs' 'maintenance/Prepare-P3SteerCodecInputs.ps1' source @('steer') @{} 'P3_STEER_CODEC_INPUTS' @('PINNED_CODEC_INPUTS_NOT_COMPILED')
Add-Step 'steer-source' 'primary-cores/steer/Prepare-Source.ps1' source @('steer') @{RetainedPrimaryStage=$RetainedPrimaryStage;CodecArchives=(Stage 'steer-codecs')} 'P3_STEER_SOURCE' @('SOURCE_PREPARED_NOT_COMPILED')
Add-Step 'steer-private-source' 'primary-cores/steer/Prepare-PrivateHelperSource.ps1' source @('steer') @{PreparedSource=(Stage 'steer-source')} 'P3_STEER_PRIVATE_SOURCE' @('SOURCE_PREPARED_NOT_COMPILED')
Add-Step 'steer-native' 'primary-cores/steer/Build-Native.ps1' native @('steer') @{PreparedSource=(Stage 'steer-private-source');RetainedSocksStage=$RetainedSocksStage} 'P3_STEER_COMPILED_NOT_ADMITTED' @('COMPILED_SOCKS_FIXTURE_PENDING')
Add-Step 'steer-fixture' 'primary-cores/steer/Invoke-OwnedFixture.ps1' fixture @('steer') @{NativeStage=(Stage 'steer-native');EmulatorSerial=$EmulatorSerial} '' @('COMPILED_SOCKS_FIXTURE_VERIFIED') (Stage 'steer-native')
Add-Step 'steer-package' 'primary-cores/steer/Complete-Package.ps1' legal @('steer') @{NativeStage=(Stage 'steer-native')} '' @('COMPILED_SOCKS_FIXTURE_PACKAGED_NOT_PHONE_VERIFIED') (Stage 'steer-native')
Add-Step 'protocols-source' 'transports/protocols/Prepare-Source.ps1' source @('protocols') @{Upstream='D:/MCRF/Projects/MCRF/work/android-upstream/sing-box-lx-mcrf-clash-20261014'} 'P3_PROTOCOLS_SOURCE' @('SOURCE_PREPARED_NOT_COMPILED')
Add-Step 'protocols-source-go126' 'transports/protocols/Advance-PreparedSource.ps1' source @('protocols') @{PreparedSource=(Stage 'protocols-source')} 'P3_PROTOCOLS_SOURCE' @('SOURCE_PREPARED_NOT_COMPILED')
Add-Step 'protocols-notices' 'transports/protocols/Prepare-CronetNotices.ps1' source @('protocols') @{Upstream='D:/MCRF/Builds/Android/p3-cronet-notices-upstream-6b0998567c6e4e5eb21007df24826365'} 'P3_CRONET_ORIGINAL_NOTICE_SUPERSET' @('SOURCE_NOTICE_SUPERSET_PREPARED')
Add-Step 'protocols-native' 'transports/protocols/Build-Native.ps1' native @('protocols') @{PreparedSource=(Stage 'protocols-source-go126')} 'P3_PROTOCOLS_COMPILED_NOT_ADMITTED' @('COMPILED_LEGAL_AND_FIXTURE_PENDING')
Add-Step 'protocols-legal' 'transports/protocols/Complete-NativeNotices.ps1' legal @('protocols') @{NativeStage=(Stage 'protocols-native');CronetNotices=(Stage 'protocols-notices')} '' @('COMPILED_FIXTURE_PENDING') (Stage 'protocols-native')
if($HistoricalRecoveryRecipe){
 Add-Step 'protocols-fixture-app' 'maintenance/Build-P3ProtocolFixtureApp.ps1' app @('protocols') @{NativeStage=(Stage 'protocols-native')} 'P3_PROTOCOLS_FIXTURE_APP' @('COMPILED_FIXTURE_APK_NOT_EXECUTED')
 Add-Step 'protocols-fixture-app-netdiag' 'maintenance/Build-P3ProtocolDiagnosticApp.ps1' app @('protocols') @{NativeStage=(Stage 'protocols-native')} 'P3_PROTOCOLS_FIXTURE_APP' @('COMPILED_FIXTURE_APK_NOT_EXECUTED')
}
Add-Step 'protocols-fixture-app-active' 'maintenance/Build-P3ProtocolDiagnosticApp.ps1' app @('protocols') @{NativeStage=(Stage 'protocols-native')} 'P3_PROTOCOLS_FIXTURE_APP' @('COMPILED_FIXTURE_APK_NOT_EXECUTED')
Add-Step 'protocols-fixture' 'transports/protocols/Invoke-OwnedFixture.ps1' fixture @('protocols') @{NativeStage=(Stage 'protocols-native');FixtureApk=(Stage 'protocols-fixture-app-active' '/source/protocols-owned-fixture/build/outputs/apk/debug/protocols-owned-fixture-debug.apk');EmulatorSerial=$EmulatorSerial} '' @('COMPILED_OWNED_FIXTURE_VERIFIED') (Stage 'protocols-native')
$slipRust='D:/MCRF/Builds/Android/p3-slipstream-rust-upstream-026156bcc28c4947825c172672a911c4'
Add-Step 'slipstream-source' 'transports/slipstream/Prepare-Source.ps1' source @('slipstream') @{Upstream=$slipRust} 'P3_SLIPSTREAM_SOURCE' @('SOURCE_PREPARED_NOT_COMPILED')
Add-Step 'slipstream-inputs' 'transports/slipstream/Prepare-BuildInputs.ps1' source @('slipstream') @{PicoquicUpstream='D:/MCRF/Builds/Android/p3-slipstream-rust-picoquic-upstream-8bf707fb721743bcae988459f4558c4d'} 'P3_SLIPSTREAM_INPUTS' @('INPUTS_PREPARED_NOT_COMPILED')
Add-Step 'slipstream-c-source' 'transports/slipstream/Prepare-CFixtureInputs.ps1' source @('slipstream') @{OriginalUpstream='D:/MCRF/Builds/Android/p3-slipstream-upstream-a45f19af07ae48b5a8995816a7ebcce7'} 'P3_SLIPSTREAM_CFIXTURE_SOURCE' @('C_FIXTURE_SOURCE_PREPARED')
Add-Step 'slipstream-perl-inputs' 'transports/slipstream/Prepare-PerlInput.ps1' source @('slipstream') @{} 'P3_SLIPSTREAM_PERL_INPUTS' @('PERL_SOURCE_PREPARED_NOT_EXECUTED')
Add-Step 'slipstream-perl-makemaker-inputs' 'transports/slipstream/Prepare-PerlMakeMakerInput.ps1' source @('slipstream') @{} 'P3_SLIPSTREAM_PERL_INPUTS' @('PERL_SOURCE_PREPARED_NOT_EXECUTED')
Add-Step 'slipstream-perl-pod-inputs' 'transports/slipstream/Prepare-PerlPodInputs.ps1' source @('slipstream') @{} 'P3_SLIPSTREAM_PERL_POD_INPUTS' @('PERL_POD_SOURCE_PREPARED_NOT_EXECUTED')
Add-Step 'slipstream-pkgconf-inputs' 'transports/slipstream/Prepare-PkgconfInput.ps1' source @('slipstream') @{} 'P3_SLIPSTREAM_PKGCONF_INPUTS' @('PKGCONF_INPUT_PREPARED_NOT_EXECUTED')
Add-Step 'slipstream-cdeps' 'transports/slipstream/Build-CDependencies.ps1' native @('slipstream') @{Inputs=(Stage 'slipstream-inputs');PerlInputs=(Stage 'slipstream-perl-inputs');PerlMakeMakerInputs=(Stage 'slipstream-perl-makemaker-inputs');PerlPodInputs=(Stage 'slipstream-perl-pod-inputs');PkgconfInputs=(Stage 'slipstream-pkgconf-inputs')} 'P3_SLIPSTREAM_CDEPS' @('CDEPS_COMPILED_NOT_EXECUTED')
if($HistoricalRecoveryRecipe){
Add-Step 'slipstream-native' 'transports/slipstream/Build-Native.ps1' native @('slipstream') @{PreparedSource=(Stage 'slipstream-source');Inputs=(Stage 'slipstream-inputs');CDependencies=(Stage 'slipstream-cdeps')} 'P3_SLIPSTREAM_NATIVE' @('COMPILED_LEGAL_AND_FIXTURE_PENDING')
Add-Step 'slipstream-cfixture' 'transports/slipstream/Build-CFixture.ps1' native @('slipstream') @{NativeStage=(Stage 'slipstream-native');CFixtureSource=(Stage 'slipstream-c-source');Inputs=(Stage 'slipstream-inputs');CDependencies=(Stage 'slipstream-cdeps')} '' @('COMPILED_LEGAL_AND_FIXTURE_PENDING') (Stage 'slipstream-native')
Add-Step 'slipstream-legal' 'transports/slipstream/Complete-NativeNotices.ps1' legal @('slipstream') @{NativeStage=(Stage 'slipstream-native');PreparedSource=(Stage 'slipstream-source');Inputs=(Stage 'slipstream-inputs')} '' @('COMPILED_FIXTURE_PENDING') (Stage 'slipstream-native')
Add-Step 'slipstream-legal-recovery' 'transports/slipstream/Recover-LegalInventory.ps1' legal @('slipstream') @{NativeStage=(Stage 'slipstream-native');PreparedSource=(Stage 'slipstream-source');Inputs=(Stage 'slipstream-inputs')} '' @('COMPILED_FIXTURE_PENDING') (Stage 'slipstream-native')
Add-Step 'slipstream-fixture-app' 'maintenance/Build-P3SlipstreamFixtureApp.ps1' app @('slipstream') @{NativeStage=(Stage 'slipstream-native')} 'P3_SLIPSTREAM_FIXTURE_APP' @('COMPILED_FIXTURE_APK_NOT_EXECUTED')
Add-Step 'slipstream-fixture-app-serverdiag' 'maintenance/Build-P3SlipstreamFixtureApp.ps1' app @('slipstream') @{NativeStage=(Stage 'slipstream-native')} 'P3_SLIPSTREAM_FIXTURE_APP' @('COMPILED_FIXTURE_APK_NOT_EXECUTED')
Add-Step 'slipstream-signature-source' 'transports/slipstream/Advance-SignatureSource.ps1' source @('slipstream') @{PreparedSource=(Stage 'slipstream-source')} 'P3_SLIPSTREAM_SOURCE' @('SOURCE_PREPARED_NOT_COMPILED')
Add-Step 'slipstream-signature-native' 'transports/slipstream/Build-Native.ps1' native @('slipstream') @{PreparedSource=(Stage 'slipstream-signature-source');Inputs=(Stage 'slipstream-inputs');CDependencies=(Stage 'slipstream-cdeps')} 'P3_SLIPSTREAM_NATIVE' @('COMPILED_LEGAL_AND_FIXTURE_PENDING')
Add-Step 'slipstream-signature-retain' 'transports/slipstream/Retain-IndependentEvidence.ps1' native @('slipstream') @{NativeStage=(Stage 'slipstream-signature-native');RetainedStage=(Stage 'slipstream-native');PreparedSource=(Stage 'slipstream-signature-source')} '' @('COMPILED_LEGAL_AND_FIXTURE_PENDING') (Stage 'slipstream-signature-native')
Add-Step 'slipstream-signature-legal' 'transports/slipstream/Complete-NativeNotices.ps1' legal @('slipstream') @{NativeStage=(Stage 'slipstream-signature-native');PreparedSource=(Stage 'slipstream-signature-source');Inputs=(Stage 'slipstream-inputs')} '' @('COMPILED_FIXTURE_PENDING') (Stage 'slipstream-signature-native')
Add-Step 'slipstream-signature-inventory' 'transports/slipstream/Recover-LegalInventory.ps1' legal @('slipstream') @{NativeStage=(Stage 'slipstream-signature-native');PreparedSource=(Stage 'slipstream-signature-source');Inputs=(Stage 'slipstream-inputs')} '' @('COMPILED_FIXTURE_PENDING') (Stage 'slipstream-signature-native')
Add-Step 'slipstream-signature-app' 'maintenance/Build-P3SlipstreamFixtureApp.ps1' app @('slipstream') @{NativeStage=(Stage 'slipstream-signature-native')} 'P3_SLIPSTREAM_FIXTURE_APP' @('COMPILED_FIXTURE_APK_NOT_EXECUTED')
Add-Step 'slipstream-android-fixture' 'transports/slipstream/Build-AndroidFixture.ps1' native @('slipstream') @{NativeStage=(Stage 'slipstream-signature-native');PreparedSource=(Stage 'slipstream-signature-source')} '' @('COMPILED_FIXTURE_PENDING') (Stage 'slipstream-signature-native')
Add-Step 'slipstream-android-app' 'maintenance/Build-P3SlipstreamFixtureApp.ps1' app @('slipstream') @{NativeStage=(Stage 'slipstream-signature-native')} 'P3_SLIPSTREAM_FIXTURE_APP' @('COMPILED_FIXTURE_APK_NOT_EXECUTED')
}else{
 # A fresh build has no failed fixture or seven retained passes to recover.
 # Apply the accepted EdDSA source correction BEFORE the sole native build.
 # Canonical Go fixture already includes the accepted Android PID fallback.
 Add-Step 'slipstream-signature-source' 'transports/slipstream/Advance-SignatureSource.ps1' source @('slipstream') @{PreparedSource=(Stage 'slipstream-source')} 'P3_SLIPSTREAM_SOURCE' @('SOURCE_PREPARED_NOT_COMPILED')
 Add-Step 'slipstream-signature-native' 'transports/slipstream/Build-Native.ps1' native @('slipstream') @{PreparedSource=(Stage 'slipstream-signature-source');Inputs=(Stage 'slipstream-inputs');CDependencies=(Stage 'slipstream-cdeps')} 'P3_SLIPSTREAM_NATIVE' @('COMPILED_LEGAL_AND_FIXTURE_PENDING')
 Add-Step 'slipstream-cfixture' 'transports/slipstream/Build-CFixture.ps1' native @('slipstream') @{NativeStage=(Stage 'slipstream-signature-native');CFixtureSource=(Stage 'slipstream-c-source');Inputs=(Stage 'slipstream-inputs');CDependencies=(Stage 'slipstream-cdeps')} '' @('COMPILED_LEGAL_AND_FIXTURE_PENDING') (Stage 'slipstream-signature-native')
 Add-Step 'slipstream-signature-legal' 'transports/slipstream/Complete-NativeNotices.ps1' legal @('slipstream') @{NativeStage=(Stage 'slipstream-signature-native');PreparedSource=(Stage 'slipstream-signature-source');Inputs=(Stage 'slipstream-inputs')} '' @('COMPILED_FIXTURE_PENDING') (Stage 'slipstream-signature-native')
 Add-Step 'slipstream-signature-inventory' 'transports/slipstream/Recover-LegalInventory.ps1' legal @('slipstream') @{NativeStage=(Stage 'slipstream-signature-native');PreparedSource=(Stage 'slipstream-signature-source');Inputs=(Stage 'slipstream-inputs')} '' @('COMPILED_FIXTURE_PENDING') (Stage 'slipstream-signature-native')
 Add-Step 'slipstream-android-app' 'maintenance/Build-P3SlipstreamFixtureApp.ps1' app @('slipstream') @{NativeStage=(Stage 'slipstream-signature-native')} 'P3_SLIPSTREAM_FIXTURE_APP' @('COMPILED_FIXTURE_APK_NOT_EXECUTED')
}
Add-Step 'slipstream-fixture' 'transports/slipstream/Invoke-OwnedFixture.ps1' fixture @('slipstream') @{NativeStage=(Stage 'slipstream-signature-native');FixtureApk=(Stage 'slipstream-android-app' '/source/slipstream-owned-fixture/build/outputs/apk/debug/slipstream-owned-fixture-debug.apk');Serial=$EmulatorSerial} '' @('COMPILED_OWNED_FIXTURE_VERIFIED') (Stage 'slipstream-signature-native')
Add-Step 'slipstream-proof-export' 'transports/slipstream/Export-FixtureProof.ps1' legal @('slipstream') @{NativeStage=(Stage 'slipstream-signature-native')} '' @('COMPILED_OWNED_FIXTURE_VERIFIED') (Stage 'slipstream-signature-native')
Add-Step 'steer-notice-files' 'primary-cores/steer/Complete-NoticeFiles.ps1' legal @('steer') @{NativeStage=(Stage 'steer-native');RetainedPrimaryStage=$RetainedPrimaryStage} '' @('COMPILED_SOCKS_FIXTURE_PACKAGED_NOT_PHONE_VERIFIED') (Stage 'steer-native')
$selectors=@('NextSteerProjectionTest','SteerHelperPreparationTest','P3TransitionFamilyParityTest','CompactProtocolPolicyTest','CompactBootstrapContextTest','CompactProtocolFailureTest','CompactProtocolLogPrivacyTest','AssistedSteerProbeBindingTest','ProbeBackendScopeTest','PrimaryRuntimeNetworkScopeTest','PrimaryFallbackFailureGuardTest','VayDnsProfileTest','SlipstreamProfileTest','MasterDnsProfileTest','SstpProfileTest','DnsttPrivateConfigTest','OlcPrivateConfigTest','EngineDiscoveryFingerprintCacheTest','CsqttGenerationChallengeBudgetTest',
 'NativeRouteDispatchPlanTest.compactRouteProtocolsRequireOwnedHelpersInsteadOfPretendSteerUris','NativeRouteDispatchPlanTest.compactMainAndRouteCaptureKeepIndependentOriginalsAndRejectPostCaptureMutation')
$selectors+=@('PrimaryValidationDiagnosticTest')
if($RetainedP3JvmSnapshot){
 Add-Step 'retained-p3-jvm' 'maintenance/Read-P3RetainedJvm.ps1' host @('app') @{ExistingSnapshot=$RetainedP3JvmSnapshot} 'P3_JVM_RETAINED' @('RETAINED_57_NEW_JVM_CASES')
 $selectors=@('NativeRouteDispatchPlanTest.compactRouteProtocolsRequireOwnedHelpersInsteadOfPretendSteerUris')
}
$tasks=@(':app:testDebugUnitTest');foreach($selector in $selectors){$tasks+=@('--tests',"org.mcrf.client.$selector")}
$tasks+=@(':app:assembleRelease',':app:assembleDebugAndroidTest')
foreach($kind in @('Csqtt','Csqtt20','Csqtt215','Csqtt25','Qwdtt','Wdtt','Steer','Protocols','Vaydns','Slipstream','Sstp','Masterdnsvpn','Dnstt','Olcrtc')){$tasks+=@(':engines:assemble'+$kind+'Release')}
$parameters=@{Task=$tasks;TestRelease=$true;ExportTestRunner=$true;ExportEngineApks=$true;BuildScratchRoot='D:/MCRF/Builds/Android';P3SteerSocksCandidate=$true;
 PrimaryCoreStage=(Stage 'steer-package' '/primary-stage');CsqttLegacyStage=(Stage 'csqtt-native');Csqtt25Stage=(Stage 'csqtt25-native');
 SwgNativeStages=@{map=@{qwdtt=(Stage 'qwdtt-native');wdtt=(Stage 'wdtt-native')}};
 CsqttHistoricalStages=@{map=@{csqtt20=(Stage 'csqtt20-package');csqtt215=(Stage 'csqtt215-package')}};
 VayDnsStage=(Stage 'vaydns-native');SlipstreamStage=(Stage 'slipstream-signature-native');SstpStage=(Stage 'sstp-native');MasterDnsStage=(Stage 'masterdnsvpn-native');DnsttStage=(Stage 'dnstt-native');OlcrtcStage=(Stage 'olcrtc-native');ProtocolsStage=(Stage 'protocols-native')}
Add-Step 'app-and-modules' 'integration-build.ps1' app @('app') $parameters 'INTEGRATION_SNAPSHOT' @('EXIT_ZERO_SOURCE_STABLE')
$taskSteps[$taskSteps.Count-1].receiptSuffix='/build-result.json'
$taskStage="D:/MCRF/Builds/Android/p3-p4-definition-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $taskStage|Out-Null
[IO.File]::WriteAllText("$taskStage/DEFINITION.json",(@{schema=1;recipe=$(if($HistoricalRecoveryRecipe){'historical-recovery-only'}else{'fresh-corrected-source'});steps=@($taskSteps.ToArray())}|ConvertTo-Json -Depth 30),[Text.UTF8Encoding]::new($false))
Write-Output "P3_P4_COMMON_DEFINITION=$taskStage"
