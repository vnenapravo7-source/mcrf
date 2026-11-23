param([switch]$UiRemainder,[switch]$RepairedRunner)
$ErrorActionPreference='Stop'
if($args.Count){throw 'No arguments accepted: current common P0 only'}
if($UiRemainder -and $RepairedRunner){throw 'Distinct recorded passes cannot be combined or repeated'}
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskSnapshot='android-integration-a6c52ec6f97149f4835a629f1a450f43'
$taskBuild=Join-Path $taskRoot "work/$taskSnapshot"
$taskRunnerBuild=if($RepairedRunner){Join-Path $taskRoot 'work/android-runner-artifacts-91b3ec9614d64e3aa2b136f24e33a488'}else{$taskBuild}
$taskRunnerFile=Join-Path $taskRunnerBuild $(if($RepairedRunner){'runner.apk'}else{'ports/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'})
$taskAdb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$taskDevice='emulator-5586'
$taskLease=Get-Content -LiteralPath (Join-Path $taskRoot 'ports/android/coordination/emulator-lease.json') -Raw|ConvertFrom-Json
if($taskLease.device -cne $taskDevice -or $taskLease.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $taskLease.state -cne 'occupied_p0_common_acceptance' -or $taskLease.notificationConsent -notmatch 'Human YES call_edc4728e9031432cbedb023258f28b56'){throw 'Current exclusive lease and fresh scoped consent required'}
$taskEvidence=Split-Path -Parent $taskLease.launchReceipt
$taskResultPath=Join-Path $taskEvidence $(if($RepairedRunner){'repaired-fixtures-run-receipt.json'}elseif($UiRemainder){'ui-remainder-receipt.json'}else{'domain-tun-receipt.json'})
if(Test-Path -LiteralPath $taskResultPath){throw 'Existing result: inspect it; never repeat a closed gate'}
$taskMutex=[Threading.Mutex]::new($false,'Local\MCRF-P0-Fixture-5586')
if(!$taskMutex.WaitOne(0)){throw 'Current gate already active; do not duplicate'}
function Adb([string[]]$Arguments){$taskValue=(& $taskAdb -s $taskDevice @Arguments 2>&1|Out-String);if($LASTEXITCODE){throw 'Owned emulator ADB failed; no reset or retry'};return $taskValue.Trim()}
function Idle{
    foreach($taskPackage in @('org.mcrf.client','org.steer.prototype')){
        if((Adb @('shell','dumpsys','activity','services',$taskPackage)) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'Active service; no interruption'}
    }
    if((Adb @('shell','dumpsys','vpn_management')) -match 'Active vpn type: [01]\b'){throw 'Active VPN; no interruption'}
}
function Protected{
    $taskPaths=(Adb @('shell','run-as','org.mcrf.client','find','files','shared_prefs','-type','f')) -split '\r?\n'|Where-Object {$_ -match '^shared_prefs/[^\s]+$|^files/(?:mcrf-store-[0-9a-f-]{36}/)?(?:profile.enc|connections.enc|routing.enc|preferences.enc|active-profile.txt)(?:\.bak|\.new)?$'}|Sort-Object
    if(!$taskPaths){throw 'Protected baseline missing; never reset'}
    return (($taskPaths|ForEach-Object {Adb @('shell','run-as','org.mcrf.client','sha256sum',$_)}) -join [Environment]::NewLine)
}
function Modules{
    $taskNames=(Adb @('shell','pm','list','packages','org.mcrf.engines.')) -split '\r?\n'|Where-Object {$_ -match '^package:org\.mcrf\.engines\.[a-z0-9]+$'}|Sort-Object
    if(!$taskNames){throw 'External modules missing'}
    return (($taskNames|ForEach-Object {$taskPackage=$_ -replace '^package:','';$taskPath=(Adb @('shell','pm','path',$taskPackage)) -replace '^package:','';if($taskPath -notmatch '^/data/app/[^\s]+/base.apk$'){throw 'Unexpected module path'};"$taskPackage="+(Adb @('shell','sha256sum',$taskPath)).Split(' ')[0]}) -join [Environment]::NewLine)
}
$taskResults=@();$taskPassed=$false;$taskBefore=$null;$taskModulesBefore=$null;$taskRunnerUpdated=$false
try{
    $taskProcesses=@(Get-CimInstance Win32_Process|Where-Object {$_.Name -eq 'emulator.exe' -and $_.ProcessId -eq $taskLease.pid -and $_.CommandLine -match 'mcrf-prerelease-clean' -and $_.CommandLine -match '-read-only' -and $_.CommandLine -match '-port 5586'})
    if($taskProcesses.Count -ne 1){throw 'Owned read-only emulator process missing'}
    if((Adb @('shell','getprop','sys.boot_completed')) -cne '1' -or (Adb @('shell','getprop','ro.kernel.qemu')) -cne '1' -or (Adb @('shell','getprop','ro.product.cpu.abi')) -cne 'x86_64'){throw 'Expected booted disposable x86_64 emulator'}
    $taskReceipt=Get-Content -LiteralPath (Join-Path $taskBuild 'build-result.json') -Raw|ConvertFrom-Json
    $taskArtifacts=Get-Content -LiteralPath (Join-Path $taskBuild 'p0-artifacts.json') -Raw|ConvertFrom-Json
    if($taskReceipt.exitCode -ne 0 -or !$taskReceipt.sourceStable -or $taskArtifacts.state -cne 'PASS' -or !$taskArtifacts.testSignedOnly){throw 'Common immutable artifact verification missing'}
    Idle
    $taskBefore=Protected;$taskModulesBefore=Modules
    if($RepairedRunner){
        $taskRunnerReceipt=Get-Content -LiteralPath (Join-Path $taskRunnerBuild 'build-result.json') -Raw|ConvertFrom-Json
        if($taskRunnerReceipt.exitCode -ne 0 -or !$taskRunnerReceipt.sourceStable){throw 'Repaired runner immutable build not complete/green'}
        $taskExport=Get-Content -LiteralPath (Join-Path $taskRunnerBuild 'artifact-receipt.json') -Raw|ConvertFrom-Json
        if($taskExport.appApkBuilt -or $taskExport.releaseSmoke -or [IO.Path]::GetFileName($taskExport.snapshot) -cne 'android-integration-bb6c59473dd64306b01424ba7c940dc0' -or (Get-FileHash -LiteralPath $taskRunnerFile -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskExport.sha256){throw 'Runner-only preserved export does not match original frozen build'}
        $taskOriginalManifest=Get-Content -LiteralPath (Join-Path $taskBuild 'source-manifest.json') -Raw|ConvertFrom-Json
        $taskRepairManifest=Get-Content -LiteralPath (Join-Path $taskRunnerBuild 'source-manifest.json') -Raw|ConvertFrom-Json
        $taskProductionCount=0
        foreach($taskRow in $taskOriginalManifest){
            if($taskRow.path -match '^ports/android/(app/src/main/|engine-spi/|engines/src/)'){
                $taskMatching=@($taskRepairManifest|Where-Object {$_.path -ceq $taskRow.path})
                if($taskMatching.Count -ne 1 -or $taskMatching[0].sha256 -cne $taskRow.sha256){throw 'Production input differs: do not use repair runner against old app'}
                $taskProductionCount++
            }
        }
        if($taskProductionCount -ne 447){throw 'Unexpected production-input boundary'}
        $taskMainHash=(Get-FileHash -LiteralPath (Join-Path $taskBuild 'ports/android/app/build/outputs/apk/debug/app-universal-debug.apk') -Algorithm SHA256).Hash.ToLowerInvariant()
        $taskMainPath=(Adb @('shell','pm','path','org.mcrf.client')) -replace '^package:',''
        if($taskMainPath -notmatch '^/data/app/[^\s]+/base.apk$' -or (Adb @('shell','sha256sum',$taskMainPath)).Split(' ')[0] -cne $taskMainHash){throw 'Installed base app changed; no runner update'}
        $env:JAVA_HOME='C:/Program Files/Java/jdk-17'
        $taskSigner=(& 'C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0/apksigner.bat' verify --print-certs $taskRunnerFile 2>&1|Out-String)
        if($LASTEXITCODE -or $taskSigner -notmatch 'certificate SHA-256 digest:\s*([0-9a-f]{64})' -or $Matches[1].ToLowerInvariant() -cne $taskArtifacts.signer){throw 'Repair runner signer mismatch'}
        if((Adb @('install','-r','-t',$taskRunnerFile)) -notmatch '(?m)^Success\s*$'){throw 'Runner-only update failed; never uninstall/reset/retry'}
        $taskRunnerUpdated=$true
        if((Protected) -cne $taskBefore -or (Modules) -cne $taskModulesBefore){throw 'Runner-only update changed protected data/modules'}
    }
    foreach($taskPair in @(@('org.mcrf.client',(Join-Path $taskBuild 'ports/android/app/build/outputs/apk/debug/app-universal-debug.apk')),@('org.mcrf.client.test',$taskRunnerFile))){
        $taskHash=(Get-FileHash -LiteralPath $taskPair[1] -Algorithm SHA256).Hash.ToLowerInvariant()
        $taskPath=(Adb @('shell','pm','path',$taskPair[0])) -replace '^package:',''
        if($taskPath -notmatch '^/data/app/[^\s]+/base.apk$' -or (Adb @('shell','sha256sum',$taskPath)).Split(' ')[0] -cne $taskHash){throw 'Installed app/runner does not match current common snapshot'}
    }
    Idle
    if((Adb @('shell','appops','get','org.mcrf.client','ACTIVATE_VPN')) -notmatch 'ACTIVATE_VPN: allow'){throw 'Existing VPN consent missing; do not bypass'}
    if((Adb @('shell','dumpsys','package','org.mcrf.client')) -notmatch 'android.permission.POST_NOTIFICATIONS: granted=true'){throw 'Notification runtime permission missing; no grant here'}
    $taskModes=if($UiRemainder){@('routing-warm','server-swipe')}else{@('primary-direct-domain','primary-direct-domain-inverted','primary-direct-domain-negative','primary-direct-domain-inverted-negative')}
    if($RepairedRunner){$taskModes+=@('minimalism-readonly')}
    foreach($taskMode in $taskModes){
        Idle
        $taskLogName=$(if($RepairedRunner){'repaired-'}elseif($UiRemainder){'ui-'}else{'initial-'})+$taskMode+'.log'
        $taskLogPath=Join-Path $taskEvidence $taskLogName
        if(Test-Path -LiteralPath $taskLogPath){throw 'Existing evidence log: never overwrite a prior attempt'}
        $taskOutput=Adb @('shell','am','instrument','-w','-r','-e','mode',$taskMode,'org.mcrf.client.test/org.mcrf.client.DeviceSmokeInstrumentation')
        [IO.File]::WriteAllText($taskLogPath,$taskOutput,[Text.UTF8Encoding]::new($false))
        $taskOk=$taskOutput -match 'INSTRUMENTATION_CODE: -1' -and ($UiRemainder -or $taskMode -ceq 'minimalism-readonly' -or $taskOutput -match 'All saved profiles, catalogue, routes, encrypted settings and full preferences remain unchanged') -and $taskOutput -notmatch '(?m)(?:^|stream=)FAIL\b|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|\bSKIP\b'
        $taskCount=0;if($taskOutput -match 'INSTRUMENTATION_RESULT: passed=(\d+)'){$taskCount=[int]$Matches[1]}
        $taskOk=$taskOk -and $taskCount -gt 0
        $taskResults+=@{mode=$taskMode;passed=$taskOk;checks=$taskCount;log=$taskLogName}
        Write-Output "$taskMode passed=$taskOk checks=$taskCount"
        if(!$taskOk){throw 'Common actual TUN gate failed; inspect original log, never blindly repeat'}
        Idle
        if((Protected) -cne $taskBefore -or (Modules) -cne $taskModulesBefore){throw 'Protected state changed; no restoration of stale data'}
    }
    $taskPassed=$true
}finally{
    try{
        $taskAfter=if($taskBefore){Protected}else{$null};$taskModulesAfter=if($taskModulesBefore){Modules}else{$null}
        $taskScope=if($UiRemainder){'Common P0 debug Android34/x86_64 routing warm/theme/320dp and owned detached swipe UI only'}else{'Common P0 debug Android34/x86_64 owned local domain DNS/TUN/notification/STOP; repaired pass also63 read-only feature combinations'}
        [IO.File]::WriteAllText($taskResultPath,(@{snapshot=$taskSnapshot;runnerSnapshot=[IO.Path]::GetFileName($taskRunnerBuild);runnerOnlyUpdated=$taskRunnerUpdated;device=$taskDevice;passed=$taskPassed;results=$taskResults;protectedUnchanged=($null -ne $taskBefore -and $taskBefore -ceq $taskAfter);modulesUnchanged=($null -ne $taskModulesBefore -and $taskModulesBefore -ceq $taskModulesAfter);protectedBefore=$taskBefore;protectedAfter=$taskAfter;modulesBefore=$taskModulesBefore;modulesAfter=$taskModulesAfter;timeUtc=[DateTime]::UtcNow.ToString('o');scope=$taskScope+'; NOT public Internet, R8 runtime, IPv6 traffic, Vivo or provider CAPTCHA';physicalPhoneTouched=$false;permissionsMutatedByScript=$false}|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
        Write-Output "EVIDENCE=$taskResultPath"
    }finally{$taskMutex.ReleaseMutex();$taskMutex.Dispose()}
}
