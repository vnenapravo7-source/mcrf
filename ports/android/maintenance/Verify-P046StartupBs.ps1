param()
$ErrorActionPreference='Stop'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation test
if($args.Count){throw 'Unknown P046 verification arguments'}
$root='D:/MCRF/Projects/MCRF'
$parent='D:/MCRF/Builds/Android/p0-preview20-d9f650a89fb24f839201425708e1ce31'
$prior=Get-Content "$parent/receipt.json" -Raw|ConvertFrom-Json
if($prior.state -cne 'PASS' -or !$prior.sourceStable -or !$prior.excludedP1 -or $prior.versionCode -ne 10020 -or
    $prior.artifacts.universal.sha256 -cne '2e8d743c0824bed2cabe0c2ea582b9ba951d3bea781b0bd0cdc4800073e7b8ae'){throw 'Exact verified published20 parent required'}
$currentPath="$root/ports/android/coordination/current.json"
$current=Get-Content $currentPath -Raw|ConvertFrom-Json
if($current.jobs.build -or $current.jobs.hostTest -or $current.jobs.device -or $current.jobs.emulator){throw 'Existing job; resume instead'}
$mutex=[Threading.Mutex]::new($false,'Local\MCRF-P046-Verification')
$owned=$false
try{$owned=$mutex.WaitOne(0)}catch [Threading.AbandonedMutexException]{$mutex.Dispose();throw 'Abandoned P046 owner; inspect existing receipts before retry'}
if(!$owned){$mutex.Dispose();throw 'P046 verification already running'}
$stage='D:/MCRF/Builds/Android/p046-startup-bs-'+[guid]::NewGuid().ToString('N')
[IO.Directory]::CreateDirectory($stage)|Out-Null
$manifest=[ordered]@{};$canonical=[ordered]@{}
$result=[ordered]@{state='PREPARING';stage=$stage;parent="$parent/receipt.json";item=46;sourceStable=$false;excludedP1=$true;
    scope='New8 P046 callback/wiring checks plus common main release/debug AndroidTest compilation ONLY; no APK/native/phone/network/publication';
    retainedPriorVerification="$parent/receipt.json";apkBuilt=$false;phoneTouched=$false;published=$false;startedUtc=[DateTime]::UtcNow.ToString('o')}
