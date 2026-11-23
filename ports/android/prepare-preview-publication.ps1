param([Parameter(Mandatory=$true)][string]$Snapshot)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$snapshotRoot=[IO.Path]::GetFullPath((Join-Path $root $Snapshot))
if(!$snapshotRoot.StartsWith((Join-Path $root 'work')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($snapshotRoot) -notmatch '^android-integration-[a-f0-9]{32}$'){throw 'Invalid snapshot'}
$build=Get-Content -LiteralPath (Join-Path $snapshotRoot 'build-result.json') -Raw|ConvertFrom-Json
if($build.exitCode -ne 0 -or !$build.sourceStable -or $build.firstLaunchFixture){throw 'Not a green normal app build'}
$stage=Join-Path $root 'outputs/android-preview-20261015-1'
if(Test-Path -LiteralPath $stage){throw 'Publication stage already exists; inspect rather than overwrite'}
New-Item -ItemType Directory -Path $stage|Out-Null
$tools='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$rows=[Collections.Generic.List[object]]::new()
function StageApk([string]$Source,[string]$Name,[string]$Package,[string]$Abi,[bool]$Module){
    $sourceFile=Get-Item -LiteralPath $Source
    $signer=(& "$tools/apksigner.bat" verify --print-certs $sourceFile.FullName 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $signer -notmatch 'Signer #1 certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw "Unexpected signer: $Name"}
    $badging=(& "$tools/aapt2.exe" dump badging $sourceFile.FullName 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $badging -notmatch ("(?m)^package: name='"+[regex]::Escape($Package)+"' versionCode='(\d+)'")){throw "Unexpected package: $Name"}
    $version=[long]$Matches[1]
    $archive=[IO.Compression.ZipFile]::OpenRead($sourceFile.FullName)
    try {
        $native=@($archive.Entries|Where-Object {$_.FullName -match '^lib/[^/]+/[^/]+\.so$'}|ForEach-Object {$_.FullName})
        if(!$Module -and $native.Count){throw 'Base APK contains native engines'}
        if($Module -and (!$native.Count -or @($native|Where-Object {!$_.StartsWith("lib/$Abi/")}).Count)){throw "Unexpected module ABI: $Name"}
        if($Module -and !@($archive.Entries|Where-Object {$_.FullName -match '(?i)(license|notice|copying)'}).Count){throw "Missing source/license notices: $Name"}
    }finally{$archive.Dispose()}
    $sha=(Get-FileHash -LiteralPath $sourceFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $target=Join-Path $stage $Name
    [IO.File]::Copy($sourceFile.FullName,$target,$false)
    if((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -cne $sha){throw 'Staging SHA mismatch'}
    $rows.Add([ordered]@{name=$Name;package=$Package;abi=$Abi;versionCode=$version;bytes=$sourceFile.Length;sha256=$sha;module=$Module})
}
foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64','universal')){
    $name=switch($abi){'arm64-v8a'{'arm64-v8a-release.apk'}'armeabi-v7a'{'armeabi-v7a-release.apk'}'x86_64'{'androidApp-x86_64-release.apk'}'universal'{'mcrf-android-universal-release.apk'}}
    StageApk (Join-Path $snapshotRoot "ports/android/app/build/outputs/apk/release/app-$abi-release.apk") $name 'org.mcrf.client' $abi $false
}
foreach($kind in @('sing-box','steer','tgws','xray','tls-core','csqtt','qwdtt','wdtt','olcrtc','dnstt','warpscout','openflux')){
    foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){
        if($kind -eq 'steer' -and $abi -eq 'armeabi-v7a'){continue} # No fake 32-bit Steer payload.
        $flavor=switch($kind){'sing-box'{'singbox'}'tls-core'{'tlscore'}default{$kind}}
        $package="org.mcrf.engines.$flavor"
        $source=if($kind -in @('sing-box','steer','tgws','xray','tls-core')){
            Join-Path $snapshotRoot "ports/android/engines/build/outputs/apk/$flavor/release/engines-$flavor-$abi-release.apk"
        }elseif($kind -eq 'openflux'){
            Join-Path $root "outputs/android-intermediate-20261015/engines-openflux-$abi-release.apk"
        }else{Join-Path $root "outputs/ports/android-engines-0.1.0-dev.4/mcrf-engine-$kind-$abi-release.apk"}
        StageApk $source "mcrf-engine-$kind-$abi-release.apk" $package $abi $true
    }
}
[IO.File]::WriteAllText((Join-Path $stage 'packages.json'),([ordered]@{schema=1;appVersion='0.1.0-preview.20261015.1';developmentSigned=$true;signer='fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0';files=$rows;limits=@('Steer has no armeabi-v7a build','OpenFlux ordinary module7 downloads but HTTPS traffic is still failing; not a runtime fix','Non-arm64 runtime not device verified','Prerelease, not full release acceptance')}|ConvertTo-Json -Depth 5))
[IO.File]::WriteAllLines((Join-Path $stage 'SHA256SUMS.txt'),@($rows|ForEach-Object {"$($_.sha256)  $($_.name)"}))
Write-Output "VERIFIED_PUBLICATION_STAGE=$stage ($($rows.Count) APKs)"
