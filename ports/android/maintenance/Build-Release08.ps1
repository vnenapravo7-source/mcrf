param(
 [Parameter(Mandatory)][string]$CsqttLegacyStage,
 [Parameter(Mandatory)][string]$Csqtt25Stage,
 [Parameter(Mandatory)][string]$Csqtt25VersionsStage,
 [Parameter(Mandatory)][string]$Csqtt20Stage,
 [Parameter(Mandatory)][string]$Csqtt215Stage,
 [string]$Sdk='C:/Users/dieonfire/AppData/Local/Android/Sdk'
)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/Release08.Common.ps1"
$root=[IO.Path]::GetFullPath("$PSScriptRoot/..")
$project=[IO.Path]::GetFullPath("$root/../..")
$authority=Read08 "$root/coordination/release08.json"
if($authority.authority -cne 'human-20261029-local-test-then-release08' -or $authority.versionName -cne '2.0' -or
   $authority.versionCode -ne 10065 -or $authority.state -cne 'GROUPED_NATIVE_SOURCE_GRAPH_READY'){throw 'Exact 2.0 authority required'}
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation build

$nativeStages=[ordered]@{
 qwdtt='D:/MCRF/Builds/Android/p3-turn-native-ed09ca6482fc4172ab8cbfdf7a315a53'
 wdtt='D:/MCRF/Builds/Android/p3-turn-native-3ce268ebd9a340668b06d4104bbad10f'
 csqtt=[IO.Path]::GetFullPath($CsqttLegacyStage)
 csqtt25=[IO.Path]::GetFullPath($Csqtt25Stage)
 steer='D:/MCRF/Builds/Android/p3-steer-native-c04cdfc8e5574728a9b53cf133d0b1b4'
}
foreach($kind in $nativeStages.Keys){
 $receipt=Read08 "$($nativeStages[$kind])/receipt.json"
 if($receipt.kind -cne $kind -or $receipt.sourceStable -ne $true -or $receipt.state -notmatch '^COMPILED'){throw "Fresh native stage required: $kind"}
 if($kind -ceq 'csqtt25' -and ($receipt.workerNegotiationContract -ne 1 -or $receipt.serverTunTxQueueContract -ne 1)){
  throw 'CSQTT25 worker-negotiation and server-TUN queue contracts required'
 }
}

& python "$root/transports/swg/csqtt25/p3_abi_provenance.py" --native $nativeStages.csqtt25
if($LASTEXITCODE){throw 'Fresh receipt/provenance-bound CSQTT25 native stage required'}
$csqtt25VersionsStage=[IO.Path]::GetFullPath($Csqtt25VersionsStage)
& python "$root/transports/swg/csqtt25/versioned_inputs.py" --verify $csqtt25VersionsStage --wrapper-stage $nativeStages.csqtt25
if($LASTEXITCODE){throw 'Verified CSQTT SWG 2.5.2/2.5.3 inputs and version-aware wrapper required'}
$historicalStages=[ordered]@{csqtt20=[IO.Path]::GetFullPath($Csqtt20Stage);csqtt215=[IO.Path]::GetFullPath($Csqtt215Stage)}
foreach($kind in $historicalStages.Keys){$null=Historical08 $historicalStages[$kind] $kind (Hash08 "$($nativeStages.csqtt)/receipt.json")}

$stage="D:/MCRF/Builds/Android/release08-$([guid]::NewGuid().ToString('N'))"
$null=New-Item -ItemType Directory "$stage/assets","$stage/native"
function Row08([string]$Path,[hashtable]$Extra=@{}){
 $p=File08 $Path;$row=[ordered]@{path=$p;sha256=Hash08 $p};foreach($key in $Extra.Keys){$row[$key]=$Extra[$key]};$row
}
function Json08([string]$Path,$Value){Save08 $Path $Value;Row08 $Path}

