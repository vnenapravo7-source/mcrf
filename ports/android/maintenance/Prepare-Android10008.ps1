param([switch]$ResumePreparedApks,
    [string]$Snapshot='android-integration-7ea8c96890374609bce44506b0e1bcd3',
    [int]$VersionCode=10008,[string]$Version='0.1.0-preview.20261017.4',
    [string]$ExpectedSha256='fc0dcc23763c054bb1ff0f0cc2295b88559e62c12b3236fa7296defb0596a34d',
    [int]$FocusedJvmTests=18)
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF'
$snapshot=$Snapshot
if($snapshot -notmatch '^android-integration-[0-9a-f]{32}$' -or $Version -notmatch '^0\.1\.0-preview\.20261017\.[45]$' -or $ExpectedSha256 -notmatch '^[0-9a-f]{64}$'){throw 'Explicit immutable candidate identity required'}
$frozen="$root/work/$snapshot"
$export="$root/work/android-module-artifacts-77f42f85d8ca4488b62e351e140162ed"
$stage="D:/MCRF/Builds/Android/publish-android-v$Version"
if((Test-Path -LiteralPath $stage) -and !$ResumePreparedApks){throw 'Publication stage exists; inspect/resume it, never overwrite'}
$build=Get-Content "$frozen/build-result.json" -Raw|ConvertFrom-Json
if($build.exitCode -ne 0 -or !$build.sourceStable -or $build.firstLaunchFixture){throw 'Verified immutable main build required'}
foreach($directory in @($stage,"$stage/app","$stage/modules","$stage/backup")){if(!(Test-Path -LiteralPath $directory)){New-Item -ItemType Directory -Path $directory|Out-Null}}
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
$sdk='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
$utf8=[Text.UTF8Encoding]::new($false)
$signer='fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'
function Sum([string]$file){(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()}
function Copy-Verified([string]$file,[string]$destination){
    if(Test-Path -LiteralPath $destination){if((Get-Item $destination).Length -ne (Get-Item $file).Length -or (Sum $destination) -cne (Sum $file)){throw 'Existing staged bytes differ; do not overwrite'};return}
    Copy-Item -LiteralPath $file -Destination $destination
}
function Verify([string]$file,[string]$package,[int]$version){
    $sig=(& "$sdk/apksigner.bat" verify --print-certs $file 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $sig -notmatch $signer){throw 'Signer mismatch'}
    $manifest=(& "$sdk/aapt2.exe" dump badging $file 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $manifest -notmatch ('(?m)^package: name='''+[regex]::Escape($package)+''' versionCode='''+$version+''' ')){throw 'Package/version mismatch'}
}
$apps=@();$modules=@()
foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64','universal')){
    $file="$frozen/ports/android/app/build/outputs/apk/release/app-$abi-release.apk"
    Verify $file 'org.mcrf.client' $VersionCode
    if((Sum $file) -cne $ExpectedSha256){throw 'Candidate bytes differ from verified app'}
    $zip=[IO.Compression.ZipFile]::OpenRead($file)
    try{if(@($zip.Entries|Where-Object {$_.FullName -match '^lib/|\.(so|aar)$'}).Count){throw 'Native in base'}}finally{$zip.Dispose()}
    $name=switch($abi){'x86_64'{'androidApp-x86_64-release.apk'}'universal'{'mcrf-android-universal-release.apk'}default{"$abi-release.apk"}}
    Copy-Verified $file "$stage/app/$name"
    $apps+=@{name=$name;size=(Get-Item $file).Length;sha256=(Sum $file)}
}
$nativeStages=@{
    csqtt='D:/MCRF/Builds/Android/csqtt-captcha-3f7afa301b7344ec96ffb1a9ef1c2583'
    csqtt25='D:/MCRF/Builds/Android/csqtt25-captcha-400c491c3f304aaabf1f9afd2cf65876'
    qwdtt='D:/MCRF/Builds/Android/qwdtt-captcha-26de700f11654cf1a73b2dd7a719ea78'
    wdtt='D:/MCRF/Builds/Android/wdtt-captcha-29c8ef1692da487095b46562fbc090fc'
}
$receipts=@{}
foreach($kind in $nativeStages.Keys){$r=Get-Content ($nativeStages[$kind]+'/receipt.json') -Raw|ConvertFrom-Json;if(!$r.sourceStable -or $r.kind -cne $kind -or $r.state -cne 'COMPILED_NOT_EXECUTED'){throw 'Native provenance mismatch'};$receipts[$kind]=$r}
$exports=Get-Content "$export/artifact-receipt.json" -Raw|ConvertFrom-Json
if(@($exports.files).Count -ne 13){throw 'Expected paired four-engine export'}
foreach($item in $exports.files){
    if($item.path -notmatch '^(csqtt|csqtt25|qwdtt|wdtt)/release/engines-\1-(arm64-v8a|armeabi-v7a|x86_64|universal)-release\.apk$'){throw 'Unexpected module path'}
    $kind=$Matches[1];$abi=$Matches[2];$file=Join-Path $export $item.path
    if((Get-Item $file).Length -ne $item.bytes -or (Sum $file) -cne $item.sha256){throw 'Export bytes changed'}
    Verify $file "org.mcrf.engines.$kind" $(if($kind -eq 'csqtt25'){1}else{5})
    $zip=[IO.Compression.ZipFile]::OpenRead($file)
    try{
        $expected=@($receipts[$kind].artifacts|Where-Object {$abi -eq 'universal' -or $_.abi -ceq $abi})
        if(@($zip.Entries|Where-Object {$_.FullName.StartsWith('lib/') -and !$_.FullName.EndsWith('/')}).Count -ne $expected.Count -or !$expected.Count){throw 'Native set mismatch'}
        foreach($native in $expected){
            $name=($native.file -replace '\\','/').Split('/')[-1];$entry=$zip.GetEntry("lib/$($native.abi)/$name")
            if(!$entry -or $entry.Length -ne $native.bytes){throw 'Native entry mismatch'}
            $hash=[Security.Cryptography.SHA256]::Create();$stream=$entry.Open()
            try{$digest=[Convert]::ToHexString($hash.ComputeHash($stream)).ToLowerInvariant()}finally{$stream.Dispose();$hash.Dispose()}
            if($digest -cne $native.sha256){throw 'Native identity changed'}
        }
        if(!@($zip.Entries|Where-Object {$_.FullName.StartsWith('assets/licenses/') -and !$_.FullName.EndsWith('/')}).Count){throw 'Original notices absent'}
    }finally{$zip.Dispose()}
    $name="mcrf-engine-$kind-$abi-release.apk";Copy-Verified $file "$stage/modules/$name"
    $modules+=@{name=$name;kind=$kind;abi=$abi;size=$item.bytes;sha256=$item.sha256}
}
$sourcePath="$stage/app/mcrf-android-source.zip"
$resumeSource=Test-Path -LiteralPath $sourcePath
if($resumeSource -and !$ResumePreparedApks){throw 'Source archive exists; explicit verified resume required'}
$sourceZip=[IO.Compression.ZipFile]::Open($sourcePath,[IO.Compression.ZipArchiveMode]::Update)
$seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
function Entry-Sum($entry){
    $hash=[Security.Cryptography.SHA256]::Create();$stream=$entry.Open()
    try{[Convert]::ToHexString($hash.ComputeHash($stream)).ToLowerInvariant()}finally{$stream.Dispose();$hash.Dispose()}
}
function Add-Source([string]$file,[string]$name){
    $name=$name -replace '\\','/'
    if($name -match '(^|/)\.\.?(/|$)' -or !$seen.Add($name)){throw 'Unsafe or duplicate archive entry'}
    $existing=$sourceZip.GetEntry($name)
    if($existing){if($existing.Length -ne (Get-Item -LiteralPath $file).Length -or (Entry-Sum $existing) -cne (Sum $file)){throw 'Existing archive source differs'};return}
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($sourceZip,$file,$name,[IO.Compression.CompressionLevel]::Optimal)|Out-Null
}
try{
    $inputs=Get-Content "$frozen/source-manifest.json" -Raw|ConvertFrom-Json
    foreach($input in $inputs){
        $ordinary=$input.path -match '^ports/(android|catalogue|fixtures)/' -and $input.path -notmatch '(^|/)(work|build|\.git|private)(/|$)'
        $spi=$input.path -in @(
            'work/steer-android-port/android-vpn/integration-helper/src/main/java/org/mcrf/steerprep/SteerMcrfSnapshotAdapter.java'
            'work/steer-android-port/android-vpn/integration-helper/src/main/java/org/mcrf/steerprep/SteerGeoProjection.java'
            'work/steer-android-port/android-vpn/integration-helper/src/main/java/org/mcrf/steerprep/json/SteerSnapshotJson.java'
            'work/steer-android-port/android-vpn/integration-helper/src/main/java/org/mcrf/steerprep/json/SteerNativeFlowShape.java'
            'work/steer-android-port/android-vpn/module-contract/src/main/java/org/mcrf/engine/spi/v1/Engine.java'
            'work/steer-android-port/android-vpn/module-contract/src/main/java/org/mcrf/engine/spi/v1/Platform.java'
        )
        if(!$ordinary -and !$spi){throw 'Unexpected frozen source path'}
        $file=Join-Path $frozen $input.path
        if((Get-Item $file).Length -ne $input.bytes -or (Sum $file) -cne $input.sha256){throw 'Frozen input changed'}
        Add-Source $file $input.path
    }
    Add-Source "$frozen/source-manifest.json" 'SOURCE_MANIFEST.json'
    if(Test-Path -LiteralPath "$root/LICENSE"){Add-Source "$root/LICENSE" 'LICENSE'}
    foreach($kind in @('csqtt','csqtt25','qwdtt','wdtt')){
        $noticeApk=Join-Path $stage "modules/mcrf-engine-$kind-arm64-v8a-release.apk"
        $noticeZip=[IO.Compression.ZipFile]::OpenRead($noticeApk)
        try{foreach($entry in $noticeZip.Entries){
            if(!$entry.FullName.StartsWith('assets/licenses/') -or $entry.FullName.EndsWith('/')){continue}
            $name="native-notices/$kind/"+$entry.FullName.Substring('assets/licenses/'.Length)
            if($name -match '(^|/)\.\.?(/|$)' -or !$seen.Add($name)){throw 'Unsafe notice entry'}
            $existing=$sourceZip.GetEntry($name)
            if($existing){if($existing.Length -ne $entry.Length -or (Entry-Sum $existing) -cne (Entry-Sum $entry)){throw 'Existing notice differs'};continue}
            $output=$sourceZip.CreateEntry($name,[IO.Compression.CompressionLevel]::Optimal).Open();$inputStream=$entry.Open()
            try{$inputStream.CopyTo($output)}finally{$inputStream.Dispose();$output.Dispose()}
        }}finally{$noticeZip.Dispose()}
    }
    # Source-only native adapters and pinned recipes: never work receipts, accounts,
    # private runtime inputs, user-provided links or unmodified closed native payloads.
    foreach($file in Get-ChildItem "$root/ports/android/transports/swg" -File){if($file.Extension -in @('.go','.rs','.patch','.ps1')){Add-Source $file.FullName ('native-recipes/swg/'+$file.Name)}}
    foreach($file in Get-ChildItem "$root/ports/android/transports/swg/csqtt25" -File){if($file.Extension -in @('.go','.md','.mod','.sum')){Add-Source $file.FullName ('native-recipes/swg/csqtt25/'+$file.Name)}}
    foreach($kind in @('csqtt','csqtt25','qwdtt','wdtt')){
        $module=Join-Path $receipts[$kind].source $(if($kind -eq 'wdtt'){'wdtt/go_client'}else{'qwdtt/qwdtt'})
        foreach($file in Get-ChildItem -LiteralPath $module -File -Recurse){
            $relative=[IO.Path]::GetRelativePath($module,$file.FullName) -replace '\\','/'
            if($relative -match '(^|/)(\.git|registry|target|build)(/|$)'){continue}
            if($file.Extension -in @('.go','.mod','.sum','.c','.h','.s','.md','.toml','.lock') -or $file.Name -match '^(LICENSE|COPYING|NOTICE)') {Add-Source $file.FullName "native-sources/$kind/$relative"}
        }
    }
    $rust='D:/MCRF/Builds/Android/csqtt-legacy-captcha-dbbb9d6a2cca4c09ad59a622e00962ac/source'
    foreach($file in Get-ChildItem -LiteralPath $rust -File -Recurse){
        $relative=[IO.Path]::GetRelativePath($rust,$file.FullName) -replace '\\','/'
        if($relative -match '(^|/)(\.git|registry|target|build)(/|$)'){continue}
        if($file.Extension -in @('.rs','.toml','.lock','.md','.c','.h') -or $file.Name -match '^(LICENSE|COPYING|NOTICE)'){Add-Source $file.FullName "native-sources/csqtt-rust/$relative"}
    }
    if(@($sourceZip.Entries).Count -ne $seen.Count -or @($sourceZip.Entries|Where-Object {!$seen.Contains($_.FullName)}).Count){throw 'Unexpected archive entries; refuse publication'}
}finally{$sourceZip.Dispose()}
$evidence=[ordered]@{version=$Version;versionCode=$VersionCode;source='attached exact frozen source archive and native adapters';mainSha256=$apps[0].sha256;mainBytes=$apps[0].size;baseNativeLibraries=0;focusedJvmTests=$FocusedJvmTests;physical=$(if($VersionCode -eq 10008){'Android 16 arm64: actual minified swipe/home/edge checks incl rounded-corner pixels, protected state and fourteen external modules preserved'}else{'Prior 10008 baseline only: Android16 arm64 minified swipe/home/edge/CSQTT ordinary HTTPS/protected state checks. New startup updater has separate focused JVM coverage; not physical Vivo acceptance.'});traffic='Prior 10008 legacy CSQTT: ordinary HTTPS requests, no forced curl family, Ya302/Google204/YouTube204 with certificate verification; ChatGPT403 is policy, not page acceptance';limits='Preview. No full Vivo/playback/QUIC/IPv6-only/all-Direct/all-provider CAPTCHA/all-ABI acceptance. Original CSQTT2.5 not declared IPv4-only; current adapter has IPv4-only inner stack. CSQTT2.5 supplied native arm64 only and retains PolyForm Noncommercial notices. No OpenFlux engine changes.'}
[IO.File]::WriteAllText("$stage/app/BUILD_EVIDENCE.json",($evidence|ConvertTo-Json -Depth 4),$utf8)
$hashes=Get-ChildItem "$stage/app" -File|Where-Object {$_.Name -cne 'SHA256SUMS.txt'}|Sort-Object Name|ForEach-Object {(Sum $_.FullName)+'  '+$_.Name}
[IO.File]::WriteAllText("$stage/app/SHA256SUMS.txt",($hashes -join "`n")+"`n",$utf8)
$plan=[ordered]@{stage=$stage;tag="android-v$Version";snapshot=$snapshot;appAssets=@(Get-ChildItem "$stage/app" -File|ForEach-Object {@{name=$_.Name;size=$_.Length;sha256=(Sum $_.FullName)}});modules=$modules;sourceEntries=$seen.Count;state='PREPARED_NOT_PUBLISHED'}
[IO.File]::WriteAllText("$stage/plan.json",($plan|ConvertTo-Json -Depth 7),$utf8)
Write-Output "PUBLICATION_STAGE=$stage"
Write-Output "Prepared exact tested APKs, source archive, notices and thirteen external modules. Nothing published."
