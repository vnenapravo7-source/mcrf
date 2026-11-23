param([Parameter(Mandatory)][string]$Receipt,[string]$PrivateSocket='',
    [ValidateSet('management-live','legacy-traffic-219','historical-traffic-215-rest','modern-traffic-25','deploy-accordion-ui','historical-traffic-20','historical-traffic-215','deploy-quick-ui-rest','deploy-quick-ui','historical-modules','deploy-browser-capture','csqtt-import-versions-rest','csqtt-import-versions','deploy-browser','deploy-call-controls','dropdown-regressions-rest','dropdown-regressions','active-idle-budget','latest-redesign','active-budget','aligned-capture','battery-design','notification-cadence','refresh-design-rest','refresh-design','remaining-ui','interval-timer','environment','route-live','route-steer','bs-live','bs-traffic','wdtt-traffic','hash-ui','hash-policy-rest','hash-help-rest','route-picker','late-ui','late-ui-rest','design-capture','menu-capture','style-final')][string[]]$Phases=@('refresh-design'))
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF';$coord="$root/ports/android/coordination"
$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe';$device='R5CX73QYN3D'
function Hash([string]$p){(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant()}
function Read([string]$p){Get-Content -LiteralPath $p -Raw|ConvertFrom-Json}
function Adb([string[]]$a){$out=(& $adb -s $device @a 2>&1|Out-String).Trim();if($LASTEXITCODE){throw "ADB failed: $out"};$out}
$built=Read $Receipt
if($built.state -cne 'COMPILED_DEVICE_PENDING' -or !$built.sourceStable -or !$built.mainAndModulesUnchanged -or (Hash $built.apk) -cne $built.sha256){throw 'Pinned completed new UI fixture required'}
$grantPath="$coord/p2-expansion-runtime-authority-2026-10-21.json";$grant=Read $grantPath;$grantHash=Hash $grantPath
if(!$grant.allowOwnedPrivateInstrumentation -or !$grant.allowTemporaryAppTestSettings -or !$grant.restoreOriginalSettings -or $grant.consumed -or $grant.stopForeignVpn -or $grant.allowRemoteVpsActions -or $grant.device -cne $device){throw 'Current expanded-P2 private UI authority required'}
$historicalTraffic=@($Phases|Where-Object {$_ -in @('historical-traffic-20','historical-traffic-215','historical-traffic-215-rest','modern-traffic-25','legacy-traffic-219','management-live')}).Count -gt 0
if($historicalTraffic){
    $modern=$Phases -contains 'modern-traffic-25'
    $legacy=$Phases -contains 'legacy-traffic-219';$management=$Phases -contains 'management-live'
    $historicalGrant=Read $(if($management){"$coord/p2-management-live-authority-2026-10-22.json"}elseif($legacy){"$coord/p2-legacy-traffic-authority-2026-10-22.json"}elseif($modern){"$coord/p2-modern-traffic-authority-2026-10-22.json"}else{"$coord/p2-historical-traffic-authority-2026-10-22.json"})
    $expectedPorts=if($management){'18445'}elseif($legacy){'18605'}elseif($modern){'18607'}else{'18720,18721'}
    if($Phases.Count -ne 1 -or $PrivateSocket -cnotmatch '^mcrf-historical-[a-f0-9]{32}$' -or !$historicalGrant.allowOwnedHistoricalTraffic -or !$historicalGrant.restoreOriginalSettings -or $historicalGrant.stopForeignVpn -or $historicalGrant.allowProviderRegistration -or $historicalGrant.allowPanelMutation -ne $management -or $historicalGrant.host -cne '144.31.159.50' -or ($historicalGrant.ports -join ',') -cne $expectedPorts -or $historicalGrant.device -cne $device -or $historicalGrant.owner -cne $grant.owner -or $historicalGrant.commonReceipt -cne $grant.commonReceipt){throw 'Separate exact generation/management authority/private pipe required'}
}elseif($PrivateSocket){throw 'Private historical pipe cannot be used in other phases'}
if('environment' -in $Phases -and (!$grant.allowWifiCycle -or !$grant.allowScreenOffTest)){throw 'Explicit Wi-Fi/screen authority required'}
if('notification-cadence' -in $Phases -and !$grant.allowScreenOffTest){throw 'Explicit screen authority required'}
if('active-budget' -in $Phases -and (!$grant.allowScreenOffTest -or !$grant.allowOwnedTestVpn)){throw 'Explicit screen/owned VPN authority required'}
if('active-idle-budget' -in $Phases -and (!$grant.allowScreenOffTest -or !$grant.allowOwnedTestVpn)){throw 'Explicit screen/owned VPN authority required'}
if(@($Phases|Where-Object {$_ -in @('route-live','route-steer','bs-live','bs-traffic','wdtt-traffic')}).Count -and !$grant.allowOwnedTestVpn){throw 'Owned test VPN authority required'}
if('route-steer' -in $Phases -and !$grant.allowSteerDnsModelAcceptance){throw 'Explicit Steer DNS consent authority required'}
$job="ExpandedP2Ui:$([guid]::NewGuid().ToString('N'))";$installed=$false;$claimed=$false;$stay=$null;$wifiOriginal=$null;$wifiEnabled=$null;$environmentStarted=$false
function Idle{
    $current=Read "$coord/current.json";$lease=Read "$coord/device-lease.json"
    if((Hash $grantPath) -cne $grantHash -or $lease.device -cne $device -or $lease.owner_thread -cne $grant.owner -or $current.activeBatch -cne 'p2-expansion-2026-10-21.json' -or $current.jobs.build -or $current.jobs.hostTest -or $current.jobs.emulator -or ($current.jobs.device -and $current.jobs.device -cne $job)){throw 'Exclusive current device lease required'}
    if((Adb @('shell','getprop','ro.product.model')) -cne 'SM-F741B' -or (Adb @('shell','am','get-current-user')) -cne '0'){throw 'Wrong phone/user'}
    if((Adb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (Adb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Awake unlocked phone required'}
    $vpn=Adb @('shell','dumpsys','vpn_management');if($vpn -notmatch 'Active vpn type:' -or $vpn -match 'Active vpn type: [01]\b'){throw 'User VPN active; no test or stop'}
    foreach($package in @('org.mcrf.client','org.steer.prototype','com.wdtt.plus','csqtt.quic.amurcanov')){if((Adb @('shell','dumpsys','activity','services',$package)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'User service active; do not interrupt'}}
}
Idle
$common=Read "$($built.common)/p2-expansion-artifacts.json"
$main=@($common.artifacts|Where-Object {$_.path -match 'app-universal-debug.apk$'})
$paths=Adb @('shell','pm','path','--user','0','org.mcrf.client')
if($main.Count -ne 1 -or $paths -cnotmatch '^package:(/data/app/(?:[A-Za-z0-9_~+=-]+/)?org\.mcrf\.client-[A-Za-z0-9_~+=-]+/base\.apk)$'){throw 'Exact installed common APK required'}
$sum=Adb @('shell','sha256sum',$Matches[1]);if($sum -cnotmatch '^([a-f0-9]{64})\s' -or $Matches[1] -cne $main[0].sha256){throw 'Installed main differs from accepted10026'}
if((Adb @('shell','pm','list','packages','--user','0','org.mcrf.client.expandedui'))){throw 'Existing unknown UI helper; never overwrite'}
$stage="$(Split-Path $Receipt)/run-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $stage|Out-Null
$result=[ordered]@{state='PREPARING';build=$Receipt;buildSha256=Hash $Receipt;checks=0;settingsRestored=$false;protectedUnchanged=$false;removed=$false;published=$false}
function Save{[IO.File]::WriteAllText("$stage/receipt.json",($result|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))}
function Guard([string]$name){
    $out=Adb @('shell','am','instrument','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation')
    $out=[regex]::Replace($out,"\r+\n","`n");[IO.File]::WriteAllText("$stage/$name.log",$out,[Text.UTF8Encoding]::new($false))
    $m=[regex]::Match($out,'(?s)INSTRUMENTATION_RESULT: protectedHashes=(.*?)\n\n')
    if(!$m.Success -or $out -notmatch 'passed=true' -or $out -notmatch 'stable=true'){throw 'Guardian failed'}
    $m.Groups[1].Value.Trim()
}
Save;Write-Output "P2_UI_RUN=$stage"
try{
    $current=Read "$coord/current.json";$current.jobs.device=$job;[IO.File]::WriteAllText("$coord/current.json",($current|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false));$claimed=$true
    $before=Guard 'protected-before'
    $stay=Adb @('shell','settings','get','global','stay_on_while_plugged_in');if($stay -cnotmatch '^[0-7]$'){throw 'Cannot preserve screen setting'}
    $null=Adb @('shell','settings','put','global','stay_on_while_plugged_in','3')
    Idle
    if((Hash $built.apk) -cne $built.sha256){throw 'Runner changed'}
    $out=Adb @('install','-t',$built.apk);if($out -notmatch '(?m)^Success\s*$'){throw 'UI helper installation failed'};$installed=$true
    $result.phases=@();$failures=@()
    foreach($phase in ($Phases | Select-Object -Unique)){
        Idle
        $expected=if($phase -ceq 'battery-design'){7}elseif($phase -ceq 'notification-cadence'){5}elseif($phase -ceq 'refresh-design-rest'){11}elseif($phase -ceq 'refresh-design'){13}elseif($phase -ceq 'hash-help-rest'){1}elseif($phase -ceq 'hash-policy-rest'){2}elseif($phase -ceq 'route-picker'){6}elseif($phase -ceq 'hash-ui'){8}elseif($phase -ceq 'wdtt-traffic'){4}elseif($phase -ceq 'style-final'){2}elseif($phase -ceq 'bs-traffic'){3}elseif($phase -in @('design-capture','menu-capture')){0}elseif($phase -ceq 'late-ui-rest'){4}elseif($phase -ceq 'late-ui'){8}elseif($phase -ceq 'remaining-ui'){11}elseif($phase -ceq 'interval-timer'){5}elseif($phase -in @('route-live','route-steer')){9}elseif($phase -ceq 'bs-live'){5}else{6}
        if($phase -in @('active-budget','active-idle-budget','deploy-call-controls')){$expected=2};if($phase -ceq 'aligned-capture'){$expected=0};if($phase -ceq 'latest-redesign'){$expected=11};if($phase -ceq 'dropdown-regressions'){$expected=7};if($phase -ceq 'dropdown-regressions-rest'){$expected=2}
        if($phase -ceq 'csqtt-import-versions'){$expected=4}
        if($phase -ceq 'csqtt-import-versions-rest'){$expected=2}
        if($phase -ceq 'deploy-browser-capture'){$expected=0}
        if($phase -ceq 'historical-modules'){$expected=4}
        if($phase -ceq 'deploy-quick-ui'){$expected=9}
        if($phase -ceq 'deploy-quick-ui-rest'){$expected=7}
        if($phase -ceq 'deploy-accordion-ui'){$expected=7}
        if($phase -in @('historical-traffic-20','historical-traffic-215','modern-traffic-25')){$expected=4}
        if($phase -ceq 'historical-traffic-215-rest'){$expected=3}
        if($phase -ceq 'legacy-traffic-219'){$expected=4}
        if($phase -ceq 'management-live'){$expected=5}
        $argsList=@('-s',$device,'shell','am','instrument','-w','-r','-e','phase',$phase)
        if($historicalTraffic){$argsList+=@('-e','privateSocket',$PrivateSocket)}
        if($phase -in @('route-live','route-steer','bs-live','bs-traffic','wdtt-traffic') -and $grant.allowSystemVpnConsent){$argsList+=@('-e','allowVpnConsent','true')}
        if($phase -ceq 'route-steer'){$argsList+=@('-e','allowNetworkScreen','true')}
        if($phase -ceq 'notification-cadence'){$environmentStarted=$true;$argsList+=@('-e','allowNetworkScreen','true')}
        if($phase -in @('active-budget','active-idle-budget')){$environmentStarted=$true;$argsList+=@('-e','allowNetworkScreen','true');if($grant.allowSystemVpnConsent){$argsList+=@('-e','allowVpnConsent','true')}}
        if($phase -ceq 'environment'){
            $wifiOriginal=Adb @('shell','settings','get','global','wifi_on')
            if($wifiOriginal -cnotmatch '^[0-3]$'){throw 'Unknown original Wi-Fi state'}
            $wifiStatus=(Adb @('shell','cmd','wifi','status')) -split "`n" | Select-Object -First 1
            if($wifiStatus.Trim() -cnotmatch '^Wifi is (enabled|disabled)$'){throw 'Unknown physical Wi-Fi state'}
            $wifiEnabled=$Matches[1] -ceq 'enabled'
            $environmentStarted=$true;$argsList+=@('-e','allowNetworkScreen','true')
        }
        $argsList+='org.mcrf.client.expandedui/org.mcrf.expandedui.ExpandedUiInstrumentation'
        $stdout="$stage/$phase.log";$stderr="$stage/$phase.stderr.log"
        $process=Start-Process -FilePath $adb -ArgumentList $argsList -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        $handled=@{};$deadline=[DateTime]::UtcNow.AddSeconds($(if($historicalTraffic -or $phase -in @('route-live','route-steer','bs-live','bs-traffic','wdtt-traffic')){360}else{150}))
        while(!$process.HasExited -and [DateTime]::UtcNow -lt $deadline){
            $live=if(Test-Path -LiteralPath $stdout){Get-Content -LiteralPath $stdout -Raw}else{''}
            foreach($marker in @('WIFI_OFF_WAIT','WIFI_ON_WAIT','SCREEN_OFF_WAIT','SCREEN_ON_WAIT')){
                if($phase -in @('environment','notification-cadence','active-budget','active-idle-budget') -and !$handled.ContainsKey($marker) -and $live -match "INSTRUMENTATION_STATUS: stage=$marker"){
                    $handled[$marker]=$true
                    switch($marker){
                        'WIFI_OFF_WAIT' {$null=Adb @('shell','svc','wifi','disable')}
                        'WIFI_ON_WAIT' {$null=Adb @('shell','svc','wifi','enable')}
                        'SCREEN_OFF_WAIT' {$null=Adb @('shell','input','keyevent','223')}
                        'SCREEN_ON_WAIT' {$null=Adb @('shell','input','keyevent','224')}
                    }
                    Write-Output "P2_UI_STAGE=$marker"
                }
            }
            Start-Sleep -Milliseconds 250;$process.Refresh()
        }
        if(!$process.HasExited){throw 'Instrumentation exceeded bounded deadline; retain running ownership for investigation'}
        $process.WaitForExit();$out=Get-Content -LiteralPath $stdout -Raw
        foreach($shot in [regex]::Matches($out,'(?m)(?:^|report=)SCREENSHOT (/data/user/0/org\.mcrf\.client/cache/(p2-[a-z-]+\.png))\s*$')){
            $capture=[Diagnostics.Process]::new();$capture.StartInfo.FileName=$adb
            $capture.StartInfo.Arguments="-s $device exec-out run-as org.mcrf.client cat $($shot.Groups[1].Value)"
            $capture.StartInfo.UseShellExecute=$false;$capture.StartInfo.CreateNoWindow=$true;$capture.StartInfo.RedirectStandardOutput=$true
            $stream=[IO.File]::Create((Join-Path $stage $shot.Groups[2].Value))
            try{$null=$capture.Start();$capture.StandardOutput.BaseStream.CopyTo($stream);$capture.WaitForExit();if($capture.ExitCode){throw 'Screenshot capture failed'}}finally{$stream.Dispose();$capture.Dispose()}
            $null=Adb @('shell','run-as','org.mcrf.client','rm',$shot.Groups[1].Value)
        }
        if($phase -ceq 'environment'){
            $null=Adb @('shell','svc','wifi',$(if($wifiEnabled){'enable'}else{'disable'}))
            if((Adb @('shell','settings','get','global','wifi_on')) -cne $wifiOriginal){throw 'Original Wi-Fi setting not restored'}
            $null=Adb @('shell','input','keyevent','224');$environmentStarted=$false
        }
        if($phase -ceq 'notification-cadence'){$null=Adb @('shell','input','keyevent','224');$environmentStarted=$false}
        if($phase -in @('active-budget','active-idle-budget')){$null=Adb @('shell','input','keyevent','224');$environmentStarted=$false}
        $match=[regex]::Match($out,'INSTRUMENTATION_RESULT: passed=([0-9]+)');$count=if($match.Success){[int]$match.Groups[1].Value}else{0}
        $restored=$out -match 'INSTRUMENTATION_RESULT: settingsRestored=true'
        $unchanged=(Guard "protected-after-$phase") -ceq $before
        $phasePass=$count -eq $expected -and $restored -and $unchanged -and $out -match 'INSTRUMENTATION_CODE: -1' -and $out -notmatch '(?m)^FAIL |CLEANUP FAIL|RESTORE FAIL'
        $result.phases+=@{phase=$phase;checks=$count;expected=$expected;passed=$phasePass;settingsRestored=$restored;protectedUnchanged=$unchanged}
        $result.checks+=$count;$result.settingsRestored=$restored;$result.protectedUnchanged=$unchanged;Save
        if(!$restored -or !$unchanged){throw 'Unsafe restoration boundary; stop further phases'}
        if(!$phasePass){$failures+=$phase}
    }
    if($failures.Count){throw "New phases failed: $($failures -join ', '); retained passes must not be replayed"}
    $result.state='PASS'
}catch{$result.state='FAILED_PRESERVED';$result.error=$_.Exception.Message;throw}
finally{
    if($environmentStarted){
        if($null -ne $wifiEnabled){$null=Adb @('shell','svc','wifi',$(if($wifiEnabled){'enable'}else{'disable'}))}
        $null=Adb @('shell','input','keyevent','224')
    }
    if($installed){$out=Adb @('uninstall','org.mcrf.client.expandedui');$result.removed=$out -match '^Success'}
    if($null -ne $stay -and (Adb @('shell','settings','get','global','stay_on_while_plugged_in')) -ceq '3'){$null=Adb @('shell','settings','put','global','stay_on_while_plugged_in',$stay)}
    if($claimed){$current=Read "$coord/current.json";if($current.jobs.device -ceq $job){$current.jobs.device=$null;[IO.File]::WriteAllText("$coord/current.json",($current|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))}}
    Save;Write-Output "P2_UI_STATE=$($result.state)"
}
