param([switch]$CompileUi)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskCache=Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
function Find-TestJar([string]$coordinate) {
    $matches=@(Get-ChildItem (Join-Path $taskCache $coordinate) -Recurse -Filter '*.jar')
    if($matches.Count -ne 1){throw "Expected one cached dependency: $coordinate"}
    return $matches[0].FullName
}
$taskStdlib=Find-TestJar 'org.jetbrains.kotlin/kotlin-stdlib/2.2.10'
$taskCompiler=@(
    (Find-TestJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10'),
    $taskStdlib,
    (Find-TestJar 'org.jetbrains.kotlin/kotlin-script-runtime/2.2.10'),
    (Find-TestJar 'org.jetbrains.kotlin/kotlin-reflect/2.2.10'),
    (Find-TestJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'),
    (Find-TestJar 'org.jetbrains/annotations/23.0.0')
)
$taskJunit=Find-TestJar 'junit/junit/4.13.2'
$taskHamcrest=Find-TestJar 'org.hamcrest/hamcrest-core/1.3'
$taskScratch=Join-Path ([IO.Path]::GetTempPath()) ('mcrf-update-tests-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskScratch | Out-Null
Push-Location $taskRoot
try {
    $taskSources=@(
        'app/src/main/kotlin/org/mcrf/client/EngineUpdateScreenPolicyUi.kt',
        'app/src/test/kotlin/org/mcrf/client/EngineUpdateScreenPolicyTest.kt',
        'app/src/test/kotlin/org/mcrf/client/EngineUpdateScreenWiringTest.kt'
    )
    & java '-Dfile.encoding=UTF-8' -cp ($taskCompiler -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$taskStdlib;$taskJunit;$taskHamcrest" -d "$taskScratch/tests" @taskSources
    if($LASTEXITCODE -ne 0){throw 'Focused Kotlin test compilation failed'}
    & java '-Dfile.encoding=UTF-8' -cp "$taskScratch/tests;$taskStdlib;$taskJunit;$taskHamcrest" org.junit.runner.JUnitCore org.mcrf.client.EngineUpdateScreenPolicyTest org.mcrf.client.EngineUpdateScreenWiringTest
    if($LASTEXITCODE -ne 0){throw 'Focused JUnit tests failed'}
    if($CompileUi) {
        # Read-only ABI check against previously built classes; no Gradle or APK.
        $taskClasses=(Resolve-Path 'app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes').Path
        $taskJava=(Resolve-Path 'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes').Path
        $taskSdk=if($env:ANDROID_SDK_ROOT){$env:ANDROID_SDK_ROOT}else{Join-Path $env:LOCALAPPDATA 'Android/Sdk'}
        $taskAndroid=(Resolve-Path (Join-Path $taskSdk 'platforms/android-37.0/android.jar')).Path
        $taskUiSources=@('EngineUpdateScreenPolicyUi.kt','EngineUpdatePrimaryUi.kt','EngineUpdateInventoryUi.kt','EngineUpdateMigrationUi.kt','EngineUpdateUi.kt','EngineUpdateOpenFluxScriptUi.kt','EngineUpdateAppUi.kt','AppUpdates.kt') |
            ForEach-Object {Join-Path 'app/src/main/kotlin/org/mcrf/client' $_}
        & java '-Dfile.encoding=UTF-8' -cp ($taskCompiler -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect "-Xfriend-paths=$taskClasses" -classpath "$taskStdlib;$taskClasses;$taskJava;$taskAndroid" -d "$taskScratch/ui" @taskUiSources
        if($LASTEXITCODE -ne 0){throw 'UI compilation against existing Android ABI failed'}
        Write-Output 'UI Kotlin compile against existing Android ABI: PASS (not a full app build)'
    }
    Write-Output "Test artifacts: $taskScratch"
}finally {Pop-Location}
