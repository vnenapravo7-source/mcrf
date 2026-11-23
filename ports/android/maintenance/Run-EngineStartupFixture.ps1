$ErrorActionPreference='Stop'
$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe';$device='emulator-5584'
$root='D:/MCRF/Projects/MCRF';$evidence="$root/work/ports-test-2026-10-14/engine-startup-ui-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $evidence|Out-Null
function Adb([string[]]$argv){$out=(& $adb -s $device @argv 2>&1|Out-String).Trim();if($LASTEXITCODE -ne 0){throw 'Owned emulator command failed'};return $out}
function Hash([string]$package){$path=Adb @('shell','pm','path',$package);if($path -notmatch '^package:(/data/app/[^\r\n]+/base.apk)$'){throw 'Unexpected fixture package layout'};$sum=Adb @('shell','sha256sum',$Matches[1]);if($sum -notmatch '^([0-9a-f]{64})  '){throw 'Fixture package digest missing'};return $Matches[1]}
function Modules {$inventory=[ordered]@{};foreach($kind in @('csqtt','qwdtt','wdtt','dnstt')){$inventory[$kind]=Hash "org.mcrf.engines.$kind"};$inventory}
if((Adb @('shell','getprop','ro.hardware')) -cne 'ranchu'){throw 'Exact disposable emulator required'}
if((Hash 'org.mcrf.client') -cne '1f4d38b0b5355f32244911b432cd9d6bdd864e0012ea909142b8a6962377373a'){throw 'Wrong minified app'}
$before=Modules;$ok=$false;$unchanged=$false
try {
    $out=Adb @('shell','am','instrument','-w','-r','-e','mode','offer','org.mcrf.updatefixture.test/org.mcrf.updatefixture.EngineUpdateFixture')
    [IO.File]::WriteAllText("$evidence/output.log",$out,[Text.UTF8Encoding]::new($false))
    $ok=$out -match 'INSTRUMENTATION_RESULT: passed=true' -and $out -match 'INSTRUMENTATION_CODE: -1'
    if(!$ok){throw 'Actual mixed-version startup offer check failed; inspect fixture output'}
    $image=[regex]::Match($out,'(?m)^INSTRUMENTATION_RESULT: image64=([A-Za-z0-9+/=]+)\s*$')
    if(!$image.Success){throw 'Owned screenshot path not confirmed'}
    [IO.File]::WriteAllBytes("$evidence/offer.png",[Convert]::FromBase64String($image.Groups[1].Value))
    $after=Modules;$unchanged=($before|ConvertTo-Json -Compress) -ceq ($after|ConvertTo-Json -Compress)
    if(!$unchanged){throw 'Cancellation changed an engine package'}
}finally{
    [IO.File]::WriteAllText("$evidence/receipt.json",(@{snapshot='android-integration-e911cb0a00e8461a8c0d9b5067bc6d30';appSha256='1f4d38b0b5355f32244911b432cd9d6bdd864e0012ea909142b8a6962377373a';passed=($ok -and $unchanged);device=$device;modulesUnchanged=$unchanged;before=$before;limit='Owned read-only Android34 x86_64 emulator; exact R8 app/live enduring release, mixed stale/current installed modules and absentCS25, offer+batch consent+cancel. No download/install/provider/Vivo acceptance.'}|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
    Write-Output "ENGINE_STARTUP_UI_RECEIPT=$evidence/receipt.json"
}
