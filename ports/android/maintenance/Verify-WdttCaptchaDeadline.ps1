$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation test
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskEvidence=Join-Path "$taskRoot/work/ports-test-2026-10-14" ('wdtt-captcha-clock-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskEvidence|Out-Null
$taskInputs=@('mcrf_wdtt.go','mcrf_wdtt_test.go','wdtt-mobile.patch','build.ps1')
$taskHashes=@{}
foreach($taskInput in $taskInputs){$taskHashes[$taskInput]=(Get-FileHash -LiteralPath "$taskRoot/ports/android/transports/swg/$taskInput" -Algorithm SHA256).Hash.ToLowerInvariant()}
$taskReceipt=[ordered]@{state='ACTIVE';sourceStable=$false;inputs=$taskHashes;scope='Only three new WDTT deadline host checks including actual patched request function with owned pipe/reply. No provider/network/Android/APK or closed-test rerun.';apkBuilt=$false}
function Save-WdttDeadlineReceipt {[IO.File]::WriteAllText("$taskEvidence/receipt.json",($taskReceipt|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))}
Save-WdttDeadlineReceipt
Write-Output "EVIDENCE=$taskEvidence"
$taskOldProxy=$env:GOPROXY
try {
    $env:GOPROXY='off'
    & "$taskRoot/ports/android/transports/swg/build.ps1" -WdttHostTest -WdttHostScope captcha-deadline 2>&1|Tee-Object -FilePath "$taskEvidence/host.log"
    if($LASTEXITCODE -ne 0){throw 'WDTT deadline host checks failed'}
    $taskLog=Get-Content -LiteralPath "$taskEvidence/host.log" -Raw
    foreach($taskTest in @('ActiveChecksRealDeadlineAndCancellation','ExpiredRequestLeavesQueuedProofUntouched','QueuedExactReplyCannotBeatElapsedParent')){
        if($taskLog -notmatch ('(?m)^--- PASS: TestMcrfWdttDeadline'+$taskTest+' ')){throw 'New WDTT deadline case missing'}
    }
    if($taskLog -match '(?m)^--- FAIL:|^FAIL\s' -or [regex]::Matches($taskLog,'(?m)^--- PASS: ').Count -ne 3){throw 'Narrow WDTT scope not proven'}
    $taskMatch=[regex]::Match($taskLog,'(?m)^SWG_HOST_STAGE=(.+)\r?$')
    if(!$taskMatch.Success){throw 'Frozen WDTT source stage missing'}
    $taskStage=[IO.Path]::GetFullPath($taskMatch.Groups[1].Value.Trim())
    $taskAllowed=[IO.Path]::GetFullPath("$taskRoot/work/ports-test-2026-10-14")+[IO.Path]::DirectorySeparatorChar
    if(!$taskStage.StartsWith($taskAllowed,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($taskStage) -cnotmatch '^swg-mobile-[0-9a-f]{32}$'){throw 'Unexpected WDTT source stage'}
    foreach($taskInput in $taskInputs){
        if((Get-FileHash -LiteralPath "$taskRoot/ports/android/transports/swg/$taskInput" -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskHashes[$taskInput]){throw 'WDTT canonical input changed'}
        if($taskInput -in @('mcrf_wdtt.go','mcrf_wdtt_test.go') -and (Get-FileHash -LiteralPath "$taskStage/wdtt/go_client/$taskInput" -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskHashes[$taskInput]){throw 'Frozen WDTT helper changed'}
    }
    $taskPatched=Get-Content -LiteralPath "$taskStage/wdtt/go_client/creds.go" -Raw
    $taskStart=$taskPatched.IndexOf('func requestWebViewCaptcha(')
    if($taskStart -lt 0){throw 'Patched request function missing'}
    $taskFunction=$taskPatched.Substring($taskStart)
    $taskBudget=$taskFunction.IndexOf('waitCtx, cancel := context.WithTimeout(ctx, timeout)')
    $taskEmit=$taskFunction.IndexOf('fmt.Printf("CAPTCHA_SOLVE|')
    if($taskBudget -lt 0 -or $taskEmit -lt 0 -or $taskBudget -ge $taskEmit -or !$taskFunction.Contains('mcrfWdttCaptchaActive(waitCtx)')){throw 'Original deadline placement/guard not preserved'}
    $taskReceipt.stage=$taskStage;$taskReceipt.passedTests=3
    $taskReceipt.sourceStable=$true;$taskReceipt.state='HOST_CHECKS_PASSED'
}catch{$taskReceipt.state='FAILED';throw}
finally {$env:GOPROXY=$taskOldProxy;$taskReceipt.timeUtc=[DateTime]::UtcNow.ToString('o');Save-WdttDeadlineReceipt;Write-Output "EVIDENCE=$taskEvidence"}
