param([Parameter(Mandatory)][string]$Receipt,[ValidateSet('bootstrap','proxy','tun','failover','warp','optional','optional_tls','service','restore_ui')][string[]]$Phases=@('failover','warp'))
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF';$coord="$root/ports/android/coordination";$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe';$device='R5CX73QYN3D'
function Hash([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Read([string]$p){Get-Content -LiteralPath $p -Raw|ConvertFrom-Json}
if([IO.Path]::GetFullPath($Receipt) -cnotmatch '^D:\\MCRF\\Builds\\Android\\p2-runtime-checks-[a-f0-9]{32}\\receipt\.json$'){throw 'Exact owned verification build required'}
$built=Read $Receipt;$stage=Split-Path $Receipt;$apk="$stage/checks.apk";$common=Read "$($built.common)/p2-candidate-artifacts.json"
if(@($Phases|Where-Object {$_ -cnotin $built.phases}).Count){throw 'Selected phases are absent from this exact compiled helper'}
if($built.state -cne 'TEST_ONLY_COMPILED_NOT_RUN' -or (Hash $apk) -cne $built.sha256 -or (Hash "$($built.common)/p2-candidate-artifacts.json") -cne $built.commonArtifactsSha256){throw 'Pinned completed verification build required'}
$grant=Read "$coord/p2-runtime-authority.json";$grantSha=Hash "$coord/p2-runtime-authority.json"
if($grant.allowWarpRegistration -eq $true -and ($Phases.Count -ne 1 -or $Phases[0] -cne 'warp' -or $grant.warpRegistrationDirectHuman -cne 'сделай новый')){throw 'New-account authority is restricted to the remaining WARP phase'}
if(!$grant.allowAppInstall -or !$grant.allowOwnedTestVpn -or $grant.sourceReadySha256 -cne $common.readinessSha256 -or $grant.stopForeignVpn -or $grant.allowPublication){throw 'Current whole-P2 test grant required'}
if(@($Phases|Where-Object {$_ -cin @('service','restore_ui')}).Count -and (!$grant.allowCombinedServiceChecks -or !$grant.allowTemporarySteerDnsConsent -or !$grant.allowWifiCycle)){throw 'Explicit final service/network/consent grant required'}
$run="$stage/run-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $run|Out-Null
$result=[ordered]@{state='PREPARING';buildReceiptSha256=Hash $Receipt;controllerSha256=Hash $PSCommandPath;grantSha256=$grantSha;checks=@();installed=$false;removed=$false;cleanupPending=$false;fullP2Accepted=$false;published=$false}
$job="P2OwnedRuntime:$([guid]::NewGuid().ToString('N'))";$claimed=$false;$before=$null;$accountHash=$null
function Save{[IO.File]::WriteAllText("$run/receipt.json",($result|ConvertTo-Json -Depth 9),[Text.UTF8Encoding]::new($false))}
function Adb([string[]]$a){$out=(& $adb -s $device @a 2>&1|Out-String).Trim();if($LASTEXITCODE){throw "Exact device command failed ($($a -join ' '), exit=$LASTEXITCODE)"};$out}
function Idle{
    $current=Read "$coord/current.json";$lease=Read "$coord/device-lease.json"
    if((Hash "$coord/p2-runtime-authority.json") -cne $grantSha -or $current.activeBatch -cne 'p2-progress-2026-10-20.json' -or $current.jobs.build -or $current.jobs.hostTest -or $current.jobs.emulator -or
        ($current.jobs.device -and $current.jobs.device -cne $job) -or $lease.device -cne $device -or $lease.owner_thread -cne $grant.owner -or $lease.state -cne 'occupied_main_fixture_acceptance'){throw 'Fresh main lease required'}
    if((Adb @('shell','am','get-current-user')) -cne '0' -or (Adb @('shell','getprop','ro.product.model')) -cne 'SM-F741B'){throw 'Device changed'}
    if((Adb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (Adb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Fresh awake/unlocked state required'}
    $vpn=Adb @('shell','dumpsys','vpn_management');if($vpn -notmatch 'Active vpn type:' -or $vpn -match 'Active vpn type: [01]\b'){throw 'User VPN active; no stop allowed'}
    foreach($package in @('org.mcrf.client','org.steer.prototype','com.wdtt.plus','csqtt.quic.amurcanov')){if((Adb @('shell','dumpsys','activity','services',$package)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'User service active; no stop allowed'}}
}
function Guard([string]$name){
    $out=Adb @('shell','am','instrument','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation')
    $out=[regex]::Replace($out,"\r+\n","`n");[IO.File]::WriteAllText("$run/$name.log",$out,[Text.UTF8Encoding]::new($false))
    $m=[regex]::Match($out,'(?s)INSTRUMENTATION_RESULT: protectedHashes=(.*?)\n\n')
    if(!$m.Success -or $out -notmatch 'passed=true' -or $out -notmatch 'stable=true' -or $out -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Guardian inventory failed'}
    $m.Groups[1].Value.Trim()
}
try{
    Idle
    # Recheck every current app/module against the common manifest; no build,
    # installation or previously closed code-test execution in VerifyOnly.
    & "$root/ports/android/maintenance/Install-P2Common.ps1" -Snapshot $built.common -VerifyOnly
    if((Adb @('shell','pm','list','packages','--user','0','org.mcrf.client.p2checks'))){throw 'Unexpected existing helper; never overwrite'}
    Idle;$current=Read "$coord/current.json";$current.jobs.device=$job
    [IO.File]::WriteAllText("$coord/current.json",($current|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false));$claimed=$true
    $before=Guard 'protected-before';Save
    if((Hash $apk) -cne $built.sha256){throw 'Fixture APK changed'}
    $out=Adb @('install','-t',$apk);if($out -notmatch '(?m)^Success\s*$'){throw 'Helper install not confirmed'};$result.installed=$true;Save
    foreach($phase in $Phases){
        Idle
        $arguments=@('shell','am','instrument','-w','-r','-e','phase',$phase)
        if($phase -ceq 'warp' -and $grant.allowTemporaryWarpConsent -eq $true){$arguments+=@('-e','temporaryWarpConsent','true')}
        if($phase -ceq 'warp' -and $grant.allowWarpRegistration -eq $true){$arguments+=@('-e','allowWarpRegistration','true')}
        if($phase -cin @('service','restore_ui')){$arguments+=@('-e','allowServiceScenarios','true')}
        $out=Adb ($arguments+@('org.mcrf.client.p2checks/org.mcrf.client.P2RuntimeInstrumentation'))
        [IO.File]::WriteAllText("$run/$phase.log",$out,[Text.UTF8Encoding]::new($false))
        $account=[regex]::Match($out,'(?m)^INSTRUMENTATION_RESULT: authorizedPreferencesHash=([a-f0-9]{64})\s*$')
        if($phase -ceq 'warp' -and $grant.allowWarpRegistration -eq $true -and $account.Success){$accountHash=$account.Groups[1].Value;$result.authorizedNewWarpAccount=$true;$result.preferencesSha256=$accountHash}
        $m=[regex]::Match($out,'(?m)^INSTRUMENTATION_RESULT: passed=([0-9]+)\s*$');$count=if($m.Success){[int]$m.Groups[1].Value}else{0}
        $ok=$count -gt 0 -and $out -match '(?m)^INSTRUMENTATION_CODE: -1\s*$' -and $out -match '(?m)^INSTRUMENTATION_RESULT: cleanupPending=false\s*$'
        $result.checks+=@{phase=$phase;passed=$ok;count=$count;logSha256=Hash "$run/$phase.log"};Save
        # Related failures accumulate in one batch, but never start another
        # phase while actual retirement/ownership remains unresolved.
        Idle
        $services=Adb @('shell','dumpsys','activity','services','org.mcrf.client')
        if($services -match 'ServiceRecord\{[^\r\n]*(?:ClientPrimary[A-Za-z0-9]*Worker[0-9]*Service|ClientCarrierService)'){$result.cleanupPending=$true;throw 'Actual private worker retirement pending; retain exact owner'}
        if($out -notmatch '(?m)^INSTRUMENTATION_RESULT: cleanupPending=false\s*$'){$result.cleanupPending=$true;throw 'Actual cleanup not proven; retain helper/job'}
    }
    $result.state=if(@($result.checks|Where-Object {!$_.passed}).Count){'BATCH_FAILED_RETAIN_PASSES'}else{'OWNED_BATCH_PASS_FULL_P2_OPEN'}
}catch{$result.state='FAILED';$result.error=$_.Exception.Message}
finally{
    if($claimed){
        try{Idle}catch{$result.cleanupPending=$true;$result.cleanupError=$_.Exception.Message}
        if(!$result.cleanupPending -and $result.installed){
            try{
                $remote=Adb @('shell','pm','path','org.mcrf.client.p2checks')
                if($remote -cnotmatch '^package:(/data/app/[A-Za-z0-9_~+=/.-]+/base\.apk)$'){throw 'Unexpected helper path'}
                $hash=Adb @('shell','sha256sum',$Matches[1]);if($hash -cnotmatch ('^'+$built.sha256+'\s')){throw 'Installed helper changed; no removal'}
                if((Adb @('uninstall','org.mcrf.client.p2checks')) -notmatch '(?m)^Success\s*$'){throw 'Own helper removal unconfirmed'};$result.removed=$true
            }catch{$result.cleanupPending=$true;$result.cleanupError=$_.Exception.Message}
        }
        if(!$result.cleanupPending){try{
            $after=Guard 'protected-after';$result.protectedUnchanged=$before -ceq $after
            $expected=$before
            if($accountHash){
                if($before -notmatch '(?m)^files/preferences\.enc=[a-f0-9]{64}\r?$'){throw 'Exact original preferences inventory required'}
                $expected=[regex]::Replace($before,'(?m)^files/preferences\.enc=[a-f0-9]{64}(\r?)$',('files/preferences.enc='+$accountHash+'$1'))
            }
            $result.protectedSafe=$expected -ceq $after
            if(!$result.protectedSafe){throw 'Unrelated protected state changed; no blind rollback'}
        }catch{$result.state='FAILED';$result.guardError=$_.Exception.Message}}
        Save
        if(!$result.cleanupPending){$current=Read "$coord/current.json";if($current.jobs.device -ceq $job){$current.jobs.device=$null;[IO.File]::WriteAllText("$coord/current.json",($current|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))}}
    }
    Save
}
Write-Output "P2_RUNTIME_RESULT=$run/receipt.json"
if($result.state -ne 'OWNED_BATCH_PASS_FULL_P2_OPEN' -or $result.cleanupPending -or !$result.protectedSafe){throw 'Owned runtime acceptance incomplete; retain exact successful phases and failures'}
