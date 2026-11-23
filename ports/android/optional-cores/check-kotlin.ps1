param([string]$BuildScratchRoot='',[switch]$PureOnly)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskModules=Join-Path $taskRoot 'work/toolchains/gradle-home/caches/modules-2/files-2.1'
function OptionalJar([string]$path){(Get-ChildItem -LiteralPath (Join-Path $taskModules $path) -Filter '*.jar' -Recurse -File | Select-Object -First 1).FullName}
$taskCompiler=OptionalJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10'
$taskStd=OptionalJar 'org.jetbrains.kotlin/kotlin-stdlib/2.2.10'
$taskGradleLib=(Resolve-Path "$taskRoot/work/toolchains/gradle-home/wrapper/dists/gradle-9.1.0-bin/*/gradle-9.1.0/lib").Path
$taskClasses=Join-Path $taskRoot 'ports/android/app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes'
$taskCp=@($taskStd,(OptionalJar 'org.json/json/20250517'),(OptionalJar 'junit/junit/4.13.2'),(OptionalJar 'org.hamcrest/hamcrest-core/1.3'),"$env:LOCALAPPDATA/Android/Sdk/platforms/android-34/android.jar",$taskClasses,"$taskRoot/ports/android/app/build/CoreTuning-isolated") -join ';'
$taskSources=@(Get-ChildItem "$taskRoot/ports/android/app/src/main/kotlin/org/mcrf/client/OptionalCore*.kt","$taskRoot/ports/android/app/src/main/kotlin/org/mcrf/client/Xray*.kt","$taskRoot/ports/android/app/src/test/kotlin/org/mcrf/client/OptionalCore*.kt" | ForEach-Object FullName)
$taskSources+=@(Get-ChildItem "$taskRoot/ports/android/app/src/androidTest/kotlin/org/mcrf/client/OptionalCore*.kt" | ForEach-Object FullName)
if(!$BuildScratchRoot){$BuildScratchRoot=Join-Path ((Get-Content -LiteralPath "$PSScriptRoot/../coordination/build-retention.json" -Raw|ConvertFrom-Json).generatedScratchRoot) 'optional-cores'}
$taskOutput=Join-Path $BuildScratchRoot ('kotlin-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $taskOutput|Out-Null
$taskJava='C:/Program Files/Java/jdk-17/bin/java.exe'
& $taskJava -cp "$taskCompiler;$taskGradleLib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $taskCp -d $taskOutput @taskSources
if($LASTEXITCODE -ne 0){throw 'Optional core Kotlin compilation failed'}
& $taskJava "-Djava.io.tmpdir=$taskOutput" -cp "$taskOutput;$taskCp" org.junit.runner.JUnitCore org.mcrf.client.OptionalCoreTest org.mcrf.client.OptionalCorePlanTest org.mcrf.client.OptionalCoreProbeTest org.mcrf.client.OptionalCoreTuningTest | Tee-Object -FilePath "$taskOutput/junit.txt"
if($LASTEXITCODE -ne 0){throw 'Optional core Kotlin tests failed'}
$taskFixtures=Join-Path $taskOutput 'fixtures'
if(!$PureOnly){
& $taskJava -cp "$taskOutput;$taskCp" org.mcrf.client.OptionalCoreTuningFixtures $taskFixtures
if($LASTEXITCODE -ne 0){throw 'Optional transport fixture generation failed'}
$taskCandidate=if(Test-Path "$PSScriptRoot/current-candidate.json"){(Get-Content "$PSScriptRoot/current-candidate.json" -Raw|ConvertFrom-Json).attempt}else{Join-Path $PSScriptRoot 'build/attempt-04a69c6472424ef2872b865fa896cde9'}
$taskNative=Join-Path $taskCandidate 'optional-cores/build'
foreach($taskKind in @('xray','tls-core')) {
    & (Join-Path $taskNative "$taskKind.exe") --mcrf-check (Join-Path $taskFixtures "$taskKind.json") | Tee-Object -FilePath "$taskOutput/native-checks.txt" -Append
    if($LASTEXITCODE -ne 0){throw "Exact Kotlin-produced native tuning configuration failed: $taskKind"}
}
foreach($taskFixture in Get-ChildItem -LiteralPath $taskFixtures -Filter 'xray-*.json' -File) {
    & (Join-Path $taskNative 'xray.exe') --mcrf-check $taskFixture.FullName | Tee-Object -FilePath "$taskOutput/native-checks.txt" -Append
    if($LASTEXITCODE -ne 0){throw "Kotlin transport mapping failed: $($taskFixture.Name)"}
}
}
$taskTestCount=[regex]::Match((Get-Content "$taskOutput/junit.txt" -Raw),'OK \((\d+) tests\)').Groups[1].Value
if(!$taskTestCount){throw 'Missing successful JUnit receipt'}
$taskConfigCount=if($PureOnly){0}else{(Get-ChildItem -LiteralPath $taskFixtures -Filter '*.json' -File).Count}
@{tests=[int]$taskTestCount;nativeConfigs=$taskConfigCount;pureOnly=[bool]$PureOnly;sourceFiles=@($taskSources|ForEach-Object {@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}});androidDeviceTested=$false} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$taskOutput/receipt.json" -Encoding utf8
@{attempt=$taskOutput;receipt="$taskOutput/receipt.json"}|ConvertTo-Json|Set-Content -LiteralPath "$PSScriptRoot/current-kotlin.json" -Encoding utf8
