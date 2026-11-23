param([Parameter(Mandatory=$true)][string]$InstallReceipt,[Parameter(Mandatory=$true)][string]$PriorEvidence)
$ErrorActionPreference='Stop'
$checker=Join-Path $PSScriptRoot '../test-installed-csqtt-https.ps1'
$source=Get-Content -Raw $checker
$boundary=[regex]::Match($source,'(?m)^Idle\r?$')
if(!$boundary.Success){throw 'Reusable preflight boundary missing'}
$checkerRoot=Split-Path ([IO.Path]::GetFullPath($checker))
. ([scriptblock]::Create($source.Substring(0,$boundary.Index).Replace('$PSScriptRoot','$checkerRoot'))) -InstallReceipt $InstallReceipt
$prior=Get-Content -Raw (Join-Path $PriorEvidence 'receipt.json')|ConvertFrom-Json
if(!$prior.ownedStartIssued -or !$prior.systemVpnConnected -or $prior.ownedSessionCleaned -or $prior.device -cne $device -or $prior.snapshot -cne $install.snapshot){throw 'Exact unfinished owned session required'}
$evidence=Join-Path $PriorEvidence ('continuation-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $evidence|Out-Null
$remote='/sdcard/mcrf-p0-recovery.xml'
$remoteScreenshot='/sdcard/mcrf-p0-recovery.png'
$phase='resume-existing';$cleaned=$false;$after=$null;$https=@();$failure=$null
try {
    Guard
    $vpn=VpnState
    if(!$vpn.active -or $vpn.ownerPackage -cne 'org.mcrf.client' -or $vpn.activeType -ne 1){throw 'Existing owned VPN not active; no start/retry'}
    $installedPath=(Adb @('shell','pm','path','--user','0','org.mcrf.client')) -replace '^package:',''
    $actualHash=Adb @('shell','sha256sum',$installedPath)
    $expected=@($install.installed|Where-Object {$_.package -ceq 'org.mcrf.client'})
    if($expected.Count -ne 1 -or !$actualHash.StartsWith($expected[0].sha256,[StringComparison]::OrdinalIgnoreCase)){throw 'Installed candidate changed'}
    WriteEvidence 'existing-session.json' @{continuedWithoutStart=$true;vpn=$vpn;actualLabelVisuallyVerified='CSQTT · WhiteList · 188.253.24.174';vpnPageVisuallyVerified=$true;sourceScreenshot='../recovery-before.png'}
    $phase='bounded-https'
    foreach($url in @('https://ya.ru/','https://www.google.com/generate_204','https://www.youtube.com/generate_204','https://chatgpt.com/')){
        Guard
        if(!(VpnActive)){throw 'VPN ended; no reconnect'}
        $out=(& $adb -s $device shell /system/bin/curl --head --proto '=https' --connect-timeout 8 --max-time 15 --silent --show-error --output /dev/null --write-out 'code=%{http_code},dns=%{time_namelookup},tcp=%{time_connect},tls=%{time_appconnect},total=%{time_total},remote=%{remote_ip}' $url 2>&1|Out-String).Trim()
        $code=$LASTEXITCODE
        $metric=[regex]::Match($out,'code=\d{3},dns=[0-9.]+,tcp=[0-9.]+,tls=[0-9.]+,total=[0-9.]+,remote=[a-fA-F0-9.:]*').Value
        $https+=@([ordered]@{url=$url;exitCode=$code;metrics=$metric;tlsVerified=($code -eq 0 -and $metric -ne '' -and $metric -notmatch '^code=000');forcedAddressFamily=$false})
        WriteEvidence 'https.json' $https
        Write-Output "$url exit=$code $metric"
    }
    $phase='await-observed-normal-stop'
    Guard
    Adb @('shell','screencap','-p',$remoteScreenshot)|Out-Null
    Adb @('pull',$remoteScreenshot,(Join-Path $evidence 'before-stop.png'))|Out-Null
}catch{$failure="Failure at $phase : $($_.Exception.Message)"}
WriteEvidence 'receipt.json' @{continuedWithoutStart=$true;device=$device;snapshot=$install.snapshot;phase=$phase;https=$https;ownedSessionCleaned=$false;error=$failure;priorReceipt='../receipt.json';noProfileOrSelectionWrites=$true}
Write-Output "CONTINUATION=$evidence"
if($failure){throw $failure}
