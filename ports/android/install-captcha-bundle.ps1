param([Parameter(Mandatory=$true)][string]$BundleReceipt,[Parameter(Mandatory=$true)][ValidatePattern('^192\.168\.7\.234:[0-9]{4,5}$')][string]$Device)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$allowed=[IO.Path]::GetFullPath("$root/work/ports-test-2026-10-14")
$receiptPath=[IO.Path]::GetFullPath($BundleReceipt)
if(!$receiptPath.StartsWith($allowed+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Get-Item $receiptPath).LinkType){throw 'Use an owned packaging receipt'}
$bundle=Get-Content -LiteralPath $receiptPath -Raw|ConvertFrom-Json
if($bundle.state -cne 'PASS_PACKAGING_NOT_RUNTIME' -or $bundle.snapshot -notmatch '^android-integration-[a-f0-9]{32}$' -or @($bundle.files).Count -ne 17){throw 'Invalid bundle verification'}
$frozen="$root/work/$($bundle.snapshot)"
$build=Get-Content "$frozen/build-result.json" -Raw|ConvertFrom-Json
if($build.exitCode -ne 0 -or !$build.sourceStable){throw 'Build is not completed/stable'}
$lease=Get-Content "$root/ports/android/coordination/device-lease.json" -Raw|ConvertFrom-Json
if($lease.device -cne $Device -or $lease.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $lease.state -cne 'occupied_main_fixture_acceptance'){throw 'Main physical lease missing'}
$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
$sdk='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
function Adb([string[]]$argv){$output=(& $adb -s $Device @argv 2>&1|Out-String).Trim();if($LASTEXITCODE -ne 0){throw "ADB failed: $output"};return $output}
function Idle {
    if((Adb @('shell','getprop','ro.product.model')) -cne 'SM-F741B'){throw 'Physical identity mismatch'}
    foreach($package in @('org.mcrf.client','org.steer.prototype','com.wdtt.plus','csqtt.quic.amurcanov')){
        $services=Adb @('shell','dumpsys','activity','services',$package)
        if($services -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw "Active service: $package; no stop/retry"}
    }
    if((Adb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (Adb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Screen unavailable; no wake/unlock'}
    if((Adb @('shell','dumpsys','connectivity')) -match '(?m)^\s+NetworkAgentInfo\{[^\r\n]*\bTransports: VPN\b'){throw 'VPN network still active; no stop/retry'}
    if((Adb @('shell','dumpsys','activity','activities')) -match '(?m)^\s+topResumedActivity=.*(?:com\.wdtt\.plus|csqtt\.quic\.amurcanov)/'){throw 'Human is in another VPN app; no foreground switch'}
}
function Installed([string]$package,[switch]$Optional){
    $output=(& $adb -s $Device shell pm path $package 2>&1|Out-String).Trim()
    $pathExit=$LASTEXITCODE
    if(!$output -and $Optional -and $pathExit -in @(0,1)){
        $listed=Adb @('shell','pm','list','packages',$package)
        if($listed -match ('(?m)^package:'+ [regex]::Escape($package)+'\s*$')){throw 'Package exists but APK path unavailable'}
        return $null
    }
    if($pathExit -ne 0){throw "Installed APK path check failed: $package"}
    if($output -notmatch '^package:(/data/app/[^\r\n]+/base.apk)$'){throw "Unexpected package path: $package"}
    $sha=Adb @('shell','sha256sum',$Matches[1])
    if($sha -notmatch '^([a-f0-9]{64})  '){throw 'Installed APK hash missing'}
    return $Matches[1]
}
function Checkpoint {
    $output=Adb @('shell','am','instrument','-w','-r','-e','mode','protected-hashes','org.mcrf.client.test/org.mcrf.client.ReleaseSmokeInstrumentation')
    if($output -notmatch 'INSTRUMENTATION_CODE: -1' -or $output -notmatch 'INSTRUMENTATION_RESULT: passed=true' -or $output -notmatch 'INSTRUMENTATION_RESULT: protectedFilesUnchanged=true'){throw 'Protected-file checkpoint failed'}
    $match=[regex]::Match($output,'(?s)INSTRUMENTATION_RESULT: protectedFilesBefore=(.*?)\r?\n\r?\n')
    if(!$match.Success -or $match.Groups[1].Value -notmatch 'shared_prefs/identity.xml=' -or $match.Groups[1].Value -notmatch 'preferences.enc='){throw 'Checkpoint runner lacks full settings/identity coverage'}
    return $match.Groups[1].Value.Trim()
}
$rows=@($bundle.files|Where-Object {$_.abi -ceq 'arm64-v8a'})
if($rows.Count -ne 5 -or (($rows.kind|Sort-Object) -join ',') -cne 'app,csqtt,csqtt25,qwdtt,wdtt'){throw 'Unexpected device selection'}
foreach($row in $rows){
    $path=[IO.Path]::GetFullPath($row.path)
    if(!$path.StartsWith([IO.Path]::GetFullPath($frozen)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -cne $row.sha256){throw 'Verified APK changed'}
}
$runner="$frozen/ports/android/app/build/outputs/apk/androidTest/debug/app-release-smoke-androidTest.apk"
$runnerRows=Get-Content "$frozen/test-runner-artifacts.json" -Raw|ConvertFrom-Json
$runnerRow=@($runnerRows|Where-Object {$_.path -match 'app-release-smoke-androidTest\.apk$'})
if($runnerRow.Count -ne 1 -or (Get-FileHash $runner).Hash.ToLowerInvariant() -cne $runnerRow[0].sha256){throw 'Runner artifact changed'}
$signer=(& "$sdk/apksigner.bat" verify --print-certs $runner 2>&1|Out-String)
if($LASTEXITCODE -ne 0 -or $signer -notmatch 'Signer #1 certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Unexpected runner signer'}
$manifest=(& "$sdk/aapt2.exe" dump xmltree $runner --file AndroidManifest.xml 2>&1|Out-String)
if($LASTEXITCODE -ne 0 -or $manifest -notmatch 'ReleaseSmokeInstrumentation' -or $manifest -notmatch 'targetPackage.*org\.mcrf\.client"'){throw 'Wrong runner target'}
$evidence=Join-Path $allowed ('captcha-device-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $evidence|Out-Null
function Save([string]$name,$value){$path="$evidence/$name.json";if(Test-Path $path){throw 'No receipt overwrite'};[IO.File]::WriteAllText($path,($value|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))}
$result=[ordered]@{snapshot=$bundle.snapshot;device=$Device;installed=@();passed=$false;protectedUnchanged=$false;unrelatedModulesUnchanged=$false;utc=[DateTime]::UtcNow.ToString('o')}
try {
    Idle
    $unrelated=[ordered]@{};foreach($kind in @('singbox','steer','openflux')){$unrelated[$kind]=Installed "org.mcrf.engines.$kind" -Optional}
    $beforeApks=[ordered]@{};foreach($row in $rows){$beforeApks[$row.package]=Installed $row.package -Optional}
    Save 'preinstall-apks' ([ordered]@{unrelated=$unrelated;upgraded=$beforeApks})
    if((Adb @('install','-r','-t',$runner)) -notmatch '(?m)^Success\s*$'){throw 'Runner update failed'}
    $before=Checkpoint;Save 'protected-before' $before
    foreach($row in $rows){
        Idle
        if((Adb @('install','-r',$row.path)) -notmatch '(?m)^Success\s*$'){throw 'APK update failed; no uninstall/retry'}
        if((Installed $row.package) -cne $row.sha256){throw 'Installed APK differs from verified bundle'}
        $result.installed+=@([ordered]@{package=$row.package;sha256=$row.sha256;bytes=$row.bytes})
    }
    $after=Checkpoint;Save 'protected-after' $after
    $result.protectedUnchanged=$before -ceq $after
    $now=[ordered]@{};foreach($kind in @('singbox','steer','openflux')){$now[$kind]=Installed "org.mcrf.engines.$kind" -Optional}
    $result.unrelatedModulesUnchanged=($now|ConvertTo-Json -Compress) -ceq ($unrelated|ConvertTo-Json -Compress)
    if(!$result.protectedUnchanged -or !$result.unrelatedModulesUnchanged){throw 'Post-install state changed; inspect durable receipts before continuing'}
    $result.passed=$true
} finally {Save 'result' $result;Write-Output "DEVICE_BUNDLE_RECEIPT=$evidence/result.json"}
Write-Output 'PASS installed exact app+four external modules; full protected state and unrelated modules unchanged. Provider CAPTCHA/traffic not yet verified.'