function Sha([string]$p){(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant()}
function Safe([string]$base,[string]$relative){
    $p=[IO.Path]::GetFullPath((Join-Path $base $relative))
    if(!$p.StartsWith([IO.Path]::GetFullPath($base)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'P046 path escape'}
    return $p
}
function ReadLive([string]$relative){$p=Safe $root $relative;$item=Get-Item -LiteralPath $p;if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked source refused'};$canonical[$relative]=Sha $p;return [IO.File]::ReadAllText($p).Replace("`r`n","`n")}
function WriteFrozen([string]$relative,[string]$text){$p=Safe $stage $relative;[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($p))|Out-Null;[IO.File]::WriteAllText($p,$text,[Text.UTF8Encoding]::new($false));$manifest[$relative]=Sha $p}
function Once([string]$text,[string]$before,[string]$after){if([regex]::Matches($text,[regex]::Escape($before)).Count -ne 1){throw 'P046 exact patch boundary mismatch'};return $text.Replace($before,$after)}
function Stable {
    foreach($p in $canonical.Keys){if((Sha (Safe $root $p)) -cne $canonical[$p]){throw 'P046 canonical input changed'}}
    foreach($p in $manifest.Keys){if((Sha (Safe $stage $p)) -cne $manifest[$p]){throw 'P046 frozen input changed'}}
}
$envNames=@('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS');$oldEnv=@{}
foreach($name in $envNames){$oldEnv[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
    $current.jobs.hostTest="P046:$stage";$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8
    foreach($row in @(Get-Content "$parent/source-manifest.json" -Raw|ConvertFrom-Json)){
        $from=Safe $parent $row.path;$to=Safe $stage $row.path
        if((Sha $from) -cne $row.sha256){throw 'Published20 source drift'}
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($to))|Out-Null;[IO.File]::Copy($from,$to,$false);$manifest[$row.path]=$row.sha256
    }
    $base='ports/android/app/src/main/kotlin/org/mcrf/client/'
    foreach($name in @('BsStartupPolicy','DirectAccessCheck','BsDetectionMonitor')){WriteFrozen ($base+$name+'.kt') (ReadLive ($base+$name+'.kt'))}
    WriteFrozen 'ports/android/app/src/test/kotlin/org/mcrf/client/BsStartupPolicyTest.kt' (ReadLive 'ports/android/app/src/test/kotlin/org/mcrf/client/BsStartupPolicyTest.kt')
    # Reconstruct ONLY the three P046 service edits. The canonical service ALSO
    # contains unfinished P1 changes, which must not enter any P046 compilation.
    $service=$base+'McrfVpnService.kt';$text=[IO.File]::ReadAllText((Safe $stage $service)).Replace("`r`n","`n");$live=ReadLive $service
    $oldStart='                if(bs==null) startSource(source,null,vpn,port,applyRoutes,requested,false)'
    $newStart='                if(bs==null) startVpnWithBsPreflight(source,vpn,port,applyRoutes,requested)'
    if(!$live.Contains($newStart)){throw 'P046 service startup wiring absent'}
    $text=Once $text $oldStart $newStart
    $poolBegin=$text.IndexOf('                val catalogue=Connections(this).apply {load()}')
    $poolEnd=$text.IndexOf('                val candidates=BsPlan.candidates(template.getJSONObject("mcrf_bs"))',$poolBegin)
    if($poolBegin -lt 0 -or $poolEnd -le $poolBegin){throw 'P046 prior pool boundary missing'}
    $oldPool=$text.Substring($poolBegin,$poolEnd-$poolBegin)
    if(!$oldPool.Contains('val marked=catalogue.bsStartupEntries()') -or !$oldPool.Contains('NativeCarrier.executable(') -or !$oldPool.Contains('DnsConfig.applyGenerated(')){throw 'Unexpected parent pool logic'}
    $text=Once $text $oldPool "                val template=prepareAutoBsTemplate()`n"
    $begin=$live.IndexOf('    // P046_BEGIN:');$end=$live.IndexOf('    // P046_END',$begin)
    if($begin -lt 0 -or $end -le $begin -or $live.LastIndexOf('    // P046_BEGIN:') -ne $begin){throw 'P046 helper boundary missing/duplicated'}
    $end=$live.IndexOf("`n",$end)
    if($end -lt 0){throw 'P046 helper boundary unterminated'}
    $helper=$live.Substring($begin,$end-$begin+1)
    foreach($required in @('private fun prepareAutoBsTemplate()','private fun startVpnWithBsPreflight(','rememberVpnSession(original)','AutoSectionNotifications.post(this,true)')){
        if(!$helper.Contains($required)){throw 'P046 helper contract missing'}
    }
    $text=Once $text '    private fun startSource(template:' ($helper+'    private fun startSource(template:')
    WriteFrozen $service $text
    # Reject accidental inclusion of the CURRENT P1 packet metadata/fixture path.
    if($text.Contains('ownedBlockFixture') -or $text.Contains('PACKET_SETTINGS_GENERATION_CHANGED')){throw 'Unfinished P1 service entered P046 snapshot'}
    $producer='ports/android/maintenance/Verify-P046StartupBs.ps1';$null=ReadLive $producer
    foreach($p in @('ports/android/maintenance/Assert-HumanBatchReady.ps1','ports/android/coordination/p0-progress-2026-10-19.json')){$null=ReadLive $p}
    $rows=@($manifest.Keys|ForEach-Object {@{path=$_;sha256=$manifest[$_]}})
    [IO.File]::WriteAllText("$stage/source-manifest.json",($rows|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
    $result.canonicalInputs=$canonical;$result.inputs=$rows.Count;$result.state='RUNNING_GROUPED_P046_HOST_AND_COMPILE'
    $result|ConvertTo-Json -Depth 9|Set-Content "$stage/receipt.json" -Encoding utf8
    Stable
    $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$root/work/toolchains/gradle-home"
    $env:JAVA_TOOL_OPTIONS=($oldEnv.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
    Push-Location "$stage/ports/android"
    try{
        & "$env:JAVA_HOME/bin/java.exe" -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain ':app:testDebugUnitTest' '--tests' 'org.mcrf.client.BsStartupPolicyTest' ':app:compileReleaseKotlin' ':app:compileDebugAndroidTestKotlin' '--offline' '--console=plain' *> "$stage/verification.log"
        $result.exitCode=$LASTEXITCODE;if($LASTEXITCODE){throw 'P046 grouped verification failed; original log preserved'}
    }finally{Pop-Location}
    $xmlPath="$stage/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.BsStartupPolicyTest.xml"
    [xml]$xml=Get-Content $xmlPath -Raw
    if([int]$xml.testsuite.tests -ne 8 -or [int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped){throw 'New8 P046 checks incomplete'}
    $result.tests=@{tests=8;xml=$xmlPath;sha256=Sha $xmlPath}
    Stable;$result.sourceStable=$true;$result.state='PASS_SOURCE_POLICY_AND_COMPILE_ONLY'
}catch{$result.state='FAILED_PRESERVED';$result.error=$_.Exception.Message;throw}
finally{
    foreach($name in $oldEnv.Keys){[Environment]::SetEnvironmentVariable($name,$oldEnv[$name],'Process')}
    $result.finishedUtc=[DateTime]::UtcNow.ToString('o');$result|ConvertTo-Json -Depth 10|Set-Content "$stage/receipt.json" -Encoding utf8
    $current=Get-Content $currentPath -Raw|ConvertFrom-Json
    if($current.jobs.hostTest -ceq "P046:$stage"){$current.jobs.hostTest=$null;$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8}
    if($owned){$mutex.ReleaseMutex()};$mutex.Dispose()
    "P046=$($result.state);RECEIPT=$stage/receipt.json"
}
