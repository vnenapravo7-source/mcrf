param([Parameter(Mandatory=$true)][string]$InstallReceipt,[string[]]$Modes=@('cold-start','home-ui','settings-pages','logs-ui'))
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$allowed=[IO.Path]::GetFullPath("$root/work/ports-test-2026-10-14")
$path=[IO.Path]::GetFullPath($InstallReceipt)
if(!$path.StartsWith($allowed+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Requires owned install receipt'}
$install=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
if(!$install.passed -or !$install.protectedUnchanged -or !$install.unrelatedModulesUnchanged){throw 'Install has not passed protected checkpoints'}
if(@($Modes|Where-Object {$_ -notin @('cold-start','home-ui','settings-pages','logs-ui','primary-native','edge-ui','server-swipe-ui')}).Count){throw 'Only framework-based read-only UI/native-validation modes; no app-class fixtures against obfuscated APK'}
$device=$install.device
$lease=Get-Content "$root/ports/android/coordination/device-lease.json" -Raw|ConvertFrom-Json
if($lease.device -cne $device -or $lease.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c'){throw 'Main physical lease missing'}
$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
function Adb([string[]]$argv){$output=(& $adb -s $device @argv 2>&1|Out-String).Trim();if($LASTEXITCODE -ne 0){throw 'ADB command failed'};return $output}
function Idle {
    foreach($package in @('org.mcrf.client','com.wdtt.plus','csqtt.quic.amurcanov')){if((Adb @('shell','dumpsys','activity','services',$package)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'Active service; no stop'}}
    if((Adb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (Adb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Screen unavailable; no wake/unlock'}
    if((Adb @('shell','dumpsys','activity','activities')) -match '(?m)^\s+topResumedActivity=.*(?:com\.wdtt\.plus|csqtt\.quic\.amurcanov)/'){throw 'Human in other VPN app'}
    if((Adb @('shell','dumpsys','connectivity')) -match '(?m)^\s+NetworkAgentInfo\{[^\r\n]*\bTransports:\s+[A-Z_|]*\bVPN\b'){throw 'Active VPN; no interference'}
}
$historicalBaseline=Get-Content (Join-Path ([IO.Path]::GetDirectoryName($path)) 'protected-before.json') -Raw|ConvertFrom-Json
$expected=@($install.installed|Where-Object {$_.package -ceq 'org.mcrf.client'})
if($expected.Count -ne 1){throw 'Exact installed app receipt required'}
$remote=Adb @('shell','pm','path','org.mcrf.client')
if($remote -notmatch '^package:(/data/app/[^\r\n]+/base.apk)$'){throw 'Installed app path unavailable'}
if(!(Adb @('shell','sha256sum',$Matches[1])).StartsWith($expected[0].sha256+'  ',[StringComparison]::Ordinal)){throw 'Installed app changed'}
$evidence=Join-Path $allowed ('captcha-r8-ui-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $evidence|Out-Null
$rows=@();$ok=$false
try {
    foreach($mode in $Modes){
        Idle
        $output=Adb @('shell','am','instrument','-w','-r','-e','mode',$mode,'org.mcrf.client.test/org.mcrf.client.ReleaseSmokeInstrumentation')
        [IO.File]::WriteAllText("$evidence/$mode.log",$output,[Text.UTF8Encoding]::new($false))
        $after=[regex]::Match($output,'(?s)INSTRUMENTATION_RESULT: protectedFilesAfter=(.*?)\r?\n\r?\n')
        $before=[regex]::Match($output,'(?s)INSTRUMENTATION_RESULT: protectedFilesBefore=(.*?)\r?\n\r?\n')
        $pass=$output -match 'INSTRUMENTATION_CODE: -1' -and $output -match 'INSTRUMENTATION_RESULT: passed=true' -and $output -match 'INSTRUMENTATION_RESULT: protectedFilesUnchanged=true' -and $output -notmatch 'FAIL|SKIP|Process crashed' -and $before.Success -and $after.Success -and $after.Groups[1].Value.Trim() -ceq $before.Groups[1].Value.Trim()
        $rows+=@([ordered]@{mode=$mode;passed=$pass;freshFullProtectedBaselineEqual=($before.Success -and $after.Success -and $after.Groups[1].Value.Trim() -ceq $before.Groups[1].Value.Trim());historicalInstallBaselineEqual=($before.Success -and $before.Groups[1].Value.Trim() -ceq $historicalBaseline)})
        Write-Output "$mode passed=$pass"
        if(!$pass){throw "Actual R8 UI gate failed: $mode; inspect $evidence"}
    }
    $ok=$true
} finally {
    [IO.File]::WriteAllText("$evidence/receipt.json",([ordered]@{snapshot=$install.snapshot;device=$device;passed=$ok;results=$rows;limit='Physical arm64 exact minified APK; read-only framework UI, not live provider CAPTCHA or Internet traffic'}|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
    Write-Output "R8_UI_RECEIPT=$evidence/receipt.json"
}