$sourceFiles=Get-ChildItem -LiteralPath $root -Recurse -File | Where-Object {
 $relative=$_.FullName.Substring($root.Length+1).Replace('\','/')
 $relative -notmatch '(^|/)(build|\.gradle|\.idea|local\.properties|native-stage[^/]*)(/|$)' -and
 $relative -notmatch '\.(?:jks|keystore|p12|dpapi)$'
} | Sort-Object FullName
$sourceFiles=@($sourceFiles)+@(Get-Item -LiteralPath "$project/README.md","$project/docs/ANDROID-STATUS.md","$project/docs/DEVELOPMENT.md")
$sourceRows=@($sourceFiles|ForEach-Object {Row08 $_.FullName})
$sourceManifest=Json08 "$stage/source-manifest.json" $sourceRows

$engineRows=@()
foreach($kind in $nativeStages.Keys){
 $nativeRoot=$nativeStages[$kind];$receipt=Read08 "$nativeRoot/receipt.json";$outputs=@()
 foreach($artifact in @($receipt.artifacts)){
  $path=$artifact.file;if(!$path){continue};if(![IO.Path]::IsPathRooted($path)){$path=Join-Path $nativeRoot $path}
  $outputs+=Row08 $path @{abi=$artifact.abi}
 }
 $engineManifest=Json08 "$stage/native/$kind-source.json" @((Row08 "$nativeRoot/receipt.json"))
 $engineRows+=,[ordered]@{kind=$kind;rebuilt=$true;sourceStable=$true;sourceManifest=$engineManifest;outputs=$outputs}
}
$historicalRows=@()
foreach($kind in $historicalStages.Keys){
 $packageRoot=$historicalStages[$kind];$package=Read08 "$packageRoot/receipt.json"
 $outputs=@($package.artifacts|ForEach-Object {Row08 "$packageRoot/$($_.path)" @{abi=$_.abi}})
 $historicalRows+=,[ordered]@{kind=$kind;rebuilt=$false;sourceStable=$true;packageReceipt=(Row08 "$packageRoot/receipt.json");outputs=$outputs}
}
$nativeReceipt=Json08 "$stage/native/receipt.json" ([ordered]@{state='AFFECTED_NATIVE_REBUILT';sourceManifestSha256=$sourceManifest.sha256;engines=$engineRows;historicalPackages=$historicalRows})
$csqtt25VersionsReceipt=Row08 "$csqtt25VersionsStage/receipt.json"
$sourceProof=[ordered]@{authority=$authority.authority;state='SOURCE_READY';versionCode=10065;versionName='2.0';releaseType='release';publish=$false;groupedAffectedBuild=$true;sourceManifest=$sourceManifest.path;sourceManifestSha256=$sourceManifest.sha256;nativeReceipt=$nativeReceipt.path;nativeReceiptSha256=$nativeReceipt.sha256;csqtt25VersionsReceipt=$csqtt25VersionsReceipt}
Save08 "$stage/source-ready.json" $sourceProof
$proofSha=Hash08 "$stage/source-ready.json"

$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr';$env:ANDROID_HOME=$Sdk
# Keep the Windows AF_UNIX wakeup socket below sockaddr_un.sun_path limits in
# Codex-hosted shells. This is process-local and does not alter system networking.
$taskJavaTemp='C:/jtmp';$null=New-Item -ItemType Directory -Force -Path $taskJavaTemp
$env:TEMP=$taskJavaTemp;$env:TMP=$taskJavaTemp
$env:JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=$taskJavaTemp"
$gate=@("-Prelease08Receipt=$stage/source-ready.json","-Prelease08Sha256=$proofSha",'-PtestRelease=true')
$nativeGate=@("-PswgQwdttStage=$($nativeStages.qwdtt)","-PswgWdttStage=$($nativeStages.wdtt)","-PcsqttLegacyStage=$($nativeStages.csqtt)",'-PcsqttLegacyCandidateGate=true','-PswgNativeCandidateGate=true','-Pp3TurnPerformanceGate=true',"-Pcsqtt25Stage=$($nativeStages.csqtt25)","-Pcsqtt25VersionsStage=$csqtt25VersionsStage",'-Pcsqtt25CandidateGate=true',"-PprimaryCoreStage=$($nativeStages.steer)/primary-stage",'-PprimaryCoreNativeGate=true','-Pp3SteerSocksGate=true')
$historicalGate=@('-PhistoricalCsqttCandidateGate=true',"-Pcsqtt20Stage=$($historicalStages.csqtt20)","-Pcsqtt215Stage=$($historicalStages.csqtt215)")
$test=@(':app:testDebugUnitTest','--tests','org.mcrf.client.TurnPresentationTest','--tests','org.mcrf.client.TurnEmptyRoutesParityTest','--tests','org.mcrf.client.LegacyRoutingMigrationTest')+$gate+$nativeGate+$historicalGate
$null=& "$root/gradlew.bat" @test 2>&1|Tee-Object -FilePath "$stage/tests.log"
if($LASTEXITCODE){throw 'Affected JVM verification failed'}
$gradle=@(':app:lintRelease',':app:assembleRelease',':engines:assembleQwdttRelease',':engines:assembleWdttRelease',':engines:assembleCsqttRelease',':engines:assembleCsqtt25Release',':engines:assembleSteerRelease',':engines:assembleCsqtt20Release',':engines:assembleCsqtt215Release')+$gate+$nativeGate+$historicalGate
$null=& "$root/gradlew.bat" @gradle 2>&1|Tee-Object -FilePath "$stage/build.log"
if($LASTEXITCODE){throw 'Grouped Gradle build failed'}

$tools="$Sdk/build-tools/36.0.0";$assets=@();$signer=$null
function AddApk08([string]$Kind,[string]$Abi,[string]$From,[string]$Name){
 $target="$stage/assets/$Name";Copy-Item -LiteralPath $From -Destination $target
 $cert=Run08 "$tools/apksigner.bat" @('verify','--print-certs',$target)
 if($cert -notmatch '(?m)^Signer #1 certificate SHA-256 digest: ([a-f0-9]{64})\s*$'){throw 'Single APK signer required'}
 if($script:signer -and $script:signer -cne $Matches[1]){throw 'All APKs must share update signer'};$script:signer=$Matches[1]
 $badging=Run08 "$tools/aapt.exe" @('dump','badging',$target)
 if($badging -notmatch "(?m)^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'"){throw 'APK identity missing'}
 $script:assets+=,[ordered]@{name=$Name;kind=$Kind;abi=$Abi;package=$Matches[1];versionCode=[int]$Matches[2];versionName=$Matches[3];sha256=Hash08 $target}
}
$app=Get-ChildItem "$root/app/build/outputs/apk/release" -Filter '*.apk'|Where-Object Name -notmatch 'unsigned'|Select-Object -First 1
if(!$app){throw 'Signed app APK missing'}
foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){AddApk08 app $abi $app.FullName "mcrf-android-$abi-2.0.apk"}
foreach($kind in (@($nativeStages.Keys)+@($historicalStages.Keys))){foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){
 $apk=Get-ChildItem "$root/engines/build/outputs/apk/$kind/release" -Filter "*-$abi-release.apk" -ErrorAction SilentlyContinue|Select-Object -First 1
 if($apk){AddApk08 $kind $abi $apk.FullName "mcrf-engine-$kind-$abi-2.0.apk"}
}}
Sets08 @($assets.kind|Sort-Object -Unique) (Kinds08)

