param([Parameter(Mandatory)][string]$NativeStage,
 [string]$JavaHome='C:/Program Files/Java/jdk-17',
 [string]$AndroidSdk='C:/Users/dieonfire/AppData/Local/Android/Sdk')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation build
$taskRoot='D:/MCRF/Projects/MCRF/ports/android'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
$taskNative=(Resolve-Path -LiteralPath $NativeStage).Path.Replace([char]92,[char]47)
if($taskNative -cnotmatch '^D:/MCRF/Builds/Android/p3-protocols-native-[a-f0-9]{32}$'){throw 'Exact owned protocols stage required'}
$taskProof=Get-Content "$taskNative/receipt.json" -Raw|ConvertFrom-Json
if($taskProof.kind -cne 'protocols' -or $taskProof.sourceStable -ne $true -or
 $taskProof.state -cnotin @('COMPILED_LEGAL_AND_FIXTURE_PENDING','COMPILED_FIXTURE_PENDING') -or (Hash "$taskNative/protocols-owned.test") -cne $taskProof.fixtureSha256){throw 'Compile the frozen original fixture before building its emulator APK'}
$taskStage="D:/MCRF/Builds/Android/p3-protocols-fixture-app-$([guid]::NewGuid().ToString('N'))"
$taskSource="$taskStage/source";New-Item -ItemType Directory -Path $taskSource|Out-Null
$taskRows=@()
function Copy-Source([string]$Relative,[string]$Target=$Relative){
 $from=Get-Item -LiteralPath "$taskRoot/$Relative"
 if($from.PSIsContainer -or ($from.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Regular fixture source only'}
 $to="$taskSource/$Target";New-Item -ItemType Directory -Force -Path (Split-Path $to)|Out-Null
 Copy-Item -LiteralPath $from.FullName -Destination $to
 $script:taskRows+=@{original=$from.FullName;target=$to;sha256=Hash $to}
}
foreach($file in @('build.gradle.kts','gradle.properties','gradle/wrapper/gradle-wrapper.jar','gradle/wrapper/gradle-wrapper.properties','coordination/p3-p4-2026-10-23.json')){Copy-Source $file}
Copy-Source 'protocols-owned-fixture/fixture.settings.gradle.kts' 'settings.gradle.kts'
Copy-Source 'protocols-owned-fixture/build.gradle.kts'
foreach($file in Get-ChildItem -LiteralPath "$taskRoot/protocols-owned-fixture/src" -Recurse -File){
 Copy-Source ([IO.Path]::GetRelativePath($taskRoot,$file.FullName).Replace([char]92,[char]47))
}
Save $taskRows "$taskStage/SOURCE_MANIFEST.json"
$taskReceipt=[ordered]@{state='COMPILING_FIXTURE_APP_NOT_EXECUTED';nativeStage=$taskNative;fixtureSha256=$taskProof.fixtureSha256;sourceManifestSha256=Hash "$taskStage/SOURCE_MANIFEST.json";runtimeVerified=$false;phoneTouched=$false}
$taskOld=@{};foreach($name in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){$taskOld[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
 $env:JAVA_HOME=$JavaHome;$env:ANDROID_HOME=$AndroidSdk;$env:GRADLE_USER_HOME='D:/MCRF/Projects/MCRF/work/toolchains/gradle-home'
 $taskJavaTemp="$taskStage/jtmp";New-Item -ItemType Directory -Path $taskJavaTemp|Out-Null
 $taskShortTemp=(New-Object -ComObject Scripting.FileSystemObject).GetFolder($taskJavaTemp).ShortPath
 if($taskShortTemp.Length -gt 85){throw 'Fixture JDK socket path too long'}
 $env:JAVA_TOOL_OPTIONS=($taskOld['JAVA_TOOL_OPTIONS']+' "-Djdk.net.unixdomain.tmpdir='+$taskShortTemp+'"').Trim()
 if((Hash "$taskSource/gradle/wrapper/gradle-wrapper.jar") -cne '76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3'){throw 'Exact retained Gradle wrapper required'}
 Push-Location $taskSource
 try{
  & "$JavaHome/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain ':protocols-owned-fixture:assembleDebug' '-PprotocolsOwnedFixture=true' "-PprotocolsFixtureStage=$taskNative" '--offline' '--console=plain' '--no-daemon' *> "$taskStage/build.log"
  if($LASTEXITCODE){throw 'Owned fixture APK compilation failed; log retained'}
 }finally{Pop-Location}
 foreach($row in $taskRows){if((Hash $row.original) -cne $row.sha256 -or (Hash $row.target) -cne $row.sha256){throw 'Fixture source changed during common compilation'}}
 if((Hash "$taskNative/protocols-owned.test") -cne $taskProof.fixtureSha256){throw 'Fixture native bytes changed'}
 $apk="$taskSource/protocols-owned-fixture/build/outputs/apk/debug/protocols-owned-fixture-debug.apk"
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 $zip=[IO.Compression.ZipFile]::OpenRead($apk)
 try{
  $entry=$zip.GetEntry('lib/x86_64/libprotocols_fixture.so');if(!$entry){throw 'APK omitted actual native fixture'}
  $stream=$entry.Open();try{$sha=[Security.Cryptography.SHA256]::Create();try{$digest=([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}finally{$sha.Dispose()}}finally{$stream.Dispose()}
  if($digest -cne $taskProof.fixtureSha256){throw 'Packaged native fixture differs from producer proof'}
 }finally{$zip.Dispose()}
 $taskReceipt.state='COMPILED_FIXTURE_APK_NOT_EXECUTED';$taskReceipt.apk=$apk;$taskReceipt.apkSha256=Hash $apk;$taskReceipt.sourceStable=$true
}catch{$taskReceipt.state='FAILED_FIXTURE_APP_PRESERVED';$taskReceipt.failure=$_.Exception.Message;throw}
finally{foreach($name in $taskOld.Keys){[Environment]::SetEnvironmentVariable($name,$taskOld[$name],'Process')};Save $taskReceipt "$taskStage/receipt.json";Write-Output "P3_PROTOCOLS_FIXTURE_APP=$taskStage"}
