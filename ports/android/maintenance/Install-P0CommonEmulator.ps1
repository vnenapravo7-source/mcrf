param([Parameter(Mandatory=$true)][string]$Snapshot)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskBuild=[IO.Path]::GetFullPath((Join-Path $taskRoot $Snapshot))
if(!$taskBuild.StartsWith((Join-Path $taskRoot 'work')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($taskBuild) -cnotmatch '^android-integration-[0-9a-f]{32}$'){throw 'Frozen common snapshot required'}
$taskProof=Get-Content -LiteralPath (Join-Path $taskBuild 'p0-artifacts.json') -Raw|ConvertFrom-Json
if($taskProof.state -cne 'PASS' -or !$taskProof.testSignedOnly -or $taskProof.deviceExecuted){throw 'New common host artifact gate missing'}
$taskLeasePath=Join-Path $taskRoot 'ports/android/coordination/emulator-lease.json'
$taskLease=Get-Content -LiteralPath $taskLeasePath -Raw|ConvertFrom-Json
if($taskLease.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $taskLease.device -cne 'emulator-5586' -or $taskLease.state -cne 'booting_p0_common_readonly'){throw 'Fresh sole-main common emulator lease required'}
$taskProcesses=@(Get-CimInstance Win32_Process|Where-Object {$_.Name -eq 'emulator.exe' -and $_.ProcessId -eq $taskLease.pid -and $_.CommandLine -match 'mcrf-prerelease-clean' -and $_.CommandLine -match '-read-only' -and $_.CommandLine -match '-port 5586'})
if($taskProcesses.Count -ne 1){throw 'Owned read-only emulator process missing'}
$taskAdb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
function Adb([string[]]$Arguments){$taskText=(& $taskAdb -s emulator-5586 @Arguments 2>&1|Out-String);if($LASTEXITCODE){throw 'Owned emulator ADB failed; no reset or retry'};return $taskText.Trim()}
function Protected{
    $taskPaths=(Adb @('shell','run-as','org.mcrf.client','find','files','shared_prefs','-type','f')) -split '\r?\n'|Where-Object {$_ -match '^shared_prefs/[^\s]+$|^files/(?:mcrf-store-[0-9a-f-]{36}/)?(?:profile.enc|connections.enc|routing.enc|preferences.enc|active-profile.txt)(?:\.bak|\.new)?$'}|Sort-Object
    if(!$taskPaths){throw 'No protected baseline; do not invent or reset it'}
    return (($taskPaths|ForEach-Object {Adb @('shell','run-as','org.mcrf.client','sha256sum',$_)}) -join [Environment]::NewLine)
}
if((Adb @('shell','getprop','sys.boot_completed')) -cne '1' -or (Adb @('shell','getprop','ro.kernel.qemu')) -cne '1'){throw 'Owned emulator not booted'}
foreach($taskPackage in @('org.mcrf.client','org.steer.prototype')){
    if((Adb @('shell','dumpsys','activity','services',$taskPackage)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'Active service; no interruption'}
}
if((Adb @('shell','dumpsys','vpn_management')) -match 'Active vpn type: [01]\b'){throw 'Active VPN; no interruption'}
$taskEvidence=Split-Path -Parent $taskLease.launchReceipt
$taskBefore=Protected
[IO.File]::WriteAllText((Join-Path $taskEvidence 'protected-before-install.txt'),$taskBefore,[Text.UTF8Encoding]::new($false))
$taskPriorModules=[ordered]@{}
foreach($taskLine in ((Adb @('shell','pm','list','packages','org.mcrf.engines.')) -split '\r?\n'|Sort-Object)){
    if($taskLine -match '^package:(org\.mcrf\.engines\.[a-z0-9]+)$'){
        $taskName=$Matches[1];$taskPath=(Adb @('shell','pm','path',$taskName)) -replace '^package:',''
        if($taskPath -notmatch '^/data/app/[^\s]+/base.apk$'){throw 'Unexpected prior module path'}
        $taskPriorModules[$taskName]=(Adb @('shell','sha256sum',$taskPath)).Split(' ')[0]
    }
}
$taskApp=Join-Path $taskBuild 'ports/android/app/build/outputs/apk/debug/app-universal-debug.apk'
$taskRunner=Join-Path $taskBuild 'ports/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
$taskS3=Join-Path $taskBuild 'ports/android/engines/build/outputs/apk/s3bypass/release/engines-s3bypass-x86_64-release.apk'
foreach($taskPair in @(@($taskApp,@($taskProof.files|Where-Object {$_.kind -ceq 'app-debug'})[0]),@($taskS3,@($taskProof.files|Where-Object {$_.file -ceq 'engines-s3bypass-x86_64-release.apk'})[0]))){
    if((Get-FileHash -LiteralPath $taskPair[0] -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskPair[1].sha256){throw 'Common artifact changed after verification'}
}
$taskSignature=(& 'C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0/apksigner.bat' verify --print-certs $taskRunner 2>&1|Out-String)
if($LASTEXITCODE -or $taskSignature -notmatch 'certificate SHA-256 digest:\s*([0-9a-f]{64})' -or $Matches[1].ToLowerInvariant() -cne $taskProof.signer){throw 'Runner signer mismatch'}
foreach($taskFile in @($taskApp,$taskRunner,$taskS3)){
    if((Adb @('install','-r','-t',$taskFile)) -notmatch '(?m)^Success\s*$'){throw 'Same-signer common install failed; never uninstall/reset'}
}
$taskAfter=Protected
[IO.File]::WriteAllText((Join-Path $taskEvidence 'protected-after-install.txt'),$taskAfter,[Text.UTF8Encoding]::new($false))
$taskModulesUnchanged=$true
foreach($taskName in $taskPriorModules.Keys){
    $taskPath=(Adb @('shell','pm','path',$taskName)) -replace '^package:',''
    if($taskPath -notmatch '^/data/app/[^\s]+/base.apk$' -or (Adb @('shell','sha256sum',$taskPath)).Split(' ')[0] -cne $taskPriorModules[$taskName]){$taskModulesUnchanged=$false}
}
$taskResult=[ordered]@{state=$(if($taskBefore -ceq $taskAfter -and $taskModulesUnchanged){'PASS'}else{'FAILED_PRESERVED'});snapshot=$taskBuild;device='emulator-5586';profilesAndSettingsUnchanged=($taskBefore -ceq $taskAfter);priorModulesUnchanged=$taskModulesUnchanged;priorModuleCount=$taskPriorModules.Count;addedModule='s3bypass/x86_64';runtimeExecuted=$false;notificationGrantedByAgent=$false;physicalPhoneTouched=$false;scope='Common read-only disposable overlay install only; no profile reset, UI/grant/native/TUN/provider run or public replacement'}
[IO.File]::WriteAllText((Join-Path $taskEvidence 'install.json'),($taskResult|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$taskLease.state=if($taskResult.state -ceq 'PASS'){'occupied_p0_common_acceptance'}else{'blocked_p0_install_state_review'}
[IO.File]::WriteAllText($taskLeasePath,($taskLease|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
if($taskResult.state -cne 'PASS'){throw 'Protected state changed; inspect fresh receipts, do not restore old data'}
Write-Output "P0_EMULATOR_INSTALL=PASS;PROTECTED_UNCHANGED=true;PRIOR_MODULES=$($taskPriorModules.Count);ADDED_S3=true;NO_GRANT=true"
