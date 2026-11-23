param([Parameter(Mandatory=$true)][string]$InstallReceipt,[Parameter(Mandatory=$true)][string]$Evidence)
$ErrorActionPreference='Stop'
$checker=Join-Path $PSScriptRoot '../test-installed-csqtt-https.ps1';$source=Get-Content -Raw $checker
$boundary=[regex]::Match($source,'(?m)^Idle\r?$');if(!$boundary.Success){throw 'Preflight changed'}
$checkerRoot=Split-Path ([IO.Path]::GetFullPath($checker))
. ([scriptblock]::Create($source.Substring(0,$boundary.Index).Replace('$PSScriptRoot','$checkerRoot'))) -InstallReceipt $InstallReceipt
$evidence=$Evidence;$remoteScreenshot='/sdcard/mcrf-p0-usb.png'
$prior=Get-Content -Raw (Join-Path $evidence 'usb-recovery-receipt.json')|ConvertFrom-Json
if($device -cne 'R5CX73QYN3D' -or !$prior.ownedStartIssued -or !$prior.connectedBefore -or $prior.phase -cne 'owned-vpn-start' -or $prior.wifiRestored){throw 'Exact never-interrupted owned USB connection required'}
Guard;$v=VpnState
if(!$v.active -or $v.ownerPackage -cne 'org.mcrf.client' -or $v.activeType -ne 1){throw 'Owned VPN not active; never reconnect'}
if((Adb @('shell','cmd','wifi','status')) -notmatch '^Wifi is enabled'){throw 'Original WiFi not enabled'}
$result=[ordered]@{continuedWithoutConnect=$true;device=$device;scope='Physical USB WiFi loss and automatic recovery, normal TLS explicitly IPv4 diagnostic; no forced family app setting';baselineIpv4Google204Observed=$true;originalUnforcedFailureRetained=$true;wifiDisabledObserved=$false;wifiRestored=$false;recoveredWithoutRestart=$false;error=$null}
$disabled=$false
try {
    Adb @('shell','svc','wifi','disable')|Out-Null;$disabled=$true
    Start-Sleep -Seconds 6
    $result.wifiDisabledObserved=(Adb @('shell','cmd','wifi','status')) -match '^Wifi is disabled'
    $result.usbStayedAvailable=(Adb @('shell','getprop','ro.serialno')) -ceq $device
    Adb @('shell','svc','wifi','enable')|Out-Null;$disabled=$false;$result.wifiRestored=$true
    $watch=[Diagnostics.Stopwatch]::StartNew();$checks=@()
    do {
        Guard
        $out=(& $adb -s $device shell /system/bin/curl --ipv4 --head --proto '=https' --connect-timeout 5 --max-time 10 --silent --show-error --output /dev/null --write-out 'code=%{http_code},tls=%{time_appconnect},total=%{time_total}' https://www.google.com/generate_204 2>&1|Out-String).Trim()
        $exit=$LASTEXITCODE;$metric=[regex]::Match($out,'code=\d{3},tls=[0-9.]+,total=[0-9.]+').Value
        $v=VpnState;$checks+=@(@{exitCode=$exit;metrics=$metric;systemVpn=$v.active;owner=$v.ownerPackage})
        $result.recoveredWithoutRestart=$exit -eq 0 -and $metric -match '^code=204,' -and $v.active -and $v.ownerPackage -ceq 'org.mcrf.client'
        if(!$result.recoveredWithoutRestart){Start-Sleep -Seconds 4}
    }while(!$result.recoveredWithoutRestart -and $watch.Elapsed.TotalSeconds -lt 80)
    $result.recoverySeconds=[Math]::Round($watch.Elapsed.TotalSeconds,2);$result.https=$checks
}catch{$result.error=$_.Exception.Message}
finally {
    if($disabled){Adb @('shell','svc','wifi','enable')|Out-Null;$result.wifiRestored=$true}
    WriteEvidence 'usb-ipv4-recovery-receipt.json' $result
    try{Guard;Adb @('shell','screencap','-p',$remoteScreenshot)|Out-Null;Adb @('pull',$remoteScreenshot,(Join-Path $evidence 'after-real-network-loss.png'))|Out-Null}catch{}
}
Write-Output ('P0_USB_REAL_LOSS='+($result|ConvertTo-Json -Depth 5 -Compress))
if($result.error -or !$result.recoveredWithoutRestart){throw 'Bounded network recovery not accepted; preserve running ownership, no automatic reconnect'}
