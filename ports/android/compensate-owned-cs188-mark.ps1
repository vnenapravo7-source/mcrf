$ErrorActionPreference='Stop'
# Exact normal-UI compensation for owned 032afc row tap, not a generic editor.
$taskCompleted=Join-Path $PSScriptRoot '../../work/ports-test-2026-10-14/csqtt-owned-mark-compensation-3721beefcc07413fb5b920461c3bca56/receipt.json'
if(Test-Path -LiteralPath $taskCompleted){throw 'Owned compensation already completed; do not reapply to later human pool choices'}
$taskAdb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$taskDevice='192.168.100.234:41587'
$taskRemote='/data/local/tmp/mcrf-owned-mark-final-20261017.xml'
function TaskAdb([string[]]$a){$v=(& $taskAdb -s $taskDevice @a 2>&1|Out-String).Trim();if($LASTEXITCODE){throw 'ADB failed'};$v}
function TaskGuard {
    if((TaskAdb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (TaskAdb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Awake/unlocked guard'}
    if((TaskAdb @('shell','dumpsys','activity','activities')) -notmatch '(?m)^\s+topResumedActivity=.*org\.mcrf\.client/'){throw 'Own foreground guard'}
    if((TaskAdb @('shell','dumpsys','vpn_management')) -match '(?m)^\s+Active vpn type: [0-9]'){throw 'VPN active; no interference'}
    foreach($p in @('org.mcrf.client','com.wdtt.plus','csqtt.quic.amurcanov')){if((TaskAdb @('shell','dumpsys','activity','services',$p)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'Service active; no interference'}}
}
function TaskUi {TaskAdb @('shell','uiautomator','dump',$taskRemote)|Out-Null;[xml]$d=TaskAdb @('shell','cat',$taskRemote);if(@($d.SelectNodes('//node')|Where-Object {$_.package -and $_.package -notin @('org.mcrf.client','com.android.systemui')}).Count){throw 'External window; no bypass'};return $d}
function TaskRect($n){$m=[regex]::Match($n.bounds,'^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$');if(!$m.Success){throw 'Observed bounds required'};@([int]$m.Groups[1].Value,[int]$m.Groups[2].Value,[int]$m.Groups[3].Value,[int]$m.Groups[4].Value)}
function TaskMark($d,[string]$taskHost){
    $r=@($d.SelectNodes('//node')|Where-Object {$_.'content-desc' -match ('^CSQTT, CSQTT, '+[regex]::Escape($taskHost)+', ') -and $_.class -ceq 'android.widget.LinearLayout'})
    if($r.Count -ne 1){throw 'Exact observed server row required'}
    $b=TaskRect $r[0]
    $c=@($d.SelectNodes('//node')|Where-Object {$_.class -ceq 'android.widget.CheckBox'}|Where-Object {$v=TaskRect $_;$v[0] -ge $b[0] -and $v[1] -ge $b[1] -and $v[2] -le $b[2] -and $v[3] -le $b[3]})
    if($c.Count -ne 1){throw 'Exact row checkbox required'};return $c[0]
}
function TaskTap($n){
    TaskGuard
    $fresh=TaskUi
    $same=@($fresh.SelectNodes('//node')|Where-Object {$_.class -ceq $n.class -and $_.text -ceq $n.text -and $_.'content-desc' -ceq $n.'content-desc' -and $_.checked -ceq $n.checked -and $_.bounds -ceq $n.bounds})
    if($same.Count -ne 1 -or $same[0].enabled -cne 'true'){throw 'Exact fresh enabled control required'}
    $b=TaskRect $same[0]
    TaskAdb @('shell','input','tap',([int](($b[0]+$b[2])/2)).ToString(),([int](($b[1]+$b[3])/2)).ToString())|Out-Null
}
TaskGuard
$taskDoc=TaskUi
for($taskAttempt=0;$taskAttempt -lt 3;$taskAttempt++){
    $taskVisible=@($taskDoc.SelectNodes('//node')|Where-Object {$_.'content-desc' -match '^CSQTT, CSQTT, 188\.253\.24\.174, ' -and $_.class -ceq 'android.widget.LinearLayout'})
    if($taskVisible.Count -eq 1){break}
    TaskGuard
    $taskScroll=@($taskDoc.SelectNodes('//node')|Where-Object {$_.scrollable -ceq 'true'})
    if($taskScroll.Count -ne 1){throw 'Observed own scroll viewport required'}
    $taskBounds=TaskRect $taskScroll[0];$taskX=[int](($taskBounds[0]+$taskBounds[2])/2)
    TaskAdb @('shell','input','swipe',"$taskX",([int]$taskBounds[3]-150).ToString(),"$taskX",([int]$taskBounds[1]+150).ToString(),'450')|Out-Null
    $taskDoc=TaskUi
}
$taskCs=TaskMark $taskDoc '188.253.24.174';$taskOther=TaskMark $taskDoc '94.159.103.27'
if($taskOther.checked -cne 'false'){throw 'Other mark changed; do not touch'}
$taskOriginalMark=$taskCs.checked
if($taskCs.checked -ceq 'true'){TaskTap $taskCs;$taskDoc=TaskUi}
if((TaskMark $taskDoc '188.253.24.174').checked -cne 'false' -or (TaskMark $taskDoc '94.159.103.27').checked -cne 'false'){throw 'Mark compensation not confirmed'}
$taskAuto=@($taskDoc.SelectNodes('//node')|Where-Object {$_.text -ceq 'Автовыбор' -and $_.class -ceq 'android.widget.Switch'})
if($taskAuto.Count -ne 1){throw 'Mode control required'}
if($taskAuto[0].checked -ceq 'true'){TaskTap $taskAuto[0];$taskDoc=TaskUi}
if(@($taskDoc.SelectNodes('//node')|Where-Object {$_.text -ceq 'Автовыбор' -and $_.checked -ceq 'false'}).Count -ne 1){throw 'Latest manual mode not restored'}
TaskGuard
$taskEvidence=Join-Path ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))) ('work/ports-test-2026-10-14/csqtt-owned-mark-compensation-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskEvidence|Out-Null
$taskResult=[ordered]@{priorOwnedError='032afc prestart row tap';observedCs188Before=$taskOriginalMark;cs188AutoMarkRestoredFalse=$true;cs94AutoMarkUnchangedFalse=$true;latestHumanAutomaticFalseRestored=$true;selectedServer='existing CS188';normalUiOnly=$true;vpnStarted=$false;servicesIdle=$true;profilesImportedOrEdited=$false;privatePreferencePatch=$false;scope='Exact own erroneous checkbox compensation. Ordinary probe metadata from prior auto enable may differ; latest human manual mode preserved.'}
[IO.File]::WriteAllText((Join-Path $taskEvidence 'receipt.json'),($taskResult|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
TaskAdb @('shell','rm',$taskRemote)|Out-Null
$taskResult|ConvertTo-Json -Depth 5
"COMPENSATION_RECEIPT=$taskEvidence/receipt.json"
