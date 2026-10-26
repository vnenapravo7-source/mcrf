param()
$ErrorActionPreference='Stop'
$taskProject='D:/MCRF/Projects/MCRF'
$taskPrevious='D:/MCRF/Builds/Android/openflux016-routes-ad57825a9b0a4ed8b65b28830824a2ef'
$taskProof=Get-Content "$taskPrevious/verification-receipt.json" -Raw|ConvertFrom-Json
if(!$taskProof.sourceStable -or $taskProof.tests -ne 53 -or !$taskProof.lintAndR8){throw 'Accepted common build required'}
$taskId=[guid]::NewGuid().ToString('N')
$taskSnapshot="$taskProject/work/android-public071-$taskId"
$taskOutput="D:/MCRF/Builds/Android/public071-$taskId"
$null=New-Item -ItemType Directory $taskSnapshot,$taskOutput
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Json([string]$Path,$Value){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))}
if((Hash "$($taskProof.snapshot)/source-manifest.json") -cne $taskProof.sourceManifestSha256){throw 'Base manifest differs'}
foreach($taskRow in (Get-Content "$($taskProof.snapshot)/source-manifest.json" -Raw|ConvertFrom-Json)){
 $taskFrom=[IO.Path]::GetFullPath((Join-Path $taskProof.snapshot $taskRow.path));$taskTo=[IO.Path]::GetFullPath((Join-Path $taskSnapshot $taskRow.path))
 if(!$taskFrom.StartsWith([IO.Path]::GetFullPath($taskProof.snapshot)+'\',[StringComparison]::OrdinalIgnoreCase) -or !$taskTo.StartsWith([IO.Path]::GetFullPath($taskSnapshot)+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Manifest escaped'}
 if((Hash $taskFrom) -cne $taskRow.sha256){throw 'Accepted input changed'}
 $null=New-Item -ItemType Directory (Split-Path $taskTo) -Force;Copy-Item -LiteralPath $taskFrom -Destination $taskTo
}
$taskAndroid="$taskSnapshot/ports/android"
Copy-Item -LiteralPath "$taskProject/ports/android/app/build.gradle.kts" -Destination "$taskAndroid/app/build.gradle.kts" -Force
# Only release-version packaging differs; preserve all tested application sources.
Json "$taskSnapshot/public071.json" @{authority='human-20261026-publish-0.7.1';state='SOURCE_READY';versionCode=10061;versionName='0.7.1'}
$taskFrozen=@(Get-ChildItem $taskSnapshot -Recurse -File|ForEach-Object{@{path=[IO.Path]::GetRelativePath($taskSnapshot,$_.FullName);sha256=(Hash $_.FullName)}})
Json "$taskSnapshot/source-manifest.json" $taskFrozen
foreach($taskModule in @('app','engines','primary-spi','primary-adapter')){$null=New-Item -ItemType Directory "$taskOutput/$taskModule";$null=New-Item -ItemType Junction -Path "$taskAndroid/$taskModule/build" -Target "$taskOutput/$taskModule"}
$taskResult=[ordered]@{state='BUILDING';snapshot=$taskSnapshot;sourceStable=$false;versionCode=10061;versionName='0.7.1';nativeStage=$taskProof.nativeStage;tests=53;suites=6;testsRetainedFromVerifiedUnchangedSources=$true;testReceiptSha256=(Hash "$taskPrevious/verification-receipt.json");lintAndR8=$false;files=@()}
$taskEnvironment=@{};foreach($taskKey in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$taskEnvironment[$taskKey]=[Environment]::GetEnvironmentVariable($taskKey)}
try{
 $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$taskProject/work/toolchains/gradle-home"
 $taskTemp=(New-Object -ComObject Scripting.FileSystemObject).GetFolder("$taskProject/work/toolchains/java-tmp").ShortPath
 $env:JAVA_TOOL_OPTIONS=($taskEnvironment.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir='+$taskTemp+'"').Trim()
 Push-Location $taskAndroid
 try{
  & "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain ':app:assembleRelease' ':app:lintRelease' --no-daemon --console=plain '-PtestRelease=true' "-Popenflux016Receipt=$taskSnapshot/openflux016.json" "-Popenflux016PublicReceipt=$taskSnapshot/public071.json" "-PopenfluxTeam016Stage=$taskSnapshot/openflux016-native" *> "$taskOutput/build.log"
  if($LASTEXITCODE){throw 'Final 0.7.1 packaging failed'}
 }finally{Pop-Location}
 foreach($taskRow in $taskFrozen){if((Hash (Join-Path $taskSnapshot $taskRow.path)) -cne $taskRow.sha256){throw 'Frozen input changed'}}
 $taskApk="$taskOutput/app/outputs/apk/release/app-universal-release.apk"
 $taskSigner=(& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --print-certs $taskApk 2>&1|Out-String)
 if($LASTEXITCODE -or $taskSigner -notmatch 'certificate SHA-256 digest:\s*fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Signer differs'}
 $taskBadging=(& "$env:ANDROID_HOME/build-tools/36.0.0/aapt.exe" dump badging $taskApk|Out-String)
 if($LASTEXITCODE -or $taskBadging -notmatch "name='org.mcrf.client' versionCode='10061' versionName='0.7.1'"){throw 'Release version differs'}
 $taskResult.files=@(@{kind='app';path=$taskApk;name='app-universal-release.apk';sha256=(Hash $taskApk);bytes=(Get-Item $taskApk).Length})+@($taskProof.files|Where-Object kind -eq 'openflux')
 $taskResult.state='BUILT_VERIFIED_NOT_INSTALLED';$taskResult.sourceStable=$true;$taskResult.lintAndR8=$true;$taskResult.sourceManifestSha256=Hash "$taskSnapshot/source-manifest.json"
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{foreach($taskKey in $taskEnvironment.Keys){[Environment]::SetEnvironmentVariable($taskKey,$taskEnvironment[$taskKey])};Json "$taskOutput/verification-receipt.json" $taskResult;Write-Output "PUBLIC071=$($taskResult.state);STAGE=$taskOutput"}
