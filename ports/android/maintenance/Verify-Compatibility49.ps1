param([switch]$ResumeNative)
$ErrorActionPreference='Stop'
$taskRoot='D:/MCRF/Projects/MCRF'
$taskStage=(Get-Content "$taskRoot/ports/android/coordination/p0-49-compatibility-stage.json" -Raw|ConvertFrom-Json).stage
$taskPrep=Get-Content "$taskStage/preparation.json" -Raw|ConvertFrom-Json
$taskCurrent="$taskRoot/ports/android/coordination/current.json"
function Sha([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Save($v,[string]$p){[IO.File]::WriteAllText($p,($v|ConvertTo-Json -Depth 14),[Text.UTF8Encoding]::new($false))}
$taskPrevious=$null
if(Test-Path "$taskStage/verification.json"){
 if(!$ResumeNative){throw 'Existing common verification must be inspected, not duplicated'}
 $taskPrevious=Get-Content "$taskStage/verification.json" -Raw|ConvertFrom-Json
 if($taskPrevious.state -cne 'FAILED_PRESERVED' -or $taskPrevious.phases.application -cne 'PASS' -or $taskPrevious.error -cne 'Pinned fragment overlay failed'){throw 'Unexpected resume point'}
 if(Test-Path "$taskStage/verification-attempt1.json"){throw 'Resume already attempted'}
 Copy-Item "$taskStage/verification.json" "$taskStage/verification-attempt1.json"
}
if(!$taskPrep.sourceReady -or !$taskPrep.p2Excluded){throw 'Whole related source package required'}
foreach($group in @('app','optional','steer')){
 if((Sha "$taskStage/$group-inputs.json") -cne $taskPrep.manifestHashes.$group){throw 'Manifest changed'}
 $base=switch($group){'app'{"$taskStage/source"}'optional'{"$taskStage/native"}'steer'{"$taskStage/steer-source"}}
 foreach($row in (Get-Content "$taskStage/$group-inputs.json" -Raw|ConvertFrom-Json)){if((Sha "$base/$($row.path)") -cne $row.sha256){throw 'Frozen source drift'}}
}
$taskLive=Get-Content $taskCurrent -Raw|ConvertFrom-Json
if($taskLive.jobs.build -or $taskLive.jobs.hostTest -or $taskLive.jobs.device -or $taskLive.jobs.emulator){throw 'Existing job active'}
$taskOwner="P049_COMPAT:$taskStage";$taskLive.jobs.build=$taskOwner;Save $taskLive $taskCurrent
$taskResult=[ordered]@{state='RUNNING_COMMON_HOST_AND_COMPILE';p2Excluded=$true;phoneTouched=$false;phases=@{}}
if($ResumeNative){$taskResult.phases.application='PASS_RETAINED_64_CASES_AND_RELEASE_COMPILE'}
$taskNames=@('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS','GOPATH','GOCACHE','GOOS','GOARCH','CGO_ENABLED')
$taskOld=@{};foreach($n in $taskNames){$taskOld[$n]=[Environment]::GetEnvironmentVariable($n,'Process')}
$taskGo="$taskRoot/work/toolchains/go1.26.8/go/bin/go.exe"
try{
 Save $taskResult "$taskStage/verification.json"
 $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$taskRoot/work/toolchains/gradle-home"
 $env:JAVA_TOOL_OPTIONS=($taskOld.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
 if(!$ResumeNative){
 Push-Location "$taskStage/source/ports/android"
 try{
  & "$env:JAVA_HOME/bin/java.exe" -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain ':app:testDebugUnitTest' '--tests' 'org.mcrf.client.Optional*' ':app:compileReleaseKotlin' '--offline' '--console=plain' *> "$taskStage/app-compile.log"
  if($LASTEXITCODE){throw 'Common optional models/application compilation failed'}
 }finally{Pop-Location}
 $taskResult.phases.application='PASS';Save $taskResult "$taskStage/verification.json"
 }
 $env:GOPATH="$taskRoot/work/toolchains/go-home";$env:GOCACHE="$taskRoot/work/toolchains/go-build-cache";$env:GOOS='windows';$env:GOARCH='amd64';$env:CGO_ENABLED='0'
 $taskNative="$taskStage/native/ports/android"
 Push-Location "$taskNative/transports/tls-core"
 try{
  $taskPinned=(& $taskGo mod download -json github.com/throneproj/sing-box@v1.11.16-0.20261003011458-ebe0747b4182 |ConvertFrom-Json)
  if($LASTEXITCODE){throw 'Pinned TLS module unavailable'}
  $taskOverlay="$taskNative/optional-cores/build/tls-overlay"
  $taskPatch=(& 'C:/Users/dieonfire/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' "$taskNative/optional-cores/prepare_overlay.py" $taskPinned.Dir $taskOverlay |ConvertFrom-Json)
  if($LASTEXITCODE){throw 'Pinned fragment overlay failed'}
  $taskMod=(Get-Content go.mod -Raw).Replace('replace github.com/sagernet/sing-box => github.com/throneproj/sing-box v1.11.16-0.20261003011458-ebe0747b4182',"replace github.com/sagernet/sing-box => $($taskOverlay.Replace('\','/'))")
  [IO.File]::WriteAllText("$taskStage/tls-overlay.mod",$taskMod);Copy-Item go.sum "$taskStage/tls-overlay.sum"
 }finally{Pop-Location}
 Push-Location "$taskNative/optional-cores/bridge"
 try{& $taskGo test -mod=readonly -count=1 -v . *> "$taskStage/bridge-tests.log";if($LASTEXITCODE){throw 'Private DNS/config boundary failed'}}finally{Pop-Location}
 $taskResult.phases.bridge='PASS';Save $taskResult "$taskStage/verification.json"
 New-Item -ItemType Directory -Path "$taskNative/optional-cores/build" -Force|Out-Null
 foreach($kind in @('xray','tls-core')){
  Push-Location "$taskNative/transports/$kind"
  try{
   $extra=@();$tags=@();$ld='-s -w -checklinkname=0'
   if($kind -eq 'tls-core'){$extra=@("-modfile=$taskStage/tls-overlay.mod");$tags=@('-tags=with_utls,with_purego');$ld+=" -X main.fragmentPatch=$($taskPatch.patched_sha256)"}
   & $taskGo build -mod=readonly @extra @tags -trimpath "-ldflags=$ld" -o "$taskNative/optional-cores/build/$kind.exe" . *> "$taskStage/$kind-build.log"
   if($LASTEXITCODE){throw "Native host build failed: $kind"}
   & $taskGo test -mod=readonly @extra @tags '-ldflags=-checklinkname=0' -count=1 -v . *> "$taskStage/$kind-tests.log"
   if($LASTEXITCODE){throw "Common actual native/DNS/TLS tests failed: $kind"}
  }finally{Pop-Location}
  $taskResult.phases[$kind]='PASS';Save $taskResult "$taskStage/verification.json"
 }
 $taskResult.state='PASS_COMMON_MODELS_HOST_NATIVE_ANDROID_COMPILE';$taskResult.nativeAndroidBuilt=$false;$taskResult.nativeRuntimeAccepted=$false
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{
 foreach($n in $taskNames){[Environment]::SetEnvironmentVariable($n,$taskOld[$n],'Process')}
 Save $taskResult "$taskStage/verification.json"
 $taskLive=Get-Content $taskCurrent -Raw|ConvertFrom-Json
 if($taskLive.jobs.build -ceq $taskOwner){$taskLive.jobs.build=$null;Save $taskLive $taskCurrent}
 Write-Output "P049_COMPAT=$($taskResult.state);STAGE=$taskStage"
}
