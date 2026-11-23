param([Parameter(Mandatory=$true)][ValidateSet('Packet','Proxy')][string]$Kind,
 [Parameter(Mandatory=$true)][string]$TrafficReceipt,[Parameter(Mandatory=$true)][string]$DeviceReceipt,
 [Parameter(Mandatory=$true)][string]$WholeReceipt)
$ErrorActionPreference='Stop';$root='D:/MCRF/Projects/MCRF'
function Json([string]$p){Get-Content -LiteralPath $p -Raw|ConvertFrom-Json}
function Sha([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Canonical($v){$v|ConvertTo-Json -Depth 25 -Compress}
$whole=Json $WholeReceipt;$phone=Json $DeviceReceipt;$traffic=Json $TrafficReceipt
if($whole.state -cne 'COMMON_HOST_BUILD_PASS_ANDROID_NATIVE_TRAFFIC_ACCEPTANCE_PENDING' -or !$whole.sourceStable -or
 $phone.snapshot -cne $whole.phases.common_main_r8_debug_runner_helpers_and_external_modules.snapshot -or
 !$phone.user_files_unchanged -or @($phone.results).Count -ne 1 -or !$phone.results[0].protected_files_unchanged){throw 'Exact completed source/device/protected evidence required'}
$snapshot=$phone.snapshot;$artifactsPath=Join-Path $snapshot 'p1-owner-artifacts.json';$artifacts=Json $artifactsPath
if((Sha $artifactsPath) -cne $whole.phases.common_main_r8_debug_runner_helpers_and_external_modules.artifactsSha256){throw 'Common compiled artifact evidence changed'}
$logName=$phone.results[0].log
if($logName -cne [IO.Path]::GetFileName($logName) -or $logName -cnotmatch '^(packet-vpn|primary-steer-vpn)(?:-[0-9TZa-f-]{1,64})?\.log$'){throw 'Exact owned device log required'}
$logPath=Join-Path ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($DeviceReceipt))) $logName
$log=[IO.File]::ReadAllText($logPath)
if($log -notmatch 'INSTRUMENTATION_CODE: -1' -or $log -match '(?m)^(?:INSTRUMENTATION_RESULT: stream=)?FAIL\b|INSTRUMENTATION_FAILED|Process crashed'){throw 'Actual Android gate did not pass; cannot repair by reclassification'}
function Marker([string]$text){if($log -notmatch ('(?m)^(?:INSTRUMENTATION_(?:RESULT|STATUS): stream=)?PASS '+[regex]::Escape($text)+'\r?$')){throw 'Mandatory actual device marker absent'}}
$moduleKind=if($Kind -ceq 'Packet'){'wg'}else{'steer'}
$candidate=@($artifacts.externalCandidates|Where-Object {$_.kind -ceq $moduleKind -and $_.path -match 'arm64-v8a-debug\.apk$'})
if($candidate.Count -ne 1){throw 'Exact arm64 common candidate required'}
if($Kind -ceq 'Packet'){
 if($phone.results[0].mode -cne 'packet-vpn' -or !$phone.p1_native_packet.installed_hashes_verified -or
    $traffic.installedModuleSha256 -cne $candidate[0].sha256 -or !$traffic.privateKeysRemoved -or $traffic.firewallChanged -or @($traffic.rows).Count -ne 1){throw 'Packet exact installed/cleanup proof incomplete'}
 Marker 'Managed WG DNS uses actual IPv4 IPv6 kernel UDP TCP alias reflection and framework raw DNS; explicit IPv4 option respected'
 Marker 'Independent WG/AWG real Android TUN: exact local-peer HTTP payload verified over IPv4 AND IPv6'
 Marker 'WG/AWG STOP retires owned VPN/worker; private inputs removed and user profiles/preferences preserved'
 $peer=$traffic.rows[0].peer;$channel='dns_'+$traffic.managedDns;$expected=if($traffic.ipv4Compatibility){5}else{10}
 if($traffic.managedDns -cnotin @('udp4','udp6','tcp4','tcp6') -or $peer.event -cne 'closed' -or !$peer.handshake_observed -or
    $peer.http_requests -ne 2 -or $peer.http_body_bytes -le 0 -or $peer.dns_requests -ne $expected -or $peer.$channel -ne $expected -or
    $peer.dns_refused -ne 0 -or $peer.internal_dns_packets -ne 0 -or !$peer.dns_sockets_closed -or $peer.tunnel_counters.rx_bytes -le 0 -or $peer.tunnel_counters.tx_bytes -le 0){throw 'Independent packet peer did not corroborate exact traffic'}
 foreach($other in @('dns_udp4','dns_udp6','dns_tcp4','dns_tcp6')){if($other -cne $channel -and $peer.$other -ne 0){throw 'Unexpected DNS channel'}}
 $scope='Actual WG '+$traffic.managedDns+' kernel/framework DNS IPv4/IPv6 + independent decrypted peer, no internal alias leak, STOP/state unchanged. Original failed control receipt preserved; wrong original-owner marker was not a WG prerequisite.'
}else{
 if($phone.results[0].mode -cne 'primary-steer-vpn' -or !$phone.p1_native_steer.installed_hashes_verified -or !$traffic.private_files_removed -or $traffic.scenario -cne 'Proxy'){throw 'Exact proxy device scope/cleanup missing'}
 $a=$traffic.android;$peer=$traffic.peer
 if(!$a.android_segment_passed -or !$a.cleanup_verified -or !$a.user_state_unchanged -or $a.moduleSha256 -cne $candidate[0].sha256 -or $a.full_p1_accepted -ne $false){throw 'Proxy Android segment did not pass'}
 $match=@([regex]::Matches($log,'(?m)^STEER_RECEIPT (\{[^\r\n]+\})'))
 $exact=@($match|ForEach-Object {$_.Groups[1].Value|ConvertFrom-Json}|Where-Object inputSha256 -CEQ $a.inputSha256)
 if($exact.Count -ne 1 -or (Canonical $exact[0]) -cne (Canonical $a)){throw 'Exact original Android log/nonce-bound input receipt required'}
 Marker 'P1 actual different-UID authenticated HTTP CONNECT absolute HTTP SOCKS TCP UDP DNS and denied/allowed-after controls'
 Marker 'Actual Steer STOP observes Binder/PID death, closes TUN/leases/runtime and preserves the main process'
 if($peer.schema -ne 2 -or !$peer.listener_closed -or !$peer.all_sockets_closed -or $peer.forwarded_connections -ne 0 -or
    @($peer.refused|Where-Object {$_ -cnotin @('EOF','TimeoutError','ConnectionResetError','BrokenPipeError')}).Count){throw 'Peer cleanup/admission mismatch'}
 $http=@($peer.accepted|Where-Object kind -CEQ HTTP);$dns=@($peer.accepted|Where-Object kind -CEQ DNS)
 if($http.Count -ne 11 -or $dns.Count -ne 2 -or @($a.p1_proxy).Count -ne 12 -or @($http|Where-Object family -EQ 4).Count -ne 7 -or @($http|Where-Object family -EQ 6).Count -ne 4){throw 'Exact independently observed proxy traffic counts missing'}
 foreach($pair in @(@('198.18.0.2',3),@('198.18.0.3',4),@('fd77::2',1),@('fd77::3',3))){if(@($http|Where-Object target -CEQ $pair[0]).Count -ne $pair[1]){throw 'Original app target policy mismatch'}}
 foreach($q in @(1,28)){if(@($dns|Where-Object {$_.channel -ceq 'global' -and $_.qtype -eq $q}).Count -ne 1){throw 'Proxy managed DNS mismatch'}}
 foreach($hash in @($a.getResponseSha256,$a.postResponseSha256,$a.ipv6ResponseSha256)){if(!@($http|Where-Object response_sha256 -CEQ $hash).Count){throw 'Independent exact response hash missing'}}
 foreach($row in $a.p1_proxy){if($row.uid -eq $a.ownerUid -or ($row.sharedUidUnion -join ',') -cne 'org.mcrf.p1owner,org.mcrf.p1owner.peer'){throw 'Original/shared app scope mismatch'}}
 $scope='Actual different/shared UID app policy, authenticated HTTP CONNECT/absolute + SOCKS TCP IPv4/IPv6 + UDP DNS, auth/policy denial and permitted-after, independent peer hashes, STOP/state unchanged. Original false join came from iterating absent p1_dns in proxy mode.'
}
$out=Join-Path $root ('work/ports-test-2026-10-14/p1-traffic-review-'+[guid]::NewGuid().ToString('N'));[IO.Directory]::CreateDirectory($out)|Out-Null
$result=@{scopedPassed=$true;fullP1Accepted=$false;phoneTouched=$false;kind=$Kind;scope=$scope;wholeReceipt=$WholeReceipt;wholeReceiptSha256=Sha $WholeReceipt;deviceReceipt=$DeviceReceipt;deviceReceiptSha256=Sha $DeviceReceipt;trafficReceipt=$TrafficReceipt;trafficReceiptSha256=Sha $TrafficReceipt;deviceLogSha256=Sha $logPath;reviewControllerSha256=Sha $PSCommandPath;originalReceiptsPreserved=$true;newTrafficExecuted=$false}
[IO.File]::WriteAllText((Join-Path $out 'receipt.json'),($result|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
Write-Output "P1_RETAINED_TRAFFIC_REVIEW=$out/receipt.json;SCOPED_PASS_NO_RERUN"
