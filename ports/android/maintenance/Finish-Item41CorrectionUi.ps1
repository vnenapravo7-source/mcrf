param([Parameter(Mandatory=$true)][string]$InstallReceipt)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Item41-PhoneUi.ps1') -ExpectedVersion 10012
$installation=Get-Content $InstallReceipt -Raw|ConvertFrom-Json
if(!$installation.passed -or !$installation.protectedUnchanged -or !$installation.modulesUnchanged -or $installation.sha256 -cne 'e40c2446d2bf2d77951088dbe0e3f46a4bc1c9a130058aa18f6dd5ff624bb5fc'){throw 'Exact final guarded candidate required'}
$folder=Split-Path $InstallReceipt;$ui="$folder/ui"
function ProtectedManifest([string]$s){$m=[regex]::Match(($s -replace '\r+\n',"`n"),'(?s)INSTRUMENTATION_RESULT: protectedHashes=(.*?)\n\n');if(!$m.Success){throw 'Missing protected map'};$m.Groups[1].Value.Trim()}
$result=[ordered]@{passed=$false;versionCode=10012;sha256=$installation.sha256;protectedUnchanged=$false;modulesUnchanged=$false;noNewVpnSession=$true;scope='Full expanded31 affected checks retained by exact source;13 changed lifecycle checks. Actual Samsung direct+Home Overview, app-launched system Settings return, original Everything-except-RU enabled, no resets/credentials/provider actions. Other UI evidence from previous same-source10012 batch retained.'}
try{
    foreach($name in @('02-direct-overview','03-home-overview')){
        [xml]$x=Get-Content "$ui/$name.xml" -Raw
        if($x.hierarchy.node.package -cne 'com.sec.android.app.launcher' -or @($x.SelectNodes('//node')|Where-Object {$_.'resource-id' -match ':id/taskView$' -and $_.'content-desc' -match 'MCRF'}).Count){throw 'Actual direct/Home Recents gate failed'}
    }
    [xml]$external=Get-Content "$ui/08-system-settings.xml" -Raw;[xml]$returned=Get-Content "$ui/09-external-return.xml" -Raw
    if($external.hierarchy.node.package -cne 'com.android.settings' -or $returned.hierarchy.node.package -cne 'org.mcrf.client'){throw 'External activity return failed'}
    [xml]$route=Get-Content "$ui/10-routes-original.xml" -Raw
    if(@($route.SelectNodes('//node')|Where-Object {$_.class -eq 'android.widget.Switch' -and $_.text -ceq 'Всё кроме РФ' -and $_.checked -ceq 'true'}).Count -ne 1){throw 'Original rule enabled state not proven'}
    UiTap 200 2480;UiDump '11-home-final' $ui|Out-Null;UiScreenshot '11-home-final' $ui
    UiReady
    $guard=UiAdb @('shell','am','instrument','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation')
    [IO.File]::WriteAllText("$ui/protected-final.log",$guard,[Text.UTF8Encoding]::new($false))
    if($guard -notmatch 'passed=true' -or $guard -notmatch 'stable=true' -or $guard -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Final read-only guard failed'}
    $before=ProtectedManifest (Get-Content "$folder/protected-after.log" -Raw);$after=ProtectedManifest $guard
    $result.protectedUnchanged=$before -ceq $after
    $modules=[ordered]@{}
    foreach($line in ((UiAdb @('shell','pm','list','packages','--user','0','org.mcrf.engines')) -split '\r?\n'|Sort-Object)){
        if($line -notmatch '^package:(org\.mcrf\.engines(?:\.[a-z0-9]+)?)$'){throw 'Unexpected package inventory'};$package=$Matches[1]
        $path=UiAdb @('shell','pm','path','--user','0',$package);if($path -notmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Unexpected module path'}
        $hash=UiAdb @('shell','sha256sum',$Matches[1]);if($hash -notmatch '^([0-9a-f]{64})\s'){throw 'Module hash unavailable'};$modules[$package]=$Matches[1]
    }
    $beforeModules=Get-Content "$folder/modules-after.json" -Raw|ConvertFrom-Json -AsHashtable
    $result.modulesUnchanged=$modules.Count -eq 15 -and @($modules.Keys|Where-Object {$modules[$_] -cne $beforeModules[$_]}).Count -eq 0
    if(!$result.protectedUnchanged -or !$result.modulesUnchanged){throw 'Final exact-data gate failed; preserve differences, never rollback ciphertext'}
    $result.protectedEntries=$after.Split("`n").Count;$result.moduleCount=$modules.Count;$result.directOverview=$true;$result.homeOverview=$true;$result.externalReturn=$true;$result.passed=$true
}catch{$result.error=$_.Exception.Message;throw}
finally{
    $result.finishedUtc=[DateTime]::UtcNow.ToString('o');$result|ConvertTo-Json -Depth 6|Set-Content "$ui/receipt.json" -Encoding utf8
    UiEnd $ui
    Write-Output "FINAL_UI=$($result.passed);RECEIPT=$ui/receipt.json"
}
