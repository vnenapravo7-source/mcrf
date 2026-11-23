param([Parameter(Mandatory=$true)][string]$Snapshot)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$frozen=[IO.Path]::GetFullPath((Join-Path $root $Snapshot))
if(!$frozen.StartsWith((Join-Path $root 'work')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($frozen) -notmatch '^android-integration-[a-f0-9]{32}$'){throw 'Invalid frozen source snapshot'}
$stage=Join-Path $root 'outputs/android-preview-20261015-1'
if(!(Test-Path -LiteralPath (Join-Path $stage 'packages.json'))){throw 'Verified publication stage required'}
$manifest=Get-Content -LiteralPath (Join-Path $frozen 'source-manifest.json') -Raw|ConvertFrom-Json
Add-Type -AssemblyName System.IO.Compression.FileSystem
$file=Join-Path $stage 'mcrf-android-source.zip'
$zip=[IO.Compression.ZipFile]::Open($file,[IO.Compression.ZipArchiveMode]::Create)
try {
    foreach($item in $manifest){
        $path=$item.path -replace '\\','/'
        $allow=$path -match '^ports/android/(app/src/main/|engines/src/main/(java/|kotlin/|assets/licenses/|AndroidManifest.xml)|primary-spi/|primary-adapter/|primary-cores/|transports/|catalogue/|gradle/wrapper/)'
        $allow=$allow -or $path -match '^ports/android/(settings.gradle.kts|build.gradle.kts|gradle.properties|gradlew|gradlew.bat|android-core-lock.json|native-core/build.ps1|optional-cores/(source-lock.json|prepare_overlay.py|build.ps1)|app/(build.gradle.kts|proguard-rules.pro)|engines/build.gradle.kts)$'
        $allow=$allow -or $path -match '^work/steer-android-port/android-vpn/integration-helper/src/main/'
        if(!$allow -or $path -match '/(build|jniLibs|libs|coordination|fixtures|\.git)/' -or $path -match '\.(apk|aar|so|keystore|jks|enc|pem|key)$'){continue}
        $source=Join-Path $frozen $path
        if((Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant() -cne $item.sha256){throw 'Frozen source changed'}
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$source,$path,[IO.Compression.CompressionLevel]::Optimal)|Out-Null
    }
}finally{$zip.Dispose()}
# Exact source archive already tied to the installed Steer module receipt. No owner checkout edits.
$steer=Join-Path $root 'outputs/steer-android/primary-stage-dev.5/source.zip'
$steerReceipt=Get-Content (Join-Path $root 'outputs/steer-android/primary-stage-dev.5/receipt.json') -Raw|ConvertFrom-Json
if((Get-FileHash -LiteralPath $steer).Hash.ToLowerInvariant() -cne $steerReceipt.modules.steer.sourceSha256){throw 'Steer source archive mismatch'}
[IO.File]::Copy($steer,(Join-Path $stage 'steer-module5-source.zip'),$false)
$core=Get-Content (Join-Path $frozen 'ports/android/android-core-lock.json') -Raw|ConvertFrom-Json
$nativeRoot=Join-Path $root 'work/android-upstream/sing-box-lx-mcrf-clash-20261014'
& git -C $nativeRoot archive --format=zip --prefix=sing-box/ -o (Join-Path $stage 'sing-box-lx-source.zip') $core.commit
if($LASTEXITCODE -ne 0){throw 'Native source archive failed'}
foreach($property in $core.submodules.PSObject.Properties){
    $name=$property.Name.Split('/')[-1]
    & git -C (Join-Path $nativeRoot $property.Name) archive --format=zip "--prefix=$name/" -o (Join-Path $stage "sing-box-dependency-$name-source.zip") $property.Value
    if($LASTEXITCODE -ne 0){throw "Native submodule source archive failed: $name"}
}
Write-Output 'Public source archives prepared from bounded source-only allowlist and exact upstream commits; no profiles, coordination receipts, credentials, APK signing key or private test inputs included'
