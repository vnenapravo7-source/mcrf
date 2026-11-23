param([Parameter(Mandatory)][string]$NativeStage,[string]$JavaHome='C:/Program Files/Java/jdk-17',[string]$AndroidSdk='C:/Users/dieonfire/AppData/Local/Android/Sdk')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation build
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
$taskRoot='D:/MCRF/Projects/MCRF/ports/android'
$taskNative=(Resolve-Path $NativeStage).Path.Replace([char]92,[char]47)
if($taskNative -cnotmatch '^D:/MCRF/Builds/Android/p3-slipstream-native-[a-f0-9]{32}$'){throw 'Exact native stage required'}
$taskProof=Get-Content "$taskNative/receipt.json" -Raw|ConvertFrom-Json
if(!$taskProof.sourceStable -or $taskProof.state -cnotin @('COMPILED_FIXTURE_PENDING','FIXTURE_FAILED_PRESERVED')){throw 'Unaccepted compiled Slipstream required'}
$taskStage="D:/MCRF/Builds/Android/p3-slipstream-fixture-app-$([guid]::NewGuid().ToString('N'))"
$taskSource="$taskStage/source";New-Item -ItemType Directory -Path $taskSource|Out-Null
$taskRows=@()
function Copy-Source([string]$Relative,[string]$Target=$Relative){
 $from=Get-Item -LiteralPath "$taskRoot/$Relative";if($from.LinkType -or $from.PSIsContainer){throw 'Regular source required'}
 $to="$taskSource/$Target";New-Item -ItemType Directory -Path (Split-Path $to) -Force|Out-Null;Copy-Item -LiteralPath $from.FullName -Destination $to
 $script:taskRows+=@{original=$from.FullName;target=$to;sha256=Hash $to}
}
foreach($file in @('build.gradle.kts','gradle.properties','gradle/wrapper/gradle-wrapper.jar','gradle/wrapper/gradle-wrapper.properties')){Copy-Source $file}
Copy-Source 'slipstream-owned-fixture/fixture.settings.gradle.kts' 'settings.gradle.kts';Copy-Source 'slipstream-owned-fixture/build.gradle.kts'
Get-ChildItem "$taskRoot/slipstream-owned-fixture/src" -Recurse -File|ForEach-Object {Copy-Source ([IO.Path]::GetRelativePath($taskRoot,$_.FullName).Replace([char]92,[char]47))}
Save $taskRows "$taskStage/SOURCE_MANIFEST.json"
$taskReceipt=@{state='COMPILING_FIXTURE_APP_NOT_EXECUTED';nativeStage=$taskNative;sourceManifestSha256=Hash "$taskStage/SOURCE_MANIFEST.json";phoneTouched=$false;runtimeVerified=$false}
$taskOld=@{};foreach($name in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$taskOld[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
 $env:JAVA_HOME=$JavaHome;$env:ANDROID_HOME=$AndroidSdk;$env:GRADLE_USER_HOME='D:/MCRF/Projects/MCRF/work/toolchains/gradle-home'
 $taskTemp="$taskStage/jtmp";New-Item -ItemType Directory -Path $taskTemp|Out-Null
 $taskShort=(New-Object -ComObject Scripting.FileSystemObject).GetFolder($taskTemp).ShortPath
 $env:JAVA_TOOL_OPTIONS=($taskOld.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir='+$taskShort+'"').Trim()
 if((Hash "$taskSource/gradle/wrapper/gradle-wrapper.jar") -cne '76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3'){throw 'Pinned wrapper required'}
 Push-Location $taskSource
 try{& "$JavaHome/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain ':slipstream-owned-fixture:assembleDebug' '-PslipstreamOwnedFixture=true' "-PslipstreamFixtureStage=$taskNative" '--offline' '--console=plain' '--no-daemon' *> "$taskStage/build.log";if($LASTEXITCODE){throw 'Owned Slipstream APK build failed'}}finally{Pop-Location}
 foreach($row in $taskRows){if((Hash $row.original) -cne $row.sha256 -or (Hash $row.target) -cne $row.sha256){throw 'Fixture source changed'}}
 $apk="$taskSource/slipstream-owned-fixture/build/outputs/apk/debug/slipstream-owned-fixture-debug.apk"
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 $taskZip=[IO.Compression.ZipFile]::OpenRead($apk)
 try{foreach($row in @($taskProof.artifacts)+@($taskProof.fixtureArtifacts)|Where-Object {$_.file -match '^x86_64/|^fixtures/'}){
  $entry=$taskZip.GetEntry('lib/x86_64/'+[IO.Path]::GetFileName($row.file));if(!$entry){throw 'Native APK entry missing'}
  $stream=$entry.Open();try{$digest=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()}finally{$stream.Dispose()}
  if($digest -cne $row.sha256){throw 'APK changed native fixture bytes'}
 }}finally{$taskZip.Dispose()}
 $taskReceipt.state='COMPILED_FIXTURE_APK_NOT_EXECUTED';$taskReceipt.apk=$apk;$taskReceipt.apkSha256=Hash $apk;$taskReceipt.sourceStable=$true
}catch{$taskReceipt.state='FAILED_FIXTURE_APP_PRESERVED';$taskReceipt.failure=$_.Exception.Message;throw}
finally{foreach($name in $taskOld.Keys){[Environment]::SetEnvironmentVariable($name,$taskOld[$name],'Process')};Save $taskReceipt "$taskStage/receipt.json";Write-Output "P3_SLIPSTREAM_FIXTURE_APP=$taskStage"}
