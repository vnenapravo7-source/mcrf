param([string]$JavaHome='C:/Program Files/Java/jdk-17')
$ErrorActionPreference='Stop'
$taskAndroid=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if($args.Count){throw 'Unknown sing-box source-check argument'}
& (Join-Path $taskAndroid 'maintenance/Assert-HumanBatchReady.ps1') -Operation test
$taskBatch=Get-Content -LiteralPath (Join-Path $taskAndroid 'coordination/current.json') -Raw|ConvertFrom-Json
if($taskBatch.activeBatch -ceq 'p2-progress-2026-10-20.json'){throw 'Use the common P2 adapter build and affected new fixtures; this historical wrapper reruns retained unchanged P1 groups'}
$taskRoot=Split-Path (Split-Path $taskAndroid -Parent) -Parent
$taskModules=Join-Path $taskRoot 'work/toolchains/gradle-home/caches/modules-2/files-2.1'
function TaskJar([string]$relative){(Get-ChildItem (Join-Path $taskModules $relative) -Filter '*.jar' -Recurse|Select-Object -First 1).FullName}
$taskCompiler=TaskJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10'
$taskStd=TaskJar 'org.jetbrains.kotlin/kotlin-stdlib/2.2.10'
$taskJson=TaskJar 'org.json/json/20250517'
$taskLib=(Resolve-Path (Join-Path $taskRoot 'work/toolchains/gradle-home/wrapper/dists/gradle-9.1.0-bin/*/gradle-9.1.0/lib')).Path
$taskSpi=Join-Path $taskRoot 'work/steer-android-port/android-vpn/module-contract/src/main/java/org/mcrf/engine/spi/v1'
$taskOut=Join-Path $PSScriptRoot 'audit/source-check-classes'
$taskJar=Join-Path $PSScriptRoot 'audit/source-libbox-classes.jar'
$taskInputPaths=@((Join-Path $taskSpi 'Engine.java'),(Join-Path $taskSpi 'Platform.java'),(Join-Path $taskAndroid 'app/libs/libbox.aar'),
    (Join-Path $taskAndroid 'app/src/main/java/org/mcrf/client/ClientPrimaryCorePolicy.java'),(Join-Path $taskAndroid 'app/src/main/kotlin/org/mcrf/client/ClientPrimaryCoreConfig.kt'),$PSCommandPath,(Join-Path $PSScriptRoot 'Check-Policy.ps1'))+
    @(Get-ChildItem (Join-Path $PSScriptRoot 'src/main/kotlin/org/mcrf/engines/singbox/*.kt'),(Join-Path $PSScriptRoot 'tests/*') -File|ForEach-Object FullName)
$taskHashes=@(foreach($taskInput in $taskInputPaths){[ordered]@{path=[IO.Path]::GetFullPath($taskInput);sha256=(Get-FileHash -LiteralPath $taskInput).Hash.ToLowerInvariant()}})
$taskResults=[Collections.Generic.List[string]]::new()
New-Item -ItemType Directory -Path $taskOut -Force|Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskAar=[IO.Compression.ZipFile]::OpenRead((Join-Path $taskAndroid 'app/libs/libbox.aar'))
try {[IO.Compression.ZipFileExtensions]::ExtractToFile($taskAar.GetEntry('classes.jar'),$taskJar,$true)} finally {$taskAar.Dispose()}
try {
    & (Join-Path $JavaHome 'bin/javac.exe') -d $taskOut (Join-Path $taskSpi 'Engine.java') (Join-Path $taskSpi 'Platform.java')
    if($LASTEXITCODE -ne 0){throw 'Canonical SPI compilation failed'}
    & (Join-Path $JavaHome 'bin/javac.exe') -classpath $taskOut -d $taskOut (Join-Path $PSScriptRoot 'tests/SingBoxDeferredRequestChecks.java')
    if($LASTEXITCODE -ne 0){throw 'Deferred constructor checks compilation failed'}
    $taskResult=& (Join-Path $JavaHome 'bin/java.exe') -classpath $taskOut org.mcrf.engines.singbox.SingBoxDeferredRequestChecks
    if($LASTEXITCODE -ne 0){throw 'Deferred constructor checks failed'}
    $taskResult|ForEach-Object {$taskResults.Add($_);Write-Output $_}
    $taskCp=@($taskStd,$taskJson,"$env:LOCALAPPDATA/Android/Sdk/platforms/android-34/android.jar",$taskOut,$taskJar) -join ';'
    $taskSources=@(Get-ChildItem (Join-Path $PSScriptRoot 'src/main/kotlin/org/mcrf/engines/singbox/*.kt'),(Join-Path $PSScriptRoot 'tests/*.kt')|ForEach-Object FullName)+@(Join-Path $taskAndroid 'app/src/main/kotlin/org/mcrf/client/ClientPrimaryCoreConfig.kt')
    & (Join-Path $JavaHome 'bin/java.exe') -cp "$taskCompiler;$taskLib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $taskCp -d $taskOut @taskSources
    if($LASTEXITCODE -ne 0){throw 'Real module sources compilation failed'}
    foreach($taskTest in @('SingBoxRuntimeConfigChecks','SingBoxCallbackGuardChecks','SingBoxMetricStateChecks','SingBoxDnsCompletionChecks','SingBoxTunProxyChecks')) {
        $taskResult=& (Join-Path $JavaHome 'bin/java.exe') -cp "$taskOut;$taskCp" "org.mcrf.engines.singbox.$taskTest"
        if($LASTEXITCODE -ne 0){throw "Failed $taskTest"}
        $taskResult|ForEach-Object {$taskResults.Add($_);Write-Output $_}
    }
    $taskResult=& (Join-Path $JavaHome 'bin/java.exe') -cp "$taskOut;$taskCp" org.mcrf.client.ClientPrimaryCoreConfigChecks
    if($LASTEXITCODE -ne 0){throw 'Host envelope wiring checks failed'}
    $taskResult|ForEach-Object {$taskResults.Add($_);Write-Output $_}
    $taskResult=& (Join-Path $PSScriptRoot 'Check-Policy.ps1') -JavaHome $JavaHome
    $taskResult|ForEach-Object {$taskResults.Add($_);Write-Output $_}
    foreach($taskHash in $taskHashes){if((Get-FileHash -LiteralPath $taskHash.path).Hash.ToLowerInvariant() -ne $taskHash.sha256){throw 'Check input changed; receipt refused'}}
    $taskCount=0
    foreach($taskLine in $taskResults){if($taskLine -match '^PASS ([0-9]+) '){$taskCount+=[int]$Matches[1]}else{throw 'Unexpected test output; receipt refused'}}
    if($taskResults.Count -ne 8){throw 'Missing test group; receipt refused'}
    $taskReceipt=[ordered]@{schema=1;state='passed';sourceStable=$true;checkedAtUtc=[DateTime]::UtcNow.ToString('o');checks=$taskCount;
        scope='Isolated real module source compile and host-only policy/model/concurrency guards; no Android/JNI/IPC/native traffic/package acceptance';groups=@($taskResults);inputs=$taskHashes}
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'audit/source-check-receipt.json'),($taskReceipt|ConvertTo-Json -Depth 6),(New-Object Text.UTF8Encoding($false)))
} finally {if(Test-Path -LiteralPath $taskJar){Remove-Item -LiteralPath $taskJar}}
