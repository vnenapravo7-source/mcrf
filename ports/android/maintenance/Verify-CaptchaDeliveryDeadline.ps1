param([string]$VerifyExistingEvidence='')
$ErrorActionPreference='Stop'
if(!$VerifyExistingEvidence){& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation test}
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskInputs=@('mcrf_captcha.go','mcrf_captcha_test.go','build.ps1')
if($VerifyExistingEvidence){
    $taskEvidence=[IO.Path]::GetFullPath($VerifyExistingEvidence)
    $taskAllowedEvidence=[IO.Path]::GetFullPath("$taskRoot/work/ports-test-2026-10-14")+[IO.Path]::DirectorySeparatorChar
    if(!$taskEvidence.StartsWith($taskAllowedEvidence,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($taskEvidence) -cnotmatch '^captcha-delivery-clock-[0-9a-f]{32}$' -or (Get-Item -LiteralPath $taskEvidence).LinkType){throw 'Unexpected owned evidence directory'}
    $taskReceipt=Get-Content -LiteralPath "$taskEvidence/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
    if($taskReceipt.state -ne 'FAILED'){throw 'Only finished failed verification can resume without rerunning tests'}
    $taskHashes=$taskReceipt.inputs
    $taskReceipt.verificationOnly=$true
    $taskReceipt.priorVerifierFailure='Original session69671 exited1 after11passing tests: expected two names without Rejects. Revalidate existing log and exact frozen inputs; no test/build restart.'
}else{
    $taskEvidence=Join-Path "$taskRoot/work/ports-test-2026-10-14" ('captcha-delivery-clock-'+[guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $taskEvidence|Out-Null
    $taskHashes=@{}
    foreach($taskInput in $taskInputs){$taskHashes[$taskInput]=(Get-FileHash -LiteralPath "$taskRoot/ports/android/transports/swg/$taskInput" -Algorithm SHA256).Hash.ToLowerInvariant()}
    $taskReceipt=[ordered]@{state='ACTIVE';sourceStable=$false;inputs=$taskHashes;scope='Affected QWDTT Go waiter host tests only; no provider/network/Android/APK/process-tree acceptance';apkBuilt=$false}
}
function Save-DeadlineReceipt {[IO.File]::WriteAllText("$taskEvidence/receipt.json",($taskReceipt|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))}
Save-DeadlineReceipt
Write-Output "EVIDENCE=$taskEvidence"
$taskOldProxy=$env:GOPROXY
try {
    if(!$VerifyExistingEvidence){
        $env:GOPROXY='off'
        & "$taskRoot/ports/android/transports/swg/build.ps1" -CaptchaHostTest 2>&1|Tee-Object -FilePath "$taskEvidence/host.log"
        if($LASTEXITCODE -ne 0){throw 'CAPTCHA deadline host checks failed'}
    }
    $taskLog=Get-Content -LiteralPath "$taskEvidence/host.log" -Raw
    foreach($taskTest in @('RejectsRegistrationBeforeTimerRuns','RejectsDeliveryBeforeTimerRuns','FutureAndUnboundedRemainUsable')){
        if($taskLog -notmatch ('(?m)^--- PASS: TestMcrfCaptchaDeadline'+$taskTest+' ')){throw 'New deadline case missing'}
    }
    if($taskLog -match '(?m)^--- FAIL:|^FAIL\s' -or $taskLog -notmatch '(?m)^ok\s'){throw 'Host suite not proven'}
    $taskStageMatch=[regex]::Match($taskLog,'(?m)^SWG_HOST_STAGE=(.+)\r?$')
    if(!$taskStageMatch.Success){throw 'Frozen host source stage missing'}
    $taskStage=[IO.Path]::GetFullPath($taskStageMatch.Groups[1].Value.Trim())
    $taskAllowed=[IO.Path]::GetFullPath("$taskRoot/work/ports-test-2026-10-14")+[IO.Path]::DirectorySeparatorChar
    if(!$taskStage.StartsWith($taskAllowed,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($taskStage) -cnotmatch '^swg-mobile-[0-9a-f]{32}$'){throw 'Unexpected host source stage'}
    foreach($taskInput in $taskInputs){
        if((Get-FileHash -LiteralPath "$taskRoot/ports/android/transports/swg/$taskInput" -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskHashes[$taskInput]){throw 'Canonical input changed'}
        if($taskInput -ne 'build.ps1' -and (Get-FileHash -LiteralPath "$taskStage/qwdtt/qwdtt/$taskInput" -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskHashes[$taskInput]){throw 'Frozen adapter input changed'}
    }
    $taskReceipt.stage=$taskStage;$taskReceipt.passedTests=[regex]::Matches($taskLog,'(?m)^--- PASS: ').Count
    $taskReceipt.sourceStable=$true;$taskReceipt.state='HOST_CHECKS_PASSED'
}catch{$taskReceipt.state='FAILED';throw}
finally {$env:GOPROXY=$taskOldProxy;$taskReceipt.timeUtc=[DateTime]::UtcNow.ToString('o');Save-DeadlineReceipt;Write-Output "EVIDENCE=$taskEvidence"}
