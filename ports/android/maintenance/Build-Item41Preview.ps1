param([string]$ResumeFailedSnapshot='',[switch]$RecentsRepair,[switch]$FullCorrections,[switch]$PrivacyCorrection,[switch]$UiRound2,[switch]$UiRound2Keyboard,[switch]$SectionSelection,[switch]$SelectionHotfix,[switch]$HotfixAssertionRepair,[switch]$HandoverRetirement,[switch]$LongPressConsume)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation build
$taskRoot='D:/MCRF/Projects/MCRF'
if($PrivacyCorrection){$FullCorrections=$true}
$taskBase='D:/MCRF/Builds/Android/p0-layout-r11-9a54680d79dc48ec960bf9529804de79'
$attestation=Get-Content 'D:/MCRF/Builds/Android/p0-layout-r11-dispatch-18c6545325c445f98ce59112cf95321b/receipt.json' -Raw|ConvertFrom-Json
if($attestation.state -cne 'PASS' -or !$attestation.sourceStable -or !$attestation.excludedP1 -or $attestation.compiledSnapshot -cne $taskBase){throw 'Verified R11 parent attestation required'}
if($RecentsRepair){
    if($ResumeFailedSnapshot){throw 'Explicit repair parent required; do not combine modes'}
    $taskBase='D:/MCRF/Builds/Android/p0-item41-46768156a5cf45c8adf2bb40bfef5f56'
    $verified=Get-Content "$taskBase/receipt.json" -Raw|ConvertFrom-Json
    if($verified.state -cne 'PASS' -or !$verified.sourceStable -or !$verified.excludedP1 -or $verified.versionCode -ne 10011){throw 'Verified installed10011 parent required'}
}
if($FullCorrections){
    if($ResumeFailedSnapshot -or $RecentsRepair){throw 'Full correction mode has one explicit immutable parent'}
    $taskBase='D:/MCRF/Builds/Android/p0-item41-c1b609d34f974934b460327dccb6ac24'
    if($PrivacyCorrection){$taskBase='D:/MCRF/Builds/Android/p0-item41-aa820ce68b48484aafafa49c6e131c93'}
    $verified=Get-Content "$taskBase/receipt.json" -Raw|ConvertFrom-Json
    if($verified.state -cne 'PASS' -or !$verified.sourceStable -or !$verified.excludedP1 -or $verified.versionCode -ne 10012){throw 'Verified preliminary P0 parent required'}
    if(!$PrivacyCorrection){foreach($relative in @('ports/android/app/src/main/kotlin/org/mcrf/client/RecentsDeparture.kt','ports/android/app/src/test/kotlin/org/mcrf/client/RecentsDepartureTest.kt')){
        if((Get-FileHash "$taskRoot/$relative").Hash -cne (Get-FileHash "$taskBase/$relative").Hash){throw 'Retained policy evidence source changed'}
    }
    [xml]$retained=Get-Content "$taskBase/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.RecentsDepartureTest.xml" -Raw
    if([int]$retained.testsuite.tests -ne 9 -or [int]$retained.testsuite.failures -or [int]$retained.testsuite.errors -or [int]$retained.testsuite.skipped){throw 'Retained policy evidence incomplete'}
    }else{
        if(@($verified.tests).Count -ne 7 -or ($verified.tests|Measure-Object tests -Sum).Sum -ne 31){throw 'Full expanded31-check parent required'}
    }
}
if($UiRound2){
    if($ResumeFailedSnapshot -or $RecentsRepair -or $FullCorrections -or $PrivacyCorrection){throw 'UIround2 uses only its final installed10012 parent'}
    $taskBase='D:/MCRF/Builds/Android/p0-item41-313679768d7845d293c3bd7fbf99362b'
    $verified=Get-Content "$taskBase/receipt.json" -Raw|ConvertFrom-Json
    if($verified.state -cne 'PASS' -or !$verified.sourceStable -or !$verified.excludedP1 -or $verified.artifacts.universal.sha256 -cne 'e40c2446d2bf2d77951088dbe0e3f46a4bc1c9a130058aa18f6dd5ff624bb5fc'){throw 'Exact delivered10012 parent required'}
}
if($UiRound2Keyboard){
    if($ResumeFailedSnapshot -or $RecentsRepair -or $FullCorrections -or $PrivacyCorrection -or $UiRound2){throw 'Keyboard correction has one verified10013 parent'}
    $taskBase='D:/MCRF/Builds/Android/p0-item41-27a801fd850544a4a34f0736911f8cd4'
    $verified=Get-Content "$taskBase/receipt.json" -Raw|ConvertFrom-Json
    if($verified.state -cne 'PASS' -or !$verified.sourceStable -or !$verified.excludedP1 -or $verified.versionCode -ne 10013 -or ($verified.tests|Measure-Object tests -Sum).Sum -ne 22){throw 'Complete verified22-check10013 parent required'}
}
if($SectionSelection){
    if($ResumeFailedSnapshot -or $RecentsRepair -or $FullCorrections -or $PrivacyCorrection -or $UiRound2 -or $UiRound2Keyboard){throw 'Selection batch has one verified keyboard parent'}
    $taskBase='D:/MCRF/Builds/Android/p0-item41-ec7402b6b2dc4b43a697e0463ce24217'
    $verified=Get-Content "$taskBase/receipt.json" -Raw|ConvertFrom-Json
    if($verified.state -cne 'PASS' -or !$verified.sourceStable -or !$verified.excludedP1 -or $verified.versionCode -ne 10014 -or ($verified.tests|Measure-Object tests -Sum).Sum -ne 7){throw 'Verified7-check keyboard10014 parent required'}
}
if($SelectionHotfix){
    if($ResumeFailedSnapshot -or $RecentsRepair -or $FullCorrections -or $PrivacyCorrection -or $UiRound2 -or $UiRound2Keyboard -or $SectionSelection){throw 'Hotfix requires exact delivered10015 parent'}
    $taskBase='D:/MCRF/Builds/Android/p0-item41-50b8c7d0857f4ef5a500fc7bd497a9c9'
    $verified=Get-Content "$taskBase/receipt.json" -Raw|ConvertFrom-Json
    if($verified.state -cne 'PASS' -or !$verified.sourceStable -or !$verified.excludedP1 -or $verified.versionCode -ne 10015 -or $verified.artifacts.universal.sha256 -cne '1367cd28db5b7517e1701942afde3d777f83485cc0152b89fe107c924fadb304'){throw 'Verified delivered10015 required'}
    if($HotfixAssertionRepair){
        $taskBase='D:/MCRF/Builds/Android/p0-item41-ab10aa21a7d146f895205401de55d967'
        $failed=Get-Content "$taskBase/receipt.json" -Raw|ConvertFrom-Json
        [xml]$oldTests=Get-Content "$taskBase/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.SelectionHotfixTest.xml" -Raw
        if($failed.state -cne 'FAILED_PRESERVED' -or !$failed.excludedP1 -or $oldTests.testsuite.tests -ne 11 -or $oldTests.testsuite.failures -ne 1 -or @($oldTests.testsuite.testcase|Where-Object failure)[0].name -cne 'allVisualConnectEntrypointsDelegateWithoutOldWhitelistSelectionGuard'){throw 'Exact test-only hotfix assertion failure required'}
    }
}
if($HotfixAssertionRepair -and !$SelectionHotfix){throw 'Assertion repair only belongs to hotfix'}
if($HandoverRetirement){
    if($ResumeFailedSnapshot -or $RecentsRepair -or $FullCorrections -or $PrivacyCorrection -or $UiRound2 -or $UiRound2Keyboard -or $SectionSelection -or $SelectionHotfix -or $HotfixAssertionRepair){throw 'Retirement+row correction has one verified10016 parent'}
    $taskBase='D:/MCRF/Builds/Android/p0-item41-f9780f6018584c56a5e4fa61df38ffd5'
    $verified=Get-Content "$taskBase/receipt.json" -Raw|ConvertFrom-Json
    if($verified.state -cne 'PASS' -or !$verified.sourceStable -or !$verified.excludedP1 -or $verified.versionCode -ne 10016 -or $verified.retainedHotfixPolicyChecks -ne 10){throw 'Exact10016 host parent required; runtimefailure preserved separately'}
}
if($LongPressConsume){
    if($ResumeFailedSnapshot -or $RecentsRepair -or $FullCorrections -or $PrivacyCorrection -or $UiRound2 -or $UiRound2Keyboard -or $SectionSelection -or $SelectionHotfix -or $HotfixAssertionRepair -or $HandoverRetirement){throw 'Final hold correction has exact verified17 parent'}
    $taskBase='D:/MCRF/Builds/Android/p0-item41-f6b4adddc0d04a7084a2a7e71f7af379'
    $verified=Get-Content "$taskBase/receipt.json" -Raw|ConvertFrom-Json
    if($verified.state -cne 'PASS' -or !$verified.sourceStable -or !$verified.excludedP1 -or $verified.versionCode -ne 10017 -or ($verified.tests|Measure-Object tests -Sum).Sum -ne 11){throw 'Complete17 host parent required'}
    $rowPath='ports/android/app/src/main/kotlin/org/mcrf/client/ServerSwipeRow.kt'
    $oldRow=[IO.File]::ReadAllText("$taskBase/$rowPath")
    $newRow=[IO.File]::ReadAllText("$taskRoot/$rowPath")
    $replacement="        // Consume a stationary hold without selecting the server on release or revealing trays.`n        row.setOnLongClickListener {true}"
    if($oldRow.Replace('        row.setOnLongClickListener(null);row.isLongClickable=false',$replacement).Replace("`r`n","`n") -cne $newRow.Replace("`r`n","`n")){throw 'Final hold changed unrelated production logic'}
}
if($ResumeFailedSnapshot){
    $taskBase=[IO.Path]::GetFullPath($ResumeFailedSnapshot)
    if($taskBase -cnotmatch '^D:\\MCRF\\Builds\\Android\\p0-item41-[a-f0-9]{32}$'){throw 'Unexpected repair snapshot'}
    $failed=Get-Content "$taskBase/receipt.json" -Raw|ConvertFrom-Json
    if($failed.state -cne 'FAILED_PRESERVED' -or !$failed.excludedP1){throw 'Preserved failed P0 run required'}
}
if(@(Get-Process -Name java,go -ErrorAction SilentlyContinue).Count){throw 'Existing producer: continue it, do not duplicate'}
$currentPath="$taskRoot/ports/android/coordination/current.json"
$current=Get-Content $currentPath -Raw|ConvertFrom-Json
if($current.jobs.build -or $current.jobs.hostTest){throw 'Registered producer exists'}
$stage=Join-Path 'D:/MCRF/Builds/Android' ('p0-item41-'+[guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($stage)|Out-Null
function SafePath([string]$root,[string]$relative){
    $full=[IO.Path]::GetFullPath((Join-Path $root $relative))
    if(!$full.StartsWith([IO.Path]::GetFullPath($root)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Snapshot path escape'}
    $full
}
$hashes=[ordered]@{};$canonical=[ordered]@{}
foreach($row in @(Get-Content "$taskBase/source-manifest.json" -Raw|ConvertFrom-Json)){
    $from=SafePath $taskBase $row.path;$to=SafePath $stage $row.path
    if((Get-FileHash $from -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row.sha256){throw "Frozen input mismatch: $($row.path)"}
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($to))|Out-Null
    [IO.File]::Copy($from,$to,$false);$hashes[$row.path]=$row.sha256
}
$production=@('MainActivity','AdvancedDnsEditor','ClientBehaviorSettings','ClientBehaviorUi','ClientSettingsNavigation','ClientTunUi','LanSharingUi','OptionalCoreUi','LocalProxyAuthUi','SettingsGroupReset','CoreTuningForm','CoreTuningUi','SettingsSearchIndex','ProtocolsSheet','GeoDataEditor')
$suites=@('SettingsGroupResetTest','ClientBehaviorSettingsTest','ClientSettingsNavigationTest','SettingsSearchIndexTest')
if($RecentsRepair){$production=@('MainActivity','ClientBehaviorUi','RecentsDeparture');$suites=@('RecentsDepartureTest')}
if($FullCorrections){
    $production=@('MainActivity','ClientBehaviorUi','RecentsDeparture','Loft','ConnectionAppearance','ConnectionAppearanceUi','UserThemeColorsUi','SettingsSearchIndex','AdvancedDnsEditor','ClientSettingsNavigation','CoreTuningForm','LanSharingUi','ClientTunUi','StandardButtons','AndroidDeployUi','RoutingDefaultsPresetPicker','WallpaperCombinations','PatternWallpaper','UserThemeColors','LauncherIconsUi','RouteEditorDraft','RoutingPage','HomeUiControls','ThemedDialogBuilder','CarrierCaptchaActivity','QrScanActivity','VkRelayLoginActivity')
    $suites=@('SettingsSearchTargetTest','RouteEditorDraftTest','WallpaperCombinationsTest','UserThemeColorsTest','ClientSettingsNavigationTest','SettingsSearchIndexTest','ConnectionAppearanceTest')
}
if($PrivacyCorrection){$production=@('MainActivity','ClientBehaviorUi','RecentsDeparture');$suites=@('RecentsDepartureTest')}
if($UiRound2){
    $production=@('MainActivity','ThemedDialogBuilder','DialogActionLayout','Loft','UserThemeColors','CoreTuningForm','ConnectionAppearanceUi','SystemBarsUi','ClientSettingsNavigation')
    $suites=@('DialogActionLayoutTest','AdvancedReturnTest','Item41SurfacesTest','UserThemeColorsTest')
}
if($UiRound2Keyboard){$production=@('AppPickerWindow','AppPickerWindowBounds');$suites=@('AppPickerWindowBoundsTest')}
if($SectionSelection){$production=@('MainActivity','SectionConnectSelection');$suites=@('SectionConnectSelectionTest')}
if($SelectionHotfix){$production=@('MainActivity','ServerRow','ServersPage','SectionConnectSelection','ServerTapPolicy');$suites=@('SelectionHotfixTest')}
if($HandoverRetirement){$production=@('MainActivity','ServerRow','ServerSwipeRow','ServerSwitchTransition');$suites=@('SelectionHotfixTest','ServerSwitchTransitionTest','ServerSelectionContainerTest')}
if($LongPressConsume){$production=@('ServerSwipeRow');$suites=@('ServerSelectionContainerTest')}
$paths=@($production|ForEach-Object {"ports/android/app/src/main/kotlin/org/mcrf/client/$_.kt"})+@($suites|ForEach-Object {"ports/android/app/src/test/kotlin/org/mcrf/client/$_.kt"})+@('ports/android/app/build.gradle.kts')
if($RecentsRepair){$paths+=@('ports/android/app/src/main/AndroidManifest.xml')}
foreach($path in $paths){
    $from=SafePath $taskRoot $path;$to=SafePath $stage $path
    $canonical[$path]=(Get-FileHash $from -Algorithm SHA256).Hash.ToLowerInvariant()
    $text=[IO.File]::ReadAllText($from)
    if($path.EndsWith('/MainActivity.kt')){
        $baseText=[IO.File]::ReadAllText((SafePath $taskBase $path))
        $start='    private val primaryHost=';$end='    private fun offerPrimaryChoice'
        $a=$text.IndexOf($start);$z=$text.IndexOf($end,$a);$b=$baseText.IndexOf($start);$e=$baseText.IndexOf($end,$b)
        if($a -lt 0 -or $z -le $a -or $b -lt 0 -or $e -le $b){throw 'P1 host boundaries changed'}
        $text=$text.Substring(0,$a)+$baseText.Substring($b,$e-$b)+$text.Substring($z)
    }elseif($RecentsRepair -and $path.EndsWith('/AndroidManifest.xml')){
        $text=[IO.File]::ReadAllText((SafePath $taskBase $path))
        $needle='<service android:name=".McrfVpnService" android:exported="false" android:permission="android.permission.BIND_VPN_SERVICE" android:foregroundServiceType="specialUse">'
        if($text.Split($needle,[StringSplitOptions]::None).Count -ne 2){throw 'Expected one frozen service manifest declaration'}
        $text=$text.Replace($needle,$needle.Replace('>',' android:stopWithTask="false">'))
    }elseif($path.EndsWith('/GeoDataEditor.kt')){
        $text=$text.Replace("`r`n","`n")
        $a=$text.IndexOf('        body.addView(button("Подготовить категории для Steer",false).apply {')
        $z=$text.IndexOf("        }`n        body.addView(status",$a)
        if($a -lt 0 -or $z -le $a){throw 'P1 Geo boundaries changed'}
        $text=$text.Substring(0,$a)+$text.Substring($z)
        if(!$text.Contains('reset-group:geo:geoipUrl,geositeUrl') -or $text.Contains('SteerGeoPreparation')){throw 'Geo reset or exclusion mismatch'}
    }
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($to))|Out-Null
    [IO.File]::WriteAllText($to,$text,[Text.UTF8Encoding]::new($false))
    $hashes[$path]=(Get-FileHash $to -Algorithm SHA256).Hash.ToLowerInvariant()
}
# Preserve the frozen P0 service; the only requested service change is the four
# existing recents hooks. Never import canonical unfinished packet-routing work.
$path='ports/android/app/src/main/kotlin/org/mcrf/client/McrfVpnService.kt'
$text=[IO.File]::ReadAllText((SafePath $taskBase $path))
if(!$ResumeFailedSnapshot -and !$RecentsRepair -and !$FullCorrections -and !$UiRound2 -and !$UiRound2Keyboard -and !$SectionSelection -and !$SelectionHotfix -and !$HandoverRetirement -and !$LongPressConsume -and [regex]::Matches($text,'ClientBehaviorUi\.applyRecents\(this,(true|false)\)').Count -ne 4){throw 'Expected four frozen service recents hooks'}
if(($ResumeFailedSnapshot -or $RecentsRepair -or $FullCorrections -or $UiRound2 -or $UiRound2Keyboard -or $SectionSelection -or $SelectionHotfix -or $HandoverRetirement -or $LongPressConsume) -and [regex]::Matches($text,'ClientBehaviorUi\.applyRecents\(this\)').Count -ne 4){throw 'Expected four retained service hooks'}
if($HandoverRetirement){
    # Three app-level retirement observations ONLY. Never copy canonical unfinished
    # P1 service networking/packet ownership into this P0 release.
    $canonical[$path]=(Get-FileHash (SafePath $taskRoot $path) -Algorithm SHA256).Hash.ToLowerInvariant()
    $hooks=@(
        @{before='        if (intent == null || intent.action == STOP) {';after="        if (intent == null || intent.action == STOP) {`n            ServerSwitchTransition.observeCommand(intent?.getStringExtra(ServerSwitchTransition.EXTRA))"},
        @{before='        if (intent.action != START) { stopSelf(startId); return START_NOT_STICKY }';after="        if (intent.action != START) { stopSelf(startId); return START_NOT_STICKY }`n        ServerSwitchTransition.observeCommand(null)"},
        @{before='    override fun onRevoke() { ';after='    override fun onRevoke() { ServerSwitchTransition.observeCommand(null);'}
    )
    foreach($hook in $hooks){if([regex]::Matches($text,[regex]::Escape($hook.before)).Count -ne 1){throw 'Frozen service hook boundary changed'};$text=$text.Replace($hook.before,$hook.after)}
}
$text=[regex]::Replace($text,'ClientBehaviorUi\.applyRecents\(this,(true|false)\)','ClientBehaviorUi.applyRecents(this)')
[IO.File]::WriteAllText((SafePath $stage $path),$text,[Text.UTF8Encoding]::new($false))
$hashes[$path]=(Get-FileHash (SafePath $stage $path) -Algorithm SHA256).Hash.ToLowerInvariant()
$manifest=@($hashes.Keys|ForEach-Object {@{path=$_;sha256=$hashes[$_]}})
if($HotfixAssertionRepair){foreach($row in @(Get-Content "$taskBase/source-manifest.json" -Raw|ConvertFrom-Json)){
    if($row.path -cne 'ports/android/app/src/test/kotlin/org/mcrf/client/SelectionHotfixTest.kt' -and $hashes[$row.path] -cne $row.sha256){throw 'Assertion-only repair changed production or unrelated input'}
}}
if($ResumeFailedSnapshot){
    foreach($row in @(Get-Content "$taskBase/source-manifest.json" -Raw|ConvertFrom-Json)){
        if($row.path -cne 'ports/android/app/src/test/kotlin/org/mcrf/client/SettingsSearchIndexTest.kt' -and $hashes[$row.path] -cne $row.sha256){throw "Test-only repair changed another input: $($row.path)"}
    }
    foreach($suite in $suites|Where-Object {$_ -cne 'SettingsSearchIndexTest'}){
        [xml]$xml=Get-Content "$taskBase/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.$suite.xml" -Raw
        if([int]$xml.testsuite.tests -lt 1 -or [int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped){throw 'Cannot retain incomplete affected suite'}
    }
}
$manifest|ConvertTo-Json -Depth 4|Set-Content "$stage/source-manifest.json" -Encoding utf8
$canonical|ConvertTo-Json -Depth 4|Set-Content "$stage/canonical-inputs.json" -Encoding utf8
$result=[ordered]@{state='STARTED';stage=$stage;parent=$taskBase;excludedP1=$true;sourceStable=$false;versionCode=10011;versionName='0.1.0-preview.20261019.1';testSignedOnly=$true;published=$false;deviceMutated=$false;startedUtc=[DateTime]::UtcNow.ToString('o');scope='Complete item41 source batch + previously verified item40 casting disconnect. Four affected JVM suites, main/AndroidTest compilation, R8 release base native0. No repeated renderer/network/native suites, no fixture APK.'}
if($RecentsRepair){$result.versionCode=10012;$result.scope='Fresh human Recents repair + same-prerelease revision: one complete lifecycle-policy source batch,9 new policy JVM checks, main/AndroidTest compile and R8 base native0. Verified10011 parent; old unchanged suites not repeated. No fixture/module APK.'}
if($FullCorrections){$result.versionCode=10012;$result.scope='Full expanded item41 correction batch; immutable verified P0 parent, P1 host block excluded. Affected UI/search/palette/list suites grouped once; unchanged9 Recents policy tests retained by exact source SHA. Main/AndroidTest compile and R8 native0. No unchanged renderer/network/native repeats.';$result.retainedRecentsEvidence="$taskBase/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.RecentsDepartureTest.xml"}
if($PrivacyCorrection){$result.scope='Samsung actual direct-Overview regression correction: background-pause privacy fallback with protected external/config/multiwindow paths. Full expanded UI source inherited unchanged,31 affected checks retained; only changed13-policy checks +main/AndroidTest/R8. Same10012 revision not yet published.';$result.retainedUiEvidence="$taskBase/receipt.json";$result.retainedRecentsEvidence="$stage/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.RecentsDepartureTest.xml"}
if($UiRound2){$result.versionCode=10013;$result.scope='Full new human item41 UI feedback batch: native dialog footer geometry for ALL dialogs, runtime systembar colors, neutral opaque cards/navigation,70percentopaque connection block, Advanced origin return+retention, spacing and header no underline.22affectedchecks+main/AndroidTest/R8; P1 excluded. Phone update authorized; publication STRICTLY WAIT human approval.';$result.approvalRequiredBeforePublication=$true;$result.retainedRecentsEvidence="$taskBase/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.RecentsDepartureTest.xml"}
if($UiRound2Keyboard){$result.versionCode=10014;$result.scope='Actual Samsung keyboard regression in fixed-height app picker corrected: viewport follows visible display without recreating selection/search. Full UiRound2 source unchanged from verified10013;22 affected checks retained exactly, only7new viewport checks/main+AndroidTest/R8. P1 excluded; phone authorized, publication WAIT human approval.';$result.approvalRequiredBeforePublication=$true;$result.retainedUiEvidence="$taskBase/receipt.json"}
if($SectionSelection){$result.versionCode=10015;$result.scope='Complete UIround2 plus mirrored manual CONNECT pool repair: selected wrong/missing pool -> minimum displayed successful ping in requested VPN/BS only, stable fallback if unmeasured; same-pool choice and automatic marked policy untouched.9newchecks/mainAndroidTest/R8,7keyboard+22UI retained exact sources. Human approved design and Samsung plus SAMErelease update, no Windows/modules/provider traffic authority.';$result.retainedUiEvidence="$taskBase/receipt.json";$result.samePrereleaseAuthorized=$true}
if($SelectionHotfix){$result.versionCode=10016;$result.scope='Urgent complete server selection batch: in-place row/auto/captcha updates without page render; shared CONNECT from VPN/BS/edge fixes old early guards and wrong/empty automatic pools; active row click explicitly transfers to clicked family through normal preflight/consent/START serial closeCore.11newpolicy+wiringchecks +MainAndroidTest/R8; prior unrelated UI/keyboard retained. Human allows saved-server bounded handover+restore, NOT publication.';$result.retainedUiEvidence="$taskBase/receipt.json";$result.samePrereleaseAuthorized=$false}
if($HotfixAssertionRepair){$result.retainedHotfixPolicyChecks=10;$result.retainedFailedAttempt="$taskBase/receipt.json";$result.retainedUiEvidence='D:/MCRF/Builds/Android/p0-item41-50b8c7d0857f4ef5a500fc7bd497a9c9/receipt.json'}
if($HandoverRetirement){$result.versionCode=10017;$result.scope='Actual10016 PRIMARY_WORKER_COMPONENT_BUSY correction: exact ownedSTOP ticket+fullworkerretirement before native validation/start, userSTOP/revoke cancel pending. Fullwidth row selection includes pencil, no longpress reveal, swipe redraw+transparent foreground.11affected lifecycle/wiring/rowchecks plus10unchangedpolicy retained; MainAndroidTest/R8. Frozen10016 service plus3observationhooks ONLY/P1excluded. Human saved-server retest+restore; NOT publication.';$result.retainedHotfixPolicyChecks=10;$result.retainedUiEvidence="$taskBase/receipt.json";$result.samePrereleaseAuthorized=$false}
if($LongPressConsume){$result.versionCode=10017;$result.scope='Final complete17 package inherits verified11 retirement/row checks+10policy; consume stationary longpress with no release click, no tray reveal. Only1changed hold assertion/MainAndroidTest/R8;20prior checks retained, allother production inheritedexact includingserviceP0hooks. No device/network/publication claim fromhost.';$result.retainedHotfixPolicyChecks=20;$result.retainedUiEvidence="$taskBase/receipt.json";$result.samePrereleaseAuthorized=$false}
$oldEnv=@{};foreach($name in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$oldEnv[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
    $current.jobs.build="ITEM41:$stage";$current|ConvertTo-Json -Depth 16|Set-Content $currentPath -Encoding utf8
    $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$taskRoot/work/toolchains/gradle-home"
    $env:JAVA_TOOL_OPTIONS=($oldEnv.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
    Push-Location "$stage/ports/android"
    try {
        $argsList=@(':app:testDebugUnitTest');foreach($suite in $suites){if(!$ResumeFailedSnapshot -or $suite -ceq 'SettingsSearchIndexTest'){
            $filter=if($FullCorrections -and $suite -ceq 'ConnectionAppearanceTest'){'org.mcrf.client.ConnectionAppearanceTest.classicAndBlueprintHideMetricsWithoutHidingOtherCustomStyles'}else{"org.mcrf.client.$suite"}
            if($HotfixAssertionRepair){$filter='org.mcrf.client.SelectionHotfixTest.allVisualConnectEntrypointsDelegateWithoutOldWhitelistSelectionGuard'}
            if($HandoverRetirement -and $suite -ceq 'SelectionHotfixTest'){$filter='org.mcrf.client.SelectionHotfixTest.tappingServerDoesNotRebuildPageAndUsesClickedFamilyForReconnect'}
            if($LongPressConsume){$filter='org.mcrf.client.ServerSelectionContainerTest.holdingRowCannotOpenTrayAndBothRevealDirectionsRedraw'}
            $argsList+=@('--tests',$filter)
        }}
        $argsList+=@(':app:compileDebugAndroidTestKotlin',':app:assembleRelease','-PtestRelease=true','--offline','--console=plain')
        & "$env:JAVA_HOME/bin/java.exe" -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain @argsList *> "$stage/build.log"
        $result.exitCode=$LASTEXITCODE;if($LASTEXITCODE){throw 'Grouped item41 build/check failed; preserved log'}
    }finally{Pop-Location}
    foreach($row in $manifest){if((Get-FileHash (SafePath $stage $row.path) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row.sha256){throw 'Frozen inputs changed'}}
    foreach($path in $canonical.Keys){if((Get-FileHash (SafePath $taskRoot $path) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $canonical[$path]){throw 'Canonical inputs changed during build'}}
    $result.sourceStable=$true;$result.tests=@()
    foreach($suite in $suites){
        $suiteRoot=if($ResumeFailedSnapshot -and $suite -cne 'SettingsSearchIndexTest'){$taskBase}else{$stage}
        [xml]$xml=Get-Content "$suiteRoot/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.$suite.xml" -Raw
        if([int]$xml.testsuite.tests -lt 1 -or [int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped){throw 'Incomplete affected suite'}
        $result.tests+=@{suite=$suite;tests=[int]$xml.testsuite.tests;evidence="$suiteRoot/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.$suite.xml"}
    }
    $parent=Get-Content 'D:/MCRF/Builds/Android/p0-animation-r10-df9b7a15ab8a4f63ac5ab69f10b82f7f/receipt.json' -Raw|ConvertFrom-Json
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $result.artifacts=@{}
    foreach($kind in @('universal','arm64-v8a','armeabi-v7a','x86_64')){
        $apk="$stage/ports/android/app/build/outputs/apk/release/app-$kind-release.apk"
        $cert=(& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --print-certs $apk 2>&1|Out-String)
        if($LASTEXITCODE -or $cert -notmatch 'certificate SHA-256 digest:\s*([0-9a-f]{64})' -or $Matches[1].ToLowerInvariant() -cne $parent.signer){throw 'Installed signer lineage mismatch'}
        $zip=[IO.Compression.ZipFile]::OpenRead($apk);try{if(@($zip.Entries|Where-Object {$_.FullName -match '^lib/|\.so$'}).Count){throw 'Native leaked into base'}}finally{$zip.Dispose()}
        $result.artifacts[$kind]=@{apk=$apk;bytes=(Get-Item $apk).Length;sha256=(Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
    $result.signer=$parent.signer;$result.state='PASS'
}catch{$result.state='FAILED_PRESERVED';$result.error=$_.Exception.Message;throw}
finally {
    foreach($name in $oldEnv.Keys){[Environment]::SetEnvironmentVariable($name,$oldEnv[$name],'Process')}
    $result.finishedUtc=[DateTime]::UtcNow.ToString('o');$result|ConvertTo-Json -Depth 8|Set-Content "$stage/receipt.json" -Encoding utf8
    $current=Get-Content $currentPath -Raw|ConvertFrom-Json
    if($current.jobs.build -ceq "ITEM41:$stage"){$current.jobs.build=$null;$current|ConvertTo-Json -Depth 16|Set-Content $currentPath -Encoding utf8}
    Write-Output "ITEM41=$($result.state);RECEIPT=$stage/receipt.json"
}
