param([string]$Output=(Join-Path 'D:/MCRF/Builds/Android' ('settings20-b-checks-'+[guid]::NewGuid().ToString('N'))),
      [switch]$ReviewPackage)
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
$android='C:/Users/dieonfire/AppData/Local/Android/Sdk/platforms/android-37.0/android.jar'
$zxing=Jar 'com.google.zxing/core/3.5.3'
$deps=@($json,$friend,$stdlib,$junit,$hamcrest,$zxing,$android,
 "$root/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes",
 "$root/app/build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar",
 "$root/primary-spi/build/libs/primary-spi.jar",
 "$root/primary-adapter/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar") -join ';'
$sources=@('MainActivity','ClientSettingsNavigation','ClientBehaviorSettings','ClientBehaviorUi','ClientTunUi',
 'ServersPage','LogsPage','SettingsSearchIndex','RoutingPage','ConditionProfiles','ConditionProfilesEnvironment',
 'ConditionProfilesUi','ChangeHistoryStore','ChangeHistoryDiff','NetworkProfileConfiguration','NetworkProfilesRuntime',
 'McrfApplication','McrfVpnService',
 # Current concurrently edited call-site dependencies, compiled only to resolve this closure.
 'TrafficCaptureActivity','TrafficCaptureService','TrafficCaptureRecorder','TrafficCapturePolicy','TrafficCaptureConfig',
 'ClientPrimaryHostPlatform','RoutingDefaults','RouteRules','TrafficJournalStore')|ForEach-Object {"$root/app/src/main/kotlin/org/mcrf/client/$_.kt"}
$tests=@('ClientBehaviorSettingsTest','RecoveryKillSwitchTest','ConditionProfilesTest','NetworkProfilesTest',
 'SettingsHistory20Test','BetaPersistenceDiagnosticsTest','BetaSettingsUiTest','ExperienceModeStoreTest',
 'ClientSettingsNavigationTest','SettingsSearchIndexTest','SettingsSearchTargetTest','SafeModeStoreTest',
 'ConnectionAppearanceTest','UnifiedAutoSelectionTest')
if($ReviewPackage) {
 $sources+=@(Get-ChildItem -LiteralPath "$root/app/src/main/kotlin/org/mcrf/client" -Filter '*.kt' -File |
   Where-Object LastWriteTime -ge ([datetime]'2026-10-31 19:39') | ForEach-Object FullName)
 $tests+=@('TrafficCaptureTest','TrafficCaptureUiTest','TrafficJournalDnsTest','QrCodecTest','QrPreviewTransformTest',
   'GeoDataRussiaTest','PresetRulesTest','RoutingPresetWorkflowTest','RouteEditorDraftTest','TelegramBridgeTest',
   'OpenFluxPinned0171Test','OpenFluxFreshCandidatePolicyReviewTest','OpenFluxFreshProfileReviewTest',
   'OpenFluxVerificationWireReviewTest','ConnectionPresentationTest','SettingsGroupResetTest')
 $tests=@($tests|Select-Object -Unique)
}
$sources+=@($tests|ForEach-Object {"$root/app/src/test/kotlin/org/mcrf/client/$_.kt"})
$sources=@($sources|ForEach-Object {(Resolve-Path -LiteralPath $_).Path}|Select-Object -Unique)
$null=New-Item -ItemType Directory -Path $Output
$manifest=@($sources|ForEach-Object {[pscustomobject]@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash}})
$manifest|ConvertTo-Json -Depth 4|Set-Content -LiteralPath "$Output/source-manifest.json" -Encoding utf8
# Compile only this source closure against retained dependencies. No Gradle/APK/native/device tasks.
& $java -Xmx2g -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 "-Xfriend-paths=$friend" -classpath $deps -d "$Output/classes" @sources 2>&1 | Tee-Object "$Output/kotlin-compile.log"
if($LASTEXITCODE){throw 'Affected Kotlin source compilation failed'}
Push-Location $root
try {
 & $java -cp "$Output/classes;$deps" org.junit.runner.JUnitCore @($tests|ForEach-Object {"org.mcrf.client.$_"}) 2>&1 | Tee-Object "$Output/kotlin-tests.log"
 if($LASTEXITCODE){throw 'Focused settings/history/network-profile tests failed'}
} finally {Pop-Location}
$drift=@($manifest|Where-Object {(Get-FileHash -LiteralPath $_.path -Algorithm SHA256).Hash -ne $_.sha256})
if($drift.Count){throw "Sources changed during focused verification: $($drift.path -join ', ')"}
Write-Output "Evidence: $Output"
