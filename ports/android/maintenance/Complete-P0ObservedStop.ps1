param([Parameter(Mandatory=$true)][string]$InstallReceipt,[Parameter(Mandatory=$true)][string]$Evidence)
$ErrorActionPreference='Stop'
$checker=Join-Path $PSScriptRoot '../test-installed-csqtt-https.ps1'
$source=Get-Content -Raw $checker
$boundary=[regex]::Match($source,'(?m)^Idle\r?$')
if(!$boundary.Success){throw 'Preflight boundary missing'}
$checkerRoot=Split-Path ([IO.Path]::GetFullPath($checker))
. ([scriptblock]::Create($source.Substring(0,$boundary.Index).Replace('$PSScriptRoot','$checkerRoot'))) -InstallReceipt $InstallReceipt
$evidence=$Evidence
$r=Get-Content -Raw (Join-Path $evidence 'receipt.json')|ConvertFrom-Json
if(!$r.continuedWithoutStart -or $r.phase -cne 'await-observed-normal-stop' -or $r.device -cne $device -or $r.snapshot -cne $install.snapshot){throw 'Exact owned continuation required'}
$image=Get-Item (Join-Path $evidence 'before-stop.png')
if(([DateTime]::UtcNow-$image.LastWriteTimeUtc).TotalMinutes -gt 5){throw 'Observed screenshot stale; no tap'}
Guard
$v=VpnState
if(!$v.active -or $v.ownerPackage -cne 'org.mcrf.client' -or $v.activeType -ne 1){throw 'Owned VPN changed; no stop'}
$remote='/sdcard/mcrf-p0-recovery.xml'
$remoteScreenshot='/sdcard/mcrf-p0-recovery.png'
# Native 1080x2640 screenshot was visually inspected: VPN page selected,
# green ring at [48,329]-[360,681], actual CS188 WhiteList session.
Adb @('shell','input','tap','204','485')|Out-Null
$wait=[Diagnostics.Stopwatch]::StartNew();$idle=$false
do {
    $services=Adb @('shell','dumpsys','activity','services','org.mcrf.client')
    $idle=!(VpnActive) -and $services -notmatch 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'
    if(!$idle){Start-Sleep -Milliseconds 500}
}while(!$idle -and $wait.Elapsed.TotalSeconds -lt 35)
WriteEvidence 'normal-stop.json' @{fromPage='VPN';sessionSection='WhiteList';ownedStartOnly=$true;idle=$idle;elapsedSeconds=[Math]::Round($wait.Elapsed.TotalSeconds,2);normalUi=$true;forceStop=$false;sourceImageSha256=(Get-FileHash $image.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
if(!$idle){throw 'Normal stop not confirmed; no force stop'}
Idle
$after=Hashes
WriteEvidence 'protected-after.json' $after
$before=Get-Content -Raw (Join-Path (Split-Path $evidence) 'protected-before.json')|ConvertFrom-Json
$same=$after -ceq $before
Adb @('shell','am','start','-n','org.mcrf.client/.MainActivity')|Out-Null
Adb @('shell','screencap','-p',$remoteScreenshot)|Out-Null
Adb @('pull',$remoteScreenshot,(Join-Path $evidence 'after-stop.png'))|Out-Null
$r|Add-Member -Force NoteProperty ownedSessionCleaned $idle
$r|Add-Member -Force NoteProperty protectedHashesExactlyEqual $same
$r|Add-Member -Force NoteProperty crossSectionNormalStop $true
$r|Add-Member -Force NoteProperty phase 'complete'
WriteEvidence 'receipt.json' $r
Adb @('shell','rm','-f',$remote,$remoteScreenshot)|Out-Null
Write-Output "P0_PHYSICAL_STOP idle=$idle protectedExactlyEqual=$same"
if(!$same){throw 'Protected state differs; preserve evidence, no rollback'}
