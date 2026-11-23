param([Parameter(Mandatory=$true)][string]$Snapshot,[string]$Device='192.168.100.234:43583')
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$frozen=[IO.Path]::GetFullPath((Join-Path $root $Snapshot))
if(!$frozen.StartsWith((Join-Path $root 'work')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($frozen) -notmatch '^android-integration-[0-9a-f]{32}$'){throw 'Invalid frozen snapshot'}
$build=Get-Content -LiteralPath (Join-Path $frozen 'build-result.json') -Raw|ConvertFrom-Json
if($build.exitCode -ne 0 -or !$build.sourceStable){throw 'Snapshot must be green/stable'}
$lease=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'coordination/device-lease.json') -Raw|ConvertFrom-Json
if($lease.owner_thread -ne '01a13a9b-6754-7b13-af52-422a6eb5579c' -or $lease.state -ne 'occupied_main_fixture_acceptance' -or $lease.device -ne $Device){throw 'Main device lease required'}
$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
function Assert-Idle {
    foreach($package in @('org.mcrf.client','org.steer.prototype')){
        $services=(& $adb -s $Device shell dumpsys activity services $package|Out-String)
        if($LASTEXITCODE -ne 0 -or $services -match 'isForeground=true|/\.McrfVpnService\b|/\.ProbeService\b|/\.PrototypeVpnService\b'){throw 'Existing native operation; no package change'}
    }
}
function Assert-Signer([string]$apk) {
    $signature=(& 'C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0/apksigner.bat' verify --print-certs $apk 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $signature -notmatch 'Signer #1 certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Unexpected local acceptance signer'}
}
$testApk=Join-Path $frozen 'ports/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
$debugApk=Join-Path $frozen 'ports/android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk'
Assert-Signer $testApk;Assert-Signer $debugApk
$runner=(& 'C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0/aapt.exe' dump xmltree $testApk AndroidManifest.xml|Out-String)
if($runner -notmatch 'org.mcrf.client.ReleaseSmokeInstrumentation'){throw 'Build this snapshot with ReleaseSmoke first'}
# Read the frozen test source, not a presumed capability of an old installed runner.
$runnerSource=Get-Content -LiteralPath (Join-Path $frozen 'ports/android/app/src/androidTest/java/org/mcrf/client/ReleaseSmokeInstrumentation.java') -Raw
if($runnerSource -notmatch 'mode.equals\("protected-hashes"\)'){throw 'Frozen runner lacks read-only protected-hashes mode'}
Assert-Idle
$evidence=Join-Path $root ('work/ports-test-2026-10-14/integration-'+[IO.Path]::GetFileName($frozen).Substring(20,4)+'-device')
New-Item -ItemType Directory -Path $evidence -Force|Out-Null
$run=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
Copy-Item -LiteralPath $testApk -Destination (Join-Path $evidence "framework-smoke-instrumentation-$run.apk")
$installed=(& $adb -s $Device install -r $testApk 2>&1|Out-String)
if($LASTEXITCODE -ne 0 -or $installed -notmatch '(?m)^Success\s*$'){throw 'Framework test APK install failed'}
$hashes=(& $adb -s $Device shell am instrument -w -r -e mode protected-hashes 'org.mcrf.client.test/org.mcrf.client.ReleaseSmokeInstrumentation' 2>&1|Out-String)
[IO.File]::WriteAllText((Join-Path $evidence "protected-before-debug-$run.log"),$hashes,[Text.UTF8Encoding]::new($false))
if($LASTEXITCODE -ne 0 -or $hashes -notmatch 'INSTRUMENTATION_RESULT: passed=true' -or $hashes -notmatch 'INSTRUMENTATION_RESULT: checks=0' -or $hashes -notmatch 'INSTRUMENTATION_RESULT: protectedFilesUnchanged=true' -or $hashes -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Read-only framework hash receipt failed'}
$expected=@{}
foreach($m in [regex]::Matches($hashes,'(?m)(connections\.enc|routing\.enc)=([0-9a-f]{64})')){
    if($expected.ContainsKey($m.Groups[1].Value) -and $expected[$m.Groups[1].Value] -ne $m.Groups[2].Value){throw 'Inconsistent protected hash receipt'}
    $expected[$m.Groups[1].Value]=$m.Groups[2].Value
}
if($expected.Count -ne 2){throw 'Both encrypted user files must be accounted for'}
$releaseApk=Join-Path $frozen 'ports/android/app/build/outputs/apk/release/app-arm64-v8a-release.apk'
$releaseBefore=(Get-FileHash -LiteralPath $releaseApk).Hash
$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk'
$env:GRADLE_USER_HOME=Join-Path $root 'work/toolchains/gradle-home'
$env:JAVA_TOOL_OPTIONS='"-Djdk.net.unixdomain.tmpdir=C:\Users\DIEONF~1\DOCUME~1\ChatGPT\MCRF\work\TOOLCH~1\java-tmp"'
Push-Location (Join-Path $frozen 'ports/android')
try{& "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain ':app:assembleDebugAndroidTest' '-PreleaseSmoke=false' '-PtestRelease=true' '--console=plain';if($LASTEXITCODE -ne 0){throw 'Debug test packaging failed'}}finally{Pop-Location}
if((Get-FileHash -LiteralPath $releaseApk).Hash -ne $releaseBefore){throw 'Frozen release artifact changed while repackaging test runner'}
Assert-Signer $testApk;Assert-Idle
foreach($file in @($debugApk,$testApk)){
    $installed=(& $adb -s $Device install -r $file 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $installed -notmatch '(?m)^Success\s*$'){throw 'Matching debug package install failed'}
}
$actual=(& $adb -s $Device shell run-as org.mcrf.client sha256sum files/connections.enc files/routing.enc 2>&1|Out-String)
if($LASTEXITCODE -ne 0){throw 'Cannot verify restored debug protected files'}
foreach($name in $expected.Keys){if($actual -notmatch ([regex]::Escape($expected[$name])+'\s+files/'+[regex]::Escape($name))){throw 'Protected user data changed; no reset/rollback performed'}}
$receipt=[pscustomobject]@{snapshot=$frozen;framework_hash_mode_passed=$true;protected_hashes=$expected;user_files_preserved=$true;release_apk_unchanged=$true;debug_test_sha256=(Get-FileHash -LiteralPath $testApk).Hash.ToLowerInvariant()}
[IO.File]::WriteAllText((Join-Path $evidence "prepare-debug-$run.json"),($receipt|ConvertTo-Json -Depth 3),[Text.UTF8Encoding]::new($false))
Write-Output 'PASS framework-only pre-update hashes; matching debug/test installed with encrypted user files preserved'
