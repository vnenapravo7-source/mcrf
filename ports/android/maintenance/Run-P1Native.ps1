param([Parameter(Mandatory=$true)][string]$WholeReceipt)
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF';$device='R5CX73QYN3D';$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
function Sha([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Json([string]$p){Get-Content -LiteralPath $p -Raw|ConvertFrom-Json}
function Adb([string[]]$a){$s=(& $adb -s $device @a 2>&1|Out-String).Trim();if($LASTEXITCODE){throw 'Owned Android command failed'};$s}
$whole=Json $WholeReceipt
if(!$whole.sourceStable -or $whole.state -cne 'COMMON_HOST_BUILD_PASS_ANDROID_NATIVE_TRAFFIC_ACCEPTANCE_PENDING'){throw 'Whole common build PASS required'}
$stage=$whole.phases.external_steer_two_abi_and_source_bound_stage.stage
$native=Json (Join-Path $stage 'receipt.json')
if((Sha (Join-Path $stage 'receipt.json')) -cne $whole.phases.external_steer_two_abi_and_source_bound_stage.sha256){throw 'Native origin changed'}
$fixtures=@($native.nativeFixtures|Where-Object abi -CEQ 'arm64-v8a')
$names=@('ip6_packet_test','host_routing_test','host_dualstack_flow_test','host_dualstack_wire_test')
if($fixtures.Count -ne 4){throw 'Exact four new native Android fixtures required'}
$currentPath=Join-Path $root 'ports/android/coordination/current.json';$job=$null
function Idle{
 $c=Json $currentPath;$l=Json (Join-Path $root 'ports/android/coordination/device-lease.json');$r=Json (Join-Path $root 'ports/android/coordination/p1-progress-2026-10-17.json')
 if($c.jobs.build -or $c.jobs.hostTest -or $c.jobs.emulator -or ($c.jobs.device -and $c.jobs.device -cne $job) -or
    $l.device -cne $device -or $l.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $l.state -cne 'occupied_main_fixture_acceptance' -or
    !$r.allowCommonDeviceVerification -or $r.deviceVerificationAuthority -cne 'human-p1-whole-owned-native'){throw 'Sole current authorized P1 lease required'}
 if((Adb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (Adb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Fresh awake/unlocked required'}
 if((Adb @('shell','getprop','ro.product.model')) -cne 'SM-F741B' -or (Adb @('shell','am','get-current-user')) -cne '0'){throw 'Exact owner Samsung required'}
 $vpn=Adb @('shell','dumpsys','vpn_management')
 if($vpn -notmatch 'Active vpn type:' -or $vpn -match 'Active vpn type: [01]\b'){throw 'No active user VPN may be touched'}
 if((Adb @('shell','dumpsys','activity','services','org.mcrf.client')) -match 'isForeground=true|startRequested=true|/\.McrfVpnService\b'){throw 'Phone service not idle'}
}
Idle
$id=[guid]::NewGuid().ToString('N');$remote='/data/local/tmp/mcrf-p1-native-'+$id
if($remote -cnotmatch '^/data/local/tmp/mcrf-p1-native-[0-9a-f]{32}$'){throw 'Owned private remote path required'}
$out=Join-Path $root ('work/ports-test-2026-10-14/p1-native-'+$id);[IO.Directory]::CreateDirectory($out)|Out-Null
$state=[ordered]@{passed=$false;wholeReceiptSha256=Sha $WholeReceipt;abi='arm64-v8a';assertionsEnabled=$true;fixtures=@();remoteFilesRemoved=$false;fullP1Accepted=$false;scope='Four NEW local native assertion fixtures only, not live VPN/Internet/x86 runtime acceptance'}
$made=$false;$copied=@()
try{
 $job='P1Native:'+$id;$c=Json $currentPath;$c.jobs.device=$job;[IO.File]::WriteAllText($currentPath,($c|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
 [void](Adb @('shell','mkdir','-m','700',$remote));$made=$true
 foreach($name in $names){
   Idle;$row=@($fixtures|Where-Object {$_.file -ceq "fixture-native/arm64-v8a/$name"})
   $file=Join-Path $stage "steer/fixture-native/arm64-v8a/$name"
   if($row.Count -ne 1 -or (Get-Item -LiteralPath $file).LinkType -or (Sha $file) -cne $row[0].sha256){throw 'Exact compiled native fixture origin required'}
   $target="$remote/$name";$copied+=$target;[void](Adb @('push',$file,$target))
   if((Adb @('shell','sha256sum',$target)) -cnotmatch ('^'+$row[0].sha256+'\s')){throw 'Uploaded native fixture changed'}
   [void](Adb @('shell','chmod','700',$target));$text=Adb @('shell','toybox','timeout','20',$target)
   [IO.File]::WriteAllText((Join-Path $out ($name+'.log')),$text,[Text.UTF8Encoding]::new($false))
   if($text -cnotmatch '^PASS'){throw 'Native assertion success marker missing'}
   $state.fixtures+=@{name=$name;sha256=$row[0].sha256;passed=$true}
 }
 $state.passed=$true
}finally{
 try{
   foreach($target in $copied){if(!$target.StartsWith($remote+'/') -or $target.Substring($remote.Length+1) -cnotin $names){throw 'Unexpected cleanup path'};[void](Adb @('shell','rm','-f',$target))}
   if($made){[void](Adb @('shell','rmdir',$remote))};$state.remoteFilesRemoved=$true
 }finally{
   $state.finishedUtc=[DateTime]::UtcNow.ToString('o');[IO.File]::WriteAllText((Join-Path $out 'receipt.json'),($state|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
   if($job){$c=Json $currentPath;if($c.jobs.device -ceq $job){$c.jobs.device=$null;[IO.File]::WriteAllText($currentPath,($c|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))}}
   Write-Output "P1_NATIVE_RECEIPT=$out/receipt.json"
 }
}
