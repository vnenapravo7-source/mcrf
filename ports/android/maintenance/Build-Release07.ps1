param([string]$RetainModuleFrom='')
$ErrorActionPreference='Stop'
$taskProject='D:/MCRF/Projects/MCRF'
$taskCanonical="$taskProject/ports/android"
$taskBaseline=[IO.Path]::GetFullPath("$taskProject/work/android-integration-4f53ffc3358c4796bb56a265d3e090d6")
$taskFresh="$taskProject/work/ports-test-2026-10-14/openflux-build-v0.13.0-a871260fa0024ca089b77c3dccdc3dbc/verified-native"
$taskPhone='D:/MCRF/Builds/Android/openflux13-validation-385c4578d00e4c9aa2be74e47d03e6ab'
$taskId=[guid]::NewGuid().ToString('N')
$taskSnapshot=[IO.Path]::GetFullPath("$taskProject/work/android-release07-$taskId")
$taskOutput="D:/MCRF/Builds/Android/release07-$taskId"
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Json([string]$Path,$Value){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))}
$taskPhoneProof=Get-Content "$taskPhone/phone/receipt.json" -Raw|ConvertFrom-Json
if($taskPhoneProof.state -cne 'PASS_FRESH_OPENFLUX_LOCAL_ANDROID_RUNTIME' -or !$taskPhoneProof.userDataPreserved -or $taskPhoneProof.protectedEntries -ne 34){throw 'Fresh phone acceptance/preservation receipt missing'}
$taskValidator=Get-Content "$taskPhone/receipt.json" -Raw|ConvertFrom-Json
if($taskValidator.nativeSha256 -cne (Hash "$taskFresh/arm64-v8a/libopenflux.so")){throw 'Accepted native differs from release input'}
$taskBaselineProof=Get-Content "$taskBaseline/build-result.json" -Raw|ConvertFrom-Json
if(!$taskBaselineProof.sourceStable -or $taskBaselineProof.exitCode -ne 0){throw 'Retained main-app build not green'}
$taskInputManifest=Get-Content "$taskBaseline/source-manifest.json" -Raw|ConvertFrom-Json
if((Hash "$taskBaseline/source-manifest.json") -cne $taskBaselineProof.sourceManifestSha256){throw 'Retained input manifest differs'}
$null=New-Item -ItemType Directory $taskSnapshot,$taskOutput
# No existing snapshot, output, canonical gate or user file is overwritten.
foreach($taskRow in $taskInputManifest){
 $taskFrom=[IO.Path]::GetFullPath((Join-Path $taskBaseline $taskRow.path));$taskTo=[IO.Path]::GetFullPath((Join-Path $taskSnapshot $taskRow.path))
 if(!$taskFrom.StartsWith($taskBaseline+'\',[StringComparison]::OrdinalIgnoreCase) -or !$taskTo.StartsWith($taskSnapshot+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Manifest path escaped snapshot'}
 if((Hash $taskFrom) -cne $taskRow.sha256){throw "Retained source differs: $($taskRow.path)"}
 $null=New-Item -ItemType Directory (Split-Path $taskTo) -Force;Copy-Item -LiteralPath $taskFrom -Destination $taskTo
}
$taskAndroid="$taskSnapshot/ports/android"
# Main runtime/UI code remains byte-identical to installed10058. New release
# version and module packaging gates are the ONLY main-snapshot overlays.
$taskReleaseOverlay=@('app/build.gradle.kts','engines/build.gradle.kts','app/src/main/AndroidManifest.xml',
 'app/src/main/kotlin/org/mcrf/client/McrfVpnService.kt','app/src/main/kotlin/org/mcrf/client/PacketManagedDnsRuntime.kt',
 'app/src/main/kotlin/org/mcrf/client/McrfQuickTiles.kt','app/src/main/kotlin/org/mcrf/client/PacketManagedDnsFixture.kt',
 'app/src/main/kotlin/org/mcrf/client/LogsPage.kt','app/src/main/kotlin/org/mcrf/client/MainActivity.kt',
 'app/src/test/kotlin/org/mcrf/client/Release07CompatibilityTest.kt')
foreach($taskName in $taskReleaseOverlay){Copy-Item -LiteralPath "$taskCanonical/$taskName" -Destination "$taskAndroid/$taskName" -Force}
$taskFreshReceipt=Get-Content "$taskFresh/fresh-core-candidate.json" -Raw|ConvertFrom-Json
foreach($taskProperty in $taskFreshReceipt.sources.PSObject.Properties){
 $taskRelative='transports/openflux/'+$taskProperty.Name;$taskFrom="$taskCanonical/$taskRelative"
 if((Hash $taskFrom) -cne $taskProperty.Value){throw "Fresh producer source differs: $taskRelative"}
 $taskTo="$taskAndroid/$taskRelative";$null=New-Item -ItemType Directory (Split-Path $taskTo) -Force;Copy-Item -LiteralPath $taskFrom -Destination $taskTo -Force
}
Copy-Item -LiteralPath $taskFresh -Destination "$taskSnapshot/openflux-native" -Recurse
$taskAcceptance=[ordered]@{authority='human-20261026-finish-openflux-phone-publish07';state='PHONE_RUNTIME_ACCEPTED';commit=$taskFreshReceipt.commit;arm64Sha256=$taskValidator.nativeSha256;userDataPreserved=$true;encryptedHttp=$true;anonymousRejected=$true;wrongKeyRejected=$true;stopDuringStartup=$true;stopAfterReady=$true;stdinEof=$true;processExitAndOutputEof=$true;phoneReceipt="$taskPhone/phone/receipt.json";phoneReceiptSha256=(Hash "$taskPhone/phone/receipt.json");limits='Owned encrypted direct transport only; no live provider CAPTCHA/ownHTTP/inline/script enrollment or 32bit/x86 device acceptance'}
Json "$taskSnapshot/openflux-acceptance.json" $taskAcceptance
Json "$taskSnapshot/release07.json" @{authority=$taskAcceptance.authority;state='SOURCE_READY';versionCode=10059;versionName='0.7';baseline=$taskBaseline;baselineManifest=$taskBaselineProof.sourceManifestSha256;retainedUiDistinct=16;newAppRuntimeCode=$true;publish=$false}
$taskFrozen=@(Get-ChildItem -LiteralPath $taskSnapshot -Recurse -File|ForEach-Object {@{path=[IO.Path]::GetRelativePath($taskSnapshot,$_.FullName);sha256=(Hash $_.FullName);bytes=$_.Length}})
Json "$taskSnapshot/source-manifest.json" $taskFrozen
foreach($taskModule in @('app','engines','primary-spi','primary-adapter')){
 $null=New-Item -ItemType Directory "$taskOutput/$taskModule"
 $null=New-Item -ItemType Junction -Path "$taskAndroid/$taskModule/build" -Target "$taskOutput/$taskModule"
}
$taskResult=[ordered]@{state='BUILDING';snapshot=$taskSnapshot;output=$taskOutput;sourceStable=$false;versionCode=10059;versionName='0.7';retainedMainSnapshot=$taskBaseline;retainedUiDistinct=16;newAppRuntimeCode=$true;newCompatibilitySourceChecks=7;published=$false;installed=$false}
$taskPreviousOptions=$env:JAVA_TOOL_OPTIONS
try{
 $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$taskProject/work/toolchains/gradle-home"
 $taskTemp=(New-Object -ComObject Scripting.FileSystemObject).GetFolder("$taskProject/work/toolchains/java-tmp").ShortPath
 $env:JAVA_TOOL_OPTIONS=($taskPreviousOptions+' "-Djdk.net.unixdomain.tmpdir='+$taskTemp+'"').Trim()
 Push-Location $taskAndroid
 try{
  $taskTasks=@(':app:testDebugUnitTest','--tests','org.mcrf.client.Release07CompatibilityTest',':app:assembleRelease',':app:lintRelease')
  if($RetainModuleFrom){
   $taskRetainedRoot=[IO.Path]::GetFullPath($RetainModuleFrom)
   if(!$taskRetainedRoot.StartsWith('D:\MCRF\Builds\Android\release07-',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected retained module root'}
   $taskRetainedProof=Get-Content "$taskRetainedRoot/receipt.json" -Raw|ConvertFrom-Json
   foreach($taskRow in (Get-Content "$($taskRetainedProof.snapshot)/source-manifest.json" -Raw|ConvertFrom-Json)){if((Hash (Join-Path $taskRetainedProof.snapshot $taskRow.path)) -cne $taskRow.sha256){throw 'Retained module source changed'}}
   if((Hash "$($taskRetainedProof.snapshot)/ports/android/engines/build.gradle.kts") -cne (Hash "$taskAndroid/engines/build.gradle.kts")){throw 'Retained module packaging source differs'}
   Copy-Item -LiteralPath "$taskRetainedRoot/engines/outputs" -Destination "$taskOutput/engines/outputs" -Recurse
   $taskResult.retainedOpenFluxModule=$taskRetainedRoot
  }else{$taskTasks+=':engines:assembleOpenfluxRelease'}
  & "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain @taskTasks --no-daemon --console=plain '-PtestRelease=true' "-Prelease07Receipt=$taskSnapshot/release07.json" "-PopenfluxFreshProduction=$taskSnapshot/openflux-native" "-PopenfluxFreshAcceptance=$taskSnapshot/openflux-acceptance.json" *> "$taskOutput/build.log"
  if($LASTEXITCODE){throw 'Release07 build failed; inspect exact build.log'}
 }finally{Pop-Location}
 foreach($taskRow in $taskFrozen){if((Hash (Join-Path $taskSnapshot $taskRow.path)) -cne $taskRow.sha256){throw "Frozen input changed: $($taskRow.path)"}}
 $taskResult.state='BUILT_NOT_INSTALLED_OR_PUBLISHED';$taskResult.sourceStable=$true;$taskResult.sourceManifestSha256=Hash "$taskSnapshot/source-manifest.json"
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{$env:JAVA_TOOL_OPTIONS=$taskPreviousOptions;Json "$taskOutput/receipt.json" $taskResult;Write-Output "RELEASE07=$($taskResult.state);RECEIPT=$taskOutput/receipt.json"}
