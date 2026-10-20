param(
    [Parameter(Mandatory=$true)][string]$Snapshot,
    [string[]]$Modes=@('core-live-api','core-live-ntp'),
    [string]$Device='192.168.7.234:43583',
    [string]$PrivateFixture='',
    [switch]$Install,
    [switch]$AllowIdleWebView,
    [switch]$AuthorizeSingleWarpRegistration,
    [switch]$WarpTimingDiagnostics,
    [switch]$ExcludeOpenFlux,
    [string]$P1SteerApk='',
    [string]$P1WgApk=''
)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskSnapshot=[IO.Path]::GetFullPath((Join-Path $taskRoot $Snapshot))
$taskWork=[IO.Path]::GetFullPath((Join-Path $taskRoot 'work'))
if(!$taskSnapshot.StartsWith($taskWork+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($taskSnapshot) -notmatch '^android-integration-[0-9a-f]{32}$'){throw 'Not a validated integration snapshot'}
$taskLease=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/device-lease.json') -Raw|ConvertFrom-Json
if($taskLease.owner_thread -ne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $taskLease.device -ne $Device -or $taskLease.state -ne 'occupied_main_fixture_acceptance'){throw 'Main device lease is not held'}
$taskBuild=Get-Content -LiteralPath (Join-Path $taskSnapshot 'build-result.json') -Raw|ConvertFrom-Json
if($taskBuild.exitCode -ne 0 -or !$taskBuild.sourceStable){throw 'Snapshot build is not green/stable'}
if($taskBuild.firstLaunchFixture){throw 'Isolated first-launch APK must use its dedicated runner, never replace the main app'}
$taskP1SteerScenario=@($Modes|Where-Object {$_ -cin @('p1-steer-vpn','p1-steer-proxy','p1-steer-tls')})
$taskP1SteerMode=$taskP1SteerScenario.Count -gt 0
$taskP1WgScenario=@($Modes|Where-Object {$_ -cin @('p1-packet-dns','p1-packet-block','p1-packet-tls')})
$taskP1WgMode=$taskP1WgScenario.Count -gt 0
$taskP1OwnerMode=('p1-original-owner' -in $Modes) -or $taskP1SteerMode -or $taskP1WgMode
$taskActiveHuman=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/current.json') -Raw|ConvertFrom-Json
if($taskActiveHuman.activeBatch -ceq 'p1-progress-2026-10-17.json' -and !$taskP1OwnerMode){throw 'Active P1 permits only its separately authorized common fixtures; no historical device grant or closed-suite rerun'}
if($P1SteerApk -and !$taskP1SteerMode){throw 'P1 Steer artifact is scoped to the exclusive native fixture'}
if($P1WgApk -and !$taskP1WgMode){throw 'P1 WG artifact is scoped to the exclusive native fixture'}
$taskP1Artifacts=@();$taskP1Proof=$null
if($taskP1OwnerMode){
    # SOURCE ONLY common-P1 coordinator. No micro-build/install/consent grants.
    # The four common artifacts (including BOTH shared UID helpers) must already
    # be installed with fresh authority.
    if($Modes.Count -ne 1 -or $Install -or ($PrivateFixture -and !$taskP1SteerMode -and !$taskP1WgMode) -or $AllowIdleWebView -or $AuthorizeSingleWarpRegistration -or $WarpTimingDiagnostics -or $ExcludeOpenFlux){throw 'P1 is an exclusive already-installed common batch, not an install or old-suite rerun'}
    if($taskP1SteerMode -and ($PrivateFixture -cnotmatch '^mcrf-steer-input-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\.json$' -or !$P1SteerApk)){throw 'Pinned P1 Steer artifact/private input required'}
    if($taskP1WgMode -and ($PrivateFixture -cnotmatch '^mcrf-packet-input-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\.json$' -or !$P1WgApk)){throw 'Pinned P1 WG artifact/private input required'}
    & (Join-Path $PSScriptRoot 'maintenance/Assert-HumanBatchReady.ps1') -Operation test
    $taskP1Current=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/current.json') -Raw|ConvertFrom-Json
    if($taskP1OwnerMode){
        $taskP1Authority=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/p1-progress-2026-10-17.json') -Raw|ConvertFrom-Json
        if($taskP1Authority.allowCommonDeviceVerification -isnot [bool] -or !$taskP1Authority.allowCommonDeviceVerification -or $taskP1Authority.deviceVerificationAuthority -cnotin @('human-p1-steer-owned-native','human-p1-whole-owned-native')){throw 'No fresh human common-P1 native device authority'}
        if(!$taskP1SteerMode -and $taskP1Authority.deviceVerificationAuthority -cne 'human-p1-whole-owned-native'){throw 'A Steer-only device grant does not authorize the other P1 native fixtures'}
    }
    if($taskP1Current.activeBatch -cne 'p1-progress-2026-10-17.json' -or $taskBuild.p1OwnerFixture -isnot [bool] -or !$taskBuild.p1OwnerFixture -or $taskBuild.sourceStable -isnot [bool] -or !$taskBuild.sourceStable){throw 'Current P1/common helper build required'}
    $taskP1Record=Get-Content -LiteralPath (Join-Path $taskSnapshot 'p1-owner-artifacts.json') -Raw|ConvertFrom-Json
    $taskP1ReadyPath=Join-Path $taskSnapshot 'ports/android/coordination/p1-progress-2026-10-17.json'
    $taskP1Ready=Get-Content -LiteralPath $taskP1ReadyPath -Raw|ConvertFrom-Json
    if($taskP1Record.priority -ne 1 -or $taskP1Ready.priority -ne 1 -or $taskP1Ready.readyForBuild -isnot [bool] -or !$taskP1Ready.readyForBuild -or
       $taskP1Record.readinessSha256 -cnotmatch '^[0-9a-f]{64}$' -or
       (Get-FileHash -LiteralPath $taskP1ReadyPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskP1Record.readinessSha256 -or
       $taskP1Record.sourceManifestSha256 -cnotmatch '^[0-9a-f]{64}$' -or $taskBuild.sourceManifestSha256 -cne $taskP1Record.sourceManifestSha256 -or
       (Get-FileHash -LiteralPath (Join-Path $taskSnapshot 'source-manifest.json') -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskP1Record.sourceManifestSha256){throw 'Frozen common-P1 readiness/manifest mismatch'}
    $taskP1Manifest=Get-Content -LiteralPath (Join-Path $taskSnapshot 'source-manifest.json') -Raw|ConvertFrom-Json
    $taskP1CoordinatorRows=@($taskP1Manifest|Where-Object {$_.path.Replace('\','/') -ceq 'ports/android/integration-device-checks.ps1'})
    if($taskP1CoordinatorRows.Count -ne 1 -or $taskP1CoordinatorRows[0].sha256 -cnotmatch '^[0-9a-f]{64}$'){throw 'Common-P1 device coordinator not pinned by the frozen manifest'}
    $taskP1CoordinatorSha=$taskP1CoordinatorRows[0].sha256
    $taskP1CoordinatorFrozen=Join-Path $taskSnapshot 'ports/android/integration-device-checks.ps1'
    foreach($taskP1CoordinatorPath in @($PSCommandPath,$taskP1CoordinatorFrozen)){
        $taskP1CoordinatorFile=Get-Item -LiteralPath $taskP1CoordinatorPath
        if($taskP1CoordinatorFile.LinkType -or (Get-FileHash -LiteralPath $taskP1CoordinatorPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskP1CoordinatorSha){throw 'Live/frozen common-P1 device coordinator changed'}
    }
    $taskP1Packages=[ordered]@{
        'app/build/outputs/apk/debug/app-universal-debug.apk'='org.mcrf.client'
        'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'='org.mcrf.client.test'
        'p1-owner-probe/build/outputs/apk/debug/p1-owner-probe-debug.apk'='org.mcrf.p1owner'
        'p1-owner-peer/build/outputs/apk/debug/p1-owner-peer-debug.apk'='org.mcrf.p1owner.peer'
    }
    if(@($taskP1Record.artifacts).Count -ne 4){throw 'Exactly four common-P1 artifacts required'}
    foreach($taskP1Path in $taskP1Packages.Keys){
        $taskP1Relative='ports/android/'+$taskP1Path
        $taskP1Rows=@($taskP1Record.artifacts|Where-Object { $_.path.Replace('\','/') -ceq $taskP1Relative })
        if($taskP1Rows.Count -ne 1){throw 'Missing/duplicate common-P1 artifact'}
        $taskP1Row=$taskP1Rows[0];$taskP1File=Get-Item -LiteralPath (Join-Path $taskSnapshot $taskP1Relative)
        if($taskP1File.PSIsContainer -or $taskP1File.LinkType -or $taskP1Row.sha256 -cnotmatch '^[0-9a-f]{64}$' -or
           $taskP1File.Length -ne $taskP1Row.bytes -or (Get-FileHash -LiteralPath $taskP1File.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskP1Row.sha256){throw 'Common-P1 artifact bytes changed'}
        $taskP1Artifacts+= [pscustomobject]@{package=$taskP1Packages[$taskP1Path];sha256=$taskP1Row.sha256}
    }
    if($taskP1SteerMode){
        if($P1SteerApk -cnotmatch '^[A-Za-z0-9_./-]+\.apk$' -or $P1SteerApk.Split('/') -contains '..'){throw 'Frozen relative Steer artifact path required'}
        $taskSteerPath=[IO.Path]::GetFullPath((Join-Path $taskSnapshot $P1SteerApk))
        $taskSteerRows=@($taskP1Record.externalCandidates|Where-Object {$_.kind -ceq 'steer' -and $_.path.Replace('\','/') -ceq $P1SteerApk})
        if(!$taskSteerPath.StartsWith($taskSnapshot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or $taskSteerRows.Count -ne 1 -or (Get-Item -LiteralPath $taskSteerPath).LinkType -or
            (Get-Item -LiteralPath $taskSteerPath).Length -ne $taskSteerRows[0].bytes -or
            (Get-FileHash -LiteralPath $taskSteerPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskSteerRows[0].sha256){throw 'Steer APK is not an output of the common frozen source batch'}
        $taskP1Artifacts+=[pscustomobject]@{package='org.mcrf.engines.steer';sha256=$taskSteerRows[0].sha256}
        # Reuse safe coordinator transport/logging, not the closed schema1 test.
        # The actual instrumentation mode below is still p1-steer-vpn.
        $Modes=@('primary-steer-vpn')
    }
    if($taskP1WgMode){
        if($P1WgApk -cnotmatch '^[A-Za-z0-9_./-]+\.apk$' -or $P1WgApk.Split('/') -contains '..'){throw 'Frozen relative WG artifact path required'}
        $taskWgPath=[IO.Path]::GetFullPath((Join-Path $taskSnapshot $P1WgApk))
        $taskWgRows=@($taskP1Record.externalCandidates|Where-Object {$_.kind -ceq 'wg' -and $_.path.Replace('\','/') -ceq $P1WgApk})
        if(!$taskWgPath.StartsWith($taskSnapshot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or $taskWgRows.Count -ne 1 -or (Get-Item -LiteralPath $taskWgPath).LinkType -or
            (Get-Item -LiteralPath $taskWgPath).Length -ne $taskWgRows[0].bytes -or (Get-FileHash -LiteralPath $taskWgPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskWgRows[0].sha256){throw 'WG APK is not an output of the common source batch'}
        $taskP1Artifacts+=[pscustomobject]@{package='org.mcrf.engines.wg';sha256=$taskWgRows[0].sha256}
        $Modes=@('packet-vpn')
    }
    $taskP1Scope=if($taskP1SteerMode){'Actual Steer model2 DNS/alias OR different-UID authenticated proxy with disjoint package targets, explicit denial/permitted-after, protect/bind/STOP; independent peer join REQUIRED; NOT sharedUID/encryptedDNS/fullP1 acceptance'}elseif($taskP1WgMode){'Actual compact WG managed kernel DNS4/6 UDP/TCP/framework OR atomic BLOCK, real STOP; independent decrypted peer join REQUIRED; NOT encryptedDNS/fullP1 acceptance'}else{'platform original-owner prerequisite only; NOT Steer native/protectedDNS/sharedUID acceptance'}
    $taskP1Proof=[ordered]@{scope=$taskP1Scope;readiness_sha256=$taskP1Record.readinessSha256;source_manifest_sha256=$taskP1Record.sourceManifestSha256;artifacts=$taskP1Artifacts;installed_hashes_verified=$false;full_p1_accepted=$false}
}
$taskAdb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
function Assert-TaskP1Installed {
    if(!$taskP1OwnerMode){return}
    foreach($taskP1CoordinatorPath in @($PSCommandPath,$taskP1CoordinatorFrozen)){
        if((Get-Item -LiteralPath $taskP1CoordinatorPath).LinkType -or (Get-FileHash -LiteralPath $taskP1CoordinatorPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskP1CoordinatorSha){throw 'Common-P1 device coordinator changed during execution'}
    }
    foreach($taskP1Artifact in $taskP1Artifacts){
        $taskP1PackagePath=(& $taskAdb -s $Device shell pm path $taskP1Artifact.package 2>&1|Out-String).Trim()
        $taskP1PathPattern='^package:(/data/app/(?:[A-Za-z0-9_~+=-]+/)?'+[regex]::Escape($taskP1Artifact.package)+'-[A-Za-z0-9_~+=-]+/base\.apk)$'
        if($LASTEXITCODE -ne 0 -or $taskP1PackagePath -cnotmatch $taskP1PathPattern){throw 'P1 requires one ordinary installed base APK of exact package, no split/path guesses'}
        $taskP1InstalledPath=$Matches[1]
        $taskP1InstalledHash=(& $taskAdb -s $Device shell sha256sum $taskP1InstalledPath 2>&1|Out-String).Trim()
        if($LASTEXITCODE -ne 0 -or $taskP1InstalledHash -cnotmatch ('^([0-9a-f]{64})\s+'+[regex]::Escape($taskP1InstalledPath)+'$') -or $Matches[1] -cne $taskP1Artifact.sha256){throw 'P1 installed APK hash mismatch/unreadable; do not install, pull or bypass'}
    }
}
function Assert-TaskIdle {
    foreach($package in @('org.mcrf.client','org.steer.prototype')){
        $services=(& $taskAdb -s $Device shell dumpsys activity services $package 2>&1|Out-String)
        if($LASTEXITCODE -ne 0){throw "Cannot inspect device services for $package"}
        $records=@([regex]::Matches($services,'ServiceRecord\{[^\r\n]*?\b(org\.[^\s}]+)'))
        # Opening MCRF binds the TG state messenger even while TG is disabled. This
        # sole never-started binding has no native child and is not an active proxy.
        $idleTgBinding=$package -eq 'org.mcrf.client' -and $records.Count -eq 1 -and $records[0].Value -match '/\.TelegramProxyService\b' -and $services -match 'startForegroundCount=0\b' -and $services -notmatch 'isForeground=true|startRequested=true'
        # Explicit human confirmation that VK login is complete permits a normal APK
        # replacement with only never-started cached WebView/TG bindings. Never stop them.
        # The authorized P1 foreground is the same ordinary WebView UI. Its
        # renderer/TG bindings are idle only under these exact zero-start checks.
        # Never stop them or admit any started/foreground/unknown service.
        $idleWebBindings=($AllowIdleWebView -or $taskP1OwnerMode) -and $package -eq 'org.mcrf.client' -and $records.Count -gt 0 -and @($records|Where-Object {$_.Value -notmatch '/(?:\.TelegramProxyService|org\.chromium\.content\.app\.SandboxedProcessService\d+:\d+)\b'}).Count -eq 0 -and $services -notmatch 'isForeground=true|startRequested=true|startForegroundCount=[1-9]' -and [regex]::Matches($services,'startForegroundCount=0\b').Count -eq $records.Count
        if($records.Count -gt 0 -and !$idleTgBinding -and !$idleWebBindings){throw "Device not idle for $package; no service will be stopped"}
    }
}
function Get-TaskUserHashes {
    $rows=(& $taskAdb -s $Device shell run-as org.mcrf.client sha256sum files/connections.enc files/routing.enc 2>&1|Out-String).Trim()
    if($LASTEXITCODE -ne 0 -and $Install -and $rows -match 'not debuggable'){
        # A normal signed release does not permit run-as. Its already-installed,
        # same-signature framework runner reads only its own protected file hashes.
        $proof=(& $taskAdb -s $Device shell am instrument -w -r -e mode protected-hashes 'org.mcrf.client.test/org.mcrf.client.ReleaseSmokeInstrumentation' 2>&1|Out-String)
        if($LASTEXITCODE -ne 0 -or $proof -notmatch 'INSTRUMENTATION_RESULT: passed=true' -or $proof -notmatch 'INSTRUMENTATION_RESULT: protectedFilesUnchanged=true'){throw 'Release framework protected-file checkpoint failed'}
        $checked=@()
        foreach($name in @('connections.enc','routing.enc')){
            $hashes=@([regex]::Matches($proof,'(?m)^(?:INSTRUMENTATION_RESULT: protectedFiles(?:Before|After)=)?'+[regex]::Escape($name)+'=([0-9a-f]{64})\r?$'))
            if(!$hashes.Count -or @($hashes|ForEach-Object {$_.Groups[1].Value}|Select-Object -Unique).Count -ne 1){throw 'Ambiguous release protected-file checkpoint'}
            $checked+=$hashes[0].Groups[1].Value+'  files/'+$name
        }
        return ($checked -join [Environment]::NewLine)
    }
    if($LASTEXITCODE -ne 0 -or $rows -notmatch '[0-9a-f]{64}'){throw 'Cannot record protected user-file hashes'}
    return $rows
}
function Assert-TaskP1Screen {
    $taskP1Power=(& $taskAdb -s $Device shell dumpsys power 2>&1|Out-String);if($LASTEXITCODE -ne 0){throw 'P1 power state unavailable'}
    $taskP1Policy=(& $taskAdb -s $Device shell dumpsys window policy 2>&1|Out-String);if($LASTEXITCODE -ne 0){throw 'P1 lock state unavailable'}
    if($taskP1Power -notmatch 'mWakefulness=Awake' -or $taskP1Policy -notmatch '(?m)^\s+showing=false\s*$'){throw 'P1 requires freshly awake/unlocked phone; no wake/unlock'}
    $taskP1Foreground=(& $taskAdb -s $Device shell dumpsys activity activities 2>&1|Out-String);if($LASTEXITCODE -ne 0){throw 'P1 foreground state unavailable'}
    if($taskP1Foreground -notmatch '(?m)^\s*(?:mResumedActivity:|topResumedActivity=|mTopResumedActivity=)[^\r\n]*\borg\.mcrf\.client/'){throw 'P1 requires MCRF already foreground; no launch/other-app interaction'}
}
$taskP1DeviceMutex=$null;$taskP1DeviceHeld=$false
try {
if($taskP1OwnerMode){
    $taskP1DeviceMutex=[Threading.Mutex]::new($false,'Local\MCRF-P1-OriginalOwner')
    try{$taskP1DeviceHeld=$taskP1DeviceMutex.WaitOne(0)}catch [Threading.AbandonedMutexException] {
        $taskP1DeviceHeld=$true;throw 'Previous P1 device owner ended unexpectedly; reconcile live device/jobs first, never assume idle'
    }
    if(!$taskP1DeviceHeld){throw 'P1 device coordinator already active; continue the existing session'}
    Assert-TaskP1Installed;Assert-TaskP1Screen
}
Assert-TaskIdle
$taskBefore=Get-TaskUserHashes
if($Install){
    $env:JAVA_HOME='C:/Program Files/Java/jdk-17'
    $apks=@((Join-Path $taskSnapshot 'ports/android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk'),(Join-Path $taskSnapshot 'ports/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
    foreach($apk in $apks){
        $signer=(& 'C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0/apksigner.bat' verify --print-certs $apk 2>&1|Out-String)
        if($LASTEXITCODE -ne 0 -or $signer -notmatch 'Signer #1 certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Unexpected snapshot APK signer'}
        $installed=(& $taskAdb -s $Device install -r $apk 2>&1|Out-String)
        if($LASTEXITCODE -ne 0 -or $installed -notmatch '(?m)^Success\s*$'){throw "Snapshot APK installation failed: $installed"}
    }
}
$taskEvidence=Join-Path $taskRoot ('work/ports-test-2026-10-14/integration-'+[IO.Path]::GetFileName($taskSnapshot).Substring(20,4)+'-device')
New-Item -ItemType Directory -Path $taskEvidence -Force|Out-Null
$taskRunId=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)
$taskResults=@()
foreach($mode in $Modes){
    if($mode -eq 'dns-runtime'){
        # Reviewed debug-only generated rules against owned on-device UDP peers.
        # No TUN, public DNS, accounts, saved settings or external server operations.
        Assert-TaskIdle
    }
    if($ExcludeOpenFlux -and $mode -ne 'authorized-servers'){throw 'OpenFlux exclusion flag is scoped to the supplied-server fixture'}
    if($AuthorizeSingleWarpRegistration -and $mode -ne 'warp-authorized'){throw 'Registration authorization is scoped only to WARP fixture'}
    if($WarpTimingDiagnostics -and ($mode -ne 'warp-authorized' -or $AuthorizeSingleWarpRegistration)){throw 'Timing diagnostic uses only an existing WARP account; no registration'}
    if($mode -notin @('p1-original-owner','routing-warm','app-update-ui','dual-primary-choice','wg-policy','packet-vpn','packet-native','published-engines','presets-ui','home-fit','warp-authorized','ui','edge-ui','deploy-ui','core-live-api','core-live-ntp','russian-apps','presets','backup','backup-ui-cancel','advanced-dns','dns-no-edit','dns-runtime','dnstt-loopback','ssh-primitives','primary-spi','primary-modules','primary-vpn','primary-steer-vpn','primary-revoke','primary-retirement','primary-destroyed-retirement','primary-connect-pending','primary-choice','client-runtime','probe-transition','optional-features','wireguard-peer','authorized-servers','openflux-authorized','openflux-provider-stage','openflux-provider-stage-v2','openflux-provider-stage-v3','settings-pages')){throw 'Mode not in isolated acceptance allowlist'}
    Assert-TaskIdle
    if($mode -in @('routing-warm','app-update-ui','dual-primary-choice','presets-ui','home-fit','ui','edge-ui','deploy-ui','russian-apps','presets','backup-ui-cancel','dns-no-edit','settings-pages','primary-choice')){
        $power=(& $taskAdb -s $Device shell dumpsys power|Out-String)
        $policy=(& $taskAdb -s $Device shell dumpsys window policy|Out-String)
        if($power -notmatch 'mWakefulness=Awake' -or $policy -notmatch '(?m)^\s+showing=false\s*$'){
            $taskResults+=[pscustomobject]@{mode=$mode;state='deferred_screen_not_available';passed=0}
            Write-Output "DEFER $mode; no wake/unlock or other-app capture"
            continue
        }
    }
    $extras=@();if($mode -eq 'wireguard-peer'){if($PrivateFixture -notmatch '^mcrf-wg-input-[0-9a-f-]{36}\.json$'){throw 'Private WG fixture filename required'};$extras=@('-e','fixture',$PrivateFixture)}
    if($mode -eq 'packet-vpn'){if($PrivateFixture -notmatch '^mcrf-packet-input-[0-9a-f-]{36}\.json$'){throw 'Private packet fixture filename required'};$extras=@('-e','fixture',$PrivateFixture)}
    if($mode -eq 'warp-authorized' -and $AuthorizeSingleWarpRegistration){$extras=@('-e','fixture','human-authorized-single-warp-registration')}
    if($mode -eq 'warp-authorized' -and $WarpTimingDiagnostics){$extras=@('-e','fixture','human-authorized-warp-timing')}
    if($mode -eq 'authorized-servers' -and $ExcludeOpenFlux){$extras=@('-e','fixture','authorized-relays-only')}
    if($mode -eq 'authorized-servers' -and $PrivateFixture){if(!$ExcludeOpenFlux -or $PrivateFixture -notmatch '^mcrf-relay-input-[0-9a-f]{32}\.json$'){throw 'Incident fixture must explicitly exclude OpenFlux and use a private scoped input'};$extras=@('-e','fixture',$PrivateFixture)}
    if($mode -eq 'primary-steer-vpn'){if($PrivateFixture -notmatch '^mcrf-steer-input-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\.json$'){throw 'Private Steer fixture filename required'};$extras=@('-e','fixture',$PrivateFixture)}
    if($taskP1OwnerMode){Assert-TaskP1Installed;Assert-TaskP1Screen;if(!$taskP1SteerMode -and !$taskP1WgMode){
        $taskHelperSha=($taskP1Artifacts|Where-Object {$_.package -ceq 'org.mcrf.p1owner'}).sha256
        $taskPeerSha=($taskP1Artifacts|Where-Object {$_.package -ceq 'org.mcrf.p1owner.peer'}).sha256
        $taskScopeBytes=[Text.Encoding]::ASCII.GetBytes("p1-shared-scope-v1`n$taskHelperSha`n$taskPeerSha`n")
        $taskScopeSha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($taskScopeBytes)).ToLowerInvariant()
        $extras=@('-e','fixture',$taskScopeSha)
    }}
    $taskInstrumentationMode=if($taskP1SteerMode){$taskP1SteerScenario[0]}elseif($taskP1WgMode){'packet-vpn'}else{$mode}
    $output=(& $taskAdb -s $Device shell am instrument -w -r -e mode $taskInstrumentationMode @extras 'org.mcrf.client.test/org.mcrf.client.DeviceSmokeInstrumentation' 2>&1|ForEach-Object {
        if("$_" -match '^INSTRUMENTATION_STATUS: stream=(?:WARP_TIMING|RELAY_INCIDENT) [A-Za-z0-9_=/ .-]+$'){Write-Host "$_"}
        $_
    }|Out-String)
    $commandExit=$LASTEXITCODE
    $logName=if(Test-Path -LiteralPath (Join-Path $taskEvidence "$mode.log")){"$mode-$taskRunId.log"}else{"$mode.log"}
    [IO.File]::WriteAllText((Join-Path $taskEvidence $logName),$output,[Text.UTF8Encoding]::new($false))
    $ok=$commandExit -eq 0 -and $output -match 'INSTRUMENTATION_CODE: -1' -and $output -notmatch '(?m)^(?:INSTRUMENTATION_RESULT:\s*stream=)?FAIL\b' -and $output -notmatch 'INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed'
    if($taskP1OwnerMode){
        if(!$taskP1SteerMode -and !$taskP1WgMode -and $output -notmatch '(?m)^(?:INSTRUMENTATION_(?:RESULT|STATUS): stream=)?PASS p1-owner-scope-only-main-helper-wrong-sha-before-start\r?$'){$ok=$false}
        if($taskP1SteerMode){
            $taskP1Markers=@('Actual Steer STOP observes Binder/PID death, closes TUN/leases/runtime and preserves the main process')
            if($taskP1SteerScenario[0] -ceq 'p1-steer-proxy'){$taskP1Markers+='P1 actual different-UID authenticated HTTP CONNECT absolute HTTP SOCKS TCP UDP DNS and denied/allowed-after controls'}
            elseif($taskP1SteerScenario[0] -ceq 'p1-steer-tls'){$taskP1Markers+='P1 actual encrypted DNS global success wrong-hostname untrusted-cert rejection without fallback and permitted-after completed'}
            else{$taskP1Markers+=@('P1 actual global and per-rule DNS A AAAA UDP TCP plus native alias traffic completed','P1 actual IPv6 native TCP carries owned HTTP response through the same selected VLESS peer')}
            foreach($taskP1Marker in $taskP1Markers){
                if($output -notmatch ('(?m)^(?:INSTRUMENTATION_(?:RESULT|STATUS): stream=)?PASS '+[regex]::Escape($taskP1Marker)+'\r?$')){$ok=$false}
            }
        }elseif($taskP1WgMode){
            $taskPacketMarker=if($taskP1WgScenario[0] -ceq 'p1-packet-tls'){'Encrypted managed WG DNS uses kernel aliases and framework with system trust hostname checks no upstream fallback and permitted-after; independent TLS audit required'}elseif($taskP1WgScenario[0] -ceq 'p1-packet-dns'){'Managed WG DNS uses actual IPv4 IPv6 kernel UDP TCP alias reflection and framework raw DNS; explicit IPv4 option respected'}else{'Owned IPv4/IPv6 BLOCK uses atomic route envelope independently recomputed and SHA-acknowledged by worker; original peer narrowed, permitted-after-block works; independent peer audit required'}
            foreach($taskP1Marker in @($taskPacketMarker,'WG/AWG STOP retires owned VPN/worker; private inputs removed and user profiles/preferences preserved')){
                if($output -notmatch ('(?m)^(?:INSTRUMENTATION_(?:RESULT|STATUS): stream=)?PASS '+[regex]::Escape($taskP1Marker)+'\r?$')){$ok=$false}
            }
        }else{foreach($taskP1Marker in @('tcp4','tcp6','udp4','udp6')){if($output -notmatch ('(?m)^(?:INSTRUMENTATION_(?:RESULT|STATUS): stream=)?PASS p1-original-helper-'+$taskP1Marker+'-uid-package-tuple-io-close\r?$')){$ok=$false}}}
        Assert-TaskP1Installed
        $taskP1Proof.installed_hashes_verified=$true
    }
    $count=0;if($output -match 'INSTRUMENTATION_RESULT: passed=(\d+)'){$count=[int]$Matches[1]}
    $state=if($ok){'passed'}else{'failed'}
    if($taskP1OwnerMode -and $ok){$state=if($taskP1SteerMode -or $taskP1WgMode){'p1_native_pending_independent_peer_join'}else{'platform_original_owner_passed_not_full_p1'}}
    if($mode -eq 'primary-steer-vpn' -and !$taskP1SteerMode -and $ok){$state='android_passed_pending_host_peer_crossmatch'}
    # Control markers (including "feature: SKIP"), not a button named Skip in a PASS report.
    if($output -match '(?m)(?:^|:\s*|stream=)SKIP\b'){$state='incomplete_skipped'}
    $checkpoint=Get-TaskUserHashes
    $unchanged=$taskBefore -ceq $checkpoint
    $taskResults+=[pscustomobject]@{mode=$mode;state=$state;passed=$count;adb_exit=$commandExit;log=$logName;protected_files_unchanged=$unchanged}
    Write-Output "$mode : $state ($count checks)"
    if(!$ok){Write-Output $output}
    if(!$unchanged){Write-Output 'Protected-file checkpoint changed; no further device gate will run';break}
}
$taskAfter=Get-TaskUserHashes
$serverAttempt=@($taskResults|Where-Object {$_.mode -in @('authorized-servers','openflux-authorized','openflux-provider-stage','openflux-provider-stage-v2','openflux-provider-stage-v3')}).Count -gt 0
$warpAttempt=@($taskResults|Where-Object {$_.mode -eq 'warp-authorized'}).Count -gt 0
$receipt=[pscustomobject]@{snapshot=$taskSnapshot;device=$Device;time_utc=[DateTime]::UtcNow.ToString('o');results=$taskResults;user_files_before=$taskBefore;user_files_after=$taskAfter;user_files_unchanged=($taskBefore -ceq $taskAfter);external_accounts_or_servers=($serverAttempt -or $warpAttempt);human_authorized_supplied_server_test=$serverAttempt;existing_warp_account_only=$warpAttempt;external_account_actions=$false;remote_server_settings_changed=$false}
if($taskP1OwnerMode){$taskP1ProofName=if($taskP1SteerMode){'p1_native_steer'}elseif($taskP1WgMode){'p1_native_packet'}else{'p1_original_owner'};$receipt|Add-Member -NotePropertyName $taskP1ProofName -NotePropertyValue $taskP1Proof}
$receiptName=if(Test-Path -LiteralPath (Join-Path $taskEvidence 'device-result.json')){"device-result-$taskRunId.json"}else{'device-result.json'}
if($AuthorizeSingleWarpRegistration){
    $receipt.external_account_actions=[bool]($output -match 'One human-authorized WARP registration saved encrypted')
    $receipt.existing_warp_account_only=$false
    $receipt|Add-Member -NotePropertyName human_authorized_single_warp_registration -NotePropertyValue $true
}
[IO.File]::WriteAllText((Join-Path $taskEvidence $receiptName),($receipt|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
if($taskBefore -cne $taskAfter){throw 'User profile/routing files changed during isolated acceptance'}
Write-Output "EVIDENCE=$taskEvidence"
Write-Output "DEVICE_RECEIPT=$(Join-Path $taskEvidence $receiptName)"
if(@($taskResults|Where-Object {$_.state -eq 'failed'}).Count -gt 0){throw 'One or more isolated gates failed; exact logs retained'}
} finally {
    if($taskP1DeviceHeld){$taskP1DeviceMutex.ReleaseMutex()}
    if($null -ne $taskP1DeviceMutex){$taskP1DeviceMutex.Dispose()}
}
