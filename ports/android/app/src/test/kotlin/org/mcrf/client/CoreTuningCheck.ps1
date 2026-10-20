param([string]$Root = 'D:/MCRF/Projects/MCRF')
$ErrorActionPreference = 'Stop'
Push-Location $Root
try {
    $tunModules = Join-Path $Root 'work/toolchains/gradle-home/caches/modules-2/files-2.1'
    function TunJar([string]$path, [string]$pattern = '*.jar') {
        (Get-ChildItem -LiteralPath (Join-Path $tunModules $path) -Filter $pattern -Recurse -File | Select-Object -First 1).FullName
    }
    $tunLib = (Resolve-Path 'work/toolchains/gradle-home/wrapper/dists/gradle-9.1.0-bin/*/gradle-9.1.0/lib').Path
    $tunCompiler = TunJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10'
    $tunStd = TunJar 'org.jetbrains.kotlin/kotlin-stdlib/2.2.10'
    $tunBox = (Resolve-Path 'work/toolchains/gradle-home/caches/9.1.0/transforms/0ad797573b7807a427a73e78a38fb2a8/workspace/transformed/libbox-api.jar').Path
    $tunCp = @($tunStd,(TunJar 'org.json/json/20250517'),(TunJar 'junit/junit/4.13.2'),(TunJar 'org.hamcrest/hamcrest-core/1.3'),$tunBox,"$env:LOCALAPPDATA/Android/Sdk/platforms/android-34/android.jar",'ports/android/app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes') -join ';'
    $tunSources = @(Get-ChildItem 'ports/android/app/src/main/kotlin/org/mcrf/client/ClientTun*.kt','ports/android/app/src/main/kotlin/org/mcrf/client/CoreTuning*.kt','ports/android/app/src/test/kotlin/org/mcrf/client/ClientTun*.kt','ports/android/app/src/test/kotlin/org/mcrf/client/CoreTuning*.kt' | ForEach-Object FullName)
    $tunOutput = 'ports/android/app/build/CoreTuning-isolated'
    $tunJava = 'C:/Program Files/Java/jdk-17/bin/java.exe'
    & $tunJava -cp "$tunCompiler;$tunLib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $tunCp -d $tunOutput @tunSources
    if($LASTEXITCODE -ne 0) { throw 'Isolated compilation failed' }
    & $tunJava -cp "$tunOutput;$tunCp" org.junit.runner.JUnitCore org.mcrf.client.ClientTunTest org.mcrf.client.CoreTuningTest
    if($LASTEXITCODE -ne 0) { throw 'Unit tests failed' }
    & $tunJava -cp "$tunOutput;$tunCp" org.mcrf.client.CoreTuningNativeFixtures 'ports/android/app/build/CoreTuning-fixtures'
    if($LASTEXITCODE -ne 0) { throw 'Fixture generation failed' }
    foreach($tunFixture in Get-ChildItem 'ports/android/app/build/CoreTuning-fixtures/CoreTuning-*.json') {
        $tunResult = & 'work/linux-tests/host-tools/sing-box.exe' check -c $tunFixture.FullName 2>&1
        $tunCode = $LASTEXITCODE
        $tunNegative = $tunFixture.Name -like '*unsupported*'
        Write-Output "$($tunFixture.Name): exit=$tunCode $tunResult"
        if(($tunNegative -and $tunCode -eq 0) -or (!$tunNegative -and $tunCode -ne 0)) { throw 'Unexpected native schema result' }
    }
} finally { Pop-Location }
