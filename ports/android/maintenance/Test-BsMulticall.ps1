param([Parameter(Mandatory)][string]$Output)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$cache=Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
function Jar([string]$relative){$items=@(Get-ChildItem -LiteralPath "$cache/$relative" -Recurse -Filter '*.jar');if($items.Count -ne 1){throw "Expected one jar: $relative"};$items[0].FullName}
$stdlib=Jar 'org.jetbrains.kotlin/kotlin-stdlib/2.2.10'
$junit=Jar 'junit/junit/4.13.2';$hamcrest=Jar 'org.hamcrest/hamcrest-core/1.3';$json=Jar 'org.json/json/20250517'
$compiler=@((Jar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10'),$stdlib,
 (Jar 'org.jetbrains.kotlin/kotlin-script-runtime/2.2.10'),(Jar 'org.jetbrains.kotlin/kotlin-reflect/1.6.10'),
 (Jar 'org.jetbrains.kotlin/kotlin-daemon-embeddable/2.2.10'),(Jar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0'),(Jar 'org.jetbrains/annotations/13.0')) -join ';'
$java='C:/Program Files/Java/jdk-17/bin/java.exe'
$friend=(Resolve-Path "$root/app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar").Path
$deps=@($friend,$stdlib,$json,$junit,$hamcrest,
 'C:/Users/dieonfire/AppData/Local/Android/Sdk/platforms/android-37.0/android.jar',
 "$root/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes",
 "$root/app/build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar",
 "$root/primary-spi/build/libs/primary-spi.jar",
 "$root/primary-adapter/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar") -join ';'
if(Test-Path -LiteralPath $Output){throw 'Fresh standalone Kotlin stage required'}
$null=New-Item -ItemType Directory -Path $Output
$sources=@('BsEngineContract','BsEnginePackage','BsEngineMigration','EngineModules','EnginePackage','EnginePackages','EngineStartupPolicy','NativeCarrier','CsqttSwgVersions','CsqttGenerations','CsqttAutoDetection')|ForEach-Object {"$root/app/src/main/kotlin/org/mcrf/client/$_.kt"}
$sources+="$root/app/src/test/kotlin/org/mcrf/client/BsEngineContractTest.kt"
& $java -Xmx2g -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 "-Xfriend-paths=$friend" -classpath $deps -d "$Output/classes" @sources 2>&1 | Tee-Object "$Output/compile.log"
if($LASTEXITCODE){throw 'BS changed-source Kotlin compilation failed'}
& $java -cp "$Output/classes;$deps" org.junit.runner.JUnitCore org.mcrf.client.BsEngineContractTest 2>&1 | Tee-Object "$Output/tests.log"
if($LASTEXITCODE){throw 'BS Kotlin tests failed'}
