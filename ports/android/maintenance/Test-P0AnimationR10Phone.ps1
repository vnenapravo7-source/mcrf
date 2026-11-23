param([Parameter(Mandatory=$true)][string]$ReceiptPath,[string]$RunnerReceiptPath,[switch]$ResumeRenderer)
$ErrorActionPreference='Stop'
$taskSource=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Install-P0AnimationR10.ps1') -Raw
$taskBoundary=$taskSource.IndexOf('$taskResult=[ordered]')
if($taskBoundary -lt 0){throw 'Reviewed guard preamble missing'}
. ([scriptblock]::Create($taskSource.Substring(0,$taskBoundary))) -ReceiptPath $ReceiptPath
if($RunnerReceiptPath){
    $taskRunner=Get-Content -LiteralPath $RunnerReceiptPath -Raw|ConvertFrom-Json
    if($taskRunner.state -cne 'PASS' -or !$taskRunner.sourceStable -or !$taskRunner.mainUnchanged -or !$taskRunner.fixtureUnchanged){throw 'Verified runner-only repair required'}
    $taskBatch.artifacts.runner=$taskRunner
}
$taskCurrentPath="$taskRoot/ports/android/coordination/current.json"
$taskCurrent=Get-Content -Raw -LiteralPath $taskCurrentPath|ConvertFrom-Json
if($taskCurrent.jobs.device){throw 'An existing device session owns execution'}
$taskResult=[ordered]@{passed=$false;device=$taskDevice;stage=$taskBatch.stage;scope='Actual packaged WebView/Android settings in fresh animationfixture10 UID only; no VPN/profile/module mutations';startedUtc=[DateTime]::UtcNow.ToString('o')}
try{
    Idle
    if((Installed 'org.mcrf.client') -cne $taskCandidate.sha256 -or (Installed 'org.mcrf.client.animationfixture10') -cne $taskBatch.artifacts.fixture.sha256 -or (Installed 'org.mcrf.client.animationfixture10.test') -cne $taskBatch.artifacts.runner.sha256){throw 'Installed byte identity differs'}
    $taskBefore=Checkpoint 'protected-before-matrix';$taskModulesBefore=Modules
    $taskCurrent.jobs.device="P0_R10_MATRIX:$taskEvidence";$taskCurrent|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $taskCurrentPath -Encoding utf8
    Idle
    $taskArguments=@('shell','am','instrument','-w','-r')
    if($ResumeRenderer){$taskArguments+=@('-e','resumeRenderer','true')}
    $taskArguments+='org.mcrf.client.animationfixture10.test/org.mcrf.client.ConnectionAnimationInstrumentation'
    $taskOutput=Adb $taskArguments
    [IO.File]::WriteAllText("$taskEvidence/matrix.log",$taskOutput,[Text.UTF8Encoding]::new($false))
    $taskAfter=Checkpoint 'protected-after-matrix';$taskModulesAfter=Modules
    $taskResult.protectedUnchanged=$taskBefore -ceq $taskAfter
    $taskResult.modulesUnchanged=($taskModulesBefore|ConvertTo-Json -Compress) -ceq ($taskModulesAfter|ConvertTo-Json -Compress)
    if($taskOutput -match '(?m)^FAIL ' -or $taskOutput -notmatch 'INSTRUMENTATION_CODE: -1' -or $taskOutput -notmatch 'Whole P0 item40 presentation batch' -or !$taskResult.protectedUnchanged -or !$taskResult.modulesUnchanged){throw 'Animation matrix incomplete/failed or protected drift; preserve logs'}
    Adb @('pull','/sdcard/Android/data/org.mcrf.client.animationfixture10/cache/connection-animations',"$taskEvidence/frames")|Out-Null
    $taskResult.passed=$true
}catch{$taskResult.error=$_.Exception.Message;throw}
finally{
    $taskResult.finishedUtc=[DateTime]::UtcNow.ToString('o');$taskResult|ConvertTo-Json -Depth 5|Set-Content -LiteralPath "$taskEvidence/matrix-receipt.json" -Encoding utf8
    $taskCurrent=Get-Content -Raw -LiteralPath $taskCurrentPath|ConvertFrom-Json
    if($taskCurrent.jobs.device -ceq "P0_R10_MATRIX:$taskEvidence"){$taskCurrent.jobs.device=$null;$taskCurrent|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $taskCurrentPath -Encoding utf8}
    Write-Output "P0_R10_MATRIX=$($taskResult.passed);EVIDENCE=$taskEvidence"
}
