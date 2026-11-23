$ErrorActionPreference='Stop'
$taskRoot='D:/MCRF/Projects/MCRF'
$taskStage='D:/MCRF/Builds/Android/p047-eacb79c431714020bd9621836dcc819f'
$taskSource="$taskStage/source"
$taskCurrent="$taskRoot/ports/android/coordination/current.json"
function Sha([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Save($v,[string]$p){[IO.File]::WriteAllText($p,($v|ConvertTo-Json -Depth 14),[Text.UTF8Encoding]::new($false))}
if(Test-Path -LiteralPath "$taskStage/verification.json"){throw 'Existing attempt must be inspected, never duplicated'}
$taskPrep=Get-Content -LiteralPath "$taskStage/preparation.json" -Raw|ConvertFrom-Json
if((Sha "$taskStage/SOURCE_MANIFEST.json") -cne $taskPrep.manifestSha256){throw 'Input manifest changed'}
$taskInputs=Get-Content -LiteralPath "$taskStage/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json
foreach($row in $taskInputs){if((Sha "$taskSource/$($row.path)") -cne $row.sha256){throw 'Snapshot drift'}}
$taskLive=Get-Content -LiteralPath $taskCurrent -Raw|ConvertFrom-Json
if($taskLive.jobs.build -or $taskLive.jobs.hostTest -or $taskLive.jobs.device -or $taskLive.jobs.emulator){throw 'Producer already active'}
$taskOwner="P047:$taskStage";$taskLive.jobs.hostTest=$taskOwner;Save $taskLive $taskCurrent
$taskResult=[ordered]@{state='RUNNING';sourceManifestSha256=$taskPrep.manifestSha256;excludedP2=$true;apkBuilt=$false;nativeBuilt=$false;phoneChanged=$false}
$taskEnv=@{};foreach($name in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$taskEnv[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
 Save $taskResult "$taskStage/verification.json"
 $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$taskRoot/work/toolchains/gradle-home"
 $env:JAVA_TOOL_OPTIONS=($taskEnv.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
 Push-Location "$taskSource/ports/android"
 try{& "$env:JAVA_HOME/bin/java.exe" -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain ':app:compileDebugKotlin' ':app:testDebugUnitTest' '--tests' 'org.mcrf.client.AppPickerLoadGateTest' '--tests' 'org.mcrf.client.HomeUiAppFilterTest' '--offline' '--console=plain' *> "$taskStage/verification.log"
  $taskResult.exitCode=$LASTEXITCODE;if($LASTEXITCODE){throw 'Grouped picker compile/tests failed; preserve exact log'}
 }finally{Pop-Location}
 $taskPassed=0
 foreach($name in @('AppPickerLoadGateTest','HomeUiAppFilterTest')){
  $p="$taskSource/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.$name.xml";[xml]$x=Get-Content -LiteralPath $p -Raw
  if([int]$x.testsuite.tests -lt 1 -or [int]$x.testsuite.failures -or [int]$x.testsuite.errors -or [int]$x.testsuite.skipped){throw 'Missing affected tests'}
  $taskPassed += [int]$x.testsuite.tests
 }
 foreach($row in $taskInputs){if((Sha "$taskSource/$($row.path)") -cne $row.sha256){throw 'Source changed during verification'}}
 $taskResult.state='PASS_COMPILE_AND_PICKER_MODELS';$taskResult.tests=$taskPassed;$taskResult.sourceStable=$true
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{
 foreach($name in $taskEnv.Keys){[Environment]::SetEnvironmentVariable($name,$taskEnv[$name],'Process')}
 Save $taskResult "$taskStage/verification.json"
 $taskLive=Get-Content -LiteralPath $taskCurrent -Raw|ConvertFrom-Json
 if($taskLive.jobs.hostTest -ceq $taskOwner){$taskLive.jobs.hostTest=$null;Save $taskLive $taskCurrent}
 Write-Output "P047=$($taskResult.state);RECEIPT=$taskStage/verification.json"
}
