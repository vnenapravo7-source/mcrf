param()
$ErrorActionPreference='Stop'
if($args.Count){throw 'Unknown arguments'}
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation test
$root='D:/MCRF/Projects/MCRF'
$parent='D:/MCRF/Builds/Android/p0-item41-1c8a2f6a152b42b0b9ecb55128146dfb'
$prior=Get-Content "$parent/receipt.json" -Raw|ConvertFrom-Json
if($prior.state -cne 'PASS' -or !$prior.sourceStable -or !$prior.excludedP1 -or $prior.versionCode -ne 10017 -or
    $prior.artifacts.universal.sha256 -cne '80b61bf1b31d646fb281cea38795d93d8246d7dda829b60c2b084def76404482'){throw 'Exact delivered10017 parent required'}
$currentPath="$root/ports/android/coordination/current.json"
$current=Get-Content $currentPath -Raw|ConvertFrom-Json
if($current.jobs.build -or $current.jobs.hostTest -or $current.jobs.device){throw 'Continue existing job; no duplicate producer'}
$stage='D:/MCRF/Builds/Android/p0-network42-'+[guid]::NewGuid().ToString('N')
[IO.Directory]::CreateDirectory($stage)|Out-Null
$hashes=[ordered]@{};$canonical=[ordered]@{};$patched=[ordered]@{}
$result=[ordered]@{schema=1;state='PREPARING';parent="$parent/receipt.json";stage=$stage;versionCode=10017;excludedP1=$true;sourceStable=$false;apkBuilt=$false;installed=$false;published=$false;startedUtc=[DateTime]::UtcNow.ToString('o');scope='Complete P0 sidebar+42-45+globalIPv4 source batch: affected JVM policies/wiring and main/AndroidTest compilation only; no APK/R8/native execution/device acceptance'}
function Safe([string]$base,[string]$relative){
    $full=[IO.Path]::GetFullPath((Join-Path $base $relative))
    if(!$full.StartsWith([IO.Path]::GetFullPath($base)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Snapshot path escape'}
    $full
}
function Sha([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
function ReadCanonical([string]$relative){$path=Safe $root $relative;$canonical[$relative]=Sha $path;[IO.File]::ReadAllText($path).Replace("`r`n","`n")}
function WriteFrozen([string]$relative,[string]$value){
    $path=Safe $stage $relative;[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))|Out-Null
    [IO.File]::WriteAllText($path,$value,[Text.UTF8Encoding]::new($false));$hashes[$relative]=Sha $path
}
function ReplaceOnce([string]$value,[string]$before,[string]$after){
    if(!$before -or [regex]::Matches($value,[regex]::Escape($before)).Count -ne 1){throw 'Exact frozen patch boundary mismatch'}
    $value.Replace($before,$after)
}
function Line([string]$value,[string]$prefix){
    $found=@($value -split "`n"|Where-Object {$_.TrimStart().StartsWith($prefix,[StringComparison]::Ordinal)})
    if($found.Count -ne 1){throw "Expected one line: $prefix"};$found[0]
}
function PatchLines([string]$relative,[string[]]$prefixes){
    $text=[IO.File]::ReadAllText((Safe $stage $relative)).Replace("`r`n","`n");$live=ReadCanonical $relative
    foreach($prefix in $prefixes){$text=ReplaceOnce $text (Line $text $prefix) (Line $live $prefix)}
    WriteFrozen $relative $text;$patched[$relative]='Exact P0 line patches only; all unrelated/P1 functions inherited from10017'
}
function InsertCanonicalLine([string]$text,[string]$live,[string]$prefix){
    $lines=$live -split "`n";$insert=Line $live $prefix;$index=[Array]::IndexOf($lines,$insert)
    if($index -lt 1){throw 'Insertion predecessor missing'}
    ReplaceOnce $text $lines[$index-1] ($lines[$index-1]+"`n"+$insert)
}
$envNames=@('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS');$oldEnv=@{}
foreach($name in $envNames){$oldEnv[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
    $current.jobs.hostTest="P0_NETWORK42:$stage";$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8
    foreach($row in @(Get-Content "$parent/source-manifest.json" -Raw|ConvertFrom-Json)){
        $from=Safe $parent $row.path;$to=Safe $stage $row.path
        if((Sha $from) -cne $row.sha256){throw "Frozen parent mismatch: $($row.path)"}
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($to))|Out-Null;[IO.File]::Copy($from,$to,$false);$hashes[$row.path]=$row.sha256
    }
    $production=@('AutoSectionNotice','AutoSectionNotifications','AutoSelection','Connections','ServersPage','EdgeConnectionState','ClientTunSettings','ClientTunConfig','ClientTunUi','SettingsSearchIndex','DnsConfig','AdvancedDnsConfig','DirectDnsFamily','DirectDnsNetwork','RouteRules')
    $newSuites=@('AutoSectionNoticeTest','AutoStartupPoolTest','GlobalIpv4CompatibilityTest','DirectDnsFamilyTest','NetworkP0WiringTest','EdgeSectionHandoverTest')
    foreach($name in $production){$p="ports/android/app/src/main/kotlin/org/mcrf/client/$name.kt";WriteFrozen $p (ReadCanonical $p)}
    foreach($name in $newSuites){$p="ports/android/app/src/test/kotlin/org/mcrf/client/$name.kt";WriteFrozen $p (ReadCanonical $p)}
    foreach($p in @('ports/android/app/src/test/kotlin/org/mcrf/client/EdgeConnectionStateTest.kt','ports/android/app/src/main/assets/connection-scene-r10.html','ports/android/app/src/androidTest/kotlin/org/mcrf/client/ConnectionAnimationDeviceChecks.kt','ports/android/app/src/androidTest/java/org/mcrf/client/ConnectionAnimationInstrumentation.java')){WriteFrozen $p (ReadCanonical $p)}
    $main='ports/android/app/src/main/kotlin/org/mcrf/client/MainActivity.kt'
    PatchLines $main @('val hasAutoCandidates=','val pool = ConnectionSections.entries(','if (automatic && pool.isEmpty())','var prepared = LocalProxyConfig.apply(ProfileConfig.build(content, vpn, number)')
    $text=[IO.File]::ReadAllText((Safe $stage $main));$live=ReadCanonical $main
    $begin='    private fun toggleEdgeConnection';$end='    private fun resumeWantedOnLaunch'
    $oldBlock=$text.Substring($text.IndexOf($begin),$text.IndexOf($end)-$text.IndexOf($begin))
    $newBlock=$live.Substring($live.IndexOf($begin),$live.IndexOf($end)-$live.IndexOf($begin))
    WriteFrozen $main (ReplaceOnce $text $oldBlock $newBlock)
    $service='ports/android/app/src/main/kotlin/org/mcrf/client/McrfVpnService.kt'
    PatchLines $service @('if(applyRoutes){val routes=RoutingStore(this);','runningConfig=null')
    $text=[IO.File]::ReadAllText((Safe $stage $service));$live=ReadCanonical $service
    foreach($prefix in @('private val autoSectionNotice=','private var runningDirectDnsIpv4:','if(autoSectionNotice.accept(true,','if(autoSectionNotice.accept(false,')){$text=InsertCanonicalLine $text $live $prefix}
    $text=ReplaceOnce $text (Line $text 'if(applyRoutes){val routes=RoutingStore(this);') ((Line $live 'runningDirectDnsIpv4=null')+"`n"+(Line $live 'if(applyRoutes){val routes=RoutingStore(this);'))
    $oldBranch=Line $text '}else if(optionalSession!=null||carrier!=null||ClientPrimarySelection.selected(this)=="steer")'
    $newBranch=(Line $live '}else if(optionalSession!=null||carrier!=null||ClientPrimarySelection.selected(this)=="steer"||')+"`n"+(Line $live 'DirectDnsFamily.requiresRestart(runningDirectDnsIpv4,')
    WriteFrozen $service (ReplaceOnce $text $oldBranch $newBranch)
    $platformSource='ports/android/app/src/main/kotlin/org/mcrf/client/ClientPrimaryHostPlatform.kt'
    PatchLines $platformSource @('val changed = underlying != selected')
    $text=[IO.File]::ReadAllText((Safe $stage $platformSource));$live=ReadCanonical $platformSource
    $text=InsertCanonicalLine $text $live 'import java.net.Inet6Address'
    $text=InsertCanonicalLine $text $live 'private var underlyingDirectIpv4:'
    $text=ReplaceOnce $text (Line $text 'val changed = underlying != selected') ((Line $live 'val directIpv4=networks[selected]')+"`n"+(Line $live 'val changed = underlying != selected')+"`n"+(Line $live 'underlyingDirectIpv4=directIpv4'))
    WriteFrozen $platformSource $text
    $selection='ports/android/app/src/main/kotlin/org/mcrf/client/ClientPrimarySelection.kt'
    PatchLines $selection @('config=RouteRules.apply(')
    $text=[IO.File]::ReadAllText((Safe $stage $selection))
    # The delivered legacy Steer is already IPv4-only. New host-only setting is
    # consumed semantically here, not passed as an unknown SPI field. No model2/P1 copy.
    $text=ReplaceOnce $text '.put("tun",ClientTunStore(context).load().json())' '.put("tun",ClientTunStore(context).load().json().apply {remove("ipv4Compatibility")})'
    WriteFrozen $selection $text
    foreach($r in @(Get-Content "$parent/source-manifest.json" -Raw|ConvertFrom-Json)){
        if($hashes[$r.path] -cne $r.sha256 -and !$canonical.Contains($r.path)){throw 'Unapproved input changed'}
    }
    $manifest=@($hashes.Keys|ForEach-Object {@{path=$_;sha256=$hashes[$_]}})
    [IO.File]::WriteAllText("$stage/source-manifest.json",($manifest|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
    $result.inputs=$manifest.Count;$result.canonicalInputs=$canonical;$result.partialP0Patches=$patched;$result.state='RUNNING_GROUPED_VERIFICATION'
    $result|ConvertTo-Json -Depth 8|Set-Content "$stage/receipt.json" -Encoding utf8
    $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$root/work/toolchains/gradle-home"
    $env:JAVA_TOOL_OPTIONS=($oldEnv.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
    $filters=@($newSuites|ForEach-Object {"org.mcrf.client.$_"})+@('org.mcrf.client.EdgeConnectionStateTest.explicitOppositeOnSwitchesButOppositeOffNeverStopsTheLiveSection','org.mcrf.client.RouteRulesTest.perRuleDnsAndGlobalExclusion')
    $gradle=@(':app:testDebugUnitTest');foreach($f in $filters){$gradle+=@('--tests',$f)}
    $gradle+=@(':app:compileDebugAndroidTestKotlin','--offline','--console=plain')
    Push-Location "$stage/ports/android"
    try {& "$env:JAVA_HOME/bin/java.exe" -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain @gradle *> "$stage/verify.log";$result.exitCode=$LASTEXITCODE;if($LASTEXITCODE){throw 'Grouped P0 verification failed; original log preserved'}}finally{Pop-Location}
    foreach($r in $manifest){if((Sha (Safe $stage $r.path)) -cne $r.sha256){throw 'Frozen source changed during verification'}}
    foreach($p in $canonical.Keys){if((Sha (Safe $root $p)) -cne $canonical[$p]){throw 'Canonical source changed after capture'}}
    $result.tests=@()
    foreach($suite in @($newSuites)+@('EdgeConnectionStateTest','RouteRulesTest')){
        $xmlPath="$stage/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.$suite.xml"
        [xml]$xml=Get-Content $xmlPath -Raw
        if([int]$xml.testsuite.tests -lt 1 -or [int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped){throw 'Incomplete affected test result'}
        $result.tests+=@{suite=$suite;tests=[int]$xml.testsuite.tests;xml=$xmlPath}
    }
    $result.sourceStable=$true;$result.state='PASS_HOST_AND_ANDROID_COMPILE_ONLY'
}catch{$result.state='FAILED_PRESERVED';$result.error=$_.Exception.Message;throw}
finally {
    foreach($name in $oldEnv.Keys){[Environment]::SetEnvironmentVariable($name,$oldEnv[$name],'Process')}
    $result.finishedUtc=[DateTime]::UtcNow.ToString('o');$result|ConvertTo-Json -Depth 10|Set-Content "$stage/receipt.json" -Encoding utf8
    $current=Get-Content $currentPath -Raw|ConvertFrom-Json
    if($current.jobs.hostTest -ceq "P0_NETWORK42:$stage"){$current.jobs.hostTest=$null;$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8}
    Write-Output "P0_NETWORK42=$($result.state);RECEIPT=$stage/receipt.json"
}
