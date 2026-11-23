param()
$ErrorActionPreference='Stop'
if($args.Count){throw 'No additional finalization targets permitted'}
$root='D:/MCRF/Projects/MCRF';$android=Join-Path $root 'ports/android'
$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe';$device='R5CX73QYN3D'
$wholePath='D:/MCRF/Builds/Android/p1-whole-17fb34f9d2324793918321b1fe266a7e/receipt.json'
$installPath=Join-Path $root 'work/ports-test-2026-10-14/p1-install-a542ef10d7ab417987d8d6feba214866/receipt.json'
$ledgerPath=Join-Path $android 'coordination/p1-device-acceptance-2026-10-19.json'
$inputs=[ordered]@{};$gates=[ordered]@{}
function Sha([string]$path){(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}
function Read([string]$path){
 $path=[IO.Path]::GetFullPath($path)
 if(!$path.StartsWith('D:\MCRF\',[StringComparison]::OrdinalIgnoreCase) -or !(Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).LinkType){throw 'Ordinary owned receipt required'}
 $inputs[$path]=Sha $path;Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
}
function Adb([string[]]$a){$v=(& $adb -s $device @a 2>&1|Out-String).Trim();if($LASTEXITCODE){throw 'Final owned ADB operation failed'};return $v}
function Installed([string]$package){
 $path=Adb @('shell','pm','path','--user','0',$package)
 if($path -cnotmatch ('^package:(/data/app/(?:[A-Za-z0-9_~+=-]+/)?'+[regex]::Escape($package)+'-[A-Za-z0-9_~+=-]+/base\.apk)$')){throw 'Exact installed package path required'}
 $remote=$Matches[1];$hash=Adb @('shell','sha256sum',$remote)
 if($hash -cnotmatch ('^([0-9a-f]{64})\s+'+[regex]::Escape($remote)+'$')){throw 'Installed hash missing'};$Matches[1]
}
$whole=Read $wholePath;$install=Read $installPath;$ledger=Read $ledgerPath
if((Sha $wholePath) -cne 'f77f2081fb2dc2049d46b3744f25b0a8d06db036fb74c50f0d4a23c3e782e7a2' -or
 $whole.state -cne 'COMMON_HOST_BUILD_PASS_ANDROID_NATIVE_TRAFFIC_ACCEPTANCE_PENDING' -or !$whole.sourceStable){throw 'Final common build incomplete'}
foreach($name in @('main_affected_models_and_compilation','steer_models_and_actual_packaged_rule_sets','independent_steer_peer_new_cases','wg_awg_and_managed_kernel_dns_host','independent_packet_peer_new_cases','external_wg_three_abi_and_adapter','external_steer_two_abi_and_source_bound_stage','common_main_r8_debug_runner_helpers_and_external_modules')){
 if($whole.phases.$name.state -cne 'PASS'){throw 'Mandatory common phase missing'};$gates[$name]='PASS retained/executed source-bound'
}
if($install.state -cne 'INSTALLED_NOT_RUNTIME_ACCEPTED' -or !$install.protectedUnchanged -or !$install.unrelatedModulesUnchanged -or $install.wholeReceiptSha256 -cne (Sha $wholePath) -or @($install.artifacts).Count -ne 6){throw 'Final installed ownership incomplete'}
# Retain old Android results only while every production input is identical.
# Exactly THREE changed main classes are narrow private DEBUG fixture code;
# actual transport/native/owner/proxy/rule/DNS implementations are unchanged.
$priorPath='D:/MCRF/Builds/Android/p1-whole-ec6e846c0714410f8b80ebae41512ccd/source-inputs.json'
$old=Read $priorPath;$fresh=Read (Join-Path $whole.stage 'source-inputs.json')
$pattern='^(ports/android/(app/src/main/|primary-spi/src/|primary-adapter/src/|primary-cores/|transports/wg-backend/|wg-module/src/)|work/steer-android-port/(src/|android-vpn/(core/src/main/|integration-helper/|module-contract/|primary-adapter/)))'
$oldRows=@($old|Where-Object {$_.path -match $pattern});$newRows=@($fresh|Where-Object {$_.path -match $pattern})
if($oldRows.Count -ne $newRows.Count){throw 'Retained runtime source set changed'}
$changed=@();$allowed=@('ports/android/app/src/main/kotlin/org/mcrf/client/ClientPacketFixture.kt','ports/android/app/src/main/kotlin/org/mcrf/client/ClientSteerFixture.kt','ports/android/app/src/main/kotlin/org/mcrf/client/PacketManagedDnsFixture.kt')
for($i=0;$i -lt $oldRows.Count;$i++){
 if($oldRows[$i].path -cne $newRows[$i].path){throw 'Retained runtime input identity changed'}
 if($oldRows[$i].sha256 -cne $newRows[$i].sha256){if($oldRows[$i].path -cnotin $allowed){throw 'Actual closed runtime input changed'};$changed+=$oldRows[$i].path}
}
if($changed.Count -ne 3){throw 'Unexpected private fixture source delta'}
foreach($property in $ledger.closedDoNotRerunUnchanged.PSObject.Properties){
 if($property.Value -cnotmatch '^(.+\.json)(?::.*)?$'){throw 'Exact closed gate receipt required'}
 $path=Join-Path $root $Matches[1];$row=Read $path;$pass=$false
 if($property.Name -ceq 'nativeFourArm64Assertions'){$pass=$row.passed -and $row.abi -ceq 'arm64-v8a' -and $row.assertionsEnabled -and $row.remoteFilesRemoved -and @($row.fixtures).Count -eq 4 -and @($row.fixtures|Where-Object {!$_.passed}).Count -eq 0}
 elseif($property.Name -ceq 'originalKernelOwnerTcpUdpBothFamiliesAndStop'){$pass=$row.user_files_unchanged -and @($row.results).Count -eq 1 -and $row.results[0].state -ceq 'platform_original_owner_passed_not_full_p1' -and $row.results[0].passed -eq 8}
 elseif($property.Name -ceq 'peerCapacityOneNewEightRetained'){$pass=$row.passed -and $row.executedCases -eq 1 -and $row.retainedCases -eq 8}
 elseif($row.PSObject.Properties.Name -ccontains 'scopedPassed'){
  $pass=$row.scopedPassed -and $row.originalReceiptsPreserved -and !$row.newTrafficExecuted -and !$row.phoneTouched
  foreach($pair in @(@('deviceReceipt','deviceReceiptSha256'),@('trafficReceipt','trafficReceiptSha256'))){if((Sha $row.($pair[0])) -cne $row.($pair[1])){throw 'Reviewed original evidence changed'};[void](Read $row.($pair[0]))}
  $deviceProof=Read $row.deviceReceipt
  if(@($deviceProof.results).Count -ne 1 -or $deviceProof.results[0].log -cnotmatch '^[a-z0-9TZ_.-]+\.log$'){throw 'Exact reviewed device log missing'}
  $log=Join-Path ([IO.Path]::GetDirectoryName($row.deviceReceipt)) $deviceProof.results[0].log
  if((Sha $log) -cne $row.deviceLogSha256){throw 'Reviewed original log changed'};$inputs[$log]=Sha $log
 }
 elseif($row.PSObject.Properties.Name -ccontains 'actual_scoped_gate_passed'){$pass=$row.actual_scoped_gate_passed -and $row.private_files_removed -and $row.android.cleanup_verified -and $row.android.user_state_unchanged -and $row.peer.all_sockets_closed -and $row.peer.listener_closed}
 elseif($row.PSObject.Properties.Name -ccontains 'rows'){
  $rows=if($property.Name -ceq 'wgAtomicBlockBothFamilies'){@($row.rows[0])}else{@($row.rows)}
  $pass=$row.privateKeysRemoved -and $rows.Count -gt 0 -and @($rows|Where-Object {!$_.passed -or !$_.phonePassed -or !$_.peer.handshake_observed -or $_.peer.blocked_packets -ne 0 -or $_.peer.internal_dns_packets -ne 0}).Count -eq 0
  foreach($packetRow in $rows){
   $directory=Join-Path ([IO.Path]::GetDirectoryName($path)) $packetRow.version;$deviceProof=Read (Join-Path $directory 'device-result.json')
   $log=Join-Path $directory 'packet-vpn.log';$inputs[$log]=Sha $log
   if(!$deviceProof.user_files_unchanged -or @($deviceProof.results).Count -ne 1 -or $deviceProof.results[0].passed -lt 5 -or
     ![IO.File]::ReadAllText($log).Contains('WG/AWG STOP retires owned VPN/worker; private inputs removed and user profiles/preferences preserved')){throw 'Actual packet STOP/profile proof missing'}
  }
 }else{throw 'Unknown closed gate schema'}
 if(!$pass){throw ('Mandatory actual owned gate incomplete: '+$property.Name)};$gates[$property.Name]='PASS exact original evidence retained'
}
foreach($name in @('wgManagedDohUntrustedCert','wgManagedDotUntrustedCert','wgManagedDohWrongHostname','wgManagedDotWrongHostname','steerManagedDohTrustHostnameAndPermittedAfter','steerManagedDotTrustHostnameAndPermittedAfter','awg3Awg31ActualAndroidBlockHttpDnsBothFamiliesAndStop')){if(!$gates.Contains($name)){throw 'Final mandatory gate absent'}}
$grant=Read (Join-Path $android 'coordination/p1-device-authority-2026-10-19.json')
$liveReady=Get-Content -LiteralPath (Join-Path $android 'coordination/p1-progress-2026-10-17.json') -Raw|ConvertFrom-Json
if($grant.state -cne 'ACTIVE_CONDITIONAL_ON_COMMON_PASS' -or !$grant.install -or !$grant.verify -or
 !$liveReady.allowCommonDeviceVerification -or $liveReady.deviceVerificationAuthority -cne 'human-p1-whole-owned-native' -or
 $grant.humanReply -cne 'call_b9b0a2a31ed64ef0ac76ddd41403d993' -or $grant.wholeReceiptSha256 -cne (Sha $wholePath) -or !$grant.restoreTemporarySettings -or $grant.stopUserVpn -or $grant.publish){throw 'Fresh exact owned cleanup authority required; completed grant cannot be replayed'}
$guard=Read (Join-Path $root 'work/p0-data-guard-8509f0f4e30040888930e74758e270c2/receipt.json')
function Idle {
 $lease=Get-Content (Join-Path $android 'coordination/device-lease.json') -Raw|ConvertFrom-Json
 $current=Get-Content (Join-Path $android 'coordination/current.json') -Raw|ConvertFrom-Json
 if($lease.device -cne $device -or $lease.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $lease.state -cne 'occupied_main_fixture_acceptance' -or $current.jobs.build -or $current.jobs.device -or $current.jobs.hostTest -or $current.jobs.emulator){throw 'Sole fresh idle main lease required'}
 if((Adb @('shell','am','get-current-user')) -cne '0' -or (Adb @('shell','getprop','ro.product.model')) -cne 'SM-F741B'){throw 'Wrong phone owner'}
 if((Adb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (Adb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Fresh awake unlocked screen required'}
 if((Adb @('shell','dumpsys','vpn_management')) -match 'Active vpn type: [01]\b' -or (Adb @('shell','dumpsys','activity','services','org.mcrf.client')) -match 'isForeground=true|startRequested=true|/\.McrfVpnService\b'){throw 'No user VPN/service stop permitted'}
}
$evidence=Join-Path $root ('work/ports-test-2026-10-14/p1-final-'+[guid]::NewGuid().ToString('N'));[IO.Directory]::CreateDirectory($evidence)|Out-Null
$result=[ordered]@{state='PREPARING';wholeReceiptSha256=Sha $wholePath;installReceiptSha256=Sha $installPath;controllerSha256=Sha $PSCommandPath;sourceDeltaOnlyPrivateFixtures=$changed;gates=$gates;inputs=$inputs;temporaryHelpersRemoved=@();protectedUnchanged=$false;unrelatedModulesUnchanged=$false;userVpnStopped=$false;published=$false;fullProjectAccepted=$false;scope='Supported bounded P1 Steer rules/DNS/original UID/authenticated loopback HTTP+SOCKS/IPv6 and WG/AWG3/3.1 IP/BLOCK/managed DNS, owned Samsung fixtures. Not Vivo, arbitrary providers/accounts/VPS, standalone/LAN proxy or every physical IPv6 network.'}
function Save {[IO.File]::WriteAllText((Join-Path $evidence 'receipt.json'),($result|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
function Protected([string]$name){
 $out=Adb @('shell','am','instrument','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation');$out=[regex]::Replace($out,"\r+\n","`n")
 [IO.File]::WriteAllText((Join-Path $evidence ($name+'.log')),$out,[Text.UTF8Encoding]::new($false))
 $hashes=[regex]::Match($out,'(?s)INSTRUMENTATION_RESULT: protectedHashes=(.*?)\n\n')
 if($out -notmatch 'passed=true' -or $out -notmatch 'stable=true' -or $out -notmatch 'INSTRUMENTATION_CODE: -1' -or !$hashes.Success){throw 'Protected inventory not stable'};$hashes.Groups[1].Value.Trim()
}
function Modules {
 $map=[ordered]@{};foreach($line in ((Adb @('shell','pm','list','packages','--user','0','org.mcrf.engines')) -split '\r?\n'|Sort-Object)){
  if($line -cnotmatch '^package:(org\.mcrf\.engines(?:\.[a-z0-9]+)?)$'){throw 'Unexpected module inventory'};$package=$Matches[1];$map[$package]=Installed $package
 };$map
}
try {
 Idle
 if($guard.state -cne 'PASS' -or !$guard.sourceStable -or (Sha $guard.apk) -cne $guard.sha256 -or (Installed 'org.mcrf.client.guard') -cne $guard.sha256){throw 'Verified existing guardian required'}
 foreach($artifact in $install.artifacts){if((Installed $artifact.package) -cne $artifact.sha256){throw 'Current installed candidate changed'}}
 $uidLine=Adb @('shell','pm','list','packages','--user','0','-U','org.mcrf.p1owner')
 $ownerUid=[regex]::Match($uidLine,'(?m)^package:org\.mcrf\.p1owner uid:([0-9]+)\s*$')
 if(!$ownerUid.Success){throw 'Owned temporary UID unavailable'}
 $shared=@((Adb @('shell','pm','list','packages','--user','0','--uid',$ownerUid.Groups[1].Value)) -split '\r?\n')
 $sharedNames=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
 foreach($line in $shared){
  if($line -cnotmatch '^package:(org\.mcrf\.p1owner(?:\.peer)?)(?: uid:([0-9]+))?$' -or
    $Matches[2] -and $Matches[2] -cne $ownerUid.Groups[1].Value -or !$sharedNames.Add($Matches[1])){throw 'Temporary UID has unrelated packages; no removal'}
 }
 if($shared.Count -ne 2 -or !$sharedNames.SetEquals([string[]]@('org.mcrf.p1owner','org.mcrf.p1owner.peer'))){throw 'Temporary UID has unrelated packages; no removal'}
 $before=Protected 'protected-before-cleanup';$baseline=[IO.File]::ReadAllText((Join-Path ([IO.Path]::GetDirectoryName($installPath)) 'protected-after.log'))
 $baseline=[regex]::Match([regex]::Replace($baseline,"\r+\n","`n"),'(?s)INSTRUMENTATION_RESULT: protectedHashes=(.*?)\n\n').Groups[1].Value.Trim()
 if(!$baseline -or $before -cne $baseline -or @($before -split '\n').Count -lt 20){throw 'Protected data changed; no historical ciphertext rollback'}
 $modules=Modules;Idle
 foreach($package in @('org.mcrf.p1owner.peer','org.mcrf.p1owner')){
  $expected=@($install.artifacts|Where-Object {$_.package -ceq $package});if($expected.Count -ne 1 -or (Installed $package) -cne $expected[0].sha256){throw 'Owned temporary package identity changed'}
  $out=Adb @('shell','pm','uninstall','--user','0',$package);if($out -cne 'Success'){throw 'Normal temporary helper uninstall failed'}
  if(Adb @('shell','pm','list','packages','--user','0',$package)){throw 'Temporary helper remains installed'};$result.temporaryHelpersRemoved+=$package;Save
 }
 Idle;$after=Protected 'protected-after-cleanup';$afterModules=Modules
 if($before -cne $after -or ($modules|ConvertTo-Json -Compress) -cne ($afterModules|ConvertTo-Json -Compress)){throw 'Protected data/modules changed during cleanup'}
 foreach($proofPath in $inputs.Keys){if((Sha $proofPath) -cne $inputs[$proofPath]){throw 'Audited original receipt changed'}}
 foreach($artifact in $install.artifacts|Where-Object {$_.package -cnotin @('org.mcrf.p1owner','org.mcrf.p1owner.peer')}){if((Installed $artifact.package) -cne $artifact.sha256){throw 'Retained installed app/module changed'}}
 Idle;[void](Adb @('shell','am','start','-n','org.mcrf.client/.LauncherDefault'))
 $result.protectedUnchanged=$true;$result.unrelatedModulesUnchanged=$true;$result.retainedModules=$afterModules;$result.protectedFiles=($after -split '\n').Count
 $result.state='SUPPORTED_P1_OWNED_ACCEPTANCE_AND_CLEANUP_PASS'
}catch{$result.state='FAILED_PRESERVED';$result.error=$_.Exception.Message;throw}
finally{$result.finishedUtc=[DateTime]::UtcNow.ToString('o');Save;Write-Output ('P1_FINAL='+$result.state+';RECEIPT='+(Join-Path $evidence 'receipt.json'))}
