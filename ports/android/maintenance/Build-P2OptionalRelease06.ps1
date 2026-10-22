param()
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF';$base='D:/MCRF/Builds/Android/p2-release06-e7e81f3a12a14a48808710f07f92289e'
$native='D:/MCRF/Builds/Android/p049-followup-4f3f833bd3094a1891c5f35701fdb439/native/ports/android/optional-cores/build/android-p049-2182bcb9f5b1423482e0103e329ea0c0'
function Hash([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Save($v,[string]$p){[IO.File]::WriteAllText($p,($v|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
if((Hash "$native/receipt.json") -cne '4a400143deca3b5853b1085dd8fc36cb50522020a5650f978f265d722a9ca74c'){throw 'Exact installed accepted optional native stage required'}
$nr=Get-Content "$native/receipt.json" -Raw|ConvertFrom-Json
foreach($row in $nr.files){$lib=if($row.kind -eq 'tls-core'){'libtls_core.so'}else{'libxray.so'};if((Hash "$native/candidates/$($row.kind)/$($row.abi)/$lib") -cne $row.sha256){throw 'Optional accepted native drift'}}
$stage="D:/MCRF/Builds/Android/p2-optional-release06-$([guid]::NewGuid().ToString('N'))";$source="$stage/source";New-Item -ItemType Directory -Path $source|Out-Null
$rows=@()
foreach($row in (Get-Content "$base/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)){
 $path=$row.path;if((Hash "$base/source/$path") -cne $row.sha256){throw 'Frozen source changed'}
 $from=if($path -ceq 'ports/android/engines/build.gradle.kts'){"$root/$path"}else{"$base/source/$path"}
 $to="$source/$path";New-Item -ItemType Directory -Force -Path (Split-Path $to)|Out-Null;Copy-Item -LiteralPath $from -Destination $to
 $rows+=@{path=$path;sha256=Hash $to;bytes=(Get-Item $to).Length}
}
Save $rows "$stage/SOURCE_MANIFEST.json"
$result=[ordered]@{state='BUILDING';source=$source;nativeStage=$native;nativeRebuilt=$false;testsReplayed=$false}
Save $result "$stage/receipt.json";Write-Output "OPTIONAL_RELEASE06=$stage"
$old=@{};foreach($n in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$old[$n]=[Environment]::GetEnvironmentVariable($n,'Process')}
try{
 $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$root/work/toolchains/gradle-home";$env:JAVA_TOOL_OPTIONS=($old.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
 $argsList=@(':engines:assembleXrayRelease',':engines:assembleTlscoreRelease','-PtestRelease=true','--offline','--console=plain','--no-daemon',"-PoptionalCoreStage=$native",'-PoptionalCoreNativeGate=true',"-PhistoricalCsqttReleaseReceipt=$base/promotion.json","-PhistoricalCsqttReleaseSha256=$(Hash "$base/promotion.json")")
 Push-Location "$source/ports/android";try{& "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain @argsList *> "$stage/build.log";$result.exitCode=$LASTEXITCODE}finally{Pop-Location}
 if($result.exitCode -ne 0){throw 'Optional release packaging failed; preserve receipt'}
 foreach($row in $rows){if((Hash "$source/$($row.path)") -cne $row.sha256){throw 'Optional packaging source drift'}}
 $result.state='COMPILED_OPTIONAL_RELEASE_AWAITING_VERIFY';$result.sourceStable=$true
}catch{$result.state='FAILED_PRESERVED';throw}finally{foreach($n in $old.Keys){[Environment]::SetEnvironmentVariable($n,$old[$n],'Process')};Save $result "$stage/receipt.json";Write-Output "OPTIONAL_STATE=$($result.state)"}
