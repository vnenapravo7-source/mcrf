param([Parameter(Mandatory=$true)][string]$Candidate,[switch]$Runtime)
$ErrorActionPreference='Stop'
$proof=Get-Content "$Candidate/receipt.json" -Raw|ConvertFrom-Json
if($proof.state -cnotin @('PASS_INDEPENDENT_MODEL_PACKAGING','PASS_UPDATE_ACTION_ROW_PACKAGING') -or $proof.published -ne $false){throw 'Independent accepted candidate required'}
$sdk='C:/Users/dieonfire/AppData/Local/Android/Sdk';$java='C:/Program Files/Java/jdk-17/bin'
$prefix=if($Runtime){'next-runtime-probe'}else{'update-ui-probe'}
$folder=if($Runtime){'next-runtime'}else{'app-update-check'}
$entry=if($Runtime){'NextRuntimeInstrumentation.java'}else{'DeliveryInstrumentation.java'}
$stage="$Candidate/$prefix-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $stage,"$stage/classes","$stage/dex"|Out-Null
foreach($name in @('AndroidManifest.xml',$entry)){Copy-Item -LiteralPath "$PSScriptRoot/$folder/$name" -Destination "$stage/$name"}
& "$java/javac.exe" --release 8 -encoding UTF-8 -classpath "$sdk/platforms/android-34/android.jar" -d "$stage/classes" "$stage/$entry" *> "$stage/javac.log"
if($LASTEXITCODE){throw 'UI probe javac failed'}
& "$java/jar.exe" cf "$stage/classes.jar" -C "$stage/classes" .
if($LASTEXITCODE){throw 'UI probe jar failed'}
& "$sdk/build-tools/36.0.0/d8.bat" --release --min-api 29 --lib "$sdk/platforms/android-34/android.jar" --output "$stage/dex" "$stage/classes.jar" *> "$stage/d8.log"
if($LASTEXITCODE){throw 'UI probe dex failed'}
& "$sdk/build-tools/36.0.0/aapt.exe" package -f -M "$stage/AndroidManifest.xml" -I "$sdk/platforms/android-34/android.jar" -F "$stage/unsigned.apk" *> "$stage/aapt.log"
if($LASTEXITCODE){throw 'UI probe manifest failed'}
Push-Location "$stage/dex";try{& "$sdk/build-tools/36.0.0/aapt.exe" add "$stage/unsigned.apk" classes.dex *> "$stage/aapt-add.log";if($LASTEXITCODE){throw 'UI probe dex package failed'}}finally{Pop-Location}
& "$sdk/build-tools/36.0.0/zipalign.exe" -f 4 "$stage/unsigned.apk" "$stage/aligned.apk"
if($LASTEXITCODE){throw 'UI probe align failed'}
& "$sdk/build-tools/36.0.0/apksigner.bat" sign --ks 'C:/Users/dieonfire/.android/debug.keystore' --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$stage/probe.apk" "$stage/aligned.apk"
if($LASTEXITCODE){throw 'UI probe signing failed'}
Write-Output "NEW_UI_PROBE=$stage/probe.apk"
