param()
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation test
$taskRoot='D:/MCRF/Projects/MCRF'
$taskParent='D:/MCRF/Builds/Android/p0-layout-r11-9a54680d79dc48ec960bf9529804de79'
$taskFirst='D:/MCRF/Builds/Android/p0-layout-r11-672d1b6aac2a41039d6d9d0edc17e9f3'
$taskReceipt=Get-Content -LiteralPath "$taskParent/receipt.json" -Raw|ConvertFrom-Json
if($taskReceipt.state -cne 'FAILED_PRESERVED' -or $taskReceipt.exitCode -ne 0 -or $taskReceipt.castingExit -ne 1){throw 'Expected completed JVM run with Node-only harness failure'}
$taskRows=@(Get-Content -LiteralPath "$taskParent/source-manifest.json" -Raw|ConvertFrom-Json)
foreach($row in $taskRows){if((Get-FileHash -LiteralPath (Join-Path $taskParent $row.path) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row.sha256){throw 'Parent inputs changed'}}
$taskInputs=Get-Content -LiteralPath "$taskParent/canonical-inputs.json" -Raw|ConvertFrom-Json -AsHashtable
$taskHelper='ports/android/maintenance/Test-CastingRound11.cjs'
foreach($path in $taskInputs.Keys){
    if($path -cne $taskHelper -and (Get-FileHash -LiteralPath (Join-Path $taskRoot $path) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskInputs[$path]){throw 'More than Node harness changed; retained JVM evidence not applicable'}
}
$taskTests=@()
foreach($suite in @('ConnectionAppearanceTest','ConnectionSceneAssetTest','LayoutDnsRound11Test','AdvancedDnsRoutingTest','ClientSettingsNavigationTest')){
    $suiteRoot=if($suite -ceq 'ClientSettingsNavigationTest'){$taskParent}else{$taskFirst}
    [xml]$xml=Get-Content -Raw -LiteralPath "$suiteRoot/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.$suite.xml"
    if([int]$xml.testsuite.tests -lt 1 -or [int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped){throw 'Incomplete JVM evidence'}
    $taskTests+=@{suite=$suite;tests=[int]$xml.testsuite.tests;evidence="$suiteRoot/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.$suite.xml"}
}
$taskStage=Join-Path 'D:/MCRF/Builds/Android' ('p0-layout-r11-dispatch-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskStage|Out-Null
$taskAsset='ports/android/app/src/main/assets/connection-scene-r10.html'
[IO.File]::Copy((Join-Path $taskParent $taskAsset),"$taskStage/scene.html",$false)
[IO.File]::Copy((Join-Path $taskRoot $taskHelper),"$taskStage/check.cjs",$false)
$taskFrozen=@('scene.html','check.cjs')|ForEach-Object {@{path=$_;sha256=(Get-FileHash -LiteralPath "$taskStage/$_" -Algorithm SHA256).Hash}}
$taskFrozen|ConvertTo-Json|Set-Content -LiteralPath "$taskStage/source-manifest.json" -Encoding utf8
$taskResult=[ordered]@{state='STARTED';compiledSnapshot=$taskParent;previousFailedRun=$taskFirst;tests=$taskTests;sourceStable=$false;apkBuilt=$false;deviceMutated=$false;excludedP1=$true;scope='All 68 affected JVM checks retained with exact source attestation; Node harness-only Path2D shim repair; no rerun of passed JVM suites. UI Kotlin compiled, device visual checks NOT run.'}
try {
    & 'C:/Users/dieonfire/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe' "$taskStage/check.cjs" "$taskStage/scene.html" *> "$taskStage/dispatch.log"
    $taskResult.dispatchExit=$LASTEXITCODE
    if($LASTEXITCODE){throw 'Dispatch harness still failed'}
    foreach($row in $taskFrozen){if((Get-FileHash -LiteralPath "$taskStage/$($row.path)" -Algorithm SHA256).Hash -cne $row.sha256){throw 'Frozen dispatcher inputs changed'}}
    $taskResult.sourceStable=$true;$taskResult.state='PASS';$taskResult.dispatchFrames=10
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{$taskResult.finishedUtc=[DateTime]::UtcNow.ToString('o');$taskResult|ConvertTo-Json -Depth 6|Set-Content -LiteralPath "$taskStage/receipt.json" -Encoding utf8;Write-Output "R11_FINAL=$($taskResult.state);RECEIPT=$taskStage/receipt.json"}
