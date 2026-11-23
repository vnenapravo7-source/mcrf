param([Parameter(Mandatory=$true)][string]$Snapshot,[Parameter(Mandatory=$true)][string]$PeerStage,[string]$Device='192.168.100.234:43583',[string]$HostAddress='192.168.7.135')
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$stage=[IO.Path]::GetFullPath((Join-Path $root $PeerStage))
if(!$stage.StartsWith((Join-Path $root 'work')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($stage) -notmatch '^WireGuard-phone-peer-[0-9a-f]{32}$'){throw 'Private captured peer stage required'}
$ip=[Net.IPAddress]::Parse($HostAddress)
if($ip.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or $HostAddress -notmatch '^192\.168\.7\.[0-9]+$'){throw 'Use the explicitly selected private LAN interface only'}
$lease=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/device-lease.json') -Raw|ConvertFrom-Json
if($lease.state -ne 'occupied_main_fixture_acceptance' -or $lease.owner_thread -ne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $lease.device -ne $Device){throw 'Coordinator phone lease required'}
$runRoot=Join-Path $root ('work/WireGuard-phone-run-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot|Out-Null
$name='mcrf-wg-input-'+[guid]::NewGuid().ToString()+'.json'
$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$processes=@();$ready=@();$closed=@();$testError=$null
try{
    foreach($version in @('wg','awg1','awg2')){
        $directory=Join-Path $runRoot $version;New-Item -ItemType Directory -Path $directory|Out-Null
        $start=[Diagnostics.ProcessStartInfo]::new((Join-Path $stage 'WireGuardPeer.exe'))
        $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardInput=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
        foreach($arg in @('-version',$version,'-endpoint',$HostAddress,'-output',$directory)){$start.ArgumentList.Add($arg)}
        $child=[Diagnostics.Process]::new();$child.StartInfo=$start
        if(!$child.Start()){throw 'Private peer failed to start'}
        $processes+=$child
        $line=$child.StandardOutput.ReadLineAsync();if(!$line.Wait(15000)){throw 'Private peer readiness timeout'}
        $item=$line.Result|ConvertFrom-Json
        if($item.event -ne 'ready' -or $item.version -ne $version -or [IO.Path]::GetFullPath($item.config_path) -ne (Join-Path $directory 'client.conf')){throw 'Invalid private peer readiness'}
        $ready+=$item
        Write-Output "Private $version peer ready; generated keys are not printed"
    }
    $cases=@(foreach($item in $ready){[pscustomobject]@{version=$item.version;config=[IO.File]::ReadAllText($item.config_path);url=$item.url;url_ipv6=$item.url_ipv6}})
    $input=Join-Path $runRoot $name
    [IO.File]::WriteAllText($input,([pscustomobject]@{cases=$cases}|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
    & $adb -s $Device push $input "/data/local/tmp/$name";if($LASTEXITCODE -ne 0){throw 'Private test input transport failed'}
    & $adb -s $Device shell run-as org.mcrf.client cp "/data/local/tmp/$name" "cache/$name";if($LASTEXITCODE -ne 0){throw 'Private app test input copy failed'}
    & $adb -s $Device shell run-as org.mcrf.client chmod 600 "cache/$name";if($LASTEXITCODE -ne 0){throw 'Private app input permissions failed'}
    & $adb -s $Device shell rm "/data/local/tmp/$name"
    & (Join-Path $PSScriptRoot 'integration-device-checks.ps1') -Snapshot $Snapshot -Device $Device -Modes @('wireguard-peer') -PrivateFixture $name
}catch{$testError=$_;Write-Output ('Private peer gate incomplete: '+$_.Exception.Message)}
finally{
    foreach($child in $processes){
        try{
            if(!$child.HasExited){$child.StandardInput.WriteLine('STOP');$child.StandardInput.Flush();$child.StandardInput.Close()}
            if(!$child.WaitForExit(15000)){$child.Kill($true);$child.WaitForExit(5000)|Out-Null}
            $line=$child.StandardOutput.ReadToEnd().Trim()
            if($line){$value=$line|ConvertFrom-Json;if($value.event -eq 'closed'){$closed+=$value}}
        }catch{Write-Output 'Owned peer cleanup/receipt incomplete'}finally{if(!$child.HasExited){$child.Kill($true)};$child.Dispose()}
    }
    # Exact UUID filenames only, never a user profile or a broad device directory.
    & $adb -s $Device shell run-as org.mcrf.client rm -f "cache/$name"
    & $adb -s $Device shell rm -f "/data/local/tmp/$name"
    foreach($file in @((Join-Path $runRoot $name))+@('wg','awg1','awg2'|ForEach-Object {Join-Path $runRoot "$_/client.conf"})){
        $absolute=[IO.Path]::GetFullPath($file)
        if(!$absolute.StartsWith($runRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe generated fixture cleanup target'}
        if(Test-Path -LiteralPath $absolute){Remove-Item -LiteralPath $absolute}
    }
    $proof=$closed.Count -eq 3
    foreach($value in $closed){$proof=$proof -and $value.handshake_observed -and $value.tunnel_counters.rx_bytes -gt 0 -and $value.tunnel_counters.tx_bytes -gt 0 -and $value.http_requests -ge 2 -and $value.http_body_bytes -gt 0}
    $receipt=[pscustomobject]@{snapshot=$Snapshot;peer_stage=$stage;host_private_lan=$HostAddress;peer_receipts=$closed;all3peer_handshakes_and_http_proven=$proof;phone_gate_passed=($null -eq $testError);firewall_changed=$false;real_provider_or_vps_used=$false;generated_key_files_removed=$true;scope='Actual WG/AWG1/AWG2 private tunnel HTTP4+6 only; not VPN/DNS/Doze/IPv6 leak acceptance'}
    [IO.File]::WriteAllText((Join-Path $runRoot 'peer-phone-result.json'),($receipt|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
    Write-Output "WG_PHONE_EVIDENCE=$runRoot"
}
if($null -ne $testError){throw $testError}
if(!$proof){throw 'Android delay alone is not proof; peer handshake/HTTP receipts failed'}
Write-Output 'PASS WG/AWG1/AWG2 actual Android IPv4+IPv6 tunnel HTTP with peer handshake/byte receipts'
