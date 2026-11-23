param([Parameter(Mandatory)][string]$Snapshot,[switch]$RunPrivateChecks)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'));$coord="$root/ports/android/coordination"
$device='R5CX73QYN3D';$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$tools='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
function Hash([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Read([string]$p){Get-Content -LiteralPath $p -Raw|ConvertFrom-Json}
function Adb([string[]]$a){$out=(& $adb -s $device @a 2>&1|Out-String).Trim();if($LASTEXITCODE){throw 'Exact owned device command failed'};$out}
function Certificate([string]$p){
    $out=(& "$tools/apksigner.bat" verify --print-certs $p 2>&1|Out-String)
    $m=[regex]::Matches($out,'(?m)^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})\s*$')
    if($LASTEXITCODE -or $m.Count -ne 1){throw 'Verified APK signer required'};$m[0].Groups[1].Value
}
$snapshotPath=[IO.Path]::GetFullPath($Snapshot)
if($snapshotPath -cnotmatch '^D:\\MCRF\\Projects\\MCRF\\work\\(?:android-integration|p2-expanded-ui-common)-[0-9a-f]{32}$' -or (Get-Item -LiteralPath $snapshotPath).LinkType){throw 'Exact immutable common snapshot required'}
$build=Read "$snapshotPath/build-result.json";$common=Read "$snapshotPath/p2-expansion-artifacts.json"
$jvm=Read "$snapshotPath/p2-expansion-jvm-evidence.json"
$versionApk=Join-Path $snapshotPath 'ports/android/app/build/outputs/apk/debug/app-universal-debug.apk'
$versionRow=@($common.artifacts|Where-Object {$_.path.Replace('\','/') -ceq 'ports/android/app/build/outputs/apk/debug/app-universal-debug.apk'})
if($versionRow.Count -ne 1 -or (Hash $versionApk) -cne $versionRow[0].sha256){throw 'Exact common main APK hash required before version inspection'}
$badging=(& "$tools/aapt.exe" dump badging $versionApk 2>&1|Out-String)
if($LASTEXITCODE -or $badging -cnotmatch "package: name='org\.mcrf\.client' versionCode='(10026|10027|10028|10029|10030|10031|10032|10033|10034|10035|10036|10037|10038|10039|10040|10041|10042)' "){throw 'Recognized common APK version required'}
$expectedVersion=[int]$Matches[1]
if($expectedVersion -in @(10028,10029,10030)){
    if($jvm.count -ne 23 -or $jvm.selectionSha256 -cne (Hash "$snapshotPath/ports/android/coordination/p2-expansion-jvm-affected-2026-10-21.txt")){throw 'Exact supplemental P2 affected-case receipt required'}
}
if($expectedVersion -eq 10031){
    if($jvm.count -ne 19 -or $jvm.selectionSha256 -cne (Hash "$snapshotPath/ports/android/coordination/p2-expansion-jvm-affected-2026-10-21.txt")){throw 'Exact hash pool/policy affected selection required'}
}
if($expectedVersion -eq 10032){
    if($jvm.count -ne 22 -or $jvm.newExecutionCount -ne 3 -or @($jvm.retained.cases).Count -ne 19 -or $jvm.selectionSha256 -cne (Hash "$snapshotPath/ports/android/coordination/p2-expansion-jvm-affected-2026-10-21.txt")){throw 'Three fresh unified-selection cases plus exact retained19 required'}
}
if($expectedVersion -eq 10033){
    if($jvm.count -ne 29 -or $jvm.newExecutionCount -ne 7 -or @($jvm.retained.cases).Count -ne 22 -or $jvm.selectionSha256 -cne (Hash "$snapshotPath/ports/android/coordination/p2-expansion-jvm-affected-2026-10-21.txt")){throw 'Seven fresh notification/OFF/observation cases plus exact retained22 required'}
}
if($expectedVersion -eq 10034){
    if($jvm.count -ne 31 -or $jvm.newExecutionCount -ne 2 -or @($jvm.retained.cases).Count -ne 29 -or $jvm.selectionSha256 -cne (Hash "$snapshotPath/ports/android/coordination/p2-expansion-jvm-affected-2026-10-21.txt")){throw 'Two fresh deviation cases plus exact retained29 required'}
}
if($expectedVersion -in @(10035,10036)){
    if($jvm.count -ne 31 -or $jvm.newExecutionCount -ne 0 -or !$jvm.retained.allRetained -or @($jvm.retained.cases).Count -ne 31 -or $jvm.selectionSha256 -cne (Hash "$snapshotPath/ports/android/coordination/p2-expansion-jvm-affected-2026-10-21.txt")){throw 'Exact unchanged31 retained cases required; widget regressions require new phone acceptance'}
}
if($expectedVersion -eq 10037){
    if($jvm.count -ne 34 -or $jvm.newExecutionCount -ne 3 -or @($jvm.retained.cases).Count -ne 31 -or $jvm.selectionSha256 -cne (Hash "$snapshotPath/ports/android/coordination/p2-expansion-jvm-affected-2026-10-21.txt")){throw 'Three fresh deploy-call cases plus exact retained31 required'}
}
if($expectedVersion -eq 10038){
    if($jvm.count -ne 34 -or $jvm.newExecutionCount -ne 0 -or !$jvm.retained.allRetained -or @($jvm.retained.cases).Count -ne 34 -or $jvm.selectionSha256 -cne (Hash "$snapshotPath/ports/android/coordination/p2-expansion-jvm-affected-2026-10-21.txt")){throw 'Exact unchanged34 retained cases required for deploy-script repair; actual installer acceptance separate'}
}
if($expectedVersion -eq 10042){
    $initial=$jvm.newExecutionCount -eq 12 -and !$jvm.retained.allRetained -and @($jvm.retained.cases).Count -eq 63 -and $jvm.retained.mode -ceq 'csqtt-generations-swg25-accordion'
    $subnet=$jvm.newExecutionCount -eq 0 -and $jvm.retained.allRetained -and @($jvm.retained.cases).Count -eq 75 -and $jvm.retained.mode -ceq 'csqtt-generations-swg-subnet-only'
    if(!$jvm.passed -or $jvm.count -ne 75 -or !($initial -or $subnet) -or $jvm.selectionSha256 -cne (Hash "$snapshotPath/ports/android/coordination/p2-expansion-jvm-affected-2026-10-21.txt")){throw 'Exact75 accepted SWG25/accordion model chain required; real traffic/UI remain separate'}
}
if($expectedVersion -eq 10041){
    if(!$jvm.passed -or $jvm.count -ne 68 -or $jvm.newExecutionCount -ne 7 -or $jvm.retained.allRetained -or @($jvm.retained.cases).Count -ne 61 -or $jvm.retained.mode -cne 'csqtt-generations-autodeploy-ux' -or $jvm.selectionSha256 -cne (Hash "$snapshotPath/ports/android/coordination/p2-expansion-jvm-affected-2026-10-21.txt")){throw 'Exact retained61 plus seven fresh autodeploy UX/model cases required'}
}
if($expectedVersion -eq 10040){
    if(!$jvm.passed -or $jvm.count -ne 61 -or $jvm.newExecutionCount -ne 0 -or !$jvm.retained.allRetained -or @($jvm.retained.cases).Count -ne 61 -or $jvm.retained.mode -cne 'csqtt-generations-packaging-visual-only' -or $jvm.selectionSha256 -cne (Hash "$snapshotPath/ports/android/coordination/p2-expansion-jvm-affected-2026-10-21.txt")){throw 'Exact retained61 proof required for manifest/glyph-only repair; native module runtime acceptance separate'}
}
if($expectedVersion -eq 10039){
    $fresh=$jvm.retained.mode -ceq 'csqtt-generations-affected-only' -and @($jvm.retained.cases).Count -eq 14 -and $jvm.newExecutionCount -ge 11
    $resumed=$jvm.retained.mode -ceq 'csqtt-generations-fixture-resume' -and @($jvm.retained.cases).Count -eq 60 -and $jvm.retained.baseRetainedCaseCount -eq 14 -and $jvm.retained.resumedCaseCount -eq 46 -and $jvm.newExecutionCount -eq 1 -and $jvm.retained.partial.parentExitCode -eq 1 -and $jvm.retained.partial.fixtureOnlyCorrection
    if(!$jvm.passed -or !($fresh -or $resumed) -or $jvm.selectionSha256 -cne (Hash "$snapshotPath/ports/android/coordination/p2-expansion-jvm-affected-2026-10-21.txt")){throw 'Fresh generation/management cases or exact failed-fixture-only resume required'}
}
if($expectedVersion -eq 10027){
    $uiProof=Read "$snapshotPath/ui-correction-source.json"
    if($uiProof.state -cne 'UI_CORRECTION_SOURCE_READY' -or !$uiProof.nativeUnchanged -or $common.uiCorrectionSourceSha256 -cne (Hash "$snapshotPath/ui-correction-source.json") -or !$common.retainedEvidence.nativeUnchanged -or $common.retainedEvidence.count -ne 52 -or $common.retainedEvidence.newExecution -ne $false){throw 'Explicit UI-correction retention proof required'}
    $expectedVersion=10027
}
if($build.exitCode -ne 0 -or !$build.sourceStable -or !$jvm.passed -or $jvm.count -le 0 -or
   $common.sourceManifestSha256 -cne (Hash "$snapshotPath/source-manifest.json") -or $common.jvmEvidenceSha256 -cne (Hash "$snapshotPath/p2-expansion-jvm-evidence.json")){throw 'Successful NEW common source/build/JVM receipts required'}
$grantPath="$coord/p2-expansion-runtime-authority-2026-10-21.json";$grant=Read $grantPath;$grantSha=Hash $grantPath
if($grant.device -cne $device -or [IO.Path]::GetFullPath($grant.commonReceipt) -cne (Join-Path $snapshotPath 'p2-expansion-artifacts.json') -or !$grant.allowAppInstall -or !$grant.allowFourBsModuleUpdates -or !$grant.allowOwnedPrivateInstrumentation -or $grant.stopForeignVpn -or $grant.allowProviderRegistration -or $grant.allowRemoteVpsActions -or $grant.allowPublication -or $grant.consumed){throw 'Fresh expanded-P2 human grant required'}
$stage="D:/MCRF/Builds/Android/p2-expansion-install-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $stage|Out-Null
$job="ExpandedP2Install:$([guid]::NewGuid().ToString('N'))"
$result=[ordered]@{state='PREPARING';common=$snapshotPath;authoritySha256=$grantSha;artifactsSha256=Hash "$snapshotPath/p2-expansion-artifacts.json";installed=@();privateChecks=$null;protectedUnchanged=$false;published=$false}
function Save{[IO.File]::WriteAllText("$stage/receipt.json",($result|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))}
function Idle{
    $current=Read "$coord/current.json";$lease=Read "$coord/device-lease.json"
    if((Hash $grantPath) -cne $grantSha -or $current.activeBatch -cne 'p2-expansion-2026-10-21.json' -or $current.jobs.build -or $current.jobs.hostTest -or $current.jobs.emulator -or ($current.jobs.device -and $current.jobs.device -cne $job) -or $lease.device -cne $device -or $lease.owner_thread -cne $grant.owner -or $lease.state -cne 'occupied_main_fixture_acceptance'){throw 'Exact exclusive expanded-P2 device lease required'}
    if((Adb @('shell','am','get-current-user')) -cne '0' -or (Adb @('shell','getprop','ro.product.model')) -cne 'SM-F741B'){throw 'Phone identity changed'}
    if((Adb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (Adb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Unlocked awake phone required; no unlock bypass'}
    $vpn=Adb @('shell','dumpsys','vpn_management')
    if($vpn -notmatch 'Active vpn type:' -or $vpn -match 'Active vpn type: [01]\b'){throw 'User VPN active/unknown; no install or stop'}
    foreach($package in @('org.mcrf.client','org.steer.prototype','com.wdtt.plus','csqtt.quic.amurcanov')){
        if((Adb @('shell','dumpsys','activity','services',$package)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'User service active; do not interrupt it'}
    }
}
function Installed([string]$package){
    $out=Adb @('shell','pm','path','--user','0',$package)
    if($out -cnotmatch '^package:(/data/app/(?:[A-Za-z0-9_~+=-]+/)?'+[regex]::Escape($package)+'-[A-Za-z0-9_~+=-]+/base\.apk)$'){throw 'Exact installed base APK required'}
    $path=$Matches[1];$sum=Adb @('shell','sha256sum',$path)
    if($sum -cnotmatch '^([0-9a-f]{64})\s'){throw 'Installed APK digest missing'}
    @{path=$path;sha256=$Matches[1]}
}
function Guard([string]$name){
    $out=Adb @('shell','am','instrument','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation')
    $out=[regex]::Replace($out,"\r+\n","`n");[IO.File]::WriteAllText("$stage/$name.log",$out,[Text.UTF8Encoding]::new($false))
    $m=[regex]::Match($out,'(?s)INSTRUMENTATION_RESULT: protectedHashes=(.*?)\n\n')
    if(!$m.Success -or $out -notmatch 'passed=true' -or $out -notmatch 'stable=true' -or $out -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Read-only protected inventory failed'}
    $m.Groups[1].Value.Trim()
}
Save;Write-Output "EXPANDED_P2_INSTALL=$stage"
$claimed=$false
$originalStayOn=$null
try{
    Idle
    $originalStayOn=Adb @('shell','settings','get','global','stay_on_while_plugged_in')
    if($originalStayOn -cnotmatch '^[0-7]$'){throw 'Cannot preserve display test setting'}
    $null=Adb @('shell','settings','put','global','stay_on_while_plugged_in','3')
    $result.originalStayOn=[int]$originalStayOn;Save
    $guardian=Read "$root/work/p0-data-guard-8509f0f4e30040888930e74758e270c2/receipt.json"
    if($guardian.state -cne 'PASS' -or !$guardian.sourceStable -or (Hash $guardian.apk) -cne $guardian.sha256 -or (Installed 'org.mcrf.client.guard').sha256 -cne $guardian.sha256){throw 'Exact existing read-only guardian required; no helper reinstall'}
    $inputs=@();$seen=[Collections.Generic.HashSet[string]]::new()
    foreach($row in $common.artifacts){
        $relative=$row.path.Replace('\','/')
        if($relative -cnotmatch '(?:app-universal-debug|app-debug-androidTest|engines-(?:csqtt|csqtt25|qwdtt|wdtt)-arm64-v8a-debug)\.apk$'){continue}
        if([IO.Path]::IsPathRooted($relative) -or '..' -cin $relative.Split('/')){throw 'Unsafe common artifact path'}
        $path=Join-Path $snapshotPath $relative;$file=Get-Item -LiteralPath $path
        if($file.LinkType -or $file.Length -ne $row.bytes -or (Hash $path) -cne $row.sha256){throw 'Common APK changed'}
        $badging=(& "$tools/aapt2.exe" dump badging $path 2>&1|Out-String)
        if($LASTEXITCODE -or $badging -cnotmatch "(?m)^package: name='(org\.mcrf\.(?:client(?:\.test)?|engines\.(?:csqtt|csqtt25|qwdtt|wdtt)))' versionCode='([0-9]*)'"){throw 'Unexpected expanded package'}
        $package=$Matches[1];$version=[int]$Matches[2]
        if(!$seen.Add($package) -or ($package -ceq 'org.mcrf.client' -and $version -ne $expectedVersion) -or ($package -cne 'org.mcrf.client.test' -and $version -le 0)){throw 'Duplicate/wrong expanded app version'}
        $inputs+=@{package=$package;file=$path;sha256=$row.sha256;versionCode=$version;certificate=Certificate $path}
    }
    if($inputs.Count -ne 6){throw 'Whole app/runner and four arm64 BS modules required'}
    foreach($input in $inputs){
        $old=Installed $input.package
        $backup="$stage/previous-$($input.package).apk";$null=Adb @('pull',$old.path,$backup)
        if((Hash $backup) -cne $old.sha256 -or (Certificate $backup) -cne $input.certificate){throw 'Existing package backup/signer mismatch'}
    }
    $current=Read "$coord/current.json";$current.jobs.device=$job
    [IO.File]::WriteAllText("$coord/current.json",($current|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false));$claimed=$true
    $before=Guard 'protected-before'
    foreach($input in $inputs){
        Idle
        if((Hash $input.file) -cne $input.sha256){throw 'APK changed before installation'}
        if((Installed $input.package).sha256 -cne $input.sha256){
            $out=Adb @('install','-r',$input.file)
            if($out -notmatch '(?m)^Success\s*$'){throw 'Replacement not confirmed'}
        }
        if((Installed $input.package).sha256 -cne $input.sha256){throw 'Installed artifact differs from common receipt'}
        $result.installed+=$input.package;Save
    }
    $after=Guard 'protected-after-install'
    if($after -cne $before){throw 'Protected state changed at install; do not blindly restore user data'}
    if($RunPrivateChecks){
        Idle
        $out=Adb @('shell','am','instrument','-w','-r','-e','mode','p2-expansion-private','org.mcrf.client.test/org.mcrf.client.DeviceSmokeInstrumentation')
        [IO.File]::WriteAllText("$stage/private-checks.log",$out,[Text.UTF8Encoding]::new($false))
        $result.privateChecks='executed';Save
        if($out -notmatch 'INSTRUMENTATION_CODE: -1' -or $out -match '(?m)^FAIL|INSTRUMENTATION_FAILED'){throw 'New private Android checks failed; inspect exact log'}
        $result.privateChecks='PASS'
    }
    $final=Guard 'protected-final';$result.protectedUnchanged=$final -ceq $before
    if(!$result.protectedUnchanged){throw 'Protected state changed; do not blindly roll back'}
    $result.state='INSTALLED_PRIVATE_CHECKS_COMPLETE_UI_PENDING';Save
}catch{$result.state='FAILED';Save;throw}
finally{
    if($null -ne $originalStayOn){
        $now=Adb @('shell','settings','get','global','stay_on_while_plugged_in')
        if($now -ceq '3'){$null=Adb @('shell','settings','put','global','stay_on_while_plugged_in',$originalStayOn)}
        $result.displayRestored=(Adb @('shell','settings','get','global','stay_on_while_plugged_in')) -ceq $originalStayOn;Save
    }
    if($claimed){$current=Read "$coord/current.json";if($current.jobs.device -ceq $job){$current.jobs.device=$null;[IO.File]::WriteAllText("$coord/current.json",($current|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))}}
}
