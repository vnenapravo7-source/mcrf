param()
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF'
$parent="$root/work/android-integration-fda518740e8845d4b7a09383d92675e6"
$stage="D:/MCRF/Builds/Android/p2-release06-$([guid]::NewGuid().ToString('N'))"
$utf8=[Text.UTF8Encoding]::new($false)
function Hash([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Read([string]$p){Get-Content -LiteralPath $p -Raw|ConvertFrom-Json}
function Save($v,[string]$p){[IO.File]::WriteAllText($p,($v|ConvertTo-Json -Depth 12),$utf8)}
$build=Read "$parent/build-result.json";$jvm=Read "$parent/p2-expansion-jvm-evidence.json"
if($build.exitCode -ne 0 -or !$build.sourceStable -or !$jvm.passed -or $jvm.count -ne 75 -or $jvm.newExecutionCount -ne 0 -or
 (Hash "$parent/source-manifest.json") -cne $build.sourceManifestSha256){throw 'Exact completed common and75 retained JVM required'}
$proofs=@(
 @{path='D:/MCRF/Builds/Android/p2-expanded-ui-139b0eb0ebbd4dae9bd108a552965242/run-c734b999d4fa43729ff3dc7aeda109ca/receipt.json';count=7;kind='accordion'},
 @{path='D:/MCRF/Builds/Android/p2-expanded-ui-139b0eb0ebbd4dae9bd108a552965242/run-78d067227d7e4a748e2dde3792cf5f64/receipt.json';count=4;kind='2.0'},
 @{path='D:/MCRF/Builds/Android/p2-expanded-ui-6e9e317b108748a0828edd2bb83ca253/run-656dc0e232f84037a58cd95d1afc845c/receipt.json';count=3;kind='2.1.5-rest'},
 @{path='D:/MCRF/Builds/Android/p2-expanded-ui-6ab61bc0520244f1ba21249a4017bcaf/run-66cf3c6a78f14a5289cf36ba299cfb8b/receipt.json';count=4;kind='2.1.9'},
 @{path='D:/MCRF/Builds/Android/p2-expanded-ui-139b0eb0ebbd4dae9bd108a552965242/run-77e21ff97d8a4219b01ce90b19e1ec6b/receipt.json';count=4;kind='2.5'},
 @{path='D:/MCRF/Builds/Android/p2-expanded-ui-6ab61bc0520244f1ba21249a4017bcaf/run-5f90cf1ba1e84755966464ffbde910f5/receipt.json';count=5;kind='management'}
)
foreach($row in $proofs){
 $p=Read $row.path
 if($p.state -cne 'PASS' -or $p.checks -ne $row.count -or !$p.settingsRestored -or !$p.protectedUnchanged -or !$p.removed){throw "Actual restored runtime receipt required: $($row.kind)"}
 $row.sha256=Hash $row.path
}
$partial='D:/MCRF/Builds/Android/p2-expanded-ui-139b0eb0ebbd4dae9bd108a552965242/run-9e1399ded2624c218426f8e56a1da2dd/receipt.json'
$p=Read $partial
if($p.state -cne 'FAILED_PRESERVED' -or $p.checks -ne 1 -or !$p.settingsRestored -or !$p.protectedUnchanged){throw 'Original215 successful start and failed traffic must remain preserved'}
$proofs+=@{path=$partial;sha256=Hash $partial;count=1;kind='2.1.5-start-preserved'}
$current=Read "$root/ports/android/coordination/current.json"
if(@($current.jobs.PSObject.Properties|Where-Object {$_.Value}).Count){throw 'An owned job is active; do not overlap release packaging'}
$overlay=@('ports/android/app/build.gradle.kts','ports/android/engines/build.gradle.kts')
$manifest=Read "$parent/source-manifest.json"
foreach($row in $manifest){
 $path=$row.path.Replace('\','/')
 if($path.StartsWith('ports/android/app/src/main/') -and (Hash "$root/$path") -cne $row.sha256){throw "Production source changed after phone acceptance: $path"}
 if((Hash "$parent/$path") -cne $row.sha256){throw 'Accepted snapshot drift'}
}
New-Item -ItemType Directory -Path $stage,"$stage/source"|Out-Null
$source="$stage/source";$rows=@()
foreach($row in $manifest){
 $path=$row.path.Replace('\','/')
 if([IO.Path]::IsPathRooted($path) -or '..' -cin $path.Split('/') -or $path -match '\.(?:jks|keystore|dpapi|p12)$'){throw 'Unsafe frozen source path'}
 $from=if($path -cin $overlay){"$root/$path"}else{"$parent/$path"}
 $to="$source/$path";New-Item -ItemType Directory -Force -Path (Split-Path $to)|Out-Null
 Copy-Item -LiteralPath $from -Destination $to
 $rows+=@{path=$path;sha256=Hash $to;bytes=(Get-Item $to).Length}
}
Save $rows "$stage/SOURCE_MANIFEST.json"
$promotion=[ordered]@{state='P2_RELEASE_PROMOTION_READY';versionCode=10043;versionName='0.6';
 sourceManifestSha256=Hash "$stage/SOURCE_MANIFEST.json";parent=$parent;parentManifestSha256=Hash "$parent/source-manifest.json";
 protocolTrafficPassed=@('2.0','2.1.5','2.1.9','2.5');managementChecks=5;accordionChecks=7;receipts=$proofs;retainedJvm=75;newJvmExecution=0;
 authority='Direct human22Oct: закончишь - выпускай пререлиз';nativeRebuilt=$false;overlay=$overlay}
Save $promotion "$stage/promotion.json"
$result=[ordered]@{state='BUILDING_RELEASE';stage=$stage;source=$source;versionCode=10043;versionName='0.6';nativeRebuilt=$false;testsReplayed=$false;published=$false;artifacts=@()}
Save $result "$stage/receipt.json";Write-Output "P2_RELEASE06=$stage"
$job="P2_RELEASE06:$stage";$current.jobs.build=$job;Save $current "$root/ports/android/coordination/current.json"
$envs=@{};foreach($name in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$envs[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
 $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$root/work/toolchains/gradle-home"
 $env:JAVA_TOOL_OPTIONS=($envs.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
 $argsList=@(':app:assembleRelease','-PtestRelease=true','--offline','--console=plain','--no-daemon',
 "-PhistoricalCsqttReleaseReceipt=$stage/promotion.json","-PhistoricalCsqttReleaseSha256=$(Hash "$stage/promotion.json")",
 '-PcsqttLegacyCandidateGate=true','-Pcsqtt25CandidateGate=true','-PswgNativeCandidateGate=true','-PhistoricalCsqttCandidateGate=true',
 "-PcsqttLegacyStage=$source/ports/android/native-stage-csqtt-legacy","-Pcsqtt25Stage=$source/ports/android/native-stage-csqtt25",
 "-PswgQwdttStage=$source/ports/android/native-stage-qwdtt","-PswgWdttStage=$source/ports/android/native-stage-wdtt",
 "-Pcsqtt20Stage=$source/ports/android/native-stage-csqtt20","-Pcsqtt215Stage=$source/ports/android/native-stage-csqtt215")
 foreach($kind in @('Csqtt','Csqtt25','Qwdtt','Wdtt','Csqtt20','Csqtt215')){$argsList+=":engines:assemble${kind}Release"}
 Push-Location "$source/ports/android"
 try{& "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain @argsList *> "$stage/build.log";$result.exitCode=$LASTEXITCODE}finally{Pop-Location}
 if($result.exitCode -ne 0){throw 'Release packaging failed; preserve exact log; no automatic old-suite retry'}
 $paths=@("$source/ports/android/app/build/outputs/apk/release/app-universal-release.apk")
 foreach($kind in @('csqtt','csqtt25','qwdtt','wdtt','csqtt20','csqtt215')){
  $files=@(Get-ChildItem "$source/ports/android/engines/build/outputs/apk/$kind/release" -Filter '*.apk' -File|Where-Object Name -NotMatch '-universal-')
  $expected=if($kind -eq 'csqtt25'){1}else{3}
  if($files.Count -ne $expected){throw "Exact supported ABI release modules required: $kind"};$paths+=@($files.FullName)
 }
 foreach($path in $paths){
  $cert=(& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --print-certs $path 2>&1|Out-String)
  if($LASTEXITCODE -or $cert -notmatch 'certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Exact existing public signer required'}
  $result.artifacts+=@{path=[IO.Path]::GetRelativePath($source,$path);bytes=(Get-Item $path).Length;sha256=Hash $path}
 }
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 $zip=[IO.Compression.ZipFile]::OpenRead($paths[0]);try{if(@($zip.Entries|Where-Object {$_.FullName -match '^lib/|\.so$|(?:^|/)__pycache__/|\.pyc$'}).Count){throw 'No native engines/generated bytecode in universal main'}}finally{$zip.Dispose()}
 foreach($row in $rows){if((Hash "$source/$($row.path)") -cne $row.sha256){throw 'Frozen release source drift'}}
 $result.state='PASS_RELEASE_PACKAGING';$result.sourceStable=$true
}catch{$result.state='FAILED_PRESERVED';throw}
finally{
 foreach($name in $envs.Keys){[Environment]::SetEnvironmentVariable($name,$envs[$name],'Process')}
 $current=Read "$root/ports/android/coordination/current.json"
 if($current.jobs.build -ceq $job){$current.jobs.build=$null;Save $current "$root/ports/android/coordination/current.json"}
 Save $result "$stage/receipt.json";Write-Output "P2_RELEASE06_STATE=$($result.state)"
}
