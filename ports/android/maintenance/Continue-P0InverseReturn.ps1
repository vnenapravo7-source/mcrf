param([Parameter(Mandatory=$true)][string]$InstallReceipt,[Parameter(Mandatory=$true)][string]$Evidence)
$ErrorActionPreference='Stop'
$checker=Join-Path $PSScriptRoot '../test-installed-csqtt-https.ps1';$source=Get-Content -Raw $checker
$boundary=[regex]::Match($source,'(?m)^Idle\r?$');if(!$boundary.Success){throw 'Preflight changed'}
$checkerRoot=Split-Path ([IO.Path]::GetFullPath($checker))
. ([scriptblock]::Create($source.Substring(0,$boundary.Index).Replace('$PSScriptRoot','$checkerRoot'))) -InstallReceipt $InstallReceipt
$evidence=$Evidence;$remote='/sdcard/mcrf-p0-usb.xml';$remoteScreenshot='/sdcard/mcrf-p0-usb.png'
$prior=Get-Content -Raw (Join-Path $evidence 'inverse-return-receipt.json')|ConvertFrom-Json
if($device -cne 'R5CX73QYN3D' -or !$prior.vpnStartedForReturnSnapshot -or !$prior.autoEnabledTemporarily){throw 'Exact same snapshot cohort required'}
$result=[ordered]@{continuedWithoutRepeatingVpnSnapshot=$true;originalCs188Selected=$false;bsStartIssued=$false;systemVpnConnected=$false;observationsComplete=$false;autoDetectOnVisuallyVerified='../../bs-stop-before.png';error=$null}
try {
    Idle;$doc=Ui
    for($i=0;$i -lt 6;$i++){
        $row=@($doc.SelectNodes('//node')|Where-Object {$_.class -ceq 'android.widget.FrameLayout' -and $_.'content-desc' -match '^CSQTT, CSQTT, 188\.253\.24\.174,'})
        $manual=@($doc.SelectNodes('//node')|Where-Object {$_.class -ceq 'android.widget.Switch' -and $_.text -ceq 'Автовыбор' -and $_.checked -ceq 'false'})
        if($row.Count -eq 1 -and $manual.Count -eq 1){break}
        $doc=ScrollUi $doc $false
    }
    if($row.Count -ne 1 -or $manual.Count -ne 1){throw 'Observed existing CS188 and manual selection required'}
    Tap $row[0];$result.originalCs188Selected=$true;$doc=Ui
    for($i=0;$i -lt 6;$i++){
        $button=@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -ceq 'Подключить' -and $_.class -ceq 'android.widget.Button'})
        if($button.Count -eq 1){break};$doc=ScrollUi $doc $true
    }
    if($button.Count -ne 1){throw 'Fresh connect control required'}
    Tap $button[0];$result.bsStartIssued=$true
    $watch=[Diagnostics.Stopwatch]::StartNew()
    do{Guard;$v=VpnState;if($v.active -and $v.ownerPackage -ceq 'org.mcrf.client' -and $v.activeType -eq 1){$result.systemVpnConnected=$true;break};Start-Sleep -Seconds 1}while($watch.Elapsed.TotalSeconds -lt 80)
    if(!$result.systemVpnConnected){throw 'BS owned connection did not establish; no retry'}
    Adb @('shell','screencap','-p',$remoteScreenshot)|Out-Null;Adb @('pull',$remoteScreenshot,(Join-Path $evidence 'inverse-bs-established.png'))|Out-Null
    Write-Output 'P0_INVERSE_BS_ESTABLISHED; observing one60s monitor interval, no UI restart'
    $watch.Restart()
    while($watch.Elapsed.TotalSeconds -lt 75){Guard;Start-Sleep -Seconds 5}
    Guard;Adb @('shell','screencap','-p',$remoteScreenshot)|Out-Null;Adb @('pull',$remoteScreenshot,(Join-Path $evidence 'inverse-after-interval.png'))|Out-Null
    $result.observationsComplete=$true;$result.monitorWaitSeconds=[Math]::Round($watch.Elapsed.TotalSeconds,2)
}catch{$result.error=$_.Exception.Message}
finally{WriteEvidence 'inverse-return-continuation.json' $result}
Write-Output ('P0_INVERSE_OBSERVATION='+($result|ConvertTo-Json -Depth 4 -Compress))
if($result.error){throw 'Existing cohort evidence preserved; no retry or force stop'}
