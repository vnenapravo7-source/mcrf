param([Parameter(Mandatory=$true)][string]$Directory,[switch]$Ipv4Comparison,[switch]$DirectInvestigation,[switch]$Module7Acceptance,[switch]$DualStackAcceptance,[switch]$Handover19Acceptance)
# Fresh10018 Samsung acceptance only. Never reuse prior item41 ownership markers.
$ErrorActionPreference='Stop'
$script:p0ExpectedVersion=if($Handover19Acceptance){10019}else{10018}
. (Join-Path $PSScriptRoot 'Item41-PhoneUi.ps1') -ExpectedVersion $script:p0ExpectedVersion
$script:p0IdleReady=${function:UiReady}
$script:p0UiPath=[IO.Path]::GetFullPath($Directory)
if(([int]$Ipv4Comparison.IsPresent+[int]$DirectInvestigation.IsPresent+[int]$Module7Acceptance.IsPresent+[int]$Handover19Acceptance.IsPresent) -gt 1){throw 'Choose exactly one fresh phone scope'}
if($DualStackAcceptance -and !$Module7Acceptance){throw 'Dual-stack diagnostic requires installed module7 proof'}
$expectedSuffix=if($DualStackAcceptance){'acceptance-dualstack'}elseif($Ipv4Comparison){'acceptance-ipv4'}elseif($DirectInvestigation){'acceptance-direct-debug'}else{'acceptance'}
$parentPattern=if($Handover19Acceptance){'p0-handover19-phone-'}elseif($Module7Acceptance){'p044-module-phone-'}else{'p0-network42-phone-'}
if($script:p0UiPath -cnotmatch ('^D:\\MCRF\\Projects\\MCRF\\work\\ports-test-2026-10-14\\'+$parentPattern+'[a-f0-9]{32}\\'+$expectedSuffix+'$')){throw 'Unexpected10018 acceptance directory'}
$script:p0Authority=if($Handover19Acceptance){'human-20261019-do-items1-and2-fixed-handover19-restore'}elseif($DualStackAcceptance){'human-20261019-do-items1-and2-dualstack-saved-servers-restore'}elseif($Module7Acceptance){'human-20261019-check-phone-and-complete-module7-restore'}elseif($Ipv4Comparison){'human-ipv4-comparison-restore-OFF-call5dab259c78d74e0892041374c67af540'}elseif($DirectInvestigation){'human-20261019-independent-open-close-check-until-fixed-normal-UI-owned-VPN-restore'}else{'human-network42-saved-vpn-bs-chrome-direct-restore-call3800e02eed6d4191a90236a046e272a0'}
$install=Get-Content (Join-Path ([IO.Path]::GetDirectoryName($script:p0UiPath)) 'receipt.json') -Raw|ConvertFrom-Json
if($Handover19Acceptance){
    if(!$install.passed -or !$install.appInstalled -or !$install.protectedUnchanged -or !$install.modulesUnchanged -or $install.versionCode -ne 10019 -or $install.moduleVersionCode -ne 7){throw 'Verified19 with unchanged module7 required'}
}elseif($Module7Acceptance){
    if(!$install.passed -or !$install.moduleInstalled -or !$install.protectedUnchanged -or !$install.other14ModulesUnchanged -or !$install.mainUnchanged -or $install.mainVersionCode -ne 10018 -or $install.moduleVersionCode -ne 7){throw 'Verified main18 plus module7 install required'}
}elseif(!$install.passed -or !$install.appInstalled -or !$install.protectedUnchanged -or !$install.modulesUnchanged -or $install.versionCode -ne 10018){throw 'Verified10018 install required'}
function P0Vpn {
    $dump=UiAdb @('shell','dumpsys','vpn_management')
    $owner=[regex]::Match($dump,'(?ms)^  0: ([^\r\n]+)\r?\n(.*?)(?=^  \d+:|\z)')
    if(!$owner.Success){throw 'Owner0 VPN state absent'}
    $type=[regex]::Match($owner.Groups[2].Value,'(?m)^\s+Active vpn type: (-?\d+)\s*$')
    if(!$type.Success){throw 'VPN type absent'}
    [pscustomobject]@{owner=$owner.Groups[1].Value.Trim();type=[int]$type.Groups[1].Value}
}
function UiReady {
    $ownership="$script:p0UiPath/ownership.json"
    if(!(Test-Path -LiteralPath $ownership)){& $script:p0IdleReady;return}
    $session=Get-Content -LiteralPath $ownership -Raw|ConvertFrom-Json
    if(!$session.armed -or $session.versionCode -ne $script:p0ExpectedVersion -or $session.authority -cne $script:p0Authority){throw 'No fresh acceptance authority'}
    $lease=Get-Content "$taskUiRoot/ports/android/coordination/device-lease.json" -Raw|ConvertFrom-Json
    $current=Get-Content "$taskUiRoot/ports/android/coordination/current.json" -Raw|ConvertFrom-Json
    if($lease.device -cne $taskUiDevice -or $lease.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $lease.state -cne 'occupied_main_fixture_acceptance' -or $current.jobs.device -cne "ITEM41_UI:$script:p0UiPath"){throw 'Owned10018 lease/job changed'}
    if((UiAdb @('shell','am','get-current-user')) -cne '0' -or (UiAdb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (UiAdb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Phone locked/asleep; no wake/unlock'}
    if((UiAdb @('shell','dumpsys','package','org.mcrf.client')) -notmatch "\bversionCode=$script:p0ExpectedVersion\b"){throw 'Installed version changed'}
    foreach($package in @('org.steer.prototype','com.wdtt.plus','csqtt.quic.amurcanov')){if((UiAdb @('shell','dumpsys','activity','services',$package)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'Foreign user service; no interference'}}
    $vpn=P0Vpn
    if($vpn.type -ge 0 -and $vpn.owner -cne 'org.mcrf.client'){throw 'Foreign VPN; no interference'}
}
function P0Begin([object]$Original=$null) {
    if(Test-Path -LiteralPath "$script:p0UiPath/ownership.json"){throw 'Resume existing10018 session; do not re-arm'}
    & $script:p0IdleReady;UiForeground
    if($Module7Acceptance -or $Handover19Acceptance){
        if(!$Original -or !$Original.server -or $null -eq $Original.ipv4Compatibility){throw 'Fresh UI-verified original settings required'}
        $modulePath=UiAdb @('shell','pm','path','--user','0','org.mcrf.engines.singbox')
        if($modulePath -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Module7 path missing'}
        $expectedModuleSha=if($Handover19Acceptance){$install.moduleSha}else{$install.sha256}
        if((UiAdb @('shell','sha256sum',$Matches[1])) -cnotmatch ('^'+[regex]::Escape($expectedModuleSha)+'\s')){throw 'Installed module7 bytes changed'}
    }
    UiBegin $script:p0UiPath
    [IO.Directory]::CreateDirectory($script:p0UiPath)|Out-Null
    if(!$Original){$Original=@{server='🇸🇪 03.10 Швеция VLESS httpupgrade TLS';autoSelection=$false;autoDetectBs=$false;mode='VPN';view='VPN';ipv4Compatibility=$false}}
    @{armed=$true;versionCode=$script:p0ExpectedVersion;installedSha=$(if($Module7Acceptance){'6c6a84e7f0d68ce2c62634dd0c6d5aff2cf4641d0c4df0c9ba946e165ef3cd60'}else{$install.sha256});moduleSha=$(if($Handover19Acceptance){$install.moduleSha}elseif($Module7Acceptance){$install.sha256}else{$null});authority=$script:p0Authority;armedUtc=[DateTime]::UtcNow.ToString('o');original=$Original;stopped=$false}|ConvertTo-Json -Depth 5|Set-Content "$script:p0UiPath/ownership.json" -Encoding utf8
}
function P0ChromeReady {
    UiReady
    $focus=(UiAdb @('shell','dumpsys','window')) -split '\r?\n'|Where-Object {$_ -match '\bmCurrentFocus='}
    if(($focus -join '') -notmatch '\bcom\.android\.chrome/'){throw 'Chrome foreground changed; no input'}
}
function P0ConnectIdle([string]$EvidenceName,[bool]$Automatic) {
    if($EvidenceName -cnotmatch '^[a-zA-Z0-9-]+$'){throw 'Unique named connect preflight required'}
    UiReady;UiForeground
    if((P0Vpn).type -ge 0){throw 'Connect preflight requires idle'}
    $screen=UiDump $EvidenceName $script:p0UiPath
    $auto=@($screen.SelectNodes('//node')|Where-Object {$_.text -ceq 'Автовыбор' -and $_.class -eq 'android.widget.Switch'})
    if($auto.Count -ne 1 -or $auto[0].checked -cne $Automatic.ToString().ToLowerInvariant()){throw 'Connect preflight automatic-mode mismatch'}
    $button=@($screen.SelectNodes('//node')|Where-Object {$_.'content-desc' -ceq 'Подключить · Отключено' -and $_.class -eq 'android.widget.Button'})
    if($button.Count -ne 1){throw 'Connect scene is not visible; never tap old scene coordinates in a scrolled server list'}
    $bounds=[regex]::Match($button[0].bounds,'^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    if(!$bounds.Success -or [int]$bounds.Groups[2].Value -lt 300 -or [int]$bounds.Groups[4].Value -gt 1500 -or [int]$bounds.Groups[4].Value-[int]$bounds.Groups[2].Value -lt 300){throw 'Connect scene clipped or moved; inspect and scroll normally first'}
    UiTargetTap $screen 'Подключить · Отключено' -Description -Class 'android.widget.Button'
}
function P0PopupChoose([string]$Label){
    UiReady
    $focus=(UiAdb @('shell','dumpsys','window')) -split '\r?\n'|Where-Object {$_ -match '\bmCurrentFocus='}
    $resumed=(UiAdb @('shell','dumpsys','activity','activities')) -split '\r?\n'|Where-Object {$_ -match 'topResumedActivity='}
    if(($focus -join '') -notmatch '\bu0 PopupWindow:' -or ($resumed -join '') -notmatch '\borg\.mcrf\.client/'){throw 'Not an owned MCRF popup'}
    $xml=UiDump 'popup-guard' $script:p0UiPath
    if($xml.hierarchy.node.package -cne 'org.mcrf.client'){throw 'Popup package mismatch; no tap'}
    $nodes=@($xml.SelectNodes('//node')|Where-Object {$_.text -ceq $Label -and $_.class -eq 'android.widget.CheckedTextView'})
    if($nodes.Count -ne 1){throw 'Popup option ambiguous'}
    $bounds=[regex]::Match($nodes[0].bounds,'^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    if(!$bounds.Success){throw 'Missing popup bounds'}
    UiReady
    UiAdb @('shell','input','tap',"$(([int]$bounds.Groups[1].Value+[int]$bounds.Groups[3].Value)/2)","$(([int]$bounds.Groups[2].Value+[int]$bounds.Groups[4].Value)/2)")|Out-Null
}
function P0ChromeTap([int]$X,[int]$Y){P0ChromeReady;UiAdb @('shell','input','tap',"$X","$Y")|Out-Null}
function P0ChromeAddress([string]$Url){
    if($Url -cne 'https://yandex.ru/'){throw 'Only authorizedYandex destination'}
    P0ChromeReady;UiAdb @('shell','input','text',$Url)|Out-Null
    P0ChromeReady;UiAdb @('shell','input','keyevent','66')|Out-Null
}
function P0Screenshot([string]$Name){
    UiReady
    $focus=(UiAdb @('shell','dumpsys','window')) -split '\r?\n'|Where-Object {$_ -match '\bmCurrentFocus='}
    if(($focus -join '') -notmatch '\b(org\.mcrf\.client|com\.android\.chrome)/'){throw 'Unrelated foreground; no capture'}
    UiScreenshot $Name $script:p0UiPath
}
function P0Finish {
    & $script:p0IdleReady
    $path="$script:p0UiPath/ownership.json";$session=Get-Content $path -Raw|ConvertFrom-Json
    $session.armed=$false;$session.stopped=$true;$session|ConvertTo-Json -Depth 5|Set-Content $path -Encoding utf8
    UiEnd $script:p0UiPath
}
