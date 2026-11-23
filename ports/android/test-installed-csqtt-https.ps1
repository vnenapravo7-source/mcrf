param([Parameter(Mandatory=$true)][string]$InstallReceipt,[ValidateSet('standard','tls-diagnostic','ipv6-diagnostic')][string]$Mode='standard',[switch]$RequireSessionLabel,[switch]$CaptureSession,[switch]$StopFromVpnSection,[switch]$TemporaryExistingCs188,[switch]$UndoOwnedCs188Mark,[switch]$CompensateOwnedMarkOnly,[switch]$FreshR10ManualConsent)
$ErrorActionPreference='Stop'
if($args.Count){throw 'Unknown HTTPS checker arguments; no device action'}
if($UndoOwnedCs188Mark -or $CompensateOwnedMarkOnly -or $TemporaryExistingCs188 -and !$FreshR10ManualConsent){throw 'Temporary selection/032afc compensation is complete and retired. This checker now preserves the existing manual selection only; never reapply to later human choices.'}
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$path=[IO.Path]::GetFullPath($InstallReceipt)
if(!$path.StartsWith("$root\work\ports-test-2026-10-14\",[StringComparison]::OrdinalIgnoreCase)){throw 'Owned installation receipt required'}
$install=Get-Content $path -Raw|ConvertFrom-Json
if(!$install.passed -or !$install.protectedUnchanged){throw 'Verified installed app required'}
$device=$install.device
$r10ConsentPath=Join-Path $PSScriptRoot 'coordination/p0-r10-manual-test-consent.json'
$script:r10ConsumedHere=$false
if($FreshR10ManualConsent){
    $r10Consent=Get-Content -LiteralPath $r10ConsentPath -Raw|ConvertFrom-Json
    if(!$TemporaryExistingCs188 -or $Mode -cne 'standard' -or $r10Consent.used -or $r10Consent.device -cne $device -or $r10Consent.mainSha -cne $install.sha256){throw 'Fresh one-shot R10 manual test authority does not match'}
}
function ConsumeR10Consent {
    if(!$FreshR10ManualConsent -or $script:r10ConsumedHere){return}
    $ticket=Get-Content -LiteralPath $r10ConsentPath -Raw|ConvertFrom-Json
    if($ticket.used){throw 'Fresh R10 authority already consumed; no retry'}
    $ticket.used=$true
    $ticket|Add-Member -NotePropertyName evidence -NotePropertyValue $evidence
    $ticket|Add-Member -NotePropertyName consumedUtc -NotePropertyValue ([DateTime]::UtcNow.ToString('o'))
    $ticket|ConvertTo-Json -Depth 4|Set-Content -LiteralPath $r10ConsentPath -Encoding utf8
    $script:r10ConsumedHere=$true
}
$lease=Get-Content "$PSScriptRoot/coordination/device-lease.json" -Raw|ConvertFrom-Json
if($lease.device -cne $device -or $lease.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c'){throw 'Main sole lease required'}
$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
function Adb([string[]]$argv){$output=(& $adb -s $device @argv 2>&1|Out-String).Trim();if($LASTEXITCODE -ne 0){throw 'ADB command failed; no retry or service stop'};return $output}
function WriteEvidence([string]$name,$value){[IO.File]::WriteAllText((Join-Path $evidence $name),($value|ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))}
function Guard([bool]$allowIdleLauncher=$false) {
    if((Adb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (Adb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'No wake/unlock'}
    foreach($pkg in @('com.wdtt.plus','csqtt.quic.amurcanov')){if((Adb @('shell','dumpsys','activity','services',$pkg)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'User VPN active; no interference'}}
    $top=Adb @('shell','dumpsys','activity','activities')
    $ours=$top -match '(?m)^\s+topResumedActivity=.*org\.mcrf\.client/'
    $idleLauncher=$allowIdleLauncher -and $top -match '(?m)^\s+topResumedActivity=.*com\.sec\.android\.app\.launcher/'
    if(!$ours -and !$idleLauncher){throw 'Human foreground outside MCRF/authorized idle launcher; no interference'}
}
function VpnState {
    # A cached NetworkCapabilities VPN entry survives STOP. Use actual current-owner
    # active type (idle=-1), and accept VPN anywhere in the active agent transport set.
    $dump=Adb @('shell','dumpsys','vpn_management')
    $owner=[regex]::Match($dump,'(?ms)^  0: ([^\r\n]+)\r?\n(.*?)(?=^  \d+:|\z)')
    $type=if($owner.Success){[regex]::Match($owner.Groups[2].Value,'(?m)^\s+Active vpn type: (-?\d+)\s*$')}else{$null}
    $activeType=if($type -and $type.Success){[int]$type.Groups[1].Value}else{-1}
    $agent=(Adb @('shell','dumpsys','connectivity')) -match '(?m)^\s+NetworkAgentInfo\{[^\r\n]*\bTransports:\s+[A-Z_|]*\bVPN\b'
    return [pscustomobject]@{active=($activeType -ge 0 -or $agent);ownerPackage=if($owner.Success){$owner.Groups[1].Value.Trim()}else{''};activeType=$activeType;activeAgent=$agent}
}
function VpnActive {return (VpnState).active}
function Idle {
    Guard $true
    if((Adb @('shell','dumpsys','activity','services','org.mcrf.client')) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]' -or (VpnActive)){throw 'Existing app service or VPN; no stop'}
}
function Ui {
    # A failed idle wait can return exit0 and leave an OLD XML in place.
    # Never derive tap targets from that prior screen.
    Adb @('shell','rm','-f',$remote)|Out-Null
    $dump=Adb @('shell','uiautomator','dump',$remote)
    if($dump -notmatch 'UI hierchary dumped to:'){throw 'Fresh UI dump unavailable; no stale XML action'}
    [xml]$doc=Adb @('shell','cat',$remote)
    if(@($doc.SelectNodes('//node')|Where-Object {$_.package -and $_.package -ne 'org.mcrf.client' -and $_.package -ne 'com.android.systemui'}).Count){throw 'External confirmation/window; no bypass'}
    return $doc
}
function Tap($node) {
    Guard
    if(!$node){throw 'Missing observed control'}
    # Revalidate the observed control after asynchronous page/loading/dialog
    # changes. Never reuse coordinates from a view replaced since the dump.
    $fresh=Ui
    $same=@($fresh.SelectNodes('//node')|Where-Object {$_.class -ceq $node.class -and $_.text -ceq $node.text -and $_.'content-desc' -ceq $node.'content-desc' -and $_.checked -ceq $node.checked -and $_.selected -ceq $node.selected})
    if($same.Count -gt 1){$same=@($same|Where-Object {$_.bounds -ceq $node.bounds})}
    if($same.Count -ne 1){throw 'Observed control changed before tap; no stale-coordinate action'}
    $node=$same[0]
    if(!$node -or $node.enabled -eq 'false' -or $node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$'){throw 'Missing/disabled observed control'}
    $x=[int]( ([int]$Matches[1]+[int]$Matches[3])/2 );$y=[int]( ([int]$Matches[2]+[int]$Matches[4])/2 )
    Adb @('shell','input','tap',"$x","$y")|Out-Null
}
function ScrollUi($doc,[bool]$towardTop) {
    Guard
    $scroll=@($doc.SelectNodes('//node')|Where-Object {$_.scrollable -ceq 'true'})
    # Spinners now expose scrollable=true too; they are not the page viewport.
    $pageScroll=@($scroll|Where-Object {$_.class -ceq 'android.widget.ScrollView'})
    if($pageScroll.Count -eq 1){$scroll=$pageScroll}
    if($scroll.Count -ne 1 -or $scroll[0].bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$'){throw 'Observed scroll viewport required'}
    $x=[int](([int]$Matches[1]+[int]$Matches[3])/2);$top=[int]$Matches[2]+150;$bottom=[int]$Matches[4]-150
    if($towardTop){Adb @('shell','input','swipe',"$x","$top","$x","$bottom",'450')|Out-Null}
    else{Adb @('shell','input','swipe',"$x","$bottom","$x","$top",'450')|Out-Null}
    return Ui
}
function Cs188Mark($doc,$row){
    if(!$row -or $row.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$'){return @()}
    $left=[int]$Matches[1];$top=[int]$Matches[2];$right=[int]$Matches[3];$bottom=[int]$Matches[4]
    # Accessibility may promote a row's CheckBox to a sibling node. Match the
    # exact observed row rectangle, not an assumed XML parent relationship.
    return @($doc.SelectNodes('//node')|Where-Object {
        $_.class -ceq 'android.widget.CheckBox' -and $_.'content-desc' -ceq 'Использовать CSQTT в автовыборе' -and
        $_.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$' -and
        [int]$Matches[1] -ge $left -and [int]$Matches[2] -ge $top -and [int]$Matches[3] -le $right -and [int]$Matches[4] -le $bottom
    })
}
function Hashes {
    if($install.guardComponent -ceq 'org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation'){
        $out=Adb @('shell','am','instrument','-w','-r',$install.guardComponent)
        $match=[regex]::Match($out,'(?s)INSTRUMENTATION_RESULT: protectedHashes=(.*?)\r?\n\r?\n')
        if(!$match.Success -or $out -notmatch 'INSTRUMENTATION_RESULT: passed=true' -or $out -notmatch 'INSTRUMENTATION_RESULT: stable=true'){throw 'Complete P0 protected checkpoint failed'}
        return $match.Groups[1].Value.Trim()
    }
    $out=Adb @('shell','am','instrument','-w','-r','-e','mode','protected-hashes','org.mcrf.client.test/org.mcrf.client.ReleaseSmokeInstrumentation')
    $match=[regex]::Match($out,'(?s)INSTRUMENTATION_RESULT: protectedFilesBefore=(.*?)\r?\n\r?\n')
    if(!$match.Success -or $out -notmatch 'INSTRUMENTATION_RESULT: passed=true' -or $out -notmatch 'INSTRUMENTATION_RESULT: protectedFilesUnchanged=true'){throw 'Fresh protected hash checkpoint failed'}
    return $match.Groups[1].Value.Trim()
}
function WaitOwnedMeasurementsIdle {
    $wait=[Diagnostics.Stopwatch]::StartNew()
    do {
        Guard
        if(VpnActive){throw 'VPN became active during restoration; no stop or checkpoint'}
        $services=Adb @('shell','dumpsys','activity','services','org.mcrf.client')
        $idle=$services -notmatch 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'
        if(!$idle){Start-Sleep -Milliseconds 500}
    }while(!$idle -and $wait.Elapsed.TotalSeconds -lt 100)
    WriteEvidence 'restoration-probe-idle.json' @{idle=$idle;elapsedSeconds=[Math]::Round($wait.Elapsed.TotalSeconds,2);forcedStop=$false}
    return $idle
}
Idle
if((Adb @('shell','am','get-current-user')) -cne '0'){throw 'Authorized owner user0 only; no user switching'}
$expected=@($install.installed|Where-Object {$_.package -ceq 'org.mcrf.client'})
$apk=Adb @('shell','pm','path','--user','0','org.mcrf.client')
if($expected.Count -ne 1 -or $apk -notmatch '^package:(/data/app/[^\r\n]+/base.apk)$'){throw 'Exact installed APK required'}
if(!(Adb @('shell','sha256sum',$Matches[1])).StartsWith($expected[0].sha256+'  ',[StringComparison]::Ordinal)){throw 'App changed'}
$id=[guid]::NewGuid().ToString('N')
$evidence=Join-Path $root "work/ports-test-2026-10-14/csqtt-installed-https-$id"
New-Item -ItemType Directory -Path $evidence|Out-Null
$remote="/data/local/tmp/mcrf-owned-hierarchy-$id.xml"
$remoteScreenshot="/data/local/tmp/mcrf-owned-session-$id.png"
$started=$false;$cleaned=$false;$before=$null;$after=$null;$connected=$false;$elapsed=$null;$https=@();$errorCode=$null;$phase='protected-before'
$restoreAutomatic=$false;$selectionRestored=$null
$restorationProbeIdle=$null
$restoreManualAfterCompensation=$false;$manualRestored=$null;$markCompensated=$null
try {
    $before=Hashes;WriteEvidence 'protected-before.json' $before
    $phase='open-existing-app'
    Adb @('shell','am','start','-W','-n','org.mcrf.client/org.mcrf.client.MainActivity')|Out-Null
    $ready=[Diagnostics.Stopwatch]::StartNew();$doc=$null
    do {
        $doc=Ui
        # A new host APK may legitimately offer engine updates at cold start.
        # Defer only this exact own discovery dialog; NEVER accept an installer,
        # permission request, CAPTCHA, or unrelated/human settings dialog.
        $updateTitle=@($doc.SelectNodes('//node')|Where-Object {$_.text -ceq 'Обновления движков'})
        if($updateTitle.Count -eq 1){
            $later=@($doc.SelectNodes('//node')|Where-Object {$_.text -ceq 'Позже' -and $_.clickable -ceq 'true'})
            if($later.Count -ne 1){throw 'Exact own update deferral control missing'}
            Tap $later[0]
            WriteEvidence 'startup-update-deferred.json' @{ownUpdateOfferObserved=$true;downloadOrInstallApproved=$false;profilesOrModulesChanged=$false}
            $doc=Ui
        }
        $nav=@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -ceq 'Белые списки'})
        # Navigation exists in the loading shell before encrypted stores load.
        # A real automatic-mode control proves the catalogue page is rendered.
        $loaded=@($doc.SelectNodes('//node')|Where-Object {$_.text -ceq 'Автовыбор' -and $_.class -ceq 'android.widget.Switch'}).Count -eq 1
        if($nav.Count -eq 1 -and $loaded){break};Start-Sleep -Milliseconds 250
    }while($ready.Elapsed.TotalSeconds -lt 15)
    if($nav.Count -ne 1 -or !$loaded){throw 'Whitelists navigation or loaded catalogue unavailable'}
    Tap $nav[0];$doc=Ui
    $sectionReady=[Diagnostics.Stopwatch]::StartNew()
    while(@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -ceq 'Белые списки' -and $_.selected -ceq 'true'}).Count -ne 1 -and $sectionReady.Elapsed.TotalSeconds -lt 10){Start-Sleep -Milliseconds 200;$doc=Ui}
    if(@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -ceq 'Белые списки' -and $_.selected -ceq 'true'}).Count -ne 1){throw 'Whitelists page transition not confirmed'}
    $phase='verify-existing-selection'
    for($attempt=0;$attempt -lt 6;$attempt++){
        $chosen=@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -match '^CSQTT, CSQTT, 188\.253\.24\.174, .*выбран$'})
        if($chosen.Count -eq 1){break};if($attempt -lt 5){$doc=ScrollUi $doc $false}
    }
    WriteEvidence 'selection-gate.json' @{chosenMatches=$chosen.Count;navigation=@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -in @('VPN','Белые списки') -and $_.class -ceq 'android.widget.Button'}|ForEach-Object {@{section=$_.'content-desc';selected=$_.selected}});automaticControls=@($doc.SelectNodes('//node')|Where-Object {$_.text -ceq 'Автовыбор'}|ForEach-Object {@{checked=$_.checked;class=$_.class}});expectedServerNodes=@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -match '^CSQTT, CSQTT, 188\.253\.24\.174,'}|ForEach-Object {@{class=$_.class;chosenSuffix=($_.'content-desc' -match 'выбран$');selected=$_.selected}})}
    if($chosen.Count -ne 1 -and $CaptureSession){Guard;Adb @('shell','screencap','-p',$remoteScreenshot)|Out-Null;Adb @('pull',$remoteScreenshot,(Join-Path $evidence 'selection-gate.png'))|Out-Null}
    if($chosen.Count -ne 1){throw 'Approved existing CS188 must already be selected; no selection/import change'}
    $auto=@($doc.SelectNodes('//node')|Where-Object {$_.text -ceq 'Автовыбор' -and $_.class -ceq 'android.widget.Switch'})
    if($auto.Count -ne 1){throw 'Observed automatic mode required before any temporary selection'}
    if($CompensateOwnedMarkOnly){
        if(!$UndoOwnedCs188Mark -or !$TemporaryExistingCs188){throw 'Exact owned compensation flags required'}
        if($auto[0].checked -ceq 'false'){
            Idle;$restoreManualAfterCompensation=$true
            WriteEvidence 'compensation-current-mode.json' @{automatic=$false;selectedExistingCs188=$true;restoreLatestManualMode=$true;reason='Only reveal the hidden checkbox for exact owned compensation; normal automatic enable may start measurements'}
            Tap $auto[0];$doc=Ui
            for($attempt=0;$attempt -lt 6;$attempt++){
                $chosen=@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -match '^CSQTT, CSQTT, 188\.253\.24\.174, .*выбран$'})
                if($chosen.Count -eq 1){break};$doc=ScrollUi $doc $false
            }
            $auto=@($doc.SelectNodes('//node')|Where-Object {$_.text -ceq 'Автовыбор' -and $_.class -ceq 'android.widget.Switch'})
            if($chosen.Count -ne 1 -or $auto.Count -ne 1){throw 'Owned compensation controls unavailable'}
        }
    }
    if($UndoOwnedCs188Mark){
        if(!$TemporaryExistingCs188 -or $auto[0].checked -cne 'true'){throw 'Owned checkbox correction requires original automatic mode'}
        # Exact compensation for the preceding owned 032afc pre-start tap only.
        # Before that tap both observed checkboxes were false. In auto mode a row
        # tap marks the pool, not manual selection. Never reuse this for human edits.
        $prior=Get-Content (Join-Path $root 'work/ports-test-2026-10-14/csqtt-installed-https-032afc0cec1a4bab8d0c8efbae0e8428/receipt.json') -Raw|ConvertFrom-Json
        if($prior.ownedStartIssued -or !$prior.originalSelectionAndAutomaticRestored -or $prior.device -cne $device){throw 'Exact owned pre-start correction receipt required'}
        $mark=@(Cs188Mark $doc $chosen[0])
        WriteEvidence 'compensation-mark-observed.json' @{inObservedCs188Row=$mark.Count;checked=@($mark|ForEach-Object {$_.checked});otherMarksNotSelectedByLabelAlone=$true}
        if($mark.Count -ne 1 -or $mark[0].checked -notin @('true','false')){throw 'Owned temporary mark no longer matches; no compensation'}
        $alreadyOriginal=$mark[0].checked -ceq 'false'
        if(!$alreadyOriginal){Tap $mark[0];$doc=Ui}
        $chosen=@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -match '^CSQTT, CSQTT, 188\.253\.24\.174, .*выбран$'})
        $mark=if($chosen.Count -eq 1){@(Cs188Mark $doc $chosen[0])}else{@()}
        if($mark.Count -ne 1 -or $mark[0].checked -cne 'false'){throw 'Owned temporary mark restoration not confirmed'}
        $markCompensated=$true
        WriteEvidence 'owned-mark-compensated.json' @{originalCs188Mark=$false;restored=$true;alreadyOriginalBeforeAction=$alreadyOriginal;source='032afc pre-start owned row tap; original observed false';otherMarksChanged=$false}
        $auto=@($doc.SelectNodes('//node')|Where-Object {$_.text -ceq 'Автовыбор' -and $_.class -ceq 'android.widget.Switch'})
    }
    if($CompensateOwnedMarkOnly){return}
    if($auto[0].checked -ceq 'true'){
        if(!$TemporaryExistingCs188){throw 'Explicit existing manual server required; no pool change'}
        # Human explicitly authorized temporary CS188 selection and return. This
        # narrow path only handles an already-selected CS188 with automation on:
        # never invent an unknown original server or patch private preferences.
        WriteEvidence 'temporary-selection-before.json' @{originalServer='existing CS188';automatic=$true;restoration='normal switch; may refresh ordinary probe metadata';catalogueEditingApproved=$false}
        ConsumeR10Consent
        $restoreAutomatic=$true
        # In automatic mode a row tap edits pool membership. Disable automation
        # with its actual switch; CS188 is ALREADY the selected manual server.
        Tap $auto[0];$doc=Ui
        $manual=@($doc.SelectNodes('//node')|Where-Object {$_.text -ceq 'Автовыбор' -and $_.class -ceq 'android.widget.Switch' -and $_.checked -ceq 'false'})
        if($manual.Count -ne 1){throw 'Temporary manual selection not confirmed'}
    }
    $button=@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -cmatch '^Подключить(?: · Отключено)?$'})
    $phase='locate-primary-control'
    for($attempt=0;$button.Count -ne 1 -and $attempt -lt 3;$attempt++){
        $doc=ScrollUi $doc $true;$button=@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -cmatch '^Подключить(?: · Отключено)?$'})
    }
    if($button.Count -ne 1){throw 'Observed primary connection control required'}
    $phase='normal-connect';ConsumeR10Consent;$timer=[Diagnostics.Stopwatch]::StartNew();Tap $button[0];$started=$true
    while($timer.Elapsed.TotalSeconds -lt 100){
        Guard
        $vpn=VpnState
        if($vpn.active){if($vpn.ownerPackage -cne 'org.mcrf.client' -or $vpn.activeType -ne 1){throw 'Unexpected VPN owner/type; no interference'};$connected=$true;$elapsed=[Math]::Round($timer.Elapsed.TotalSeconds,2);WriteEvidence 'vpn-state.json' $vpn;break}
        $doc=Ui
        $observed=@($doc.SelectNodes('//node')|Where-Object {$_.text -match '^VPN подключён$|^Прокси подключён$|^Подключение…$|^Ошибка подключения$|^Отключено$'}|ForEach-Object {$_.text})
        WriteEvidence 'ui-state.json' ([ordered]@{phase=$phase;elapsedSeconds=[Math]::Round($timer.Elapsed.TotalSeconds,2);status=$observed})
        if(@($doc.SelectNodes('//node')|Where-Object {$_.text -match '^Ошибка подключения$|^Нужен совместимый|^Через что отправлять|^Для подключения VK просит|^Подтверждение VK$'}).Count){throw 'Connection needs user/provider action or failed; no automatic retry'}
        Start-Sleep -Milliseconds 500
    }
    if(!$connected){throw 'No established Android VPN within bounded test'}
    WriteEvidence 'connected.json' ([ordered]@{snapshot=$install.snapshot;systemVpn=$connected;elapsedSeconds=$elapsed;source='normal existing CS188 UI connection; no import or catalogue edit';temporaryManualSelection=$restoreAutomatic})
    if($CaptureSession){
        $phase='owned-session-screenshot';Start-Sleep -Milliseconds 800;Guard
        Adb @('shell','screencap','-p',$remoteScreenshot)|Out-Null
        Adb @('pull',$remoteScreenshot,(Join-Path $evidence 'connected.png'))|Out-Null
        WriteEvidence 'session-screenshot.json' ([ordered]@{ownedAppForeground=$true;path='connected.png';scope='Visual running-server label check; requires actual image inspection, not automatic label PASS'})
    }
    if($RequireSessionLabel){
        $phase='actual-session-label';$labelVerified=$false
        for($labelAttempt=0;$labelAttempt -lt 4;$labelAttempt++){
            $labelDoc=Ui
            $labelVerified=@($labelDoc.SelectNodes('//node')|Where-Object {$_.text -match 'WhiteList' -and $_.text -match '188\.253\.24\.174'}).Count -eq 1
            if($labelVerified){break};Start-Sleep -Milliseconds 300
        }
        WriteEvidence 'actual-session-label.json' ([ordered]@{verified=$labelVerified;actualSection='WhiteList';expectedSelectedExistingServer=$true;rawCredentialsRecorded=$false})
        if(!$labelVerified){throw 'Actual running server/section label missing; not accepted'}
    }
    $requests=if($Mode -eq 'ipv6-diagnostic'){@(
        @{url='https://ya.ru/';flags=@('--ipv4');path='tun-ya-ipv4'},
        @{url='https://www.google.com/generate_204';flags=@('--ipv6');path='tun-google-ipv6'},
        @{url='https://www.youtube.com/generate_204';flags=@('--ipv4');path='tun-youtube-ipv4'},
        @{url='https://chatgpt.com/';flags=@('--ipv4');path='tun-chatgpt-ipv4'}
    )}elseif($Mode -eq 'tls-diagnostic'){@(
        @{url='https://www.google.com/generate_204';flags=@('--ipv4');path='tun-ipv4'},
        @{url='https://ya.ru/';flags=@('--tls-max','1.2');path='tun-tls12'},
        @{url='https://www.google.com/generate_204';flags=@('--ipv4','--tls-max','1.2');path='tun-ipv4-tls12'}
    )}else{@('https://ya.ru/','https://www.google.com/generate_204','https://www.youtube.com/generate_204','https://chatgpt.com/')|ForEach-Object {@{url=$_;flags=@();path='tun-standard'}}}
    foreach($request in $requests) {
        $url=$request.url
        $phase='bounded-https'
        Guard
        if(!(VpnActive)){throw 'VPN disappeared before owned HTTPS request'}
        $curl=@('shell','/system/bin/curl','--head','--proto','=https','--connect-timeout','8','--max-time','15','--silent','--show-error','--output','/dev/null','--write-out','code=%{http_code},dns=%{time_namelookup},tcp=%{time_connect},tls=%{time_appconnect},total=%{time_total},remote=%{remote_ip}')+@($request.flags)+@($url)
        $out=(& $adb -s $device @curl 2>&1|Out-String).Trim()
        $exit=$LASTEXITCODE
        $match=[regex]::Match($out,'code=(\d{3}),dns=([0-9.]+),tcp=([0-9.]+),tls=([0-9.]+),total=([0-9.]+),remote=([a-fA-F0-9.:]*)')
        $curlError=[regex]::Match($out,'curl: \(\d+\)[^\r\n]*').Value
        $curlError=[regex]::Replace($curlError,'(?i)https?://[^\s]+','[URL]')
        $curlError=$curlError.Substring(0,[Math]::Min(300,$curlError.Length))
        $row=[ordered]@{url=$url;path=$request.path;exitCode=$exit;response=if($match.Success){$match.Value}else{'no bounded curl metrics'};curlError=$curlError;tlsVerified=($exit -eq 0 -and $match.Success -and $match.Groups[1].Value -ne '000');vpnPresentAfter=(VpnActive)}
        $https+=@($row);WriteEvidence 'https.json' $https
        Write-Output "$($row.path) $url tls=$($row.tlsVerified) $($row.response) $curlError"
    }
}catch{
    $errorCode="bounded_gate_failed_at_$phase"
    $knownReason=$_.Exception.Message
    if($knownReason -notin @('ADB command failed; no retry or service stop','External confirmation/window; no bypass','Actual running server/section label missing; not accepted','Connection needs user/provider action or failed; no automatic retry','Approved existing CS188 must already be selected; no selection/import change','Observed automatic mode required before any temporary selection','Temporary manual selection not confirmed','Observed control changed before tap; no stale-coordinate action','Owned compensation controls unavailable','Owned temporary mark no longer matches; no compensation','Owned temporary mark restoration not confirmed','Exact owned pre-start correction receipt required','Owned checkbox correction requires original automatic mode')){$knownReason='Other guarded diagnostic failure; inspect phase, not raw credentials'}
    WriteEvidence 'guard-failure.json' ([ordered]@{phase=$phase;reason=$knownReason;exceptionType=$_.Exception.GetType().FullName;scriptLine=$_.InvocationInfo.ScriptLineNumber})
    Write-Output "Bounded gate stopped at $phase; no provider/permission retry"
}
finally {
    if($started){
        try {
            Guard;$doc=Ui
            if($StopFromVpnSection -and $connected){
                $vpnNavigation=@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -ceq 'VPN' -and $_.class -ceq 'android.widget.Button'})
                if($vpnNavigation.Count -ne 1){throw 'Observed VPN navigation missing for owned cross-section STOP'}
                Tap $vpnNavigation[0];$doc=Ui
                $vpnSelected=@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -ceq 'VPN' -and $_.selected -ceq 'true'}).Count -eq 1
                WriteEvidence 'cross-section-stop.json' @{activeSessionSection='WhiteList';stopPage='VPN';pageSelected=$vpnSelected;ownedConnection=$true;onlyNormalUi=$true}
                # Accessibility may omit a custom navigation button's selected
                # bit. Retain the failed assertion, but do not strand the owned
                # session before its ordinary STOP cleanup. Visual acceptance
                # remains separate; this is not an automatic page PASS.
            }
            $stop=@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -in @('Отключить','Идёт подключение') -or $_.'content-desc' -cmatch '^Отключить · (?:Подключено|Подключение)(?: · .*)?$'})
            if($stop.Count -eq 1 -and $stop[0].enabled -ceq 'true'){Tap $stop[0]}
            $wait=[Diagnostics.Stopwatch]::StartNew()
            do {
                $s=Adb @('shell','dumpsys','activity','services','org.mcrf.client')
                $cleaned=!(VpnActive) -and $s -notmatch 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'
                if(!$cleaned){Start-Sleep -Milliseconds 500}
            }while(!$cleaned -and $wait.Elapsed.TotalSeconds -lt 30)
        }catch{$cleaned=$false}
    }else{$cleaned=$true}
    if($cleaned -and $restoreManualAfterCompensation){
        try {
            Guard
            if(VpnActive){throw 'Unexpected active VPN; no mode override'}
            $doc=Ui
            for($attempt=0;$attempt -lt 6;$attempt++){
                $auto=@($doc.SelectNodes('//node')|Where-Object {$_.text -ceq 'Автовыбор' -and $_.class -ceq 'android.widget.Switch'})
                if($auto.Count -eq 1){break};$doc=ScrollUi $doc $true
            }
            if($auto.Count -ne 1){throw 'Latest manual mode restoration control unavailable'}
            if($auto[0].checked -ceq 'true'){Tap $auto[0];$doc=Ui}
            $manualRestored=@($doc.SelectNodes('//node')|Where-Object {$_.text -ceq 'Автовыбор' -and $_.checked -ceq 'false'}).Count -eq 1
            $restorationProbeIdle=WaitOwnedMeasurementsIdle
            WriteEvidence 'compensation-manual-mode-restored.json' @{restored=$manualRestored;automatic=$false;ownedMeasurementsIdle=$restorationProbeIdle;privatePreferencesPatch=$false}
        }catch{$manualRestored=$false}
    }
    if($cleaned -and $restoreAutomatic){
        try{
            Idle;$doc=Ui
            $nav=@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -ceq 'Белые списки'})
            if($nav.Count -ne 1){throw 'Restoration navigation unavailable'}
            Tap $nav[0];$doc=Ui
            for($attempt=0;$attempt -lt 6;$attempt++){
                $auto=@($doc.SelectNodes('//node')|Where-Object {$_.text -ceq 'Автовыбор' -and $_.class -ceq 'android.widget.Switch'})
                if($auto.Count -eq 1){break};$doc=ScrollUi $doc $true
            }
            if($auto.Count -ne 1){throw 'Restoration switch unavailable'}
            if($auto[0].checked -ceq 'false'){Tap $auto[0];$doc=Ui}
            $selectionRestored=@($doc.SelectNodes('//node')|Where-Object {$_.text -ceq 'Автовыбор' -and $_.checked -ceq 'true'}).Count -eq 1
            $restoredServer=$false
            for($attempt=0;$attempt -lt 6;$attempt++){
                $restoredServer=@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -match '^CSQTT, CSQTT, 188\.253\.24\.174, .*выбран$'}).Count -eq 1
                if($restoredServer){break};$doc=ScrollUi $doc $false
            }
            $selectionRestored=$selectionRestored -and $restoredServer
            WriteEvidence 'temporary-selection-restored.json' @{automaticRestored=$selectionRestored;originalExistingServerRestored=$restoredServer;noPrivatePreferencesPatch=$true;ordinaryProbeRefreshMayRun=$true}
            # Enabling automatic selection normally starts an owned measurement.
            # Let it finish before instrumentation; never kill it with a hash
            # checkpoint or confuse a queued probe with an idle/retired process.
            $probeWait=[Diagnostics.Stopwatch]::StartNew()
            do {
                Guard
                if(VpnActive){throw 'VPN became active during restoration; no stop or checkpoint'}
                $services=Adb @('shell','dumpsys','activity','services','org.mcrf.client')
                $restorationProbeIdle=$services -notmatch 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'
                if(!$restorationProbeIdle){Start-Sleep -Milliseconds 500}
            }while(!$restorationProbeIdle -and $probeWait.Elapsed.TotalSeconds -lt 100)
            WriteEvidence 'restoration-probe-idle.json' @{idle=$restorationProbeIdle;elapsedSeconds=[Math]::Round($probeWait.Elapsed.TotalSeconds,2);forcedStop=$false}
        }catch{$selectionRestored=$false}
    }
    if($cleaned -and ((!$restoreAutomatic -and !$restoreManualAfterCompensation) -or $restorationProbeIdle -eq $true)){try{Idle;$after=Hashes;WriteEvidence 'protected-after.json' $after}catch{}}
    try{Adb @('shell','rm',$remote)|Out-Null}catch{}
    if($CaptureSession){try{Adb @('shell','rm',$remoteScreenshot)|Out-Null}catch{}}
    WriteEvidence 'receipt.json' ([ordered]@{snapshot=$install.snapshot;device=$device;ownedStartIssued=$started;systemVpnConnected=$connected;connectSeconds=$elapsed;https=$https;error=$errorCode;ownedSessionCleaned=$cleaned;temporaryManualSelection=$restoreAutomatic;originalSelectionAndAutomaticRestored=$selectionRestored;restorationProbeIdle=$restorationProbeIdle;ownedCs188MarkCompensated=$markCompensated;latestManualModeRestored=$manualRestored;protectedCheckpointComplete=($null -ne $before -and $null -ne $after);protectedHashesExactlyEqual=($null -ne $before -and $null -ne $after -and $before -ceq $after);limit='Existing authorized CS188 only; real shell HTTPS with normal certificate validation. HTTP403 can mean site policy, not transport failure. Does not prove Vivo, playback, UDP/QUIC, all CAPTCHA types or all routes. Authorized normal selection and automatic restoration may refresh probe/runtime metadata; no import/catalogue edit/reset/private-preference patch performed.'})
    Write-Output "INSTALLED_CS_HTTPS_RECEIPT=$evidence/receipt.json"
    if(!$cleaned){throw 'Owned session cleanup not confirmed; no force-stop or user foreground override'}
    if($restoreAutomatic -and !$selectionRestored){throw 'Original automatic selection restoration not confirmed; human action required'}
    if($restoreAutomatic -and !$restorationProbeIdle){throw 'Owned restoration measurement not confirmed idle; no forced stop/checkpoint'}
    if($CompensateOwnedMarkOnly -and (!$markCompensated -or $restoreManualAfterCompensation -and (!$manualRestored -or !$restorationProbeIdle))){throw 'Owned mark/latest manual mode compensation not fully confirmed'}
}
