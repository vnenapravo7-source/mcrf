param([Parameter(Mandatory=$true)][ValidatePattern('^android-integration-[a-f0-9]{32}$')][string]$Snapshot,[Parameter(Mandatory=$true)][string]$PreviousInstallReceipt,[ValidatePattern('^192\.168\.7\.234:[1-9][0-9]{0,4}$')][string]$CurrentDevice='',[ValidateSet(10008,10009)][int]$ExpectedVersionCode=10008)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$previousPath=[IO.Path]::GetFullPath($PreviousInstallReceipt)
if(!$previousPath.StartsWith("$root\work\ports-test-2026-10-14\",[StringComparison]::OrdinalIgnoreCase)){throw 'Owned previous install receipt required'}
$previous=Get-Content $previousPath -Raw|ConvertFrom-Json
if(!$previous.passed -or !$previous.protectedUnchanged){throw 'Previous install not verified'}
$device=if($CurrentDevice){$CurrentDevice}else{$previous.device}
$lease=Get-Content "$root/ports/android/coordination/device-lease.json" -Raw|ConvertFrom-Json
if($lease.device -cne $device -or $lease.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $lease.state -cne 'occupied_main_fixture_acceptance'){throw 'Main lease missing'}
$frozen="$root/work/$Snapshot"
$build=Get-Content "$frozen/build-result.json" -Raw|ConvertFrom-Json
if($build.exitCode -ne 0 -or !$build.sourceStable -or ':app:assembleRelease' -notin $build.task){throw 'Completed immutable R8 build required'}
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
$sdk='C:/Users/dieonfire/AppData/Local/Android/Sdk'
$adb="$sdk/platform-tools/adb.exe"
function Adb([string[]]$argv){$out=(& $adb -s $device @argv 2>&1|Out-String).Trim();if($LASTEXITCODE -ne 0){throw "ADB failed: $out"};return $out}
function Idle {
    if((Adb @('shell','am','get-current-user')) -cne '0'){throw 'Only the already-authorized owner user0; no profile access or user switching'}
    if((Adb @('shell','getprop','ro.product.model')) -cne 'SM-F741B'){throw 'Physical identity mismatch'}
    if((Adb @('shell','getprop','ro.serialno')) -cne 'R5CX73QYN3D'){throw 'Previously authorized physical serial mismatch; no endpoint substitution'}
    if((Adb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (Adb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Screen unavailable; no wake/unlock'}
    foreach($package in @('org.mcrf.client','org.steer.prototype','com.wdtt.plus','csqtt.quic.amurcanov')){if((Adb @('shell','dumpsys','activity','services',$package)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'Active service; no stop or install'}}
    if((Adb @('shell','dumpsys','connectivity')) -match '(?m)^\s+NetworkAgentInfo\{[^\r\n]*\bTransports:\s+[A-Z_|]*\bVPN\b'){throw 'Active VPN; no interference'}
    if((Adb @('shell','dumpsys','activity','activities')) -match '(?m)^\s+topResumedActivity=.*(?:com\.wdtt\.plus|csqtt\.quic\.amurcanov)/'){throw 'Human in other VPN app'}
}
function Installed([string]$package){$remote=Adb @('shell','pm','path','--user','0',$package);if($remote -notmatch '^package:(/data/app/[^\r\n]+/base.apk)$'){throw "Unexpected package APK path: $package"};$out=Adb @('shell','sha256sum',$Matches[1]);if($out -notmatch '^([a-f0-9]{64})  '){throw 'Installed APK SHA missing'};return $Matches[1]}
function Modules {
    # The default list also attempts unrelated locked profiles. Never retry that
    # denied inventory; explicitly inspect only our already-authorized user0.
    $packages=Adb @('shell','pm','list','packages','--user','0','org.mcrf.engines')
    $inventory=[ordered]@{}
    foreach($line in ($packages -split '\r?\n'|Sort-Object)){if($line -notmatch '^package:(org\.mcrf\.engines(?:\.[a-z0-9]+)?)$'){throw 'Unexpected module package inventory'};$package=$Matches[1];$inventory[$package]=Installed $package}
    if($inventory.Count -lt 7){throw 'Expected installed external module inventory missing'}
    return $inventory
}
function Checkpoint {
    $out=Adb @('shell','am','instrument','-w','-r','-e','mode','protected-hashes','org.mcrf.client.test/org.mcrf.client.ReleaseSmokeInstrumentation')
    if($out -notmatch 'INSTRUMENTATION_CODE: -1' -or $out -notmatch 'passed=true' -or $out -notmatch 'protectedFilesUnchanged=true'){throw 'Strong checkpoint failed'}
    $match=[regex]::Match($out,'(?s)INSTRUMENTATION_RESULT: protectedFilesBefore=(.*?)\r?\n\r?\n')
    if(!$match.Success -or $match.Groups[1].Value -notmatch 'shared_prefs/identity.xml=' -or $match.Groups[1].Value -notmatch 'preferences.enc='){throw 'Incomplete settings/identity checkpoint'}
    return $match.Groups[1].Value.Trim()
}
function Signer([string]$path){$out=(& "$sdk/build-tools/36.0.0/apksigner.bat" verify --print-certs $path 2>&1|Out-String);if($LASTEXITCODE -ne 0 -or $out -notmatch 'Signer #1 certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Unexpected APK signer'}}
$app="$frozen/ports/android/app/build/outputs/apk/release/app-arm64-v8a-release.apk"
$appHash=(Get-FileHash $app).Hash.ToLowerInvariant();Signer $app
$manifest=(& "$sdk/build-tools/36.0.0/aapt2.exe" dump badging $app 2>&1|Out-String)
$expectedVersion=if($ExpectedVersionCode -eq 10009){'0.1.0-preview.20261017.5'}else{'0.1.0-preview.20261017.4'}
if($LASTEXITCODE -ne 0 -or $manifest -notmatch ("package: name='org.mcrf.client' versionCode='"+$ExpectedVersionCode+"'") -or $manifest -notmatch ("versionName='"+[regex]::Escape($expectedVersion)+"'")){throw 'Wrong app package/version'}
$zip=[IO.Compression.ZipFile]::OpenRead($app);try{if(@($zip.Entries|Where-Object {$_.FullName -match '^lib/|\.(so|aar)$'}).Count){throw 'Native payload in base APK'}}finally{$zip.Dispose()}
$runner="$frozen/ports/android/app/build/outputs/apk/androidTest/debug/app-release-smoke-androidTest.apk"
$runnerRows=Get-Content "$frozen/test-runner-artifacts.json" -Raw|ConvertFrom-Json
$row=@($runnerRows|Where-Object {$_.path -match 'app-release-smoke-androidTest\.apk$'})
if($row.Count -ne 1 -or (Get-FileHash $runner).Hash.ToLowerInvariant() -cne $row[0].sha256){throw 'Runner SHA mismatch'}
Signer $runner
$evidence="$root/work/ports-test-2026-10-14/priority-app-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $evidence|Out-Null
function Save([string]$name,$value){$path="$evidence/$name.json";if(Test-Path $path){throw 'Receipt overwrite forbidden'};[IO.File]::WriteAllText($path,($value|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))}
$result=[ordered]@{snapshot=$Snapshot;device=$device;passed=$false;installed=@();protectedUnchanged=$false;unrelatedModulesUnchanged=$false;utc=[DateTime]::UtcNow.ToString('o')}
try{
    Idle
    $oldApp=@($previous.installed|Where-Object {$_.package -ceq 'org.mcrf.client'})
    if($oldApp.Count -ne 1 -or (Installed 'org.mcrf.client') -cne $oldApp[0].sha256){throw 'Current app differs from verified previous install; do not overwrite'}
    $modules=Modules;Save 'modules-before' $modules
    if((Adb @('install','-r','-t',$runner)) -notmatch '(?m)^Success\s*$'){throw 'Runner install failed; no retry'}
    $before=Checkpoint;Save 'protected-before' $before
    Idle
    if((Get-FileHash $app).Hash.ToLowerInvariant() -cne $appHash){throw 'App bytes changed'}
    if((Adb @('install','-r',$app)) -notmatch '(?m)^Success\s*$'){throw 'App update failed; no uninstall/retry'}
    $result.installed=@([ordered]@{package='org.mcrf.client';sha256=$appHash;bytes=(Get-Item $app).Length})
    if((Installed 'org.mcrf.client') -cne $appHash){throw 'Installed app SHA mismatch'}
    $after=Checkpoint;Save 'protected-after' $after
    $modulesAfter=Modules;Save 'modules-after' $modulesAfter
    $result.protectedUnchanged=$before -ceq $after
    $result.unrelatedModulesUnchanged=($modules|ConvertTo-Json -Compress) -ceq ($modulesAfter|ConvertTo-Json -Compress)
    if(!$result.protectedUnchanged -or !$result.unrelatedModulesUnchanged){throw 'Post-install drift; inspect durable evidence, never restore automatically'}
    $result.passed=$true
}finally{Save 'result' $result;Write-Output "PRIORITY_APP_RECEIPT=$evidence/result.json"}
Write-Output 'PASS exact new minified app installed; full fresh protected state and all external modules unchanged. UI/traffic gates still pending.'
