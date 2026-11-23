$ErrorActionPreference='Stop'
$taskRoot='D:/MCRF/Projects/MCRF'
$taskStage='D:/MCRF/Builds/Android/p049-candidate-701f35cbd75f41dab40a97dafdca7cb0'
$taskSource="$taskStage/source"
$taskCurrent="$taskRoot/ports/android/coordination/current.json"
function Sha([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Save($v,[string]$p){[IO.File]::WriteAllText($p,($v|ConvertTo-Json -Depth 14),[Text.UTF8Encoding]::new($false))}
if(Test-Path -LiteralPath "$taskStage/build.json"){throw 'Existing build must be inspected/resumed, never duplicated'}
$taskPrep=Get-Content -LiteralPath "$taskStage/preparation.json" -Raw|ConvertFrom-Json
if(!$taskPrep.sourceReady -or !$taskPrep.excludedP2 -or (Sha "$taskStage/SOURCE_MANIFEST.json") -cne $taskPrep.manifestSha256){throw 'Candidate inputs not frozen'}
if((Sha "$($taskPrep.parent)/verification.json") -cne $taskPrep.retainedParentReceiptSha256){throw 'Retained evidence changed'}
$taskInputs=Get-Content -LiteralPath "$taskStage/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json
foreach($row in $taskInputs){if((Sha "$taskSource/$($row.path)") -cne $row.sha256){throw 'Snapshot changed'}}
$taskLive=Get-Content -LiteralPath $taskCurrent -Raw|ConvertFrom-Json
if($taskLive.jobs.build -or $taskLive.jobs.hostTest -or $taskLive.jobs.device -or $taskLive.jobs.emulator){throw 'Resume existing producer'}
$taskOwner="P049_CANDIDATE:$taskStage";$taskLive.jobs.build=$taskOwner;Save $taskLive $taskCurrent
$taskResult=[ordered]@{state='BUILDING';sourceManifestSha256=$taskPrep.manifestSha256;excludedP2=$true;versionCode=10022;versionName=$taskPrep.candidateVersionName;phoneChanged=$false;published=$false;nativeRebuilt=$false;artifacts=@{}}
$taskEnv=@{};foreach($name in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$taskEnv[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
 Save $taskResult "$taskStage/build.json"
 $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$taskRoot/work/toolchains/gradle-home"
 $env:JAVA_TOOL_OPTIONS=($taskEnv.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
 Push-Location "$taskSource/ports/android"
 try{& "$env:JAVA_HOME/bin/java.exe" -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain ':app:testDebugUnitTest' '--tests' 'org.mcrf.client.AccessProbeBatchTest' '--tests' 'org.mcrf.client.DirectAccessBudgetTest' '--tests' 'org.mcrf.client.BsStartupPolicyTest' ':app:assembleRelease' ':app:assembleDebugAndroidTest' '-PtestRelease=true' '--offline' '--console=plain' *> "$taskStage/build.log"
  $taskResult.exitCode=$LASTEXITCODE;if($LASTEXITCODE){throw 'Candidate grouped build failed; preserve log'}
 }finally{Pop-Location}
 $taskExecuted=0
 foreach($name in @('AccessProbeBatchTest','DirectAccessBudgetTest','BsStartupPolicyTest')){
  $p="$taskSource/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.$name.xml";[xml]$x=Get-Content -LiteralPath $p -Raw
  if([int]$x.testsuite.tests -lt 1 -or [int]$x.testsuite.failures -or [int]$x.testsuite.errors -or [int]$x.testsuite.skipped){throw 'Affected budget cases incomplete'}
  $taskExecuted += [int]$x.testsuite.tests
 }
 if($taskExecuted -ne 13){throw 'Expected13 executed budget/BS cases'}
 $taskResult.jvm=@{executed=13;retainedUnchanged=138;uniquePassed=151;parentReceipt=$taskPrep.retainedParentReceiptSha256}
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 foreach($abi in @('universal','arm64-v8a','armeabi-v7a','x86_64')){
  $p="$taskSource/ports/android/app/build/outputs/apk/release/app-$abi-release.apk"
  $cert=(& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --print-certs $p 2>&1|Out-String)
  if($LASTEXITCODE -or $cert -notmatch 'certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Signer mismatch'}
  $identity=(& "$env:ANDROID_HOME/build-tools/36.0.0/aapt2.exe" dump badging $p 2>&1|Out-String)
  if($LASTEXITCODE -or $identity -notmatch "package: name='org.mcrf.client' versionCode='10022' versionName='0.1.0-preview.20261020.2'"){throw 'Candidate identity mismatch'}
  $zip=[IO.Compression.ZipFile]::OpenRead($p);try{if(@($zip.Entries|Where-Object FullName -Match '^lib/|\.so$').Count){throw 'Native engine in main APK'}}finally{$zip.Dispose()}
  $taskResult.artifacts[$abi]=@{path=$p;bytes=(Get-Item -LiteralPath $p).Length;sha256=(Sha $p)}
 }
 $test="$taskSource/ports/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
 $taskResult.testApk=@{path=$test;bytes=(Get-Item -LiteralPath $test).Length;sha256=(Sha $test)}
 foreach($row in $taskInputs){if((Sha "$taskSource/$($row.path)") -cne $row.sha256){throw 'Frozen source drift'}}
 $taskResult.state='PASS_R8_CANDIDATE_AND_151_JVM';$taskResult.sourceStable=$true
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{
 foreach($name in $taskEnv.Keys){[Environment]::SetEnvironmentVariable($name,$taskEnv[$name],'Process')}
 Save $taskResult "$taskStage/build.json"
 $taskLive=Get-Content -LiteralPath $taskCurrent -Raw|ConvertFrom-Json
 if($taskLive.jobs.build -ceq $taskOwner){$taskLive.jobs.build=$null;Save $taskLive $taskCurrent}
 Write-Output "P049=$($taskResult.state);RECEIPT=$taskStage/build.json"
}
