param([switch]$Install,[ValidateSet('primary-direct','primary-direct-inverted','primary-direct-negative','primary-direct-inverted-negative')][string[]]$Modes=@('primary-direct','primary-direct-inverted','primary-direct-negative','primary-direct-inverted-negative'))
$ErrorActionPreference='Stop'
if($args.Count){throw 'Unknown arguments'}
$taskRoot='D:/MCRF/Projects/MCRF';$taskDevice='emulator-5586'
$taskSnapshot='android-integration-dda317d3e4514adcb3de5df200e7bddf'
$taskBuild="$taskRoot/work/$taskSnapshot"
$taskAdb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$taskEvidence=Join-Path "$taskRoot/work/ports-test-2026-10-14" ('direct-system-tun-'+[guid]::NewGuid().ToString('N'))
function Adb([string[]]$Arguments){$value=(& $taskAdb -s $taskDevice @Arguments 2>&1|Out-String);if($LASTEXITCODE -ne 0){throw 'Owned emulator ADB command failed; no retry/reset'};return $value}
function Idle {
    foreach($package in @('org.mcrf.client','org.steer.prototype')){
        $services=Adb @('shell','dumpsys','activity','services',$package)
        if($services -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'Active service; no interruption'}
    }
    $vpn=Adb @('shell','dumpsys','vpn_management')
    if($vpn -match 'Active vpn type: [01]\b'){throw 'Active VPN; no interruption'}
}
function Protected {
    $paths=(Adb @('shell','run-as','org.mcrf.client','find','files','shared_prefs','-type','f')) -split '\r?\n' | Where-Object {$_ -match '^shared_prefs/[^\s]+$|^files/(?:mcrf-store-[0-9a-f-]{36}/)?(?:profile.enc|connections.enc|routing.enc|preferences.enc|active-profile.txt)(?:\.bak|\.new)?$'} | Sort-Object
    if(!$paths){throw 'No protected baseline'}
    return (($paths|ForEach-Object {(Adb @('shell','run-as','org.mcrf.client','sha256sum',$_)).Trim()}) -join "`n")
}
function Modules {
    $packages=(Adb @('shell','pm','list','packages','org.mcrf.engines.')) -split '\r?\n' | Where-Object {$_ -match '^package:org\.mcrf\.engines\.[a-z0-9]+$'} | Sort-Object
    if(!$packages){throw 'Missing external modules'}
    return (($packages|ForEach-Object {$package=$_ -replace '^package:','';$path=(Adb @('shell','pm','path',$package)).Trim()-replace '^package:','';if($path -notmatch '^/data/app/[^\s]+/base.apk$'){throw 'Unexpected engine path'};$hash=(Adb @('shell','sha256sum',$path)).Split(' ')[0];"$package=$hash"}) -join "`n")
}
$taskLease=Get-Content -LiteralPath "$taskRoot/ports/android/coordination/emulator-lease.json" -Raw|ConvertFrom-Json
if($taskLease.device -ne $taskDevice -or $taskLease.owner_thread -ne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $taskLease.state -ne 'occupied_main_fixture_acceptance'){throw 'Exclusive owned emulator lease missing'}
$taskProcesses=@(Get-CimInstance Win32_Process|Where-Object {$_.Name -eq 'emulator.exe' -and $_.CommandLine -match 'mcrf-prerelease-clean' -and $_.CommandLine -match '-read-only' -and $_.CommandLine -match '-port 5586'})
if($taskProcesses.Count -ne 1){throw 'Expected exactly one read-only owned emulator'}
if((Adb @('shell','getprop','sys.boot_completed')).Trim() -ne '1' -or (Adb @('shell','getprop','ro.product.cpu.abi')).Trim() -ne 'x86_64'){throw 'Expected booted x86_64 emulator'}
$taskReceipt=Get-Content -LiteralPath "$taskBuild/build-result.json" -Raw|ConvertFrom-Json
if($taskReceipt.exitCode -ne 0 -or !$taskReceipt.sourceStable){throw 'Immutable build not green'}
Idle
if((Adb @('shell','appops','get','org.mcrf.client','ACTIVATE_VPN')) -notmatch 'ACTIVATE_VPN: allow'){throw 'Existing Android VPN consent required; never bypass'}
New-Item -ItemType Directory -Path $taskEvidence|Out-Null
$taskBefore=Protected;$taskModulesBefore=Modules;$taskResults=@();$taskPassed=$false;$taskAfter=$null;$taskModulesAfter=$null
try{
    $taskApp="$taskBuild/ports/android/app/build/outputs/apk/debug/app-x86_64-debug.apk"
    $taskRunner="$taskBuild/ports/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    $env:JAVA_HOME='C:/Program Files/Java/jdk-17'
    foreach($apk in @($taskApp,$taskRunner)){
        $signer=(& 'C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0/apksigner.bat' verify --print-certs $apk 2>&1|Out-String)
        if($LASTEXITCODE -ne 0 -or $signer -notmatch 'Signer #1 certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'APK signer mismatch'}
    }
    $zip=[IO.Compression.ZipFile]::OpenRead($taskApp)
    try{if(@($zip.Entries|Where-Object {$_.FullName -match '^lib/'}).Count){throw 'Base APK includes native payload'}}finally{$zip.Dispose()}
    if($Install){foreach($apk in @($taskApp,$taskRunner)){if((Adb @('install','-r','-t',$apk)) -notmatch '(?m)^Success\s*$'){throw 'Installation failed; no reset/retry'}}}
    $taskAppHash=(Get-FileHash -LiteralPath $taskApp).Hash.ToLowerInvariant()
    $taskInstalledPath=(Adb @('shell','pm','path','org.mcrf.client')).Trim()-replace '^package:',''
    if($taskInstalledPath -notmatch '^/data/app/[^\s]+/base.apk$' -or !(Adb @('shell','sha256sum',$taskInstalledPath)).StartsWith($taskAppHash+' ')){throw 'Installed APK hash mismatch'}
    if((Protected) -cne $taskBefore -or (Modules) -cne $taskModulesBefore){throw 'Installation altered protected baseline or engines'}
    foreach($mode in $Modes){
        Idle
        $output=Adb @('shell','am','instrument','-w','-r','-e','mode',$mode,'org.mcrf.client.test/org.mcrf.client.DeviceSmokeInstrumentation')
        [IO.File]::WriteAllText("$taskEvidence/$mode.log",$output,[Text.UTF8Encoding]::new($false))
        $ok=$output -match 'INSTRUMENTATION_CODE: -1' -and $output -match 'All saved profiles, catalogue, routes, encrypted settings and full preferences remain unchanged' -and $output -notmatch '(?m)(?:^|stream=)FAIL\b|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|\bSKIP\b'
        $count=0;if($output -match 'INSTRUMENTATION_RESULT: passed=(\d+)'){$count=[int]$Matches[1]}
        $taskResults+=@{mode=$mode;passed=$ok;checks=$count}
        Write-Output "$mode passed=$ok checks=$count"
        if(!$ok){Write-Output $output;throw 'Actual TUN gate failed; inspect original receipt before any next attempt'}
        Idle
        if((Protected) -cne $taskBefore -or (Modules) -cne $taskModulesBefore){throw 'Protected baseline changed; no restore'}
    }
    $taskPassed=$true
}finally{
    $taskAfter=Protected;$taskModulesAfter=Modules
    [IO.File]::WriteAllText("$taskEvidence/receipt.json",(@{snapshot=$taskSnapshot;device=$taskDevice;passed=$taskPassed;appSha256=$taskAppHash;results=$taskResults;protectedBefore=$taskBefore;protectedAfter=$taskAfter;protectedUnchanged=($taskBefore -ceq $taskAfter);modulesBefore=$taskModulesBefore;modulesAfter=$taskModulesAfter;modulesUnchanged=($taskModulesBefore -ceq $taskModulesAfter);scope='Owned read-only Android34 x86_64 local origin actual system-TUN CIDR only; NOT public Internet/domain DNS/IPv6/Vivo/provider CAPTCHA';timeUtc=[DateTime]::UtcNow.ToString('o')}|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
    Write-Output "EVIDENCE=$taskEvidence"
}
