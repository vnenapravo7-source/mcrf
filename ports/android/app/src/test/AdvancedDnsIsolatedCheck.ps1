$ErrorActionPreference = 'Stop'
$dnsAndroidRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$dnsWorkspace = [IO.Path]::GetFullPath((Join-Path $dnsAndroidRoot '../..'))
$dnsJava = 'C:/Program Files/Java/jdk-17/bin/java.exe'
$dnsGradleHome = Join-Path $dnsWorkspace 'work/toolchains/gradle-home'
$dnsCompilerRoot = (Get-ChildItem (Join-Path $dnsGradleHome 'wrapper/dists/gradle-9.1.0-bin') -Directory | Select-Object -First 1).FullName + '/gradle-9.1.0/lib'
function Find-DnsJar([string]$relative) { return (Get-ChildItem (Join-Path $dnsGradleHome $relative) -Recurse -Filter '*.jar' | Select-Object -First 1).FullName }
$dnsJson = Find-DnsJar 'caches/modules-2/files-2.1/org.json/json/20250517'
$dnsJunit = Find-DnsJar 'caches/modules-2/files-2.1/junit/junit/4.13.2'
$dnsHamcrest = Find-DnsJar 'caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3'
$dnsOutput = Join-Path $dnsAndroidRoot 'app/build/dns-routing-isolated'
New-Item -ItemType Directory -Force -Path $dnsOutput | Out-Null
$dnsBaseline = Join-Path $dnsAndroidRoot 'app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes'
$dnsSdk = 'C:/Users/dieonfire/AppData/Local/Android/Sdk/platforms/android-34/android.jar'
# org.json must precede Android's throwing JSON stubs at runtime.
$dnsClasspath = "$dnsJson;$dnsOutput;$dnsBaseline;$dnsSdk;$dnsJunit;$dnsCompilerRoot/kotlin-stdlib-2.2.0.jar"
$dnsSources = Get-ChildItem (Join-Path $dnsAndroidRoot 'app/src/main/kotlin/org/mcrf/client') -Filter '*.kt' | Where-Object {
    $_.BaseName -in @('DnsConfig','DnsEditor','RouteRules','RoutingPage','RoutingStore','PresetAssets') -or $_.BaseName -match '^(AdvancedDns|GeoData|RemoteRouting|RoutingDefaults)'
} | Select-Object -ExpandProperty FullName
$dnsTests = Get-ChildItem (Join-Path $dnsAndroidRoot 'app/src/test/kotlin/org/mcrf/client') -Filter '*.kt' | Where-Object {$_.BaseName -in @('AdvancedDnsRoutingTest','RoutingDefaultsRussianAppsTest')} | Select-Object -ExpandProperty FullName
& $dnsJava -cp "$dnsCompilerRoot/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $dnsClasspath -d $dnsOutput @dnsSources @dnsTests
if ($LASTEXITCODE -ne 0) { throw 'DNS/routing isolated Kotlin compilation failed' }
$dnsOldTests = Join-Path $dnsAndroidRoot 'app/build/intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes'
$dnsFixtures = Join-Path $dnsWorkspace 'ports/fixtures'
& $dnsJava -cp "$dnsClasspath;$dnsHamcrest;$dnsOldTests;$dnsFixtures" org.junit.runner.JUnitCore org.mcrf.client.AdvancedDnsRoutingTest org.mcrf.client.RouteRulesTest org.mcrf.client.RoutingDefaultsRussianAppsTest
if ($LASTEXITCODE -ne 0) { throw 'DNS/routing isolated tests failed' }
