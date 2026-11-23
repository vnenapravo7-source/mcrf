param([Parameter(Mandatory=$true)][string]$RunnerReceipt,[Parameter(Mandatory=$true)][string]$InstallReceipt,[ValidateSet('captcha-r8-notification','direct-native')][string]$Mode='captcha-r8-notification',[string]$DirectVectors='')
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$installPath=[IO.Path]::GetFullPath($InstallReceipt)
$runnerPath=[IO.Path]::GetFullPath($RunnerReceipt)
if(!$installPath.StartsWith("$root\work\ports-test-2026-10-14\",[StringComparison]::OrdinalIgnoreCase) -or !$runnerPath.StartsWith("$root\work\android-runner-artifacts-",[StringComparison]::OrdinalIgnoreCase)){throw 'Requires owned exact build receipts'}
$install=Get-Content $installPath -Raw|ConvertFrom-Json
$runner=Get-Content $runnerPath -Raw|ConvertFrom-Json
if(!$install.passed -or !$install.protectedUnchanged -or !$runner.releaseSmoke -or $runner.appApkBuilt){throw 'Wrong install/runner provenance'}
$device=$install.device
$lease=Get-Content "$root/ports/android/coordination/device-lease.json" -Raw|ConvertFrom-Json
if($lease.device -cne $device -or $lease.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $lease.state -cne 'occupied_main_fixture_acceptance'){throw 'Main lease required'}
$build=Get-Content "$root/work/$($install.snapshot)/build-result.json" -Raw|ConvertFrom-Json
if($build.exitCode -ne 0 -or !$build.sourceStable){throw 'Exact minified snapshot required'}
$map=Get-Content "$root/work/$($install.snapshot)/ports/android/app/build/outputs/mapping/release/mapping.txt" -Raw
$block=[regex]::Match($map,'(?ms)^org\.mcrf\.client\.CarrierChallenge -> ([^:\r\n]+):\r?\n.*?(?=^[^ #\r\n])')
if(!$block.Success){throw 'Registry map missing'}
function Member([string]$pattern){$names=@([regex]::Matches($block.Value,$pattern)|ForEach-Object {$_.Groups[1].Value}|Sort-Object -Unique);if($names.Count -ne 1){throw 'Mapped registry member ambiguous'};return $names[0]}
$symbols=[ordered]@{registry=$block.Groups[1].Value;register=(Member 'registerBounded\(java.lang.String,java.lang.String,kotlin.jvm.functions.Function1,kotlin.jvm.functions.Function1,long,boolean,kotlin.jvm.functions.Function0\).* -> ([A-Za-z0-9_$]+)');remove=(Member 'void remove\(java.lang.String\).* -> ([A-Za-z0-9_$]+)');pending=(Member 'pendingManual\(\).* -> ([A-Za-z0-9_$]+)');requests=(Member 'ConcurrentHashMap requests -> ([A-Za-z0-9_$]+)')}
$mappedOwner='';$shows=@(@(foreach($line in ($map -split '\r?\n')){if($line -match '^[^ #].+ -> (.+):$'){$mappedOwner=$Matches[1]};if($line -match 'CarrierChallengeNotifications\.show\(android.content.Context,java.lang.String\).* -> ([A-Za-z0-9_$]+)$'){[pscustomobject]@{owner=$mappedOwner;method=$Matches[1]}}})|Sort-Object owner,method -Unique)
if($shows.Count -ne 1){throw 'Production notification map ambiguous'}
$symbols.showClass=$shows[0].owner;$symbols.show=$shows[0].method
$encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($symbols|ConvertTo-Json -Compress)))
$instrumentArgs=@('-e','mode',$Mode)
if($Mode -eq 'captcha-r8-notification'){$instrumentArgs+=@('-e','captchaMap',$encoded)}else{
    $vectorsPath=[IO.Path]::GetFullPath($DirectVectors)
    if(!$vectorsPath.StartsWith("$root\work\direct-android-inputs-",[StringComparison]::OrdinalIgnoreCase) -or (Get-Item $vectorsPath).Length -gt 65536){throw 'Only owned bounded route vectors'}
    $vectorText=Get-Content $vectorsPath -Raw;$fixture=$vectorText|ConvertFrom-Json
    $route="$root/work/$($install.snapshot)/ports/android/app/src/main/kotlin/org/mcrf/client/RouteRules.kt"
    if((Get-FileHash $route).Hash.ToLowerInvariant() -cne $fixture.routeRulesSha256){throw 'Production compiler changed; regenerate vectors from exact build'}
    $binder=[regex]::Match($map,'(?m)^org\.mcrf\.client\.ClientPrimaryPlatformBinder\$HostBinder -> ([^:\r\n]+):')
    if(!$binder.Success){throw 'Exact R8 platform binder mapping missing'}
    $instrumentArgs+=@('-e','platformBinderClass',$binder.Groups[1].Value,'-e','directVectors',[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($vectorText)))
}
$apk=Join-Path ([IO.Path]::GetDirectoryName($runnerPath)) 'runner.apk'
if((Get-FileHash $apk).Hash.ToLowerInvariant() -cne $runner.sha256){throw 'Runner APK changed'}
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
$sdk='C:/Users/dieonfire/AppData/Local/Android/Sdk'
$signer=(& "$sdk/build-tools/36.0.0/apksigner.bat" verify --print-certs $apk 2>&1|Out-String)
if($LASTEXITCODE -ne 0 -or $signer -notmatch 'Signer #1 certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Runner signer mismatch'}
$adb="$sdk/platform-tools/adb.exe"
function Adb([string[]]$argv){$out=(& $adb -s $device @argv 2>&1|Out-String).Trim();if($LASTEXITCODE -ne 0){throw "ADB diagnostic failed: $out"};return $out}
if((Adb @('shell','getprop','ro.product.model')) -cne 'SM-F741B'){throw 'Wrong physical device'}
if((Adb @('shell','am','get-current-user')) -cne '0'){throw 'Only authorized owner user0'}
if((Adb @('shell','dumpsys','power')) -notmatch 'mWakefulness=Awake' -or (Adb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Screen unavailable; no wake/unlock'}
foreach($package in @('org.mcrf.client','org.steer.prototype','com.wdtt.plus','csqtt.quic.amurcanov')){if((Adb @('shell','dumpsys','activity','services',$package)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'Active service; no stop'}}
if((Adb @('shell','dumpsys','connectivity')) -match '(?m)^\s+NetworkAgentInfo\{[^\r\n]*\bTransports: VPN\b'){throw 'Active VPN; no interference'}
foreach($row in $install.installed){$remote=Adb @('shell','pm','path',$row.package);if($remote -notmatch '^package:(/data/app/[^\r\n]+/base.apk)$'){throw 'Installed package missing'};$hash=Adb @('shell','sha256sum',$Matches[1]);if(!$hash.StartsWith($row.sha256+'  ',[StringComparison]::Ordinal)){throw 'Installed bundle changed'}}
$prefix=if($Mode -eq 'direct-native'){'direct-android-native'}else{'captcha-r8-notification'}
$evidence="$root/work/ports-test-2026-10-14/$prefix-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $evidence|Out-Null
$passed=$false
try{
    if((Adb @('install','-r','-t',$apk)) -notmatch '(?m)^Success\s*$'){throw 'Runner install failed; no retry'}
    $out=Adb (@('shell','am','instrument','-w','-r')+$instrumentArgs+@('org.mcrf.client.test/org.mcrf.client.ReleaseSmokeInstrumentation'))
    [IO.File]::WriteAllText("$evidence/output.log",$out,[Text.UTF8Encoding]::new($false))
    $baseline=Get-Content (Join-Path ([IO.Path]::GetDirectoryName($installPath)) 'protected-before.json') -Raw|ConvertFrom-Json
    $after=[regex]::Match($out,'(?s)INSTRUMENTATION_RESULT: protectedFilesAfter=(.*?)\r?\n\r?\n')
    $before=[regex]::Match($out,'(?s)INSTRUMENTATION_RESULT: protectedFilesBefore=(.*?)\r?\n\r?\n')
    # A later test must preserve its fresh baseline, not assume the human has
    # never edited their catalogue since installation. Record any earlier drift.
    $installBaselineEqual=$before.Success -and $before.Groups[1].Value.Trim() -ceq $baseline
    $passed=$out -match 'INSTRUMENTATION_RESULT: passed=true' -and $out -match 'INSTRUMENTATION_CODE: -1' -and $out -match 'protectedFilesUnchanged=true' -and $before.Success -and $after.Success -and $after.Groups[1].Value.Trim() -ceq $before.Groups[1].Value.Trim()
    if(!$passed){throw "Actual CAPTCHA notification gate failed; inspect $evidence/output.log"}
}finally{
    [IO.File]::WriteAllText("$evidence/receipt.json",([ordered]@{snapshot=$install.snapshot;runner=$runner.snapshot;mode=$Mode;passed=$passed;installBaselineEqual=$installBaselineEqual;appApkReplaced=$false;scope=if($Mode -eq 'direct-native'){'Exact Android arm64/native installed external core,24owned loopback HTTP routing/counterchecks; notTUN/Vivo/Internet/UDP/leaks'}else{'Actual exact-R8 production notification+owned request cancellation; no browser/provider/VPN/network or settings writes'};mapSha256=(Get-FileHash "$root/work/$($install.snapshot)/ports/android/app/build/outputs/mapping/release/mapping.txt").Hash.ToLowerInvariant()}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    Write-Output "R8_NATIVE_GATE_RECEIPT=$evidence/receipt.json"
}
