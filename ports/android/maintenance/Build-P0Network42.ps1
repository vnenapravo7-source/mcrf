param()
$ErrorActionPreference='Stop'
if($args.Count){throw 'Unknown arguments'}
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation build
$root='D:/MCRF/Projects/MCRF'
$parent='D:/MCRF/Builds/Android/p0-network42-log-1b2b8f59479c4bafab53a0f1c02eaa7c'
$hostParent='D:/MCRF/Builds/Android/p0-network42-60e32072817a449cb83d574866caf84c'
$baseline='D:/MCRF/Builds/Android/p0-item41-1c8a2f6a152b42b0b9ecb55128146dfb'
$prior=Get-Content "$parent/receipt.json" -Raw|ConvertFrom-Json
$hostEvidence=Get-Content "$hostParent/receipt.json" -Raw|ConvertFrom-Json
$installedParent=Get-Content "$baseline/receipt.json" -Raw|ConvertFrom-Json
if($prior.state -cne 'PASS_COMPILE_34_HOST_RETAINED' -or !$prior.sourceStable -or !$prior.excludedP1 -or $prior.retainedHostTests -ne 34){throw 'Exact final compiled P0 parent required'}
if($hostEvidence.state -cne 'PASS_HOST_AND_ANDROID_COMPILE_ONLY' -or !$hostEvidence.sourceStable -or ($hostEvidence.tests|Measure-Object tests -Sum).Sum -ne 34){throw 'Whole grouped34 evidence required'}
if($installedParent.state -cne 'PASS' -or $installedParent.versionCode -ne 10017 -or $installedParent.artifacts.universal.sha256 -cne '80b61bf1b31d646fb281cea38795d93d8246d7dda829b60c2b084def76404482'){throw 'Installed10017 signer parent required'}
$currentPath="$root/ports/android/coordination/current.json"
$current=Get-Content $currentPath -Raw|ConvertFrom-Json
if($current.jobs.build -or $current.jobs.hostTest -or $current.jobs.device){throw 'Continue existing job, do not duplicate'}
$stage='D:/MCRF/Builds/Android/p0-network42-apk-'+[guid]::NewGuid().ToString('N')
[IO.Directory]::CreateDirectory($stage)|Out-Null
$result=[ordered]@{state='PREPARING';stage=$stage;parent="$parent/receipt.json";versionCode=10018;versionName='0.1.0-preview.20261019.1';excludedP1=$true;sourceStable=$false;retainedHostTests=34;retainedHostReceipt="$hostParent/receipt.json";baseNative=0;testSignedOnly=$true;published=$false;installed=$false;startedUtc=[DateTime]::UtcNow.ToString('o');scope='Fresh human10018 build/install permission; exact passedP0snapshot +versionCode ONLY. R8release, no repeated34host tests/native/P1/fixture/modules/publication.'}
function Sha([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Safe([string]$base,[string]$relative){
    $path=[IO.Path]::GetFullPath((Join-Path $base $relative))
    if(!$path.StartsWith([IO.Path]::GetFullPath($base)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Snapshot path escape'}
    $path
}
function CanonicalStable {
    foreach($entry in $hostEvidence.canonicalInputs.PSObject.Properties){
        $expected=if($entry.Name -ceq 'ports/android/app/src/main/kotlin/org/mcrf/client/McrfVpnService.kt'){$prior.sourceSha}else{$entry.Value}
        if((Sha (Safe $root $entry.Name)) -cne $expected){throw 'Canonical source drift since passedP0 verification'}
    }
}
$envNames=@('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS');$oldEnv=@{}
foreach($name in $envNames){$oldEnv[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
    $current.jobs.build="P0_NETWORK42_APK:$stage";$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8
    CanonicalStable
    $manifest=@(Get-Content "$parent/source-manifest.json" -Raw|ConvertFrom-Json)
    foreach($row in $manifest){
        $from=Safe $parent $row.path;$to=Safe $stage $row.path
        if((Sha $from) -cne $row.sha256){throw 'Frozen parent mismatch'}
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($to))|Out-Null;[IO.File]::Copy($from,$to,$false)
    }
    $gradleFile='ports/android/app/build.gradle.kts';$path=Safe $stage $gradleFile
    $text=[IO.File]::ReadAllText($path)
    if([regex]::Matches($text,'versionCode = 10017').Count -ne 1){throw 'Exact parent version required'}
    $text=$text.Replace('versionCode = 10017','versionCode = 10018')
    $canonicalVersion=Sha (Safe $root $gradleFile)
    if([IO.File]::ReadAllText((Safe $root $gradleFile)).Replace("`r`n","`n") -cne $text.Replace("`r`n","`n")){throw 'Unrelated Gradle changes; no P1 build configuration copy'}
    [IO.File]::WriteAllText($path,$text,[Text.UTF8Encoding]::new($false));($manifest|Where-Object {$_.path -ceq $gradleFile}).sha256=Sha $path
    $manifest|ConvertTo-Json -Depth 4|Set-Content "$stage/source-manifest.json" -Encoding utf8
    $result.inputs=$manifest.Count;$result.canonicalVersionSha=$canonicalVersion;$result.retainedTests=@()
    foreach($suite in $hostEvidence.tests){
        [xml]$xml=Get-Content $suite.xml -Raw
        if([int]$xml.testsuite.tests -ne $suite.tests -or [int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped){throw 'Retained host evidence changed'}
        $result.retainedTests+=@{suite=$suite.suite;tests=$suite.tests;xml=$suite.xml;sha256=Sha $suite.xml}
    }
    $result.state='RUNNING_R8';$result|ConvertTo-Json -Depth 8|Set-Content "$stage/receipt.json" -Encoding utf8
    $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$root/work/toolchains/gradle-home"
    $env:JAVA_TOOL_OPTIONS=($oldEnv.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
    Push-Location "$stage/ports/android"
    try {& "$env:JAVA_HOME/bin/java.exe" -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain ':app:assembleRelease' '-PtestRelease=true' '--offline' '--console=plain' *> "$stage/build.log";$result.exitCode=$LASTEXITCODE;if($LASTEXITCODE){throw 'R8 build failed; log preserved'}}finally{Pop-Location}
    foreach($row in $manifest){if((Sha (Safe $stage $row.path)) -cne $row.sha256){throw 'Frozen input changed during build'}}
    CanonicalStable
    if((Sha (Safe $root $gradleFile)) -cne $canonicalVersion){throw 'Canonical version changed during build'}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $result.artifacts=@{}
    foreach($kind in @('universal','arm64-v8a','armeabi-v7a','x86_64')){
        $apk="$stage/ports/android/app/build/outputs/apk/release/app-$kind-release.apk"
        $cert=(& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --print-certs $apk 2>&1|Out-String)
        if($LASTEXITCODE -or $cert -notmatch 'certificate SHA-256 digest:\s*([0-9a-f]{64})' -or $Matches[1].ToLowerInvariant() -cne $installedParent.signer){throw 'Installed signer lineage mismatch'}
        $badging=(& "$env:ANDROID_HOME/build-tools/36.0.0/aapt.exe" dump badging $apk 2>&1|Out-String)
        if($LASTEXITCODE -or $badging -notmatch "package: name='org.mcrf.client' versionCode='10018'"){throw 'APK identity/version mismatch'}
        $zip=[IO.Compression.ZipFile]::OpenRead($apk);try{if(@($zip.Entries|Where-Object {$_.FullName -match '^lib/|\.so$'}).Count){throw 'Native leaked into base'}}finally{$zip.Dispose()}
        $result.artifacts[$kind]=@{apk=$apk;bytes=(Get-Item -LiteralPath $apk).Length;sha256=Sha $apk}
    }
    $result.signer=$installedParent.signer;$result.sourceStable=$true;$result.state='PASS'
}catch{$result.state='FAILED_PRESERVED';$result.error=$_.Exception.Message;throw}
finally {
    foreach($name in $oldEnv.Keys){[Environment]::SetEnvironmentVariable($name,$oldEnv[$name],'Process')}
    $result.finishedUtc=[DateTime]::UtcNow.ToString('o');$result|ConvertTo-Json -Depth 9|Set-Content "$stage/receipt.json" -Encoding utf8
    $current=Get-Content $currentPath -Raw|ConvertFrom-Json
    if($current.jobs.build -ceq "P0_NETWORK42_APK:$stage"){$current.jobs.build=$null;$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8}
    Write-Output "P0_NETWORK42_APK=$($result.state);RECEIPT=$stage/receipt.json"
}
