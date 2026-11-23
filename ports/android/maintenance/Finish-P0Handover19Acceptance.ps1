param([Parameter(Mandatory=$true)][string]$InstallReceipt)
$ErrorActionPreference='Stop'
$evidence=[IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($InstallReceipt))
if($evidence -cnotmatch '^D:\\MCRF\\Projects\\MCRF\\work\\ports-test-2026-10-14\\p0-handover19-phone-[a-f0-9]{32}$'){throw 'Exact19 receipt required'}
$d="$evidence/acceptance"
. "$PSScriptRoot/P0Network42-PhoneUi.ps1" -Directory $d -Handover19Acceptance
UiReady;UiForeground
if((P0Vpn).type -ne -1){throw 'Restore normal idle before finishing'}
$x=UiDump '30-final-restored-idle' $d
if(!$x.SelectSingleNode('//node[@content-desc="Подключить · Отключено"]') -or !$x.SelectSingleNode('//node[@text="Выкл"][@checked="true"]') -or $x.SelectSingleNode('//node[@text="Автовыбор"]').checked -ne 'false' -or !$x.SelectSingleNode('//node[@text="🇸🇪 03.10 Швеция VLESS httpupgrade TLS"]')){throw 'Fresh restoration proof missing'}
$install=Get-Content $InstallReceipt -Raw|ConvertFrom-Json
function Digest([string]$Package){$p=UiAdb @('shell','pm','path','--user','0',$Package);if($p -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'APK path'};$sum=UiAdb @('shell','sha256sum',$Matches[1]);if($sum -cnotmatch '^([a-f0-9]{64})\s'){throw 'APK digest'};$Matches[1]}
if((Digest 'org.mcrf.client') -cne $install.sha256){throw 'Main19 changed'}
$expected=Get-Content "$evidence/modules-after.json" -Raw|ConvertFrom-Json
$count=0
foreach($line in ((UiAdb @('shell','pm','list','packages','--user','0','org.mcrf.engines')) -split '\r?\n')){
    if($line -cnotmatch '^package:(org\.mcrf\.engines(?:\.[a-z0-9]+)?)$'){throw 'Module inventory'}
    $p=$Matches[1];if((Digest $p) -cne $expected.$p){throw 'Module changed'};$count++
}
if($count -ne 15){throw 'Exact15 modules required'}
$guardian=Get-Content 'D:/MCRF/Projects/MCRF/work/p0-data-guard-8509f0f4e30040888930e74758e270c2/receipt.json' -Raw|ConvertFrom-Json
if($guardian.state -cne 'PASS' -or !$guardian.sourceStable -or (Digest 'org.mcrf.client.guard') -cne $guardian.sha256){throw 'Guardian changed'}
$out=UiAdb @('shell','am','instrument','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation')
if($out -notmatch 'passed=true' -or $out -notmatch 'stable=true' -or $out -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Guardian failed'}
[IO.File]::WriteAllText("$d/protected-final.log",$out,[Text.UTF8Encoding]::new($false))
function Rows([string]$data){$rows=@{};foreach($hit in [regex]::Matches($data,'(?m)(?:^|protectedHashes=)((?:files|shared_prefs)/[^\r\n=]+)=([a-f0-9]{64}|absent)\s*$')){$rows[$hit.Groups[1].Value]=$hit.Groups[2].Value};$rows}
$before=Rows (Get-Content "$evidence/protected-after.log" -Raw);$after=Rows $out
if($before.Count -ne 20 -or $after.Count -ne 20){throw 'Protected20 incomplete'}
$changed=@($before.Keys|Where-Object {$before[$_] -cne $after[$_]})
UiAdb @('shell','am','start','-W','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n','org.mcrf.client/.Launcher4')|Out-Null
UiReady;UiForeground;P0Finish
@{mainUnchanged=$true;modulesUnchanged=15;protectedExact=20-$changed.Count;protectedChanged=$changed;idle=$true;ownershipReleased=$true}|ConvertTo-Json -Compress
