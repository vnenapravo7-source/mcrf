param()
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF'
$accepted="$root/work/android-integration-0dcead46dd0e473fa46510ffe38f6dea"
$release='D:/MCRF/Builds/Android/p2-release06-e7e81f3a12a14a48808710f07f92289e'
function Hash([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Read([string]$p){Get-Content -LiteralPath $p -Raw|ConvertFrom-Json}
function Save($v,[string]$p){[IO.File]::WriteAllText($p,($v|ConvertTo-Json -Depth 14),[Text.UTF8Encoding]::new($false))}
$accept=Read "$root/ports/android/coordination/p2-runtime-acceptance-2026-10-21.json"
if(!$accept.fullP2Accepted -or $accept.ownedAndroidRuntime.passedChecks -ne 81 -or $accept.common -cne $accepted){throw 'Actual accepted primary runtime required'}
if((Hash "$accepted/source-manifest.json") -cne '538e2e2ce30591f27172017c4e7b40e4d0a03eaad35108e4cc897abb0135f5c0'){throw 'Exact accepted primary snapshot required'}
$main=Read "$release/verification-receipt.json"
if($main.state -cne 'PASS_RELEASE_PACKAGING' -or !$main.sourceStable){throw 'Verified final main release required'}
$stage="D:/MCRF/Builds/Android/p2-primary-release06-$([guid]::NewGuid().ToString('N'))"
$source="$stage/source";New-Item -ItemType Directory -Path $source|Out-Null
$overlays=@('ports/android/engines/build.gradle.kts','ports/android/wg-module/build.gradle.kts','ports/android/p2-route-dispatcher/build.gradle.kts')
$rows=@()
foreach($row in (Read "$accepted/source-manifest.json")){
 $path=$row.path.Replace('\','/')
 if([IO.Path]::IsPathRooted($path) -or '..' -cin $path.Split('/')){throw 'Unsafe manifest path'}
 if((Hash "$accepted/$path") -cne $row.sha256){throw 'Accepted primary source drift'}
 $from=if($path -cin $overlays){"$root/$path"}else{"$accepted/$path"}
 $to="$source/$path";New-Item -ItemType Directory -Force -Path (Split-Path $to)|Out-Null;Copy-Item -LiteralPath $from -Destination $to
 $rows+=@{path=$path;sha256=Hash $to;bytes=(Get-Item $to).Length}
}
foreach($file in Get-ChildItem "$release/source/ports/android/engines" -Filter '*.xml' -File){
 $path="ports/android/engines/$($file.Name)"
 if(!(Test-Path -LiteralPath "$source/$path")){
  Copy-Item -LiteralPath $file.FullName -Destination "$source/$path"
  $rows+=@{path=$path;sha256=Hash "$source/$path";bytes=$file.Length}
 }
}
Save $rows "$stage/SOURCE_MANIFEST.json"
$proof=Read "$release/promotion.json"
$proof|Add-Member retainedPrimaryRuntimeChecks 81
$proof|Add-Member acceptedPrimaryManifestSha256 '538e2e2ce30591f27172017c4e7b40e4d0a03eaad35108e4cc897abb0135f5c0'
$proof|Add-Member primaryAcceptanceSha256 (Hash "$root/ports/android/coordination/p2-runtime-acceptance-2026-10-21.json")
Save $proof "$stage/promotion.json"
$result=[ordered]@{state='BUILDING';stage=$stage;source=$source;nativeRebuilt=$false;testsReplayed=$false;artifacts=@()}
Save $result "$stage/receipt.json";Write-Output "PRIMARY_RELEASE06=$stage"
$gate="$source/ports/android/coordination/p2-progress-2026-10-20.json"
$primary="$source/work/p2-primary-p2-steer-candidate-d3992f56f32d41438c654d3d785162a1"
$dispatcher="$source/work/p2-dispatcher-native-p2-singbox-route-source-2f8e34d8ca304776b0b0cb3eb452f1f1"
$wg="$source/ports/android/transports/wg-backend/build/candidate-0a6d6707d0ad443595d82da34ea85e1a"
$oldEnv=@{};foreach($n in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$oldEnv[$n]=[Environment]::GetEnvironmentVariable($n,'Process')}
try{
 $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$root/work/toolchains/gradle-home"
 $env:JAVA_TOOL_OPTIONS=($oldEnv.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
 $argsList=@(':engines:assembleSteerRelease',':p2-route-dispatcher:assembleRelease',':wg-module:assembleRelease','--offline','--console=plain','--no-daemon','-PtestRelease=true',
 '-Pp2SteerCandidate=true','-Pp2DispatcherCandidate=true','-Pp2WgCandidate=true','-PprimaryCoreNativeGate=true','-PwgBackendCandidateGate=true',
 "-Pp2SourceReadyReceipt=$gate","-Pp2SourceReadySha256=$(Hash $gate)","-PprimaryCoreStage=$primary","-Pp2DispatcherStage=$dispatcher","-PwgBackendStage=$wg",
 "-PhistoricalCsqttReleaseReceipt=$stage/promotion.json","-PhistoricalCsqttReleaseSha256=$(Hash "$stage/promotion.json")")
 $argsList+="-Pp2JvmSelectionSha256=$(Hash "$source/ports/android/coordination/p2-jvm-affected-2026-10-20.txt")"
 Push-Location "$source/ports/android"
 try{& "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain @argsList *> "$stage/build.log";$result.exitCode=$LASTEXITCODE}finally{Pop-Location}
 if($result.exitCode -ne 0){throw 'Primary packaging failed; preserve log; no automatic test/native retry'}
 foreach($row in $rows){if((Hash "$source/$($row.path)") -cne $row.sha256){throw 'Frozen primary source drift'}}
 $result.state='COMPILED_RELEASE_AWAITING_VERIFY';$result.sourceStable=$true
}catch{$result.state='FAILED_PRESERVED';throw}finally{
 foreach($n in $oldEnv.Keys){[Environment]::SetEnvironmentVariable($n,$oldEnv[$n],'Process')}
 Save $result "$stage/receipt.json";Write-Output "PRIMARY_STATE=$($result.state)"
}
