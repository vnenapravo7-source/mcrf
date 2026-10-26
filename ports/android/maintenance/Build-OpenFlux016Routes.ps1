param([Parameter(Mandatory=$true)][string]$NativeStage)
$ErrorActionPreference='Stop'
$taskProject='D:/MCRF/Projects/MCRF';$taskCanonical="$taskProject/ports/android"
$taskBaseline=[IO.Path]::GetFullPath("$taskProject/work/android-release07-245e566d90dd490bb31202c5c6612fc0")
$taskBaselineReceipt='D:/MCRF/Builds/Android/release07-245e566d90dd490bb31202c5c6612fc0/receipt.json'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Json([string]$Path,$Value){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))}
$taskPrevious=Get-Content $taskBaselineReceipt -Raw|ConvertFrom-Json
if(!$taskPrevious.sourceStable -or (Hash "$taskBaseline/source-manifest.json") -cne $taskPrevious.sourceManifestSha256){throw 'Accepted0.7 source receipt differs'}
$taskNativeProof=Get-Content "$NativeStage/receipt.json" -Raw|ConvertFrom-Json
if($taskNativeProof.state -cne 'BUILT_HOST_VERIFIED_NOT_PHONE_ACCEPTED' -or !$taskNativeProof.sourceStable){throw 'Verified016 source stage required'}
$taskId=[guid]::NewGuid().ToString('N');$taskSnapshot=[IO.Path]::GetFullPath("$taskProject/work/android-openflux016-$taskId");$taskOutput="D:/MCRF/Builds/Android/openflux016-routes-$taskId"
$null=New-Item -ItemType Directory $taskSnapshot,$taskOutput
foreach($taskRow in (Get-Content "$taskBaseline/source-manifest.json" -Raw|ConvertFrom-Json)){
 $taskFrom=[IO.Path]::GetFullPath((Join-Path $taskBaseline $taskRow.path));$taskTo=[IO.Path]::GetFullPath((Join-Path $taskSnapshot $taskRow.path))
 if(!$taskFrom.StartsWith($taskBaseline+'\',[StringComparison]::OrdinalIgnoreCase) -or !$taskTo.StartsWith($taskSnapshot+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Manifest escaped snapshot'}
 if((Hash $taskFrom) -cne $taskRow.sha256){throw "Accepted source changed: $($taskRow.path)"}
 $null=New-Item -ItemType Directory (Split-Path $taskTo) -Force;Copy-Item -LiteralPath $taskFrom -Destination $taskTo
}
$taskAndroid="$taskSnapshot/ports/android"
$taskOverlay=@('app/build.gradle.kts','engines/build.gradle.kts','engines/src/openflux/AndroidManifest.xml')
foreach($taskName in @('OpenFluxProfile','EngineUpdatePolicy','EngineUpdateScripts','NativeCarrier','RoutingConnectionBinding','RoutingConnectionBindingUi','RouteBindingSelection','RoutingPage','RoutingStore','RoutingGroups','RouteRules','RouteServerPlan','MainActivity','McrfVpnService','NativeRoutePreparation')){$taskOverlay+="app/src/main/kotlin/org/mcrf/client/$taskName.kt"}
foreach($taskName in @('OpenFluxTeam016Test','RoutingConnectionBindingTest','RouteServerPlanTest')){$taskOverlay+="app/src/test/kotlin/org/mcrf/client/$taskName.kt"}
foreach($taskProperty in $taskNativeProof.inputs.PSObject.Properties){$taskOverlay+='transports/openflux/'+$taskProperty.Name}
$taskInputs=@{}
foreach($taskName in $taskOverlay){$taskFrom="$taskCanonical/$taskName";$taskTo="$taskAndroid/$taskName";$taskInputs[$taskName]=Hash $taskFrom;$null=New-Item -ItemType Directory (Split-Path $taskTo) -Force;Copy-Item -LiteralPath $taskFrom -Destination $taskTo -Force}
Copy-Item -LiteralPath $NativeStage -Destination "$taskSnapshot/openflux016-native" -Recurse
Json "$taskSnapshot/openflux016.json" @{authority='human-20261026-support-openflux016';state='SOURCE_READY';versionCode=10060;versionName='0.7.1-candidate.1';publish=$false}
$taskFrozen=@(Get-ChildItem $taskSnapshot -Recurse -File|ForEach-Object{@{path=[IO.Path]::GetRelativePath($taskSnapshot,$_.FullName);sha256=(Hash $_.FullName)}})
Json "$taskSnapshot/source-manifest.json" $taskFrozen
foreach($taskModule in @('app','engines','primary-spi','primary-adapter')){$null=New-Item -ItemType Directory "$taskOutput/$taskModule";$null=New-Item -ItemType Junction -Path "$taskAndroid/$taskModule/build" -Target "$taskOutput/$taskModule"}
$taskResult=[ordered]@{state='BUILDING';snapshot=$taskSnapshot;sourceStable=$false;versionCode=10060;versionName='0.7.1-candidate.1';installed=$false;published=$false;inputs=$taskInputs;nativeStage=$NativeStage;files=@()}
$taskEnvironment=@{};foreach($taskKey in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$taskEnvironment[$taskKey]=[Environment]::GetEnvironmentVariable($taskKey)}
try{
 $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$taskProject/work/toolchains/gradle-home"
 $taskTemp=(New-Object -ComObject Scripting.FileSystemObject).GetFolder("$taskProject/work/toolchains/java-tmp").ShortPath
 $env:JAVA_TOOL_OPTIONS=($taskEnvironment.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir='+$taskTemp+'"').Trim()
 Push-Location $taskAndroid
 try{
  & "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain ':app:testDebugUnitTest' '--tests' 'org.mcrf.client.OpenFluxTeam016Test' '--tests' 'org.mcrf.client.OpenFluxProfileTest' '--tests' 'org.mcrf.client.EngineUpdatePolicyTest' '--tests' 'org.mcrf.client.RoutingConnectionBindingTest' '--tests' 'org.mcrf.client.RoutingGroupsTest' '--tests' 'org.mcrf.client.RouteServerPlanTest' ':app:assembleRelease' ':app:lintRelease' ':engines:assembleOpenfluxRelease' --no-daemon --console=plain '-PtestRelease=true' "-Popenflux016Receipt=$taskSnapshot/openflux016.json" "-PopenfluxTeam016Stage=$taskSnapshot/openflux016-native" *> "$taskOutput/build.log"
  if($LASTEXITCODE){throw 'Common016/routes verification/build failed; inspect build.log'}
 }finally{Pop-Location}
 foreach($taskRow in $taskFrozen){if((Hash (Join-Path $taskSnapshot $taskRow.path)) -cne $taskRow.sha256){throw 'Frozen build input changed'}}
 foreach($taskName in $taskInputs.Keys){if((Hash "$taskCanonical/$taskName") -cne $taskInputs[$taskName]){throw 'Canonical source changed during build'}}
 $taskApks=@(Get-ChildItem "$taskOutput/app/outputs/apk/release" -Filter 'app-universal-release.apk')+@(Get-ChildItem "$taskOutput/engines/outputs/apk/openflux/release" -Filter '*.apk')
 if($taskApks.Count -ne 5){throw 'Expected main + three ABI modules + universal module'}
 foreach($taskApk in $taskApks){
  $taskSigner=(& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --print-certs $taskApk.FullName 2>&1|Out-String)
  if($LASTEXITCODE -or $taskSigner -notmatch 'certificate SHA-256 digest:\s*fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'APK signer differs'}
  $taskBadging=(& "$env:ANDROID_HOME/build-tools/36.0.0/aapt.exe" dump badging $taskApk.FullName|Out-String)
  $taskKind=if($taskBadging -match "name='org.mcrf.client' versionCode='10060'"){'app'}elseif($taskBadging -match "name='org.mcrf.engines.openflux' versionCode='15'"){'openflux'}else{throw 'APK package/version differs'}
  $taskResult.files+=@{kind=$taskKind;path=$taskApk.FullName;name=$taskApk.Name;sha256=(Hash $taskApk.FullName);bytes=$taskApk.Length}
 }
 $taskResult.state='BUILT_VERIFIED_NOT_INSTALLED';$taskResult.sourceStable=$true;$taskResult.sourceManifestSha256=Hash "$taskSnapshot/source-manifest.json"
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{foreach($taskKey in $taskEnvironment.Keys){[Environment]::SetEnvironmentVariable($taskKey,$taskEnvironment[$taskKey])};Json "$taskOutput/receipt.json" $taskResult;Write-Output "OPENFLUX016_ROUTES=$($taskResult.state);STAGE=$taskOutput"}
