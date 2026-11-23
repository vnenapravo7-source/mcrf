param([switch]$AppUpdate)
$ErrorActionPreference='Stop';$root='D:/MCRF/Projects/MCRF';$src="$PSScriptRoot/delivery-fix";$sdk='C:/Users/dieonfire/AppData/Local/Android/Sdk';$bt="$sdk/build-tools/36.0.0";$jdk='C:/Program Files/Java/jdk-17/bin'
if($AppUpdate){$src="$PSScriptRoot/app-update-check"}
$stage="D:/MCRF/Builds/Android/delivery-fix-runner-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $stage,"$stage/classes","$stage/dex"|Out-Null
Copy-Item -LiteralPath "$src/DeliveryInstrumentation.java","$src/AndroidManifest.xml" -Destination $stage
function Check{if($LASTEXITCODE){throw 'Delivery fixture compilation failed; inspect retained logs'}}
& "$jdk/javac.exe" --release 8 -encoding UTF-8 -classpath "$sdk/platforms/android-34/android.jar" -d "$stage/classes" "$stage/DeliveryInstrumentation.java" *> "$stage/javac.log";Check
& "$jdk/jar.exe" cf "$stage/classes.jar" -C "$stage/classes" .;Check
& "$bt/d8.bat" --min-api 29 --lib "$sdk/platforms/android-34/android.jar" --output "$stage/dex" "$stage/classes.jar" *> "$stage/d8.log";Check
& "$bt/aapt2.exe" link -I "$sdk/platforms/android-34/android.jar" --manifest "$stage/AndroidManifest.xml" -o "$stage/unsigned.apk" *> "$stage/aapt.log";Check
& "$jdk/jar.exe" uf "$stage/unsigned.apk" -C "$stage/dex" classes.dex;Check
& "$bt/zipalign.exe" -f 4 "$stage/unsigned.apk" "$stage/aligned.apk";Check
& "$bt/apksigner.bat" sign --ks C:/Users/dieonfire/.android/debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$stage/runner.apk" "$stage/aligned.apk";Check
$r=@{state='COMPILED_NEW_DELIVERY_CHECKS_ONLY';apk="$stage/runner.apk";sha256=(Get-FileHash "$stage/runner.apk").Hash.ToLowerInvariant();mainRebuilt=$false;nativeRebuilt=$false}
[IO.File]::WriteAllText("$stage/receipt.json",($r|ConvertTo-Json),[Text.UTF8Encoding]::new($false));Write-Output "DELIVERY_RUNNER=$stage"
