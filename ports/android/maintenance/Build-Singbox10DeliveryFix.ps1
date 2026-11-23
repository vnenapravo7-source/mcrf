param()
$ErrorActionPreference='Stop';$root='D:/MCRF/Projects/MCRF'
$parent='D:/MCRF/Builds/Android/p2-primary-release06-dfecc988d4a5433fbcd428501d77b36f'
function Hash([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Save($v,[string]$p){[IO.File]::WriteAllText($p,($v|ConvertTo-Json -Depth 9),[Text.UTF8Encoding]::new($false))}
$verified=Get-Content "$parent/verification-receipt.json" -Raw|ConvertFrom-Json
if($verified.state -cne 'PASS_PRIMARY_RELEASE_PACKAGING' -or !$verified.sourceStable){throw 'Exact accepted release packaging required'}
$stage="D:/MCRF/Builds/Android/singbox10-delivery-fix-$([guid]::NewGuid().ToString('N'))";$source="$stage/source";New-Item -ItemType Directory -Path $source|Out-Null
$overlay=@('ports/android/p2-route-dispatcher/build.gradle.kts','ports/android/p2-route-dispatcher/src/main/AndroidManifest.xml');$rows=@()
foreach($row in (Get-Content "$parent/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)){
 $p=$row.path;if((Hash "$parent/source/$p") -cne $row.sha256){throw 'Frozen successful source changed'}
 $from=if($p -cin $overlay){"$root/$p"}else{"$parent/source/$p"};$to="$source/$p"
 New-Item -ItemType Directory -Force -Path (Split-Path $to)|Out-Null;Copy-Item -LiteralPath $from -Destination $to
 $rows+=@{path=$p;sha256=Hash $to;bytes=(Get-Item $to).Length}
}
Save $rows "$stage/SOURCE_MANIFEST.json"
$old=@{};foreach($n in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$old[$n]=[Environment]::GetEnvironmentVariable($n,'Process')}
$r=[ordered]@{state='BUILDING_DELIVERY_FIX';source=$source;nativeRebuilt=$false;oldTestsReplayed=$false;mainRebuilt=$false}
Save $r "$stage/receipt.json";Write-Output "SINGBOX10=$stage"
try{
 $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$root/work/toolchains/gradle-home";$env:JAVA_TOOL_OPTIONS=($old.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
 $gate="$source/ports/android/coordination/p2-progress-2026-10-20.json"
 $argsList=@(':p2-route-dispatcher:assembleRelease','-PtestRelease=true','--offline','--console=plain','--no-daemon','-Pp2DispatcherCandidate=true',
 "-Pp2DispatcherStage=$source/work/p2-dispatcher-native-p2-singbox-route-source-2f8e34d8ca304776b0b0cb3eb452f1f1",
 "-Pp2SourceReadyReceipt=$gate","-Pp2SourceReadySha256=$(Hash $gate)","-Pp2JvmSelectionSha256=$(Hash "$source/ports/android/coordination/p2-jvm-affected-2026-10-20.txt")",
 "-PhistoricalCsqttReleaseReceipt=$parent/promotion.json","-PhistoricalCsqttReleaseSha256=$(Hash "$parent/promotion.json")")
 Push-Location "$source/ports/android";try{& "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain @argsList *> "$stage/build.log";$r.exitCode=$LASTEXITCODE}finally{Pop-Location}
 if($r.exitCode -ne 0){throw 'Delivery packaging failed; preserve exact log'}
 foreach($row in $rows){if((Hash "$source/$($row.path)") -cne $row.sha256){throw 'Frozen source drift'}}
 $r.state='COMPILED_SINGBOX10_DELIVERY_FIX';$r.sourceStable=$true
}catch{$r.state='FAILED_PRESERVED';throw}finally{foreach($n in $old.Keys){[Environment]::SetEnvironmentVariable($n,$old[$n],'Process')};Save $r "$stage/receipt.json";Write-Output "DELIVERY_STATE=$($r.state)"}