$notes="$stage/RELEASE_NOTES.md"
[IO.File]::WriteAllText($notes,"# MCRF Android 2.0`r`n`r`n- Добавлен автоматический выбор поколения CSQTT: существующие 2.0/2.1.5/2.1.9 и SWG 2.5.1/2.5.2/2.5.3.`r`n- Движки можно скачать по одному или все сразу.`r`n- Карточки серверов VPN и БС объединены.`r`n- Маршруты по умолчанию используют VPN/прокси; восстановлена работа WDTT без пользовательских правил.`r`n- Для TURN показаны сервер, IP и фактическое число потоков; неактивный прокси скрыт.`r`n- Добавлено случайное оформление при каждом возвращении в приложение.`r`n- Обновлены QWDTT, WDTT, CSQTT и Steer.`r`n",[Text.UTF8Encoding]::new($false))
$archive="$stage/mcrf-android-2.0-source.zip"
$zip=[IO.Compression.ZipFile]::Open($archive,[IO.Compression.ZipArchiveMode]::Create)
try{
 foreach($file in $sourceFiles){
  $relative=[IO.Path]::GetRelativePath($project,$file.FullName).Replace([char]92,[char]47)
  $null=[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file.FullName,$relative,[IO.Compression.CompressionLevel]::Optimal)
 }
}finally{$zip.Dispose()}
$receipt=[ordered]@{state='BUILT_NOT_INSTALLED_OR_PUBLISHED';authority=$authority.authority;versionName='2.0';versionCode=10065;sourceStable=$true;groupedAffectedBuild=$true;sourceManifest=$sourceManifest;nativeReceipt=$nativeReceipt;signer=$signer;assets=$assets;sourceArchive=Row08 $archive;releaseNotes=Row08 $notes;buildLog=Row08 "$stage/build.log";published=$false}
Save08 "$stage/receipt.json" $receipt;$null=Build08 $stage;Write-Output $stage
