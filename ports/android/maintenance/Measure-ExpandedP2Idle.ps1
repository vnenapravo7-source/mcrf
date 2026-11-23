$ErrorActionPreference='Stop'
$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe';$device='R5CX73QYN3D'
function Adb([string[]]$arguments){(& $adb -s $device @arguments 2>&1|Out-String).Trim()}
function Idle {
    if((Adb @('shell','dumpsys','vpn_management')) -match 'Active vpn type: [01]\b'){throw 'VPN active; idle measurement refused'}
    if((Adb @('shell','dumpsys','activity','services','org.mcrf.client')) -match 'isForeground=true|startRequested=true'){throw 'Main service active'}
}
Idle
$stage='D:/MCRF/Builds/Android/p2-idle-budget-'+[guid]::NewGuid().ToString('N')
New-Item -ItemType Directory -Path $stage|Out-Null
$packages=@(((Adb @('shell','pm','list','packages','--user','0','-U','org.mcrf')) -split "`n")|ForEach-Object {
    if($_.Trim() -match '^package:(org\.mcrf\.(?:client|engines\.[a-z0-9]+)) uid:([0-9]+)$'){@{package=$Matches[1];uid=[int]$Matches[2]}}
})
if(!@($packages|Where-Object {$_.package -ceq 'org.mcrf.client'}).Count -or $packages.Count -lt 5){throw 'UID inventory incomplete; no traffic claim permitted'}
$uids=@($packages|ForEach-Object {$_.uid})
function Sample {
    Idle
    if((Adb @('shell','dumpsys','netstats','--poll')) -notmatch 'Forced poll'){throw 'Network counters cannot be refreshed'}
    $totals=@{};foreach($uid in $uids){$totals["$uid"]=@{rx=0L;tx=0L}}
    $active=$null
    foreach($line in ((Adb @('shell','dumpsys','netstats','--uid')) -split "`n")){
        if($line -match '\buid=(-?[0-9]+) set='){$active=if([int]$Matches[1] -in $uids){$Matches[1]}else{$null}}
        elseif($null -ne $active -and $line -match '\brb=([0-9]+) rp=[0-9]+ tb=([0-9]+)'){
            $totals[$active].rx+=[long]$Matches[1];$totals[$active].tx+=[long]$Matches[2]
        }
    }
    $processes=@(((Adb @('shell','ps','-A','-o','PID,NAME')) -split "`n")|Where-Object {$_ -match '\borg\.mcrf\.(client(?:$|:)|engines\.)'})
    $pidText=Adb @('shell','pidof','org.mcrf.client');$cpu=$null
    if($pidText -match '^[0-9]+$'){
        $stat=Adb @('shell','run-as','org.mcrf.client','cat',"/proc/$pidText/stat")
        if($stat -match '^([0-9]+) \(.*\) (.*)$'){
            $fields=$Matches[2] -split ' ';$cpu=@{pid=[int]$Matches[1];ticks=([long]$fields[11]+[long]$fields[12]);started=$fields[19]}
        }
    }
    $locks=@((Adb @('shell','dumpsys','power')) -split "`n"|Where-Object {
        $line=$_;@($uids|Where-Object {$line -match ('uid='+$_+'\b')}).Count -gt 0
    }).Count
    @{utc=[DateTime]::UtcNow.ToString('o');network=$totals;cpu=$cpu;processes=$processes;uidWakeLockLines=$locks}
}
$null=Adb @('shell','am','start','--user','0','-n','org.mcrf.client/.MainActivity')
Start-Sleep -Seconds 2
$null=Adb @('shell','input','keyevent','3')
$before=Sample
Write-Output "P2_IDLE_STAGE=$stage"
Start-Sleep -Seconds 30
Write-Output 'P2_IDLE_HALF=30s'
Start-Sleep -Seconds 30
$after=Sample
$seconds=([DateTime]::Parse($after.utc)-[DateTime]::Parse($before.utc)).TotalSeconds
$delta=@($packages|ForEach-Object {
    $id="$($_.uid)";@{package=$_.package;rx=$after.network[$id].rx-$before.network[$id].rx;tx=$after.network[$id].tx-$before.network[$id].tx}
})
$ticks=if($before.cpu -and $after.cpu -and $before.cpu.pid -eq $after.cpu.pid -and $before.cpu.started -ceq $after.cpu.started){$after.cpu.ticks-$before.cpu.ticks}else{$null}
$result=@{scope='Idle after ordinary app open/Home; VPN and detector inactive. Not active-tunnel or mAh battery acceptance.';seconds=$seconds;network=$delta;mainCpuTicks=$ticks;clockTicks=100;before=$before;after=$after}
[IO.File]::WriteAllText("$stage/receipt.json",($result|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
$result|Select-Object seconds,mainCpuTicks,@{n='rx';e={($_.network|Measure-Object rx -Sum).Sum}},@{n='tx';e={($_.network|Measure-Object tx -Sum).Sum}}
$null=Adb @('shell','am','start','--user','0','-n','org.mcrf.client/.MainActivity')
