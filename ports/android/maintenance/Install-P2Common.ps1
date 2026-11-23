param([Parameter(Mandatory)][string]$Snapshot,[switch]$VerifyOnly,[switch]$SkipMatching,[ValidateSet('primary-modules')][string[]]$Checks=@())
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF';$coord="$root/ports/android/coordination"
$device='R5CX73QYN3D';$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$toolsRoot='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
function Sha([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Json([string]$p){Get-Content -LiteralPath $p -Raw|ConvertFrom-Json}
$snapshotPath=[IO.Path]::GetFullPath($Snapshot)
if($snapshotPath -cnotmatch '^D:\\MCRF\\Projects\\MCRF\\work\\android-integration-[a-f0-9]{32}$' -or (Get-Item $snapshotPath).LinkType){throw 'Ordinary common snapshot required'}
$build=Json "$snapshotPath/build-result.json";$common=Json "$snapshotPath/p2-candidate-artifacts.json"
$grantPath="$coord/p2-runtime-authority.json";$grant=Json $grantPath;$grantSha=Sha $grantPath
if($build.exitCode -ne 0 -or !$build.sourceStable -or $common.priority -ne 2 -or
    (Sha "$snapshotPath/source-manifest.json") -cne $build.sourceManifestSha256 -or $common.sourceManifestSha256 -cne $build.sourceManifestSha256 -or
    $common.jvmEvidenceSha256 -cne (Sha "$snapshotPath/p2-jvm-evidence.json") -or !(Json "$snapshotPath/p2-jvm-evidence.json").passed){throw 'Successful whole-P2 common source/build/JVM evidence required'}
if($grant.authority -cne 'human-20261021-p2-common-samsung-install-check-restore' -or $grant.device -cne $device -or
    !$grant.allowAppInstall -or !$grant.allowExternalModuleInstall -or $grant.stopForeignVpn -or $grant.allowPublication -or $grant.sourceReadySha256 -cne $common.readinessSha256){throw 'Fresh exact P2 phone grant required'}
$stage="D:/MCRF/Builds/Android/p2-install-$([guid]::NewGuid().ToString('N'))";[IO.Directory]::CreateDirectory($stage)|Out-Null
$result=[ordered]@{state='PREPARING';common=$snapshotPath;commonArtifactsSha256=Sha "$snapshotPath/p2-candidate-artifacts.json";controllerSha256=Sha $PSCommandPath;authoritySha256=$grantSha;installed=@();protectedUnchanged=$false;published=$false}
function Save{[IO.File]::WriteAllText("$stage/receipt.json",($result|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))}
function Adb([string[]]$Arguments){$out=(& $adb -s $device @Arguments 2>&1|Out-String).Trim();if($LASTEXITCODE){throw 'Exact device ADB command failed'};$out}
function Certificate([string]$p){$out=(& "$toolsRoot/apksigner.bat" verify --print-certs $p 2>&1|Out-String);$matches=[regex]::Matches($out,'(?m)^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})\s*$');if($LASTEXITCODE -or $matches.Count -ne 1){throw 'APK signature verification failed'};$matches[0].Groups[1].Value}
function Installed([string]$package){
    $path=Adb @('shell','pm','path','--user','0',$package)
    $pattern='^package:(/data/app/(?:[A-Za-z0-9_~+=-]+/)?'+[regex]::Escape($package)+'-[A-Za-z0-9_~+=-]+/base\.apk)$'
    if($path -cnotmatch $pattern){throw 'Exact ordinary installed APK required'}
    $remote=$Matches[1];$hash=Adb @('shell','sha256sum',$remote)
    if($hash -cnotmatch '^([a-f0-9]{64})\s'){throw 'Installed hash missing'}
    @{path=$remote;sha256=$Matches[1]}
}
$job=$null
function Idle{
    $current=Json "$coord/current.json";$lease=Json "$coord/device-lease.json"
    if((Sha $grantPath) -cne $grantSha -or $current.activeBatch -cne 'p2-progress-2026-10-20.json' -or $current.jobs.build -or $current.jobs.hostTest -or $current.jobs.emulator -or
        ($current.jobs.device -and $current.jobs.device -cne $job) -or $lease.device -cne $device -or $lease.owner_thread -cne $grant.owner -or $lease.state -cne 'occupied_main_fixture_acceptance'){throw 'Exact exclusive P2 device lease required'}
    if((Adb @('shell','am','get-current-user')) -cne '0' -or (Adb @('shell','getprop','ro.product.model')) -cne 'SM-F741B'){throw 'Device/user changed'}
    if((Adb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (Adb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Fresh awake/unlocked screen required'}
    $vpn=Adb @('shell','dumpsys','vpn_management');if($vpn -notmatch 'Active vpn type:' -or $vpn -match 'Active vpn type: [01]\b'){throw 'Active or unknown user VPN; no stop/install'}
    foreach($package in @('org.mcrf.client','org.steer.prototype','com.wdtt.plus','csqtt.quic.amurcanov')){if((Adb @('shell','dumpsys','activity','services',$package)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'User service active; no stop/install'}}
}
function Checkpoint([string]$name){
    $out=Adb @('shell','am','instrument','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation')
    $out=[regex]::Replace($out,"\r+\n","`n");[IO.File]::WriteAllText("$stage/$name.log",$out,[Text.UTF8Encoding]::new($false))
    $hashes=[regex]::Match($out,'(?s)INSTRUMENTATION_RESULT: protectedHashes=(.*?)\n\n')
    if($out -notmatch 'passed=true' -or $out -notmatch 'stable=true' -or $out -notmatch 'INSTRUMENTATION_CODE: -1' -or !$hashes.Success -or $hashes.Groups[1].Value -notmatch 'files/profile.enc=' -or $hashes.Groups[1].Value -notmatch 'shared_prefs/identity.xml='){throw 'Protected inventory incomplete'}
    $hashes.Groups[1].Value.Trim()
}
$before=$null;$checksStarted=$false;$result.replaced=@();$result.cleanupPending=$false
Save
try{
    Idle
    $guard=Json "$root/work/p0-data-guard-8509f0f4e30040888930e74758e270c2/receipt.json"
    if($guard.state -cne 'PASS' -or !$guard.sourceStable -or (Sha $guard.apk) -cne $guard.sha256 -or (Installed 'org.mcrf.client.guard').sha256 -cne $guard.sha256){throw 'Exact installed read-only guardian required; no new helper grant inferred'}
    $inputs=@();$packages=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach($row in $common.artifacts){
        $relative=$row.path.Replace('\','/')
        if($relative -cmatch 'x86_64|armeabi-v7a'){continue}
        if($row.kind -cne 'host' -and $relative -cnotmatch 'arm64-v8a' -and @($common.artifacts|Where-Object {$_.kind -ceq $row.kind -and $_.path -cmatch 'arm64-v8a'}).Count){continue}
        if($relative -cnotmatch '^[A-Za-z0-9_./-]+\.apk$' -or '..' -in $relative.Split('/')){throw 'Invalid candidate artifact path'}
        $path=Join-Path $snapshotPath $relative;$file=Get-Item $path
        if($file.LinkType -or $file.Length -ne $row.bytes -or (Sha $path) -cne $row.sha256){throw 'Candidate APK changed'}
        $badging=(& "$toolsRoot/aapt2.exe" dump badging $path 2>&1|Out-String)
        if($LASTEXITCODE -or $badging -cnotmatch "(?m)^package: name='(org\.mcrf\.(?:client(?:\.test)?|engines\.(?:singbox|steer|wg|csqtt|csqtt25)))' versionCode='([0-9]*)'"){throw 'Unexpected candidate package'}
        $package=$Matches[1];$version=[int]$Matches[2]
        if(!$packages.Add($package) -or $package -ceq 'org.mcrf.client' -and $version -ne 10025 -or $package -cne 'org.mcrf.client.test' -and $version -lt 1){throw 'Duplicate/wrong app candidate version'}
        $inputs+=@{package=$package;versionCode=$version;file=$path;sha256=$row.sha256;certificate=Certificate $path}
    }
    if($inputs.Count -ne 7){throw 'Whole app/runner and five arm64 module packages required'}
    $existing=Installed 'org.mcrf.client';$null=Adb @('pull',$existing.path,"$stage/previous-app.apk")
    if((Sha "$stage/previous-app.apk") -cne $existing.sha256){throw 'Installed backup changed'}
    $certificate=Certificate "$stage/previous-app.apk"
    if(@($inputs|Where-Object certificate -cne $certificate).Count){throw 'All candidates must retain installed app trust identity'}
    $current=Json "$coord/current.json";$job="P2Install:$stage";$current.jobs.device=$job
    [IO.File]::WriteAllText("$coord/current.json",($current|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
    $before=Checkpoint 'protected-before';$result.artifacts=$inputs;Save
    foreach($input in $inputs){
        Idle
        if((Sha $input.file) -cne $input.sha256){throw 'APK changed before install'}
        $alreadyMatches=$SkipMatching -and (Installed $input.package).sha256 -ceq $input.sha256
        if(!$VerifyOnly -and !$alreadyMatches){$out=Adb @('install','-r',$input.file);if($out -notmatch '(?m)^Success\s*$'){throw 'Package replacement not confirmed'};$result.replaced+=$input.package}
        if((Installed $input.package).sha256 -cne $input.sha256){throw 'Installed artifact differs'}
        $result.installed+=$input.package;Save
    }
    $result.newInstallation=(!$VerifyOnly);$result.checks=@()
    foreach($mode in $Checks){
        Idle
        $checksStarted=$true
        $out=Adb @('shell','am','instrument','-w','-r','-e','mode',$mode,'org.mcrf.client.test/org.mcrf.client.DeviceSmokeInstrumentation')
        [IO.File]::WriteAllText("$stage/$mode.log",$out,[Text.UTF8Encoding]::new($false))
        $countMatch=[regex]::Match($out,'(?m)^INSTRUMENTATION_RESULT: passed=([1-9][0-9]*)\s*$')
        $checkCount=if($countMatch.Success){[int]$countMatch.Groups[1].Value}else{0}
        $result.checks+=@{mode=$mode;passed=($checkCount -gt 0 -and $out -match "(?m)^$checkCount checks passed\s*$" -and $out -match '(?m)^INSTRUMENTATION_CODE: -1\s*$' -and $out -notmatch 'INSTRUMENTATION_FAILED|INSTRUMENTATION_RESULT: (?:error|shortMsg)=');count=$checkCount;logSha256=Sha "$stage/$mode.log"};Save
        if(!$result.checks[-1].passed){throw 'Installed-module scoped check failed; retain actual log'}
    }
    $after=Checkpoint 'protected-after';$result.protectedUnchanged=$before -ceq $after
    if(!$result.protectedUnchanged){throw 'Protected files changed; no automatic profile rollback'}
    $result.state=if($Checks.Count){'INSTALLED_SCOPED_CHECKS_PASS_FULL_P2_OPEN'}else{'INSTALLED_RUNTIME_PENDING'};Save;Write-Output "P2_INSTALL=$stage/receipt.json"
}catch{$result.state='FAILED';$result.error=$_.Exception.Message;Save;throw}
finally{
    if($job){
        if($before -and $result.state -ceq 'FAILED'){
            try{$after=Checkpoint 'protected-finally';$result.protectedUnchanged=$before -ceq $after}
            catch{$result.protectedCheckError=$_.Exception.Message}
        }
        if($checksStarted){
            try{
                $services=Adb @('shell','dumpsys','activity','services','org.mcrf.client')
                if($services -match 'ClientPrimary[^\s/]*WorkerService|ClientCarrierService'){throw 'Test worker retirement not proven; retain device job'}
            }catch{$result.cleanupPending=$true;$result.cleanupError=$_.Exception.Message}
        }
        Save
        if(!$result.cleanupPending){$current=Json "$coord/current.json";if($current.jobs.device -ceq $job){$current.jobs.device=$null;[IO.File]::WriteAllText("$coord/current.json",($current|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))}}
    }
}
