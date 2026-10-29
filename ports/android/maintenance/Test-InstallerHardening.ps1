param([string]$Output='D:/MCRF/Builds/Android/installer-hardening-20261029/checks', [switch]$AndroidSourcesOnly)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$cache=Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
function Jar([string]$relative) {
    $found=@(Get-ChildItem -LiteralPath "$cache/$relative" -Recurse -Filter '*.jar')
    if($found.Count-ne 1){throw "Expected one jar: $relative"}
    $found[0].FullName
}
$java=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin/java.exe'}else{'C:/Program Files/Java/jdk-17/bin/java.exe'}
$stdlib=Jar 'org.jetbrains.kotlin/kotlin-stdlib/2.2.10'
$junit=Jar 'junit/junit/4.13.2'
$hamcrest=Jar 'org.hamcrest/hamcrest-core/1.3'
$compiler=@(
    (Jar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10'),$stdlib,
    (Jar 'org.jetbrains.kotlin/kotlin-script-runtime/2.2.10'),
    (Jar 'org.jetbrains.kotlin/kotlin-reflect/1.6.10'),
    (Jar 'org.jetbrains.kotlin/kotlin-daemon-embeddable/2.2.10'),
    (Jar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0'),
    (Jar 'org.jetbrains/annotations/13.0')
) -join ';'
New-Item -ItemType Directory -Path $Output -Force | Out-Null
if($AndroidSourcesOnly) {
    # Compile the changed Android consumers against existing dependency classes; no APK/native producer.
    $friendJar=(Resolve-Path "$root/app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar").Path
    $sdk=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{Join-Path $env:LOCALAPPDATA 'Android/Sdk'}
    $deps=@($friendJar,$stdlib,(Jar 'org.json/json/20250517'),(Jar 'com.google.zxing/core/3.5.3'),
        "$sdk/platforms/android-37.0/android.jar",
        "$root/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes",
        "$root/app/build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar",
        "$root/primary-spi/build/libs/primary-spi.jar",
        "$root/primary-adapter/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar")
    $sources=@('EngineArchivePolicy','EngineMetadata','EngineInstallPermission','EngineSignerPolicy','EngineSignatures',
        'EnginePackages','EngineUpdatePrimary','EngineUpdatePacket','EngineUpdateTelegram','EngineImportUi','MainActivity')|
        ForEach-Object {"$root/app/src/main/kotlin/org/mcrf/client/$_.kt"}
    & $java -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 "-Xfriend-paths=$friendJar" -classpath ($deps -join ';') -d "$Output/android-classes" @sources 2>&1 | Tee-Object "$Output/android-compile.log"
    if($LASTEXITCODE-ne 0){throw 'Changed Android consumer compilation failed'}
    return
}
$classes="$Output/classes"
$policies=@('EngineInstallPermission','EngineSignerPolicy','EngineMetadata','EngineArchivePolicy')
$sources=@($policies|ForEach-Object {"$root/app/src/main/kotlin/org/mcrf/client/$_.kt"})
$sources+=@('EngineInstallerRepairTest','EngineInstallerHardeningTest')|ForEach-Object {"$root/app/src/test/kotlin/org/mcrf/client/$_.kt"}
& $java -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$stdlib;$junit;$hamcrest" -d $classes @sources 2>&1 | Tee-Object "$Output/compile.log"
if($LASTEXITCODE-ne 0){throw 'Focused Kotlin compilation failed'}
& $java -cp "$classes;$stdlib;$junit;$hamcrest" org.junit.runner.JUnitCore org.mcrf.client.EngineInstallerRepairTest org.mcrf.client.EngineInstallerHardeningTest 2>&1 | Tee-Object "$Output/junit.log"
if($LASTEXITCODE-ne 0){throw 'Installer policy tests failed'}
Push-Location $root
try {
    python -m unittest discover -s tests -p test_engine_installer_contract.py -v 2>&1 | Tee-Object "$Output/source-contract.log"
    if($LASTEXITCODE-ne 0){throw 'Installer wiring checks failed'}
} finally {Pop-Location}
