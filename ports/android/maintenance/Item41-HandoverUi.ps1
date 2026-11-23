param([Parameter(Mandatory=$true)][string]$Directory,[ValidateSet(10016,10017)][int]$Version=10017)
# This helper is ONLY for the fresh, human-authorized10016 saved-server handover.
# Ordinary UI helpers still reject every active VPN. Ownership is armed while idle,
# before the first normal Connect tap; no shell service STOP or permission grants.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Item41-PhoneUi.ps1') -ExpectedVersion $Version
$script:handoverIdleReady=${function:UiReady}
$script:handoverPath=[IO.Path]::GetFullPath($Directory)
if($script:handoverPath -cnotmatch '^D:\\MCRF\\Projects\\MCRF\\work\\ports-test-2026-10-14\\item41-phone-[a-f0-9]{32}\\handover$'){throw 'Unexpected owned handover directory'}
function HandoverVpnState {
    $dump=UiAdb @('shell','dumpsys','vpn_management')
    $owner=[regex]::Match($dump,'(?ms)^  0: ([^\r\n]+)\r?\n(.*?)(?=^  \d+:|\z)')
    if(!$owner.Success){throw 'Owner0 VPN state absent'}
    $type=[regex]::Match($owner.Groups[2].Value,'(?m)^\s+Active vpn type: (-?\d+)\s*$')
    if(!$type.Success){throw 'Current VPN type absent'}
    [pscustomobject]@{owner=$owner.Groups[1].Value.Trim();type=[int]$type.Groups[1].Value}
}
function UiReady {
    $ownership="$script:handoverPath/ownership.json"
    if(!(Test-Path $ownership)){& $script:handoverIdleReady;return}
    $session=Get-Content $ownership -Raw|ConvertFrom-Json
    if(!$session.armed -or $session.versionCode -ne $Version -or $session.authority -cne 'human-saved-vpn-bs-handover-and-restore-2026-10-19'){throw 'No owned handover authority'}
    $lease=Get-Content "$taskUiRoot/ports/android/coordination/device-lease.json" -Raw|ConvertFrom-Json
    $current=Get-Content "$taskUiRoot/ports/android/coordination/current.json" -Raw|ConvertFrom-Json
    if($lease.device -cne $taskUiDevice -or $lease.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $lease.state -cne 'occupied_main_fixture_acceptance' -or !$current.jobs.device -or !$current.jobs.device.StartsWith('ITEM41_UI:',[StringComparison]::Ordinal) -or ![IO.Path]::GetFullPath($current.jobs.device.Substring('ITEM41_UI:'.Length)).Equals($script:handoverPath,[StringComparison]::OrdinalIgnoreCase)){throw 'Owned lease/job changed'}
    if((UiAdb @('shell','am','get-current-user')) -cne '0' -or (UiAdb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (UiAdb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'No wake/unlock'}
    if((UiAdb @('shell','dumpsys','package','org.mcrf.client')) -notmatch "\bversionCode=$Version\b"){throw 'Owned version changed'}
    foreach($package in @('org.steer.prototype','com.wdtt.plus','csqtt.quic.amurcanov')){if((UiAdb @('shell','dumpsys','activity','services',$package)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'Foreign user service, no interference'}}
    $vpn=HandoverVpnState
    if($vpn.type -ge 0 -and $vpn.owner -cne 'org.mcrf.client'){throw 'Foreign VPN, no interference'}
}
function HandoverArm {
    if(Test-Path "$script:handoverPath/ownership.json"){throw 'Resume existing owned session, never arm a duplicate'}
    & $script:handoverIdleReady;UiForeground
    UiBegin $script:handoverPath
    [IO.Directory]::CreateDirectory($script:handoverPath)|Out-Null
    @{armed=$true;versionCode=$Version;authority='human-saved-vpn-bs-handover-and-restore-2026-10-19';armedUtc=[DateTime]::UtcNow.ToString('o');stopped=$false}|ConvertTo-Json|Set-Content "$script:handoverPath/ownership.json" -Encoding utf8
}
function HandoverRelease {
    & $script:handoverIdleReady
    $path="$script:handoverPath/ownership.json";$session=Get-Content $path -Raw|ConvertFrom-Json
    $session.armed=$false;$session.stopped=$true;$session|ConvertTo-Json|Set-Content $path -Encoding utf8
    UiEnd $script:handoverPath
}
