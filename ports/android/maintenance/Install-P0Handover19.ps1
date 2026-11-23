param([Parameter(Mandatory=$true)][string]$ReceiptPath,[switch]$Preview20)
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF'
$expectedCode=if($Preview20){10020}else{10019};$previousCode=$expectedCode-1
. "$PSScriptRoot/Item41-PhoneUi.ps1" -ExpectedVersion $previousCode
$batch=Get-Content $ReceiptPath -Raw|ConvertFrom-Json
$stagePattern=if($Preview20){'^D:/MCRF/Builds/Android/p0-preview20-[a-f0-9]{32}$'}else{'^D:/MCRF/Builds/Android/p0-handover19-[a-f0-9]{32}$'}
if($batch.state -cne 'PASS' -or !$batch.sourceStable -or !$batch.excludedP1 -or $batch.versionCode -ne $expectedCode -or $ReceiptPath.Replace('\','/') -cne "$($batch.stage)/receipt.json" -or $batch.stage -cnotmatch $stagePattern){throw 'Exact grouped candidate required'}
if($Preview20){if(@($batch.tests|Where-Object {$_.suite -ceq 'EnginePreviewChannelTest' -and $_.tests -eq 10}).Count -ne 1 -or $batch.retainedPriorHostTests -ne 40){throw 'Complete preview discovery gates required'}}elseif($batch.tests.tests -ne 6 -or $batch.retainedHostTests -ne 34){throw 'Complete handover gates required'}
$candidate=$batch.artifacts.universal
$guardian=Get-Content "$root/work/p0-data-guard-8509f0f4e30040888930e74758e270c2/receipt.json" -Raw|ConvertFrom-Json
if($guardian.state -cne 'PASS' -or !$guardian.sourceStable){throw 'Verified guardian required'}
foreach($artifact in @($candidate,$guardian)){if((Get-FileHash $artifact.apk -Algorithm SHA256).Hash.ToLowerInvariant() -cne $artifact.sha256){throw 'Artifact changed'}}
function Installed([string]$Package){$p=UiAdb @('shell','pm','path','--user','0',$Package);if($p -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'APK path'};$sum=UiAdb @('shell','sha256sum',$Matches[1]);if($sum -cnotmatch '^([a-f0-9]{64})\s'){throw 'APK digest'};$Matches[1]}
function Modules {$rows=[ordered]@{};foreach($line in ((UiAdb @('shell','pm','list','packages','--user','0','org.mcrf.engines')) -split '\r?\n'|Sort-Object)){if($line -cnotmatch '^package:(org\.mcrf\.engines(?:\.[a-z0-9]+)?)$'){throw 'Module inventory'};$p=$Matches[1];$rows[$p]=Installed $p};if($rows.Count -ne 15){throw 'Exact15 modules'};$rows}
$evidence="$root/work/ports-test-2026-10-14/"+$(if($Preview20){'p0-preview20-phone-'}else{'p0-handover19-phone-'})+[guid]::NewGuid().ToString('N')
[IO.Directory]::CreateDirectory($evidence)|Out-Null
function Protected([string]$Name){
    $out=UiAdb @('shell','am','instrument','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation')
    [IO.File]::WriteAllText("$evidence/$Name.log",$out,[Text.UTF8Encoding]::new($false))
    if($out -notmatch 'passed=true' -or $out -notmatch 'stable=true' -or $out -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Guardian failed'}
    $match=[regex]::Match(([regex]::Replace($out,"\r+\n","`n")),'(?s)INSTRUMENTATION_RESULT: protectedHashes=(.*?)\r?\n\r?\n')
    if(!$match.Success -or ([regex]::Matches($match.Groups[1].Value,'(?m)^(files|shared_prefs)/')).Count -ne 20){throw 'Protected20 inventory incomplete'}
    $match.Groups[1].Value.Trim()
}
$currentPath="$root/ports/android/coordination/current.json";$current=Get-Content $currentPath -Raw|ConvertFrom-Json
if($current.jobs.build -or $current.jobs.hostTest -or $current.jobs.device){throw 'Existing job'}
$result=[ordered]@{passed=$false;appInstalled=$false;protectedUnchanged=$false;modulesUnchanged=$false;apk=$candidate.apk;sha256=$candidate.sha256;bytes=$candidate.bytes;versionCode=$expectedCode;moduleVersionCode=7;moduleSha='ad76ae8e8ad41e2e7721f19b7a9c4c27eb56d4dadedc4086bcc0308b91b4bbba';device='R5CX73QYN3D';published=$false;authority=$(if($Preview20){'Fresh human NEW prerelease+update phone ifnotcurrent+offerSingbox7. Main20 only, no network/setting changes.'}else{'Latest human do items1 and2; repair installation and controlled tests, restore settings. No publication.'});startedUtc=[DateTime]::UtcNow.ToString('o')}
try {
    $current.jobs.device="P0_HANDOVER19_INSTALL:$evidence";$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8
    UiReady
    if((UiAdb @('shell','getprop','ro.product.model')) -cne 'SM-F741B'){throw 'Exact Samsung required'}
    $previousSha=if($Preview20){'5888a20953f8b19aa84f0a29953960747954216bf6facd2c66644ea97b299de6'}else{'6c6a84e7f0d68ce2c62634dd0c6d5aff2cf4641d0c4df0c9ba946e165ef3cd60'}
    if((Installed 'org.mcrf.client') -cne $previousSha -or (Installed 'org.mcrf.client.guard') -cne $guardian.sha256){throw 'Installed identity drift'}
    $beforeModules=Modules
    if($beforeModules.'org.mcrf.engines.singbox' -cne $result.moduleSha){throw 'Module7 identity drift'}
    $beforeModules|ConvertTo-Json|Set-Content "$evidence/modules-before.json" -Encoding utf8
    $before=Protected 'protected-before'
    UiReady;UiAdb @('shell','am','start','-W','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n','org.mcrf.client/.Launcher4')|Out-Null;UiForeground;UiReady
    if((UiAdb @('install','--user','0','-r',$candidate.apk)) -notmatch '(?m)^Success\s*$'){throw 'Install failed; no reset/retry'}
    $result.appInstalled=$true
    if((Installed 'org.mcrf.client') -cne $candidate.sha256 -or (UiAdb @('shell','dumpsys','package','org.mcrf.client')) -notmatch "\bversionCode=$expectedCode\b"){throw 'Installed version mismatch'}
    $after=Protected 'protected-after';$afterModules=Modules
    $afterModules|ConvertTo-Json|Set-Content "$evidence/modules-after.json" -Encoding utf8
    $result.protectedUnchanged=$before -ceq $after;$result.modulesUnchanged=($beforeModules|ConvertTo-Json -Compress) -ceq ($afterModules|ConvertTo-Json -Compress)
    if(!$result.protectedUnchanged -or !$result.modulesUnchanged){throw 'Data/module drift; preserve evidence, never roll back ciphertext'}
    $result.passed=$true
    UiAdb @('shell','am','start','-W','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n','org.mcrf.client/.Launcher4')|Out-Null
}catch{$result.error=$_.Exception.Message;throw}
finally {
    $result.finishedUtc=[DateTime]::UtcNow.ToString('o');$result|ConvertTo-Json -Depth 8|Set-Content "$evidence/receipt.json" -Encoding utf8
    $current=Get-Content $currentPath -Raw|ConvertFrom-Json;if($current.jobs.device -ceq "P0_HANDOVER19_INSTALL:$evidence"){$current.jobs.device=$null;$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8}
    "HANDOVER19_INSTALL=$($result.passed);RECEIPT=$evidence/receipt.json"
}
