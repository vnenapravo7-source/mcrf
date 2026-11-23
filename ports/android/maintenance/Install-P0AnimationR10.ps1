param([Parameter(Mandatory=$true)][string]$ReceiptPath)
$ErrorActionPreference='Stop'
$taskRoot='D:/MCRF/Projects/MCRF';$taskDevice='R5CX73QYN3D'
$taskBatch=Get-Content -LiteralPath $ReceiptPath -Raw|ConvertFrom-Json
if($taskBatch.state -cne 'PASS' -or $taskBatch.versionCode -ne 10010 -or !$taskBatch.sourceStable -or !$taskBatch.excludedP1){throw 'Verified immutable R10 batch required'}
$taskCandidate=$taskBatch.artifacts.main
$taskCandidate|Add-Member state $taskBatch.state
$taskCandidate|Add-Member sourceStable $taskBatch.sourceStable
$taskCandidate|Add-Member excludedP1 $taskBatch.excludedP1
$taskGuard=Get-Content -LiteralPath "$taskRoot/work/p0-data-guard-8509f0f4e30040888930e74758e270c2/receipt.json" -Raw|ConvertFrom-Json
if($taskCandidate.state -cne 'PASS' -or !$taskCandidate.sourceStable -or !$taskCandidate.excludedP1 -or $taskGuard.state -cne 'PASS' -or !$taskGuard.sourceStable){throw 'Verified P0-only candidate and read-only guard required'}
foreach($taskArtifact in @($taskCandidate,$taskGuard)){if((Get-FileHash -LiteralPath $taskArtifact.apk -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskArtifact.sha256){throw 'Verified P0 artifact bytes changed'}}
$taskLeasePath=Join-Path $taskRoot 'ports/android/coordination/device-lease.json'
$taskLease=Get-Content -LiteralPath $taskLeasePath -Raw|ConvertFrom-Json
if($taskLease.device -cne $taskDevice -or $taskLease.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $taskLease.state -cne 'occupied_main_fixture_acceptance'){throw 'Exact authorized phone/main lease required'}
if(@(Get-CimInstance Win32_Process|Where-Object {$_.Name -match '^(emulator|qemu-system.*)\.exe$'}).Count){throw 'Close owned emulator before physical phone operations'}
$taskAdb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
function Adb([string[]]$Arguments){$taskOut=(& $taskAdb -s $taskDevice @Arguments 2>&1|Out-String).Trim();if($LASTEXITCODE){throw 'Authorized owner-user0 ADB operation failed; no retry/other profiles'};return $taskOut}
function Idle{
    if((Adb @('shell','am','get-current-user')) -cne '0' -or (Adb @('shell','getprop','ro.product.model')) -cne 'SM-F741B' -or (Adb @('shell','getprop','ro.serialno')) -cne 'R5CX73QYN3D'){throw 'Authorized physical identity/owner changed'}
    if((Adb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (Adb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Phone unavailable; never wake/unlock'}
    foreach($taskPackage in @('org.mcrf.client','org.steer.prototype','com.wdtt.plus','csqtt.quic.amurcanov')){if((Adb @('shell','dumpsys','activity','services',$taskPackage)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'User service active; do not stop/install'}}
    if((Adb @('shell','dumpsys','vpn_management')) -match 'Active vpn type: [01]\b'){throw 'Active VPN; do not interfere'}
}
function Installed([string]$Package){
    $taskPath=Adb @('shell','pm','path','--user','0',$Package)
    if($taskPath -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Unexpected installed APK path'}
    $taskSha=Adb @('shell','sha256sum',$Matches[1]);if($taskSha -cnotmatch '^([0-9a-f]{64})\s'){throw 'Missing installed APK SHA'};return $Matches[1]
}
function Modules{
    $taskMap=[ordered]@{}
    foreach($taskLine in ((Adb @('shell','pm','list','packages','--user','0','org.mcrf.engines')) -split '\r?\n'|Sort-Object)){
        if($taskLine -cnotmatch '^package:(org\.mcrf\.engines(?:\.[a-z0-9]+)?)$'){throw 'Unexpected module inventory'}
        $taskPackage=$Matches[1];$taskMap[$taskPackage]=Installed $taskPackage
    }
    if($taskMap.Count -lt 7){throw 'Expected external engines missing'};return $taskMap
}
$taskEvidence=Join-Path $taskRoot ('work/ports-test-2026-10-14/p0-r10-phone-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskEvidence|Out-Null
function Checkpoint([string]$Name){
    $taskOutput=Adb @('shell','am','instrument','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation')
    $taskOutput=[regex]::Replace($taskOutput,"\r+\n","`n")
    [IO.File]::WriteAllText("$taskEvidence/$Name.log",$taskOutput,[Text.UTF8Encoding]::new($false))
    if($taskOutput -notmatch 'passed=true' -or $taskOutput -notmatch 'stable=true' -or $taskOutput -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Complete protected checkpoint failed'}
    $taskMatch=[regex]::Match($taskOutput,'(?s)INSTRUMENTATION_RESULT: protectedHashes=(.*?)\r?\n\r?\n')
    if(!$taskMatch.Success -or $taskMatch.Groups[1].Value -notmatch 'files/profile.enc=' -or $taskMatch.Groups[1].Value -notmatch 'shared_prefs/identity.xml='){throw 'Complete profile/identity inventory missing'}
    return $taskMatch.Groups[1].Value.Trim()
}
$taskResult=[ordered]@{passed=$false;device=$taskDevice;candidate=$taskCandidate.apk;sha256=$taskCandidate.sha256;bytes=$taskCandidate.bytes;authority='Fresh human OK install on phone and verify all available item40 R10. Main10010 plus fresh isolated UI fixture; no publication/module update/old permission reuse.';appInstalled=$false;guardInstalled=$false;protectedUnchanged=$false;modulesUnchanged=$false;startedUtc=[DateTime]::UtcNow.ToString('o')}
try{
    $taskCurrentPath="$taskRoot/ports/android/coordination/current.json"
    $taskCurrent=Get-Content -LiteralPath $taskCurrentPath -Raw|ConvertFrom-Json
    if($taskCurrent.jobs.device){throw 'Existing main device session must finish'}
    $taskCurrent.jobs.device="P0_R10_INSTALL:$taskEvidence"
    $taskCurrent|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $taskCurrentPath -Encoding utf8
    Idle
    if(Adb @('shell','pm','list','packages','--user','0','org.mcrf.client.animationfixture10')){throw 'Fresh fixture UID already exists; do not clear/reuse'}
    $taskOld=Installed 'org.mcrf.client'
    if($taskOld -cne '897b0da523e2b4dda3bf23286fd4c77abdce7acc9d2c75e5adb1422fa773e75f'){throw 'Current app differs from exact prior installed P0; do not overwrite unknown version'}
    $taskModulesBefore=Modules
    [IO.File]::WriteAllText("$taskEvidence/modules-before.json",($taskModulesBefore|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    if((Installed 'org.mcrf.client.guard') -cne $taskGuard.sha256){throw 'Existing read-only guardian identity changed'}
    $taskResult.guardAlreadyInstalled=$true
    $taskBefore=Checkpoint 'protected-before'
    Idle
    if((Adb @('install','--user','0','-r',$taskCandidate.apk)) -notmatch '(?m)^Success\s*$'){throw 'P0 same-signer install failed; no uninstall/reset/retry'}
    $taskResult.appInstalled=$true
    if((Installed 'org.mcrf.client') -cne $taskCandidate.sha256){throw 'Installed P0 bytes differ'}
    $taskAfter=Checkpoint 'protected-after'
    $taskModulesAfter=Modules
    [IO.File]::WriteAllText("$taskEvidence/modules-after.json",($taskModulesAfter|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    $taskResult.protectedUnchanged=$taskBefore -ceq $taskAfter
    $taskResult.modulesUnchanged=($taskModulesBefore|ConvertTo-Json -Compress) -ceq ($taskModulesAfter|ConvertTo-Json -Compress)
    $taskResult.moduleCount=$taskModulesBefore.Count
    if(!$taskResult.protectedUnchanged -or !$taskResult.modulesUnchanged){throw 'Post-install drift: preserve evidence, never restore old user data'}
    foreach($taskKind in @('fixture','runner')){
        Idle
        $taskArtifact=$taskBatch.artifacts.$taskKind
        if((Get-FileHash -LiteralPath $taskArtifact.apk -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskArtifact.sha256){throw 'Fixture artifact changed'}
        if((Adb @('install','--user','0',$taskArtifact.apk)) -notmatch '(?m)^Success\s*$'){throw 'Fresh fixture install failed; no reset/retry'}
    }
    $taskResult.fixtureInstalled=$true
    $taskResult.snapshot=$taskBatch.stage
    $taskResult.guardComponent='org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation'
    $taskResult.installed=@(@{package='org.mcrf.client';sha256=$taskCandidate.sha256})
    $taskFinal=Checkpoint 'protected-after-fixture-install'
    if($taskFinal -cne $taskBefore){throw 'Fixture install changed protected main data'}
    $taskResult.passed=$true
}catch{$taskResult.error=$_.Exception.Message;throw}
finally{
    [IO.File]::WriteAllText("$taskEvidence/receipt.json",($taskResult|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
    if($taskCurrentPath){
        $taskCurrent=Get-Content -LiteralPath $taskCurrentPath -Raw|ConvertFrom-Json
        if($taskCurrent.jobs.device -ceq "P0_R10_INSTALL:$taskEvidence"){$taskCurrent.jobs.device=$null;$taskCurrent|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $taskCurrentPath -Encoding utf8}
    }
    Write-Output "P0_PHONE_INSTALL=$($taskResult.passed);RECEIPT=$taskEvidence/receipt.json"
}
