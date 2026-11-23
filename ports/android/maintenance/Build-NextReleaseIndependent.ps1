param([switch]$UpdateActionsRow)
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation build
$parent='D:/MCRF/Builds/Android/inapp-update-5bf64329fa5144e4bd61b2a218195463'
if($UpdateActionsRow){$parent='D:/MCRF/Builds/Android/next-independent-b49377f3c54e4113a7eb9a886e33933d'}
$promotion='D:/MCRF/Builds/Android/p2-release06-e7e81f3a12a14a48808710f07f92289e/promotion.json'
function Hash([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Save($v,[string]$p){[IO.File]::WriteAllText($p,($v|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
$accepted=Get-Content "$parent/receipt.json" -Raw|ConvertFrom-Json
$expected=if($UpdateActionsRow){'PASS_INDEPENDENT_MODEL_PACKAGING'}else{'PASS_INAPP_UPDATE_PACKAGING'}
if($accepted.state -cne $expected -or !$accepted.sourceStable){throw 'Accepted parent required'}
$stage="D:/MCRF/Builds/Android/next-independent-$([guid]::NewGuid().ToString('N'))";$source="$stage/source"
New-Item -ItemType Directory -Path $source|Out-Null
$names=@('AndroidDeployBrowserUi','AndroidDeployPlan','AndroidDeployQuickConnection','AndroidDeployScripts','AndroidDeploySwg','AppRelease','AppUpdates','ClientBehaviorUi','CompatibilityImportQwdtt','Connections','EngineImportUi','ImportPlan','McrfVpnService','RoutingPage','WakeTunnelCheck')
$overlay=@($names|ForEach-Object {"ports/android/app/src/main/kotlin/org/mcrf/client/$_.kt"})
$overlay+=@('ports/android/app/build.gradle.kts','ports/android/app/src/test/kotlin/org/mcrf/client/NextReleaseFixesTest.kt','ports/android/app/src/test/NextReleaseRawDeployTest.py','ports/android/coordination/next-release-2026-10-23.json')
if($UpdateActionsRow){$overlay=@('ports/android/app/src/main/kotlin/org/mcrf/client/AppUpdates.kt')}
$rows=@()
foreach($row in (Get-Content "$parent/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)){
 $p=$row.path;if((Hash "$parent/source/$p") -cne $row.sha256){throw 'Parent source drift'}
 $from=if($p -cin $overlay){"$root/$p"}else{"$parent/source/$p"};$to="$source/$p"
 New-Item -ItemType Directory -Force -Path (Split-Path $to)|Out-Null;Copy-Item -LiteralPath $from -Destination $to
 $rows+=@{path=$p;sha256=Hash $to;bytes=(Get-Item $to).Length}
}
foreach($p in $overlay|Where-Object {$_ -cnotin $rows.path}){
 $to="$source/$p";New-Item -ItemType Directory -Force -Path (Split-Path $to)|Out-Null;Copy-Item -LiteralPath "$root/$p" -Destination $to
 $rows+=@{path=$p;sha256=Hash $to;bytes=(Get-Item $to).Length}
}
Save $rows "$stage/SOURCE_MANIFEST.json"
$r=[ordered]@{state='BUILDING';scope='independent-six-only';versionCode=10048;versionName='0.6.2-candidate.1';source=$source;nativeRebuilt=$false;oldTestsReplayed=$false;published=$false;deferred=@('wdtt-empty-route-group','bs-upload-throughput');overlay=$overlay}
$envs=@{};foreach($n in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$envs[$n]=[Environment]::GetEnvironmentVariable($n,'Process')}
Write-Output "NEXT_CANDIDATE=$stage";Save $r "$stage/receipt.json"
try{
 $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$root/work/toolchains/gradle-home";$env:JAVA_TOOL_OPTIONS=($envs.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
 if(!$UpdateActionsRow){
  & python "$source/ports/android/app/src/test/NextReleaseRawDeployTest.py" *> "$stage/python.log"
  $r.pythonExit=$LASTEXITCODE;if($LASTEXITCODE){throw 'New RAW deployment checks failed'}
  $r.newPythonTests=3
 }else{$r.retainedModelReceipt="$parent/receipt.json";$r.modelTestsReplayed=$false;$r.correction='human: update actions must remain in one row'}
 $proof="$source/ports/android/coordination/next-release-2026-10-23.json"
 $argsList=@(':app:testDebugUnitTest','--tests','org.mcrf.client.NextReleaseFixesTest',':app:assembleRelease','-PtestRelease=true','--offline','--console=plain','--no-daemon',"-PnextIndependentReceipt=$proof","-PnextIndependentSha256=$(Hash $proof)","-PhistoricalCsqttReleaseReceipt=$promotion","-PhistoricalCsqttReleaseSha256=$(Hash $promotion)",'-PcsqttLegacyCandidateGate=true','-Pcsqtt25CandidateGate=true','-PswgNativeCandidateGate=true','-PhistoricalCsqttCandidateGate=true')
 foreach($pair in @(@('csqttLegacy','csqtt-legacy'),@('csqtt25','csqtt25'),@('swgQwdtt','qwdtt'),@('swgWdtt','wdtt'),@('csqtt20','csqtt20'),@('csqtt215','csqtt215'))){$argsList+="-P$($pair[0])Stage=$source/ports/android/native-stage-$($pair[1])"}
 if($UpdateActionsRow){$argsList=$argsList[3..($argsList.Count-1)]}
 Push-Location "$source/ports/android";try{& "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain @argsList *> "$stage/build.log";$r.exitCode=$LASTEXITCODE}finally{Pop-Location}
 if($r.exitCode){throw 'New independent checks/build failed; inspect preserved log'}
 if(!$UpdateActionsRow){
  $tests=@(Get-ChildItem "$source/ports/android/app/build/test-results/testDebugUnitTest" -Filter '*.xml'|ForEach-Object {([xml](Get-Content $_.FullName -Raw)).testsuite.testcase})
  if($tests.Count -ne 9 -or @($tests|Where-Object {$_.classname -cne 'org.mcrf.client.NextReleaseFixesTest' -or $_.failure -or $_.error}).Count){throw 'Exactly nine new affected tests required'}
 }
 $apk="$source/ports/android/app/build/outputs/apk/release/app-universal-release.apk"
 $cert=(& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --print-certs $apk 2>&1|Out-String)
 if($LASTEXITCODE -or $cert -notmatch 'certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Exact public signer required'}
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 $zip=[IO.Compression.ZipFile]::OpenRead($apk);try{if(@($zip.Entries|Where-Object FullName -Match '^lib/|\.so$').Count){throw 'Main must remain native-free'}}finally{$zip.Dispose()}
 foreach($row in $rows){if((Hash "$source/$($row.path)") -cne $row.sha256){throw 'Frozen source drift'}}
 $r.state='PASS_INDEPENDENT_MODEL_PACKAGING';$r.sourceStable=$true;$r.newJvmTests=9;$r.apk=$apk;$r.sha256=Hash $apk;$r.bytes=(Get-Item $apk).Length
 if($UpdateActionsRow){$r.state='PASS_UPDATE_ACTION_ROW_PACKAGING';$r.newJvmTests=0;$r.newPythonTests=0}
}catch{$r.state='FAILED_PRESERVED';$r.failure=$_.Exception.Message;throw}finally{foreach($n in $envs.Keys){[Environment]::SetEnvironmentVariable($n,$envs[$n],'Process')};Save $r "$stage/receipt.json";Write-Output "NEXT_STATE=$($r.state)"}
