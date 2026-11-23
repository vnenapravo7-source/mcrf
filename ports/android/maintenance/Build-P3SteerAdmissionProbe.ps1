param([Parameter(Mandatory)][string]$Arm64Module)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation build
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
$taskModule=(Resolve-Path $Arm64Module).Path
if($taskModule -cnotmatch 'engines-steer-arm64-v8a-release\.apk$'){throw 'Exact new ARM64 module required'}
$taskRoot="D:/MCRF/Builds/Android/p3-steer-arm64-admission-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $taskRoot,"$taskRoot/classes","$taskRoot/dex","$taskRoot/assets"|Out-Null
Copy-Item "$PSScriptRoot/next-runtime/P3SteerAdmissionInstrumentation.java" "$taskRoot/P3SteerAdmissionInstrumentation.java"
[IO.File]::WriteAllText("$taskRoot/assets/module.sha256",(Hash $taskModule)+"`n",[Text.Encoding]::ASCII)
[IO.File]::WriteAllText("$taskRoot/AndroidManifest.xml",'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.mcrf.p3steeradmission"><uses-sdk android:minSdkVersion="29" android:targetSdkVersion="34"/><queries><package android:name="org.mcrf.engines.steer"/></queries><application android:label="MCRF read-only ARM64 Steer admission" android:allowBackup="false" android:testOnly="true"/><instrumentation android:name="org.mcrf.runtime.P3SteerAdmissionInstrumentation" android:targetPackage="org.mcrf.p3steeradmission" android:functionalTest="true"/></manifest>',[Text.UTF8Encoding]::new($false))
$taskSdk='C:/Users/dieonfire/AppData/Local/Android/Sdk';$taskJava='C:/Program Files/Java/jdk-17/bin';$taskTools="$taskSdk/build-tools/36.0.0";$taskAndroid="$taskSdk/platforms/android-34/android.jar"
function Run([string]$Name,[string]$Exe,[string[]]$Arguments){& $Exe @Arguments *> "$taskRoot/$Name.log";if($LASTEXITCODE){throw "$Name failed; retain source/log"}}
Run 'module-signature' "$taskTools/apksigner.bat" @('verify','--print-certs',$taskModule)
if([IO.File]::ReadAllText("$taskRoot/module-signature.log") -notmatch 'certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Exact signer required'}
Run 'javac' "$taskJava/javac.exe" @('--release','8','-encoding','UTF-8','-classpath',$taskAndroid,'-d',"$taskRoot/classes","$taskRoot/P3SteerAdmissionInstrumentation.java")
Run 'jar' "$taskJava/jar.exe" @('cf',"$taskRoot/classes.jar",'-C',"$taskRoot/classes",'.')
Run 'd8' "$taskTools/d8.bat" @('--release','--min-api','29','--lib',$taskAndroid,'--output',"$taskRoot/dex","$taskRoot/classes.jar")
Run 'aapt' "$taskTools/aapt.exe" @('package','-f','-M',"$taskRoot/AndroidManifest.xml",'-A',"$taskRoot/assets",'-I',$taskAndroid,'-F',"$taskRoot/unsigned.apk")
Push-Location "$taskRoot/dex";try{Run 'aapt-add' "$taskTools/aapt.exe" @('add',"$taskRoot/unsigned.apk",'classes.dex')}finally{Pop-Location}
Run 'zipalign' "$taskTools/zipalign.exe" @('-f','4',"$taskRoot/unsigned.apk","$taskRoot/aligned.apk")
Run 'sign' "$taskTools/apksigner.bat" @('sign','--ks','C:/Users/dieonfire/.android/debug.keystore','--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',"$taskRoot/probe.apk","$taskRoot/aligned.apk")
$taskReceipt=@{state='ARM64_READ_ONLY_HELPER_COMPILED_NOT_RUN';apk="$taskRoot/probe.apk";apkSha256=Hash "$taskRoot/probe.apk";moduleApk=$taskModule;moduleApkSha256=Hash $taskModule;sourceSha256=Hash "$taskRoot/P3SteerAdmissionInstrumentation.java";selfTargeted=$true;productionRebuilt=$false;networkRequested=$false;userStoresRead=$false}
[IO.File]::WriteAllText("$taskRoot/receipt.json",($taskReceipt|ConvertTo-Json),[Text.UTF8Encoding]::new($false));Write-Output "P3_STEER_ARM64_PROBE=$taskRoot"
