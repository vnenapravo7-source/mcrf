param(
    [Parameter(Mandatory=$true)][string]$Snapshot,
    [Parameter(Mandatory=$true)][string]$SteerApk,
    [Parameter(Mandatory=$true)][string]$Device,
    [Parameter(Mandatory=$true)][string]$HostAddress,
    [ValidateSet('Dns','Proxy','TlsDoT','TlsDoH')][string]$Scenario='Dns',
    [string]$TlsPeerStage=''
)
# SOURCE ONLY: no caller should execute this before the WHOLE P1 source package
# and a separately authorized already-installed common Android batch are ready.
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
& (Join-Path $PSScriptRoot 'maintenance/Assert-HumanBatchReady.ps1') -Operation test
$taskCurrent=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/current.json') -Raw|ConvertFrom-Json
$taskReady=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/p1-progress-2026-10-17.json') -Raw|ConvertFrom-Json
if($taskCurrent.activeBatch -cne 'p1-progress-2026-10-17.json' -or $taskReady.allowCommonDeviceVerification -isnot [bool] -or !$taskReady.allowCommonDeviceVerification -or
    $taskReady.deviceVerificationAuthority -cnotin @('human-p1-steer-owned-native','human-p1-whole-owned-native')){throw 'No fresh common-P1 native device authority; source readiness alone is not phone permission'}
