param()
$ErrorActionPreference='Stop'
$taskRepo='vnenapravo7-source/mcrf'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
if($taskRoot -ne 'D:\MCRF\Projects\MCRF'){throw 'Unexpected source root'}
$taskStage=Join-Path $taskRoot 'outputs/unified-engines'
New-Item -ItemType Directory -Path $taskStage -Force|Out-Null
$taskTags=@('engines-2026-10-02-1','engines-2026-10-13-1','android-engines-2026-10-15-1','android-engines-2026-10-15-2')
$taskChosen=@{}
foreach($taskTag in $taskTags){
    $taskRelease=(& gh release view $taskTag --repo $taskRepo --json assets|Out-String|ConvertFrom-Json)
    if($LASTEXITCODE -ne 0){throw 'Cannot inventory release'}
    foreach($taskAsset in $taskRelease.assets){
        $taskName=$taskAsset.name
        if($taskName -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]+$'){throw 'Unexpected asset name'}
        if($taskName -in @('packages.json','SHA256SUMS.txt','engine-catalog.json')){continue}
        if($taskChosen.ContainsKey($taskName) -and $taskName -notlike 'mcrf-engine-warpscout-*-release.apk'){throw 'Unexpected duplicate asset'}
        $taskChosen[$taskName]=[pscustomobject]@{tag=$taskTag;asset=$taskAsset}
    }
}
foreach($taskName in @($taskChosen.Keys|Sort-Object)){
    $taskItem=$taskChosen[$taskName];$taskAsset=$taskItem.asset
    $taskSha=([string]$taskAsset.digest) -replace '^sha256:',''
    if($taskSha -notmatch '^[0-9a-f]{64}$'){throw 'Missing published SHA256'}
    $taskDestination=Join-Path $taskStage $taskName
    if(!(Test-Path -LiteralPath $taskDestination)){
        $taskLocal=if($taskItem.tag -eq 'android-engines-2026-10-15-1'){Join-Path $taskRoot "outputs/android-preview-20261015-1/$taskName"}
            elseif($taskItem.tag -eq 'android-engines-2026-10-15-2'){Join-Path $taskRoot "outputs/android-warp-fast-20261015-5/$taskName"}else{$null}
        if($taskLocal -and (Test-Path -LiteralPath $taskLocal)){
            if((Get-FileHash -LiteralPath $taskLocal -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskSha){throw 'Local staged source hash mismatch'}
            Copy-Item -LiteralPath $taskLocal -Destination $taskDestination
        }else{
            & gh release download $taskItem.tag --repo $taskRepo --pattern $taskName --dir $taskStage
            if($LASTEXITCODE -ne 0){throw 'Published asset download failed'}
        }
    }
    if((Get-Item -LiteralPath $taskDestination).Length -ne $taskAsset.size -or (Get-FileHash -LiteralPath $taskDestination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskSha){throw 'Asset verification failed'}
    Write-Output "VERIFIED $taskName"
}
$taskCatalogText=(& gh release download engines-2026-10-13-1 --repo $taskRepo --pattern engine-catalog.json --output -|Out-String)
if($LASTEXITCODE -ne 0){throw 'Cannot read Windows catalogue'}
$taskCatalog=$taskCatalogText|ConvertFrom-Json
if($taskCatalog.Version -ne 1 -or $taskCatalog.Engines.Count -ne 4){throw 'Unexpected Windows catalogue schema'}
foreach($taskEngine in $taskCatalog.Engines){
    $taskUri=[uri]$taskEngine.Url
    if($taskUri.Host -ne 'github.com' -or !$taskUri.AbsolutePath.StartsWith('/vnenapravo7-source/mcrf/releases/download/')){throw 'Unexpected catalogue URL'}
    $taskFilename=[IO.Path]::GetFileName($taskUri.AbsolutePath)
    if(!$taskChosen.ContainsKey($taskFilename)){throw 'Missing Windows package'}
    if((Get-FileHash -LiteralPath (Join-Path $taskStage $taskFilename) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskEngine.Sha256 -or (Get-Item -LiteralPath (Join-Path $taskStage $taskFilename)).Length -ne $taskEngine.Size){throw 'Windows catalogue/package mismatch'}
    # Existing Windows catalogue is byte-for-byte preserved below; no client/catalogue migration.
}
$taskUtf8=[Text.UTF8Encoding]::new($false)
$taskCatalogPath=Join-Path $taskStage 'engine-catalog.json'
# Download exact original bytes (Out-String/JSON reserialization would change its digest).
& gh api 'repos/vnenapravo7-source/mcrf/releases/tags/engines-2026-10-13-1' --jq '.assets[]|select(.name=="engine-catalog.json")|.browser_download_url' | ForEach-Object {
    Invoke-WebRequest -Uri $_ -OutFile $taskCatalogPath
}
if((Get-FileHash -LiteralPath $taskCatalogPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne '30f27e0451ed260798d53f3b056fe4579f44696fb4fb27706bfc0d4af6b5c6d3'){throw 'Original Windows catalogue identity changed'}
$taskFiles=@(Get-ChildItem -LiteralPath $taskStage -File|Where-Object {$_.Name -notin @('packages.json','SHA256SUMS.txt')}|Sort-Object Name|ForEach-Object {
    [ordered]@{name=$_.Name;bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
})
$taskManifest=[ordered]@{schema=1;release='engines-2026-10-13-1';platforms=@('android','windows');linux='No previously published Linux engine assets; publish only after platform acceptance';files=$taskFiles}
[IO.File]::WriteAllText((Join-Path $taskStage 'packages.json'),($taskManifest|ConvertTo-Json -Depth 8),$taskUtf8)
$taskSums=@(Get-ChildItem -LiteralPath $taskStage -File|Where-Object Name -ne 'SHA256SUMS.txt'|Sort-Object Name|ForEach-Object {(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()+'  '+$_.Name})
[IO.File]::WriteAllText((Join-Path $taskStage 'SHA256SUMS.txt'),($taskSums -join "`n")+"`n",$taskUtf8)
Write-Output "UNIFIED_STAGE=$taskStage VERIFIED_FILES=$($taskFiles.Count)"
