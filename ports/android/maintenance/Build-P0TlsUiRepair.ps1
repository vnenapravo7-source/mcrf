param()
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation build
$taskRoot='D:/MCRF/Projects/MCRF'
$taskBase=Join-Path $taskRoot 'work/android-integration-a6c52ec6f97149f4835a629f1a450f43'
$taskRows=@(Get-Content -LiteralPath "$taskBase/source-manifest.json" -Raw|ConvertFrom-Json)
if($taskRows.Count -ne 875 -or @(Get-Process -Name java,go -ErrorAction SilentlyContinue).Count){throw 'Exact frozen P0 inputs and idle producer required'}
$taskApproved=Get-Content -Raw -LiteralPath (Join-Path $taskRoot 'ports/android/coordination/tls-ui-repair-reviewed-sources.json')|ConvertFrom-Json -AsHashtable
if($taskApproved.Count -ne 6){throw 'Exact reviewed TLS/UI overlay required'}
foreach($taskPath in $taskApproved.Keys){if((Get-FileHash -LiteralPath (Join-Path $taskRoot $taskPath) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskApproved[$taskPath]){throw 'Reviewed canonical source changed before snapshot'}}
$taskStage=Join-Path 'D:/MCRF/Builds/Android' ('p0-tls-ui-repair-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskStage|Out-Null
$taskManifest=@()
foreach($taskRow in $taskRows){
    $taskSource=[IO.Path]::GetFullPath((Join-Path $taskBase $taskRow.path))
    $taskTarget=[IO.Path]::GetFullPath((Join-Path $taskStage $taskRow.path))
    if(!$taskSource.StartsWith($taskBase+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or !$taskTarget.StartsWith($taskStage+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Get-FileHash -LiteralPath $taskSource -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskRow.sha256){throw 'Parent P0 manifest/input mismatch'}
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($taskTarget))|Out-Null
    [IO.File]::Copy($(if($taskApproved.ContainsKey($taskRow.path)){Join-Path $taskRoot $taskRow.path}else{$taskSource}),$taskTarget,$false)
    $taskManifest+=@{path=$taskRow.path;sha256=(Get-FileHash -LiteralPath $taskTarget -Algorithm SHA256).Hash.ToLowerInvariant()}
}
foreach($taskPath in $taskApproved.Keys){
    if($taskPath -notin @($taskRows.path)){
        $taskTarget=Join-Path $taskStage $taskPath
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($taskTarget))|Out-Null
        [IO.File]::Copy((Join-Path $taskRoot $taskPath),$taskTarget,$false)
        $taskManifest+=@{path=$taskPath;sha256=$taskApproved[$taskPath]}
    }
}
[IO.File]::WriteAllText("$taskStage/source-manifest.json",($taskManifest|ConvertTo-Json -Depth 3),[Text.UTF8Encoding]::new($false))
$taskEnvironment=@{}
foreach($taskName in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$taskEnvironment[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
$taskCurrentPath=Join-Path $taskRoot 'ports/android/coordination/current.json'
$taskCurrent=Get-Content -LiteralPath $taskCurrentPath -Raw|ConvertFrom-Json
if($taskCurrent.jobs.build -or $taskCurrent.activeBatch -cne 'p0-progress-2026-10-17.json'){throw 'Another build/current priority owns execution'}
$taskResult=[ordered]@{state='STARTED';parent=$taskBase;stage=$taskStage;changedSource=@($taskApproved.Keys);excludedP1=$true;mainVersionCode=10009;testSignedOnly=$true;published=$false;sourceStable=$false;startedUtc=[DateTime]::UtcNow.ToString('o');scope='Human-authorized common TLS compatibility + ModeSwitch UI repair; two affected JVM suites + R8/baseNative only; no module rebuild/publication/phone action'}
try{
    $taskCurrent.jobs.build="P0_TLS_UI_REPAIR:$taskStage"
    [IO.File]::WriteAllText($taskCurrentPath,($taskCurrent|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk'
    $env:GRADLE_USER_HOME=Join-Path $taskRoot 'work/toolchains/gradle-home'
    $env:JAVA_TOOL_OPTIONS=($taskEnvironment.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
    Push-Location "$taskStage/ports/android"
    try{& "$env:JAVA_HOME/bin/java.exe" -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain ':app:testDebugUnitTest' '--tests' 'org.mcrf.client.ConnectionAddressFamilyTest' '--tests' 'org.mcrf.client.CarrierAddressFamilyTest' ':app:assembleRelease' '-PtestRelease=true' '--offline' '--console=plain' *> "$taskStage/build.log";$taskResult.exitCode=$LASTEXITCODE}finally{Pop-Location}
    if($taskResult.exitCode -ne 0){throw 'Common P0 correction build failed; preserve exact log, do not relaunch on timeout'}
    foreach($taskRow in $taskManifest){if((Get-FileHash -LiteralPath (Join-Path $taskStage $taskRow.path) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskRow.sha256){throw 'P0 correction inputs changed during build'}}
    $taskResult.sourceStable=$true
    $taskSuites=@()
    foreach($taskSuite in @('ConnectionAddressFamilyTest','CarrierAddressFamilyTest')){
        [xml]$taskXml=Get-Content -Raw -LiteralPath "$taskStage/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.$taskSuite.xml"
        if([int]$taskXml.testsuite.tests -lt 1 -or [int]$taskXml.testsuite.failures -or [int]$taskXml.testsuite.errors -or [int]$taskXml.testsuite.skipped){throw 'Affected suite did not pass completely'}
        if($taskSuite -ceq 'ConnectionAddressFamilyTest' -and [int]$taskXml.testsuite.tests -ne 4){throw 'Missing new family policy cases'}
        $taskSuites+=@{suite=$taskSuite;tests=[int]$taskXml.testsuite.tests}
    }
    $taskResult.unitTests=$taskSuites
    $taskApk=Join-Path $taskStage 'ports/android/app/build/outputs/apk/release/app-universal-release.apk'
    $taskProof=Get-Content -LiteralPath "$taskBase/p0-artifacts.json" -Raw|ConvertFrom-Json
    $taskCert=(& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --print-certs $taskApk 2>&1|Out-String)
    if($LASTEXITCODE -or $taskCert -notmatch 'certificate SHA-256 digest:\s*([0-9a-f]{64})' -or $Matches[1].ToLowerInvariant() -cne $taskProof.signer){throw 'Correction signer differs from common P0'}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $taskZip=[IO.Compression.ZipFile]::OpenRead($taskApk)
    try{if(@($taskZip.Entries|Where-Object {$_.FullName -match '^lib/|\.so$'}).Count){throw 'Native leaked into base APK'}}finally{$taskZip.Dispose()}
    $taskResult.apk=$taskApk;$taskResult.sha256=(Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash.ToLowerInvariant();$taskResult.bytes=(Get-Item -LiteralPath $taskApk).Length
    $taskResult.signer=$taskProof.signer;$taskResult.state='PASS'
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{
    foreach($taskName in $taskEnvironment.Keys){[Environment]::SetEnvironmentVariable($taskName,$taskEnvironment[$taskName],'Process')}
    $taskResult.finishedUtc=[DateTime]::UtcNow.ToString('o')
    [IO.File]::WriteAllText("$taskStage/receipt.json",($taskResult|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
    $taskCurrent=Get-Content -LiteralPath $taskCurrentPath -Raw|ConvertFrom-Json
    if($taskCurrent.jobs.build -ceq "P0_TLS_UI_REPAIR:$taskStage"){$taskCurrent.jobs.build=$null;[IO.File]::WriteAllText($taskCurrentPath,($taskCurrent|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))}
    Write-Output "P0_TLS_UI_REPAIR=$($taskResult.state);RECEIPT=$taskStage/receipt.json"
}