$taskSnapshot=[IO.Path]::GetFullPath((Join-Path $taskRoot $Snapshot))
if(!$taskSnapshot.StartsWith((Join-Path $taskRoot 'work')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
    [IO.Path]::GetFileName($taskSnapshot) -cnotmatch '^android-integration-[0-9a-f]{32}$'){throw 'Common immutable integration snapshot required'}
$taskBuild=Get-Content -LiteralPath (Join-Path $taskSnapshot 'build-result.json') -Raw|ConvertFrom-Json
$taskArtifacts=Get-Content -LiteralPath (Join-Path $taskSnapshot 'p1-owner-artifacts.json') -Raw|ConvertFrom-Json
$taskManifestPath=Join-Path $taskSnapshot 'source-manifest.json'
if($taskBuild.exitCode -ne 0 -or $taskBuild.sourceStable -isnot [bool] -or !$taskBuild.sourceStable -or !$taskBuild.p1OwnerFixture -or
    $taskArtifacts.priority -ne 1 -or @($taskArtifacts.artifacts).Count -ne 4 -or
    $taskBuild.sourceManifestSha256 -cne $taskArtifacts.sourceManifestSha256 -or
    (Get-FileHash -LiteralPath $taskManifestPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskArtifacts.sourceManifestSha256){throw 'Common P1 manifest/build mismatch'}
$taskManifest=Get-Content -LiteralPath $taskManifestPath -Raw|ConvertFrom-Json
function Get-TaskFrozen([string]$Relative){
    if($Relative -cnotmatch '^[A-Za-z0-9_./-]+$' -or $Relative.Split('/') -contains '..'){throw 'Frozen relative path required'}
    $path=[IO.Path]::GetFullPath((Join-Path $taskSnapshot $Relative))
    $row=@($taskManifest|Where-Object {$_.path.Replace('\','/') -ceq $Relative})
    if(!$path.StartsWith($taskSnapshot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or $row.Count -ne 1 -or
        (Get-Item -LiteralPath $path).LinkType -or $row[0].sha256 -cnotmatch '^[0-9a-f]{64}$' -or
        (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row[0].sha256){throw 'Frozen input changed/unpinned'}
    return $path
}
$taskCoordinator=Get-TaskFrozen 'ports/android/integration-p1-steer.ps1'
if((Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash -cne (Get-FileHash -LiteralPath $taskCoordinator -Algorithm SHA256).Hash){throw 'Live coordinator differs from frozen source'}
$taskDeviceCoordinator=Get-TaskFrozen 'ports/android/integration-device-checks.ps1'
$taskLiveDeviceCoordinator=Join-Path $PSScriptRoot 'integration-device-checks.ps1'
if((Get-FileHash -LiteralPath $taskLiveDeviceCoordinator -Algorithm SHA256).Hash -cne (Get-FileHash -LiteralPath $taskDeviceCoordinator -Algorithm SHA256).Hash){throw 'Live device coordinator differs from frozen source'}
$taskPeer=Get-TaskFrozen 'work/steer-android-port/android-vpn/tests/p1_owned_peer.py'
$taskModule=[IO.Path]::GetFullPath((Join-Path $taskSnapshot $SteerApk))
$taskCandidate=@($taskArtifacts.externalCandidates|Where-Object {$_.kind -ceq 'steer' -and $_.path.Replace('\','/') -ceq $SteerApk})
if($SteerApk -cnotmatch '^[A-Za-z0-9_./-]+\.apk$' -or $SteerApk.Split('/') -contains '..' -or
    !$taskModule.StartsWith($taskSnapshot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or $taskCandidate.Count -ne 1 -or
    (Get-Item -LiteralPath $taskModule).LinkType -or (Get-Item -LiteralPath $taskModule).Length -ne $taskCandidate[0].bytes -or
    (Get-FileHash -LiteralPath $taskModule -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskCandidate[0].sha256){throw 'Exact external output of SAME frozen common P1 batch required'}
$taskModuleSha=(Get-FileHash -LiteralPath $taskModule -Algorithm SHA256).Hash.ToLowerInvariant()
$taskFrozenReady=Get-TaskFrozen 'ports/android/coordination/p1-progress-2026-10-17.json'
if((Get-FileHash -LiteralPath $taskFrozenReady -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskArtifacts.readinessSha256){throw 'Frozen P1 readiness changed'}
$taskLease=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/device-lease.json') -Raw|ConvertFrom-Json
if($taskLease.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $taskLease.device -cne $Device -or $taskLease.state -cne 'occupied_main_fixture_acceptance'){throw 'Fresh main phone lease required'}
if($HostAddress -cnotmatch '^(10\.[0-9.]+|172\.(1[6-9]|2[0-9]|3[01])\.[0-9.]+|192\.168\.[0-9.]+)$' -or
    !(Get-NetIPAddress -AddressFamily IPv4|Where-Object {$_.IPAddress -ceq $HostAddress})){throw 'Explicit current private host interface required'}
$taskAdb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
function Assert-TaskPhone {
    $lease=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/device-lease.json') -Raw|ConvertFrom-Json
    if($lease.owner_thread -cne $taskLease.owner_thread -or $lease.device -cne $Device -or $lease.state -cne $taskLease.state){throw 'Phone lease changed'}
    $power=(& $taskAdb -s $Device shell dumpsys power 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $power -notmatch 'mWakefulness=Awake'){throw 'Phone not freshly awake; no wake/unlock'}
    $policy=(& $taskAdb -s $Device shell dumpsys window policy 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $policy -notmatch '(?m)^\s+showing=false\s*$'){throw 'Phone not unlocked; no bypass'}
    $foreground=(& $taskAdb -s $Device shell dumpsys activity activities 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $foreground -notmatch '(?m)^\s*(?:mResumedActivity:|topResumedActivity=|mTopResumedActivity=)[^\r\n]*\borg\.mcrf\.client/'){throw 'MCRF must already own foreground'}
    $services=(& $taskAdb -s $Device shell dumpsys activity services org.mcrf.client 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $services -match 'isForeground=true|startRequested=true|/\.McrfVpnService\b'){throw 'Phone is not idle; no user service is stopped'}
    $connectivity=(& $taskAdb -s $Device shell dumpsys connectivity 2>&1|Out-String)
    if($LASTEXITCODE -ne 0){throw 'Connectivity inventory unavailable'}
    # LISTEN/NetworkRequest entries mention VPN even while no VPN exists.
    $agents=@([regex]::Matches($connectivity,'(?m)^\s*NetworkAgentInfo\{[^\r\n]*'))
    $vpn=(& $taskAdb -s $Device shell dumpsys vpn_management 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $vpn -notmatch 'Active vpn type:' -or $vpn -match 'Active vpn type: [01]\b' -or
        !$agents.Count -or @($agents|Where-Object {$_.Value -match 'Transports:\s*VPN\b'}).Count){throw 'Existing VPN must remain untouched or idle could not be proved'}
}
function Assert-TaskInstalled([string]$Package,[string]$Sha){
    if($Sha -cnotmatch '^[0-9a-f]{64}$'){throw 'Artifact hash required'}
    $path=(& $taskAdb -s $Device shell pm path $Package 2>&1|Out-String).Trim()
    $pattern='^package:(/data/app/(?:[A-Za-z0-9_~+=-]+/)?'+[regex]::Escape($Package)+'-[A-Za-z0-9_~+=-]+/base\.apk)$'
    if($LASTEXITCODE -ne 0 -or $path -cnotmatch $pattern){throw 'One ordinary installed APK of exact package required'}
    $remote=$Matches[1]
    $installed=(& $taskAdb -s $Device shell sha256sum $remote 2>&1|Out-String).Trim()
    if($LASTEXITCODE -ne 0 -or $installed -cnotmatch ('^([0-9a-f]{64})\s+'+[regex]::Escape($remote)+'$') -or $Matches[1] -cne $Sha){throw 'Installed APK mismatch; no automatic install'}
}
$taskPackages=@{'ports/android/app/build/outputs/apk/debug/app-universal-debug.apk'='org.mcrf.client';'ports/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'='org.mcrf.client.test';'ports/android/p1-owner-probe/build/outputs/apk/debug/p1-owner-probe-debug.apk'='org.mcrf.p1owner'}
$taskPackages['ports/android/p1-owner-peer/build/outputs/apk/debug/p1-owner-peer-debug.apk']='org.mcrf.p1owner.peer'
Assert-TaskPhone
foreach($relative in $taskPackages.Keys){
    $rows=@($taskArtifacts.artifacts|Where-Object {$_.path.Replace('\','/') -ceq $relative})
    if($rows.Count -ne 1 -or (Get-FileHash -LiteralPath (Join-Path $taskSnapshot $relative) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $rows[0].sha256){throw 'Common artifact changed'}
    Assert-TaskInstalled $taskPackages[$relative] $rows[0].sha256
}
Assert-TaskInstalled 'org.mcrf.engines.steer' $taskModuleSha
$taskId=[guid]::NewGuid().ToString('N')
$taskTls=$Scenario -cin @('TlsDoT','TlsDoH')
if($TlsPeerStage -and !$taskTls){throw 'TLS peer only for new encrypted scenarios'}
$taskPeerRoot=Join-Path (Split-Path -Parent $taskPeer) "runtime/p1-owned/$taskId"
$taskEvidence=Join-Path $taskRoot "work/ports-test-2026-10-14/p1-steer-$taskId"
New-Item -ItemType Directory -Path $taskEvidence|Out-Null
if($taskTls){
    $tlsRoot=[IO.Path]::GetFullPath($TlsPeerStage)
    if(!$tlsRoot.StartsWith('D:\MCRF\Builds\Android\packet-dns-peer-audit-',[StringComparison]::OrdinalIgnoreCase)){throw 'Explicit verified final TLS peer required'}
    $tlsReceipt=Get-Content -LiteralPath (Join-Path $tlsRoot 'receipt.json') -Raw|ConvertFrom-Json
    $tlsPeer=Join-Path $tlsRoot 'WireGuardPeer.exe'
    if(!$tlsReceipt.sourceStable -or !$tlsReceipt.final_tls_awg3 -or (Get-FileHash -LiteralPath $tlsPeer).Hash.ToLowerInvariant() -cne $tlsReceipt.sha256){throw 'Final peer proof mismatch'}
    foreach($row in $tlsReceipt.source){$bound=Get-TaskFrozen ('ports/android/WireGuardFixtures/'+$row.path.Replace('\','/'));if((Get-FileHash -LiteralPath $bound).Hash.ToLowerInvariant() -cne $row.sha256){throw 'Final TLS peer not same common source'}}
    $recipe=Get-TaskFrozen 'ports/android/WireGuardFixtures/Build-PacketAuditPeer.ps1'
    if((Get-FileHash -LiteralPath $recipe).Hash.ToLowerInvariant() -cne $tlsReceipt.recipeSha256){throw 'Final TLS producer recipe changed'}
    # Refuse an explicit firewall denial; never change policy or reuse paths.
    $denied=@(Get-NetFirewallApplicationFilter -ErrorAction Stop|Where-Object {$_.Program -ieq $tlsPeer}|Get-NetFirewallRule|Where-Object {[string]$_.Action -eq 'Block' -and [string]$_.Enabled -eq 'True' -and [string]$_.Direction -eq 'Inbound'})
    if($denied.Count){throw 'Exact final TLS peer inbound denied; no workaround'}
    $taskPeerRoot=Join-Path $taskEvidence 'tls-peer';New-Item -ItemType Directory -Path $taskPeerRoot|Out-Null
}
$taskLeaf='mcrf-steer-input-'+[guid]::NewGuid().ToString()+'.json'
$taskInput=Join-Path $taskEvidence $taskLeaf
$taskProcess=$null;$taskCopied=$false;$taskError=$null;$taskJoined=$false;$taskAndroid=$null;$taskHost=$null
$taskMutex=[Threading.Mutex]::new($false,'Local\MCRF-P1-Steer-Peer');$taskHeld=$false
try {
    try{$taskHeld=$taskMutex.WaitOne(0)}catch [Threading.AbandonedMutexException] {$taskHeld=$true;throw 'Abandoned P1 peer: reconcile before continuing'}
    if(!$taskHeld){throw 'P1 peer coordinator already active'}
    $start=[Diagnostics.ProcessStartInfo]::new($(if($taskTls){$tlsPeer}else{'C:/Users/dieonfire/AppData/Local/Programs/Python/Python314/python.exe'}))
    $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    $peerArgs=if($taskTls){@('-vless-final','-endpoint',$HostAddress,'-output',$taskPeerRoot,'-public-dns',$(if($Scenario -ceq 'TlsDoH'){'doh'}else{'dot'}))}else{@($taskPeer,'--listen-ip',$HostAddress,'--private-dir',$taskPeerRoot,'--run-id',$taskId)}
    foreach($arg in $peerArgs){$start.ArgumentList.Add($arg)}
    Assert-TaskPhone
    $taskProcess=[Diagnostics.Process]::new();$taskProcess.StartInfo=$start
    if(!$taskProcess.Start()){throw 'Owned peer did not start'}
    $taskStdout=$taskProcess.StandardOutput.ReadToEndAsync();$taskStderr=$taskProcess.StandardError.ReadToEndAsync()
    $deadline=[DateTime]::UtcNow.AddSeconds(8)
    while(!(Test-Path -LiteralPath (Join-Path $taskPeerRoot 'fixture-private.json'))){
        if($taskProcess.HasExited -or [DateTime]::UtcNow -ge $deadline){throw 'Peer not ready'}
        Start-Sleep -Milliseconds 100
    }
    $peer=Get-Content -LiteralPath (Join-Path $taskPeerRoot 'fixture-private.json') -Raw|ConvertFrom-Json
    # The independently pinned Kotlin parser remains authoritative. Extract ONLY
    # its legacy public JSON constant, then apply the exact synthetic P1 delta.
    $source=Get-TaskFrozen 'ports/android/app/src/main/kotlin/org/mcrf/client/ClientSteerFixture.kt'
    $constant=[regex]::Matches((Get-Content -LiteralPath $source -Raw),'(?s)const val SYNTHETIC_SNAPSHOT_JSON = """(.*?)"""')
    if($constant.Count -ne 1){throw 'Pinned synthetic template missing'}
    $taskSynthetic=$constant[0].Groups[1].Value|ConvertFrom-Json -AsHashtable
    $taskSynthetic.schema=2;$taskSynthetic.Remove('acceptSteerIpv4DnsModel');$taskSynthetic.acceptSteerDnsModel=$true;$taskSynthetic.tun.ipv6=$true
    $taskSynthetic.routing.rules=@(
        @{id='owned-target';name='Owned dual-stack TCP target';enabled=$true;mode='addresses';target='proxy';invert=$false;text="IP-CIDR,198.18.0.2/32`nIP-CIDR,fd77::2/128"},
        @{id='owned-rule-dns';name='Owned separate rule DNS';enabled=$true;mode='addresses';target='proxy';invert=$false;text='DOMAIN-SUFFIX,rule.owned.invalid';dns=@{provider='Свой';protocol='UDP';custom='198.18.0.253'}},
        @{id='owned-global-dns';name='Owned global DNS';enabled=$true;mode='addresses';target='proxy';invert=$false;text='DOMAIN-SUFFIX,global.owned.invalid'}
    )
    $taskPrivate=@{schema=2;peer=$peer;snapshot=$taskSynthetic;moduleSha256=$taskModuleSha}
    if($taskTls){
        $protocol=if($Scenario -ceq 'TlsDoH'){'DoH'}else{'DoT'}
        $taskSynthetic.routing.globalDns=@{provider='Cloudflare';protocol=$protocol;custom=''}
        $taskSynthetic.routing.rules[1].dns=@{provider='Google';protocol=$protocol;custom=''}
        $taskSynthetic.routing.rules+=@{id='owned-trust-dns';name='Owned untrusted DNS';enabled=$true;mode='addresses';target='proxy';invert=$false;text='DOMAIN-SUFFIX,trust.owned.invalid';dns=@{provider='Свой';protocol=$protocol;custom=$(if($protocol -ceq 'DoH'){'https://198.18.0.253:1443/dns-query'}else{'tls://198.18.0.253:1853'})}}
    }
    if($Scenario -ceq 'Proxy'){
        $taskSynthetic.tun.mixedEnabled=$true;$taskSynthetic.tun.randomPort=$true
        $taskSynthetic.localProxyAuth=@{enabled=$true;username='p1-owned';password=$peer.nonce}
        $taskSynthetic.routing.rules=@(
            @{id='owned-main-control';name='Owned main control';enabled=$true;mode='addresses';target='proxy';invert=$false;text='';nativeMatch=@{package_name=@('org.mcrf.client');ip_cidr=@('198.18.0.2/32','fd77::2/128');port=@(80);network=@('tcp')}},
            @{id='owned-helper-request';name='Owned original helper request';enabled=$true;mode='addresses';target='proxy';invert=$false;text='';nativeMatch=@{package_name=@('org.mcrf.p1owner.peer');ip_cidr=@('198.18.0.3/32','fd77::3/128');port=@(80);network=@('tcp')}},
            @{id='owned-helper-dns';name='Owned helper managed DNS';enabled=$true;mode='addresses';target='proxy';invert=$false;text='';nativeMatch=@{package_name=@('org.mcrf.p1owner.peer');ip_cidr=@('198.18.0.254/32');port=@(53);network=@('udp')}},
            $taskSynthetic.routing.rules[1],$taskSynthetic.routing.rules[2])
        $taskHelperRows=@($taskArtifacts.artifacts|Where-Object {$_.path.Replace('\','/') -ceq 'ports/android/p1-owner-probe/build/outputs/apk/debug/p1-owner-probe-debug.apk'})
        if($taskHelperRows.Count -ne 1){throw 'Exactly one frozen helper artifact required'}
        $taskPeerRows=@($taskArtifacts.artifacts|Where-Object {$_.path.Replace('\','/') -ceq 'ports/android/p1-owner-peer/build/outputs/apk/debug/p1-owner-peer-debug.apk'})
        if($taskPeerRows.Count -ne 1){throw 'Exactly one frozen UID peer artifact required'}
        $taskHelperSha=$taskHelperRows[0].sha256;$taskPeerSha=$taskPeerRows[0].sha256
        $taskPrivate.helperSha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::ASCII.GetBytes("p1-shared-scope-v1`n$taskHelperSha`n$taskPeerSha`n"))).ToLowerInvariant()
    }
    # Compact JSON also avoids CRLF translation by Windows adb's stdin path.
    [IO.File]::WriteAllText($taskInput,($taskPrivate|ConvertTo-Json -Compress -Depth 20),[Text.UTF8Encoding]::new($false))
    $taskInputSha=(Get-FileHash -LiteralPath $taskInput -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-TaskPhone
    # Private stdin -> app-private file; never put URI/payload in argv, a shell
    # temporary world-readable file, a tool response, or a diagnostic log.
    $copyStart=[Diagnostics.ProcessStartInfo]::new($taskAdb);$copyStart.UseShellExecute=$false;$copyStart.CreateNoWindow=$true
    $copyStart.RedirectStandardInput=$true;$copyStart.RedirectStandardOutput=$true;$copyStart.RedirectStandardError=$true
    $copyStart.StandardInputEncoding=[Text.UTF8Encoding]::new($false)
    $bytes=[IO.File]::ReadAllBytes($taskInput)
    if($bytes.Length -lt 1 -or $bytes.Length -gt 16384){throw 'Bounded private fixture size required'}
    foreach($arg in @('-s',$Device,'shell','-T','run-as','org.mcrf.client','dd','bs=1',"count=$($bytes.Length)","of=cache/$taskLeaf")){$copyStart.ArgumentList.Add($arg)}
    $copy=[Diagnostics.Process]::new();$copy.StartInfo=$copyStart
    try{
        if(!$copy.Start()){throw 'Private input transfer did not start'}
        $taskCopied=$true # Partial transfers also own this exact private leaf.
        $copy.StandardInput.BaseStream.Write($bytes,0,$bytes.Length);$copy.StandardInput.Close()
        if(!$copy.WaitForExit(10000)){$copy.Kill();throw 'Private input transfer timed out'}
        if($copy.ExitCode -ne 0){throw 'Private input transfer failed'};$taskCopied=$true
    }finally{$copy.Dispose()}
    & $taskAdb -s $Device shell run-as org.mcrf.client chmod 600 "cache/$taskLeaf"
    if($LASTEXITCODE -ne 0){throw 'Private fixture permission failed'}
    $remoteHash=(& $taskAdb -s $Device shell run-as org.mcrf.client sha256sum "cache/$taskLeaf"|Out-String).Trim()
    if($LASTEXITCODE -ne 0 -or $remoteHash -cnotmatch ('^'+$taskInputSha+'\s+cache/'+[regex]::Escape($taskLeaf)+'$')){
        $remoteSize=(& $taskAdb -s $Device shell run-as org.mcrf.client stat -c '%s' "cache/$taskLeaf"|Out-String).Trim()
        Write-Output ('P1_TRANSFER_EXPECTED_BYTES='+$bytes.Length+';ACTUAL_BYTES='+$remoteSize+';HASH_MATCH='+($remoteHash.StartsWith($taskInputSha)))
        throw 'Private transferred input hash mismatch; no test launched'
    }
    $taskMode=if($taskTls){'p1-steer-tls'}elseif($Scenario -ceq 'Proxy'){'p1-steer-proxy'}else{'p1-steer-vpn'}
    & $taskLiveDeviceCoordinator -Snapshot $Snapshot -Device $Device -Modes @($taskMode) -PrivateFixture $taskLeaf -P1SteerApk $SteerApk
} catch {$taskError=$_}
finally {
    try {
    if($taskProcess){
        try{
            if(!$taskProcess.HasExited){[IO.File]::WriteAllText((Join-Path $taskPeerRoot 'stop'),'',[Text.UTF8Encoding]::new($false))}
            if(!$taskProcess.WaitForExit(10000)){$taskProcess.Kill();$taskProcess.WaitForExit(5000)|Out-Null}
            if($taskProcess.ExitCode -ne 0){throw 'Peer failed; no acceptance'}
        }catch{if(!$taskError){$taskError=$_}}finally{$taskProcess.Dispose()}
    }
    try{
        $taskHost=Get-Content -LiteralPath (Join-Path $taskPeerRoot 'peer-receipt.json') -Raw|ConvertFrom-Json
        $taskPhoneEvidence=Join-Path $taskRoot ('work/ports-test-2026-10-14/integration-'+[IO.Path]::GetFileName($taskSnapshot).Substring(20,4)+'-device')
        foreach($log in Get-ChildItem -LiteralPath $taskPhoneEvidence -File -Filter 'primary-steer-vpn*.log'){
            $text=Get-Content -LiteralPath $log.FullName -Raw
            foreach($match in [regex]::Matches($text,'(?m)^STEER_RECEIPT (\{[^\r\n]+\})')){
                $value=$match.Groups[1].Value|ConvertFrom-Json
                if($value.inputSha256 -ceq $taskInputSha){if($taskAndroid){throw 'Duplicate Android receipt'};$taskAndroid=$value}
            }
        }
        $dns=@($taskHost.accepted|Where-Object {$_.kind -ceq 'DNS'})
        $http=@($taskHost.accepted|Where-Object {$_.kind -ceq 'HTTP'})
        $joined=$taskHost.schema -eq 2 -and $taskHost.listener_closed -eq $true -and $taskHost.all_sockets_closed -eq $true -and ($taskTls -or $taskHost.forwarded_connections -eq 0) -and
            $taskAndroid.schema -eq 2 -and $taskAndroid.moduleSha256 -ceq $taskModuleSha -and $taskAndroid.android_segment_passed -eq $true -and
            $taskAndroid.cleanup_verified -eq $true -and $taskAndroid.user_state_unchanged -eq $true -and $taskAndroid.full_p1_accepted -eq $false -and
            @($taskHost.refused|Where-Object {$_ -cnotin @('EOF','TimeoutError','ConnectionResetError','BrokenPipeError','OWNED_STREAM_CLOSED')}).Count -eq 0
        if($taskTls){
            if(@($taskAndroid.p1_tls).Count -ne 12 -or $dns.Count -ne 0 -or $http.Count -ne 4 -or
                $taskHost.forwarded_connections -lt 2 -or $taskHost.forwarded_connections -gt 12 -or $taskHost.tls_public_up -le 0 -or $taskHost.tls_public_down -le 0 -or
                $taskHost.tls_public_refused -ne 0 -or $taskHost.tls_untrusted_rejected -lt 4 -or $taskHost.tls_untrusted_rejected -gt 8 -or
                $taskHost.tls_untrusted_accepted -ne 0 -or $taskHost.tls_application_bytes -ne 0 -or $taskHost.public_mode -cne $(if($Scenario -ceq 'TlsDoH'){'doh'}else{'dot'})){$joined=$false}
            foreach($channel in @('global','rule','trust')){foreach($transport in @('udp','tcp')){foreach($qtype in @(1,28)){
                $matches=@($taskAndroid.p1_tls|Where-Object {$_.channel -ceq $channel -and $_.transport -ceq $transport -and $_.qtype -eq $qtype -and $_.rcode -eq $(if($channel -ceq 'global'){3}else{2})})
                if($matches.Count -ne 1){$joined=$false}
            }}}
        }elseif($Scenario -ceq 'Proxy'){
            if(@($taskAndroid.p1_proxy).Count -ne 12 -or $dns.Count -ne 2 -or $http.Count -ne 11 -or
                @($http|Where-Object {$_.target -ceq '198.18.0.3'}).Count -ne 4 -or @($http|Where-Object {$_.target -ceq 'fd77::3'}).Count -ne 3 -or
                @($http|Where-Object {$_.target -ceq '198.18.0.2'}).Count -ne 3 -or @($http|Where-Object {$_.target -ceq 'fd77::2'}).Count -ne 1 -or
                @($taskAndroid.p1_proxy|Where-Object {$_.uid -eq $taskAndroid.ownerUid}).Count -ne 0){$joined=$false}
            foreach($qtype in @(1,28)){if(@($dns|Where-Object {$_.channel -ceq 'global' -and $_.qtype -eq $qtype}).Count -ne 1){$joined=$false}}
        }else{
            if(@($taskAndroid.p1_dns).Count -ne 8 -or $dns.Count -ne 8 -or $http.Count -ne 12 -or @($http|Where-Object {$_.target -cin @('198.18.0.3','fd77::3')}).Count){$joined=$false}
            foreach($channel in @('global','rule')){foreach($qtype in @(1,28)){if(@($dns|Where-Object {$_.channel -ceq $channel -and $_.qtype -eq $qtype}).Count -ne 2){$joined=$false}}}
        }
        if(@($http|Where-Object {$_.family -eq 4}).Count -ne $(if($taskTls){3}else{7}) -or @($http|Where-Object {$_.family -eq 6}).Count -ne $(if($taskTls){1}elseif($Scenario -ceq 'Proxy'){4}else{5}) -or
            @($http|Where-Object {$_.method -ceq 'POST' -and $_.request_body_bytes -eq 96 -and $_.family -eq 4}).Count -ne 1){$joined=$false}
        foreach($field in @('getResponseSha256','postResponseSha256','ipv6ResponseSha256')){if(!@($http|Where-Object {$_.response_sha256 -ceq $taskAndroid.$field}).Count){$joined=$false}}
        if($Scenario -ceq 'Dns'){foreach($row in @($taskAndroid.p1_dns)){if(!@($http|Where-Object {$_.response_sha256 -ceq $row.nativeResponseSha256}).Count){$joined=$false}}}
        $taskJoined=$joined -and !$taskError
    }catch{if(!$taskError){$taskError=$_};$taskJoined=$false}
    $removed=$true
    if($taskCopied){& $taskAdb -s $Device shell run-as org.mcrf.client rm -f "cache/$taskLeaf";if($LASTEXITCODE -ne 0){$removed=$false}}
    foreach($file in @($taskInput,(Join-Path $taskPeerRoot 'fixture-private.json'))){
        $absolute=[IO.Path]::GetFullPath($file)
        if(!($absolute.StartsWith($taskEvidence+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or $absolute.StartsWith($taskPeerRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase))){$removed=$false;continue}
        if(Test-Path -LiteralPath $absolute){if((Get-Item -LiteralPath $absolute).LinkType){$removed=$false}else{Remove-Item -LiteralPath $absolute -ErrorAction Continue;if(Test-Path -LiteralPath $absolute){$removed=$false}}}
    }
    $taskJoined=$taskJoined -and $removed
    [IO.File]::WriteAllText((Join-Path $taskEvidence 'receipt.json'),(@{schema=1;scenario=$Scenario;actual_scoped_gate_passed=$taskJoined;full_p1_accepted=$false;private_files_removed=$removed;fixedPublicDnsTested=$taskTls;android=$taskAndroid;peer=$taskHost;scope=$(if($taskTls){'Actual installed native Steer global DoH/DoT success and per-rule wrong-hostname/untrusted-cert rejection without fallback; exact synthetic names only, fixed CF relay independent audit and permitted-after/STOP. Not general Internet or wholeP1 acceptance.'}else{'Actual native model2 scoped DNS/alias OR different-UID authenticated proxy with disjoint package targets, UDP managedDNS, explicit auth/policy denial and permitted-after. Both observe protect/bind and real STOP. NOT encryptedDNS/allP1 acceptance.'})}|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
    } finally {if($taskHeld){$taskMutex.ReleaseMutex()};$taskMutex.Dispose()}
}
if(!$taskJoined){
    if($taskError){Write-Output ('P1_CONTROLLER_FAILURE_TYPE='+$taskError.Exception.GetType().FullName+';LINE='+$taskError.InvocationInfo.ScriptLineNumber)}
    throw 'P1 native DNS/peer join incomplete; evidence retained, no full acceptance claimed'
}
Write-Output "P1_STEER_SCOPED_RECEIPT=$(Join-Path $taskEvidence 'receipt.json')"
