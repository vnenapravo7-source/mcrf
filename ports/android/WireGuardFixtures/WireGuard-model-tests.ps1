param(
    [string]$DependencySnapshot='',
    [string]$BuildScratchRoot=''
)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskRetention=Get-Content -LiteralPath (Join-Path $taskRoot 'ports/android/coordination/build-retention.json') -Raw|ConvertFrom-Json
if(!$DependencySnapshot){
    if(!$taskRetention.installedSnapshot){throw 'Pass -DependencySnapshot with a retained completed app snapshot.'}
    $DependencySnapshot=Join-Path 'work' $taskRetention.installedSnapshot
}
$taskCache=Join-Path $taskRoot 'work/toolchains/gradle-home/caches/modules-2/files-2.1'
function TaskJar([string]$path){(Get-ChildItem -Path (Join-Path $taskCache $path) -Filter '*.jar' -Recurse | Select-Object -First 1).FullName}
$taskCompiler=@((TaskJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10'),(TaskJar 'org.jetbrains.kotlin/kotlin-stdlib/2.2.10'),(TaskJar 'org.jetbrains.kotlin/kotlin-script-runtime/2.2.10'),(TaskJar 'org.jetbrains.kotlin/kotlin-reflect/1.6.10'),(TaskJar 'org.jetbrains.kotlin/kotlin-daemon-embeddable/2.2.10'),(TaskJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0'),(TaskJar 'org.jetbrains/annotations/13.0'))
$taskLibraries=@((TaskJar 'org.jetbrains.kotlin/kotlin-stdlib/2.2.10'),(TaskJar 'org.json/json/20250517'),(TaskJar 'junit/junit/4.13.2'),(TaskJar 'org.hamcrest/hamcrest-core/1.3'),(TaskJar 'org.jetbrains/annotations/13.0'))
$taskDependency=if([IO.Path]::IsPathRooted($DependencySnapshot)){[IO.Path]::GetFullPath($DependencySnapshot)}else{[IO.Path]::GetFullPath((Join-Path $taskRoot $DependencySnapshot))}
$taskPrior=Join-Path $taskDependency 'ports/android/app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes'
if(!(Test-Path -LiteralPath $taskPrior)){throw 'Coordinator compiled model classes required for WarpProfile dependencies; pure codec can be tested separately.'}
if(!$BuildScratchRoot){$BuildScratchRoot=$taskRetention.generatedScratchRoot}
if(![IO.Path]::IsPathRooted($BuildScratchRoot)){throw 'An absolute generated build scratch directory is required.'}
$taskScratch=[IO.Path]::GetFullPath($BuildScratchRoot)
New-Item -ItemType Directory -Path $taskScratch -Force|Out-Null
$taskOutput=Join-Path $taskScratch ('WireGuard-test-output-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskOutput | Out-Null
$taskSnapshot=Join-Path $taskOutput 'dependencies'
Copy-Item -LiteralPath $taskPrior -Destination $taskSnapshot -Recurse
$taskPrior=$taskSnapshot
$taskClasses=Join-Path $taskOutput 'classes'
New-Item -ItemType Directory -Path $taskClasses | Out-Null
$taskSources=Join-Path $taskRoot 'ports/android/app/src/main/kotlin/org/mcrf/client'
$taskTests=Join-Path $taskRoot 'ports/android/app/src/test/kotlin/org/mcrf/client'
$taskAndroid=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platforms/android-34/android.jar'
$taskBuilt=Join-Path $taskDependency 'ports/android/app/build/intermediates'
$taskR=Join-Path $taskBuilt 'compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar'
$taskJava=Join-Path $taskBuilt 'javac/debug/compileDebugJavaWithJavac/classes'
$taskFiles=@('WireGuardProfile.kt','WireGuardEditor.kt','WarpProfile.kt','WarpOperations.kt','WarpScanner.kt','WarpSettings.kt','WarpSettingsStore.kt','WarpPage.kt','WarpNetworkPolicy.kt','WarpController.kt','WarpControllerState.kt','WarpProbePlan.kt','WarpTraceTransport.kt','WarpTraceProbe.kt','WarpTraceService.kt','WarpTraceSession.kt') | ForEach-Object {Join-Path $taskSources $_}
$taskFiles+=@('WireGuardProfileTest.kt','WarpOperationsTest.kt','WarpProfileTest.kt','WarpSettingsTest.kt','WarpNetworkPolicyTest.kt','WarpControllerStateTest.kt','WarpProbePlanTest.kt','WarpTraceTransportTest.kt','WarpTraceSessionTest.kt') | ForEach-Object {Join-Path $taskTests $_}
# Current gate API is coordinator/engine-owned; compile it read-only against frozen dependencies.
$taskFiles+=Join-Path $taskSources 'EngineUpdateGate.kt'
$taskFiles+=@('WarpChildProcess.kt','WarpAppearance.kt','WarpRegistrationStore.kt') | ForEach-Object {Join-Path $taskSources $_}
$taskFiles+=@('WarpChildProcessTest.kt','WarpAppearanceTest.kt','WarpRegistrationStoreTest.kt') | ForEach-Object {Join-Path $taskTests $_}
# Compile the actual newly shared API read-only; no generated substitute worker or native runtime.
$taskFiles+=(Get-ChildItem -LiteralPath $taskSources -Filter 'ClientPrimary*.kt').FullName
$taskFiles+=@('EngineUpdatePrimary.kt','EnginePackages.kt','EnginePackage.kt','EngineModules.kt','EngineUpdateTelegram.kt','EngineImportUi.kt','DrainingCallbackExecutor.kt') | ForEach-Object {Join-Path $taskSources $_}
$taskSpi=Join-Path $taskRoot 'work/primary-spi-check-8b7e8d1577e64d809bd438dcf876a93b/classes'
$taskPolicy=Join-Path $taskRoot 'ports/android/primary-cores/singbox/audit/policy-classes'
$taskSteer=Join-Path $taskRoot 'work/steer-android-port/android-vpn/test-results/snapshot-adapter/android-signatures'
& java -cp ($taskCompiler -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath (($taskLibraries+@($taskSpi,$taskPolicy,$taskSteer,$taskPrior,$taskAndroid,$taskR,$taskJava)) -join ';') -d $taskClasses @taskFiles
if($LASTEXITCODE -ne 0){throw 'Isolated Kotlin compile failed'}
& java -cp ((@($taskClasses)+$taskLibraries+@($taskPrior)) -join ';') org.junit.runner.JUnitCore org.mcrf.client.WireGuardProfileTest org.mcrf.client.WarpOperationsTest org.mcrf.client.WarpProfileTest org.mcrf.client.WarpSettingsTest org.mcrf.client.WarpNetworkPolicyTest org.mcrf.client.WarpControllerStateTest org.mcrf.client.WarpProbePlanTest org.mcrf.client.WarpTraceTransportTest org.mcrf.client.WarpTraceSessionTest org.mcrf.client.WarpChildProcessTest org.mcrf.client.WarpAppearanceTest org.mcrf.client.WarpRegistrationStoreTest
if($LASTEXITCODE -ne 0){throw 'WG/WARP model tests failed'}
Write-Output "Isolated model output: $taskOutput (no Gradle/ADB; historical dependency classes only)"
