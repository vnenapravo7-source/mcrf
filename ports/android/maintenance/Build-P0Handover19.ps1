param()
$ErrorActionPreference='Stop'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation build
$root='D:/MCRF/Projects/MCRF'
$parent='D:/MCRF/Builds/Android/p0-network42-apk-042be6d021924bb1aef85c8d63bc461a'
$prior=Get-Content "$parent/receipt.json" -Raw|ConvertFrom-Json
if($prior.state -cne 'PASS' -or !$prior.sourceStable -or !$prior.excludedP1 -or $prior.versionCode -ne 10018 -or $prior.artifacts.universal.sha256 -cne '6c6a84e7f0d68ce2c62634dd0c6d5aff2cf4641d0c4df0c9ba946e165ef3cd60'){throw 'Exact installed18 parent required'}
$currentPath="$root/ports/android/coordination/current.json"
$current=Get-Content $currentPath -Raw|ConvertFrom-Json
if($current.jobs.build -or $current.jobs.hostTest -or $current.jobs.device){throw 'Existing job; resume it'}
$stage='D:/MCRF/Builds/Android/p0-handover19-'+[guid]::NewGuid().ToString('N')
[IO.Directory]::CreateDirectory($stage)|Out-Null
$manifest=[ordered]@{};$canonical=[ordered]@{}
$result=[ordered]@{schema=1;state='PREPARING';stage=$stage;parent="$parent/receipt.json";versionCode=10019;versionName=$prior.versionName;sourceStable=$false;excludedP1=$true;baseNative=0;retainedHostTests=34;retainedHostReceipt=$prior.retainedHostReceipt;published=$false;installed=$false;startedUtc=[DateTime]::UtcNow.ToString('o');authority='Latest human: do items1 and2; Vivo human-owned. One grouped P0 handover repair verification/build/install; no publication/native/P1.'}
function Sha([string]$p){(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant()}
function Safe([string]$base,[string]$relative){$p=[IO.Path]::GetFullPath((Join-Path $base $relative));if(!$p.StartsWith([IO.Path]::GetFullPath($base)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Path escape'};$p}
function ReadCanonical([string]$p){$canonical[$p]=Sha (Safe $root $p);[IO.File]::ReadAllText((Safe $root $p)).Replace("`r`n","`n")}
function WriteFrozen([string]$p,[string]$text){$target=Safe $stage $p;[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))|Out-Null;[IO.File]::WriteAllText($target,$text,[Text.UTF8Encoding]::new($false));$manifest[$p]=Sha $target}
function ReplaceOnce([string]$text,[string]$before,[string]$after){if([regex]::Matches($text,[regex]::Escape($before)).Count -ne 1){throw 'Patch boundary mismatch'};$text.Replace($before,$after)}
function Stable {foreach($p in $canonical.Keys){if((Sha (Safe $root $p)) -cne $canonical[$p]){throw 'Canonical inputs changed'}};foreach($p in $manifest.Keys){if((Sha (Safe $stage $p)) -cne $manifest[$p]){throw 'Frozen inputs changed'}}}
$envNames=@('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS');$oldEnv=@{};foreach($name in $envNames){$oldEnv[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
    $current.jobs.build="P0_HANDOVER19:$stage";$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8
    foreach($row in @(Get-Content "$parent/source-manifest.json" -Raw|ConvertFrom-Json)){
        $from=Safe $parent $row.path;$to=Safe $stage $row.path
        if((Sha $from) -cne $row.sha256){throw 'Frozen parent drift'}
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($to))|Out-Null;[IO.File]::Copy($from,$to,$false);$manifest[$row.path]=$row.sha256
    }
    $base='ports/android/app/src/main/kotlin/org/mcrf/client/'
    foreach($file in @('ClientPrimaryHostPlatform.kt','RetainedTunTransition.kt')){WriteFrozen ($base+$file) (ReadCanonical ($base+$file))}
    # Do NOT copy canonical service: it also contains unfinished P1 work.
    $service=$base+'McrfVpnService.kt';$text=[IO.File]::ReadAllText((Safe $stage $service)).Replace("`r`n","`n");$live=ReadCanonical $service
    $text=ReplaceOnce $text "        profile?.let {`n            val transport=NativeCarrier.start" "        profile?.let {`n            prepareCarrierHandoverTun(vpn,requested)`n            val transport=NativeCarrier.start"
    $text=ReplaceOnce $text "            if(plan.requiresWorkers){`n" "            if(plan.requiresWorkers){`n                prepareCarrierHandoverTun(vpn,requested)`n"
    $begin=$live.IndexOf('    private fun prepareCarrierHandoverTun(');$end=$live.IndexOf('    private fun startSteer(', $begin)
    if($begin -lt 0 -or $end -le $begin){throw 'Missing current P0 helper'}
    $text=ReplaceOnce $text '    private fun startSteer(' ($live.Substring($begin,$end-$begin)+'    private fun startSteer(')
    WriteFrozen $service $text
    WriteFrozen 'ports/android/app/src/test/kotlin/org/mcrf/client/RetainedTunTransitionTest.kt' (ReadCanonical 'ports/android/app/src/test/kotlin/org/mcrf/client/RetainedTunTransitionTest.kt')
    $gradle='ports/android/app/build.gradle.kts';$text=[IO.File]::ReadAllText((Safe $stage $gradle)).Replace("`r`n","`n")
    $text=ReplaceOnce $text 'versionCode = 10018' 'versionCode = 10019'
    $canonicalGradle=ReadCanonical $gradle
    if($canonicalGradle -cne $text){throw 'Unrelated canonical Gradle/P1 change'}
    WriteFrozen $gradle $text
    $sourceRows=@($manifest.Keys|ForEach-Object {@{path=$_;sha256=$manifest[$_]}})
    [IO.File]::WriteAllText("$stage/source-manifest.json",($sourceRows|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
    $result.canonicalInputs=$canonical;$result.inputs=$manifest.Count;$result.state='RUNNING_GROUPED_HOST_COMPILE_R8'
    $result|ConvertTo-Json -Depth 8|Set-Content "$stage/receipt.json" -Encoding utf8
    Stable
    $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$root/work/toolchains/gradle-home"
    $env:JAVA_TOOL_OPTIONS=($oldEnv.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
    Push-Location "$stage/ports/android"
    try {& "$env:JAVA_HOME/bin/java.exe" -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain ':app:testDebugUnitTest' '--tests' 'org.mcrf.client.RetainedTunTransitionTest' ':app:compileDebugAndroidTestKotlin' ':app:assembleRelease' '-PtestRelease=true' '--offline' '--console=plain' *> "$stage/build.log";$result.exitCode=$LASTEXITCODE;if($LASTEXITCODE){throw 'Grouped verification failed; inspect preserved log'}}finally{Pop-Location}
    $test="$stage/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.RetainedTunTransitionTest.xml"
    [xml]$xml=Get-Content $test -Raw
    if([int]$xml.testsuite.tests -ne 6 -or [int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped){throw 'Changed6 host checks incomplete'}
    $result.tests=@{xml=$test;tests=6;sha256=Sha $test};$result.artifacts=@{}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    foreach($abi in @('universal','arm64-v8a','armeabi-v7a','x86_64')){
        $apk="$stage/ports/android/app/build/outputs/apk/release/app-$abi-release.apk"
        $cert=(& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --print-certs $apk 2>&1|Out-String)
        if($LASTEXITCODE -or $cert -notmatch 'certificate SHA-256 digest:\s*([a-f0-9]{64})' -or $Matches[1] -cne $prior.signer){throw 'Signer drift'}
        $badging=(& "$env:ANDROID_HOME/build-tools/36.0.0/aapt.exe" dump badging $apk 2>&1|Out-String)
        if($LASTEXITCODE -or $badging -notmatch "package: name='org.mcrf.client' versionCode='10019'"){throw 'APK identity drift'}
        $zip=[IO.Compression.ZipFile]::OpenRead($apk);try{if(@($zip.Entries|Where-Object {$_.FullName -match '^lib/|\.so$'}).Count){throw 'Native leaked into main'}}finally{$zip.Dispose()}
        $result.artifacts[$abi]=@{apk=$apk;bytes=(Get-Item $apk).Length;sha256=Sha $apk}
    }
    Stable;$result.signer=$prior.signer;$result.sourceStable=$true;$result.state='PASS'
}catch{$result.state='FAILED_PRESERVED';$result.error=$_.Exception.Message;throw}
finally {
    foreach($name in $oldEnv.Keys){[Environment]::SetEnvironmentVariable($name,$oldEnv[$name],'Process')}
    $result.finishedUtc=[DateTime]::UtcNow.ToString('o');$result|ConvertTo-Json -Depth 10|Set-Content "$stage/receipt.json" -Encoding utf8
    $current=Get-Content $currentPath -Raw|ConvertFrom-Json;if($current.jobs.build -ceq "P0_HANDOVER19:$stage"){$current.jobs.build=$null;$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8}
    "HANDOVER19=$($result.state);RECEIPT=$stage/receipt.json"
}
