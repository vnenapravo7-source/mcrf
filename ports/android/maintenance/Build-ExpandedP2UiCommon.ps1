param([string]$Parent='D:/MCRF/Projects/MCRF/work/android-integration-7eb12edebcfe481eb1ceaff2b872eae7')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation build
$root='D:/MCRF/Projects/MCRF';$coord="$root/ports/android/coordination"
function Read([string]$path){Get-Content -LiteralPath $path -Raw|ConvertFrom-Json}
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
$accepted=Read "$Parent/p2-expansion-artifacts.json";$oldBuild=Read "$Parent/build-result.json";$oldJvm=Read "$Parent/p2-expansion-jvm-evidence.json"
if($oldBuild.exitCode -ne 0 -or !$oldBuild.sourceStable -or !$oldJvm.passed -or $oldJvm.count -ne 52 -or $accepted.sourceManifestSha256 -cne (Hash "$Parent/source-manifest.json") -or $accepted.jvmEvidenceSha256 -cne (Hash "$Parent/p2-expansion-jvm-evidence.json")){throw 'Exact accepted expanded common receipts required'}
$current=Read "$coord/current.json"
if($current.activeBatch -cne 'p2-expansion-2026-10-21.json' -or @($current.jobs.PSObject.Properties|Where-Object {$_.Value}).Count){throw 'Exclusive idle expanded coordinator required'}
$changes=@('ports/android/app/src/main/kotlin/org/mcrf/client/MainActivity.kt','ports/android/app/src/main/kotlin/org/mcrf/client/RouteServerPicker.kt','ports/android/app/build.gradle.kts')
$parentRows=@(Read "$Parent/source-manifest.json")
foreach($row in $parentRows){
    if((Hash (Join-Path $Parent $row.path)) -cne $row.sha256){throw 'Immutable parent input changed'}
    if($row.path.Replace('\','/') -like 'ports/android/app/src/main/*' -and $row.path.Replace('\','/') -cnotin $changes -and (Hash (Join-Path $root $row.path)) -cne $row.sha256){throw "Unreviewed production change: $($row.path)"}
}
$stage=[IO.Path]::GetFullPath("$root/work/p2-expanded-ui-common-$([guid]::NewGuid().ToString('N'))");New-Item -ItemType Directory -Path $stage|Out-Null
$rows=@();foreach($row in $parentRows){
    $relative=$row.path.Replace('\','/');$source=Join-Path $(if($relative -cin $changes){$root}else{$Parent}) $relative
    $target=[IO.Path]::GetFullPath((Join-Path $stage $relative));if(!$target.StartsWith($stage+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe frozen path'}
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))|Out-Null;[IO.File]::Copy($source,$target,$false)
    $rows+=@{path=$relative;bytes=(Get-Item -LiteralPath $target).Length;sha256=Hash $target}
}
$proof=[ordered]@{state='UI_CORRECTION_SOURCE_READY';priority=2;versionCode=10027;parent=$Parent;parentArtifactsSha256=Hash "$Parent/p2-expansion-artifacts.json";reviewedChangedSources=$changes;nativeUnchanged=$true;retainedJvmCount=52;newJvmExecution=$false;scope='All accumulated route-picker footer, narrow routes handle and compact interval changes; remaining new UI/runtime checks only'}
[IO.File]::WriteAllText("$stage/ui-correction-source.json",($proof|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText("$stage/source-manifest.json",($rows|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath "$Parent/p2-expansion-jvm-evidence.json" -Destination "$stage/p2-expansion-jvm-evidence.json"
$job="ExpandedP2UiCommon:$([guid]::NewGuid().ToString('N'))"
$result=[ordered]@{state='BUILDING';parent=$Parent;snapshot=$stage;task=@(':app:assembleDebug',':app:assembleDebugAndroidTest');exitCode=$null;sourceStable=$false;versionCode=10027;retainedJvm=52;newJvmExecution=$false;nativeRebuilt=$false;published=$false}
$oldEnv=@{};foreach($name in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$oldEnv[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
Write-Output "EXPANDED_P2_UI_COMMON=$stage"
try{
    $current.jobs.build=$job;[IO.File]::WriteAllText("$coord/current.json",($current|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
    $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$root/work/toolchains/gradle-home"
    $env:JAVA_TOOL_OPTIONS=($oldEnv.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
    $ready="$stage/ports/android/coordination/p2-expansion-2026-10-21.json";$selection="$stage/ports/android/coordination/p2-expansion-jvm-affected-2026-10-21.txt"
    Push-Location "$stage/ports/android"
    try{
        & "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain ':app:assembleDebug' ':app:assembleDebugAndroidTest' --offline --no-daemon --console=plain "-Pp2ExpansionSourceReadyReceipt=$ready" "-Pp2ExpansionSourceReadySha256=$(Hash $ready)" "-Pp2ExpansionJvmSelectionSha256=$(Hash $selection)" "-Pp2UiCorrectionReceipt=$stage/ui-correction-source.json" "-Pp2UiCorrectionSha256=$(Hash "$stage/ui-correction-source.json")" *> "$stage/build.log"
        $result.exitCode=$LASTEXITCODE
    }finally{Pop-Location}
    if($result.exitCode -ne 0){throw 'Common UI build failed; retain exact log; no automatic retry'}
    foreach($row in $rows){if((Hash (Join-Path $stage $row.path)) -cne $row.sha256){throw 'Frozen source changed during common build'}}
    $result.sourceStable=$true
    $artifacts=@();foreach($row in $accepted.artifacts){
        $relative=$row.path.Replace('\','/');$target=Join-Path $stage $relative
        if($relative.StartsWith('ports/android/app/build/outputs/apk/debug/') -and !$relative.EndsWith('app-universal-debug.apk')){continue}
        if($relative -notmatch 'app-universal-debug.apk$|app-debug-androidTest.apk$'){
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))|Out-Null
            if((Hash (Join-Path $Parent $relative)) -cne $row.sha256){throw 'Retained native APK changed'}
            [IO.File]::Copy((Join-Path $Parent $relative),$target,$false)
        }
        if(!(Test-Path -LiteralPath $target)){throw "Missing common artifact: $relative"}
        $artifacts+=@{path=$relative;sha256=Hash $target;bytes=(Get-Item -LiteralPath $target).Length;retained=$relative -notmatch 'app-universal-debug.apk$|app-debug-androidTest.apk$'}
    }
    $apk="$stage/ports/android/app/build/outputs/apk/debug/app-universal-debug.apk"
    $badging=(& "$env:ANDROID_HOME/build-tools/36.0.0/aapt2.exe" dump badging $apk|Out-String)
    if($LASTEXITCODE -or $badging -notmatch "versionCode='10027'"){throw 'Wrong candidate version'}
    & "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify $apk;if($LASTEXITCODE){throw 'APK signature invalid'}
    $newProof=@{versionCode=10027;sourceManifestSha256=Hash "$stage/source-manifest.json";jvmEvidenceSha256=Hash "$stage/p2-expansion-jvm-evidence.json";artifacts=$artifacts;retainedEvidence=@{parent=$Parent;count=52;newExecution=$false;reviewedUiDelta=$changes;nativeUnchanged=$true};uiCorrectionSourceSha256=Hash "$stage/ui-correction-source.json"}
    [IO.File]::WriteAllText("$stage/p2-expansion-artifacts.json",($newProof|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
    $result.state='COMMON_BUILD_PASS_PHONE_PENDING'
}catch{$result.state='FAILED_PRESERVED';$result.error=$_.Exception.Message;throw}
finally{
    foreach($name in $oldEnv.Keys){[Environment]::SetEnvironmentVariable($name,$oldEnv[$name],'Process')}
    [IO.File]::WriteAllText("$stage/build-result.json",($result|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
    $current=Read "$coord/current.json";if($current.jobs.build -ceq $job){$current.jobs.build=$null;[IO.File]::WriteAllText("$coord/current.json",($current|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))}
    Write-Output "EXPANDED_P2_UI_COMMON_STATE=$($result.state)"
}
