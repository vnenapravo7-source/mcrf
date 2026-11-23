param([string]$Snapshot='D:/MCRF/Projects/MCRF/work/android-integration-9eff4b3481154ee987db8e7c654799a3')
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF';$sdk='C:/Users/dieonfire/AppData/Local/Android/Sdk';$java='C:/Program Files/Java/jdk-17/bin'
function Hash([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Read([string]$p){Get-Content -LiteralPath $p -Raw|ConvertFrom-Json}
$common=Read "$Snapshot/p2-candidate-artifacts.json";$built=Read "$Snapshot/build-result.json"
if($built.exitCode -ne 0 -or !$built.sourceStable -or $common.priority -ne 2 -or $common.sourceManifestSha256 -cne (Hash "$Snapshot/source-manifest.json") -or !(Read "$Snapshot/p2-jvm-evidence.json").passed){throw 'Completed common P2 required'}
$grant=Read "$root/ports/android/coordination/p2-runtime-authority.json"
if(!$grant.allowAppInstall -or !$grant.allowOwnedTestVpn -or $grant.sourceReadySha256 -cne $common.readinessSha256 -or $grant.allowPublication -or $grant.stopForeignVpn){throw 'Existing human P2 install/owned-test authority required'}
$source="$root/ports/android/verification/p2"
$stage='D:/MCRF/Builds/Android/p2-runtime-checks-'+[guid]::NewGuid().ToString('N')
New-Item -ItemType Directory -Path $stage,"$stage/source","$stage/classes","$stage/dex"|Out-Null
$pins=@{};foreach($file in @('P2RuntimeInstrumentation.java','P2ServiceScenarios.java','AndroidManifest.xml')){$pins[$file]=Hash "$source/$file";Copy-Item -LiteralPath "$source/$file" -Destination "$stage/source/$file"}
$pins['controller']=Hash $PSCommandPath
$appJar="$Snapshot/ports/android/app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar"
$steerStage="$Snapshot/work/p2-primary-p2-steer-candidate-d3992f56f32d41438c654d3d785162a1"
$spi="$steerStage/spi.jar";$kotlin="$steerStage/steer/bundled/org.jetbrains.kotlin_kotlin-stdlib_2.2.10.jar"
$android="$sdk/platforms/android-34/android.jar";$tools="$sdk/build-tools/36.0.0"
$inputHashes=@{};foreach($file in @($appJar,$spi,$kotlin,$android,"$tools/aapt2.exe","$tools/lib/d8.jar","$java/javac.exe")){$inputHashes[$file]=Hash $file}
$result=[ordered]@{state='COMPILING_TEST_ONLY';common=$Snapshot;commonArtifactsSha256=Hash "$Snapshot/p2-candidate-artifacts.json";source=$pins;inputs=$inputHashes;phases=@('bootstrap','proxy','tun','failover','warp','optional','optional_tls','service','restore_ui');productionRebuilt=$false;installed=$false;published=$false}
function Save{[IO.File]::WriteAllText("$stage/receipt.json",($result|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))}
function Run([string]$name,[string]$exe,[string[]]$arguments){$out=& $exe @arguments 2>&1|Out-String;[IO.File]::WriteAllText("$stage/$name.log",$out,[Text.UTF8Encoding]::new($false));if($LASTEXITCODE){throw "$name failed (see owned build log)"}}
try{
    Save
    New-Item -ItemType Directory -Path "$stage/assets"|Out-Null
    Run 'owned-pki' "$java/keytool.exe" @('-genkeypair','-alias','owned','-keyalg','EC','-groupname','secp256r1','-dname','CN=owned.test','-ext','SAN=dns:owned.test','-validity','2','-storetype','PKCS12','-keystore',"$stage/assets/owned-peer.p12",'-storepass','owned-fixture','-keypass','owned-fixture','-noprompt')
    $result.ownedFixturePkiSha256=Hash "$stage/assets/owned-peer.p12"
    Run 'javac' "$java/javac.exe" @('-source','8','-target','8','-encoding','UTF-8','-classpath',"$android;$appJar;$spi;$kotlin",'-d',"$stage/classes","$stage/source/P2RuntimeInstrumentation.java","$stage/source/P2ServiceScenarios.java")
    Run 'jar' "$java/jar.exe" @('cf',"$stage/classes.jar",'-C',"$stage/classes",'.')
    Run 'd8' "$java/java.exe" @('-cp',"$tools/lib/d8.jar",'com.android.tools.r8.D8','--min-api','29','--lib',$android,'--classpath',$appJar,'--classpath',$spi,'--classpath',$kotlin,'--output',"$stage/dex","$stage/classes.jar")
    Run 'aapt2' "$tools/aapt2.exe" @('link','-I',$android,'--manifest',"$stage/source/AndroidManifest.xml",'-A',"$stage/assets",'--debug-mode','-o',"$stage/unsigned.apk")
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::Open("$stage/unsigned.apk",[IO.Compression.ZipArchiveMode]::Update)
    try{[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$stage/dex/classes.dex",'classes.dex',[IO.Compression.CompressionLevel]::Optimal)|Out-Null}finally{$zip.Dispose()}
    Run 'zipalign' "$tools/zipalign.exe" @('-f','-p','4',"$stage/unsigned.apk","$stage/aligned.apk")
    Run 'sign' "$tools/apksigner.bat" @('sign','--ks',"C:/Users/dieonfire/.android/debug.keystore",'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',"$stage/checks.apk","$stage/aligned.apk")
    Run 'signature' "$tools/apksigner.bat" @('verify','--print-certs',"$stage/checks.apk")
    if((Get-Content "$stage/signature.log" -Raw) -cnotmatch 'Signer #1 certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Helper not same-signed as accepted common app'}
    foreach($file in $inputHashes.Keys){if((Hash $file) -cne $inputHashes[$file]){throw 'Build dependency changed'}}
    foreach($file in @('P2RuntimeInstrumentation.java','P2ServiceScenarios.java','AndroidManifest.xml')){if((Hash "$source/$file") -cne $pins[$file]){throw 'Verification source changed during compile'}}
    if((Hash $PSCommandPath) -cne $pins.controller){throw 'Build controller changed'}
    $result.state='TEST_ONLY_COMPILED_NOT_RUN';$result.apk="$stage/checks.apk";$result.sha256=Hash $result.apk;$result.bytes=(Get-Item $result.apk).Length;Save
    Write-Output "P2_RUNTIME_CHECKS=$stage/receipt.json"
}catch{$result.state='FAILED';$result.error=$_.Exception.Message;Save;throw}
