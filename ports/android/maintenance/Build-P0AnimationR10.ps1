param()
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation build
$taskRoot=[IO.Path]::GetFullPath('D:/MCRF/Projects/MCRF')
$taskBase=[IO.Path]::GetFullPath('D:/MCRF/Builds/Android/p0-tls-ui-repair-9209bccf20ee45ddbbbd96b8231282e2')
$taskParent=Get-Content -LiteralPath "$taskBase/continuation-receipt.json" -Raw|ConvertFrom-Json
if($taskParent.state -cne 'PASS' -or !$taskParent.sourceStable -or $taskParent.sha256 -cne '897b0da523e2b4dda3bf23286fd4c77abdce7acc9d2c75e5adb1422fa773e75f'){throw 'Verified parent required'}
if(@(Get-Process -Name java,go -ErrorAction SilentlyContinue).Count){throw 'Existing producer must finish first'}
$taskRows=@(Get-Content -LiteralPath "$taskBase/source-manifest.json" -Raw|ConvertFrom-Json)
$taskApproved=Get-Content -LiteralPath "$taskRoot/ports/android/coordination/p0-40-reviewed-sources.json" -Raw|ConvertFrom-Json -AsHashtable
$taskStage=Join-Path 'D:/MCRF/Builds/Android' ('p0-animation-r10-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskStage|Out-Null
$taskManifest=@()
foreach($taskRow in $taskRows){
    $taskFrom=[IO.Path]::GetFullPath((Join-Path $taskBase $taskRow.path))
    $taskTo=[IO.Path]::GetFullPath((Join-Path $taskStage $taskRow.path))
    if(!$taskFrom.StartsWith($taskBase+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or !$taskTo.StartsWith($taskStage+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Get-FileHash -LiteralPath $taskFrom -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskRow.sha256){throw 'Frozen P0 parent mismatch'}
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($taskTo))|Out-Null
    [IO.File]::Copy($taskFrom,$taskTo,$false)
}
foreach($taskPath in $taskApproved.Keys){
    $taskItem=$taskApproved[$taskPath]
    $taskFrom=[IO.Path]::GetFullPath((Join-Path $taskRoot $taskItem.source))
    $taskTo=[IO.Path]::GetFullPath((Join-Path $taskStage $taskPath))
    if(!$taskFrom.StartsWith($taskRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or !$taskTo.StartsWith($taskStage+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Get-FileHash -LiteralPath $taskFrom -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskItem.sha256){throw 'Reviewed overlay mismatch'}
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($taskTo))|Out-Null
    [IO.File]::Copy($taskFrom,$taskTo,$true)
}
foreach($taskPath in @(@($taskRows.path)+@($taskApproved.Keys)|Sort-Object -Unique)){$taskManifest+=@{path=$taskPath;sha256=(Get-FileHash -LiteralPath (Join-Path $taskStage $taskPath) -Algorithm SHA256).Hash.ToLowerInvariant()}}
$taskManifest|ConvertTo-Json -Depth 4|Set-Content -LiteralPath "$taskStage/source-manifest.json" -Encoding utf8
$taskCurrentPath="$taskRoot/ports/android/coordination/current.json"
$taskCurrent=Get-Content -LiteralPath $taskCurrentPath -Raw|ConvertFrom-Json
if($taskCurrent.jobs.build -or $taskCurrent.activeBatch -cne 'p0-progress-2026-10-17.json'){throw 'Current batch/job mismatch'}
$taskResult=[ordered]@{state='STARTED';stage=$taskStage;parent=$taskBase;excludedP1=$true;versionCode=10010;testSignedOnly=$true;published=$false;sourceStable=$false;startedUtc=[DateTime]::UtcNow.ToString('o');scope='One source-ready P0 R10 batch: 3 affected JVM suites, R8 main and fresh isolated UI fixture. No native modules/publication.'}
$taskEnv=@{}
foreach($taskName in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$taskEnv[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
try{
    $taskCurrent.jobs.build="P0_ANIMATION_R10:$taskStage";$taskCurrent|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $taskCurrentPath -Encoding utf8
    $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk'
    $env:GRADLE_USER_HOME="$taskRoot/work/toolchains/gradle-home"
    $env:JAVA_TOOL_OPTIONS=($taskEnv.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
    Push-Location "$taskStage/ports/android"
    try{
        & "$env:JAVA_HOME/bin/java.exe" -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain ':app:testDebugUnitTest' '--tests' 'org.mcrf.client.ConnectionAppearanceTest' '--tests' 'org.mcrf.client.ConnectionMotionTest' '--tests' 'org.mcrf.client.ConnectionSceneAssetTest' ':app:assembleRelease' '-PtestRelease=true' '--offline' '--console=plain' *> "$taskStage/main-build.log"
        $taskResult.mainExit=$LASTEXITCODE
        if($LASTEXITCODE){throw 'Main/common affected checks failed; preserve log'}
        & "$env:JAVA_HOME/bin/java.exe" -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain ':app:assembleDebug' ':app:assembleDebugAndroidTest' '-PisolatedAnimationFixture=true' '-PisolatedAnimationFixtureGate=true' '--offline' '--console=plain' *> "$taskStage/fixture-build.log"
        $taskResult.fixtureExit=$LASTEXITCODE
        if($LASTEXITCODE){throw 'Fresh fixture build failed; preserve log'}
    }finally{Pop-Location}
    foreach($taskRow in $taskManifest){if((Get-FileHash -LiteralPath (Join-Path $taskStage $taskRow.path) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskRow.sha256){throw 'Inputs changed during build'}}
    $taskResult.sourceStable=$true
    $taskResult.unitTests=@()
    foreach($taskSuite in @('ConnectionAppearanceTest','ConnectionMotionTest','ConnectionSceneAssetTest')){
        [xml]$taskXml=Get-Content -Raw -LiteralPath "$taskStage/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.$taskSuite.xml"
        if([int]$taskXml.testsuite.tests -lt 1 -or [int]$taskXml.testsuite.failures -or [int]$taskXml.testsuite.errors -or [int]$taskXml.testsuite.skipped){throw 'Incomplete affected suite'}
        $taskResult.unitTests+=@{suite=$taskSuite;tests=[int]$taskXml.testsuite.tests}
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $taskResult.artifacts=@{}
    foreach($taskKind in @('main','fixture','runner')){
        $taskApk=switch($taskKind){'main'{"$taskStage/ports/android/app/build/outputs/apk/release/app-universal-release.apk"}'fixture'{"$taskStage/ports/android/app/build/outputs/apk/debug/app-universal-debug.apk"}'runner'{"$taskStage/ports/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"}}
        $taskCert=(& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --print-certs $taskApk 2>&1|Out-String)
        if($LASTEXITCODE -or $taskCert -notmatch 'certificate SHA-256 digest:\s*([0-9a-f]{64})' -or $Matches[1].ToLowerInvariant() -cne $taskParent.signer){throw 'Signer differs from installed parent'}
        $taskZip=[IO.Compression.ZipFile]::OpenRead($taskApk)
        try{if(@($taskZip.Entries|Where-Object {$_.FullName -match '^lib/|\.so$'}).Count){throw 'Native leaked into base/fixture'}}
        finally{$taskZip.Dispose()}
        $taskResult.artifacts[$taskKind]=@{apk=$taskApk;bytes=(Get-Item -LiteralPath $taskApk).Length;sha256=(Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
    $taskResult.signer=$taskParent.signer;$taskResult.state='PASS'
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{
    foreach($taskName in $taskEnv.Keys){[Environment]::SetEnvironmentVariable($taskName,$taskEnv[$taskName],'Process')}
    $taskResult.finishedUtc=[DateTime]::UtcNow.ToString('o');$taskResult|ConvertTo-Json -Depth 7|Set-Content -LiteralPath "$taskStage/receipt.json" -Encoding utf8
    $taskCurrent=Get-Content -LiteralPath $taskCurrentPath -Raw|ConvertFrom-Json
    if($taskCurrent.jobs.build -ceq "P0_ANIMATION_R10:$taskStage"){$taskCurrent.jobs.build=$null;$taskCurrent|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $taskCurrentPath -Encoding utf8}
    Write-Output "P0_ANIMATION_R10=$($taskResult.state);RECEIPT=$taskStage/receipt.json"
}
