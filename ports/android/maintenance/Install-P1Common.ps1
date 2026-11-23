param([Parameter(Mandatory=$true)][string]$WholeReceipt,[string]$PreviousInstallReceipt='')
$ErrorActionPreference='Stop'
if($args.Count){throw 'Unknown installation arguments'}
$root='D:/MCRF/Projects/MCRF';$android=Join-Path $root 'ports/android'
$device='R5CX73QYN3D';$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$signer='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0/apksigner.bat'
function Sha([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
function ReadJson([string]$path){Get-Content -LiteralPath $path -Raw|ConvertFrom-Json}
$whole=ReadJson $WholeReceipt
if($whole.state -cne 'COMMON_HOST_BUILD_PASS_ANDROID_NATIVE_TRAFFIC_ACCEPTANCE_PENDING' -or !$whole.sourceStable -or !$whole.finishedUtc -or
    @($whole.phases.PSObject.Properties|Where-Object {$_.Value.state -cne 'PASS'}).Count){throw 'Successful WHOLE common verification required before any phone access'}
$grant=ReadJson (Join-Path $android 'coordination/p1-device-authority-2026-10-19.json')
if($grant.humanReply -cne 'call_b9b0a2a31ed64ef0ac76ddd41403d993' -or $grant.install -ne $true -or $grant.stopUserVpn -ne $false -or $grant.publish -ne $false -or
    $grant.wholeReceiptSha256 -cne (Sha $WholeReceipt)){throw 'Fresh artifact-bound human P1 phone grant required; no historical P0 grants'}
$ready=ReadJson (Join-Path $android 'coordination/p1-progress-2026-10-17.json')
if(!$ready.allowCommonDeviceVerification -or $ready.deviceVerificationAuthority -cne 'human-p1-whole-owned-native'){throw 'Live P1 device gate closed'}
$snapshot=[IO.Path]::GetFullPath($whole.phases.common_main_r8_debug_runner_helpers_and_external_modules.snapshot)
if([IO.Path]::GetDirectoryName($snapshot) -ine (Join-Path $root 'work') -or [IO.Path]::GetFileName($snapshot) -cnotmatch '^android-integration-[0-9a-f]{32}$'){throw 'Exact common snapshot required'}
$build=ReadJson (Join-Path $snapshot 'build-result.json');$artifacts=ReadJson (Join-Path $snapshot 'p1-owner-artifacts.json')
if($build.exitCode -ne 0 -or !$build.sourceStable -or !$build.p1OwnerFixture -or @($artifacts.artifacts).Count -ne 4 -or
    (Sha (Join-Path $snapshot 'source-manifest.json')) -cne $build.sourceManifestSha256 -or
    (Sha (Join-Path $snapshot 'p1-owner-artifacts.json')) -cne $whole.phases.common_main_r8_debug_runner_helpers_and_external_modules.artifactsSha256){throw 'Completed common artifact/source receipt mismatch'}
$map=[ordered]@{
    'ports/android/app/build/outputs/apk/debug/app-universal-debug.apk'='org.mcrf.client'
    'ports/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'='org.mcrf.client.test'
    'ports/android/p1-owner-probe/build/outputs/apk/debug/p1-owner-probe-debug.apk'='org.mcrf.p1owner'
    'ports/android/p1-owner-peer/build/outputs/apk/debug/p1-owner-peer-debug.apk'='org.mcrf.p1owner.peer'
}
foreach($kind in @('steer','wg')){
    $rows=@($artifacts.externalCandidates|Where-Object {$_.kind -ceq $kind -and $_.path -match 'arm64-v8a-debug\.apk$'})
    if($rows.Count -ne 1){throw 'Exactly one common arm64 external module required'}
    $map[$rows[0].path.Replace('\','/')]='org.mcrf.engines.'+$kind
}
function Certificate([string]$path){
    $out=(& $signer verify --verbose --print-certs $path 2>&1|Out-String)
    $values=[regex]::Matches($out,'(?m)^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})\s*$')
    if($LASTEXITCODE -ne 0 -or $values.Count -ne 1){throw 'Exact APK signing certificate verification failed'}
    $values[0].Groups[1].Value
}
$inputs=@();$certificate=$null
foreach($relative in $map.Keys){
    if($relative -cnotmatch '^[A-Za-z0-9_./-]+\.apk$' -or $relative.Split('/') -contains '..'){throw 'Invalid artifact path'}
    $rows=@(@($artifacts.artifacts)+@($artifacts.externalCandidates)|Where-Object {$_.path.Replace('\','/') -ceq $relative})
    $path=Join-Path $snapshot $relative;$file=Get-Item -LiteralPath $path
    if($rows.Count -ne 1 -or $file.LinkType -or $file.PSIsContainer -or $file.Length -ne $rows[0].bytes -or (Sha $path) -cne $rows[0].sha256){throw 'Artifact bytes changed'}
    $cert=Certificate $path;if(!$certificate){$certificate=$cert};if($cert -cne $certificate){throw 'App/modules/helper signing identity differs'}
    $zip=[IO.Compression.ZipFile]::OpenRead($path)
    try{
        $native=@($zip.Entries|Where-Object {$_.FullName -match '^lib/[^/]+/[^/]+\.so$'})
        $package=$map[$relative]
        if($package -notin @('org.mcrf.engines.steer','org.mcrf.engines.wg')){
            if($native.Count){throw 'Native code must remain outside the main app/fixtures'}
        }else{
            $steer=$package -ceq 'org.mcrf.engines.steer'
            $library=if($steer){'libsteer_vpn.so'}else{'libmcrfwg.so'}
            if($native.Count -ne 1 -or $native[0].FullName -cne "lib/arm64-v8a/$library"){throw 'Exact arm64 module native payload required'}
            $phase=if($steer){'external_steer_two_abi_and_source_bound_stage'}else{'external_wg_three_abi_and_adapter'}
            $aar=Join-Path $whole.phases.$phase.stage $(if($steer){'steer/runtime.aar'}else{'wg-backend.aar'})
            $origin=[IO.Compression.ZipFile]::OpenRead($aar)
            try{
                $entry=$origin.GetEntry("jni/arm64-v8a/$library");if(!$entry){throw 'Compiled native origin missing'}
                $left=$native[0].Open();$right=$entry.Open();$hash=[Security.Cryptography.SHA256]::Create()
                try{if([Convert]::ToHexString($hash.ComputeHash($left)) -cne [Convert]::ToHexString($hash.ComputeHash($right))){throw 'Packaged native differs from verified external producer'}}finally{$left.Dispose();$right.Dispose();$hash.Dispose()}
            }finally{$origin.Dispose()}
        }
        if($package -ceq 'org.mcrf.p1owner.peer'){
            # AGP adds unused Kotlin/R dex even to hasCode=false applications.
            # Prove the Android runtime contract: no code loaded and NO entry
            # point/component, rather than mistaking generated dex for an app.
            $manifest=(& 'C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0/aapt2.exe' dump xmltree $path --file AndroidManifest.xml 2>&1|Out-String)
            if($LASTEXITCODE -ne 0 -or $manifest -cnotmatch ':hasCode\([^)]+\)=false' -or
                $manifest -cnotmatch ':sharedUserId\([^)]+\)="org\.mcrf\.p1fixture\.shared"' -or
                $manifest -match '(?m)^\s+E:\s+(activity|activity-alias|service|receiver|provider)\b'){throw 'Shared UID peer must be hasCode=false with no executable components'}
        }
    }finally{$zip.Dispose()}
    $inputs+=[ordered]@{package=$map[$relative];file=$path;sha256=$rows[0].sha256;bytes=$file.Length}
}
$guard=ReadJson (Join-Path $root 'work/p0-data-guard-8509f0f4e30040888930e74758e270c2/receipt.json')
if($guard.state -cne 'PASS' -or !$guard.sourceStable -or (Sha $guard.apk) -cne $guard.sha256){throw 'Verified read-only guardian required'}
function Adb([string[]]$Arguments){$out=(& $adb -s $device @Arguments 2>&1|Out-String).Trim();if($LASTEXITCODE){throw 'ADB failed; no alternate device/owner/bypass'};$out}
function Installed([string]$package){
    $path=Adb @('shell','pm','path','--user','0',$package)
    $pattern='^package:(/data/app/(?:[A-Za-z0-9_~+=-]+/)?'+[regex]::Escape($package)+'-[A-Za-z0-9_~+=-]+/base\.apk)$'
    if($path -cnotmatch $pattern){throw 'One ordinary installed APK of exact package required'}
    $remote=$Matches[1];$sum=Adb @('shell','sha256sum',$remote)
    if($sum -cnotmatch '^([0-9a-f]{64})\s'){throw 'Installed APK SHA missing'}
    [pscustomobject]@{path=$remote;sha256=$Matches[1]}
}
function Modules{
    $result=[ordered]@{}
    foreach($line in ((Adb @('shell','pm','list','packages','--user','0','org.mcrf.engines')) -split '\r?\n'|Sort-Object)){
        if($line -cnotmatch '^package:(org\.mcrf\.engines(?:\.[a-z0-9]+)?)$'){throw 'Module inventory malformed'}
        $package=$Matches[1];$result[$package]=(Installed $package).sha256
    };$result
}
$currentPath=Join-Path $android 'coordination/current.json';$job=$null
function Idle{
    $lease=ReadJson (Join-Path $android 'coordination/device-lease.json');$current=ReadJson $currentPath
    if($lease.device -cne $device -or $lease.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $lease.state -cne 'occupied_main_fixture_acceptance' -or
       $current.jobs.build -or $current.jobs.hostTest -or $current.jobs.emulator -or ($current.jobs.device -and $current.jobs.device -cne $job)){throw 'Exact sole idle main lease required'}
    if((Adb @('shell','am','get-current-user')) -cne '0' -or (Adb @('shell','getprop','ro.product.model')) -cne 'SM-F741B' -or (Adb @('shell','getprop','ro.serialno')) -cne $device){throw 'Samsung owner/identity changed'}
    if((Adb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (Adb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Screen locked/asleep; no wake or unlock'}
    if((Adb @('shell','dumpsys','vpn_management')) -match 'Active vpn type: [01]\b'){throw 'User VPN active; do not stop it'}
    foreach($package in @('org.mcrf.client','org.steer.prototype','com.wdtt.plus','csqtt.quic.amurcanov')){
        if((Adb @('shell','dumpsys','activity','services',$package)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'User service active; no install/stop'}
    }
    $focus=((Adb @('shell','dumpsys','window')) -split '\r?\n'|Where-Object {$_ -match '\bmCurrentFocus='}) -join ''
    if($focus -notmatch '\b(org\.mcrf\.client|com\.sec\.android\.app\.launcher)/'){throw 'Another app foreground; no interruption'}
}
$evidence=Join-Path $root ('work/ports-test-2026-10-14/p1-install-'+[guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($evidence)|Out-Null
$result=[ordered]@{state='PREPARING';humanReply=$grant.humanReply;wholeReceiptSha256=Sha $WholeReceipt;controllerSha256=Sha $PSCommandPath;artifacts=$inputs;installed=@();published=$false;userVpnStopped=$false;protectedUnchanged=$false}
function Save{[IO.File]::WriteAllText((Join-Path $evidence 'receipt.json'),($result|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))}
function Checkpoint([string]$name){
    $out=Adb @('shell','am','instrument','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation')
    $out=[regex]::Replace($out,"\r+\n","`n");[IO.File]::WriteAllText((Join-Path $evidence ($name+'.log')),$out,[Text.UTF8Encoding]::new($false))
    $hashes=[regex]::Match($out,'(?s)INSTRUMENTATION_RESULT: protectedHashes=(.*?)\n\n')
    if($out -notmatch 'passed=true' -or $out -notmatch 'stable=true' -or $out -notmatch 'INSTRUMENTATION_CODE: -1' -or !$hashes.Success -or
        $hashes.Groups[1].Value -notmatch 'files/profile.enc=' -or $hashes.Groups[1].Value -notmatch 'shared_prefs/identity.xml='){throw 'Protected inventory incomplete'}
    $hashes.Groups[1].Value.Trim()
}
try{
    Idle
    if((Installed 'org.mcrf.client.guard').sha256 -cne $guard.sha256){throw 'Installed read-only guardian differs'}
    $existingHelpers=Adb @('shell','pm','list','packages','--user','0','org.mcrf.p1owner')
    if($existingHelpers){
        if(!$PreviousInstallReceipt){throw 'Helper package already exists; exact owned previous install evidence required'}
        $previousPath=[IO.Path]::GetFullPath($PreviousInstallReceipt)
        if([IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($previousPath)) -ine (Join-Path $root 'work/ports-test-2026-10-14') -or
            [IO.Path]::GetFileName([IO.Path]::GetDirectoryName($previousPath)) -cnotmatch '^p1-install-[0-9a-f]{32}$' -or [IO.Path]::GetFileName($previousPath) -cne 'receipt.json'){throw 'Exact previous owned installer receipt required'}
        $previous=ReadJson $previousPath
        if($previous.state -cne 'INSTALLED_NOT_RUNTIME_ACCEPTED' -or !$previous.protectedUnchanged -or !$previous.unrelatedModulesUnchanged -or
            $previous.humanReply -cne $grant.humanReply -or @($previous.installed).Count -ne 6 -or @($previous.artifacts).Count -ne 6){throw 'Previous owned installer did not complete safely'}
        foreach($row in $previous.artifacts){
            if($row.package -cnotin @($inputs|ForEach-Object package) -or (Installed $row.package).sha256 -cne $row.sha256){throw 'Previous installed ownership/hash changed; no overwrite'}
        }
        $result.previousInstallReceipt=$previousPath;$result.previousInstallReceiptSha256=Sha $previousPath
    }
    $job='P1Install:'+$evidence;$current=ReadJson $currentPath;$current.jobs.device=$job
    [IO.File]::WriteAllText($currentPath,($current|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
    Idle;$before=Checkpoint 'protected-before';$beforeModules=Modules
    $backup=Join-Path $evidence 'previous';[IO.Directory]::CreateDirectory($backup)|Out-Null
    foreach($package in @('org.mcrf.client','org.mcrf.engines.steer','org.mcrf.engines.wg')){
        if($package -ne 'org.mcrf.client' -and !$beforeModules.Contains($package)){continue}
        $installed=Installed $package;$copy=Join-Path $backup ($package+'.apk')
        [void](Adb @('pull',$installed.path,$copy))
        if((Sha $copy) -cne $installed.sha256 -or (Certificate $copy) -cne $certificate){throw 'Existing APK identity differs; no uninstall/data reset'}
    }
    foreach($artifactRow in $inputs){
        Idle;[void](Adb @('install','-r','-t',$artifactRow.file))
        if((Installed $artifactRow.package).sha256 -cne $artifactRow.sha256){throw 'Installed APK differs from common artifact'}
        $result.installed+= $artifactRow.package;Save
    }
    Idle;$after=Checkpoint 'protected-after';$afterModules=Modules
    if($before -cne $after){throw 'Protected data changed; preserve evidence, never restore old ciphertext'}
    foreach($package in $beforeModules.Keys){if($package -notin @('org.mcrf.engines.steer','org.mcrf.engines.wg') -and $beforeModules[$package] -cne $afterModules[$package]){throw 'Unrelated module changed'}}
    foreach($package in $afterModules.Keys){if(!$beforeModules.Contains($package) -and $package -notin @('org.mcrf.engines.steer','org.mcrf.engines.wg')){throw 'Unexpected new module'}}
    $result.protectedUnchanged=$true;$result.unrelatedModulesUnchanged=$true;$result.state='INSTALLED_NOT_RUNTIME_ACCEPTED'
}catch{$result.state='FAILED_PRESERVED';$result.error=$_.Exception.Message;throw}
finally{
    $result.finishedUtc=[DateTime]::UtcNow.ToString('o');Save
    if($job){$current=ReadJson $currentPath;if($current.jobs.device -ceq $job){$current.jobs.device=$null;[IO.File]::WriteAllText($currentPath,($current|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))}}
    Write-Output "P1_INSTALL=$($result.state);RECEIPT=$(Join-Path $evidence 'receipt.json')"
}
