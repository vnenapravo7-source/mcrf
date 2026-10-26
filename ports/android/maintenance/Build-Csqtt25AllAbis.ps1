param()
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskProject='D:/MCRF/Projects/MCRF'
$taskPrevious='D:/MCRF/Builds/Android/public071-8da7ba54413b4ef1b8dda5c90f443d39'
$taskProof=Get-Content "$taskPrevious/verification-receipt.json" -Raw|ConvertFrom-Json
if(!$taskProof.sourceStable -or $taskProof.versionName -cne '0.7.1'){throw 'Accepted final0.7.1 source required'}
$taskPublication=Get-Content 'D:/MCRF/Builds/Android/publish071-20261026/publication-receipt.json' -Raw|ConvertFrom-Json
if(!$taskPublication.published){throw 'Human order: publish app before CSQTT changes'}
$taskId=[guid]::NewGuid().ToString('N');$taskSnapshot="$taskProject/work/android-csqtt25-abis-$taskId";$taskOutput="D:/MCRF/Builds/Android/csqtt25-abis-$taskId"
$null=New-Item -ItemType Directory $taskSnapshot,$taskOutput
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Json([string]$Path,$Value){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 30),[Text.UTF8Encoding]::new($false))}
if((Hash "$($taskProof.snapshot)/source-manifest.json") -cne $taskProof.sourceManifestSha256){throw 'Base manifest differs'}
foreach($taskRow in (Get-Content "$($taskProof.snapshot)/source-manifest.json" -Raw|ConvertFrom-Json)){
 $taskFrom=[IO.Path]::GetFullPath((Join-Path $taskProof.snapshot $taskRow.path));$taskTo=[IO.Path]::GetFullPath((Join-Path $taskSnapshot $taskRow.path))
 if(!$taskFrom.StartsWith([IO.Path]::GetFullPath($taskProof.snapshot)+'\',[StringComparison]::OrdinalIgnoreCase) -or !$taskTo.StartsWith([IO.Path]::GetFullPath($taskSnapshot)+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Manifest escaped'}
 if((Hash $taskFrom) -cne $taskRow.sha256){throw 'Accepted input changed'}
 $null=New-Item -ItemType Directory (Split-Path $taskTo) -Force;Copy-Item -LiteralPath $taskFrom -Destination $taskTo
}
$taskAndroid="$taskSnapshot/ports/android";$taskStage="$taskAndroid/native-stage-csqtt25"
Copy-Item -LiteralPath "$taskProject/ports/android/engines/build.gradle.kts" -Destination "$taskAndroid/engines/build.gradle.kts" -Force
$taskDescriptor=Get-Content "$taskStage/receipt.json" -Raw|ConvertFrom-Json
if($taskDescriptor.packageVersionCode -ne 6 -or @($taskDescriptor.artifacts).Count -ne 6){throw 'Expected complete retained3ABI native stage'}
$taskDescriptor.packageVersionCode=7;$taskDescriptor.packageVersionName='2.5.1-swgpanel-abi.7'
Json "$taskStage/receipt.json" $taskDescriptor
$taskFrozen=@(Get-ChildItem $taskSnapshot -Recurse -File|ForEach-Object{@{path=[IO.Path]::GetRelativePath($taskSnapshot,$_.FullName);sha256=(Hash $_.FullName)}})
Json "$taskSnapshot/source-manifest.json" $taskFrozen
foreach($taskModule in @('app','engines','primary-spi','primary-adapter')){$null=New-Item -ItemType Directory "$taskOutput/$taskModule";$null=New-Item -ItemType Junction -Path "$taskAndroid/$taskModule/build" -Target "$taskOutput/$taskModule"}
$taskResult=[ordered]@{state='BUILDING';snapshot=$taskSnapshot;sourceStable=$false;versionCode=7;versionName=$taskDescriptor.packageVersionName;kind='csqtt25';nativeRebuilt=$false;deviceRuntimeTested=$false;installed=$false;published=$false;files=@()}
$taskEnvironment=@{};foreach($taskKey in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$taskEnvironment[$taskKey]=[Environment]::GetEnvironmentVariable($taskKey)}
try{
 $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$taskProject/work/toolchains/gradle-home"
 $taskTemp=(New-Object -ComObject Scripting.FileSystemObject).GetFolder("$taskProject/work/toolchains/java-tmp").ShortPath
 $env:JAVA_TOOL_OPTIONS=($taskEnvironment.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir='+$taskTemp+'"').Trim()
 Push-Location $taskAndroid
 try{
  & "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain ':engines:assembleCsqtt25Release' ':engines:lintCsqtt25Release' --no-daemon --console=plain '-PtestRelease=true' '-Pcsqtt25CandidateGate=true' "-Pcsqtt25Stage=$taskStage" *> "$taskOutput/build.log"
  if($LASTEXITCODE){throw 'CSQTT25 common ABI packaging/lint failed'}
 }finally{Pop-Location}
 foreach($taskRow in $taskFrozen){if((Hash (Join-Path $taskSnapshot $taskRow.path)) -cne $taskRow.sha256){throw 'Frozen input changed'}}
 $taskApks=@(Get-ChildItem "$taskOutput/engines/outputs/apk/csqtt25/release" -File -Filter '*.apk')
 if($taskApks.Count -ne 4){throw 'Four APK outputs required'}
 foreach($taskApk in $taskApks){
  if($taskApk.Name -notmatch '^engines-csqtt25-(arm64-v8a|armeabi-v7a|x86_64|universal)-release\.apk$'){throw 'Unexpected APK name'};$taskAbi=$Matches[1]
  $taskSigner=(& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --print-certs $taskApk.FullName 2>&1|Out-String)
  if($LASTEXITCODE -or $taskSigner -notmatch 'certificate SHA-256 digest:\s*fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Signer differs'}
  $taskBadging=(& "$env:ANDROID_HOME/build-tools/36.0.0/aapt.exe" dump badging $taskApk.FullName|Out-String)
  if($LASTEXITCODE -or $taskBadging -notmatch "name='org.mcrf.engines.csqtt25' versionCode='7' versionName='2.5.1-swgpanel-abi.7'"){throw 'Module identity differs'}
  $taskExpectedAbis=if($taskAbi -eq 'universal'){@('arm64-v8a','armeabi-v7a','x86_64')}else{@($taskAbi)}
  $taskZip=[IO.Compression.ZipFile]::OpenRead($taskApk.FullName)
  try{
   $taskLibs=@($taskZip.Entries|Where-Object {$_.FullName.StartsWith('lib/') -and !$_.FullName.EndsWith('/')}|ForEach-Object {$_.FullName}|Sort-Object)
   $taskExpected=@($taskExpectedAbis|ForEach-Object {"lib/$_/libcsqtt25.so";"lib/$_/libcsqtt25_transport.so"}|Sort-Object)
   if(($taskLibs -join '|') -cne ($taskExpected -join '|')){throw 'Missing/foreign ABI library'}
   foreach($taskName in $taskExpected){$taskEntry=$taskZip.GetEntry($taskName);$taskStream=$taskEntry.Open();try{$taskSha=[Security.Cryptography.SHA256]::Create();try{$taskHash=([BitConverter]::ToString($taskSha.ComputeHash($taskStream))).Replace('-','').ToLowerInvariant()}finally{$taskSha.Dispose()}}finally{$taskStream.Dispose()};if($taskHash -cne (Hash "$taskStage/$($taskName.Substring(4))")){throw 'Packaged native differs'}}
   foreach($taskNotice in @('CSQTT-LICENSE','qwdtt-LICENSE')){if(!$taskZip.GetEntry("assets/licenses/$taskNotice")){throw 'License notice missing'}}
  }finally{$taskZip.Dispose()}
  $taskResult.files+=@{abi=$taskAbi;path=$taskApk.FullName;name=$taskApk.Name;bytes=$taskApk.Length;sha256=(Hash $taskApk.FullName)}
 }
 $taskResult.state='BUILT_VERIFIED_NOT_INSTALLED_OR_PUBLISHED';$taskResult.sourceStable=$true;$taskResult.lint=$true;$taskResult.sourceManifestSha256=Hash "$taskSnapshot/source-manifest.json"
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{foreach($taskKey in $taskEnvironment.Keys){[Environment]::SetEnvironmentVariable($taskKey,$taskEnvironment[$taskKey])};Json "$taskOutput/receipt.json" $taskResult;Write-Output "CSQTT25_ABIS=$($taskResult.state);STAGE=$taskOutput"}
