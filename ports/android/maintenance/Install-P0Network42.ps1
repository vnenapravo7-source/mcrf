param([Parameter(Mandatory=$true)][string]$ReceiptPath,[switch]$AllowLauncher)
$ErrorActionPreference='Stop'
if($args.Count){throw 'Unknown arguments'}
$root='D:/MCRF/Projects/MCRF';$device='R5CX73QYN3D';$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$batch=Get-Content -LiteralPath $ReceiptPath -Raw|ConvertFrom-Json
if($batch.state -cne 'PASS' -or !$batch.sourceStable -or !$batch.excludedP1 -or $batch.versionCode -ne 10018 -or $batch.retainedHostTests -ne 34){throw 'Exact complete10018 P0 candidate required'}
if($ReceiptPath.Replace('\','/') -cne "$($batch.stage)/receipt.json" -or $batch.stage -cnotmatch '^D:/MCRF/Builds/Android/p0-network42-apk-[0-9a-f]{32}$'){throw 'Unexpected candidate receipt path'}
$candidate=$batch.artifacts.universal
$guard=Get-Content "$root/work/p0-data-guard-8509f0f4e30040888930e74758e270c2/receipt.json" -Raw|ConvertFrom-Json
if($guard.state -cne 'PASS' -or !$guard.sourceStable){throw 'Verified readonly data guardian required'}
foreach($artifact in @($candidate,$guard)){if((Get-FileHash -LiteralPath $artifact.apk -Algorithm SHA256).Hash.ToLowerInvariant() -cne $artifact.sha256){throw 'Artifact bytes changed'}}
if(@(Get-CimInstance Win32_Process|Where-Object {$_.Name -match '^(emulator|qemu-system.*)\.exe$'}).Count){throw 'Emulator active; phone operation blocked'}
function Adb([string[]]$Arguments){$out=(& $adb -s $device @Arguments 2>&1|Out-String).Trim();if($LASTEXITCODE){throw 'ADB failed; no alternate owner/device/retry'};$out}
function Lease {
    $lease=Get-Content "$root/ports/android/coordination/device-lease.json" -Raw|ConvertFrom-Json
    if($lease.device -cne $device -or $lease.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $lease.state -cne 'occupied_main_fixture_acceptance'){throw 'Exact main lease required'}
}
function Idle([switch]$RequireMcrf){
    Lease
    if((Adb @('shell','am','get-current-user')) -cne '0' -or (Adb @('shell','getprop','ro.product.model')) -cne 'SM-F741B' -or (Adb @('shell','getprop','ro.serialno')) -cne $device){throw 'Phone identity/owner changed'}
    if((Adb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (Adb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Phone locked/asleep; no wake/unlock'}
    foreach($package in @('org.mcrf.client','org.steer.prototype','com.wdtt.plus','csqtt.quic.amurcanov')){if((Adb @('shell','dumpsys','activity','services',$package)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'User service active; do not stop/install'}}
    if((Adb @('shell','dumpsys','vpn_management')) -match 'Active vpn type: [01]\b'){throw 'User VPN active; do not interfere'}
    $focus=((Adb @('shell','dumpsys','window')) -split '\r?\n'|Where-Object {$_ -match '\bmCurrentFocus='}) -join ''
    $allowed=if($RequireMcrf){'\borg\.mcrf\.client/'}else{'\b(org\.mcrf\.client|com\.sec\.android\.app\.launcher)/'}
    if($focus -notmatch $allowed){throw 'Foreground changed; wait for human MCRF return'}
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
    if($map.Count -ne 15){throw 'Expected exact existing15 modules'};$map
}
$evidence="$root/work/ports-test-2026-10-14/p0-network42-phone-"+[guid]::NewGuid().ToString('N')
[IO.Directory]::CreateDirectory($evidence)|Out-Null
function Checkpoint([string]$name){
    $out=Adb @('shell','am','instrument','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation')
    $out=[regex]::Replace($out,"\r+\n","`n");[IO.File]::WriteAllText("$evidence/$name.log",$out,[Text.UTF8Encoding]::new($false))
    if($out -notmatch 'passed=true' -or $out -notmatch 'stable=true' -or $out -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Protected checkpoint failed'}
    $match=[regex]::Match($out,'(?s)INSTRUMENTATION_RESULT: protectedHashes=(.*?)\r?\n\r?\n')
    if(!$match.Success -or $match.Groups[1].Value -notmatch 'files/profile.enc=' -or $match.Groups[1].Value -notmatch 'shared_prefs/identity.xml='){throw 'Incomplete protected inventory'}
    $match.Groups[1].Value.Trim()
}
$result=[ordered]@{passed=$false;buildReceipt=$ReceiptPath;candidate=$candidate.apk;sha256=$candidate.sha256;bytes=$candidate.bytes;versionCode=10018;device=$device;appInstalled=$false;protectedUnchanged=$false;modulesUnchanged=$false;published=$false;authority='Fresh human continue answers build/install10018 question; separate ready reply VPNoff/MCRFopen. Main-only update and readonly UI, no network session/service stop/setting edits/module installs/publication.';startedUtc=[DateTime]::UtcNow.ToString('o')}
if($AllowLauncher){$result.launcherAuthority='Fresh explicit YES call8203066287da43fd9315e84ef5ea6b32: install while exact Samsung launcherforeground; no otherapp/lock/VPN guard relaxation.'}
$currentPath="$root/ports/android/coordination/current.json";$registered=$false
try {
    $current=Get-Content $currentPath -Raw|ConvertFrom-Json
    if($current.jobs.device -or $current.jobs.build -or $current.jobs.hostTest){throw 'Another job registered; no duplicate'}
    $current.jobs.device="P0_NETWORK42_INSTALL:$evidence";$registered=$true;$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8
    Idle -RequireMcrf:(!$AllowLauncher)
    if((Installed 'org.mcrf.client') -cne '80b61bf1b31d646fb281cea38795d93d8246d7dda829b60c2b084def76404482'){throw 'Unknown installed main; do not overwrite/retry'}
    if((Installed 'org.mcrf.client.guard') -cne $guard.sha256){throw 'Readonly guardian identity changed'}
    $beforeModules=Modules;$beforeModules|ConvertTo-Json|Set-Content "$evidence/modules-before.json" -Encoding utf8
    $before=Checkpoint 'protected-before'
    Idle -RequireMcrf:(!$AllowLauncher)
    if((Adb @('install','--user','0','-r',$candidate.apk)) -notmatch '(?m)^Success\s*$'){throw 'Install failed; no uninstall/reset/retry'}
    $result.appInstalled=$true
    if((Installed 'org.mcrf.client') -cne $candidate.sha256){throw 'Installed bytes mismatch'}
    $after=Checkpoint 'protected-after';$afterModules=Modules
    $afterModules|ConvertTo-Json|Set-Content "$evidence/modules-after.json" -Encoding utf8
    $result.protectedUnchanged=$before -ceq $after;$result.modulesUnchanged=($beforeModules|ConvertTo-Json -Compress) -ceq ($afterModules|ConvertTo-Json -Compress);$result.moduleCount=$beforeModules.Count
    if(!$result.protectedUnchanged -or !$result.modulesUnchanged){throw 'Post-install drift; preserve evidence, never restore ciphertext'}
    $result.passed=$true
}catch{$result.error=$_.Exception.Message;throw}
finally {
    $result.finishedUtc=[DateTime]::UtcNow.ToString('o');$result|ConvertTo-Json -Depth 6|Set-Content "$evidence/receipt.json" -Encoding utf8
    if($registered){$current=Get-Content $currentPath -Raw|ConvertFrom-Json;if($current.jobs.device -ceq "P0_NETWORK42_INSTALL:$evidence"){$current.jobs.device=$null;$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8}}
    Write-Output "P0_NETWORK42_INSTALL=$($result.passed);RECEIPT=$evidence/receipt.json"
}
