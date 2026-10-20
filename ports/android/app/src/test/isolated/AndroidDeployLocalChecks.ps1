param([Parameter(Mandatory=$true)][string]$FrozenClasses)
$ErrorActionPreference='Stop'
if(-not (Test-Path -LiteralPath $FrozenClasses -PathType Leaf)) {
    throw 'FrozenClasses must point to the compile classes.jar of a currently retained completed snapshot. Historical snapshots may have been archived by the three-version retention policy.'
}
# Direct compiler only: no Gradle, phone, provider or SSH. Frozen classes supply other owners' API signatures.
$appRoot=(Resolve-Path "$PSScriptRoot/../../..").Path
$repoRoot=(Resolve-Path "$appRoot/../../..").Path
$cacheRoot="$repoRoot/work/toolchains/gradle-home/caches/modules-2/files-2.1"
$jars=Get-ChildItem -LiteralPath $cacheRoot -Recurse -Filter '*.jar'
$compiler=($jars | Where-Object { $_.Name -match '^(kotlin-compiler-embeddable-2.2.10|kotlin-stdlib-2.2.10|kotlin-reflect-2.2.10|kotlin-script-runtime-2.2.10|annotations-13.0|kotlinx-coroutines-core-jvm-1.8.0)\.jar$' }).FullName -join ';'
$deps=($jars | Where-Object { $_.Name -match '^(kotlin-stdlib-2.2.10|json-20250517|jsch-0.2.24|junit-4.13.2|hamcrest-core-1.3|core-3.5.3|annotations-13.0)\.jar$' }).FullName -join ';'
$androidApi="$env:LOCALAPPDATA/Android/Sdk/platforms/android-34/android.jar"
$sources=(Get-ChildItem "$appRoot/src/main/kotlin/org/mcrf/client" -Filter 'VkRelay*.kt').FullName + (Get-ChildItem "$appRoot/src/main/kotlin/org/mcrf/client" -Filter 'AndroidDeploy*.kt').FullName + (Get-ChildItem "$appRoot/src/test/kotlin/org/mcrf/client" -Filter 'VkRelay*.kt').FullName + (Get-ChildItem "$appRoot/src/test/kotlin/org/mcrf/client" -Filter 'AndroidDeploy*.kt').FullName + "$PSScriptRoot/VkRelayCompileStub.kt"
$output="$appRoot/build/VkRelay-change18-isolated.jar"
$sources += "$appRoot/src/androidTest/kotlin/org/mcrf/client/AndroidDeployDeviceChecks.kt"
$java='C:/Program Files/Java/jdk-17/bin/java.exe'
& $java -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$deps;$androidApi;$FrozenClasses" -d $output @sources
if($LASTEXITCODE -ne 0) { throw 'Owned Kotlin compilation failed' }
& $java '-Djdk.util.jar.enableMultiRelease=false' -cp "$output;$appRoot/src/test/resources;$deps;$androidApi;$FrozenClasses" org.junit.runner.JUnitCore org.mcrf.client.VkRelayTest org.mcrf.client.AndroidDeployTest org.mcrf.client.AndroidDeployQrTest
if($LASTEXITCODE -ne 0) { throw 'Owned model/QR tests failed' }
