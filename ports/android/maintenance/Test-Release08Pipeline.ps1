# Local source/receipt validation only: no Gradle, compilers, adb or network calls.
param()
$ErrorActionPreference='Stop'
. "$PSScriptRoot/Release08.Common.ps1"
$android=[IO.Path]::GetFullPath("$PSScriptRoot/..")
$project=[IO.Path]::GetFullPath("$android/../..")
$checks=0
function Check([bool]$Condition,[string]$Message){if(!$Condition){throw $Message};$script:checks++}
function Reject([scriptblock]$Action,[string]$Name){
 $rejected=$false;try{$null=& $Action}catch{$rejected=$true}
 Check $rejected "Expected refusal: $Name"
}
function TextFixture([string]$Path,[string]$Value){
 $null=New-Item -ItemType Directory -Force (Split-Path $Path)
 [IO.File]::WriteAllText($Path,$Value,[Text.UTF8Encoding]::new($false))
}
function Row([string]$Path,[string]$Abi=''){
 $r=@{path=[IO.Path]::GetFullPath($Path);sha256=Hash08 $Path};if($Abi){$r.abi=$Abi};$r
}
function JsonFixture([string]$Path,$Value){
 $null=New-Item -ItemType Directory -Force (Split-Path $Path);Save08 $Path $Value;Row $Path
}
function Mutated([string]$Path,[scriptblock]$Edit,[scriptblock]$Action,[string]$Name){
 $original=Read08 $Path;$candidate=Read08 $Path
 try{& $Edit;Save08 $Path $candidate;Reject $Action $Name}finally{Save08 $Path $original}
}

