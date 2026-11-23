param([Parameter(Mandatory=$true)][string]$NativeStage)
$ErrorActionPreference='Stop'
$taskProject='D:/MCRF/Projects/MCRF';$taskSdk='C:/Users/dieonfire/AppData/Local/Android/Sdk';$taskJava='C:/Program Files/Java/jdk-17/bin';$taskTools="$taskSdk/build-tools/36.0.0"
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Json([string]$Path,$Value){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))}
$taskProof=Get-Content "$NativeStage/receipt.json" -Raw|ConvertFrom-Json
if($taskProof.state -cne 'BUILT_HOST_VERIFIED_NOT_PHONE_ACCEPTED' -or !$taskProof.sourceStable -or $taskProof.commit -cne '5a46f2f1f178637538542a36c7f086f26df86bcd'){throw 'Exact016 source-bound stage required'}
$taskNative="$NativeStage/arm64-v8a/libopenflux.so";$taskExit="$NativeStage/fixtures/arm64-v8a/libopenflux_testserver.so"
if((Hash $taskNative) -cne ($taskProof.files|Where-Object abi -eq 'arm64-v8a').sha256 -or (Hash $taskExit) -cne $taskProof.fixtureSha256){throw '016 payload differs'}
$taskLegacy='D:/MCRF/Projects/MCRF/work/ports-test-2026-10-14/openflux-build-v0.13.0-a871260fa0024ca089b77c3dccdc3dbc/verified-native'
$taskLegacyProof=Get-Content "$taskLegacy/script-updater-capability.json" -Raw|ConvertFrom-Json
$taskLegacyNative="$taskLegacy/arm64-v8a/libopenflux.so"
if((Hash $taskLegacyNative) -cne $taskLegacyProof.sha256.'arm64-v8a'){throw 'Accepted13 client differs'}
$taskOldFixture='D:/MCRF/Builds/Android/openflux13-validation-08550348f2974ee5b04d22b064a2cf2b'
$taskOldProof=Get-Content "$taskOldFixture/receipt.json" -Raw|ConvertFrom-Json
$taskOldExit="$taskOldFixture/lib/arm64-v8a/libopenflux_testserver.so"
if((Hash $taskOldExit) -cne $taskOldProof.fixtureSha256){throw 'Accepted13 exit differs'}
$taskTemplate="$PSScriptRoot/openflux13-validation/Validation.java"
if((Hash $taskTemplate) -cne '84051400c61d79e2b107107f3bae7add6bfa8600be3203ac0b2e75254c64b893'){throw 'Reviewed private runtime validator template changed'}
$taskOutput="D:/MCRF/Builds/Android/openflux016-validation-$([guid]::NewGuid().ToString('N'))"
$null=New-Item -ItemType Directory "$taskOutput/classes","$taskOutput/dex","$taskOutput/lib/arm64-v8a"
$taskText=Get-Content $taskTemplate -Raw
function Replace-One([string]$From,[string]$To){if([regex]::Matches($script:taskText,[regex]::Escape($From)).Count -ne 1){throw "Exact validator hook missing: $From"};$script:taskText=$script:taskText.Replace($From,$To)}
# Source-bound mechanical derivative, not a mutable old binary or a provider test.
$taskText=$taskText.Replace('org.mcrf.openflux.validation','org.mcrf.openflux.team016.validation').Replace('0.13','0.16').Replace('==14','==15').Replace('mcrf.14','mcrf.15').Replace('module14','module15').Replace('4f9c786cdd1d9fcd0c133e2e0fec0c5a12522610','5a46f2f1f178637538542a36c7f086f26df86bcd')
Replace-One 'private boolean production;' 'private boolean production; private String transportType="direct",pair="same";'
Replace-One 'TimeUnit.SECONDS.toNanos(18)' 'TimeUnit.SECONDS.toNanos(65)'
Replace-One 'start();}' 'transportType=arguments==null?"direct":arguments.getString("transport","direct");pair=arguments==null?"same":arguments.getString("pair","same");require(Arrays.asList("direct","fluxfast").contains(transportType)&&Arrays.asList("same","16to13","13to16").contains(pair)&&("direct".equals(transportType)||"same".equals(pair))&&(!production||!"13to16".equals(pair)),"invalid test scope");start();}'
Replace-One 'if(production){' 'if("13to16".equals(pair))binary=new File(lib,"libopenflux13.so");if("16to13".equals(pair))exit=new File(lib,"libopenflux13_testserver.so"); if(production){'
Replace-One 'Bundle metadata=info.applicationInfo.metaData;' 'Bundle metadata=info.applicationInfo.metaData;require("OpenFluxTeam/OpenFlux".equals(metadata.getString("org.mcrf.engines.source.repository")),"production source repository differs");'
Replace-One 'hash(binary).equals("EXPECTED_CORE_SHA256")' ('hash(binary).equals("13to16".equals(pair)?"'+(Hash $taskLegacyNative)+'":"'+(Hash $taskNative)+'")')
Replace-One '.put("Context","http://mcrf-openflux-fixture")' '.put("Context","http://mcrf-openflux-fixture").put("Type",transportType)'
Replace-One '.put("type","direct").put("name","direct")' '.put("type",transportType).put("name",transportType)'
Replace-One 'pass("owned private fixture files removed; no main-app storage or VPN used");' 'pass("owned private fixture files removed; no main-app storage or VPN used; transport="+transportType+"; pair="+pair);'
[IO.File]::WriteAllText("$taskOutput/Validation.java",$taskText,[Text.UTF8Encoding]::new($false))
$taskManifest=(Get-Content "$PSScriptRoot/openflux13-validation/AndroidManifest.xml" -Raw).Replace('org.mcrf.openflux.validation','org.mcrf.openflux.team016.validation').Replace('0.13','0.16')
[IO.File]::WriteAllText("$taskOutput/AndroidManifest.xml",$taskManifest,[Text.UTF8Encoding]::new($false))
Copy-Item $taskNative "$taskOutput/lib/arm64-v8a/libopenflux.so";Copy-Item $taskExit "$taskOutput/lib/arm64-v8a/libopenflux_testserver.so"
Copy-Item $taskLegacyNative "$taskOutput/lib/arm64-v8a/libopenflux13.so";Copy-Item $taskOldExit "$taskOutput/lib/arm64-v8a/libopenflux13_testserver.so"
$taskFrozen=@(Get-ChildItem $taskOutput -Recurse -File|ForEach-Object{@{path=[IO.Path]::GetRelativePath($taskOutput,$_.FullName);sha256=(Hash $_.FullName)}})
$taskResult=[ordered]@{state='BUILDING';nativeStage=$NativeStage;installed=$false;published=$false;sourceStable=$false;nativeSha256=(Hash $taskNative);legacySha256=(Hash $taskLegacyNative);fixtureSha256=(Hash $taskExit);legacyFixtureSha256=(Hash $taskOldExit);templateSha256=(Hash $taskTemplate);recipeSha256=(Hash $PSCommandPath)}
try{
 & "$taskJava/javac.exe" --release 8 -encoding UTF-8 -classpath "$taskSdk/platforms/android-34/android.jar" -d "$taskOutput/classes" "$taskOutput/Validation.java" *> "$taskOutput/javac.log";if($LASTEXITCODE){throw '016 validator compile failed'}
 $taskClasses=@(Get-ChildItem "$taskOutput/classes" -Recurse -Filter '*.class'|ForEach-Object FullName)
 & "$taskTools/d8.bat" --lib "$taskSdk/platforms/android-34/android.jar" --min-api 29 --output "$taskOutput/dex" @taskClasses *> "$taskOutput/d8.log";if($LASTEXITCODE){throw '016 validator dex failed'}
 & "$taskTools/aapt.exe" package -f -M "$taskOutput/AndroidManifest.xml" -I "$taskSdk/platforms/android-34/android.jar" -F "$taskOutput/unsigned.apk" *> "$taskOutput/aapt.log";if($LASTEXITCODE){throw '016 validator manifest failed'}
 Copy-Item "$taskOutput/dex/classes.dex" "$taskOutput/classes.dex"
 Push-Location $taskOutput
 try{& "$taskTools/aapt.exe" add unsigned.apk classes.dex lib/arm64-v8a/libopenflux.so lib/arm64-v8a/libopenflux_testserver.so lib/arm64-v8a/libopenflux13.so lib/arm64-v8a/libopenflux13_testserver.so *> aapt-add.log;if($LASTEXITCODE){throw '016 validator payload failed'}}finally{Pop-Location}
 & "$taskTools/zipalign.exe" -f -p 4 "$taskOutput/unsigned.apk" "$taskOutput/aligned.apk";if($LASTEXITCODE){throw '016 validator alignment failed'}
 & "$taskTools/apksigner.bat" sign --ks 'C:/Users/dieonfire/.android/debug.keystore' --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$taskOutput/validation.apk" "$taskOutput/aligned.apk";if($LASTEXITCODE){throw '016 validator signing failed'}
 foreach($taskRow in $taskFrozen){if((Hash (Join-Path $taskOutput $taskRow.path)) -cne $taskRow.sha256){throw '016 validator frozen input changed'}}
 if((Hash $PSCommandPath) -cne $taskResult.recipeSha256 -or (Hash $taskTemplate) -cne $taskResult.templateSha256){throw '016 validator source changed'}
 Json "$taskOutput/source-manifest.json" $taskFrozen
 $taskResult.state='BUILT_NOT_INSTALLED';$taskResult.sourceStable=$true;$taskResult.apkSha256=Hash "$taskOutput/validation.apk"
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{Json "$taskOutput/receipt.json" $taskResult;Write-Output "OPENFLUX016_VALIDATOR=$($taskResult.state);STAGE=$taskOutput"}
