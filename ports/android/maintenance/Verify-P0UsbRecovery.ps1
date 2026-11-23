param([Parameter(Mandatory=$true)][string]$InstallReceipt,[Parameter(Mandatory=$true)][string]$Evidence,[string]$ServerPattern='^🇩🇪 NetGrid Dusseldorf 09\.08 VLESS tcp REALITY, VLESS, outright7\.ru,')
$ErrorActionPreference='Stop'
$checker=Join-Path $PSScriptRoot '../test-installed-csqtt-https.ps1'
$source=Get-Content -Raw $checker
$boundary=[regex]::Match($source,'(?m)^Idle\r?$')
if(!$boundary.Success){throw 'Preflight boundary changed'}
$checkerRoot=Split-Path ([IO.Path]::GetFullPath($checker))
. ([scriptblock]::Create($source.Substring(0,$boundary.Index).Replace('$PSScriptRoot','$checkerRoot'))) -InstallReceipt $InstallReceipt
if($device -cne 'R5CX73QYN3D'){throw 'USB hardware serial required'}
$evidence=$Evidence;$remote='/sdcard/mcrf-p0-usb.xml';$remoteScreenshot='/sdcard/mcrf-p0-usb.png'
$phase='preflight';$started=$false;$wifiDisabled=$false;$result=[ordered]@{device=$device;sourceSnapshot=$install.snapshot;selectedExistingVpnTemporarily=$false;ownedStartIssued=$false;wifiRestored=$false;error=$null}
function Https {
    $out=(& $adb -s $device shell /system/bin/curl --head --proto '=https' --connect-timeout 5 --max-time 12 --silent --show-error --output /dev/null --write-out 'code=%{http_code},tls=%{time_appconnect},total=%{time_total}' https://www.google.com/generate_204 2>&1|Out-String).Trim()
    $exit=$LASTEXITCODE;$metric=[regex]::Match($out,'code=\d{3},tls=[0-9.]+,total=[0-9.]+').Value
    return @{exitCode=$exit;metric=$metric;normalTls=$true;forcedAddressFamily=$false}
}
try {
    Idle
    if((Adb @('shell','getprop','ro.serialno')) -cne $device -or (Adb @('shell','getprop','ro.kernel.qemu')) -eq '1'){throw 'Wrong physical USB device'}
    $path=(Adb @('shell','pm','path','--user','0','org.mcrf.client')) -replace '^package:',''
    $expected=@($install.installed|Where-Object {$_.package -ceq 'org.mcrf.client'})
    if($expected.Count -ne 1 -or !(Adb @('shell','sha256sum',$path)).StartsWith($expected[0].sha256,[StringComparison]::OrdinalIgnoreCase)){throw 'Installed candidate mismatch'}
    if((Adb @('shell','cmd','wifi','status')) -notmatch '^Wifi is enabled'){throw 'WiFi must already be enabled; never change original disabled state'}
    $doc=Ui
    $row=@($doc.SelectNodes('//node')|Where-Object {$_.class -ceq 'android.widget.FrameLayout' -and $_.'content-desc' -match $ServerPattern})
    if($row.Count -ne 1){throw 'Observed existing VPN server missing'}
    Tap $row[0];$result.selectedExistingVpnTemporarily=$true
    $doc=Ui
    for($i=0;$i -lt 5;$i++){
        $button=@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -ceq 'Подключить' -and $_.enabled -ceq 'true'})
        if($button.Count -eq 1){break}
        $doc=ScrollUi $doc $true
    }
    if($button.Count -ne 1){throw 'Observed connect button missing'}
    $phase='owned-vpn-start';Tap $button[0];$started=$true;$result.ownedStartIssued=$true
    $watch=[Diagnostics.Stopwatch]::StartNew();$connected=$false
    do {
        Guard
        $vpn=VpnState
        $connected=$vpn.active -and $vpn.ownerPackage -ceq 'org.mcrf.client' -and $vpn.activeType -eq 1
        if(!$connected){Start-Sleep -Milliseconds 1000}
    }while(!$connected -and $watch.Elapsed.TotalSeconds -lt 55)
    $result.connectedBefore=$connected
    if(!$connected){throw 'Initial existing VPN did not establish; no repeated server attempts'}
    $result.httpsBefore=Https
    WriteEvidence 'usb-recovery-progress.json' $result
    if($result.httpsBefore.exitCode -ne 0 -or $result.httpsBefore.metric -notmatch '^code=204,'){throw 'Baseline HTTPS did not pass; do not interrupt network'}
    $phase='short-real-wifi-loss'
    Guard
    Adb @('shell','svc','wifi','disable')|Out-Null;$wifiDisabled=$true
    Start-Sleep -Seconds 6
    $result.wifiOffObserved=(Adb @('shell','cmd','wifi','status')) -match '^Wifi is disabled'
    $result.usbStayedAvailable=(Adb @('shell','getprop','ro.serialno')) -ceq $device
    Adb @('shell','svc','wifi','enable')|Out-Null;$wifiDisabled=$false;$result.wifiRestored=$true
    $phase='automatic-recovery'
    $watch.Restart();$checks=@();$recovered=$false
    do {
        Guard
        $check=Https;$checks+=@($check)
        $vpn=VpnState
        $recovered=$check.exitCode -eq 0 -and $check.metric -match '^code=204,' -and $vpn.active -and $vpn.ownerPackage -ceq 'org.mcrf.client'
        if(!$recovered){Start-Sleep -Seconds 4}
    }while(!$recovered -and $watch.Elapsed.TotalSeconds -lt 80)
    $result.httpsRecovery=$checks;$result.recoveredWithoutUiRestart=$recovered;$result.recoverySeconds=[Math]::Round($watch.Elapsed.TotalSeconds,2)
    if(!$recovered){throw 'No automatic HTTPS recovery in bounded window'}
    $phase='await-observed-stop-and-selection-restoration'
}catch{$result.error="${phase}: $($_.Exception.Message)"}
finally {
    if($wifiDisabled){Adb @('shell','svc','wifi','enable')|Out-Null;$result.wifiRestored=$true}
    $result.phase=$phase
    WriteEvidence 'usb-recovery-receipt.json' $result
    if($started){
        try {Guard;Adb @('shell','screencap','-p',$remoteScreenshot)|Out-Null;Adb @('pull',$remoteScreenshot,(Join-Path $evidence 'usb-recovery-current.png'))|Out-Null}catch{}
    }
}
Write-Output ('P0_USB_RECOVERY='+($result|ConvertTo-Json -Depth 5 -Compress))
if($result.error){throw 'Bounded USB recovery gate stopped; own connection/selection evidence retained, no force stop or retry'}
