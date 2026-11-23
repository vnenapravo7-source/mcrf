param([Parameter(Mandatory=$true)][string]$NativeStage,[string]$ResumeStage='')
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation native-package
$root='D:/MCRF/Projects/MCRF'
$native=[IO.Path]::GetFullPath($NativeStage)
if($native -cnotmatch '^D:\\MCRF\\Builds\\Android\\p044-native-source-[a-f0-9]{32}$'){throw 'Exact frozen native stage required'}
$built=Get-Content "$native/build-receipt.json" -Raw|ConvertFrom-Json
$checked=Get-Content "$native/runtime-static/receipt.json" -Raw|ConvertFrom-Json
$source=Get-Content "$native/receipt.json" -Raw|ConvertFrom-Json
if($built.state -cne 'COMPILED_UNINSTALLED_API_ABI_GATES_OPEN' -or !$built.sourceStable -or $checked.state -cne 'PASS_API_BYTES_AND_3ABI_ONLY' -or !$checked.apiByteExact -or $checked.abis.Count -ne 3){throw 'Native/API/3ABI candidate gates required'}
$baseline="$root/work/android-primary-stage-f386e3b1f236427c8542666076c17d5e"
$descriptor=(Get-Content "$baseline/receipt.json" -Raw|ConvertFrom-Json).modules.'sing-box'
$parent='D:/MCRF/Builds/Android/p0-network42-apk-042be6d021924bb1aef85c8d63bc461a'
$parentReceipt=Get-Content "$parent/receipt.json" -Raw|ConvertFrom-Json
if($parentReceipt.state -cne 'PASS' -or !$parentReceipt.excludedP1 -or $parentReceipt.versionCode -ne 10018){throw 'Frozen P1-excluded main18 parent required'}
$currentPath="$root/ports/android/coordination/current.json";$current=Get-Content $currentPath -Raw|ConvertFrom-Json
if($current.jobs.build -or $current.jobs.device -or $current.jobs.hostTest){throw 'Existing job; no duplicate packaging'}
$stage=if($ResumeStage){[IO.Path]::GetFullPath($ResumeStage)}else{'D:/MCRF/Builds/Android/p044-module-apk-'+[guid]::NewGuid().ToString('N')}
if($ResumeStage){
    if($stage -cnotmatch '^D:\\MCRF\\Builds\\Android\\p044-module-apk-[a-f0-9]{32}$'){throw 'Unexpected existing module stage'}
    $previous=Get-Content "$stage/receipt.json" -Raw|ConvertFrom-Json
    if($previous.state -cne 'FAILED_PRESERVED' -or $previous.error -cne 'Module notice missing' -or [IO.Path]::GetFullPath($previous.native) -cne $native -or (Get-Content "$stage/build.log" -Raw) -notmatch 'BUILD SUCCESSFUL'){throw 'Resume only exact already-built notice-path verification failure'}
    [IO.File]::Copy("$stage/receipt.json","$stage/first-static-failure.json",$false)
}else{[IO.Directory]::CreateDirectory($stage)|Out-Null}
$result=[ordered]@{schema=1;state='PREPARING';stage=$stage;native=$native;moduleVersionCode=7;mainVersionCode=10018;mainApkBuilt=$false;nativeRebuilt=$false;hostTestsRepeated=$false;excludedP1=$true;installed=$false;published=$false;sourceStable=$false;artifacts=@{}}
function Sha([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Safe([string]$Base,[string]$Relative){$path=[IO.Path]::GetFullPath((Join-Path $Base $Relative));if(!$path.StartsWith([IO.Path]::GetFullPath($Base)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Source path escape'};$path}
function SavePackage {$result|ConvertTo-Json -Depth 10|Set-Content "$stage/receipt.json" -Encoding utf8}
function CopyExact([string]$From,[string]$To,[string]$Expected){if((Sha $From) -cne $Expected){throw 'Input artifact changed'};[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($To))|Out-Null;[IO.File]::Copy($From,$To,$false);if((Sha $To) -cne $Expected){throw 'Artifact copy changed'}}
function Row([string]$File){@{file=[IO.Path]::GetFileName($File);bytes=(Get-Item $File).Length;sha256=Sha $File}}
$envNames=@('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS');$saved=@{};foreach($name in $envNames){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
SavePackage
try{
    $current.jobs.build="P044_MODULE_APK:$stage";$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8
    [IO.File]::Copy($PSCommandPath,"$stage/$(if($ResumeStage){'resume-control.ps1'}else{'producer-control.ps1'})",$false)
    if(!$ResumeStage){
    $manifest=@(Get-Content "$parent/source-manifest.json" -Raw|ConvertFrom-Json)
    foreach($row in $manifest){CopyExact (Safe $parent $row.path) (Safe $stage $row.path) $row.sha256}
    $manifest|ConvertTo-Json -Depth 4|Set-Content "$stage/source-manifest.json" -Encoding utf8
    $core="$stage/primary-stage/sing-box";$licenses="$core/licenses"
    [IO.Directory]::CreateDirectory($licenses)|Out-Null
    CopyExact "$native/runtime.aar" "$core/runtime.aar" $built.aarSha256
    $adapter=$descriptor.artifacts|Where-Object {$_.file -eq 'adapter.aar'}
    CopyExact "$baseline/sing-box/adapter.aar" "$core/adapter.aar" $adapter.sha256
    foreach($notice in $descriptor.licenses|Where-Object {$_.file -ne 'source-manifest.json'}){CopyExact "$baseline/sing-box/licenses/$($notice.file)" "$licenses/$($notice.file)" $notice.sha256}
    $oldSource=$descriptor.licenses|Where-Object {$_.file -eq 'source-manifest.json'}
    CopyExact "$baseline/sing-box/licenses/source-manifest.json" "$licenses/adapter-baseline-source-manifest.json" $oldSource.sha256
    $provenance=[ordered]@{schema=2;upstreamCommit=$source.commit;submodules=$source.submodules;nativeArchives=$source.archives;nativeSourceManifestSha256=$source.sourceManifestSha256;nativeSourceFiles=$source.sourceFiles;routeSeamSha256=$source.routeSha256;patches=$source.patches;runtime=(Row "$core/runtime.aar");adapter=(Row "$core/adapter.aar");adapterBaselineSourceSha256=$descriptor.sourceSha256;adapterBaselineScope='Original module6 Java/SPI source only; its original native AAR is NOT this runtime. Current native provenance is the frozen pinned tree plus the listed patch files.';nativeVersion=$built.version;nativeTags=$built.tags;go='1.26.8';gomobile='github.com/sagernet/gomobile v0.1.13';ndk='28.2.13676358';androidApi=24;javaClassesByteExact=$checked.apiClasses}
    $provenance|ConvertTo-Json -Depth 10|Set-Content "$licenses/source-manifest.json" -Encoding utf8
    $module=[ordered]@{spi=1;api=1;entry=$descriptor.entry;versionCode=7;moduleVersion='1.14.2-lx.11-mcrf-direct-family.7';sourceRepository=$descriptor.sourceRepository;sourceVersion=$built.version;sourceSha256=(Sha "$licenses/source-manifest.json");abis=@('arm64-v8a','armeabi-v7a','x86_64');artifacts=@((Row "$core/runtime.aar"),(Row "$core/adapter.aar"));licenses=@(Get-ChildItem -LiteralPath $licenses -File|Sort-Object Name|ForEach-Object {Row $_.FullName})}
    @{version=1;modules=@{'sing-box'=$module};scope='P044 external candidate7 only; main18 and other engines unchanged, no P1/publication/runtime acceptance'}|ConvertTo-Json -Depth 12|Set-Content "$stage/primary-stage/receipt.json" -Encoding utf8
    $stageRows=@($module.artifacts|ForEach-Object {@{path="sing-box/$($_.file)";sha256=$_.sha256}})+@($module.licenses|ForEach-Object {@{path="sing-box/licenses/$($_.file)";sha256=$_.sha256}})
    $result.state='PACKAGING_EXTERNAL_SINGBOX7_ONLY';SavePackage
    $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$root/work/toolchains/gradle-home"
    $env:JAVA_TOOL_OPTIONS=($saved.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
    Push-Location "$stage/ports/android"
    try{& "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain ':engines:assembleSingboxRelease' '-PtestRelease=true' '-PprimaryCoreNativeGate=true' "-PprimaryCoreStage=$stage/primary-stage" '--offline' '--console=plain' *> "$stage/build.log";if($LASTEXITCODE){throw 'External module packaging failed'}}finally{Pop-Location}
    }else{
        $manifest=@(Get-Content "$stage/source-manifest.json" -Raw|ConvertFrom-Json)
        $expectedManifest=@(Get-Content "$parent/source-manifest.json" -Raw|ConvertFrom-Json)
        if(($manifest|ConvertTo-Json -Compress) -cne ($expectedManifest|ConvertTo-Json -Compress)){throw 'Existing frozen Android manifest differs from exact18 parent'}
        $module=(Get-Content "$stage/primary-stage/receipt.json" -Raw|ConvertFrom-Json).modules.'sing-box'
        if($module.versionCode -ne 7 -or ($module.artifacts|Where-Object {$_.file -eq 'runtime.aar'}).sha256 -cne $built.aarSha256 -or ($module.artifacts|Where-Object {$_.file -eq 'adapter.aar'}).sha256 -cne ($descriptor.artifacts|Where-Object {$_.file -eq 'adapter.aar'}).sha256){throw 'Existing module source artifacts differ'}
        $stageRows=@($module.artifacts|ForEach-Object {@{path="sing-box/$($_.file)";sha256=$_.sha256}})+@($module.licenses|ForEach-Object {@{path="sing-box/licenses/$($_.file)";sha256=$_.sha256}})
        $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk'
        $result.resumedStaticOnly=$true
    }
    foreach($row in $manifest){if((Sha (Safe $stage $row.path)) -cne $row.sha256){throw 'Frozen Android input changed'}}
    foreach($row in $stageRows){if((Sha (Safe "$stage/primary-stage" $row.path)) -cne $row.sha256){throw 'Frozen native/notice input changed'}}
    if(Test-Path "$stage/ports/android/app/build/outputs/apk"){throw 'Unexpected main APK build'}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    foreach($abi in @('universal','arm64-v8a','armeabi-v7a','x86_64')){
        $apk="$stage/ports/android/engines/build/outputs/apk/singbox/release/engines-singbox-$abi-release.apk"
        $cert=(& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --print-certs $apk 2>&1|Out-String)
        if($LASTEXITCODE -or $cert -notmatch 'certificate SHA-256 digest:\s*fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Module signer mismatch'}
        $badging=(& "$env:ANDROID_HOME/build-tools/36.0.0/aapt.exe" dump badging $apk 2>&1|Out-String)
        if($LASTEXITCODE -or $badging -notmatch "package: name='org.mcrf.engines.singbox' versionCode='7'"){throw 'Module identity mismatch'}
        $zip=[IO.Compression.ZipFile]::OpenRead($apk)
        try{
            $abis=if($abi -eq 'universal'){@('arm64-v8a','armeabi-v7a','x86_64')}else{@($abi)}
            $entries=@($zip.Entries|Where-Object {$_.FullName.StartsWith('lib/') -and !$_.FullName.EndsWith('/')}|ForEach-Object {$_.FullName}|Sort-Object)
            if(($entries -join '|') -cne (($abis|ForEach-Object {"lib/$_/libbox.so"}|Sort-Object) -join '|')){throw 'Foreign native payload in module'}
            foreach($a in $abis){$stream=$zip.GetEntry("lib/$a/libbox.so").Open();$hash=[Security.Cryptography.SHA256]::Create();try{$actual=([BitConverter]::ToString($hash.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}finally{$stream.Dispose();$hash.Dispose()};if($actual -cne ($checked.abis|Where-Object {$_.abi -eq $a}).sha256){throw 'APK native bytes changed'}}
            foreach($notice in $module.licenses){$entry=$zip.GetEntry("assets/$($notice.file)");if(!$entry){throw 'Module notice missing'};$stream=$entry.Open();$hash=[Security.Cryptography.SHA256]::Create();try{$actual=([BitConverter]::ToString($hash.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}finally{$stream.Dispose();$hash.Dispose()};if($actual -cne $notice.sha256){throw 'Module notice changed'}}
        }finally{$zip.Dispose()}
        $result.artifacts[$abi]=@{apk=$apk;bytes=(Get-Item $apk).Length;sha256=Sha $apk}
    }
    $result.state='PASS_EXTERNAL_MODULE7_STATIC_ONLY';$result.sourceStable=$true;SavePackage
    Write-Output "P044_MODULE=$stage/receipt.json"
}catch{$result.state='FAILED_PRESERVED';$result.error=$_.Exception.Message;SavePackage;throw}
finally{foreach($name in $envNames){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')};$current=Get-Content $currentPath -Raw|ConvertFrom-Json;if($current.jobs.build -ceq "P044_MODULE_APK:$stage"){$current.jobs.build=$null;$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8}}
