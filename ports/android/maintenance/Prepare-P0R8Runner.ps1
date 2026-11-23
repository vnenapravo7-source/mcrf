param()
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation build
$taskRoot='D:/MCRF/Projects/MCRF'
$taskFrozen=Join-Path $taskRoot 'work/android-integration-a6c52ec6f97149f4835a629f1a450f43'
$taskProof=Get-Content -LiteralPath "$taskFrozen/p0-artifacts.json" -Raw|ConvertFrom-Json
$taskInputs=@(Get-Content -LiteralPath "$taskFrozen/source-manifest.json" -Raw|ConvertFrom-Json)
if($taskProof.state -cne 'PASS' -or !$taskProof.testSignedOnly -or $taskInputs.Count -ne 875){throw 'Exact completed common P0 snapshot required'}
if(@(Get-Process -Name java,go -ErrorAction SilentlyContinue).Count){throw 'Existing producer; do not duplicate or stop it'}
$taskCurrentPath=Join-Path $taskRoot 'ports/android/coordination/current.json'
$taskCurrent=Get-Content -LiteralPath $taskCurrentPath -Raw|ConvertFrom-Json
if($taskCurrent.activeBatch -cne 'p0-progress-2026-10-17.json' -or $taskCurrent.jobs.build -or $taskCurrent.jobs.hostTest){throw 'Only idle P0 common verification may continue'}
function Assert-Frozen {
    foreach($taskRow in $taskInputs){
        $taskFile=[IO.Path]::GetFullPath((Join-Path $taskFrozen $taskRow.path))
        if(!$taskFile.StartsWith($taskFrozen+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
            !(Test-Path -LiteralPath $taskFile -PathType Leaf) -or
            (Get-FileHash -LiteralPath $taskFile -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskRow.sha256){throw 'Frozen P0 source changed; no P1/canonical source substitution'}
    }
}
Assert-Frozen
$taskApks=@('debug','release')|ForEach-Object {Join-Path $taskFrozen "ports/android/app/build/outputs/apk/$_/app-universal-$_.apk"}
$taskHashes=@{}
foreach($taskApk in $taskApks){$taskHashes[$taskApk]=(Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash}
if($taskHashes[$taskApks[1]].ToLowerInvariant() -cne ($taskProof.files|Where-Object {$_.kind -ceq 'app-release'}).sha256){throw 'Existing release differs from verified P0'}
$taskExport=Join-Path $taskRoot ('work/android-r8-runner-artifacts-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskExport|Out-Null
$taskRunner=Join-Path $taskFrozen 'ports/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
[IO.File]::Copy($taskRunner,"$taskExport/prior-device-runner.apk",$false)
$taskBeforeRunner=(Get-FileHash -LiteralPath $taskRunner -Algorithm SHA256).Hash
[IO.File]::Copy("$taskFrozen/source-manifest.json","$taskExport/source-manifest.json",$false)
$taskEnvironment=@{}
foreach($taskName in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$taskEnvironment[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
$taskResult=[ordered]@{state='STARTED';snapshot=$taskFrozen;scope='Only R8 framework instrumentation manifest/package from existing immutable P0 inputs; no main/module APK build, JVM tests, P1, phone or publication';startedUtc=[DateTime]::UtcNow.ToString('o');sourceStable=$false;mainApksUnchanged=$false;runnerExported=$false}
$taskExit=$null
try{
    $taskCurrent.jobs.build="P0_R8_RUNNER_ONLY:$taskExport"
    [IO.File]::WriteAllText($taskCurrentPath,($taskCurrent|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    $env:JAVA_HOME='C:/Program Files/Java/jdk-17'
    $env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk'
    $env:GRADLE_USER_HOME=Join-Path $taskRoot 'work/toolchains/gradle-home'
    $taskTemp='D:/MCRF/Builds/Android/java-tmp'
    if(!(Test-Path -LiteralPath $taskTemp)){New-Item -ItemType Directory -Path $taskTemp|Out-Null}
    $env:JAVA_TOOL_OPTIONS=($taskEnvironment.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir='+$taskTemp+'"').Trim()
    Push-Location "$taskFrozen/ports/android"
    try{
        & "$env:JAVA_HOME/bin/java.exe" -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain ':app:assembleDebugAndroidTest' '-PreleaseSmoke=true' '-PtestRelease=true' '--offline' '--console=plain' 2>&1|Tee-Object -FilePath "$taskExport/build.log"
        $taskExit=$LASTEXITCODE
    }finally{Pop-Location}
    if($taskExit -ne 0){throw 'R8 runner packaging failed; preserve attempt, no automatic restart'}
    Assert-Frozen
    $taskResult.sourceStable=$true
    foreach($taskApk in $taskApks){if((Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash -cne $taskHashes[$taskApk]){throw 'Main APK changed during runner-only packaging'}}
    $taskResult.mainApksUnchanged=$true
    $taskManifest=(& "$env:ANDROID_HOME/build-tools/36.0.0/aapt.exe" dump xmltree $taskRunner AndroidManifest.xml|Out-String)
    if($LASTEXITCODE -or $taskManifest -notmatch 'org.mcrf.client.ReleaseSmokeInstrumentation' -or $taskManifest -match 'org.mcrf.client.DeviceSmokeInstrumentation'){throw 'R8 framework runner manifest mismatch'}
    $taskSigner=(& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --print-certs $taskRunner 2>&1|Out-String)
    if($LASTEXITCODE -or $taskSigner -notmatch 'certificate SHA-256 digest:\s*([0-9a-f]{64})' -or $Matches[1].ToLowerInvariant() -cne $taskProof.signer){throw 'R8 runner signer mismatch'}
    [IO.File]::Copy($taskRunner,"$taskExport/runner.apk",$false)
    $taskResult.runnerExported=$true
    $taskResult.runnerSha256=(Get-FileHash -LiteralPath "$taskExport/runner.apk" -Algorithm SHA256).Hash.ToLowerInvariant()
    $taskResult.runnerBytes=(Get-Item -LiteralPath "$taskExport/runner.apk").Length
    $taskResult.signer=$taskProof.signer
    $taskResult.state='PASS'
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{
    if((Get-FileHash -LiteralPath "$taskExport/prior-device-runner.apk" -Algorithm SHA256).Hash -cne $taskBeforeRunner){throw 'Saved original generated runner changed'}
    [IO.File]::Copy("$taskExport/prior-device-runner.apk",$taskRunner,$true)
    foreach($taskName in $taskEnvironment.Keys){[Environment]::SetEnvironmentVariable($taskName,$taskEnvironment[$taskName],'Process')}
    $taskResult.exitCode=$taskExit;$taskResult.finishedUtc=[DateTime]::UtcNow.ToString('o')
    [IO.File]::WriteAllText("$taskExport/receipt.json",($taskResult|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
    $taskCurrent=Get-Content -LiteralPath $taskCurrentPath -Raw|ConvertFrom-Json
    if($taskCurrent.jobs.build -ceq "P0_R8_RUNNER_ONLY:$taskExport"){$taskCurrent.jobs.build=$null;[IO.File]::WriteAllText($taskCurrentPath,($taskCurrent|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))}
    Write-Output "P0_R8_RUNNER=$($taskResult.state);RECEIPT=$taskExport/receipt.json"
}
