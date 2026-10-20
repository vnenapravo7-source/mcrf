param([Parameter(Mandatory=$true)][string]$Snapshot,[string]$Device='192.168.7.234:45825',[string]$HostAddress='192.168.7.135',
    [Parameter(Mandatory=$true)][string]$PeerStage,[switch]$BlockCapture,[int]$HostPort=0,
    [ValidateSet('','udp4','udp6','tcp4','tcp6','tls-vpn','https-vpn','tls-direct','https-direct','tls-bad-name','https-bad-name','tls-untrusted','https-untrusted')][string]$ManagedDns='',
    [switch]$Ipv4Compatibility,[string]$WgApk='',
    [ValidateSet('wg','awg1','awg2','awg3','awg3_1')][string[]]$Versions=@())
$ErrorActionPreference='Stop'
# Whole-P1 entry guard BEFORE peer, generated keys or any phone access. This is
# a queued common-batch check, not permission to run a separate packet micro-test.
& (Join-Path $PSScriptRoot 'maintenance/Assert-HumanBatchReady.ps1') -Operation test
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskCurrent=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/current.json') -Raw|ConvertFrom-Json
$taskP1=$taskCurrent.activeBatch -ceq 'p1-progress-2026-10-17.json'
$taskEncrypted=$ManagedDns -cmatch '^(tls|https)-(vpn|direct|bad-name|untrusted)$'
if($taskEncrypted -and $Ipv4Compatibility){throw 'Encrypted DNS fixture does not combine the separate IPv4 option'}
if($Ipv4Compatibility -and !$ManagedDns -or $BlockCapture -and $ManagedDns){throw 'IPv4 option requires managed DNS; BLOCK has its separate exact fixture'}
if($ManagedDns -and !$taskP1){throw 'Managed DNS is scoped to the whole P1 batch'}
if($taskP1){
    $taskReady=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/p1-progress-2026-10-17.json') -Raw|ConvertFrom-Json
    if(!$BlockCapture -and !$ManagedDns -or $taskReady.allowCommonDeviceVerification -isnot [bool] -or !$taskReady.allowCommonDeviceVerification -or
        $taskReady.deviceVerificationAuthority -cne 'human-p1-whole-owned-native'){throw 'No fresh whole-P1 packet fixture authority; no historical WG suite rerun'}
    $taskSnapshot=[IO.Path]::GetFullPath((Join-Path $taskRoot $Snapshot))
    if(!$taskSnapshot.StartsWith((Join-Path $taskRoot 'work')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($taskSnapshot) -cnotmatch '^android-integration-[0-9a-f]{32}$'){throw 'Common immutable P1 snapshot required'}
    $taskCommon=Get-Content -LiteralPath (Join-Path $taskSnapshot 'p1-owner-artifacts.json') -Raw|ConvertFrom-Json
    $taskBuild=Get-Content -LiteralPath (Join-Path $taskSnapshot 'build-result.json') -Raw|ConvertFrom-Json
    $taskManifest=Get-Content -LiteralPath (Join-Path $taskSnapshot 'source-manifest.json') -Raw|ConvertFrom-Json
    if($taskBuild.exitCode -ne 0 -or !$taskBuild.sourceStable -or !$taskBuild.p1OwnerFixture -or $taskCommon.sourceManifestSha256 -cne $taskBuild.sourceManifestSha256 -or
        (Get-FileHash -LiteralPath (Join-Path $taskSnapshot 'source-manifest.json')).Hash.ToLowerInvariant() -cne $taskCommon.sourceManifestSha256){throw 'Common P1 build/source mismatch'}
    if($WgApk -cnotmatch '^[A-Za-z0-9_./-]+\.apk$' -or $WgApk.Split('/') -contains '..'){throw 'Exact common external WG artifact required'}
    $taskWgRows=@($taskCommon.externalCandidates|Where-Object {$_.kind -ceq 'wg' -and $_.path.Replace('\','/') -ceq $WgApk})
    $taskWgFile=[IO.Path]::GetFullPath((Join-Path $taskSnapshot $WgApk))
    if(!$taskWgFile.StartsWith($taskSnapshot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or $taskWgRows.Count -ne 1 -or
        (Get-Item -LiteralPath $taskWgFile).LinkType -or (Get-Item -LiteralPath $taskWgFile).Length -ne $taskWgRows[0].bytes -or
        (Get-FileHash -LiteralPath $taskWgFile).Hash.ToLowerInvariant() -cne $taskWgRows[0].sha256){throw 'External WG artifact changed'}
    $taskCoordinator=@($taskManifest|Where-Object {$_.path.Replace('\','/') -ceq 'ports/android/integration-packet-phone.ps1'})
    if($taskCoordinator.Count -ne 1 -or (Get-FileHash -LiteralPath $PSCommandPath).Hash.ToLowerInvariant() -cne $taskCoordinator[0].sha256){throw 'Packet coordinator differs from frozen P1 source'}
}
$taskPeerRoot=[IO.Path]::GetFullPath($PeerStage)
if(!$taskPeerRoot.StartsWith('D:\MCRF\Builds\Android\packet-dns-peer-', [StringComparison]::OrdinalIgnoreCase)){throw 'Explicit owned peer stage required'}
$taskPeer=Join-Path $taskPeerRoot 'WireGuardPeer.exe'
$taskPeerReceipt=Get-Content (Join-Path $taskPeerRoot 'receipt.json') -Raw|ConvertFrom-Json
if((Get-FileHash -LiteralPath $taskPeer).Hash -ine $taskPeerReceipt.sha256 -or !$taskPeerReceipt.nonce_dns){throw 'Verified nonce-DNS peer required'}
if($ManagedDns -and ($taskPeerReceipt.managed_dns -isnot [bool] -or !$taskPeerReceipt.managed_dns)){throw 'Managed DNS requires the NEW source-bound TCP/UDP audit peer'}
if($taskP1){foreach($taskInput in @($taskPeerReceipt.source)){
    $taskRelative='ports/android/WireGuardFixtures/'+$taskInput.path.Replace('\','/')
    $taskRows=@($taskManifest|Where-Object {$_.path.Replace('\','/') -ceq $taskRelative})
    if($taskRows.Count -ne 1 -or $taskRows[0].sha256 -cne $taskInput.sha256){throw 'Independent peer source differs from common frozen package'}
}}
if($taskP1){
    $taskPeerRecipe=@($taskManifest|Where-Object {$_.path.Replace('\','/') -ceq 'ports/android/WireGuardFixtures/Build-PacketAuditPeer.ps1'})
    if($taskPeerRecipe.Count -ne 1 -or $taskPeerReceipt.sourceStable -isnot [bool] -or !$taskPeerReceipt.sourceStable -or $taskPeerRecipe[0].sha256 -cne $taskPeerReceipt.recipeSha256){throw 'Peer recipe/source stability is not bound to common batch'}
}
if($BlockCapture -and ($taskPeerReceipt.packet_audit -isnot [bool] -or !$taskPeerReceipt.packet_audit)){throw 'BLOCK gate requires a verified decrypted-packet audit peer; no test started'}
if($HostPort -ne 0 -and ($HostPort -lt 1024 -or $HostPort -gt 65535)){throw 'Explicit high UDP port required'}
# Respect an explicit host firewall denial before generating keys or touching the phone.
# Never evade it by reusing a differently allowed executable path or changing policy here.
if(!(Get-Command Get-NetFirewallApplicationFilter -ErrorAction SilentlyContinue)){throw 'Cannot inspect host firewall; no packet test started'}
$taskBlocked=@(Get-NetFirewallApplicationFilter -ErrorAction Stop|Where-Object {$_.Program -ieq $taskPeer}|Get-NetFirewallRule|
    Where-Object { [string]$_.Action -eq 'Block' -and [string]$_.Enabled -eq 'True' -and [string]$_.Direction -eq 'Inbound' }|
    ForEach-Object { $taskRule=$_; $taskPort=$taskRule|Get-NetFirewallPortFilter
        if([string]$taskPort.Protocol -in @('UDP','Any','17','256')){$taskRule}
    })
if($taskBlocked.Count){throw 'Explicit firewall UDP block for this exact peer executable; user authorization required, no VPN/key/test started'}
if($HostAddress -notmatch '^192\.168\.7\.[0-9]{1,3}$' -or !(Get-NetIPAddress -AddressFamily IPv4|Where-Object IPAddress -EQ $HostAddress)){throw 'Explicit local private interface required'}
$taskAdb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
function Assert-PacketP1Phone {
    if(!$taskP1){return}
    $lease=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/device-lease.json') -Raw|ConvertFrom-Json
    if($lease.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $lease.device -cne $Device -or $lease.state -cne 'occupied_main_fixture_acceptance'){throw 'Fresh main packet lease required'}
    $power=(& $taskAdb -s $Device shell dumpsys power 2>&1|Out-String);if($LASTEXITCODE -ne 0 -or $power -notmatch 'mWakefulness=Awake'){throw 'Phone not awake; no wake/unlock'}
    $policy=(& $taskAdb -s $Device shell dumpsys window policy 2>&1|Out-String);if($LASTEXITCODE -ne 0 -or $policy -notmatch '(?m)^\s+showing=false\s*$'){throw 'Phone locked; no bypass'}
    $foreground=(& $taskAdb -s $Device shell dumpsys activity activities 2>&1|Out-String);if($LASTEXITCODE -ne 0 -or $foreground -notmatch '(?m)^\s*(?:mResumedActivity:|topResumedActivity=|mTopResumedActivity=)[^\r\n]*\borg\.mcrf\.client/'){throw 'MCRF must already own foreground'}
    $connectivity=(& $taskAdb -s $Device shell dumpsys connectivity 2>&1|Out-String);if($LASTEXITCODE -ne 0){throw 'Connectivity inventory unavailable'}
    $agents=@([regex]::Matches($connectivity,'(?m)^\s*NetworkAgentInfo\{[^\r\n]*'))
    $vpn=(& $taskAdb -s $Device shell dumpsys vpn_management 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $vpn -notmatch 'Active vpn type:' -or $vpn -match 'Active vpn type: [01]\b' -or
        !$agents.Count -or @($agents|Where-Object {$_.Value -match 'Transports:\s*VPN\b'}).Count){throw 'Existing VPN untouched or idle unproven'}
    $services=(& $taskAdb -s $Device shell dumpsys activity services org.mcrf.client 2>&1|Out-String);if($LASTEXITCODE -ne 0 -or $services -match 'isForeground=true|startRequested=true|/\.McrfVpnService\b'){throw 'Not idle; no user service stop'}
}
Assert-PacketP1Phone
$taskInstalled=(& $taskAdb -s $Device shell pm path org.mcrf.engines.wg|Out-String).Trim()
if($LASTEXITCODE -ne 0 -or $taskInstalled -notmatch '^package:([^\r\n]+)$'){throw 'Single installed WG APK required'}
$taskRemoteApk=$Matches[1]
$taskHashOutput=(& $taskAdb -s $Device shell sha256sum $taskRemoteApk|Out-String).Trim()
if($LASTEXITCODE -ne 0 -or $taskHashOutput -notmatch '^([0-9a-f]{64})\s'){throw 'Installed APK pin unavailable'}
$taskPin=$Matches[1]
if($taskP1 -and $taskPin -cne $taskWgRows[0].sha256){throw 'Installed WG differs from exact common candidate; no automatic install'}
$taskRun=Join-Path $taskRoot ('work/Packet-phone-run-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskRun|Out-Null
$taskRows=@()
$taskVersions=if($ManagedDns){@('wg')}else{@('wg','awg1','awg2')}
if($Versions.Count){
    if(!$taskP1 -or !$BlockCapture -or $ManagedDns -or @($Versions|Select-Object -Unique).Count -ne $Versions.Count){throw 'Explicit remaining versions are only for the owned P1 BLOCK matrix'}
    $taskVersions=$Versions
}
foreach($taskVersion in $taskVersions) {
    $taskDirectory=Join-Path $taskRun $taskVersion
    New-Item -ItemType Directory -Path $taskDirectory|Out-Null
    $taskStart=[Diagnostics.ProcessStartInfo]::new($taskPeer)
    $taskStart.UseShellExecute=$false;$taskStart.CreateNoWindow=$true
    $taskStart.RedirectStandardInput=$true;$taskStart.RedirectStandardOutput=$true;$taskStart.RedirectStandardError=$true
    foreach($taskArg in @('-version',$taskVersion,'-endpoint',$HostAddress,'-output',$taskDirectory)){$taskStart.ArgumentList.Add($taskArg)}
    if($HostPort -gt 0){$taskStart.ArgumentList.Add('-listen-port');$taskStart.ArgumentList.Add($HostPort.ToString())}
    if($taskEncrypted -and $ManagedDns.EndsWith('-vpn')){$taskStart.ArgumentList.Add('-public-dns');$taskStart.ArgumentList.Add($(if($ManagedDns.StartsWith('https-')){'doh'}else{'dot'}))}
    $taskChild=[Diagnostics.Process]::new();$taskChild.StartInfo=$taskStart
    $taskName='mcrf-packet-input-'+[guid]::NewGuid().ToString()+'.json'
    $taskClosed=$null;$taskPhone=$false;$taskProblem=$null
    try {
        if($taskP1 -and $taskRows.Count -gt 0 -and $taskRows[-1].passed){
            # The previous OWNED instrumentation ended its target activity.
            # Restore our authorized app normally before the next new variant;
            # never mistake its return to the underlying app for a human input.
            $grant=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/p1-device-authority-2026-10-19.json') -Raw|ConvertFrom-Json
            $lease=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/device-lease.json') -Raw|ConvertFrom-Json
            if($grant.normalNavigationReply -cnotmatch '^call_560316f4546e41e5a430c9fe3a15aca1:' -or
                $lease.device -cne $Device -or $lease.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c'){throw 'Fresh authorized normal foreground restoration required'}
            $vpn=(& $taskAdb -s $Device shell dumpsys vpn_management|Out-String)
            $policy=(& $taskAdb -s $Device shell dumpsys window policy|Out-String)
            if($vpn -notmatch 'Active vpn type:' -or $vpn -match 'Active vpn type: [01]\b' -or $policy -notmatch '(?m)^\s+showing=false\s*$'){throw 'No user VPN stop or keyguard bypass during variant restoration'}
            & $taskAdb -s $Device shell am start -n org.mcrf.client/.LauncherDefault|Out-Null
            if($LASTEXITCODE){throw 'Normal app restoration failed'}
        }
        Assert-PacketP1Phone
        if(!$taskChild.Start()){throw 'Owned local peer start failed'}
        $taskLine=$taskChild.StandardOutput.ReadLineAsync()
        if(!$taskLine.Wait(15000)){throw 'Owned local peer readiness timeout'}
        $taskReady=$taskLine.Result|ConvertFrom-Json
        if($taskReady.event -ne 'ready' -or $taskReady.version -ne $taskVersion -or [IO.Path]::GetFullPath($taskReady.config_path) -cne (Join-Path $taskDirectory 'client.conf')){throw 'Peer readiness identity mismatch'}
        $taskConfig=[IO.File]::ReadAllText($taskReady.config_path)
        if($BlockCapture){
            # Original peer MUST contain the denied destinations. Production
            # projection then narrows it; manually adding only TUN routes would
            # not exercise that path. Never edit the independent server policy.
            $taskAllowed='(?m)^AllowedIPs = 10\.77\.0\.1/32, fd77::1/128\r?$'
            if([regex]::Matches($taskConfig,$taskAllowed).Count -ne 1){throw 'Exact original fixture AllowedIPs required'}
            $taskConfig=[regex]::Replace($taskConfig,$taskAllowed,'AllowedIPs = 10.77.0.1/32, 10.77.0.3/32, fd77::1/128, fd77::3/128')
        }
        if($ManagedDns){
            $taskDnsLine='(?m)^DNS = 10\.77\.0\.1\r?\n'
            if([regex]::Matches($taskConfig,$taskDnsLine).Count -ne 1){throw 'Exact owned original fixture DNS required'}
            $taskConfig=[regex]::Replace($taskConfig,$taskDnsLine,'')
        }
        $taskFields=[ordered]@{config=$taskConfig;url=$taskReady.url;expectedBody=$taskReady.expected_body;apkSha256=$taskPin}
        if($BlockCapture){$taskFields.captureBlocked=$true}
        if($ManagedDns){$taskFields.managedDns=$ManagedDns;if($Ipv4Compatibility){$taskFields.ipv4Compatibility=$true}}
        $taskPayload=$taskFields|ConvertTo-Json -Compress
        $taskPayloadBytes=[Text.UTF8Encoding]::new($false).GetBytes($taskPayload)
        $taskPayloadHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($taskPayloadBytes)).ToLowerInvariant()
        # Private stdin, not command-line extras, shared storage or a catalogue import.
        $taskUploadStart=[Diagnostics.ProcessStartInfo]::new($taskAdb)
        $taskUploadStart.UseShellExecute=$false;$taskUploadStart.CreateNoWindow=$true
        $taskUploadStart.RedirectStandardInput=$true;$taskUploadStart.RedirectStandardOutput=$true;$taskUploadStart.RedirectStandardError=$true
        foreach($taskArg in @('-s',$Device,'shell','-T','run-as','org.mcrf.client','dd','bs=1',"count=$($taskPayloadBytes.Length)","of=cache/$taskName")){$taskUploadStart.ArgumentList.Add($taskArg)}
        $taskUpload=[Diagnostics.Process]::new();$taskUpload.StartInfo=$taskUploadStart
        try {
            if(!$taskUpload.Start()){throw 'Private input upload start failed'}
            $taskUpload.StandardInput.BaseStream.Write($taskPayloadBytes,0,$taskPayloadBytes.Length);$taskUpload.StandardInput.BaseStream.Flush()
            $taskUpload.StandardInput.Close()
            if(!$taskUpload.WaitForExit(10000) -or $taskUpload.ExitCode -ne 0){throw 'Private input upload failed'}
        } finally {if(!$taskUpload.HasExited){$taskUpload.Kill($true)};$taskUpload.Dispose();$taskPayload=$null;$taskPayloadBytes=$null}
        & $taskAdb -s $Device shell run-as org.mcrf.client chmod 600 "cache/$taskName"
        if($LASTEXITCODE -ne 0){throw 'Private input permission failed'}
        $taskRemoteHash=(& $taskAdb -s $Device shell run-as org.mcrf.client sha256sum "cache/$taskName"|Out-String).Trim()
        if($LASTEXITCODE -ne 0 -or $taskRemoteHash -notmatch ('^'+$taskPayloadHash+'\s')){throw 'Private input SHA mismatch; no instrumentation launched'}
        Assert-PacketP1Phone
        $taskExtra=@{};$taskMode='packet-vpn'
        if($taskP1){$taskExtra.P1WgApk=$WgApk;$taskMode=if($taskEncrypted){'p1-packet-tls'}elseif($ManagedDns){'p1-packet-dns'}else{'p1-packet-block'}}
        $taskPhoneOutput=@(& (Join-Path $PSScriptRoot 'integration-device-checks.ps1') -Snapshot $Snapshot -Device $Device -Modes @($taskMode) -PrivateFixture $taskName @taskExtra)
        $taskPhoneOutput|Write-Output
        $taskSnapshotName=[IO.Path]::GetFileName([IO.Path]::GetFullPath((Join-Path $taskRoot $Snapshot)))
        $taskDeviceEvidence=Join-Path $taskRoot ('work/ports-test-2026-10-14/integration-'+$taskSnapshotName.Substring(20,4)+'-device')
        $taskReceiptLines=@($taskPhoneOutput|Where-Object {$_ -is [string] -and $_.StartsWith('DEVICE_RECEIPT=')})
        if($taskReceiptLines.Count -ne 1){throw 'Exact phone receipt identity required'}
        $taskPhoneReceipt=[IO.Path]::GetFullPath($taskReceiptLines[0].Substring('DEVICE_RECEIPT='.Length))
        if([IO.Path]::GetDirectoryName($taskPhoneReceipt) -ine [IO.Path]::GetFullPath($taskDeviceEvidence)){throw 'Phone receipt escaped owned evidence directory'}
        $taskPhoneData=Get-Content -LiteralPath $taskPhoneReceipt -Raw|ConvertFrom-Json
        if($taskPhoneData.results.Count -ne 1 -or $taskPhoneData.results[0].mode -ne 'packet-vpn' -or $taskPhoneData.results[0].state -cne $(if($taskP1){'p1_native_pending_independent_peer_join'}else{'passed'}) -or $taskPhoneData.results[0].passed -lt 5 -or !$taskPhoneData.user_files_unchanged){throw 'Phone receipt does not prove this exact packet gate'}
        $taskLogName=$taskPhoneData.results[0].log
        if($taskLogName -ne [IO.Path]::GetFileName($taskLogName) -or $taskLogName -notmatch '^packet-vpn(?:-[0-9TZ-]+[a-f0-9]*)?\.log$'){throw 'Invalid phone log identity'}
        Copy-Item -LiteralPath $taskPhoneReceipt -Destination (Join-Path $taskDirectory 'device-result.json')
        Copy-Item -LiteralPath (Join-Path $taskDeviceEvidence $taskLogName) -Destination (Join-Path $taskDirectory 'packet-vpn.log')
        if($BlockCapture -and !([IO.File]::ReadAllText((Join-Path $taskDirectory 'packet-vpn.log')).Contains('BLOCK uses atomic route envelope independently recomputed and SHA-acknowledged by worker'))){throw 'Missing actual atomic BLOCK envelope/permitted-after-block device marker'}
        if($ManagedDns -and !([IO.File]::ReadAllText((Join-Path $taskDirectory 'packet-vpn.log')).Contains('Managed WG DNS uses actual IPv4 IPv6 kernel UDP TCP alias reflection and framework raw DNS; explicit IPv4 option respected'))){throw 'Missing managed kernel/framework DNS marker'}
        if($taskEncrypted -and !([IO.File]::ReadAllText((Join-Path $taskDirectory 'packet-vpn.log')).Contains('Encrypted managed WG DNS uses kernel aliases and framework with system trust hostname checks no upstream fallback and permitted-after; independent TLS audit required'))){throw 'Missing exact encrypted DNS/trust/permitted-after marker'}
        $taskPhone=$true
    } catch {$taskProblem=$_;Write-Output "Owned $taskVersion packet gate did not pass"}
    finally {
        if($taskChild.Id -gt 0) {
            if(!$taskChild.HasExited){$taskChild.StandardInput.WriteLine('STOP');$taskChild.StandardInput.Flush();$taskChild.StandardInput.Close()}
            if(!$taskChild.WaitForExit(15000)){$taskChild.Kill($true);$taskChild.WaitForExit(5000)|Out-Null}
            $taskRest=$taskChild.StandardOutput.ReadToEnd().Trim()
            if($taskRest){$taskClosed=$taskRest|ConvertFrom-Json}
        }
        $taskChild.Dispose()
        & $taskAdb -s $Device shell run-as org.mcrf.client rm -f "cache/$taskName"
        $taskConf=Join-Path $taskDirectory 'client.conf'
        if(Test-Path -LiteralPath $taskConf){
            if([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($taskConf)) -cne $taskDirectory){throw 'Generated key cleanup escaped owned run'}
            Remove-Item -LiteralPath $taskConf
        }
        $taskProven=$taskPhone -and $taskClosed.event -eq 'closed' -and $taskClosed.handshake_observed -and $taskClosed.http_requests -ge 2 -and $taskClosed.http_body_bytes -gt 0 -and $taskClosed.dns_requests -ge 4 -and $taskClosed.tunnel_counters.rx_bytes -gt 0 -and $taskClosed.tunnel_counters.tx_bytes -gt 0
        if($BlockCapture){$taskProven=$taskProven -and ($taskClosed.PSObject.Properties.Name -contains 'blocked_packets') -and $taskClosed.blocked_packets -eq 0 -and $taskClosed.decrypted_packets -gt 0}
        if($ManagedDns -and !$taskEncrypted){
            $taskExpected=if($Ipv4Compatibility){5}else{10};$taskChannel='dns_'+$ManagedDns
            $taskProven=$taskPhone -and $taskClosed.event -ceq 'closed' -and $taskClosed.handshake_observed -eq $true -and $taskClosed.http_requests -eq 2 -and
                $taskClosed.dns_requests -eq $taskExpected -and $taskClosed.$taskChannel -eq $taskExpected -and $taskClosed.dns_refused -eq 0 -and
                $taskClosed.internal_dns_packets -eq 0 -and $taskClosed.dns_sockets_closed -eq $true -and $taskClosed.tunnel_counters.rx_bytes -gt 0 -and $taskClosed.tunnel_counters.tx_bytes -gt 0
            foreach($taskOther in @('dns_udp4','dns_udp6','dns_tcp4','dns_tcp6')){if($taskOther -cne $taskChannel -and $taskClosed.$taskOther -ne 0){$taskProven=$false}}
        }
        if($taskEncrypted){
            $taskProven=$taskPhone -and $taskClosed.event -ceq 'closed' -and $taskClosed.handshake_observed -eq $true -and $taskClosed.http_requests -eq 3 -and
                $taskClosed.dns_requests -eq 0 -and $taskClosed.dns_refused -eq 0 -and $taskClosed.internal_dns_packets -eq 0 -and
                $taskClosed.dns_sockets_closed -eq $true -and $taskClosed.tls_sockets_closed -eq $true -and $taskClosed.public_dns_sockets_closed -eq $true -and
                $taskClosed.tls_untrusted_accepted -eq 0 -and $taskClosed.tls_application_bytes -eq 0 -and
                $taskClosed.tunnel_counters.rx_bytes -gt 0 -and $taskClosed.tunnel_counters.tx_bytes -gt 0
            foreach($taskOther in @('dns_udp4','dns_udp6','dns_tcp4','dns_tcp6')){if($taskClosed.$taskOther -ne 0){$taskProven=$false}}
            if($ManagedDns.EndsWith('-vpn')){
                $kind=if($ManagedDns.StartsWith('https-')){'doh'}else{'dot'}
                $taskProven=$taskProven -and $taskClosed.public_dns_mode -ceq $kind -and $taskClosed.public_dns_connections -eq 10 -and
                    $taskClosed.public_dns_tx -gt 0 -and $taskClosed.public_dns_rx -gt 0 -and $taskClosed.public_dns_refused -eq 0 -and $taskClosed.tls_untrusted_rejected -eq 0
            }else{
                $taskProven=$taskProven -and $taskClosed.public_dns_mode -ceq 'none' -and $taskClosed.public_dns_connections -eq 0 -and $taskClosed.public_dns_tx -eq 0 -and $taskClosed.public_dns_rx -eq 0
                if($ManagedDns.EndsWith('-untrusted')){$taskProven=$taskProven -and $taskClosed.tls_untrusted_rejected -ge 10 -and $taskClosed.tls_untrusted_rejected -le 32}
                else{$taskProven=$taskProven -and $taskClosed.tls_untrusted_rejected -eq 0}
            }
        }
        $taskRows+=@{version=$taskVersion;passed=$taskProven;phonePassed=$taskPhone;peer=$taskClosed}
        [IO.File]::WriteAllText((Join-Path $taskRun 'receipt.json'),(@{snapshot=$Snapshot;installedModuleSha256=$taskPin;managedDns=$ManagedDns;ipv4Compatibility=$Ipv4Compatibility.IsPresent;full_p1_accepted=$false;blockCaptureRequested=$BlockCapture.IsPresent;atomicEnvelopeRequested=$BlockCapture.IsPresent;rows=$taskRows;privateKeysRemoved=$true;firewallChanged=$false;internetOrProviderTested=$taskEncrypted;publicNetworkScope=$(if($taskEncrypted){'Fixed 1.1.1.1 TLS DNS with synthetic nonce.invalid only; not general Internet acceptance'}else{'none'})}|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
    }
    if($taskProblem){throw $taskProblem}
    if(!$taskProven){throw 'Peer-side handshake/HTTP receipt does not prove packet tunnel'}
    Write-Output "PASS independent $taskVersion Android TUN IPv4+IPv6 HTTP and nonce UDP DNS; peer handshake, DNS count and bytes confirmed"
}
Write-Output "PACKET_PHONE_EVIDENCE=$taskRun"
