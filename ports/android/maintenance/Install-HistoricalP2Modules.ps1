param()
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF';$coord="$root/ports/android/coordination"
$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$signer='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0/apksigner.bat'
function Hash([string]$p){(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant()}
function Read([string]$p){Get-Content -LiteralPath $p -Raw|ConvertFrom-Json}
$authorityPath="$coord/p2-historical-modules-authority-2026-10-22.json"
$grant=Read $authorityPath;$authoritySha=Hash $authorityPath;$device=$grant.device
if(!$grant.allowInstall -or $grant.consumed -or $grant.stopForeignVpn -or $grant.allowProviderRegistration -or $device -cne 'R5CX73QYN3D' -or ($grant.packages -join ',') -cne 'org.mcrf.engines.csqtt20,org.mcrf.engines.csqtt215'){throw 'Exact supplementary human authority required'}
$common=[IO.Path]::GetFullPath($grant.common);$accepted=Read "$common/p2-expansion-artifacts.json";$build=Read "$common/build-result.json"
if($build.exitCode -ne 0 -or !$build.sourceStable -or $accepted.sourceManifestSha256 -cne (Hash "$common/source-manifest.json")){throw 'Successful frozen common required'}
function Adb([string[]]$a){$out=(& $adb -s $device @a 2>&1|Out-String).Trim();if($LASTEXITCODE){throw 'ADB command failed; inspect exact stage, do not overwrite unknown package'};$out}
function Certificate([string]$p){$out=(& $signer verify --print-certs $p 2>&1|Out-String);if($LASTEXITCODE -or $out -notmatch 'Signer #1 certificate SHA-256 digest: ([a-f0-9]{64})'){throw 'APK signer not verified'};$Matches[1]}
$job="HistoricalP2Install:$([guid]::NewGuid().ToString('N'))"
function Idle {
    $current=Read "$coord/current.json";$lease=Read "$coord/device-lease.json"
    if((Hash $authorityPath) -cne $authoritySha -or $lease.owner_thread -cne $grant.owner -or $lease.device -cne $device -or $current.activeBatch -cne 'p2-expansion-2026-10-21.json' -or $current.jobs.build -or $current.jobs.hostTest -or $current.jobs.emulator -or ($current.jobs.device -and $current.jobs.device -cne $job)){throw 'Exclusive idle phone and exact authority required'}
    if((Adb @('shell','getprop','ro.product.model')) -cne 'SM-F741B' -or (Adb @('shell','am','get-current-user')) -cne '0'){throw 'Wrong phone/user'}
    if((Adb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (Adb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Awake unlocked phone required'}
    $vpn=Adb @('shell','dumpsys','vpn_management');if($vpn -notmatch 'Active vpn type:' -or $vpn -match 'Active vpn type: [01]\b'){throw 'Existing VPN active or unknown; never stop it'}
    foreach($package in @('org.mcrf.client','org.steer.prototype','com.wdtt.plus','csqtt.quic.amurcanov')){if((Adb @('shell','dumpsys','activity','services',$package)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'User service active; do not interrupt'}}
}
Idle
$main=@($accepted.artifacts|Where-Object {$_.path -match 'app-universal-debug\.apk$'})
if($main.Count -ne 1 -or (Hash "$common/$($main[0].path)") -cne $main[0].sha256){throw 'Exact main artifact required'}
$installedMain=(Adb @('shell','pm','path','org.mcrf.client')).Replace('package:','').Trim()
if((Adb @('shell','sha256sum',$installedMain)) -notmatch "^$($main[0].sha256)\s"){throw 'Installed main differs from accepted common'}
$expectedSigner=Certificate "$common/$($main[0].path)"
$stage="D:/MCRF/Builds/Android/p2-historical-install-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $stage|Out-Null
$result=[ordered]@{state='PREPARING';common=$common;authoritySha256=$authoritySha;installed=@();protectedUnchanged=$false;published=$false}
function Save{[IO.File]::WriteAllText("$stage/receipt.json",($result|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))}
function Guard([string]$name){
    $out=Adb @('shell','am','instrument','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation')
    $out=[regex]::Replace($out,"\r+\n","`n");[IO.File]::WriteAllText("$stage/$name.log",$out,[Text.UTF8Encoding]::new($false))
    $m=[regex]::Match($out,'(?s)INSTRUMENTATION_RESULT: protectedHashes=(.*?)\n\n')
    if(!$m.Success -or $out -notmatch 'passed=true' -or $out -notmatch 'stable=true' -or $out -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Protected-state guard failed'}
    $m.Groups[1].Value.Trim()
}
$claimed=$false;Save;Write-Output "HISTORICAL_INSTALL=$stage"
try{
    foreach($package in $grant.packages){
        if((Adb @('shell','pm','list','packages','--user','0',$package))){
            if($grant.previousCommon -cnotin @('D:/MCRF/Projects/MCRF/work/android-integration-8f1a6328b0b84b9ea46dab8b5e8e4af9','D:/MCRF/Projects/MCRF/work/android-integration-9f4ac9a5d866481d985e746dc9d9654e')){throw 'Historical module already exists; never overwrite unknown installation'}
            $old=Read "$($grant.previousCommon)/p2-expansion-artifacts.json";$kind=$package.Substring($package.LastIndexOf('.')+1)
            $oldRow=@($old.artifacts|Where-Object {$_.path -match "engines-$kind-arm64-v8a-debug\.apk$"})
            $oldApk=(Adb @('shell','pm','path',$package)).Replace('package:','').Trim()
            if($oldRow.Count -ne 1 -or (Adb @('shell','sha256sum',$oldApk)) -notmatch "^$($oldRow[0].sha256)\s" -or (Hash "$($grant.previousCommon)/$($oldRow[0].path)") -cne $oldRow[0].sha256){throw 'Existing module is not the exact preceding authorized installation'}
            $backup="$stage/previous-$package.apk";$null=Adb @('pull',$oldApk,$backup)
            if((Hash $backup) -cne $oldRow[0].sha256 -or (Certificate $backup) -cne $expectedSigner){throw 'Existing module signer or backup changed'}
        }
    }
    $current=Read "$coord/current.json";$current.jobs.device=$job;[IO.File]::WriteAllText("$coord/current.json",($current|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false));$claimed=$true
    $before=Guard 'protected-before'
    foreach($kind in @('csqtt20','csqtt215')){
        Idle
        $row=@($accepted.artifacts|Where-Object {$_.path -match "engines-$kind-arm64-v8a-debug\.apk$"})
        if($row.Count -ne 1){throw 'One exact native ABI artifact required'}
        $file=Join-Path $common $row[0].path
        if((Hash $file) -cne $row[0].sha256 -or (Certificate $file) -cne $expectedSigner){throw 'Module artifact or signer changed'}
        if((Adb @('install','-r','-t',$file)) -notmatch '(?m)^Success\s*$'){throw 'Installation not confirmed'}
        $package="org.mcrf.engines.$kind";$installed=(Adb @('shell','pm','path',$package)).Replace('package:','').Trim()
        if((Adb @('shell','sha256sum',$installed)) -notmatch "^$($row[0].sha256)\s"){throw 'Installed module identity mismatch'}
        $result.installed+=@{package=$package;sha256=$row[0].sha256};Save
    }
    $result.protectedUnchanged=(Guard 'protected-after') -ceq $before
    if(!$result.protectedUnchanged){throw 'Protected state changed; no blind rollback'}
    $result.state='INSTALLED_PROTOCOL_VERIFICATION_PENDING';Save
}catch{$result.state='FAILED_PRESERVED';Save;throw}
finally{if($claimed){$current=Read "$coord/current.json";if($current.jobs.device -ceq $job){$current.jobs.device=$null;[IO.File]::WriteAllText("$coord/current.json",($current|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))}}}
