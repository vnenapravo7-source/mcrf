param([switch]$Native,[switch]$Cleanup,[switch]$ProfileMetadata)
$ErrorActionPreference='Stop'
$taskRoot='D:/MCRF/Projects/MCRF';$taskSdk='C:/Users/dieonfire/AppData/Local/Android/Sdk';$taskJava='C:/Program Files/Java/jdk-17/bin'
$taskStage='D:/MCRF/Builds/Android/p049-phone-29ab72fdecd74d03a163fb5cfa7c0349/helper'
if($Native){$taskStage+='-native'}
if($Cleanup){$taskStage+='-cleanup'}
if($ProfileMetadata){$taskStage+='-metadata'}
if(Test-Path -LiteralPath $taskStage){throw 'Helper attempt exists; inspect first'}
$taskFiles=@('AndroidManifest.xml','TimingInstrumentation.java');$taskInputs=@{}
New-Item -ItemType Directory -Path $taskStage,"$taskStage/classes","$taskStage/dex"|Out-Null
foreach($name in $taskFiles){$p="$taskRoot/ports/android/maintenance/p049-helper/$name";Copy-Item -LiteralPath $p -Destination "$taskStage/$name";$taskInputs[$name]=(Get-FileHash -LiteralPath $p).Hash}
if(-not $Native){throw 'Current helper source requires explicit -Native; old reader receipt is preserved'}
$taskNative='D:/MCRF/Builds/Android/p049-candidate-701f35cbd75f41dab40a97dafdca7cb0/source/ports/android/app/src/androidTest/java/org/mcrf/client/ReleasePrimaryNativeChecks.java'
$taskInputs['ReleasePrimaryNativeChecks.java']=(Get-FileHash -LiteralPath $taskNative).Hash
$taskText=[IO.File]::ReadAllText($taskNative).Replace('package org.mcrf.client;','package org.mcrf.timing;')
# Mechanical package relocation only. Original framework-only fixture is frozen;
# no application class references or private profile/config reads are introduced.
[IO.File]::WriteAllText("$taskStage/ReleasePrimaryNativeChecks.java",$taskText,[Text.UTF8Encoding]::new($false))
$taskResult=[ordered]@{state='BUILDING';scope='Temporary framework-only reader plus frozen three-core JNI validation and STOP/death fixture; no user profiles/settings/TUN/network/listener changes';sources=$taskInputs}
if($ProfileMetadata){$taskResult.scope='Exact10024 app READ API; selected-profile enums/counts/parity only in RAM; no raw URI/keys/addresses exported, no settings/catalogue mutation or production APK compilation'}
try{
 & "$taskJava/javac.exe" --release 8 -encoding UTF-8 -classpath "$taskSdk/platforms/android-34/android.jar" -d "$taskStage/classes" "$taskStage/TimingInstrumentation.java" "$taskStage/ReleasePrimaryNativeChecks.java" *> "$taskStage/javac.log";if($LASTEXITCODE){throw 'Helper javac failed'}
 & "$taskJava/jar.exe" cf "$taskStage/classes.jar" -C "$taskStage/classes" .;if($LASTEXITCODE){throw 'Helper jar failed'}
 & "$taskSdk/build-tools/36.0.0/d8.bat" --release --min-api 29 --lib "$taskSdk/platforms/android-34/android.jar" --output "$taskStage/dex" "$taskStage/classes.jar" *> "$taskStage/d8.log";if($LASTEXITCODE){throw 'Helper dex failed'}
 & "$taskSdk/build-tools/36.0.0/aapt.exe" package -f -M "$taskStage/AndroidManifest.xml" -I "$taskSdk/platforms/android-34/android.jar" -F "$taskStage/unsigned.apk" *> "$taskStage/aapt.log";if($LASTEXITCODE){throw 'Helper manifest failed'}
 Push-Location "$taskStage/dex";try{& "$taskSdk/build-tools/36.0.0/aapt.exe" add "$taskStage/unsigned.apk" classes.dex *> "$taskStage/add.log";if($LASTEXITCODE){throw 'Helper dex package failed'}}finally{Pop-Location}
 & "$taskSdk/build-tools/36.0.0/zipalign.exe" -f 4 "$taskStage/unsigned.apk" "$taskStage/aligned.apk";if($LASTEXITCODE){throw 'Helper alignment failed'}
 & "$taskSdk/build-tools/36.0.0/apksigner.bat" sign --ks 'C:/Users/dieonfire/.android/debug.keystore' --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$taskStage/timing49.apk" "$taskStage/aligned.apk";if($LASTEXITCODE){throw 'Helper signing failed'}
 $taskCert=(& "$taskSdk/build-tools/36.0.0/apksigner.bat" verify --print-certs "$taskStage/timing49.apk" 2>&1|Out-String)
 if($LASTEXITCODE -or $taskCert -notmatch 'certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Helper signer mismatch'}
 foreach($name in $taskFiles){if((Get-FileHash -LiteralPath "$taskStage/$name").Hash -cne $taskInputs[$name]){throw 'Helper source drift'}}
 if((Get-FileHash -LiteralPath $taskNative).Hash -cne $taskInputs['ReleasePrimaryNativeChecks.java']){throw 'Frozen native fixture drift'}
 $taskResult.state='PASS';$taskResult.apk="$taskStage/timing49.apk";$taskResult.sha256=(Get-FileHash -LiteralPath "$taskStage/timing49.apk").Hash.ToLowerInvariant()
}catch{$taskResult.state='FAILED_PRESERVED';throw}
finally{[IO.File]::WriteAllText("$taskStage/receipt.json",($taskResult|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false));Write-Output "HELPER=$($taskResult.state)"}
