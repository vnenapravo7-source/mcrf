param([Parameter(Mandatory)][string]$DeviceEvidence)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation build
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
$taskEvidence=(Resolve-Path $DeviceEvidence).Path.Replace([char]92,[char]47)
if($taskEvidence -cnotmatch '^D:/MCRF/Builds/Android/p3-p4-device-checks-[a-f0-9]{32}$'){throw 'Exact reviewed device evidence required'}
$taskPreflight=Get-Content "$taskEvidence/module-preflight.json" -Raw|ConvertFrom-Json
if($taskPreflight.Count -ne 14){throw 'Exact14-module common package required'}
foreach($row in $taskPreflight){if($row.sha256 -cne (Hash $row.apk) -or $row.signer -cne 'fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Reviewed common input changed'}}
$taskRoot="D:/MCRF/Builds/Android/p3-phone-module-probe-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $taskRoot,"$taskRoot/classes","$taskRoot/dex","$taskRoot/assets"|Out-Null
Copy-Item "$PSScriptRoot/next-runtime/P3PhoneModuleInstrumentation.java" "$taskRoot/P3PhoneModuleInstrumentation.java"
# Only public package identities/digests, not local paths or application stores.
$taskPublic=@($taskPreflight|ForEach-Object {@{package=$_.package;versionCode=$_.versionCode;sha256=$_.sha256}})
[IO.File]::WriteAllText("$taskRoot/assets/modules.json",($taskPublic|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText("$taskRoot/AndroidManifest.xml",'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.mcrf.client.p3modules"><uses-sdk android:minSdkVersion="29" android:targetSdkVersion="34"/><application android:label="MCRF read-only module checks" android:allowBackup="false" android:testOnly="true"/><instrumentation android:name="org.mcrf.runtime.P3PhoneModuleInstrumentation" android:targetPackage="org.mcrf.client" android:functionalTest="true"/></manifest>',[Text.UTF8Encoding]::new($false))
$taskSdk='C:/Users/dieonfire/AppData/Local/Android/Sdk';$taskJava='C:/Program Files/Java/jdk-17/bin';$taskTools="$taskSdk/build-tools/36.0.0";$taskAndroid="$taskSdk/platforms/android-34/android.jar"
function Run([string]$Name,[string]$Exe,[string[]]$Arguments){& $Exe @Arguments *> "$taskRoot/$Name.log";if($LASTEXITCODE){throw "$Name failed; retained log"}}
Run 'javac' "$taskJava/javac.exe" @('--release','8','-encoding','UTF-8','-classpath',$taskAndroid,'-d',"$taskRoot/classes","$taskRoot/P3PhoneModuleInstrumentation.java")
Run 'jar' "$taskJava/jar.exe" @('cf',"$taskRoot/classes.jar",'-C',"$taskRoot/classes",'.')
Run 'd8' "$taskTools/d8.bat" @('--release','--min-api','29','--lib',$taskAndroid,'--output',"$taskRoot/dex","$taskRoot/classes.jar")
Run 'aapt' "$taskTools/aapt.exe" @('package','-f','-M',"$taskRoot/AndroidManifest.xml",'-A',"$taskRoot/assets",'-I',$taskAndroid,'-F',"$taskRoot/unsigned.apk")
Push-Location "$taskRoot/dex";try{Run 'aapt-add' "$taskTools/aapt.exe" @('add',"$taskRoot/unsigned.apk",'classes.dex')}finally{Pop-Location}
Run 'zipalign' "$taskTools/zipalign.exe" @('-f','4',"$taskRoot/unsigned.apk","$taskRoot/aligned.apk")
Run 'sign' "$taskTools/apksigner.bat" @('sign','--ks','C:/Users/dieonfire/.android/debug.keystore','--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',"$taskRoot/probe.apk","$taskRoot/aligned.apk")
Run 'signature' "$taskTools/apksigner.bat" @('verify','--print-certs',"$taskRoot/probe.apk")
if([IO.File]::ReadAllText("$taskRoot/signature.log") -notmatch 'certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Same-signature helper required'}
$taskReceipt=@{state='READ_ONLY_HELPER_COMPILED_NOT_RUN';apk="$taskRoot/probe.apk";apkSha256=Hash "$taskRoot/probe.apk";sourceSha256=Hash "$taskRoot/P3PhoneModuleInstrumentation.java";modulesSha256=Hash "$taskRoot/assets/modules.json";deviceEvidence=$taskEvidence;productionRebuilt=$false;profilesRead=$false;nativeExecuted=$false;networkRequested=$false}
[IO.File]::WriteAllText("$taskRoot/receipt.json",($taskReceipt|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
Write-Output "P3_PHONE_MODULE_PROBE=$taskRoot"
