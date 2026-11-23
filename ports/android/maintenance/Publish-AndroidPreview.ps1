param([Parameter(Mandatory=$true)][string]$Stage,[Parameter(Mandatory=$true)][string]$Notes,[Parameter(Mandatory=$true)][string]$VersionLabels,[switch]$Apply,[switch]$EngineOnly,[switch]$ResumeAppOnly)
$ErrorActionPreference='Stop'
$gh='C:/Program Files/GitHub CLI/gh.exe';$repo='vnenapravo7-source/mcrf';$engineTag='engines-2026-10-13-1'
$stagePath=[IO.Path]::GetFullPath($Stage)
if($stagePath -notmatch '^D:\\MCRF\\Builds\\Android\\publish-android-v0\.1\.0-preview\.20261017\.[45]$' -or (Get-Item $stagePath).LinkType){throw 'Exact owned publication stage required'}
$plan=Get-Content "$Stage/plan.json" -Raw|ConvertFrom-Json
$versions=Get-Content -LiteralPath $VersionLabels -Raw|ConvertFrom-Json
$versionsRoot=[IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($VersionLabels))
if($versionsRoot -notmatch '^D:\\MCRF\\Builds\\Android\\engine-version-labels-[0-9a-f]{32}$' -or @($versions).Count -lt 30){throw 'Verified published APK versions required'}
if($plan.state -cne 'PREPARED_NOT_PUBLISHED' -or @($plan.appAssets).Count -ne 7 -or @($plan.modules).Count -ne 13){throw 'Wrong publication plan'}
$utf8=[Text.UTF8Encoding]::new($false)
function Gh([string[]]$argv){$output=(& $gh @argv 2>&1|Out-String).Trim();if($LASTEXITCODE -ne 0){throw "GitHub operation failed; inspect remote state before resuming: $output"};$output}
function Api([string]$path){Gh @('api',"repos/$repo/$path")|ConvertFrom-Json}
function Sum([string]$file){(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()}
function Save($object,[string]$file){[IO.File]::WriteAllText($file,($object|ConvertTo-Json -Depth 30),$utf8)}
function Match($remote,$local){$remote -and $remote.state -ceq 'uploaded' -and $remote.size -eq $local.size -and $remote.digest -ceq ('sha256:'+$local.sha256)}
foreach($group in @('appAssets','modules')){foreach($file in $plan.$group){
    if($file.name -notmatch '^[A-Za-z0-9._-]+$'){throw 'Unsafe asset name'}
    $path=Join-Path $Stage "$(if($group -eq 'modules'){'modules'}else{'app'})/$($file.name)"
    if((Get-Item $path).Length -ne $file.size -or (Sum $path) -cne $file.sha256){throw 'Prepared asset changed'}
}}
if(!$ResumeAppOnly){
$engine=Api "releases/tags/$engineTag"
if($engine.id -ne 411713792 -or $engine.draft -or $engine.prerelease){throw 'Wrong enduring engine release'}
$catalog=@($engine.assets|Where-Object name -CEQ 'engine-catalog.json')
if($catalog.Count -ne 1 -or $catalog[0].digest -cne 'sha256:65808eb00e6ac370124b2e9ffb8f550a4df6dd202899da195211226c074cab9f'){throw 'Windows catalogue changed; do not overwrite'}
if((Gh @('release','view','--repo',$repo,'--json','tagName')|ConvertFrom-Json).tagName -cne 'v1.0.11'){throw 'Stable latest changed; inspect before publishing'}
if(!(Test-Path "$Stage/backup/engine-before.json")){Save $engine "$Stage/backup/engine-before.json"}
$baseline=Get-Content "$Stage/backup/engine-before.json" -Raw|ConvertFrom-Json
$replaceNames=@($plan.modules.name)+@('SHA256SUMS.txt','packages.json')
$old=@($baseline.assets|Where-Object {$_.name -in $replaceNames})
if(@($old|Where-Object {$_.name -match '^mcrf-engine-'}).Count -ne 9){throw 'Unexpected replacement set'}
foreach($asset in $old){
    $file="$Stage/backup/$($asset.name)"
    if(!(Test-Path -LiteralPath $file)){
        $cached=Join-Path $versionsRoot $asset.name
        if((Test-Path -LiteralPath $cached) -and ('sha256:'+(Sum $cached)) -ceq $asset.digest){Copy-Item -LiteralPath $cached -Destination $file}
        else{Gh @('release','download',$engineTag,'--repo',$repo,'--pattern',$asset.name,'--dir',"$Stage/backup")|Out-Null}
    }
    if((Get-Item $file).Length -ne $asset.size -or ('sha256:'+(Sum $file)) -cne $asset.digest){throw 'Recovery backup differs from old published bytes'}
}
Write-Output 'Old replaced modules and metadata safely backed up and checksum verified.'
if(!$Apply){Write-Output 'PREPARED: no GitHub writes';return}
$receipt=[ordered]@{tag=$plan.tag;state='STARTED';engineRelease=$engineTag;appSnapshot=$plan.snapshot;appAssets=$plan.appAssets;modules=$plan.modules;published=$false}
Save $receipt "$Stage/publication-receipt.json"
# Metadata for immutable sources is additive; old published sources stay intact.
$sourceName="mcrf-android-source-$($plan.tag.Substring('android-v'.Length)).zip"
$sourcePath="$Stage/modules/$sourceName"
if(!(Test-Path -LiteralPath $sourcePath)){Copy-Item -LiteralPath "$Stage/app/mcrf-android-source.zip" -Destination $sourcePath}
$sourceInfo=@{name=$sourceName;size=(Get-Item $sourcePath).Length;sha256=(Sum $sourcePath)}
if($sourceInfo.sha256 -cne (@($plan.appAssets|Where-Object name -CEQ 'mcrf-android-source.zip')[0].sha256)){throw 'Source copy changed'}
function UploadEngine($local,[string]$path){
    $now=Api "releases/tags/$engineTag";$asset=@($now.assets|Where-Object name -CEQ $local.name)
    if($asset.Count -gt 1){throw 'Duplicate remote asset'}
    if($asset.Count -eq 1 -and (Match $asset[0] $local)){return}
    if($asset.Count -eq 1){
        $previous=@($baseline.assets|Where-Object name -CEQ $local.name)
        $ownedPrevious=@()
        if(Test-Path -LiteralPath "$Stage/engine-after.json"){$ownedPrevious=@((Get-Content "$Stage/engine-after.json" -Raw|ConvertFrom-Json).assets|Where-Object name -CEQ $local.name)}
        $originalMatches=$previous.Count -eq 1 -and $previous[0].id -eq $asset[0].id -and $previous[0].digest -ceq $asset[0].digest
        $ownMatches=$ownedPrevious.Count -eq 1 -and $ownedPrevious[0].id -eq $asset[0].id -and $ownedPrevious[0].digest -ceq $asset[0].digest
        if(!$originalMatches -and !$ownMatches){throw 'Remote changed independently; no overwrite'}
        if($local.name -notin $replaceNames){throw 'Overwrite outside exact allowlist'}
    }
    Gh @('release','upload',$engineTag,$path,'--repo',$repo,'--clobber')|Out-Null
    $verified=Api "releases/tags/$engineTag"
    if(!(Match (@($verified.assets|Where-Object name -CEQ $local.name)[0]) $local)){throw 'Published engine asset digest/size mismatch'}
    Write-Output "Verified published engine asset: $($local.name)"
}
UploadEngine $sourceInfo $sourcePath
foreach($module in $plan.modules){UploadEngine $module "$Stage/modules/$($module.name)"}
$receipt.state='MODULES_UPLOADED';Save $receipt "$Stage/publication-receipt.json"
$now=Api "releases/tags/$engineTag"
# Labels bind versionCode to this exact API asset/digest, not a guessed release tag.
# This avoids offering an older public module to a newer local build.
foreach($asset in $now.assets|Where-Object {$_.name -match '^mcrf-engine-.+-release\.apk$'}){
    $updated=@($plan.modules|Where-Object name -CEQ $asset.name)
    if($updated.Count -eq 1){$code=if($updated[0].kind -ceq 'csqtt25'){1}else{5}}
    else{
        $verified=@($versions|Where-Object name -CEQ $asset.name)
        if($verified.Count -ne 1 -or $verified[0].id -ne $asset.id -or $verified[0].sha256 -cne ($asset.digest -replace '^sha256:','')){throw 'Published package changed since version verification'}
        $code=$verified[0].versionCode
    }
    $label="$($asset.name); mcrf-version-code=$code"
    if($asset.label -cne $label){Gh @('api','--method','PATCH',"repos/$repo/releases/assets/$($asset.id)",'-f',"label=$label")|Out-Null}
}
$now=Api "releases/tags/$engineTag"
foreach($asset in $now.assets|Where-Object {$_.name -match '^mcrf-engine-.+-release\.apk$'}){
    if($asset.label -notmatch ('^'+[regex]::Escape($asset.name)+'; mcrf-version-code=[1-9][0-9]*$')){throw 'Published version label not confirmed'}
}
Write-Output 'Every published Android engine versionCode label verified; no binary change outside four carrier families.'
# Keep the cross-platform manifest truthful too; Windows binary/catalogue bytes stay unchanged.
$manifest=[ordered]@{schema=1;release=$engineTag;platforms=@('android','windows');linux='No Linux engine acceptance claimed';files=@($now.assets|Where-Object {$_.name -notin @('packages.json','SHA256SUMS.txt')}|Sort-Object name|ForEach-Object {@{name=$_.name;bytes=$_.size;sha256=$_.digest -replace '^sha256:','';label=$_.label}})}
Save $manifest "$Stage/modules/packages.json"
$manifestInfo=@{name='packages.json';size=(Get-Item "$Stage/modules/packages.json").Length;sha256=(Sum "$Stage/modules/packages.json")}
UploadEngine $manifestInfo "$Stage/modules/packages.json"
$now=Api "releases/tags/$engineTag"
$sums=@($now.assets|Where-Object name -CNE 'SHA256SUMS.txt'|Sort-Object name|ForEach-Object {
    if($_.digest -notmatch '^sha256:[0-9a-f]{64}$'){throw 'Missing GitHub asset digest'}
    ($_.digest -replace '^sha256:','')+'  '+$_.name
})
[IO.File]::WriteAllText("$Stage/modules/SHA256SUMS.txt",($sums -join "`n")+"`n",$utf8)
$sumsInfo=@{name='SHA256SUMS.txt';size=(Get-Item "$Stage/modules/SHA256SUMS.txt").Length;sha256=(Sum "$Stage/modules/SHA256SUMS.txt")}
UploadEngine $sumsInfo "$Stage/modules/SHA256SUMS.txt"
$now=Api "releases/tags/$engineTag"
foreach($original in $baseline.assets|Where-Object {$_.name -notin $replaceNames}){
    $same=@($now.assets|Where-Object name -CEQ $original.name)
    if($same.Count -ne 1 -or $same[0].id -ne $original.id -or $same[0].digest -cne $original.digest -or $same[0].size -ne $original.size){throw 'An unrelated engine/Windows/source asset changed'}
}
Save $now "$Stage/engine-after.json"
$receipt.state='ENGINE_CATALOGUE_VERIFIED';Save $receipt "$Stage/publication-receipt.json"
if($EngineOnly){Write-Output 'ENGINE_CATALOGUE_VERIFIED; app release not published yet';return}
}else{
    if(!$Apply){throw 'App resume requires explicit Apply'}
    $receipt=Get-Content "$Stage/publication-receipt.json" -Raw|ConvertFrom-Json -AsHashtable
    if($receipt.state -cne 'ENGINE_CATALOGUE_VERIFIED' -or $receipt.tag -cne $plan.tag){throw 'Engine phase not verified'}
    $expectedAfter=Get-Content "$Stage/engine-after.json" -Raw|ConvertFrom-Json
    $liveAfter=Api "releases/tags/$engineTag"
    if(@($expectedAfter.assets).Count -ne @($liveAfter.assets).Count){throw 'Engine catalogue changed since phase verification'}
    foreach($expected in $expectedAfter.assets){
        $actual=@($liveAfter.assets|Where-Object id -EQ $expected.id)
        if($actual.Count -ne 1 -or $actual[0].digest -cne $expected.digest -or $actual[0].size -ne $expected.size -or $actual[0].label -cne $expected.label){throw 'Engine bytes/labels changed since verified phase'}
    }
    Write-Output 'Verified existing completed engine phase; resume app draft by numeric release ID.'
}
$releases=Api 'releases?per_page=50'
$existing=@($releases|Where-Object tag_name -CEQ $plan.tag)
if(!$existing.Count){
    $commit=(Api 'commits/main').sha
    Gh @('release','create',$plan.tag,'--repo',$repo,'--target',$commit,'--title',"MCRF Android $($plan.tag.Substring('android-v'.Length))",'--notes-file',$Notes,'--draft','--prerelease','--latest=false')|Out-Null
    $existing=@((Api 'releases?per_page=50')|Where-Object tag_name -CEQ $plan.tag)
}
if($existing.Count -ne 1 -or !$existing[0].prerelease){throw 'Unexpected existing app release'}
$appReleaseId=$existing[0].id
foreach($local in $plan.appAssets){
    $current=Api "releases/$appReleaseId";$asset=@($current.assets|Where-Object name -CEQ $local.name)
    if($asset.Count -eq 1 -and (Match $asset[0] $local)){continue}
    if($asset.Count -or !$current.draft){throw 'Refuse to overwrite app release bytes'}
    Gh @('release','upload',$plan.tag,"$Stage/app/$($local.name)",'--repo',$repo)|Out-Null
    Write-Output "Uploaded app asset: $($local.name)"
}
$current=Api "releases/$appReleaseId"
if(@($current.assets).Count -ne @($plan.appAssets).Count){throw 'Unexpected app assets'}
foreach($local in $plan.appAssets){if(!(Match (@($current.assets|Where-Object name -CEQ $local.name)[0]) $local)){throw 'App asset digest/size mismatch'}}
if($current.draft){Gh @('api','--method','PATCH',"repos/$repo/releases/$appReleaseId",'-F','draft=false','-F','prerelease=true','-f','make_latest=false')|Out-Null}
$final=Api "releases/$appReleaseId"
if($final.draft -or !$final.prerelease -or (Gh @('release','view','--repo',$repo,'--json','tagName')|ConvertFrom-Json).tagName -cne 'v1.0.11'){throw 'Final visibility/latest verification failed'}
Save $final "$Stage/app-release-after.json"
$receipt.state='PUBLISHED_VERIFIED';$receipt.published=$true;$receipt.url=$final.html_url;$receipt.unrelatedAssetsPreserved=$true;$receipt.windowsLatestPreserved=$true
Save $receipt "$Stage/publication-receipt.json"
Write-Output "PUBLISHED_VERIFIED=$($final.html_url)"