$scripts=@('Build-Release08.ps1','Publish-Release08.ps1','Install-Release08.ps1','Capture-Release08Baseline.ps1','Release08.Common.ps1','Assert-HumanBatchReady.ps1','Test-Release08Pipeline.ps1')
foreach($name in $scripts){
 $tokens=$null;$errors=$null
 $null=[Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot/$name",[ref]$tokens,[ref]$errors)
 Check ($errors.Count -eq 0) "PowerShell parse error: $name / $errors"
}
$producerPath="$android/transports/swg/Prepare-CsqttHistoricalPackage.ps1"
$tokens=$null;$errors=$null
$null=[Management.Automation.Language.Parser]::ParseFile($producerPath,[ref]$tokens,[ref]$errors)
Check ($errors.Count -eq 0) 'Historical package producer parse error'
$producer=Get-Content -Raw $producerPath
Check ($producer.Contains('$result.packageVersionCode=4') -and $producer.Contains('$result.packageVersionName="$($n.generation)-mcrf.performance.4"')) 'Historical producer must emit package code 4 / performance.4'
$publicFiles=@($scripts|Where-Object {$_ -ne 'Test-Release08Pipeline.ps1'}|ForEach-Object {"$PSScriptRoot/$_"})+@(
 "$PSScriptRoot/Release08.md","$android/coordination/release08.json","$project/README.md","$android/README.md","$project/docs/DEVELOPMENT.md")
foreach($path in $publicFiles){Check ((Get-Content -Raw $path) -cnotmatch '(?<![0-9])0\.8(?![0-9])|\b10064\b|PUBLISHED_08') "Stale public release identity: $path"}
$authority=Read08 "$android/coordination/release08.json"
Check ($authority.versionName -ceq '2.0' -and $authority.versionCode -eq 10065 -and $authority.tag -ceq 'android-v2.0') 'Coordination release identity'
Sets08 $authority.requiredPackages (Kinds08)
Sets08 $authority.requiredNative @('csqtt','csqtt25','qwdtt','steer','wdtt')
Sets08 $authority.requiredHistoricalPackages @('csqtt20','csqtt215')
Sets08 $authority.requiredCsqttPackages @('csqtt20','csqtt215','csqtt','csqtt25');$checks+=4
$builder=Get-Content -Raw "$PSScriptRoot/Build-Release08.ps1"
foreach($task in @('assembleCsqtt20Release','assembleCsqtt215Release','-PhistoricalCsqttCandidateGate=true','-Pcsqtt20Stage=','-Pcsqtt215Stage=')){
 Check ($builder.Contains($task)) "Missing grouped historical input/task: $task"
}
Check ($builder -notmatch 'Build-CsqttHistoricalNative|cargo\s+ndk') 'Grouped packaging must retain unchanged historical Rust'
foreach($feature in @('2.5.1/2.5.2/2.5.3','2.0/2.1.5/2.1.9','по одному или все','VPN и БС','автоматический выбор поколения')){
 Check ($builder.Contains($feature)) "Missing release-note feature: $feature"
}
foreach($path in @("$android/app/build.gradle.kts","$android/engines/build.gradle.kts")){
 $gate=Get-Content -Raw $path
 Check ($gate.Contains('"10065"') -and $gate.Contains('"2.0"') -and $gate.Contains('"historicalPackages"') -and
        $gate.Contains('listOf("csqtt20","csqtt215")')) "Incomplete Gradle source gate: $path"
}
$engineGate=Get-Content -Raw "$android/engines/build.gradle.kts"
Check ($engineGate.Contains('&&!release08HistoricalCandidateAllowed') -and $engineGate.Contains('requireP3TurnPerformance(root.resolve("wrapper-performance")')) 'Historical variant admission/provenance checks must both remain'
Check ($engineGate.Contains('require(descriptor["packageVersionCode"].toString()=="4")') -and
       $engineGate.Contains('require(d["packageVersionCode"].toString()=="4"&&d["packageVersionName"]=="$generation-mcrf.performance.4")') -and
       $engineGate.Contains('require(code.toString().toInt()==4);versionCode=4;versionName="$generation-mcrf.performance.4"')) 'Historical source gates must require exactly package code 4 / performance.4'

# Tiny synthetic ZIPs exercise the validator; they are not executable Android APKs.
$stage="D:/MCRF/Builds/Android/release08-$([guid]::NewGuid().ToString('N'))"
$null=New-Item -ItemType Directory "$stage/assets","$stage/inputs"
$sourceManifest=JsonFixture "$stage/source-manifest.json" @((Row "$PSScriptRoot/Test-Release08Pipeline.ps1"))
$engines=@()
foreach($kind in @('qwdtt','wdtt','csqtt','csqtt25','steer')){
 $outputs=@();$artifacts=@()
 $abis=if($kind -ceq 'steer'){@('arm64-v8a','x86_64')}else{@('arm64-v8a','armeabi-v7a','x86_64')}
 foreach($abi in $abis){
  $libs=if($kind -ceq 'csqtt'){@('libqwdtt.so','libcsqtt_transport.so')}else{@("lib$kind.so")}
  foreach($lib in $libs){
   $path="$stage/inputs/$kind/$abi/$lib";TextFixture $path "fixture:$kind/$abi/$lib"
   $outputs+=Row $path $abi
   $artifacts+=@{file=$path;abi=$abi;sha256=Hash08 $path;bytes=(Get-Item $path).Length}
  }
 }
 $receipt=@{kind=$kind;state='COMPILED_NOT_EXECUTED';sourceStable=$true;artifacts=$artifacts;processGroupContract=1;turnHashPoolContract=1;captchaWaiterCapability='csqtt-id48-v1'}
 $bound=JsonFixture "$stage/inputs/$kind/receipt.json" $receipt
 $manifest=JsonFixture "$stage/inputs/$kind/source.json" @($bound)
 $engines+=@{kind=$kind;rebuilt=$true;sourceStable=$true;sourceManifest=$manifest;outputs=$outputs}
}
$wrapper=Read08 "$stage/inputs/csqtt/receipt.json"
$historical=@()
foreach($kind in @('csqtt20','csqtt215')){
 $base="$stage/inputs/$kind";$rows=@();$outputs=@();$nativeArtifacts=@()
 foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){
  foreach($lib in @("lib$kind.so",'libcsqtt_transport.so')){
   $path="$base/$abi/$lib"
   if($lib -ceq 'libcsqtt_transport.so'){
    TextFixture $path "retained:$kind/$abi";$hash=Hash08 $path;$bytes=(Get-Item $path).Length
    $nativeArtifacts+=@{abi=$abi;sha256=$hash;bytes=$bytes}
   }else{
    TextFixture $path ''
    Copy-Item -LiteralPath "$stage/inputs/csqtt/$abi/libqwdtt.so" -Destination $path
    $hash=Hash08 $path;$bytes=(Get-Item $path).Length
   }
   $rows+=@{path="$abi/$lib";abi=$abi;sha256=$hash;bytes=$bytes};$outputs+=Row $path $abi
  }
 }
 $generation=if($kind -ceq 'csqtt20'){'2.0'}else{'2.1.5'}
 $revision=if($kind -ceq 'csqtt20'){'2ff8a1a4fbe031fb76b799e172dce25cfe1ca942'}else{'de7afc23cec7481f98845bf24fc8943af19b4cf8'}
 $native=@{engine=$kind;generation=$generation;revision=$revision;state='COMPILED_NOT_EXECUTED';sourceStable=$true;artifacts=$nativeArtifacts;adapterSha256=Hash08 "$android/transports/swg/prepare-csqtt-historical.py"}
 $n=JsonFixture "$base/native-receipt.json" $native;$w=JsonFixture "$base/wrapper-receipt.json" $wrapper
 TextFixture "$base/licenses/CSQTT-LICENSE" 'fixture';TextFixture "$base/licenses/qwdtt-LICENSE" 'fixture'
 $d=@{state='HISTORICAL_ENGINE_INPUTS_READY';kind=$kind;generation=$generation;revision=$revision;sourceStable=$true;packageVersionCode=4;packageVersionName="$generation-mcrf.performance.4";wrapperReceiptSha256=$w.sha256;nativeReceiptSha256=$n.sha256;artifacts=$rows}
 $bound=JsonFixture "$base/receipt.json" $d
 $historical+=@{kind=$kind;rebuilt=$false;sourceStable=$true;packageReceipt=$bound;outputs=$outputs}
}
$nativeReceipt=JsonFixture "$stage/native.json" @{state='AFFECTED_NATIVE_REBUILT';sourceManifestSha256=$sourceManifest.sha256;engines=$engines;historicalPackages=$historical}
$assets=@()
foreach($kind in (Kinds08)){
 $abis=if($kind -ceq 'steer'){@('arm64-v8a','x86_64')}else{@('arm64-v8a','armeabi-v7a','x86_64')}
 foreach($abi in $abis){
  $prefix=if($kind -ceq 'app'){'mcrf-android'}else{"mcrf-engine-$kind"};$name="$prefix-$abi-2.0.apk"
  $zip=[IO.Compression.ZipFile]::Open("$stage/assets/$name",[IO.Compression.ZipArchiveMode]::Create)
  try{
   if($kind -cne 'app'){
    $engine=@((@($engines)+@($historical))|Where-Object kind -CEQ $kind)[0]
    foreach($out in @($engine.outputs|Where-Object abi -CEQ $abi)){
     $null=[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$out.path,"lib/$abi/$([IO.Path]::GetFileName($out.path))")
    }
   }
  }finally{$zip.Dispose()}
  $assets+=@{kind=$kind;abi=$abi;name=$name;package=$(if($kind -ceq 'app'){'org.mcrf.client'}else{"org.mcrf.engines.$kind"});versionName=$(switch -CaseSensitive ($kind){'app'{'2.0'}'csqtt20'{'2.0-mcrf.performance.4'}'csqtt215'{'2.1.5-mcrf.performance.4'}default{'fixture.3'}});versionCode=$(if($kind -ceq 'app'){10065}elseif($kind -cin @('csqtt20','csqtt215')){4}else{3});sha256=Hash08 "$stage/assets/$name"}
 }
}
TextFixture "$stage/mcrf-android-2.0-source.zip" 'synthetic source archive'
TextFixture "$stage/RELEASE_NOTES.md" 'synthetic release notes'
$build=@{state='BUILT_NOT_INSTALLED_OR_PUBLISHED';authority=$authority.authority;versionName='2.0';versionCode=10065;sourceStable=$true;groupedAffectedBuild=$true;sourceManifest=$sourceManifest;nativeReceipt=$nativeReceipt;signer=('a'*64);assets=$assets;sourceArchive=(Row "$stage/mcrf-android-2.0-source.zip");releaseNotes=(Row "$stage/RELEASE_NOTES.md")}
Save08 "$stage/receipt.json" $build
$null=Build08 $stage;$checks++
$validate={Build08 $stage}
foreach($kind in @('csqtt20','csqtt215')){
 Mutated "$stage/receipt.json" {$candidate.assets=@($candidate.assets|Where-Object kind -CNE $kind)} $validate "missing $kind"
}
Mutated "$stage/receipt.json" {$candidate.versionName='0.8';$candidate.versionCode=10064} $validate 'old release receipt'
Mutated "$stage/receipt.json" {$candidate.assets[0].name='mcrf-android-arm64-v8a-0.8.apk'} $validate 'stale public asset'
Mutated "$stage/receipt.json" {$candidate.assets[0].versionCode=10064} $validate 'stale APK identity'
Mutated "$stage/receipt.json" {$candidate.assets[0].package='org.mcrf.other'} $validate 'wrong package identity'
Mutated "$stage/receipt.json" {$candidate.assets+=@($candidate.assets[0])} $validate 'duplicate asset'
Mutated "$stage/receipt.json" {$candidate.assets=@($candidate.assets|Where-Object {$_.kind -cne 'csqtt215' -or $_.abi -cne 'x86_64'})} $validate 'missing historical ABI'
Mutated "$stage/receipt.json" {$candidate.sourceArchive.path="$stage/mcrf-android-0.8-source.zip"} $validate 'stale archive identity'
Reject {Sets08 @('csqtt20','csqtt215','csqtt','csqtt25','csqtt25') @('csqtt20','csqtt215','csqtt','csqtt25')} 'duplicate CSQTT family'
Reject {Historical08 "$stage/inputs/csqtt20" 'csqtt20' ('0'*64)} 'stale wrapper pairing'
foreach($kind in @('csqtt20','csqtt215')){
 $checkHistorical={Historical08 "$stage/inputs/$kind" $kind (Hash08 "$stage/inputs/csqtt/receipt.json")}
 foreach($code in @(3,5)){
  Mutated "$stage/inputs/$kind/receipt.json" {$candidate.packageVersionCode=$code} $checkHistorical "historical package code $code refused"
 }
 Mutated "$stage/inputs/$kind/receipt.json" {$null=$candidate.Remove('packageVersionCode')} $checkHistorical 'missing historical package code'
 Mutated "$stage/inputs/$kind/receipt.json" {$candidate.packageVersionName='wrong-performance.4'} $checkHistorical 'wrong historical package version name'
 Mutated "$stage/inputs/$kind/receipt.json" {$null=$candidate.Remove('packageVersionName')} $checkHistorical 'missing historical package version name'
 Mutated "$stage/receipt.json" {($candidate.assets|Where-Object kind -CEQ $kind)[0].versionCode=3} $validate 'old historical APK code'
 Mutated "$stage/receipt.json" {($candidate.assets|Where-Object kind -CEQ $kind)[0].versionName='old-performance.3'} $validate 'old historical APK version name'
 Mutated "$stage/inputs/$kind/receipt.json" {$candidate.revision='wrong'} $checkHistorical 'wrong historical source pin'
 Mutated "$stage/inputs/$kind/receipt.json" {$candidate.artifacts[0].sha256=('0'*64)} $checkHistorical 'historical native tamper'
 Mutated "$stage/inputs/$kind/receipt.json" {$candidate.nativeReceiptSha256=('0'*64)} $checkHistorical 'historical receipt tamper'
}
$null=Build08 $stage;$checks++
TextFixture "$stage/device-evidence.txt" 'synthetic only; not actual device acceptance'
$evidence=Row "$stage/device-evidence.txt"
$installed=@($assets|Where-Object abi -CEQ 'arm64-v8a'|ForEach-Object {@{kind=$_.kind;package=$_.package;sha256=$_.sha256}})
$install=@{state='UPDATED_PRESERVED_ACCEPTANCE_PENDING';abi='arm64-v8a';dataPreserved=$true;connectionCount=14;serial='synthetic';buildReceiptSha256=Hash08 "$stage/receipt.json";installed=$installed}
$null=JsonFixture "$stage/phone/receipt.json" $install
$caseNames=@('wdtt-empty-routes','route-default-vpn','turn-live-streams','notification-server-ip','notification-proxy-hidden','random-foreground-theme','steer-post-pr')
foreach($kind in @('qwdtt','wdtt','csqtt20','csqtt215','csqtt','csqtt25')){$caseNames+="traffic-$kind";$caseNames+="upload-$kind"}
$cases=@($caseNames|ForEach-Object {@{name=$_;result='PASS';evidence=$evidence;mbps=12}})
$accept=@{state='DEVICE_ACCEPTED';serial='synthetic';connectionCount=14;settingsPreserved=$true;buildReceiptSha256=$install.buildReceiptSha256;installReceiptSha256=Hash08 "$stage/phone/receipt.json";cases=$cases}
$null=JsonFixture "$stage/device-acceptance.json" $accept
$null=Acceptance08 $stage $build;$checks++
foreach($kind in @('csqtt20','csqtt215')){
 Mutated "$stage/device-acceptance.json" {$candidate.cases=@($candidate.cases|Where-Object name -CNE "traffic-$kind")} {Acceptance08 $stage $build} "missing $kind traffic acceptance"
}
Mutated "$stage/device-acceptance.json" {$candidate.cases[8].mbps=11} {Acceptance08 $stage $build} 'insufficient upload'
$null=Acceptance08 $stage $build;$checks++
Save08 "$stage/validation-result.json" @{state='PASS_SYNTHETIC_SOURCE_VALIDATION';checks=$checks;versionName='2.0';versionCode=10065;apkOrNativeBuilt=$false;deviceOrNetworkUsed=$false}
Write-Output "PASS: $checks source/parser/receipt checks; synthetic fixtures: $stage"
