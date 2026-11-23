param([Parameter(Mandatory=$true)][string]$Snapshot,[string]$Device='192.168.100.234:43583',[ValidateSet('normal','engine-packages','logs-ui','settings-pages','ssh-primitives','primary-spi','primary-native','primary-steer-native','packet-native')][string]$Mode='normal',[switch]$SkipInstall)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$frozen=[IO.Path]::GetFullPath((Join-Path $root $Snapshot))
if(!$frozen.StartsWith((Join-Path $root 'work')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($frozen) -notmatch '^android-integration-[0-9a-f]{32}$'){throw 'Invalid frozen snapshot'}
$lease=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/device-lease.json') -Raw|ConvertFrom-Json
if($lease.owner_thread -ne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $lease.device -ne $Device -or $lease.state -ne 'occupied_main_fixture_acceptance'){throw 'Coordinator device lease required'}
$build=Get-Content -LiteralPath (Join-Path $frozen 'build-result.json') -Raw|ConvertFrom-Json
if($build.exitCode -ne 0 -or !$build.sourceStable){throw 'Build is not green/stable'}
$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$power=(& $adb -s $Device shell dumpsys power|Out-String);$policy=(& $adb -s $Device shell dumpsys window policy|Out-String)
if($power -notmatch 'mWakefulness=Awake' -or $policy -notmatch '(?m)^\s+showing=false\s*$'){throw 'Release UI gate deferred; no wake/unlock performed'}
foreach($package in @('org.mcrf.client','org.steer.prototype')){
    $services=(& $adb -s $Device shell dumpsys activity services $package|Out-String)
    if($services -match 'isForeground=true|/\.McrfVpnService\b|/\.ProbeService\b|/\.PrototypeVpnService\b'){throw 'Existing VPN/native service active; no install or stop'}
}
$apk=Join-Path $frozen 'ports/android/app/build/outputs/apk/release/app-arm64-v8a-release.apk'
$testApk=Join-Path $frozen 'ports/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
$releaseTestApk=Join-Path $frozen 'ports/android/app/build/outputs/apk/androidTest/debug/app-release-smoke-androidTest.apk'
if(Test-Path -LiteralPath $releaseTestApk){$testApk=$releaseTestApk}
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
foreach($file in @($apk,$testApk)){
    $signature=(& 'C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0/apksigner.bat' verify --print-certs $file 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $signature -notmatch 'Signer #1 certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Release local acceptance signer mismatch'}
}
$badging=(& 'C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0/aapt.exe' dump badging $apk 2>&1|Out-String)
if($LASTEXITCODE -ne 0 -or $badging -match 'application-debuggable'){throw 'Not a genuine non-debuggable release candidate'}
$before='Protected hashes recorded inside framework-only runner; see log'
if($SkipInstall){
    foreach($pair in @(@('org.mcrf.client',$apk),@('org.mcrf.client.test',$testApk))){
        $paths=@(& $adb -s $Device shell pm path $pair[0])
        if($LASTEXITCODE -ne 0 -or $paths.Count -ne 1 -or $paths[0] -notmatch '^package:/data/app/.+/base.apk$'){throw 'Cannot resolve exact installed APK'}
        $path=$paths[0].Substring(8).Trim();$actual=(& $adb -s $Device shell sha256sum $path|Out-String)
        if($LASTEXITCODE -ne 0 -or !$actual.StartsWith((Get-FileHash -LiteralPath $pair[1]).Hash.ToLowerInvariant())){throw 'Installed APK differs from frozen receipt'}
    }
}else{
    $before=(& $adb -s $Device shell run-as org.mcrf.client sha256sum files/connections.enc files/routing.enc 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -and $before -match 'not debuggable'){
        # Upgrade only the verified same-signature test harness first. Its framework
        # checkpoint reads this app's own files without changing the installed app.
        $testInstalled=(& $adb -s $Device install -r $testApk 2>&1|Out-String)
        if($LASTEXITCODE -ne 0 -or $testInstalled -notmatch '(?m)^Success\s*$'){throw 'Cannot install verified release checkpoint harness'}
        $proof=(& $adb -s $Device shell am instrument -w -r -e mode protected-hashes 'org.mcrf.client.test/org.mcrf.client.ReleaseSmokeInstrumentation' 2>&1|Out-String)
        if($LASTEXITCODE -ne 0 -or $proof -notmatch 'INSTRUMENTATION_RESULT: passed=true' -or $proof -notmatch 'INSTRUMENTATION_RESULT: protectedFilesUnchanged=true'){throw 'Release protected checkpoint failed before replacement'}
        $rows=@()
        foreach($name in @('connections.enc','routing.enc')){
            $hashes=@([regex]::Matches($proof,'(?m)^(?:INSTRUMENTATION_RESULT: protectedFiles(?:Before|After)=)?'+[regex]::Escape($name)+'=([0-9a-f]{64})\r?$'))
            if(!$hashes.Count -or @($hashes|ForEach-Object {$_.Groups[1].Value}|Select-Object -Unique).Count -ne 1){throw 'Ambiguous protected release checkpoint'}
            $rows+=$hashes[0].Groups[1].Value+'  files/'+$name
        }
        $before=$rows -join [Environment]::NewLine
    }
    if($LASTEXITCODE -ne 0){throw 'Record protected hashes while matching debug app is installed first'}
    foreach($file in @($apk,$testApk)){
        $installed=(& $adb -s $Device install -r $file 2>&1|Out-String)
        if($LASTEXITCODE -ne 0 -or $installed -notmatch '(?m)^Success\s*$'){throw "Release/test install failed: $installed"}
    }
}
$output=(& $adb -s $Device shell am instrument -w -r -e mode $Mode 'org.mcrf.client.test/org.mcrf.client.ReleaseSmokeInstrumentation' 2>&1|Out-String)
$commandExit=$LASTEXITCODE
$ok=$commandExit -eq 0 -and $output -match 'INSTRUMENTATION_CODE: -1' -and $output -match 'INSTRUMENTATION_RESULT: passed=true' -and $output -match 'INSTRUMENTATION_RESULT: protectedFilesUnchanged=true' -and $output -notmatch 'INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed'
$upgradeUnchanged=$null
if(!$SkipInstall){
    $upgradeUnchanged=$true
    foreach($name in @('connections.enc','routing.enc')){
        $expected=[regex]::Match($before,'(?m)^([0-9a-f]{64})\s+files/'+[regex]::Escape($name)+'\s*$')
        $observed=[regex]::Matches($output,'(?m)^(?:INSTRUMENTATION_RESULT: protectedFilesBefore=)?'+[regex]::Escape($name)+'=([0-9a-f]{64})\r?$')
        if(!$expected.Success -or !$observed.Count){$upgradeUnchanged=$false;continue}
        foreach($value in $observed){if($value.Groups[1].Value -cne $expected.Groups[1].Value){$upgradeUnchanged=$false}}
    }
    $ok=$ok -and $upgradeUnchanged
}
$evidence=Join-Path $root ('work/ports-test-2026-10-14/integration-'+[IO.Path]::GetFileName($frozen).Substring(20,4)+'-device')
New-Item -ItemType Directory -Path $evidence -Force|Out-Null
$run=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
[IO.File]::WriteAllText((Join-Path $evidence "release-$Mode-$run.log"),$output,[Text.UTF8Encoding]::new($false))
$receipt=[pscustomobject]@{snapshot=$frozen;device=$Device;mode=$Mode;state=$(if($ok){'passed'}else{'failed'});minified=$true;debuggable=$false;local_development_signer=$true;apk_sha256=(Get-FileHash -LiteralPath $apk).Hash.ToLowerInvariant();test_apk_sha256=(Get-FileHash -LiteralPath $testApk).Hash.ToLowerInvariant();user_files_before=$before;preinstall_hashes_preserved=$upgradeUnchanged;scope='Framework-only exact selected mode; not all final native/provider/network acceptance'}
[IO.File]::WriteAllText((Join-Path $evidence "release-$Mode-$run.json"),($receipt|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
Write-Output $output
if(!$ok){throw 'Exact minified phone gate did not pass; no automatic app-data rollback or clear'}
