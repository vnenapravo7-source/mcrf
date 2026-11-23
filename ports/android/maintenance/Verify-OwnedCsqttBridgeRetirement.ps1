$ErrorActionPreference='Stop'
$taskRoot='D:/MCRF/Projects/MCRF'
$taskEvidence=Join-Path "$taskRoot/work/ports-test-2026-10-14" ('csqtt-owned-retirement-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$taskEvidence/temp" -Force|Out-Null
$taskPreviousTemp=$env:TEMP;$taskPreviousTmp=$env:TMP
$taskInput="$taskRoot/ports/android/transports/swg/mcrf_csqtt25_test.go"
$taskHash=(Get-FileHash -LiteralPath $taskInput).Hash.ToLowerInvariant()
$taskOk=$false
try{
    $env:TEMP="$taskEvidence/temp";$env:TMP=$env:TEMP
    & "$taskRoot/ports/android/transports/swg/build.ps1" -Csqtt25HostTest 2>&1 | Tee-Object -FilePath "$taskEvidence/host.log"
    if($LASTEXITCODE -ne 0){throw 'Owned bridge host checks failed'}
    $taskLog=Get-Content -LiteralPath "$taskEvidence/host.log" -Raw
    if($taskLog -notmatch '--- PASS: Test25SharedBridgeWaitsForOwnedTransportAndGrandchildCleanupAfterStop' -or $taskLog -match '(?m)^--- FAIL:|^FAIL\s'){throw 'New owned process cleanup test not proven'}
    $taskOk=(Get-FileHash -LiteralPath $taskInput).Hash.ToLowerInvariant() -ceq $taskHash
    if(!$taskOk){throw 'Canonical owned test changed during snapshot/check'}
}finally{
    $env:TEMP=$taskPreviousTemp;$env:TMP=$taskPreviousTmp
    [IO.File]::WriteAllText("$taskEvidence/receipt.json",(@{passed=$taskOk;testSourceSha256=$taskHash;timeUtc=[DateTime]::UtcNow.ToString('o');scope='Host-only actual shared Go bridge with owned fake transport and grandchild OS processes; STOP receipt must not become early completion; no provider/network/tunnel/Android/native-Rust execution or main/module APK';productionWriterWired=$false;graceOrKillPolicyChanged=$false}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    Write-Output "EVIDENCE=$taskEvidence"
}
