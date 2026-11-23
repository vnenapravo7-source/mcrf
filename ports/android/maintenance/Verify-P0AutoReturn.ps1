param([Parameter(Mandatory=$true)][string]$InstallReceipt,[Parameter(Mandatory=$true)][string]$Evidence)
$ErrorActionPreference='Stop'
$checker=Join-Path $PSScriptRoot '../test-installed-csqtt-https.ps1';$source=Get-Content -Raw $checker
$boundary=[regex]::Match($source,'(?m)^Idle\r?$');if(!$boundary.Success){throw 'Preflight changed'}
$checkerRoot=Split-Path ([IO.Path]::GetFullPath($checker))
. ([scriptblock]::Create($source.Substring(0,$boundary.Index).Replace('$PSScriptRoot','$checkerRoot'))) -InstallReceipt $InstallReceipt
$evidence=$Evidence;$remote='/sdcard/mcrf-p0-usb.xml';$remoteScreenshot='/sdcard/mcrf-p0-usb.png'
$prior=Get-Content -Raw (Join-Path $evidence 'usb-ipv4-recovery-receipt.json')|ConvertFrom-Json
if($device -cne 'R5CX73QYN3D' -or !$prior.recoveredWithoutRestart){throw 'Exact existing owned cohort required'}
$result=[ordered]@{device=$device;autoEnabledTemporarily=$false;originalManualMode=$true;vpnStartedForReturnSnapshot=$false;bsStarted=$false;returnedAutomatically=$false;error=$null}
function Control([string]$description){
    $doc=Ui;$nodes=@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -ceq $description -and $_.class -in @('android.widget.Button','android.widget.RadioButton')})
    if($nodes.Count -ne 1){throw "Unique observed control missing: $description"};return $nodes[0]
}
function OwnedStop {
    Guard;$vpn=VpnState;if($vpn.active -and $vpn.ownerPackage -cne 'org.mcrf.client'){throw 'Other VPN owner'}
    Tap (Control 'Отключить')
    $watch=[Diagnostics.Stopwatch]::StartNew()
    do {
        $services=Adb @('shell','dumpsys','activity','services','org.mcrf.client')
        $idle=!(VpnActive) -and $services -notmatch 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'
        if(!$idle){Start-Sleep -Milliseconds 750}
    }while(!$idle -and $watch.Elapsed.TotalSeconds -lt 35)
    if(!$idle){throw 'Owned normal STOP not idle; no force stop'}
}
function StartOwned {
    Idle;Tap (Control 'Подключить');$watch=[Diagnostics.Stopwatch]::StartNew()
    do {Guard;$v=VpnState;if($v.active -and $v.ownerPackage -ceq 'org.mcrf.client' -and $v.activeType -eq 1){return};Start-Sleep -Seconds 1}while($watch.Elapsed.TotalSeconds -lt 75)
    throw 'Owned connection start deadline; no retry'
}
$phase='stop-prior-owned-sweden'
try {
    OwnedStop
    $phase='temporary-autodetection-on';$on=Control 'Вкл'
    if($on.checked -cne 'false'){throw 'Original AutoDetect OFF required'}
    Tap $on;$result.autoEnabledTemporarily=$true
    $phase='vpn-return-snapshot-start';StartOwned;$result.vpnStartedForReturnSnapshot=$true
    Start-Sleep -Seconds 2
    OwnedStop
    $phase='select-existing-cs188';Tap (Control 'Белые списки');$doc=Ui
    for($i=0;$i -lt 6;$i++){
        $row=@($doc.SelectNodes('//node')|Where-Object {$_.class -ceq 'android.widget.FrameLayout' -and $_.'content-desc' -match '^CSQTT, CSQTT, 188\.253\.24\.174,'})
        if($row.Count -eq 1){break};$doc=ScrollUi $doc $false
    }
    if($row.Count -ne 1){throw 'Observed original CS188 not found'}
    $manual=@($doc.SelectNodes('//node')|Where-Object {$_.class -ceq 'android.widget.Switch' -and $_.text -ceq 'Автовыбор' -and $_.checked -ceq 'false'})
    if($manual.Count -ne 1){throw 'Manual mode not visible; no row/pool mutation'}
    Tap $row[0];$doc=Ui
    for($i=0;$i -lt 6;$i++){
        $button=@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -ceq 'Подключить' -and $_.class -ceq 'android.widget.Button'})
        if($button.Count -eq 1){break};$doc=ScrollUi $doc $true
    }
    $phase='bs-owned-start';StartOwned;$result.bsStarted=$true
    $phase='wait-real-inverse-return';$watch=[Diagnostics.Stopwatch]::StartNew()
    do {
        Guard;$doc=Ui
        $label=@($doc.SelectNodes('//node')|Where-Object {$_.text -match 'Швеция' -and $_.text -match '· VPN ·'})
        if($label.Count -eq 1){$result.returnedAutomatically=$true;break}
        Start-Sleep -Seconds 8
    }while($watch.Elapsed.TotalSeconds -lt 115)
    $result.returnWaitSeconds=[Math]::Round($watch.Elapsed.TotalSeconds,2)
    if(!$result.returnedAutomatically){throw 'No automatic inverse return observed; preserve actual result'}
}catch{$result.error="${phase}: $($_.Exception.Message)"}
finally {
    $result.phase=$phase;WriteEvidence 'inverse-return-receipt.json' $result
    try{Guard;Adb @('shell','screencap','-p',$remoteScreenshot)|Out-Null;Adb @('pull',$remoteScreenshot,(Join-Path $evidence 'inverse-return-current.png'))|Out-Null}catch{}
}
Write-Output ('P0_INVERSE_RETURN='+($result|ConvertTo-Json -Depth 5 -Compress))
if($result.error){throw 'Owned cohort paused for evidence review; no force stop, reset or retry'}
