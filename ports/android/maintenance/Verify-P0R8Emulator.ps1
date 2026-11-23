param([switch]$ContinueAfterOnboarding,[string]$CorrectionReceipt='')
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation test
$taskRoot='D:/MCRF/Projects/MCRF'
$taskBuild=Join-Path $taskRoot 'work/android-integration-a6c52ec6f97149f4835a629f1a450f43'
$taskRunnerRoot=Join-Path $taskRoot 'work/android-r8-runner-artifacts-6f3e181ef0bd46638888adde781eb62f'
$taskRunnerReceipt=Get-Content -LiteralPath "$taskRunnerRoot/receipt.json" -Raw|ConvertFrom-Json
if($taskRunnerReceipt.state -cne 'PASS' -or !$taskRunnerReceipt.sourceStable -or !$taskRunnerReceipt.mainApksUnchanged){throw 'Verified P0-only framework runner export required'}
$taskProof=Get-Content -LiteralPath "$taskBuild/p0-artifacts.json" -Raw|ConvertFrom-Json
$taskApp=Join-Path $taskBuild 'ports/android/app/build/outputs/apk/release/app-universal-release.apk'
$taskExpectedApp=($taskProof.files|Where-Object {$_.kind -ceq 'app-release'}).sha256
if($CorrectionReceipt){
    if($ContinueAfterOnboarding){throw 'Correction candidate needs its own install, not the completed old cohort'}
    $taskCorrection=Get-Content -LiteralPath $CorrectionReceipt -Raw|ConvertFrom-Json
    if($taskCorrection.state -cne 'PASS' -or !$taskCorrection.sourceStable -or !$taskCorrection.excludedP1 -or $taskCorrection.signer -cne $taskProof.signer -or @($taskCorrection.changedSource).Count -ne 1 -or $taskCorrection.changedSource[0] -cne 'ports/android/app/src/main/kotlin/org/mcrf/client/MainActivity.kt'){throw 'Verified P0-only correction receipt required'}
    $taskApp=$taskCorrection.apk;$taskExpectedApp=$taskCorrection.sha256
}
$taskRunner=Join-Path $taskRunnerRoot 'runner.apk'
if((Get-FileHash -LiteralPath $taskApp -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskExpectedApp -or
   (Get-FileHash -LiteralPath $taskRunner -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskRunnerReceipt.runnerSha256){throw 'Exact R8/runner bytes differ from P0 evidence'}
$taskLeasePath=Join-Path $taskRoot 'ports/android/coordination/emulator-lease.json'
$taskLease=Get-Content -LiteralPath $taskLeasePath -Raw|ConvertFrom-Json
if($taskLease.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $taskLease.device -cne 'emulator-5586' -or ($taskLease.state -cne 'booting_p0_common_readonly' -and !($CorrectionReceipt -and $taskLease.state -ceq 'occupied_p0_r8_remaining_ui'))){throw 'Fresh owned emulator lease required'}
$taskProcess=@(Get-CimInstance Win32_Process|Where-Object {$_.ProcessId -eq $taskLease.pid -and $_.Name -ceq 'emulator.exe' -and $_.CommandLine -match '-read-only' -and $_.CommandLine -match '-no-snapshot-save' -and $_.CommandLine -match '-port 5586'})
if($taskProcess.Count -ne 1){throw 'Exact owned read-only process missing'}
$taskEvidence=Split-Path -Parent $taskLease.launchReceipt
$taskPriorAttempt=Join-Path $taskEvidence 'r8-b751a0331d9c4f08a9e4a9156eb49c11-receipt.json'
if($ContinueAfterOnboarding){
    $taskPrior=Get-Content -LiteralPath $taskPriorAttempt -Raw|ConvertFrom-Json
    if($taskPrior.state -cne 'FAILED_PRESERVED' -or !$taskPrior.mainInstalled -or !$taskPrior.runnerInstalled){throw 'Resume requires the existing installed R8 attempt; never reinstall/repeat cold-start'}
}
$taskAttempt='r8-'+[guid]::NewGuid().ToString('N')
$taskReceiptPath=Join-Path $taskEvidence "$taskAttempt-receipt.json"
$taskAdb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$taskSdk='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
function Adb([string[]]$Arguments){$taskOutput=(& $taskAdb -s emulator-5586 @Arguments 2>&1|Out-String);if($LASTEXITCODE){throw 'Owned R8 emulator ADB operation failed; no retry/reset'};return $taskOutput.Trim()}
function Idle{
    foreach($taskPackage in @('org.mcrf.client','org.steer.prototype')){
        if((Adb @('shell','dumpsys','activity','services',$taskPackage)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'Existing app service; do not interrupt'}
    }
    if((Adb @('shell','dumpsys','vpn_management')) -match 'Active vpn type: [01]\b'){throw 'Existing VPN; do not interrupt'}
}
function Modules{
    $taskRows=@()
    foreach($taskLine in ((Adb @('shell','pm','list','packages','org.mcrf.engines.')) -split '\r?\n'|Sort-Object)){
        if($taskLine -cnotmatch '^package:(org\.mcrf\.engines\.[a-z0-9]+)$'){throw 'Unexpected external module inventory'}
        $taskName=$Matches[1];$taskPath=(Adb @('shell','pm','path',$taskName)) -replace '^package:',''
        if($taskPath -notmatch '^/data/app/[^\s]+/base.apk$'){throw 'Unexpected module APK path'}
        $taskRows+=$taskName+'='+((Adb @('shell','sha256sum',$taskPath)) -split '\s+')[0]
    }
    return $taskRows -join "`n"
}
function Instrument([string]$Mode){
    $taskLog=Join-Path $taskEvidence "$taskAttempt-$Mode.log"
    if(Test-Path -LiteralPath $taskLog){throw 'Attempt log already exists; never overwrite/repeat a test'}
    $taskOutput=Adb @('shell','am','instrument','-w','-r','-e','mode',$Mode,'org.mcrf.client.test/org.mcrf.client.ReleaseSmokeInstrumentation')
    [IO.File]::WriteAllText($taskLog,$taskOutput,[Text.UTF8Encoding]::new($false))
    return $taskOutput
}
function Hashes([string]$Output){
    if($Output -notmatch 'INSTRUMENTATION_RESULT: protectedFilesUnchanged=true'){throw 'Protected cohort baseline changed; no rollback'}
    $taskMatch=[regex]::Match($Output,'(?s)INSTRUMENTATION_RESULT: protectedFilesBefore=(.*?)\r?\n\r?\n')
    if(!$taskMatch.Success -or $taskMatch.Groups[1].Value -notmatch '[0-9a-f]{64}'){throw 'Missing protected hash evidence'}
    return $taskMatch.Groups[1].Value.Trim()
}
$taskResult=[ordered]@{state='STARTED';device='emulator-5586';snapshot=$taskBuild;runner=$taskRunner;minified=$true;mainInstalled=$false;runnerInstalled=$false;notificationChanged=$false;physicalPhoneTouched=$false;startedUtc=[DateTime]::UtcNow.ToString('o');results=@();scope='Existing exact P0 R8 cold-start/settings/logs runtime, not provider traffic/handover/S3/OEM proof'}
try{
    if((Adb @('shell','getprop','sys.boot_completed')) -cne '1' -or (Adb @('shell','getprop','ro.kernel.qemu')) -cne '1'){throw 'Owned emulator not yet booted; continue boot, do not launch another'}
    Idle
    $taskModulesBefore=Modules
    if(!$ContinueAfterOnboarding){
    $taskOldPath=(Adb @('shell','pm','path','org.mcrf.client')) -replace '^package:',''
    if($taskOldPath -notmatch '^/data/app/[^\s]+/base.apk$'){throw 'Existing MCRF baseline required; never clear/create profile'}
    [void](Adb @('pull',$taskOldPath,(Join-Path $taskEvidence "$taskAttempt-baseline.apk")))
    $taskPriorSigner=(& "$taskSdk/apksigner.bat" verify --print-certs (Join-Path $taskEvidence "$taskAttempt-baseline.apk") 2>&1|Out-String)
    if($LASTEXITCODE -or $taskPriorSigner -notmatch 'certificate SHA-256 digest:\s*([0-9a-f]{64})' -or $Matches[1].ToLowerInvariant() -cne $taskProof.signer){throw 'Existing baseline signer differs; no uninstall/replace workaround'}
    $taskInstalledRunner=(Adb @('shell','pm','path','org.mcrf.client.test')) -replace '^package:',''
    $taskRunnerExact=$taskInstalledRunner -match '^/data/app/[^\s]+/base.apk$' -and ((Adb @('shell','sha256sum',$taskInstalledRunner)) -split '\s+')[0] -ceq $taskRunnerReceipt.runnerSha256
    if(!$taskRunnerExact){if((Adb @('install','-r','-t',$taskRunner)) -notmatch '(?m)^Success\s*$'){throw 'R8 framework runner install failed'};$taskResult.runnerInstalled=$true}
    $taskBefore=Hashes (Instrument 'protected-hashes')
    Idle
    if((Adb @('install','-r',$taskApp)) -notmatch '(?m)^Success\s*$'){throw 'Same-signer R8 upgrade failed; never uninstall/reset'}
    $taskResult.mainInstalled=$true
    }else{
        # Normal first-use notification/advice bookkeeping happened before this
        # fresh read-only cohort. Original cold-start log remains FAILED, with
        # its three lifecycle assertions PASS; it is not rerun or relabelled.
        $taskResult.scope='Remaining exact P0 R8 settings/logs only after ordinary first-use prompts; no install, cold-start repeat, grant, profile rollback, native traffic or phone'
        $taskResult.resumes=$taskPriorAttempt
        $taskBefore=Hashes (Instrument 'protected-hashes')
    }
    $taskPath=(Adb @('shell','pm','path','org.mcrf.client')) -replace '^package:',''
    if($taskPath -notmatch '^/data/app/[^\s]+/base.apk$' -or ((Adb @('shell','sha256sum',$taskPath)) -split '\s+')[0] -cne (Get-FileHash -LiteralPath $taskApp -Algorithm SHA256).Hash.ToLowerInvariant()){throw 'Installed main APK differs from frozen R8'}
    if((Adb @('shell','dumpsys','package','org.mcrf.client')) -match '(?m)^\s*(?:pkgFlags|flags)=\[.*\bDEBUGGABLE\b'){throw 'Installed main is debuggable, not R8 acceptance'}
    if($CorrectionReceipt){
        if((Adb @('shell','wm','size')) -match 'Override' -or (Adb @('shell','wm','density')) -match 'Override' -or (Adb @('shell','settings','get','system','font_scale')) -cne '1.0'){throw 'Unknown display overrides; do not overwrite them'}
        [void](Adb @('shell','wm','size','640x1280'));[void](Adb @('shell','wm','density','320'));[void](Adb @('shell','settings','put','system','font_scale','1.3'))
        $taskResult.correctionReceipt=$CorrectionReceipt;$taskResult.narrow320dpFont13=$true
    }
    $taskModes=if($CorrectionReceipt){@('settings-pages')}elseif($ContinueAfterOnboarding){@('settings-pages','logs-ui')}else{@('cold-start','settings-pages','logs-ui')}
    foreach($taskMode in $taskModes){
        Idle
        $taskOutput=Instrument $taskMode
        $taskProtected=Hashes $taskOutput
        $taskPassed=$taskOutput -match 'INSTRUMENTATION_RESULT: passed=true' -and $taskOutput -match 'INSTRUMENTATION_CODE: -1' -and $taskOutput -notmatch 'INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed'
        $taskSame=$taskProtected -ceq $taskBefore
        $taskResult.results+=@{mode=$taskMode;passed=$taskPassed;protectedUnchanged=$taskSame}
        Write-Output "P0_R8_MODE=$taskMode;PASS=$taskPassed;PROTECTED_UNCHANGED=$taskSame"
        if(!$taskSame){throw 'Protected install baseline changed; inspect and preserve, never restore old files'}
        if(!$taskPassed){throw "P0 R8 mode failed: $taskMode; preserve this attempt, no automatic rerun"}
    }
    $taskResult.modulesUnchanged=(Modules) -ceq $taskModulesBefore
    if(!$taskResult.modulesUnchanged){throw 'Prior engine bytes changed'}
    Idle
    $taskResult.state='PASS'
    $taskLease.state='occupied_p0_r8_remaining_ui'
    [IO.File]::WriteAllText($taskLeasePath,($taskLease|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{
    if($taskResult.narrow320dpFont13){
        [void](Adb @('shell','wm','size','reset'));[void](Adb @('shell','wm','density','reset'));[void](Adb @('shell','settings','put','system','font_scale','1.0'))
    }
    $taskResult.finishedUtc=[DateTime]::UtcNow.ToString('o')
    [IO.File]::WriteAllText($taskReceiptPath,($taskResult|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
    Write-Output "P0_R8_RESULT=$($taskResult.state);RECEIPT=$taskReceiptPath"
}
