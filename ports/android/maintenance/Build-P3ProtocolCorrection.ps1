param([string]$PrivateSteerStage,[switch]$DispatcherDnsCorrection,[switch]$AuditCorrection,[switch]$BackgroundReview,[switch]$DiagnosticReview,[switch]$FinalReview,[switch]$UserFixes,[string]$FreshOpenFluxStage='')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation build
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
$taskPrior='D:/MCRF/Projects/MCRF/work/android-integration-cd0164bce121482fb0e2835caa58f42b'
$taskPriorProof=Get-Content "$taskPrior/build-result.json" -Raw|ConvertFrom-Json
if($taskPriorProof.exitCode -ne 0 -or !$taskPriorProof.sourceStable -or $taskPriorProof.sourceManifestSha256 -cne (Hash "$taskPrior/source-manifest.json")){throw 'Exact stable green common package required'}
$taskCommon=Get-Content 'D:/MCRF/Builds/Android/p3-p4-common-df8fdf8231764f9f99ad4dc41604ff1b/receipt.json' -Raw|ConvertFrom-Json
function Stage([string]$Name){$value=$taskCommon.steps.$Name.output;if(!$value){throw "Retained common stage missing: $Name"};return $value}
$taskParameters=@{
 Task=@(':app:testDebugUnitTest','--tests','org.mcrf.client.SteerHelperPreparationTest',':app:assembleRelease')
 TestRelease=$true;BuildScratchRoot='D:/MCRF/Builds/Android';P3SteerSocksCandidate=$true
 PrimaryCoreStage="$(Stage 'steer-package')/primary-stage";CsqttLegacyStage=(Stage 'csqtt-native');Csqtt25Stage=(Stage 'csqtt25-native')
 SwgNativeStages=@{qwdtt=(Stage 'qwdtt-native');wdtt=(Stage 'wdtt-native')}
 CsqttHistoricalStages=@{csqtt20=(Stage 'csqtt20-package');csqtt215=(Stage 'csqtt215-package')}
 VayDnsStage=(Stage 'vaydns-native');SlipstreamStage=(Stage 'slipstream-signature-native');SstpStage=(Stage 'sstp-native');MasterDnsStage=(Stage 'masterdnsvpn-native');DnsttStage=(Stage 'dnstt-native');OlcrtcStage=(Stage 'olcrtc-native');ProtocolsStage=(Stage 'protocols-native')
}
if($PrivateSteerStage){
 $taskStage=(Resolve-Path $PrivateSteerStage).Path.Replace([char]92,[char]47)
 if($taskStage -cnotmatch '^D:/MCRF/Builds/Android/p3-steer-native-[a-f0-9]{32}$'){throw 'Exact private helper native stage required'}
 $taskProof=Get-Content "$taskStage/receipt.json" -Raw|ConvertFrom-Json
 if($taskProof.newVersion -ne 16 -or !$taskProof.producerComplete){throw 'Complete newSteer16 native evidence required'}
 $taskParameters.PrimaryCoreStage="$taskStage/primary-stage";$taskParameters.Task=@(':app:testDebugUnitTest','--tests','org.mcrf.client.PrimaryValidationDiagnosticTest',':app:assembleRelease',':engines:assembleSteerRelease');$taskParameters.ExportEngineApks=$true
}
if($DispatcherDnsCorrection){
 if(!$PrivateSteerStage){throw 'Retained verified Steer16 stage required'}
 $taskParameters.Task=@(':app:testDebugUnitTest','--tests','org.mcrf.client.NativeRouteTunDnsTest',':app:assembleRelease')
 $taskParameters.ExportEngineApks=$false
}
if($AuditCorrection){
 if(!$PrivateSteerStage -or $DispatcherDnsCorrection){throw 'Audit correction requires retained Steer16 and its own exact selector'}
 $taskParameters.Task=@(':app:testDebugUnitTest','--tests','org.mcrf.client.CandidateAuditFixesTest',':app:assembleRelease')
 $taskParameters.ExportEngineApks=$false
}
if($BackgroundReview){
 if(!$PrivateSteerStage -or $DispatcherDnsCorrection -or $AuditCorrection){throw 'Background review requires retained Steer16 and its own exact selector'}
 $taskParameters.Task=@(':app:testDebugUnitTest','--tests','org.mcrf.client.BackgroundReviewFixesTest',':app:assembleRelease')
 $taskParameters.ExportEngineApks=$false
}
if($DiagnosticReview){
 if(!$PrivateSteerStage -or $DispatcherDnsCorrection -or $AuditCorrection -or $BackgroundReview){throw 'Diagnostic review requires retained Steer16 and its own exact selectors'}
 $taskParameters.Task=@(':app:testDebugUnitTest','--tests','org.mcrf.client.SubscriptionFailureReviewTest','--tests','org.mcrf.client.EditorFailureReviewTest','--tests','org.mcrf.client.ImportCountReviewTest',':app:assembleRelease')
 $taskParameters.ExportEngineApks=$false
}
# Reuse the accepted native inputs. No native producer, old JVM selector,
if($FinalReview){
 if(!$PrivateSteerStage -or $DispatcherDnsCorrection -or $AuditCorrection -or $BackgroundReview -or $DiagnosticReview){throw 'Final accumulated review is one distinct complete package'}
 $taskFinal=Get-Content "$PSScriptRoot/../coordination/p3-p4-2026-10-23.json" -Raw|ConvertFrom-Json
 if($taskFinal.finalization.authority -cne 'human-20261025-finish-existing-fixes-build-stop' -or $taskFinal.finalization.automationDeleted -ne $true){throw 'Final direct human authority and removed heartbeat required'}
 $taskParameters.Task=@(':app:testDebugUnitTest')
 foreach($taskSelector in $taskFinal.post55ReviewPackage.selectors){$taskParameters.Task+=@('--tests',[string]$taskSelector)}
 $taskParameters.Task+=@(':app:assembleRelease',':app:compileDebugAndroidTestKotlin')
 $taskParameters.ExportEngineApks=$false
 if($FreshOpenFluxStage){
  $taskParameters.Task=@(':engines:assembleOpenfluxDebug')
  $taskParameters.OpenFluxFreshStage=$FreshOpenFluxStage
  $taskParameters.ExportEngineApks=$true
 }
}
if($UserFixes){
 if(!$PrivateSteerStage -or $FinalReview -or $DispatcherDnsCorrection -or $AuditCorrection -or $BackgroundReview -or $DiagnosticReview -or $FreshOpenFluxStage){throw 'User fixes require one separate scoped package with retained native inputs'}
 $taskUser=Get-Content "$PSScriptRoot/../coordination/p3-p4-2026-10-23.json" -Raw|ConvertFrom-Json
 if($taskUser.userFixes26.authority -cnotin @('human-20261026-four-ui-fixes-csqtt-analysis','human-20261026-bs-sites-vk-layout-polish') -or !$taskUser.userFixes26.sourceReady){throw 'New human scope and whole source readiness required'}
 if($taskUser.userFixes26.retainedUiChecks -eq 14){
  $taskBaseline='D:/MCRF/Projects/MCRF/work/android-integration-d9fe702f575a43a79fadbc4ce7f11741'
  if((Hash "$taskBaseline/build-result.json") -cne '943a1b3c19314c2addc2a368da63eb781422f4761a32a6a692a778296857b903'){throw 'Exact retained UI receipt required'}
  $taskPrevious=Get-Content "$taskBaseline/build-result.json" -Raw|ConvertFrom-Json
  if($taskPrevious.exitCode -ne 0 -or !$taskPrevious.sourceStable -or (Hash "$taskBaseline/source-manifest.json") -cne $taskPrevious.sourceManifestSha256){throw 'Retained UI build must be stable and successful'}
  $taskManifest=Get-Content "$taskBaseline/source-manifest.json" -Raw|ConvertFrom-Json
  $taskFreshHelp=$taskUser.userFixes26.freshUiChecks -eq 2
  $taskChanged=@('AndroidDeployUi.kt');if($taskFreshHelp){$taskChanged+=@('MainActivity.kt','BsSitesPresentation.kt')}
  foreach($taskRow in @($taskManifest|Where-Object {$_.path -like 'ports/android/app/src/main/kotlin/*.kt' -and (Split-Path $_.path -Leaf) -notin $taskChanged})){
   if((Hash (Join-Path 'D:/MCRF/Projects/MCRF' $taskRow.path)) -cne $taskRow.sha256){throw "Retained UI source changed: $($taskRow.path)"}
  }
  $taskTestRelative='app/src/test/kotlin/org/mcrf/client/BsSitesUiPolishTest.kt'
  $taskOriginal=([IO.File]::ReadAllText("$taskBaseline/ports/android/$taskTestRelative")).Replace("`r`n","`n").Trim()
  $taskNow=([IO.File]::ReadAllText("$PSScriptRoot/../$taskTestRelative")).Replace("`r`n","`n")
  $taskWithoutAddition=[regex]::Replace($taskNow,'(?m)^    @Test fun linkedVkOffersOnlyUnlinkAndUnlinkedOnlyLink.*\n','')
  if($taskFreshHelp){$taskWithoutAddition=[regex]::Replace($taskWithoutAddition,'(?m)^    @Test fun startupHelpAndFailureMessageMatchCustomSites.*\n','')}
  $taskWithoutAddition=$taskWithoutAddition.Trim()
  if($taskOriginal -cne $taskWithoutAddition){throw 'Unchanged retained test bodies required'}
  $taskPassed=0
  foreach($taskXmlFile in Get-ChildItem "$($taskPrevious.generatedBuildScratch)/app/test-results/testDebugUnitTest" -Filter '*.xml'){
   [xml]$taskXml=Get-Content $taskXmlFile.FullName
   if([int]$taskXml.testsuite.failures -or [int]$taskXml.testsuite.errors -or [int]$taskXml.testsuite.skipped){throw 'Retained UI tests must all pass'}
   $taskPassed+=[int]$taskXml.testsuite.tests
  }
  if($taskPassed -ne 14){throw 'Exact14 retained UI cases required'}
  if($taskFreshHelp){
   $taskAccount='D:/MCRF/Projects/MCRF/work/android-integration-1bc2f3fce1b5487cad8c60d9c737a22d'
   $taskAccountProof=Get-Content "$taskAccount/build-result.json" -Raw|ConvertFrom-Json
   if($taskAccountProof.exitCode -ne 0 -or !$taskAccountProof.sourceStable -or (Hash "$taskAccount/source-manifest.json") -cne $taskAccountProof.sourceManifestSha256){throw 'Stable account-action followup required'}
   foreach($taskName in @('AndroidDeployUi','VkAccountPresentation')){if((Hash "$PSScriptRoot/../app/src/main/kotlin/org/mcrf/client/$taskName.kt") -cne (Hash "$taskAccount/ports/android/app/src/main/kotlin/org/mcrf/client/$taskName.kt")){throw 'Retained account action changed'}}
   [xml]$taskAccountXml=Get-Content "$($taskAccountProof.generatedBuildScratch)/app/test-results/testDebugUnitTest/TEST-org.mcrf.client.BsSitesUiPolishTest.xml"
   if([int]$taskAccountXml.testsuite.tests -ne 1 -or [int]$taskAccountXml.testsuite.failures -or [int]$taskAccountXml.testsuite.errors){throw 'Account-action test must pass'}
   $taskMainOld=[IO.File]::ReadAllText("$taskBaseline/ports/android/app/src/main/kotlin/org/mcrf/client/MainActivity.kt").Replace('Google и GitLab не ответили напрямую.','Оба выбранных сайта не ответили напрямую.').Replace("`r`n","`n")
   if($taskMainOld -cne ([IO.File]::ReadAllText("$PSScriptRoot/../app/src/main/kotlin/org/mcrf/client/MainActivity.kt")).Replace("`r`n","`n")){throw 'Only scoped startup message may differ'}
   # Old help case is rechecked, so retained14 = original13 unchanged + account1.
  }
 }
 $taskParameters.Task=@(':app:testDebugUnitTest')
 foreach($taskSelector in $taskUser.userFixes26.selectors){$taskParameters.Task+=@('--tests',[string]$taskSelector)}
 $taskParameters.Task+=@(':app:assembleRelease',':app:compileDebugAndroidTestKotlin')
 $taskParameters.ExportEngineApks=$false
}
# No old UI suite, native build or publication is part of this correction.
& "$PSScriptRoot/../integration-build.ps1" @taskParameters
