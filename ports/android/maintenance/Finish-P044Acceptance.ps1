$ErrorActionPreference='Stop'
$evidence='D:/MCRF/Projects/MCRF/work/ports-test-2026-10-14/p044-module-phone-9ae6d001a0c94e7d88aeac61fbbd81e3'
. "$PSScriptRoot/P0Network42-PhoneUi.ps1" -Directory "$evidence/acceptance" -Module7Acceptance
UiReady;UiForeground
if((P0Vpn).type -ne -1){throw 'Owned VPN not idle'}
UiSwipe 550 650 550 2220
UiSwipe 550 650 550 2220
$xml=UiDump '106-restored-idle' "$evidence/acceptance"
if(!$xml.SelectSingleNode('//node[@content-desc="Подключить · Отключено"]')){throw 'Idle scene not visible'}
if($xml.SelectSingleNode('//node[@text="Автовыбор"]').checked -ne 'false' -or $xml.SelectSingleNode('//node[@text="Выкл"][@checked="true"]') -eq $null){throw 'Automatic settings not restored'}
function InstalledDigest([string]$Package){
    $apkPath=UiAdb @('shell','pm','path','--user','0',$Package)
    if($apkPath -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Unexpected installed path'}
    $digest=UiAdb @('shell','sha256sum',$Matches[1])
    if($digest -cnotmatch '^([a-f0-9]{64})\s'){throw 'Installed digest missing'}
    $Matches[1]
}
$main=InstalledDigest 'org.mcrf.client'
if($main -cne '6c6a84e7f0d68ce2c62634dd0c6d5aff2cf4641d0c4df0c9ba946e165ef3cd60'){throw 'Main changed'}
$expected=Get-Content "$evidence/modules-after.json" -Raw|ConvertFrom-Json
$rows=[ordered]@{}
foreach($line in ((UiAdb @('shell','pm','list','packages','--user','0','org.mcrf.engines')) -split '\r?\n'|Sort-Object)){
    if($line -cnotmatch '^package:(org\.mcrf\.engines(?:\.[a-z0-9]+)?)$'){throw 'Unexpected module'}
    $packageName=$Matches[1];$rows[$packageName]=InstalledDigest $packageName
    if($rows[$packageName] -cne $expected.$packageName){throw 'Module changed'}
}
if($rows.Count -ne 15){throw 'Expected15 modules'}
$guard=UiAdb @('shell','am','instrument','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation')
if($guard -notmatch 'passed=true' -or $guard -notmatch 'stable=true' -or $guard -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Guardian failed'}
[IO.File]::WriteAllText("$evidence/acceptance/protected-final.log",$guard,[Text.UTF8Encoding]::new($false))
function HashRows([string]$Data){
    $result=@{}
    foreach($hit in [regex]::Matches($Data,'(?m)(?:^|protectedHashes=)((?:files|shared_prefs)/[^\r\n=]+)=([a-f0-9]{64}|absent)\s*$')){$result[$hit.Groups[1].Value]=$hit.Groups[2].Value}
    $result
}
$before=HashRows (Get-Content "$evidence/protected-after.log" -Raw)
$after=HashRows $guard
if($before.Count -ne 20 -or $after.Count -ne 20){throw 'Protected count changed'}
$changed=@($before.Keys|Where-Object {$before[$_] -cne $after[$_]})
UiAdb @('shell','am','start','-W','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n','org.mcrf.client/.Launcher4')|Out-Null
UiReady;UiForeground
P0Finish
@{idle=$true;mainUnchanged=$true;modulesUnchanged=15;protectedExact=20-$changed.Count;protectedChanged=$changed;ownershipReleased=$true}|ConvertTo-Json -Compress
