param([string]$AndroidSdk="$env:LOCALAPPDATA/Android/Sdk")
$ErrorActionPreference='Stop'
$engineProject=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$engineTools=Join-Path $AndroidSdk 'build-tools/36.0.0'
$engineOutput=Join-Path $engineProject 'outputs/ports/android-engines-0.1.0-dev.4'
New-Item -ItemType Directory -Path $engineOutput -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$engineApp=Join-Path $PSScriptRoot 'app/build/outputs/apk/release/app-arm64-v8a-release.apk'
$engineAppSignature=(& (Join-Path $engineTools 'apksigner.bat') verify --print-certs $engineApp|Out-String)
if($LASTEXITCODE -ne 0){throw 'Main APK signature is invalid'}
$engineSigner=[regex]::Match($engineAppSignature,'Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})').Groups[1].Value
if($engineSigner -notmatch '^[0-9a-f]{64}$'){throw 'Main APK signer digest is missing or malformed'}
$engineSums=@();$engineRows=@()
foreach($engineKind in @('csqtt','qwdtt','wdtt','openflux','olcrtc','dnstt','warpscout')){
    $engineBuild=Join-Path $PSScriptRoot "engines/build/outputs/apk/$engineKind/release"
    $engineMetadata=Get-Content -LiteralPath (Join-Path $engineBuild 'output-metadata.json') -Raw | ConvertFrom-Json
    if($engineMetadata.applicationId -ne "org.mcrf.engines.$engineKind"){throw 'Engine package identity mismatch'}
    foreach($engineAbi in @('arm64-v8a','armeabi-v7a','x86_64','universal')){
        $engineElement=@($engineMetadata.elements | Where-Object {if($engineAbi -eq 'universal'){@($_.filters).Count -eq 0}else{@($_.filters|Where-Object {$_.filterType -eq 'ABI' -and $_.value -eq $engineAbi}).Count -eq 1}})
        if($engineElement.Count -ne 1){throw "Missing split $engineKind $engineAbi"}
        $engineSource=Join-Path $engineBuild $engineElement[0].outputFile
        $engineSignature=(& (Join-Path $engineTools 'apksigner.bat') verify --print-certs $engineSource|Out-String)
        if($LASTEXITCODE -ne 0 -or [regex]::Match($engineSignature,'Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})').Groups[1].Value -ne $engineSigner){throw 'Engine/main APK signatures differ'}
        & (Join-Path $engineTools 'zipalign.exe') -c -P 16 4 $engineSource
        if($LASTEXITCODE -ne 0){throw 'Engine ZIP16K alignment failed'}
        $engineLibraries=if($engineKind -eq 'csqtt'){@('libqwdtt.so','libcsqtt_transport.so')}else{@("lib$engineKind.so")}
        $engineAbis=if($engineAbi -eq 'universal'){@('arm64-v8a','armeabi-v7a','x86_64')}else{@($engineAbi)}
        $engineExpected=@(foreach($engineNativeAbi in $engineAbis){foreach($engineLibrary in $engineLibraries){"lib/$engineNativeAbi/$engineLibrary"}})
        $engineZip=[IO.Compression.ZipFile]::OpenRead($engineSource)
        try{
            $engineActual=@($engineZip.Entries|Where-Object Name -like '*.so'|ForEach-Object FullName)
            if(@(Compare-Object ($engineExpected|Sort-Object) ($engineActual|Sort-Object)).Count){throw "Foreign/missing executable in $engineKind $engineAbi"}
            if(@($engineZip.Entries|Where-Object FullName -match 'testserver').Count){throw 'Debug fixture in engine APK'}
        }finally{$engineZip.Dispose()}
        $engineName="mcrf-engine-$engineKind-$engineAbi-release.apk";$engineTarget=Join-Path $engineOutput $engineName
        Copy-Item -LiteralPath $engineSource -Destination $engineTarget -Force
        $engineHash=(Get-FileHash -LiteralPath $engineTarget).Hash.ToLowerInvariant()
        $engineSums+="$engineHash  $engineName"
        $engineRows+=[PSCustomObject]@{engine=$engineKind;abi=$engineAbi;package=$engineMetadata.applicationId;name=$engineName;bytes=(Get-Item -LiteralPath $engineTarget).Length;sha256=$engineHash}
    }
}
[IO.File]::WriteAllLines((Join-Path $engineOutput 'SHA256SUMS.txt'),$engineSums,(New-Object Text.UTF8Encoding($false)))
[IO.File]::WriteAllText((Join-Path $engineOutput 'packages.json'),(ConvertTo-Json -InputObject $engineRows -Depth 4),(New-Object Text.UTF8Encoding($false)))
$engineRows|Format-Table engine,abi,bytes,sha256
