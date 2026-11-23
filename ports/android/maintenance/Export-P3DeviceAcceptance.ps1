param([Parameter(Mandatory)][string]$DeviceEvidence,[Parameter(Mandatory)][string]$ModuleProbe)
$ErrorActionPreference='Stop'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
$taskRoot=(Resolve-Path $DeviceEvidence).Path.Replace([char]92,[char]47)
$taskProbe=(Resolve-Path $ModuleProbe).Path.Replace([char]92,[char]47)
if($taskRoot -cnotmatch '^D:/MCRF/Builds/Android/p3-p4-device-checks-[a-f0-9]{32}$' -or $taskProbe -cnotmatch '^D:/MCRF/Builds/Android/p3-phone-module-probe-[a-f0-9]{32}$'){throw 'Exact owned evidence directories required'}
function Read-Guard([string]$Path){
 $taskText=[IO.File]::ReadAllText($Path)
 if($taskText -notmatch '(?m)^INSTRUMENTATION_RESULT: passed=true\s*$' -or $taskText -notmatch '(?m)^INSTRUMENTATION_RESULT: stable=true\s*$'){throw 'Stable successful protected-store guard required'}
 $taskMap=@{};foreach($taskLine in $taskText -split '\r?\n'){
  # Android puts the FIRST path on the protectedHashes result line itself.
  $taskLine=$taskLine -replace '^INSTRUMENTATION_RESULT: protectedHashes=',''
  if($taskLine -match '^((?:files|shared_prefs)/[^=]+)=([a-f0-9]{64}|absent)$'){$taskMap[$Matches[1]]=$Matches[2]}
 }
 if($taskMap.Count -lt 30){throw 'Incomplete protected-store inventory'};return $taskMap
}
$taskBefore=Read-Guard "$taskRoot/before-protected-stable.txt";$taskAfter=Read-Guard "$taskRoot/final-protected.txt"
if($taskBefore.Count -ne $taskAfter.Count -or @($taskBefore.Keys|Where-Object {!$taskAfter.ContainsKey($_)}).Count){throw 'Protected inventory keys changed'}
$taskChanges=@($taskBefore.Keys|Where-Object {$taskBefore[$_] -cne $taskAfter[$_]})
$taskAllowed=@('shared_prefs/engine-updates.xml','shared_prefs/android.app.ActivityThread.IDS.xml')
if(@($taskChanges|Where-Object {$_ -cnotin $taskAllowed}).Count){throw 'Unexpected user-data change; do not report acceptance'}
$taskProbeReceipt=Get-Content "$taskProbe/receipt.json" -Raw|ConvertFrom-Json
if($taskProbeReceipt.deviceEvidence -cne $taskRoot -or $taskProbeReceipt.apkSha256 -cne (Hash $taskProbeReceipt.apk) -or $taskProbeReceipt.sourceSha256 -cne (Hash "$taskProbe/P3PhoneModuleInstrumentation.java") -or $taskProbeReceipt.modulesSha256 -cne (Hash "$taskProbe/assets/modules.json")){throw 'Module probe identity changed'}
$taskRaw=[IO.File]::ReadAllText("$taskProbe/instrument.txt")
$taskPhases=@([regex]::Matches($taskRaw,'(?m)^INSTRUMENTATION_STATUS: phase=(HOST_UID_MODULE_AVAILABLE_[A-Z0-9]+_PASS)\s*$')|ForEach-Object {$_.Groups[1].Value})
if($taskPhases.Count -ne 14 -or @($taskPhases|Sort-Object -Unique).Count -ne 14 -or $taskRaw -notmatch '(?m)^INSTRUMENTATION_RESULT: passed=true\s*$' -or $taskRaw -notmatch '(?m)^INSTRUMENTATION_RESULT: libraries=20\s*$' -or $taskRaw -notmatch '(?m)^INSTRUMENTATION_RESULT: vpnStarted=false\s*$'){throw 'Fourteen host-UID module passes required'}
$taskResult=@{state='PHONE_INSTALL_PRESERVATION_AND_MODULE_ADMISSION_PASS';mainVersionCode=10049;mainVersionName='0.7-candidate.1';protectedKeys=$taskAfter.Count;userStoresUnchanged=$true;expectedChanges=$taskChanges;moduleChecks=14;readableExecutableArm64Elfs=20;nativeTransportExecuted=$false;phoneVpnStarted=$false;oldTestsReplayed=$false;published=$false;proofs=@{baselineSha256=Hash "$taskRoot/before-protected-stable.txt";finalGuardSha256=Hash "$taskRoot/final-protected.txt";moduleReceiptSha256=Hash "$taskProbe/receipt.json";moduleLogSha256=Hash "$taskProbe/instrument.txt"};limits='Installation, public native-file admission from main UID and read-only UI only; not real provider/throughput acceptance'}
[IO.File]::WriteAllText("$taskRoot/phone-acceptance.json",($taskResult|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
Write-Output "PHONE_ACCEPTANCE=14_MODULES_20_ARM64_ELFS_USER_STORES_UNCHANGED keys=$($taskAfter.Count)"
