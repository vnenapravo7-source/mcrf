param([Parameter(Mandatory=$true)][string]$PackageReceipt)
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF'
. "$PSScriptRoot/Item41-PhoneUi.ps1" -ExpectedVersion 10018
$packagePath=[IO.Path]::GetFullPath($PackageReceipt)
if($packagePath -cnotmatch '^D:\\MCRF\\Builds\\Android\\p044-module-apk-[a-f0-9]{32}\\receipt.json$'){throw 'Exact external module candidate receipt required'}
$package=Get-Content $packagePath -Raw|ConvertFrom-Json
if($package.state -cne 'PASS_EXTERNAL_MODULE7_STATIC_ONLY' -or !$package.sourceStable -or !$package.excludedP1 -or $package.mainApkBuilt -or $package.moduleVersionCode -ne 7){throw 'Passed external module7 required'}
$candidate=$package.artifacts.'arm64-v8a'
if((Get-FileHash $candidate.apk -Algorithm SHA256).Hash.ToLowerInvariant() -cne $candidate.sha256){throw 'Module artifact changed'}
$guardian=Get-Content "$root/work/p0-data-guard-8509f0f4e30040888930e74758e270c2/receipt.json" -Raw|ConvertFrom-Json
if($guardian.state -cne 'PASS' -or !$guardian.sourceStable -or (Get-FileHash $guardian.apk -Algorithm SHA256).Hash.ToLowerInvariant() -cne $guardian.sha256){throw 'Trusted readonly guardian changed'}
if(@(Get-CimInstance Win32_Process|Where-Object {$_.Name -match '^(emulator|qemu-system.*)\.exe$'}).Count){throw 'Emulator active; no parallel phone install'}
function InstalledHash([string]$Package){
    $path=UiAdb @('shell','pm','path','--user','0',$Package)
    if($path -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Unexpected installed path'}
    $digest=UiAdb @('shell','sha256sum',$Matches[1])
    if($digest -cnotmatch '^([a-f0-9]{64})\s'){throw 'Installed SHA missing'}
    $Matches[1]
}
function ModuleHashes {
    $rows=[ordered]@{}
    foreach($line in ((UiAdb @('shell','pm','list','packages','--user','0','org.mcrf.engines')) -split '\r?\n'|Sort-Object)){
        if($line -cnotmatch '^package:(org\.mcrf\.engines(?:\.[a-z0-9]+)?)$'){throw 'Module inventory changed'}
        $rows[$Matches[1]]=InstalledHash $Matches[1]
    }
    if($rows.Count -ne 15){throw 'Exact existing15 modules required'}
    $rows
}
function Protected([string]$Name){
    $out=UiAdb @('shell','am','instrument','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation')
    [IO.File]::WriteAllText("$evidence/$Name.log",$out,[Text.UTF8Encoding]::new($false))
    if($out -notmatch 'passed=true' -or $out -notmatch 'stable=true' -or $out -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Protected inventory failed'}
    $out=[regex]::Replace($out,"\r+\n","`n")
    $match=[regex]::Match($out,'(?s)INSTRUMENTATION_RESULT: protectedHashes=(.*?)\r?\n\r?\n')
    if(!$match.Success -or $match.Groups[1].Value -notmatch 'files/profile.enc=' -or $match.Groups[1].Value -notmatch 'shared_prefs/identity.xml='){throw 'Incomplete protected inventory'}
    $match.Groups[1].Value.Trim()
}
function ExactIdle {
    UiReady
    if((UiAdb @('shell','getprop','ro.product.model')) -cne 'SM-F741B' -or (UiAdb @('shell','getprop','ro.serialno')) -cne 'R5CX73QYN3D'){throw 'Exact Samsung identity changed'}
}
$currentPath="$root/ports/android/coordination/current.json"
$current=Get-Content $currentPath -Raw|ConvertFrom-Json
if($current.jobs.build -or $current.jobs.device -or $current.jobs.hostTest){throw 'Existing job; no duplicate phone operation'}
# Check lock/idle before creating any new phone job or launching an activity.
ExactIdle
$evidence="$root/work/ports-test-2026-10-14/p044-module-phone-"+[guid]::NewGuid().ToString('N')
[IO.Directory]::CreateDirectory($evidence)|Out-Null
$result=[ordered]@{schema=1;passed=$false;authority='Latest direct human independently open/close/check until fixed. P044 required external singbox module7 ONLY; no main APK/other modules/profile reset/foreign service stop/wake/unlock/router/account/publication.';packageReceipt=$packagePath;candidate=$candidate.apk;sha256=$candidate.sha256;bytes=$candidate.bytes;mainVersionCode=10018;moduleVersionCode=7;moduleInstalled=$false;protectedUnchanged=$false;other14ModulesUnchanged=$false;mainUnchanged=$false;published=$false}
$registered=$false
try{
    $current.jobs.device="P044_MODULE_INSTALL:$evidence";$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8;$registered=$true
    if((InstalledHash 'org.mcrf.client') -cne '6c6a84e7f0d68ce2c62634dd0c6d5aff2cf4641d0c4df0c9ba946e165ef3cd60' -or (InstalledHash 'org.mcrf.client.guard') -cne $guardian.sha256){throw 'Main18/guardian identity changed'}
    if((InstalledHash 'org.mcrf.engines.singbox') -cne '0e032b576babfa355fc80880420010eb5aba1c088c6fd96ad728e89d953d7cbd'){throw 'Expected module6 changed; do not overwrite/retry'}
    # Explicit latest navigation authority: normal app launch, never human foreground busywork.
    ExactIdle;UiAdb @('shell','am','start','-W','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n','org.mcrf.client/.Launcher4')|Out-Null
    Start-Sleep -Milliseconds 500
    ExactIdle;UiForeground
    $before=Protected 'protected-before';$modules=ModuleHashes
    $modules|ConvertTo-Json|Set-Content "$evidence/modules-before.json" -Encoding utf8
    # The readonly instrumentation ends its target process. Reopen normally;
    # do not mistake the resulting Chrome foreground for a human intervention.
    ExactIdle;UiAdb @('shell','am','start','-W','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n','org.mcrf.client/.Launcher4')|Out-Null
    ExactIdle;UiForeground
    if((UiAdb @('install','--user','0','-r',$candidate.apk)) -notmatch '(?m)^Success\s*$'){throw 'Module install failed; no uninstall/reset/retry'}
    $result.moduleInstalled=$true
    if((InstalledHash 'org.mcrf.engines.singbox') -cne $candidate.sha256 -or (UiAdb @('shell','dumpsys','package','org.mcrf.engines.singbox')) -notmatch '\bversionCode=7\b'){throw 'Installed module7 mismatch'}
    $after=Protected 'protected-after';$afterModules=ModuleHashes
    $afterModules|ConvertTo-Json|Set-Content "$evidence/modules-after.json" -Encoding utf8
    $result.protectedUnchanged=$before -ceq $after
    $result.other14ModulesUnchanged=$true
    foreach($name in $modules.Keys){if($name -cne 'org.mcrf.engines.singbox' -and $modules[$name] -cne $afterModules[$name]){$result.other14ModulesUnchanged=$false}}
    $result.mainUnchanged=(InstalledHash 'org.mcrf.client') -ceq '6c6a84e7f0d68ce2c62634dd0c6d5aff2cf4641d0c4df0c9ba946e165ef3cd60'
    ExactIdle
    if(!$result.protectedUnchanged -or !$result.other14ModulesUnchanged -or !$result.mainUnchanged){throw 'Post-install drift; preserve evidence, never restore ciphertext'}
    $result.passed=$true
}catch{$result.error=$_.Exception.Message;throw}
finally{
    $result.finishedUtc=[DateTime]::UtcNow.ToString('o');$result|ConvertTo-Json -Depth 8|Set-Content "$evidence/receipt.json" -Encoding utf8
    if($registered){$current=Get-Content $currentPath -Raw|ConvertFrom-Json;if($current.jobs.device -ceq "P044_MODULE_INSTALL:$evidence"){$current.jobs.device=$null;$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8}}
    Write-Output "P044_MODULE_INSTALL=$($result.passed);RECEIPT=$evidence/receipt.json"
}
