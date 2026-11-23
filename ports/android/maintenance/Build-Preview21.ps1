param([switch]$ResumeAffectedCorrection)
$ErrorActionPreference='Stop'
# Fresh 20Oct new-prerelease authority; exclusively the accepted immutable P1.
$root='D:/MCRF/Projects/MCRF';$stage='D:/MCRF/Builds/Android/preview21-6f19501d734e48e19f543bda2b5f1265';$source="$stage/source"
$parent="$root/work/android-integration-8ae976348b7848b2afa72a66f2d642bd";$currentPath="$root/ports/android/coordination/current.json"
$utf8=[Text.UTF8Encoding]::new($false)
function Sha([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Save($v,[string]$p){[IO.File]::WriteAllText($p,($v|ConvertTo-Json -Depth 12),$utf8)}
if(Test-Path -LiteralPath "$stage/build-receipt.json"){
 $old=Get-Content -LiteralPath "$stage/build-receipt.json" -Raw|ConvertFrom-Json
 if(!$ResumeAffectedCorrection -or $old.state -cne 'FAILED_PRESERVED' -or
  (Sha "$stage/build-receipt.json") -cne (Sha "$stage/attempt1/build-receipt.json")){throw 'Attempt exists: exact archived failed correction required'}
}elseif($ResumeAffectedCorrection){throw 'No correction parent'}
if((Sha "$parent/source-manifest.json") -cne 'bf987f1d87804847275bfe7b3366f47f6a46f74d3c5ea66f6d825d09e1ac81b4' -or
 (Sha "$root/work/ports-test-2026-10-14/p1-final-11b3e3b2cbab492abf0cf4833a70b6ff/receipt.json") -cne 'dce550cb578219785310f3e8f30dafe3b8b14c92db2714d4b080a74819162b3d'){throw 'Exact completed P1 source/acceptance required'}
$p2=Get-Content -LiteralPath "$root/ports/android/coordination/p2-progress-2026-10-20.json" -Raw|ConvertFrom-Json
foreach($flag in @('sourceReady','readyForBuild','allowCommonVerification','allowApkBuild','allowNativeCandidateBuild')){if($p2.$flag -ne $false){throw 'P2 state changed; no overlap'}}
$changed=@('ports/android/app/build.gradle.kts','ports/android/app/src/main/kotlin/org/mcrf/client/EnginePreviewChannel.kt')
$new='ports/android/app/src/test/kotlin/org/mcrf/client/Preview21ReleaseTest.kt'
$rows=@(Get-Content -LiteralPath "$parent/source-manifest.json" -Raw|ConvertFrom-Json);$inputs=@()
foreach($row in $rows){$p=Join-Path $source $row.path;if((Get-Item -LiteralPath $p).LinkType){throw 'Reparse input'};$sha=Sha $p
 if($row.path -notin $changed -and $sha -cne $row.sha256){throw 'Change outside reviewed P1'}
 if($row.path -in $changed -and $sha -ceq $row.sha256){throw 'Release overlay missing'}
 $inputs+=@{path=$row.path;bytes=(Get-Item -LiteralPath $p).Length;sha256=$sha}}
$inputs+=@{path=$new;bytes=(Get-Item -LiteralPath "$source/$new").Length;sha256=(Sha "$source/$new")}
Save $inputs "$stage/SOURCE_MANIFEST.json"
$current=Get-Content -LiteralPath $currentPath -Raw|ConvertFrom-Json
if($current.jobs.build -or $current.jobs.hostTest -or $current.jobs.emulator){throw 'Producer already active'}
$job="PREVIEW21:$stage";$current.jobs.build=$job;Save $current $currentPath
$result=[ordered]@{state='BUILDING';authority='Fresh human20Oct new prerelease; immutable accepted P1 ONLY';versionCode=10021;
 versionName='0.1.0-preview.20261020.1';stage=$stage;source=$source;sourceManifestSha256=(Sha "$stage/SOURCE_MANIFEST.json");
 excludedP2=$true;nativeRebuilt=$false;sourceChanges=$changed;newTests=$new;retainedP1MandatoryOwnedGates=31;
 phoneInstalled=$false;published=$false;startedUtc=[DateTime]::UtcNow.ToString('o');artifacts=@{}}
$envs=@{};foreach($name in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$envs[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
 Save $result "$stage/build-receipt.json"
 $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$root/work/toolchains/gradle-home"
 $env:JAVA_TOOL_OPTIONS=($envs.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
 Push-Location "$source/ports/android"
 $cases=if($ResumeAffectedCorrection){@('exactNewPreviewOffersAllThreeOwnedModules','unrelatedPreviewOrMissingOptInCannotOfferTheseCandidates','absentModulesAreNotAutomaticallyInstalled','installedModulesUpdateWithoutDowngradingNewerOnes','currentBytesAndDraftAreNotUpdates')}else{@('*')}
 $gradleArgs=@(':app:testDebugUnitTest');foreach($case in $cases){$gradleArgs+=@('--tests',"org.mcrf.client.Preview21ReleaseTest.$case")}
 $gradleArgs+=@(':app:assembleRelease','-PtestRelease=true','--offline','--console=plain')
 try{& "$env:JAVA_HOME/bin/java.exe" -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain @gradleArgs *> "$stage/build.log"
 $result.exitCode=$LASTEXITCODE;if($LASTEXITCODE){throw 'Grouped release build failed; exact log retained'}}finally{Pop-Location}
 $xmlFile="$source/ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.Preview21ReleaseTest.xml";[xml]$xml=Get-Content -LiteralPath $xmlFile -Raw
 $count=if($ResumeAffectedCorrection){5}else{7}
 if([int]$xml.testsuite.tests -ne $count -or [int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped){throw 'Affected release cases incomplete'}
 $retained=0
 if($ResumeAffectedCorrection){[xml]$oldXml=Get-Content -LiteralPath "$stage/attempt1/Preview21ReleaseTest.xml" -Raw
 foreach($name in @('unrelatedEnginesRemainOnStableCatalogue','newVersionIsDiscoverableAfterTwenty')){
 $case=@($oldXml.testsuite.testcase|Where-Object name -CEQ $name);if($case.Count -ne 1 -or $case[0].failure -or $case[0].error -or $case[0].skipped){throw 'Retained release case unavailable'};$retained++}}
 $result.releaseTests=@{passed=($count+$retained);executed=$count;retained=$retained;sha256=(Sha $xmlFile)};Add-Type -AssemblyName System.IO.Compression.FileSystem
 foreach($abi in @('universal','arm64-v8a','armeabi-v7a','x86_64')){
 $p="$source/ports/android/app/build/outputs/apk/release/app-$abi-release.apk"
 $cert=(& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --print-certs $p 2>&1|Out-String)
 if($LASTEXITCODE -or $cert -notmatch 'certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Signer mismatch'}
 $badging=(& "$env:ANDROID_HOME/build-tools/36.0.0/aapt2.exe" dump badging $p 2>&1|Out-String)
 if($LASTEXITCODE -or $badging -notmatch "package: name='org.mcrf.client' versionCode='10021' versionName='0.1.0-preview.20261020.1'"){throw 'APK identity mismatch'}
 $zip=[IO.Compression.ZipFile]::OpenRead($p);try{if(@($zip.Entries|Where-Object FullName -Match '^lib/|\.so$').Count){throw 'Native in main APK'}}finally{$zip.Dispose()}
 $result.artifacts[$abi]=@{path=$p;bytes=(Get-Item -LiteralPath $p).Length;sha256=(Sha $p)}}
 foreach($row in $inputs){if((Sha (Join-Path $source $row.path)) -cne $row.sha256){throw 'Frozen source drift'}}
 $result.state='PASS_RELEASE21_R8_AND_SEVEN_NEW_CHANNEL_CASES';$result.sourceStable=$true
}catch{$result.state='FAILED_PRESERVED';$result.error=$_.Exception.Message;throw}
finally{foreach($name in $envs.Keys){[Environment]::SetEnvironmentVariable($name,$envs[$name],'Process')}
 $result.finishedUtc=[DateTime]::UtcNow.ToString('o');Save $result "$stage/build-receipt.json"
 $current=Get-Content -LiteralPath $currentPath -Raw|ConvertFrom-Json;if($current.jobs.build -ceq $job){$current.jobs.build=$null;Save $current $currentPath}
 Write-Output "PREVIEW21=$($result.state);RECEIPT=$stage/build-receipt.json"}
