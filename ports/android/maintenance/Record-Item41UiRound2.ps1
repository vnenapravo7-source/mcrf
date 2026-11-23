param([Parameter(Mandatory=$true)][string]$InstallReceipt)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Item41-PhoneUi.ps1') -ExpectedVersion 10015
$installation=Get-Content $InstallReceipt -Raw|ConvertFrom-Json
if(!$installation.passed -or !$installation.protectedUnchanged -or !$installation.modulesUnchanged -or $installation.versionCode -ne 10015){throw 'Complete guarded10015 install required'}
$folder=Split-Path $InstallReceipt;$ui="$folder/ui"
function ProtectedMap([string]$path){
    $s=(Get-Content $path -Raw) -replace '\r+\n',"`n"
    if($s -notmatch 'passed=true' -or $s -notmatch 'stable=true'){throw 'Guard not stable'}
    $m=[regex]::Match($s,'(?s)INSTRUMENTATION_RESULT: protectedHashes=(.*?)\n\n')
    if(!$m.Success){throw 'Protected inventory absent'}
    $map=@{};foreach($line in ($m.Groups[1].Value.Trim() -split "`n")){
        if($line -notmatch '^([^=]+)=(.*)$'){throw 'Invalid guard line'};$map[$Matches[1]]=$Matches[2]
    };$map
}
function Footer([string]$name,[string[]]$labels,[int]$maxBottom){
    [xml]$doc=Get-Content "$ui/$name.xml" -Raw
    if($doc.hierarchy.node.package -cne 'org.mcrf.client'){throw 'MCRF dialog required'}
    foreach($label in $labels){
        $nodes=@($doc.SelectNodes('//node')|Where-Object {$_.text -ceq $label -and $_.class -ceq 'android.widget.Button'})
        if($nodes.Count -ne 1 -or $nodes[0].bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$'){throw 'Missing dialog action'}
        if([int]$Matches[4]-[int]$Matches[2] -lt 144 -or [int]$Matches[4] -gt $maxBottom){throw 'Clipped footer'}
    }
}
$result=[ordered]@{passed=$false;versionCode=10015;sha256=$installation.sha256;protectedUnchanged=$false;modulesUnchanged=$false;noNewVpnSession=$true;scope='Actual route/app picker/keyboard and proxy auth keyboard footer geometry; visually inspected app-picker keyboard screenshot; light/dark systembar screenshots inspected, original dark returned. No Save/Select, no credentials changed. AdvancedBack->VPN actual10013 evidence retained with unchanged navigation code. New cross-pool manual selection9 JVM checks, not a real transport test.'}
try{
    Footer '03-route-editor' @('Отмена','Сохранить') 2640
    Footer '05-app-picker-keyboard' @('Отмена','Выбрать') 1400
    Footer '12-auth-keyboard' @('Отмена','Сохранить') 1400
    foreach($name in @('05-app-picker-keyboard','15-light-theme','16-dark-restored')){if(!(Test-Path "$ui/$name.png")){throw 'Inspected screenshot absent'}}
    [xml]$light=Get-Content "$ui/15-light-theme.xml" -Raw;[xml]$dark=Get-Content "$ui/16-dark-restored.xml" -Raw
    if(@($light.SelectNodes('//node')|Where-Object {$_.'content-desc' -ceq 'Тёмная тема'}).Count -ne 1 -or @($dark.SelectNodes('//node')|Where-Object {$_.'content-desc' -ceq 'Светлая тема'}).Count -ne 1){throw 'Theme return not proven'}
    $before=ProtectedMap "$folder/protected-after.log";$after=ProtectedMap "$ui/protected-final.log"
    $diff=@(($before.Keys+$after.Keys)|Sort-Object -Unique|Where-Object {$before[$_] -cne $after[$_]})
    if($before.Count -ne 20 -or $after.Count -ne 20 -or $diff.Count){throw 'Protected data drift; do not restore old ciphertext'}
    UiReady
    $modules=[ordered]@{}
    foreach($line in ((UiAdb @('shell','pm','list','packages','--user','0','org.mcrf.engines')) -split '\r?\n'|Sort-Object)){
        if($line -notmatch '^package:(org\.mcrf\.engines(?:\.[a-z0-9]+)?)$'){throw 'Unexpected module'};$package=$Matches[1]
        $path=UiAdb @('shell','pm','path','--user','0',$package);if($path -notmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Unexpected path'}
        $hash=UiAdb @('shell','sha256sum',$Matches[1]);if($hash -notmatch '^([0-9a-f]{64})\s'){throw 'Module hash missing'};$modules[$package]=$Matches[1]
    }
    $old=Get-Content "$folder/modules-after.json" -Raw|ConvertFrom-Json -AsHashtable
    if($modules.Count -ne 15 -or @($modules.Keys|Where-Object {$modules[$_] -cne $old[$_]}).Count){throw 'Module drift'}
    $result.protectedUnchanged=$true;$result.protectedEntries=20;$result.modulesUnchanged=$true;$result.moduleCount=15
    $result.keyboardFooter=$true;$result.proxyAuthFooter=$true;$result.themeBarsBothDirections=$true;$result.originalThemeRestored=$true;$result.passed=$true
}catch{$result.error=$_.Exception.Message;throw}
finally{
    $result.finishedUtc=[DateTime]::UtcNow.ToString('o');$result|ConvertTo-Json -Depth 6|Set-Content "$ui/receipt.json" -Encoding utf8
    UiEnd $ui
    Write-Output "FINAL_UI=$($result.passed);RECEIPT=$ui/receipt.json"
}
