param([Parameter(Mandatory=$true)][string]$Snapshot,[string]$Device='192.168.100.234:43583',[string]$HostAddress='192.168.7.135')
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskLease=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/device-lease.json') -Raw|ConvertFrom-Json
if($taskLease.state -ne 'occupied_main_fixture_acceptance' -or $taskLease.owner_thread -ne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $taskLease.device -ne $Device){throw 'Main phone lease required'}
if($HostAddress -notmatch '^192\.168\.7\.[0-9]{1,3}$' -or !(Get-NetIPAddress -AddressFamily IPv4|Where-Object {$_.IPAddress -ceq $HostAddress})){throw 'Use the verified local private LAN interface only'}
$taskAdb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$taskSnapshotPath=[IO.Path]::GetFullPath((Join-Path $taskRoot $Snapshot))
if(!$taskSnapshotPath.StartsWith((Join-Path $taskRoot 'work')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($taskSnapshotPath) -notmatch '^android-integration-[0-9a-f]{32}$'){throw 'Verified integration snapshot required'}
$taskBuild=Get-Content -LiteralPath (Join-Path $taskSnapshotPath 'build-result.json') -Raw|ConvertFrom-Json
if($taskBuild.exitCode -ne 0 -or !$taskBuild.sourceStable){throw 'Snapshot is not green/stable'}
$taskBaseApkHash=(Get-FileHash -LiteralPath (Join-Path $taskSnapshotPath 'ports/android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk')).Hash.ToLowerInvariant()
$taskInstalledPath=(& $taskAdb -s $Device shell pm path org.mcrf.client|Out-String).Trim()
if($taskInstalledPath -notmatch '^package:(/data/app/[^\r\n]+/base\.apk)$'){throw 'Unexpected installed app path'}
$taskInstalledHash=(& $taskAdb -s $Device shell sha256sum $Matches[1]|Out-String).Trim()
if(!$taskInstalledHash.StartsWith($taskBaseApkHash+' ')){throw 'Installed app is not the selected snapshot; no peer started'}
foreach($taskPackage in @('org.mcrf.client','org.steer.prototype')){
    $taskServices=(& $taskAdb -s $Device shell dumpsys activity services $taskPackage|Out-String)
    $taskRecords=@([regex]::Matches($taskServices,'ServiceRecord\{[^\r\n]*?\b(org\.[^\s}]+)'))
    $taskIdleTg=$taskPackage -eq 'org.mcrf.client' -and $taskRecords.Count -eq 1 -and $taskRecords[0].Value -match '/\.TelegramProxyService\b' -and $taskServices -match 'startForegroundCount=0\b' -and $taskServices -notmatch 'isForeground=true|startRequested=true'
    if($LASTEXITCODE -ne 0 -or $taskRecords.Count -gt 0 -and !$taskIdleTg){throw 'Phone is not idle; do not start a peer or stop a user service'}
}
$taskSource=Join-Path $taskRoot 'outputs/steer-android/bounded-vless-fixture-schema1/source/android-vpn/tests/bounded_vless_peer.py'
$taskBase=Join-Path ([IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($taskSource))) 'integration-helper/examples/mcrf-steer-compatible.json'
if((Get-FileHash -LiteralPath $taskSource).Hash.ToLowerInvariant() -cne 'b181e020cc1cfd1965a948adf2c7b5cbedcd6d1ed2745d4404ae692ec42dca21' -or
   (Get-FileHash -LiteralPath $taskBase).Hash.ToLowerInvariant() -cne 'fb792e5eb1e649dc9843969b5a5225dbfbd924721810e74616befb4a5fc9c23b'){throw 'Reviewed peer source/template changed'}
$taskId=[guid]::NewGuid().ToString('N')
$taskPeerRoot=Join-Path ([IO.Path]::GetDirectoryName($taskSource)) "runtime/one-shot-fixture/$taskId"
if(Test-Path -LiteralPath $taskPeerRoot){throw 'Peer run already exists'}
$taskEvidence=Join-Path $taskRoot "work/Steer-phone-run-$taskId"
New-Item -ItemType Directory -Path $taskEvidence|Out-Null
$taskName='mcrf-steer-input-'+[guid]::NewGuid().ToString()+'.json'
$taskInput=Join-Path $taskEvidence $taskName
$taskChild=$null;$taskError=$null;$taskAndroid=$null;$taskPeer=$null;$taskInputHash=$null;$taskJoined=$false;$taskRemoved=$false
try {
    $taskStart=[Diagnostics.ProcessStartInfo]::new('C:/Users/dieonfire/AppData/Local/Programs/Python/Python314/python.exe')
    $taskStart.UseShellExecute=$false;$taskStart.CreateNoWindow=$true;$taskStart.RedirectStandardOutput=$true;$taskStart.RedirectStandardError=$true
    foreach($taskArg in @($taskSource,'--serve','--listen-ip',$HostAddress,'--private-dir',$taskPeerRoot)){$taskStart.ArgumentList.Add($taskArg)}
    $taskChild=[Diagnostics.Process]::new();$taskChild.StartInfo=$taskStart
    if(!$taskChild.Start()){throw 'Owned bounded peer did not start'}
    $taskUntil=[DateTime]::UtcNow.AddSeconds(8)
    while(!(Test-Path -LiteralPath (Join-Path $taskPeerRoot 'snapshot-private.json'))){
        if($taskChild.HasExited -or [DateTime]::UtcNow -ge $taskUntil){throw 'Bounded peer readiness failed; no retry/firewall change'}
        Start-Sleep -Milliseconds 50
    }
    $taskPrivate=Get-Content -LiteralPath (Join-Path $taskPeerRoot 'fixture-private.json') -Raw|ConvertFrom-Json
    $taskSnapshot=Get-Content -LiteralPath (Join-Path $taskPeerRoot 'snapshot-private.json') -Raw|ConvertFrom-Json
    if($taskPrivate.listenIpv4 -cne $HostAddress -or $taskPrivate.deadlineSeconds -ne 45 -or $taskPrivate.targetIpv4 -cne '198.18.0.2' -or $taskPrivate.targetPort -ne 80){throw 'Bounded peer contract changed'}
    [IO.File]::WriteAllText($taskInput,([pscustomobject]@{schema=1;peer=$taskPrivate;snapshot=$taskSnapshot}|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
    $taskInputHash=(Get-FileHash -LiteralPath $taskInput).Hash.ToLowerInvariant()
    & $taskAdb -s $Device push $taskInput "/data/local/tmp/$taskName"|Out-Null;if($LASTEXITCODE -ne 0){throw 'Private input transfer failed'}
    & $taskAdb -s $Device shell run-as org.mcrf.client cp "/data/local/tmp/$taskName" "cache/$taskName";if($LASTEXITCODE -ne 0){throw 'Private input copy failed'}
    & $taskAdb -s $Device shell run-as org.mcrf.client chmod 600 "cache/$taskName";if($LASTEXITCODE -ne 0){throw 'Private input permission failed'}
    & $taskAdb -s $Device shell rm -f "/data/local/tmp/$taskName"
    & (Join-Path $PSScriptRoot 'integration-device-checks.ps1') -Snapshot $Snapshot -Device $Device -Modes @('primary-steer-vpn') -PrivateFixture $taskName
} catch {$taskError=$_;Write-Output 'Bounded Steer gate incomplete; no user-service interruption or permission/firewall workaround'}
finally {
    if($taskChild){
        try {
            if(!$taskChild.HasExited -and (Test-Path -LiteralPath $taskPeerRoot)){
                [IO.File]::WriteAllText((Join-Path $taskPeerRoot 'stop'),'',[Text.UTF8Encoding]::new($false))
            }
            if(!$taskChild.WaitForExit(10000)){$taskChild.Kill();$taskChild.WaitForExit(5000)|Out-Null}
        }catch {if(!$taskError){$taskError=$_}}
        finally {$taskChild.Dispose()}
    }
    try {
    $taskPeerReceipt=Join-Path $taskPeerRoot 'peer-receipt.json'
    if(Test-Path -LiteralPath $taskPeerReceipt){$taskPeer=Get-Content -LiteralPath $taskPeerReceipt -Raw|ConvertFrom-Json}
    $taskLeaf=[IO.Path]::GetFileName($Snapshot).Substring(20,4)
    $taskPhoneEvidence=Join-Path $taskRoot "work/ports-test-2026-10-14/integration-$taskLeaf-device"
    if(Test-Path -LiteralPath $taskPhoneEvidence){
        foreach($taskLog in Get-ChildItem -LiteralPath $taskPhoneEvidence -File -Filter 'primary-steer-vpn*.log'){
            $taskText=Get-Content -LiteralPath $taskLog.FullName -Raw
            if($taskText -match '(?m)^STEER_RECEIPT (\{[^\r\n]+\})'){
                $taskValue=$Matches[1]|ConvertFrom-Json
                if($taskValue.inputSha256 -ceq $taskInputHash){if($taskAndroid){throw 'Duplicate matching Android receipt'};$taskAndroid=$taskValue}
            }
        }
    }
    if($taskPeer -and $taskAndroid){
        $taskGets=@($taskPeer.accepted|Where-Object {$_.method -ceq 'GET' -and $_.target -ceq '198.18.0.2:80' -and $_.response_sha256 -ceq $taskAndroid.getResponseSha256 -and $_.request_body_bytes -eq 0 -and $_.response_body_bytes -eq 0})
        $taskPosts=@($taskPeer.accepted|Where-Object {$_.method -ceq 'POST' -and $_.target -ceq '198.18.0.2:80' -and $_.response_sha256 -ceq $taskAndroid.postResponseSha256 -and $_.request_body_bytes -eq 96 -and $_.response_body_bytes -eq 96})
        $taskJoined=$null -eq $taskError -and $taskAndroid.android_segment_passed -eq $true -and $taskAndroid.cleanup_verified -eq $true -and $taskAndroid.user_state_unchanged -eq $true -and
            $taskPeer.listener_closed -eq $true -and $taskPeer.forwarded_connections -eq 0 -and $taskGets.Count -eq 2 -and $taskPosts.Count -eq 1 -and @($taskPeer.accepted).Count -eq 3
    }
    }catch {if(!$taskError){$taskError=$_};$taskJoined=$false}
    # Exact per-run UUID files only; no broad app/user-data or source-tree deletion.
    & $taskAdb -s $Device shell run-as org.mcrf.client rm -f "cache/$taskName"
    $taskAppRemoved=$LASTEXITCODE -eq 0
    & $taskAdb -s $Device shell rm -f "/data/local/tmp/$taskName"
    $taskShellRemoved=$LASTEXITCODE -eq 0
    $taskLocalRemoved=$true
    foreach($taskFile in @($taskInput,(Join-Path $taskPeerRoot 'fixture-private.json'),(Join-Path $taskPeerRoot 'snapshot-private.json'))){
        $taskAbsolute=[IO.Path]::GetFullPath($taskFile)
        if(!($taskAbsolute.StartsWith($taskEvidence+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or $taskAbsolute.StartsWith([IO.Path]::GetFullPath($taskPeerRoot)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase))){throw 'Generated private file escaped owned directories'}
        try {if(Test-Path -LiteralPath $taskAbsolute){Remove-Item -LiteralPath $taskAbsolute};if(Test-Path -LiteralPath $taskAbsolute){$taskLocalRemoved=$false}}
        catch {$taskLocalRemoved=$false;if(!$taskError){$taskError=$_}}
    }
    $taskRemoved=$taskAppRemoved -and $taskShellRemoved -and $taskLocalRemoved
    $taskJoined=$taskJoined -and $taskRemoved
    $taskReceipt=[pscustomobject]@{snapshot=$Snapshot;base_apk_sha256=$taskBaseApkHash;android=$taskAndroid;peer=$taskPeer;actual_gate_passed=$taskJoined;generated_private_files_removed=$taskRemoved;firewall_changed=$false;real_provider_or_vps_used=$false;real_os_revoke_verified=$false;scope='Actual bounded synthetic Steer VLESS IPv4 TUN GET/POST, counters, protect/bind and STOP; not all protocols/IPv6/DNS/leak/revoke/network/Doze/full-release acceptance'}
    [IO.File]::WriteAllText((Join-Path $taskEvidence 'peer-phone-result.json'),($taskReceipt|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
    Write-Output "STEER_PHONE_EVIDENCE=$taskEvidence"
}
if(!$taskJoined){throw 'Bounded Steer Android/host join incomplete; no full acceptance claimed'}
Write-Output 'PASS actual Steer TUN GET/POST with matching host responses and independently observed cleanup; no public provider/server used'
