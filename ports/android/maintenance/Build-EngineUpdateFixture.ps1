$ErrorActionPreference='Stop'
$stage='D:/MCRF/Builds/Android/engine-update-ui-'+[guid]::NewGuid().ToString('N')
foreach($directory in @($stage,"$stage/classes","$stage/dex")){New-Item -ItemType Directory -Path $directory|Out-Null}
foreach($name in @('EngineUpdateFixture.java','EngineUpdateFixtureManifest.xml')){Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $stage}
$before=@(Get-FileHash "$stage/EngineUpdateFixture.java","$stage/EngineUpdateFixtureManifest.xml"|Select-Object Path,Hash)
$sdk='C:/Users/dieonfire/AppData/Local/Android/Sdk';$tools="$sdk/build-tools/36.0.0";$java='C:/Program Files/Java/jdk-17'
$env:JAVA_HOME=$java
& "$java/bin/javac.exe" -encoding UTF-8 -source 17 -target 17 -classpath "$sdk/platforms/android-34/android.jar" -d "$stage/classes" "$stage/EngineUpdateFixture.java"
if($LASTEXITCODE -ne 0){throw 'Fixture javac failed'}
$classes=@(Get-ChildItem "$stage/classes" -Recurse -Filter '*.class'|ForEach-Object FullName)
& "$tools/d8.bat" --lib "$sdk/platforms/android-34/android.jar" --min-api 29 --output "$stage/dex" @classes
if($LASTEXITCODE -ne 0){throw 'Fixture D8 failed'}
& "$tools/aapt2.exe" link -o "$stage/unsigned.apk" -I "$sdk/platforms/android-34/android.jar" --manifest "$stage/EngineUpdateFixtureManifest.xml"
if($LASTEXITCODE -ne 0){throw 'Fixture manifest failed'}
$zip=[IO.Compression.ZipFile]::Open("$stage/unsigned.apk",[IO.Compression.ZipArchiveMode]::Update)
try{[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$stage/dex/classes.dex",'classes.dex')|Out-Null}finally{$zip.Dispose()}
& "$tools/apksigner.bat" sign --ks 'C:/Users/dieonfire/.android/debug.keystore' --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$stage/fixture.apk" "$stage/unsigned.apk"
if($LASTEXITCODE -ne 0){throw 'Fixture signer failed'}
foreach($row in $before){if((Get-FileHash $row.Path).Hash -cne $row.Hash){throw 'Frozen fixture source changed'}}
[IO.File]::WriteAllText("$stage/build-receipt.json",(@{stage=$stage;sourceStable=$true;apkSha256=(Get-FileHash "$stage/fixture.apk").Hash.ToLowerInvariant();purpose='Disposable emulator only; exact R8 app, live catalogue, mixed installed engine versions, no download/install on cancel'}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
Write-Output "ENGINE_UI_FIXTURE=$stage"
