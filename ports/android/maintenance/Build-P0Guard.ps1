param()
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation build
if(@(Get-Process -Name java,go -ErrorAction SilentlyContinue).Count){throw 'Producer still active; do not start another'}
$taskRoot='D:/MCRF/Projects/MCRF';$taskSdk='C:/Users/dieonfire/AppData/Local/Android/Sdk';$taskJava='C:/Program Files/Java/jdk-17/bin'
$taskStage=Join-Path $taskRoot ('work/p0-data-guard-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskStage|Out-Null
$taskManifest=@()
foreach($taskName in @('AndroidManifest.xml','GuardInstrumentation.java')){
    $taskFrom=Join-Path $PSScriptRoot "p0-guard/$taskName"
    [IO.File]::Copy($taskFrom,(Join-Path $taskStage $taskName),$false)
    $taskManifest+=@{name=$taskName;sha256=(Get-FileHash -LiteralPath $taskFrom -Algorithm SHA256).Hash}
}
$taskResult=[ordered]@{state='STARTED';scope='Small same-signer framework-only read-only P0 install guard; no main/module APK build or app-code tests';sourceStable=$false}
try{
    New-Item -ItemType Directory -Path "$taskStage/classes","$taskStage/dex"|Out-Null
    & "$taskJava/javac.exe" --release 8 -encoding UTF-8 -classpath "$taskSdk/platforms/android-34/android.jar" -d "$taskStage/classes" "$taskStage/GuardInstrumentation.java" *> "$taskStage/javac.log"
    if($LASTEXITCODE){throw 'Data guard compile failed'}
    & "$taskJava/jar.exe" cf "$taskStage/classes.jar" -C "$taskStage/classes" .
    if($LASTEXITCODE){throw 'Data guard class archive failed'}
    & "$taskSdk/build-tools/36.0.0/d8.bat" --release --min-api 29 --lib "$taskSdk/platforms/android-34/android.jar" --output "$taskStage/dex" "$taskStage/classes.jar" *> "$taskStage/d8.log"
    if($LASTEXITCODE){throw 'Data guard dex failed'}
    & "$taskSdk/build-tools/36.0.0/aapt.exe" package -f -M "$taskStage/AndroidManifest.xml" -I "$taskSdk/platforms/android-34/android.jar" -F "$taskStage/unsigned.apk" *> "$taskStage/aapt.log"
    if($LASTEXITCODE){throw 'Data guard manifest package failed'}
    Push-Location "$taskStage/dex"
    try{& "$taskSdk/build-tools/36.0.0/aapt.exe" add "$taskStage/unsigned.apk" classes.dex *> "$taskStage/aapt-add.log";if($LASTEXITCODE){throw 'Data guard dex package failed'}}finally{Pop-Location}
    & "$taskSdk/build-tools/36.0.0/zipalign.exe" -f 4 "$taskStage/unsigned.apk" "$taskStage/aligned.apk"
    if($LASTEXITCODE){throw 'Data guard alignment failed'}
    & "$taskSdk/build-tools/36.0.0/apksigner.bat" sign --ks 'C:/Users/dieonfire/.android/debug.keystore' --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$taskStage/guard.apk" "$taskStage/aligned.apk"
    if($LASTEXITCODE){throw 'Data guard signing failed'}
    $taskSigner=(& "$taskSdk/build-tools/36.0.0/apksigner.bat" verify --print-certs "$taskStage/guard.apk" 2>&1|Out-String)
    if($LASTEXITCODE -or $taskSigner -notmatch 'certificate SHA-256 digest:\s*([0-9a-f]{64})' -or $Matches[1] -cne 'fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Data guard signer differs from authorized MCRF'}
    foreach($taskRow in $taskManifest){if((Get-FileHash -LiteralPath (Join-Path $taskStage $taskRow.name) -Algorithm SHA256).Hash -cne $taskRow.sha256){throw 'Data guard frozen source changed'}}
    $taskResult.sourceStable=$true;$taskResult.apk="$taskStage/guard.apk";$taskResult.sha256=(Get-FileHash -LiteralPath "$taskStage/guard.apk" -Algorithm SHA256).Hash.ToLowerInvariant();$taskResult.state='PASS'
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{[IO.File]::WriteAllText("$taskStage/receipt.json",($taskResult|ConvertTo-Json -Depth 3),[Text.UTF8Encoding]::new($false));Write-Output "P0_GUARD=$($taskResult.state);RECEIPT=$taskStage/receipt.json"}
