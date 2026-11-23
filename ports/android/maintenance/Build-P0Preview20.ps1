param()
$ErrorActionPreference='Stop'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation build
$root='D:/MCRF/Projects/MCRF';$parent='D:/MCRF/Builds/Android/p0-handover19-9123c778352649e898d97c34078837e9'
$prior=Get-Content "$parent/receipt.json" -Raw|ConvertFrom-Json
if($prior.state -cne 'PASS' -or !$prior.sourceStable -or !$prior.excludedP1 -or $prior.versionCode -ne 10019 -or $prior.artifacts.universal.sha256 -cne '5888a20953f8b19aa84f0a29953960747954216bf6facd2c66644ea97b299de6'){throw 'Exact passed19 parent required'}
$currentPath="$root/ports/android/coordination/current.json";$current=Get-Content $currentPath -Raw|ConvertFrom-Json
if($current.jobs.build -or $current.jobs.hostTest -or $current.jobs.device){throw 'Existing job; no duplicate'}
$stage='D:/MCRF/Builds/Android/p0-preview20-'+[guid]::NewGuid().ToString('N');[IO.Directory]::CreateDirectory($stage)|Out-Null
$manifest=[ordered]@{};$canonical=[ordered]@{}
$result=[ordered]@{state='PREPARING';stage=$stage;parent="$parent/receipt.json";versionCode=10020;versionName='0.1.0-preview.20261019.2';sourceStable=$false;excludedP1=$true;baseNative=0;retainedPriorHostTests=40;published=$false;installed=$false;startedUtc=[DateTime]::UtcNow.ToString('o');authority='Fresh human NEW prerelease plus phonecurrent plus engineupdate offer. Exact-app-preview singbox discovery only, consent/non-downgrade retained. No native/P1 builds.'}
function Sha([string]$p){(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant()}
function Safe([string]$base,[string]$relative){$p=[IO.Path]::GetFullPath((Join-Path $base $relative));if(!$p.StartsWith([IO.Path]::GetFullPath($base)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Path escape'};$p}
function CopyLive([string]$p){$from=Safe $root $p;$to=Safe $stage $p;$canonical[$p]=Sha $from;[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($to))|Out-Null;[IO.File]::Copy($from,$to,$true);$manifest[$p]=Sha $to}
function Stable {foreach($p in $canonical.Keys){if((Sha (Safe $root $p)) -cne $canonical[$p]){throw 'Canonical drift'}};foreach($p in $manifest.Keys){if((Sha (Safe $stage $p)) -cne $manifest[$p]){throw 'Frozen drift'}}}
$envNames=@('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS');$oldEnv=@{};foreach($name in $envNames){$oldEnv[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
    $current.jobs.build="P0_PREVIEW20:$stage";$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8
    foreach($row in @(Get-Content "$parent/source-manifest.json" -Raw|ConvertFrom-Json)){
        $from=Safe $parent $row.path;$to=Safe $stage $row.path;if((Sha $from) -cne $row.sha256){throw 'Parent drift'}
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($to))|Out-Null;[IO.File]::Copy($from,$to,$false);$manifest[$row.path]=$row.sha256
    }
    foreach($name in @('EnginePackage','EngineStartupPolicy','EngineStartupUpdates','EnginePackages','EnginePreviewChannel')){CopyLive "ports/android/app/src/main/kotlin/org/mcrf/client/$name.kt"}
    CopyLive 'ports/android/app/src/test/kotlin/org/mcrf/client/EnginePreviewChannelTest.kt'
    $gradle='ports/android/app/build.gradle.kts';$old=[IO.File]::ReadAllText((Safe $stage $gradle)).Replace("`r`n","`n")
    $expected=$old.Replace('versionCode = 10019','versionCode = 10020').Replace('versionName = "0.1.0-preview.20261019.1"','versionName = "0.1.0-preview.20261019.2"')
    if($old -ceq $expected -or [IO.File]::ReadAllText((Safe $root $gradle)).Replace("`r`n","`n") -cne $expected){throw 'Unexpected build configuration/P1 change'}
    CopyLive $gradle
    $rows=@($manifest.Keys|ForEach-Object {@{path=$_;sha256=$manifest[$_]}})
    [IO.File]::WriteAllText("$stage/source-manifest.json",($rows|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
    $result.canonicalInputs=$canonical;$result.inputs=$rows.Count;$result.state='RUNNING_GROUPED_DISCOVERY_HOST_COMPILE_R8';$result|ConvertTo-Json -Depth 9|Set-Content "$stage/receipt.json" -Encoding utf8
    Stable
    $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$root/work/toolchains/gradle-home"
    $env:JAVA_TOOL_OPTIONS=($oldEnv.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
    $suites=@('EnginePreviewChannelTest','EnginePackageTest','EngineStartupPolicyTest','AppReleaseTest','AppUpdatePresentationTest')
    $gradleArgs=@(':app:testDebugUnitTest');foreach($suite in $suites){$gradleArgs+=@('--tests',"org.mcrf.client.$suite")}
    $gradleArgs+=@(':app:compileDebugAndroidTestKotlin',':app:assembleRelease','-PtestRelease=true','--offline','--console=plain')
    Push-Location "$stage/ports/android"
    try {& "$env:JAVA_HOME/bin/java.exe" -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain @gradleArgs *> "$stage/build.log";$result.exitCode=$LASTEXITCODE;if($LASTEXITCODE){throw 'Grouped verification failed; inspect preserved log'}}finally{Pop-Location}
    $result.tests=@()
    foreach($suite in $suites){$p="$stage/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.$suite.xml";[xml]$xml=Get-Content $p -Raw
        if([int]$xml.testsuite.tests -le 0 -or [int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped){throw 'Incomplete host suite'}
        if($suite -ceq 'EnginePreviewChannelTest' -and [int]$xml.testsuite.tests -ne 10){throw 'Incomplete new10 tests'}
        $result.tests+=@{suite=$suite;tests=[int]$xml.testsuite.tests;xml=$p;sha256=Sha $p}}
    Add-Type -AssemblyName System.IO.Compression.FileSystem;$result.artifacts=@{}
    foreach($abi in @('universal','arm64-v8a','armeabi-v7a','x86_64')){
        $apk="$stage/ports/android/app/build/outputs/apk/release/app-$abi-release.apk"
        $cert=(& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --print-certs $apk 2>&1|Out-String)
        if($LASTEXITCODE -or $cert -notmatch 'certificate SHA-256 digest:\s*([a-f0-9]{64})' -or $Matches[1] -cne $prior.signer){throw 'Signer drift'}
        $badging=(& "$env:ANDROID_HOME/build-tools/36.0.0/aapt.exe" dump badging $apk 2>&1|Out-String)
        if($LASTEXITCODE -or $badging -notmatch "package: name='org.mcrf.client' versionCode='10020' versionName='0.1.0-preview.20261019.2'"){throw 'APK identity drift'}
        $zip=[IO.Compression.ZipFile]::OpenRead($apk);try{if(@($zip.Entries|Where-Object {$_.FullName -match '^lib/|\.so$'}).Count){throw 'Native leaked into main'}}finally{$zip.Dispose()}
        $result.artifacts[$abi]=@{apk=$apk;bytes=(Get-Item $apk).Length;sha256=Sha $apk}
    }
    Stable;$result.signer=$prior.signer;$result.sourceStable=$true;$result.state='PASS'
}catch{$result.state='FAILED_PRESERVED';$result.error=$_.Exception.Message;throw}
finally {
    foreach($name in $oldEnv.Keys){[Environment]::SetEnvironmentVariable($name,$oldEnv[$name],'Process')}
    $result.finishedUtc=[DateTime]::UtcNow.ToString('o');$result|ConvertTo-Json -Depth 10|Set-Content "$stage/receipt.json" -Encoding utf8
    $current=Get-Content $currentPath -Raw|ConvertFrom-Json;if($current.jobs.build -ceq "P0_PREVIEW20:$stage"){$current.jobs.build=$null;$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8}
    "PREVIEW20=$($result.state);RECEIPT=$stage/receipt.json"
}
