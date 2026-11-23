param([switch]$ResumeConfigurationFailure)
$ErrorActionPreference='Stop'
$taskRoot='D:/MCRF/Projects/MCRF'
$taskStage='D:/MCRF/Builds/Android/p049-f523aad221694dbe9e729f6ebd7a8a51'
$taskSource="$taskStage/source"
$taskCurrent="$taskRoot/ports/android/coordination/current.json"
function Sha([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Save($v,[string]$p){[IO.File]::WriteAllText($p,($v|ConvertTo-Json -Depth 14),[Text.UTF8Encoding]::new($false))}
if(Test-Path -LiteralPath "$taskStage/verification.json"){
 $prior=Get-Content -LiteralPath "$taskStage/verification.json" -Raw|ConvertFrom-Json
 if(!$ResumeConfigurationFailure -or $prior.state -cne 'FAILED_PRESERVED' -or
    (Get-Content -LiteralPath "$taskStage/verification.log" -Raw) -notmatch "Unknown command-line option '--tests'"){throw 'Existing attempt not the inspected configuration-only failure'}
 if(Test-Path -LiteralPath "$taskStage/attempt1"){throw 'Attempt already archived'}
 New-Item -ItemType Directory -Path "$taskStage/attempt1"|Out-Null
 Copy-Item -LiteralPath "$taskStage/verification.json" -Destination "$taskStage/attempt1/verification.json"
 Copy-Item -LiteralPath "$taskStage/verification.log" -Destination "$taskStage/attempt1/verification.log"
}elseif($ResumeConfigurationFailure){throw 'No failed attempt to resume'}
$taskGate=Get-Content -LiteralPath "$taskRoot/ports/android/coordination/p0-49-connect-speed-2026-10-20.json" -Raw|ConvertFrom-Json
if(!$taskGate.sourceReady -or !$taskGate.allowCommonVerification){throw 'Whole P0 source batch not ready'}
$taskPrep=Get-Content -LiteralPath "$taskStage/preparation.json" -Raw|ConvertFrom-Json
if(!$taskPrep.excludedP2 -or (Sha "$taskStage/SOURCE_MANIFEST.json") -cne $taskPrep.manifestSha256){throw 'Unfrozen source'}
$taskInputs=Get-Content -LiteralPath "$taskStage/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json
foreach($row in $taskInputs){if((Sha "$taskSource/$($row.path)") -cne $row.sha256){throw 'Snapshot changed'}}
$taskLive=Get-Content -LiteralPath $taskCurrent -Raw|ConvertFrom-Json
if($taskLive.jobs.build -or $taskLive.jobs.hostTest -or $taskLive.jobs.device -or $taskLive.jobs.emulator){throw 'Resume existing job'}
$taskOwner="P049:$taskStage";$taskLive.jobs.hostTest=$taskOwner;Save $taskLive $taskCurrent
$taskCases=@('ConnectionStartPolicyTest','RouteTextCacheTest','WorkerValidationCacheTest','AccessProbeBatchTest','RouteParsingPerformanceTest','RouteResolutionTest','RouteListDnsTest','RoutingAppPoliciesTest','ClientPrimaryWorkerPolicyTest','ClientPrimarySpiContractTest','ClientPrimaryIdentityProofTest','ClientPrimaryRetirementTest','AdvancedDnsRoutingTest','DirectDnsFamilyTest','BsStartupPolicyTest','OptionalCorePlanTest','OptionalCoreTuningTest')
$taskResult=[ordered]@{state='RUNNING';sourceManifestSha256=$taskPrep.manifestSha256;excludedP2=$true;apkBuilt=$false;phoneChanged=$false;selected=$taskCases}
$taskEnv=@{};foreach($name in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$taskEnv[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
 Save $taskResult "$taskStage/verification.json"
 $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$taskRoot/work/toolchains/gradle-home"
 $env:JAVA_TOOL_OPTIONS=($taskEnv.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
 $taskArgs=@(':app:testDebugUnitTest')
 foreach($case in $taskCases){$taskArgs+=@('--tests',"org.mcrf.client.$case")}
 $taskArgs+=@(':app:compileReleaseKotlin',':app:compileDebugAndroidTestKotlin',':app:compileDebugAndroidTestJavaWithJavac','--offline','--console=plain')
 Push-Location "$taskSource/ports/android"
 try{& "$env:JAVA_HOME/bin/java.exe" -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain @taskArgs *> "$taskStage/verification.log"
  $taskResult.exitCode=$LASTEXITCODE;if($LASTEXITCODE){throw 'Grouped verification failed; exact log retained'}
 }finally{Pop-Location}
 $taskPassed=0
 foreach($name in $taskCases){
  $p="$taskSource/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.$name.xml";[xml]$x=Get-Content -LiteralPath $p -Raw
  if([int]$x.testsuite.tests -lt 1 -or [int]$x.testsuite.failures -or [int]$x.testsuite.errors -or [int]$x.testsuite.skipped){throw "Affected tests incomplete: $name"}
  $taskPassed += [int]$x.testsuite.tests
 }
 foreach($row in $taskInputs){if((Sha "$taskSource/$($row.path)") -cne $row.sha256){throw 'Source drift during verification'}}
 $taskResult.state='PASS_COMMON_COMPILE_AND_AFFECTED_JVM';$taskResult.tests=$taskPassed;$taskResult.sourceStable=$true
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{
 foreach($name in $taskEnv.Keys){[Environment]::SetEnvironmentVariable($name,$taskEnv[$name],'Process')}
 Save $taskResult "$taskStage/verification.json"
 $taskLive=Get-Content -LiteralPath $taskCurrent -Raw|ConvertFrom-Json
 if($taskLive.jobs.hostTest -ceq $taskOwner){$taskLive.jobs.hostTest=$null;Save $taskLive $taskCurrent}
 Write-Output "P049=$($taskResult.state);RECEIPT=$taskStage/verification.json"
}
