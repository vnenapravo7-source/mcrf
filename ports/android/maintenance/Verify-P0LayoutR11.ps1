param([string]$ResumeFailedSnapshot='')
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation test
$taskRoot='D:/MCRF/Projects/MCRF'
$taskBase='D:/MCRF/Builds/Android/p0-animation-r10-df9b7a15ab8a4f63ac5ab69f10b82f7f'
if($ResumeFailedSnapshot){
    $taskBase=[IO.Path]::GetFullPath($ResumeFailedSnapshot)
    if($taskBase -cnotmatch '^D:\\MCRF\\Builds\\Android\\p0-layout-r11-[a-f0-9]{32}$'){throw 'Unexpected repair parent'}
}
$taskParent=Get-Content -LiteralPath "$taskBase/receipt.json" -Raw|ConvertFrom-Json
if(!$taskParent.excludedP1){throw 'P1-excluded frozen parent required'}
if(!$ResumeFailedSnapshot -and ($taskParent.state -cne 'PASS' -or !$taskParent.sourceStable)){throw 'Verified frozen R10 parent required'}
if($ResumeFailedSnapshot -and $taskParent.state -cne 'FAILED_PRESERVED'){throw 'Preserved failed run required for targeted repair'}
if(@(Get-Process -Name java,go -ErrorAction SilentlyContinue).Count){throw 'Existing producer: resume it, do not duplicate'}
$taskCurrentPath="$taskRoot/ports/android/coordination/current.json"
$taskCurrent=Get-Content -LiteralPath $taskCurrentPath -Raw|ConvertFrom-Json
if($taskCurrent.jobs.build -or $taskCurrent.jobs.hostTest){throw 'Existing registered job'}
$taskStage=Join-Path 'D:/MCRF/Builds/Android' ('p0-layout-r11-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskStage|Out-Null
$taskRows=@(Get-Content -LiteralPath "$taskBase/source-manifest.json" -Raw|ConvertFrom-Json)
$taskHashes=[ordered]@{}
function TaskPath([string]$root,[string]$relative){
    $full=[IO.Path]::GetFullPath((Join-Path $root $relative))
    if(!$full.StartsWith([IO.Path]::GetFullPath($root)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Path outside exact snapshot'}
    return $full
}
foreach($row in $taskRows){
    $from=TaskPath $taskBase $row.path;$to=TaskPath $taskStage $row.path
    if((Get-FileHash -LiteralPath $from -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row.sha256){throw 'Frozen parent source mismatch'}
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($to))|Out-Null
    [IO.File]::Copy($from,$to,$false);$taskHashes[$row.path]=$row.sha256
}
$taskProduction=@('MainActivity','Loft','ConnectionAppearance','ConnectionAppearanceUi','Connections','ServersPage','ServerRow','AutoSelectSwitch','AdvancedDnsConfig','AdvancedDnsEditor','ClientTunUi','GeoDataEditor','OptionalCoreUi','ConnectionEditor','ClientSettingsNavigation')
$taskPaths=@($taskProduction|ForEach-Object {"ports/android/app/src/main/kotlin/org/mcrf/client/$_.kt"})+@(
    'ports/android/app/src/main/assets/connection-scene-r10.html',
    'ports/android/app/src/test/kotlin/org/mcrf/client/ConnectionAppearanceTest.kt',
    'ports/android/app/src/test/kotlin/org/mcrf/client/ConnectionSceneAssetTest.kt',
    'ports/android/app/src/test/kotlin/org/mcrf/client/LayoutDnsRound11Test.kt',
    'ports/android/app/src/test/kotlin/org/mcrf/client/ClientSettingsNavigationTest.kt',
    'ports/android/app/src/androidTest/kotlin/org/mcrf/client/ConnectionAnimationDeviceChecks.kt',
    'ports/android/maintenance/Test-CastingRound11.cjs')
$taskCanonical=[ordered]@{}
foreach($path in $taskPaths){
    $from=TaskPath $taskRoot $path;$to=TaskPath $taskStage $path
    $taskCanonical[$path]=(Get-FileHash -LiteralPath $from -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($to))|Out-Null
    if($path.EndsWith('/MainActivity.kt')){
        $text=[IO.File]::ReadAllText($from);$baseText=[IO.File]::ReadAllText((TaskPath $taskBase $path))
        $start='    private val primaryHost=';$end='    private fun offerPrimaryChoice'
        $a=$text.IndexOf($start);$z=$text.IndexOf($end,$a);$b=$baseText.IndexOf($start);$e=$baseText.IndexOf($end,$b)
        if($a -lt 0 -or $z -le $a -or $b -lt 0 -or $e -le $b){throw 'P1 host exclusion boundaries changed'}
        $text=$text.Substring(0,$a)+$baseText.Substring($b,$e-$b)+$text.Substring($z)
        [IO.File]::WriteAllText($to,$text,[Text.UTF8Encoding]::new($false))
    }elseif($path.EndsWith('/GeoDataEditor.kt')){
        # Canonical has a separate unfinished P1 Steer geo-preparation block. Apply
        # ONLY the requested link correction to the frozen P0 copy, never that block.
        $text=[IO.File]::ReadAllText((TaskPath $taskBase $path));$needle='form.flush();navigate(ClientSettingsNavigation.ADVANCED)'
        if(!$ResumeFailedSnapshot -and $text.Split($needle,[StringSplitOptions]::None).Count -ne 2){throw 'Expected exactly one P0 Geo advanced link'}
        if($ResumeFailedSnapshot -and !$text.Contains('form.flush();openAdvanced("advanced-geo")')){throw 'Missing preserved R11 Geo link'}
        $text=$text.Replace($needle,'form.flush();openAdvanced("advanced-geo")')
        [IO.File]::WriteAllText($to,$text,[Text.UTF8Encoding]::new($false))
    }else{[IO.File]::Copy($from,$to,$true)}
    $taskHashes[$path]=(Get-FileHash -LiteralPath $to -Algorithm SHA256).Hash.ToLowerInvariant()
}
$taskManifest=@($taskHashes.Keys|ForEach-Object {@{path=$_;sha256=$taskHashes[$_]}})
if($ResumeFailedSnapshot){
    foreach($row in $taskRows){
        if($row.path -cne 'ports/android/app/src/test/kotlin/org/mcrf/client/ClientSettingsNavigationTest.kt' -and $taskHashes[$row.path] -cne $row.sha256){throw 'Targeted repair changed more than stale navigation test; require a new complete batch'}
    }
    foreach($suite in @('ConnectionAppearanceTest','ConnectionSceneAssetTest','LayoutDnsRound11Test','AdvancedDnsRoutingTest')){
        [xml]$xml=Get-Content -Raw -LiteralPath "$taskBase/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.$suite.xml"
        if([int]$xml.testsuite.tests -lt 1 -or [int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped){throw 'Cannot retain incomplete affected evidence'}
    }
}
$taskManifest|ConvertTo-Json -Depth 4|Set-Content -LiteralPath "$taskStage/source-manifest.json" -Encoding utf8
$taskCanonical|ConvertTo-Json -Depth 4|Set-Content -LiteralPath "$taskStage/canonical-inputs.json" -Encoding utf8
$taskResult=[ordered]@{state='STARTED';parent=$taskBase;stage=$taskStage;excludedP1=$true;sourceStable=$false;apkBuilt=$false;deviceMutated=$false;startedUtc=[DateTime]::UtcNow.ToString('o');scope='Whole R11 UI/DNS/navigation + casting-stop batch. Compile and affected JVM + casting dispatch only; no APK/phone/closed motion or network suites.'}
if($ResumeFailedSnapshot){$taskResult.retainedEvidence="$taskBase/ports/android/app/build/test-results/testDebugUnitTest";$taskResult.repair='Only outdated navigation test assertion; production and all other frozen inputs byte-identical. Four passed affected suites retained without rerun.'}
$taskEnvironment=@{}
foreach($name in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$taskEnvironment[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
    $taskCurrent.jobs.hostTest="P0_LAYOUT_R11:$taskStage";$taskCurrent|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $taskCurrentPath -Encoding utf8
    $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk'
    $env:GRADLE_USER_HOME="$taskRoot/work/toolchains/gradle-home"
    $env:JAVA_TOOL_OPTIONS=($taskEnvironment.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
    Push-Location "$taskStage/ports/android"
    try {
        $gradleArgs=@(':app:testDebugUnitTest','--tests','org.mcrf.client.ClientSettingsNavigationTest')
        if(!$ResumeFailedSnapshot){$gradleArgs+=@('--tests','org.mcrf.client.ConnectionAppearanceTest','--tests','org.mcrf.client.ConnectionSceneAssetTest','--tests','org.mcrf.client.LayoutDnsRound11Test','--tests','org.mcrf.client.AdvancedDnsRoutingTest',':app:compileDebugAndroidTestKotlin')}
        & "$env:JAVA_HOME/bin/java.exe" -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain @gradleArgs '--offline' '--console=plain' *> "$taskStage/grouped-checks.log"
        $taskResult.exitCode=$LASTEXITCODE
        if($LASTEXITCODE){throw 'Grouped compile/affected checks failed; inspect preserved log'}
    }finally{Pop-Location}
    & 'C:/Users/dieonfire/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe' "$taskStage/ports/android/maintenance/Test-CastingRound11.cjs" "$taskStage/ports/android/app/src/main/assets/connection-scene-r10.html" *> "$taskStage/casting-check.log"
    $taskResult.castingExit=$LASTEXITCODE
    if($LASTEXITCODE){throw 'Casting dispatch check failed; preserve log'}
    $taskResult.tests=@()
    foreach($suite in @('ConnectionAppearanceTest','ConnectionSceneAssetTest','LayoutDnsRound11Test','AdvancedDnsRoutingTest','ClientSettingsNavigationTest')){
        $suiteRoot=if($ResumeFailedSnapshot -and $suite -cne 'ClientSettingsNavigationTest'){$taskBase}else{$taskStage}
        [xml]$xml=Get-Content -Raw -LiteralPath "$suiteRoot/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.$suite.xml"
        if([int]$xml.testsuite.tests -lt 1 -or [int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped){throw 'Incomplete affected suite'}
        $taskResult.tests+=@{suite=$suite;tests=[int]$xml.testsuite.tests}
    }
    foreach($row in $taskManifest){if((Get-FileHash -LiteralPath (TaskPath $taskStage $row.path) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row.sha256){throw 'Frozen inputs changed during verification'}}
    foreach($path in $taskCanonical.Keys){if((Get-FileHash -LiteralPath (TaskPath $taskRoot $path) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskCanonical[$path]){throw 'Canonical batch changed during verification; result cannot cover newest edits'}}
    $taskResult.sourceStable=$true;$taskResult.state='PASS'
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally {
    foreach($name in $taskEnvironment.Keys){[Environment]::SetEnvironmentVariable($name,$taskEnvironment[$name],'Process')}
    $taskResult.finishedUtc=[DateTime]::UtcNow.ToString('o');$taskResult|ConvertTo-Json -Depth 7|Set-Content -LiteralPath "$taskStage/receipt.json" -Encoding utf8
    $taskCurrent=Get-Content -LiteralPath $taskCurrentPath -Raw|ConvertFrom-Json
    if($taskCurrent.jobs.hostTest -ceq "P0_LAYOUT_R11:$taskStage"){$taskCurrent.jobs.hostTest=$null;$taskCurrent|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $taskCurrentPath -Encoding utf8}
    Write-Output "P0_LAYOUT_R11=$($taskResult.state);RECEIPT=$taskStage/receipt.json"
}
