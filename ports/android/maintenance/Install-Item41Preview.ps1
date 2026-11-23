param([Parameter(Mandatory=$true)][string]$ReceiptPath,[switch]$CorrectionRevision,[switch]$AfterSamsungRegression,[switch]$UiRound2,[switch]$UiRound2Keyboard,[switch]$SectionSelection,[switch]$SelectionHotfix,[switch]$HandoverRetirement)
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF';$device='R5CX73QYN3D';$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$batch=Get-Content $ReceiptPath -Raw|ConvertFrom-Json
$expectedCode=if($CorrectionRevision){10012}else{10011}
$expectedInstalled=if($CorrectionRevision){'35b1881362bc5555cf9bc97d9937578f22feedb6f674c8ca8023d8e0d8284eaa'}else{'f67f9ce0e06e4cecb7acedcb55ce23d213de03b119a9bdfbf1783bc05358ffa1'}
if($AfterSamsungRegression){if(!$CorrectionRevision -or !$batch.retainedUiEvidence){throw 'Exact complete parent correction required'};$expectedInstalled='5f48db040d8b6b85a3bd601f88c2bb349aa1fcaca98c8cc68d8c70e1acb81062'}
if($UiRound2){if($CorrectionRevision -or $AfterSamsungRegression -or !$batch.approvalRequiredBeforePublication){throw 'Exact phone-only UIround2 build required'};$expectedCode=10013;$expectedInstalled='e40c2446d2bf2d77951088dbe0e3f46a4bc1c9a130058aa18f6dd5ff624bb5fc'}
if($UiRound2Keyboard){
    if($CorrectionRevision -or $AfterSamsungRegression -or $UiRound2 -or !$batch.approvalRequiredBeforePublication -or !$batch.retainedUiEvidence){throw 'Exact phone-only keyboard correction required'}
    $expectedCode=10014;$parent=Get-Content $batch.retainedUiEvidence -Raw|ConvertFrom-Json
    if($parent.state -cne 'PASS' -or !$parent.sourceStable -or $parent.versionCode -ne 10013){throw 'Verified installed10013 parent required'}
    $expectedInstalled=$parent.artifacts.universal.sha256
}
if($SectionSelection){
    if($CorrectionRevision -or $AfterSamsungRegression -or $UiRound2 -or $UiRound2Keyboard -or !$batch.samePrereleaseAuthorized){throw 'Complete authorized selection batch required'}
    $expectedCode=10015
    $installedParent=Get-Content 'D:/MCRF/Builds/Android/p0-item41-27a801fd850544a4a34f0736911f8cd4/receipt.json' -Raw|ConvertFrom-Json
    if($installedParent.state -cne 'PASS' -or !$installedParent.sourceStable -or $installedParent.versionCode -ne 10013){throw 'Exact installed10013 required;10014 not installed'}
    $expectedInstalled=$installedParent.artifacts.universal.sha256
}
if($SelectionHotfix){
    if($CorrectionRevision -or $AfterSamsungRegression -or $UiRound2 -or $UiRound2Keyboard -or $SectionSelection -or $batch.samePrereleaseAuthorized){throw 'Exact phone-only hotfix required'}
    $expectedCode=10016;$expectedInstalled='1367cd28db5b7517e1701942afde3d777f83485cc0152b89fe107c924fadb304'
}
if($HandoverRetirement){
    if($CorrectionRevision -or $AfterSamsungRegression -or $UiRound2 -or $UiRound2Keyboard -or $SectionSelection -or $SelectionHotfix -or $batch.samePrereleaseAuthorized){throw 'Exact phone-only retirement correction required'}
    $expectedCode=10017;$parent=Get-Content 'D:/MCRF/Builds/Android/p0-item41-f9780f6018584c56a5e4fa61df38ffd5/receipt.json' -Raw|ConvertFrom-Json
    if($parent.state -cne 'PASS' -or !$parent.sourceStable -or $parent.versionCode -ne 10016){throw 'Verified installed10016 parent required'}
    $expectedInstalled=$parent.artifacts.universal.sha256
}
if($batch.state -cne 'PASS' -or !$batch.sourceStable -or !$batch.excludedP1 -or $batch.versionCode -ne $expectedCode){throw 'Verified item41 candidate required'}
if($CorrectionRevision -and !$batch.retainedRecentsEvidence){throw 'Full corrections receipt required; not preliminary Recents-only APK'}
$candidate=$batch.artifacts.universal
$guard=Get-Content "$root/work/p0-data-guard-8509f0f4e30040888930e74758e270c2/receipt.json" -Raw|ConvertFrom-Json
if($guard.state -cne 'PASS' -or !$guard.sourceStable){throw 'Verified read-only guard required'}
foreach($artifact in @($candidate,$guard)){if((Get-FileHash $artifact.apk -Algorithm SHA256).Hash.ToLowerInvariant() -cne $artifact.sha256){throw 'Artifact bytes changed'}}
$lease=Get-Content "$root/ports/android/coordination/device-lease.json" -Raw|ConvertFrom-Json
if($lease.device -cne $device -or $lease.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $lease.state -cne 'occupied_main_fixture_acceptance'){throw 'Exact authorized main lease required'}
if(@(Get-CimInstance Win32_Process|Where-Object {$_.Name -match '^(emulator|qemu-system.*)\.exe$'}).Count){throw 'Emulator active; phone operation blocked'}
function Adb([string[]]$Arguments){$out=(& $adb -s $device @Arguments 2>&1|Out-String).Trim();if($LASTEXITCODE){throw 'ADB failed; no alternate owner/device/retry'};$out}
function Idle {
    if((Adb @('shell','am','get-current-user')) -cne '0' -or (Adb @('shell','getprop','ro.product.model')) -cne 'SM-F741B' -or (Adb @('shell','getprop','ro.serialno')) -cne $device){throw 'Phone identity/owner changed'}
    if((Adb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (Adb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Phone unavailable; do not wake/unlock'}
    foreach($package in @('org.mcrf.client','org.steer.prototype','com.wdtt.plus','csqtt.quic.amurcanov')){if((Adb @('shell','dumpsys','activity','services',$package)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'User service active; do not stop/install'}}
    if((Adb @('shell','dumpsys','vpn_management')) -match 'Active vpn type: [01]\b'){throw 'Active VPN; do not interfere'}
}
function Installed([string]$package){
    $path=Adb @('shell','pm','path','--user','0',$package)
    if($path -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Unexpected APK path'}
    $sum=Adb @('shell','sha256sum',$Matches[1]);if($sum -cnotmatch '^([0-9a-f]{64})\s'){throw 'Missing installed SHA'};$Matches[1]
}
function Modules {
    $map=[ordered]@{}
    foreach($line in ((Adb @('shell','pm','list','packages','--user','0','org.mcrf.engines')) -split '\r?\n'|Sort-Object)){
        if($line -cnotmatch '^package:(org\.mcrf\.engines(?:\.[a-z0-9]+)?)$'){throw 'Unexpected module inventory'}
        $package=$Matches[1];$map[$package]=Installed $package
    }
    if($map.Count -ne 15){throw 'Expected exact existing 15 modules'};$map
}
$evidence=Join-Path $root ('work/ports-test-2026-10-14/item41-phone-'+[guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($evidence)|Out-Null
function Checkpoint([string]$name){
    $out=Adb @('shell','am','instrument','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation')
    $out=[regex]::Replace($out,"\r+\n","`n");[IO.File]::WriteAllText("$evidence/$name.log",$out,[Text.UTF8Encoding]::new($false))
    if($out -notmatch 'passed=true' -or $out -notmatch 'stable=true' -or $out -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Protected checkpoint failed'}
    $match=[regex]::Match($out,'(?s)INSTRUMENTATION_RESULT: protectedHashes=(.*?)\r?\n\r?\n')
    if(!$match.Success -or $match.Groups[1].Value -notmatch 'files/profile.enc=' -or $match.Groups[1].Value -notmatch 'shared_prefs/identity.xml='){throw 'Incomplete protected inventory'}
    $match.Groups[1].Value.Trim()
}
$result=[ordered]@{passed=$false;candidate=$candidate.apk;sha256=$candidate.sha256;versionCode=$expectedCode;device=$device;authority='Fresh human item41 complete correction batch: update main phone then refresh SAME existing prerelease416790557, not new release. Main-only; no fixture/module install, reset or network grant reuse.';appInstalled=$false;protectedUnchanged=$false;modulesUnchanged=$false;startedUtc=[DateTime]::UtcNow.ToString('o')}
if($UiRound2 -or $UiRound2Keyboard){$result.authority='Fresh human item41 UIround2: update main phone and check UI ONLY. WAIT separate explicit human approval before any SAME prerelease416790557 file refresh. No VPN/fixture/module/reset/credentials authority.';$result.publicationAllowed=$false}
$currentPath="$root/ports/android/coordination/current.json";$registered=$false
try {
    $current=Get-Content $currentPath -Raw|ConvertFrom-Json
    if($current.jobs.device){throw 'Device session already registered'}
    $current.jobs.device="ITEM41_INSTALL:$evidence";$registered=$true;$current|ConvertTo-Json -Depth 16|Set-Content $currentPath -Encoding utf8
    Idle
    if($UiRound2 -or $UiRound2Keyboard -or $SectionSelection -or $SelectionHotfix -or $HandoverRetirement){
        $focus=((Adb @('shell','dumpsys','window')) -split '\r?\n'|Where-Object {$_ -match '\bmCurrentFocus='}) -join ''
        if($focus -notmatch '\b(org\.mcrf\.client|com\.sec\.android\.app\.launcher)/'){throw 'User is using another app; wait for explicit return to MCRF'}
    }
    if((Installed 'org.mcrf.client') -cne $expectedInstalled){throw 'Unknown installed main; do not overwrite'}
    if((Installed 'org.mcrf.client.guard') -cne $guard.sha256){throw 'Read-only guardian identity changed'}
    $beforeModules=Modules;$beforeModules|ConvertTo-Json|Set-Content "$evidence/modules-before.json" -Encoding utf8
    $before=Checkpoint 'protected-before'
    Idle
    if((Adb @('install','--user','0','-r',$candidate.apk)) -notmatch '(?m)^Success\s*$'){throw 'Install failed; no uninstall/reset/retry'}
    $result.appInstalled=$true
    if((Installed 'org.mcrf.client') -cne $candidate.sha256){throw 'Installed bytes differ'}
    $after=Checkpoint 'protected-after';$afterModules=Modules
    $afterModules|ConvertTo-Json|Set-Content "$evidence/modules-after.json" -Encoding utf8
    $result.protectedUnchanged=$before -ceq $after;$result.modulesUnchanged=($beforeModules|ConvertTo-Json -Compress) -ceq ($afterModules|ConvertTo-Json -Compress)
    $result.moduleCount=$beforeModules.Count
    if(!$result.protectedUnchanged -or !$result.modulesUnchanged){throw 'Post-install drift: keep evidence, do not restore ciphertext'}
    $result.passed=$true
}catch{$result.error=$_.Exception.Message;throw}
finally {
    $result.finishedUtc=[DateTime]::UtcNow.ToString('o');$result|ConvertTo-Json -Depth 6|Set-Content "$evidence/receipt.json" -Encoding utf8
    if($registered){$current=Get-Content $currentPath -Raw|ConvertFrom-Json;if($current.jobs.device -ceq "ITEM41_INSTALL:$evidence"){$current.jobs.device=$null;$current|ConvertTo-Json -Depth 16|Set-Content $currentPath -Encoding utf8}}
    Write-Output "ITEM41_INSTALL=$($result.passed);RECEIPT=$evidence/receipt.json"
}
