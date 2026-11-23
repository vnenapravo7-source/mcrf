param([int]$ExpectedVersion=10011)
# Dot-source for fresh guarded, ordinary UI actions. No setting writes, permission
# grants, VPN starts, wake/unlock, task removal or application instrumentation.
$taskUiAdb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$taskUiDevice='R5CX73QYN3D'
$taskUiRoot='D:/MCRF/Projects/MCRF'
function UiAdb([string[]]$Arguments){$out=(& $taskUiAdb -s $taskUiDevice @Arguments 2>&1|Out-String).Trim();if($LASTEXITCODE){throw 'UI ADB operation failed'};$out}
function UiReady {
    $lease=Get-Content "$taskUiRoot/ports/android/coordination/device-lease.json" -Raw|ConvertFrom-Json
    if($lease.device -cne $taskUiDevice -or $lease.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $lease.state -cne 'occupied_main_fixture_acceptance'){throw 'Main lease unavailable'}
    if((UiAdb @('shell','am','get-current-user')) -cne '0' -or (UiAdb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (UiAdb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Phone not fresh awake/unlocked; no wake/unlock'}
    if((UiAdb @('shell','dumpsys','vpn_management')) -match 'Active vpn type: [01]\b'){throw 'User VPN active; no UI intervention'}
    foreach($package in @('org.mcrf.client','org.steer.prototype','com.wdtt.plus','csqtt.quic.amurcanov')){if((UiAdb @('shell','dumpsys','activity','services',$package)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'User service active; no UI intervention'}}
    if((UiAdb @('shell','dumpsys','package','org.mcrf.client')) -notmatch "\bversionCode=$ExpectedVersion\b"){throw 'Expected installed item41 version'}
}
function UiDump([string]$Name,[string]$Directory,[switch]$RedactEditors){
    UiReady
    [IO.Directory]::CreateDirectory($Directory)|Out-Null
    $remote='/sdcard/mcrf-item41-owned-ui.xml'
    UiAdb @('shell','rm','-f',$remote)|Out-Null
    $out=UiAdb @('shell','uiautomator','dump',$remote)
    if($out -notmatch 'UI hierchary dumped to:'){throw 'No fresh UI hierarchy; do not act on stale XML'}
    UiAdb @('pull',$remote,"$Directory/$Name.xml")|Out-Null
    [xml]$xml=Get-Content "$Directory/$Name.xml" -Raw
    if($RedactEditors){
        foreach($node in $xml.SelectNodes('//node')){if($node.class -eq 'android.widget.EditText'){$node.SetAttribute('text','[redacted]')}}
        $xml.Save("$Directory/$Name.xml")
    }
    $xml
}
function UiForeground([switch]$AllowSystem){
    $focus=((UiAdb @('shell','dumpsys','window')) -split '\r?\n'|Where-Object {$_ -match '\bmCurrentFocus='}) -join ''
    $allowed=if($AllowSystem){'\b(org\.mcrf\.client|com\.android\.settings|com\.sec\.android\.app\.launcher|com\.android\.documentsui)/'}else{'\borg\.mcrf\.client/'}
    if($focus -notmatch $allowed){throw 'Foreground owner changed; do not tap/scroll/Back in another user app'}
}
function UiTap([int]$X,[int]$Y){UiReady;UiForeground;UiAdb @('shell','input','tap',"$X","$Y")|Out-Null}
function UiSwipe([int]$X1,[int]$Y1,[int]$X2,[int]$Y2){UiReady;UiForeground;UiAdb @('shell','input','swipe',"$X1","$Y1","$X2","$Y2",'400')|Out-Null}
function UiBack {UiReady;UiForeground -AllowSystem;UiAdb @('shell','input','keyevent','4')|Out-Null}
function UiTargetTap([xml]$Document,[string]$Label,[switch]$Description,[string]$Class=''){
    if($Document.hierarchy.node.package -cne 'org.mcrf.client'){throw 'Fresh MCRF hierarchy required'}
    $nodes=@($Document.SelectNodes('//node')|Where-Object {$(if($Description){$_.'content-desc'}else{$_.text}) -ceq $Label -and (!$Class -or $_.class -ceq $Class)})
    if($nodes.Count -ne 1 -or $nodes[0].class -eq 'android.widget.Switch'){throw 'Ambiguous control or Switch; never treat a toggle title as edit/navigation'}
    $targetBounds=[regex]::Match($nodes[0].bounds,'^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    if(!$targetBounds.Success){throw 'Target bounds missing'}
    $targetX=([int]$targetBounds.Groups[1].Value+[int]$targetBounds.Groups[3].Value)/2
    $targetY=([int]$targetBounds.Groups[2].Value+[int]$targetBounds.Groups[4].Value)/2
    UiTap $targetX $targetY
}
function UiLaunch {UiReady;UiAdb @('shell','am','start','-n','org.mcrf.client/.MainActivity')|Out-Null}
function UiBegin([string]$Directory){
    UiReady
    $path="$taskUiRoot/ports/android/coordination/current.json";$current=Get-Content $path -Raw|ConvertFrom-Json
    if($current.jobs.device){throw 'Existing device job; do not duplicate'}
    $current.jobs.device="ITEM41_UI:$Directory";$current|ConvertTo-Json -Depth 16|Set-Content $path -Encoding utf8
}
function UiEnd([string]$Directory){
    $path="$taskUiRoot/ports/android/coordination/current.json";$current=Get-Content $path -Raw|ConvertFrom-Json
    if($current.jobs.device -and $current.jobs.device.StartsWith('ITEM41_UI:',[StringComparison]::Ordinal)){
        $owned=[IO.Path]::GetFullPath($current.jobs.device.Substring('ITEM41_UI:'.Length))
        if($owned.Equals([IO.Path]::GetFullPath($Directory),[StringComparison]::OrdinalIgnoreCase)){$current.jobs.device=$null;$current|ConvertTo-Json -Depth 16|Set-Content $path -Encoding utf8}
    }
}
function UiScreenshot([string]$Name,[string]$Directory){
    UiReady
    UiAdb @('shell','screencap','-p','/sdcard/mcrf-item41-owned-screen.png')|Out-Null
    UiAdb @('pull','/sdcard/mcrf-item41-owned-screen.png',"$Directory/$Name.png")|Out-Null
}
