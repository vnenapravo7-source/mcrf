param([Parameter(Mandatory=$true)][string]$Stage)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation build
$taskRoot='D:/MCRF/Projects/MCRF';$taskStage=[IO.Path]::GetFullPath($Stage)
if([IO.Path]::GetDirectoryName($taskStage) -ine 'D:\MCRF\Builds\Android' -or [IO.Path]::GetFileName($taskStage) -cnotmatch '^p0-tls-ui-repair-[a-f0-9]{32}$'){throw 'Exact existing repair stage required'}
$taskPrior=Get-Content -Raw -LiteralPath "$taskStage/receipt.json"|ConvertFrom-Json
if($taskPrior.state -cne 'FAILED_PRESERVED' -or (Get-Content -Raw "$taskStage/build.log") -notmatch "testReleaseUnitTest.*not found"){throw 'Only recorded pre-execution missing task may continue'}
if(Test-Path "$taskStage/continuation-build.log"){throw 'Continuation already started; resume session instead'}
$taskManifest=Get-Content -Raw "$taskStage/source-manifest.json"|ConvertFrom-Json
function Stable{foreach($taskRow in $taskManifest){if((Get-FileHash -LiteralPath (Join-Path $taskStage $taskRow.path) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskRow.sha256){throw 'Frozen repair input changed'}}}
Stable
$taskCurrentPath=Join-Path $taskRoot 'ports/android/coordination/current.json'
$taskCurrent=Get-Content -Raw $taskCurrentPath|ConvertFrom-Json
if($taskCurrent.jobs.build){throw 'Existing build owns execution'}
$taskResult=[ordered]@{state='STARTED';stage=$taskStage;parent=$taskPrior.parent;excludedP1=$true;mainVersionCode=10009;testSignedOnly=$true;published=$false;sourceStable=$false;originalFailurePreserved=$true;reason='Correct only unavailable test task; same frozen source, no previous compilation/tests/APK';startedUtc=[DateTime]::UtcNow.ToString('o')}
try{
    $taskCurrent.jobs.build="P0_TLS_UI_REPAIR_CONTINUE:$taskStage";$taskCurrent|ConvertTo-Json -Depth 9|Set-Content $taskCurrentPath -Encoding utf8
    $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$taskRoot/work/toolchains/gradle-home"
    $env:JAVA_TOOL_OPTIONS='"-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"'
    Push-Location "$taskStage/ports/android"
    try{& "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain ':app:testDebugUnitTest' --tests org.mcrf.client.ConnectionAddressFamilyTest --tests org.mcrf.client.CarrierAddressFamilyTest ':app:assembleRelease' '-PtestRelease=true' --offline --console=plain *> "$taskStage/continuation-build.log";$taskResult.exitCode=$LASTEXITCODE}finally{Pop-Location}
    if($taskResult.exitCode){throw 'Actual grouped repair execution failed; preserve log, no automatic rerun'}
    Stable;$taskResult.sourceStable=$true;$taskSuites=@()
    foreach($taskSuite in @('ConnectionAddressFamilyTest','CarrierAddressFamilyTest')){
        [xml]$taskXml=Get-Content -Raw "$taskStage/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.$taskSuite.xml"
        if([int]$taskXml.testsuite.tests -lt 1 -or [int]$taskXml.testsuite.failures -or [int]$taskXml.testsuite.errors -or [int]$taskXml.testsuite.skipped){throw 'Affected suite not completely passed'}
        if($taskSuite -ceq 'ConnectionAddressFamilyTest' -and [int]$taskXml.testsuite.tests -ne 4){throw 'Missing new policy cases'}
        $taskSuites+=@{suite=$taskSuite;tests=[int]$taskXml.testsuite.tests}
    }
    $taskResult.unitTests=$taskSuites;$taskApk="$taskStage/ports/android/app/build/outputs/apk/release/app-universal-release.apk"
    $taskProof=Get-Content -Raw "$($taskPrior.parent)/p0-artifacts.json"|ConvertFrom-Json
    $taskCert=(& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --print-certs $taskApk 2>&1|Out-String)
    if($LASTEXITCODE -or $taskCert -notmatch 'certificate SHA-256 digest:\s*([0-9a-f]{64})' -or $Matches[1] -cne $taskProof.signer){throw 'Repair signer mismatch'}
    Add-Type -AssemblyName System.IO.Compression.FileSystem;$taskZip=[IO.Compression.ZipFile]::OpenRead($taskApk)
    try{if(@($taskZip.Entries|Where-Object {$_.FullName -match '^lib/|\.so$'}).Count){throw 'Base APK contains native'}}finally{$taskZip.Dispose()}
    $taskResult.apk=$taskApk;$taskResult.bytes=(Get-Item $taskApk).Length;$taskResult.sha256=(Get-FileHash $taskApk -Algorithm SHA256).Hash.ToLowerInvariant();$taskResult.signer=$taskProof.signer;$taskResult.state='PASS'
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{
    $taskResult.finishedUtc=[DateTime]::UtcNow.ToString('o');$taskResult|ConvertTo-Json -Depth 7|Set-Content "$taskStage/continuation-receipt.json" -Encoding utf8
    $taskCurrent=Get-Content -Raw $taskCurrentPath|ConvertFrom-Json
    if($taskCurrent.jobs.build -ceq "P0_TLS_UI_REPAIR_CONTINUE:$taskStage"){$taskCurrent.jobs.build=$null;$taskCurrent|ConvertTo-Json -Depth 9|Set-Content $taskCurrentPath -Encoding utf8}
    Write-Output "P0_TLS_UI_CONTINUATION=$($taskResult.state);RECEIPT=$taskStage/continuation-receipt.json"
}
