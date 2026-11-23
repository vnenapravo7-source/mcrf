param([Parameter(Mandatory=$true)][string]$Snapshot,[string]$Device='192.168.100.234:45825',[switch]$Tour,[switch]$UsageColors)
$ErrorActionPreference='Stop'
if($Tour -and $UsageColors){throw 'Select one isolated UI scenario only'}
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$frozen=[IO.Path]::GetFullPath((Join-Path $root $Snapshot))
if(!$frozen.StartsWith((Join-Path $root 'work')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($frozen) -notmatch '^android-integration-[a-f0-9]{32}$'){throw 'Not an integration snapshot'}
$build=Get-Content -LiteralPath (Join-Path $frozen 'build-result.json') -Raw|ConvertFrom-Json
if(!$build.firstLaunchFixture -or !$build.sourceStable -or $build.exitCode -ne 0){throw 'Requires green, frozen, isolated first-launch build'}
$lease=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/device-lease.json') -Raw|ConvertFrom-Json
if($lease.device -ne $Device -or $lease.owner_thread -ne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $lease.state -ne 'occupied_main_fixture_acceptance'){throw 'Main lease not held'}
$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$sdk='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
function DeviceText([string[]]$Arguments){$text=(& $adb -s $Device @Arguments 2>&1|Out-String).Trim();if($LASTEXITCODE -ne 0){throw "Device command failed: $text"};return $text}
function PackagePath([string]$Package){$result=(& $adb -s $Device shell pm path --user 0 $Package 2>&1|Out-String).Trim();$status=$LASTEXITCODE;if(!$result -and $status -in @(0,1)){return ''};if($status -ne 0 -or $result -notmatch '^package:(/data/app/[^\r\n]+/base.apk)$'){throw 'Unexpected APK path or package query failure'};return $Matches[1]}
function Identity([string]$Package){$path=PackagePath $Package;if(!$path){throw 'Package missing'};$hash=DeviceText @('shell','sha256sum',$path);$dump=DeviceText @('shell','pm','list','packages','-U','--user','0',$Package);if($dump -notmatch ("(?m)^package:"+[regex]::Escape($Package)+" uid:(\d+)\s*$")){throw 'Package UID missing'};return "$($Matches[1])|$hash"}
function MainCheckpoint {
    $identity=Identity 'org.mcrf.client'
    # Read only the human application's protected hashes; never clear or replace it.
    $hash=DeviceText @('shell','run-as','org.mcrf.client','sha256sum','files/connections.enc','files/routing.enc','files/preferences.enc','shared_prefs/MainActivity.xml')
    if([regex]::Matches($hash,'(?m)^[0-9a-f]{64}  ').Count -ne 4){throw 'Main protected checkpoint incomplete'}
    return "$identity`n$hash"
}
$power=DeviceText @('shell','dumpsys','power');$policy=DeviceText @('shell','dumpsys','window','policy')
if($power -notmatch 'mWakefulness=Awake' -or $policy -notmatch '(?m)^\s+showing=false\s*$'){throw 'Screen not available; no wake/unlock'}
foreach($package in @('org.mcrf.client','org.steer.prototype')){
    $services=DeviceText @('shell','dumpsys','activity','services',$package)
    if($services -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'An application service is active; nothing will be stopped'}
}
$packages=@('org.mcrf.client.onboardingfixture','org.mcrf.client.onboardingfixture.test')
$apks=@((Join-Path $frozen 'ports/android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk'),(Join-Path $frozen 'ports/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
$identities=@{};$installed=@();$expected=@{}
for($i=0;$i -lt 2;$i++){
    if(PackagePath $packages[$i]){throw 'Fixture already exists; refusing overwrite or reset'}
    $badging=(& "$sdk/aapt2.exe" dump badging $apks[$i] 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $badging -notmatch ("(?m)^package: name='"+[regex]::Escape($packages[$i])+"'")){throw 'Unexpected fixture APK package'}
    if($i -eq 1){$xml=(& "$sdk/aapt2.exe" dump xmltree $apks[$i] --file AndroidManifest.xml 2>&1|Out-String);if($LASTEXITCODE -ne 0 -or $xml -notmatch 'targetPackage.*org\.mcrf\.client\.onboardingfixture"'){throw 'Unexpected instrumentation target'}}
    $signer=(& "$sdk/apksigner.bat" verify --print-certs $apks[$i] 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $signer -notmatch 'Signer #1 certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Unexpected fixture signer'}
    $expected[$packages[$i]]=(Get-FileHash -LiteralPath $apks[$i] -Algorithm SHA256).Hash.ToLowerInvariant()
}
$before=MainCheckpoint
$run=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
$evidence=Join-Path $root ('work/ports-test-2026-10-14/first-launch-'+$run)
New-Item -ItemType Directory -Path $evidence|Out-Null
$passed=$false;$failure='';$cleanup=@()
try {
    for($i=0;$i -lt 2;$i++){
        $response=DeviceText @('install','-t',$apks[$i]);if($response -notmatch '(?m)^Success\s*$'){throw 'Fixture installation failed'}
        $identity=Identity $packages[$i]
        if($identity.Split('|')[0] -eq $before.Split('|')[0] -or !$identity.Contains($expected[$packages[$i]])){throw 'Fixture UID/hash is not isolated'}
        $identities[$packages[$i]]=$identity;$installed+=$packages[$i]
    }
    $mode=if($UsageColors){'usage-colors'}elseif($Tour){'docs-tour'}else{'primary-choice'}
    $output=DeviceText @('shell','am','instrument','-w','-r','-e','mode',$mode,'org.mcrf.client.onboardingfixture.test/org.mcrf.client.DeviceSmokeInstrumentation')
    [IO.File]::WriteAllText((Join-Path $evidence 'primary-choice.log'),$output)
    if($output -notmatch 'INSTRUMENTATION_CODE: -1' -or $output -notmatch 'INSTRUMENTATION_RESULT: passed=1\b' -or $output -match 'FAIL|SKIP|Process crashed'){throw 'Isolated UI acceptance failed; see retained log'}
    if($Tour){
        if($output -notmatch 'DOCS_TOUR_FRAMES=\d+' -or $output -notmatch 'PASS real native fades'){throw 'Tour capture incomplete'}
        $null=DeviceText @('pull','/storage/emulated/0/Android/data/org.mcrf.client.onboardingfixture/cache/docs-tour',$evidence)
    }elseif($UsageColors){
        if($output -notmatch 'PASS fresh use chooser precedes core choice' -or $output -notmatch 'PASS proxy-only hides TUN fields' -or $output -notmatch 'PASS both-theme colors apply/reset'){throw 'Use/color scenario incomplete'}
    }else{
        if($output -notmatch 'PASS both core cards' -or $output -notmatch 'PASS inspection leaves core selection'){throw 'Chooser checks incomplete'}
        $null=DeviceText @('pull','/storage/emulated/0/Android/data/org.mcrf.client.onboardingfixture/cache/mcrf-primary-chooser.png',(Join-Path $evidence 'primary-chooser.png'))
    }
    $passed=$true
}catch{$failure=$_.Exception.Message}
finally {
    [array]::Reverse($installed)
    foreach($package in $installed){
        try {
            if($package -notin $packages -or (Identity $package) -cne $identities[$package]){throw 'Fixture identity changed; refusing deletion'}
            $null=DeviceText @('uninstall',$package)
            if(PackagePath $package){throw 'Fixture remains installed'}
            $cleanup+="Removed only fresh $package"
        }catch{$cleanup+=$_.Exception.Message;$passed=$false}
    }
    $after=MainCheckpoint
    if($after -cne $before){$passed=$false;$failure+=' Main protected state changed'}
    [IO.File]::WriteAllText((Join-Path $evidence 'receipt.json'),([ordered]@{snapshot=$frozen;passed=$passed;failure=$failure;main_unchanged=($after -ceq $before);main_before=$before;main_after=$after;cleanup=$cleanup;account_actions=$false;vpn_actions=$false}|ConvertTo-Json -Depth 4))
}
Write-Output "EVIDENCE=$evidence"
if(!$passed){throw "First-launch acceptance incomplete: $failure $($cleanup -join '; ')"}
Write-Output 'PASS fresh first-launch chooser; main APK, UID and all four protected files unchanged; temporary fixture packages removed'
