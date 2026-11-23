param([string]$Output = ('D:/MCRF/Temp/ab-comparison-checks-' + [guid]::NewGuid().ToString('N')))
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$cache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
function Jar([string]$relative) {
    $items = @(Get-ChildItem -LiteralPath "$cache/$relative" -Recurse -Filter '*.jar')
    if ($items.Count -ne 1) { throw "Expected one cached jar: $relative" }
    $items[0].FullName
}
$stdlib = Jar 'org.jetbrains.kotlin/kotlin-stdlib/2.2.10'
$json = Jar 'org.json/json/20250517'
$compiler = @((Jar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10'), $stdlib,
    (Jar 'org.jetbrains.kotlin/kotlin-script-runtime/2.2.10'), (Jar 'org.jetbrains.kotlin/kotlin-reflect/1.6.10'),
    (Jar 'org.jetbrains.kotlin/kotlin-daemon-embeddable/2.2.10'),
    (Jar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0'), (Jar 'org.jetbrains/annotations/13.0')) -join ';'
$java = 'C:/Program Files/Java/jdk-17/bin/java.exe'
$friend = (Resolve-Path "$root/app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar").Path
# Executable JSON must precede retained app classes and Android's non-executable stubs.
$deps = @($json, $stdlib, (Jar 'junit/junit/4.13.2'), (Jar 'org.hamcrest/hamcrest-core/1.3'), $friend,
    'C:/Users/dieonfire/AppData/Local/Android/Sdk/platforms/android-37.0/android.jar',
    "$root/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes",
    "$root/app/build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar",
    "$root/primary-spi/build/libs/primary-spi.jar",
    "$root/primary-adapter/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar") -join ';'
if (Test-Path -LiteralPath $Output) { throw 'Fresh host-only output required' }
$null = New-Item -ItemType Directory -Path $Output
$sources = @('TransportComparison', 'TransportComparisonNativeProvider', 'TransportComparisonUi', 'ConnectionSections') |
    ForEach-Object { "$root/app/src/main/kotlin/org/mcrf/client/$_.kt" }
$tests = @('TransportComparisonTest', 'TransportComparisonBoundsTest')
$sources += @($tests | ForEach-Object { "$root/app/src/test/kotlin/org/mcrf/client/$_.kt" })
$manifest = @($sources | ForEach-Object { [pscustomobject]@{ path = $_; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash } })
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath "$Output/source-manifest.json" -Encoding utf8
& $java -Xmx1g -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 "-Xfriend-paths=$friend" -classpath $deps -d "$Output/classes" @sources 2>&1 | Tee-Object "$Output/compile.log"
if ($LASTEXITCODE) { throw 'A/B Kotlin compilation failed' }
Push-Location $root
try {
    & $java -cp "$Output/classes;$deps" org.junit.runner.JUnitCore @($tests | ForEach-Object { "org.mcrf.client.$_" }) 2>&1 | Tee-Object "$Output/junit.log"
    if ($LASTEXITCODE) { throw 'A/B JUnit tests failed' }
    python -B -m unittest discover -s tests -p test_transport_comparison_host.py -v
    if ($LASTEXITCODE) { throw 'A/B host contracts failed' }
} finally { Pop-Location }
if (@($manifest | Where-Object { (Get-FileHash -LiteralPath $_.path -Algorithm SHA256).Hash -ne $_.sha256 }).Count) { throw 'Source changed during verification' }
Write-Output "A/B host-only evidence: $Output"
