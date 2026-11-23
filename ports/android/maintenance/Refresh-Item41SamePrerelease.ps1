param([Parameter(Mandatory=$true)][string]$Stage,[Parameter(Mandatory=$true)][string]$PhoneUiReceipt,[switch]$Apply,[switch]$UiRound2Revision,[switch]$SelectionHotfixRevision)
$ErrorActionPreference='Stop'
$repo='vnenapravo7-source/mcrf';$id=416790557;$tag='android-v0.1.0-preview.20261019.1'
$gh='C:/Program Files/GitHub CLI/gh.exe';$utf8=[Text.UTF8Encoding]::new($false)
$backup='D:/MCRF/Builds/Android/item41-prerelease-282c76b04ef4429483bff3674649534c'
if($UiRound2Revision){$backup='D:/MCRF/Builds/Android/item41-prerelease-0754f01afb10465aa38522921ee5eb60'}
if($SelectionHotfixRevision){if($UiRound2Revision){throw 'Conflicting revision modes'};$backup='D:/MCRF/Builds/Android/item41-prerelease-63d236fb996a4f3c8231389e30e78d92'}
function Gh([string[]]$argv){$out=(& $gh @argv 2>&1|Out-String).Trim();if($LASTEXITCODE){throw 'Remote operation failed; preserve concrete IDs and inspect before any resume'};$out}
function Api([string]$path){(Gh @('api',"repos/$repo/$path"))|ConvertFrom-Json}
function Sum([string]$file){(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()}
function Save($value,[string]$file){[IO.File]::WriteAllText($file,($value|ConvertTo-Json -Depth 14),$utf8)}
function Shape($r){@($r.assets|Sort-Object name|ForEach-Object {@{id=$_.id;name=$_.name;digest=$_.digest;size=$_.size;label=$_.label}})|ConvertTo-Json -Depth 4 -Compress}
$stagePath=[IO.Path]::GetFullPath($Stage)
if($stagePath -cnotmatch '^D:\\MCRF\\Builds\\Android\\item41-prerelease-[a-f0-9]{32}$'){throw 'Unexpected prepared stage'}
$plan=Get-Content "$stagePath/plan.json" -Raw|ConvertFrom-Json
$old=Get-Content "$backup/plan.json" -Raw|ConvertFrom-Json
$ui=Get-Content $PhoneUiReceipt -Raw|ConvertFrom-Json
if($SelectionHotfixRevision){
    $approval=Get-Content "$stagePath/human-approval.json" -Raw|ConvertFrom-Json
    $evidence=Get-Content "$stagePath/app/BUILD_EVIDENCE.json" -Raw|ConvertFrom-Json
    if(!$approval.humanApprovedSameReleaseRefresh -or !$approval.humanReportedAppChecked -or $approval.releaseId -ne $id -or $approval.tag -cne $tag -or $approval.versionCode -ne 10017 -or $ui.versionCode -ne 10017 -or $evidence.versionCode -ne 10017 -or $approval.mainSha256 -cne $plan.mainSha256){throw 'Exact10017 install and fresh human acceptance/publication approval required'}
}
if($UiRound2Revision -and ($ui.versionCode -ne 10015 -or !$ui.keyboardFooter -or !$ui.proxyAuthFooter -or !$ui.themeBarsBothDirections)){throw 'Final10015 actual keyboard/auth/theme UI evidence required'}
if($plan.tag -cne $tag -or !$plan.phoneVerified -or @($plan.assets).Count -ne 7 -or @($old.assets).Count -ne 7){throw 'Prepared guarded seven-asset revision required'}
if(!$ui.passed -or !$ui.protectedUnchanged -or !$ui.modulesUnchanged -or $ui.sha256 -cne $plan.mainSha256){throw 'Fresh final scoped UI+data evidence required'}
foreach($set in @(@{items=$old.assets;root="$backup/app"},@{items=$plan.assets;root="$stagePath/app"})){
    foreach($asset in $set.items){if($asset.name -notmatch '^[a-zA-Z0-9_.-]+$' -or (Sum "$($set.root)/$($asset.name)") -cne $asset.sha256 -or (Get-Item "$($set.root)/$($asset.name)").Length -ne $asset.bytes){throw 'Recoverable backup/new asset verification failed'}}
}
if((@($old.assets.name|Sort-Object)-join '|') -cne (@($plan.assets.name|Sort-Object)-join '|')){throw 'Revision must replace only the same explicit seven names'}
$before=Api "releases/$id"
if($before.tag_name -cne $tag -or !$before.prerelease -or $before.draft -or @($before.assets).Count -ne 7){throw 'Exact published existing prerelease required'}
foreach($asset in $old.assets){$found=@($before.assets|Where-Object name -CEQ $asset.name);if($found.Count -ne 1 -or $found[0].digest -cne "sha256:$($asset.sha256)" -or $found[0].size -ne $asset.bytes){throw 'Remote old revision drift; do not delete anything'}}
$engine=Api 'releases/tags/engines-2026-10-13-1';$latest=Api 'releases/latest'
if($latest.tag_name -cne 'v1.0.11'){throw 'Unexpected stable latest'}
Save $before "$stagePath/replace-release-before.json";Save $engine "$stagePath/replace-engines-before.json"
Save $latest "$stagePath/replace-latest-before.json"
# Verify every public source entry against the immutable source manifest, not only ZIP size.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead("$stagePath/app/mcrf-android-source.zip")
try{
    $manifestEntry=$zip.GetEntry('SOURCE_MANIFEST.json');$reader=[IO.StreamReader]::new($manifestEntry.Open())
    try{$manifest=$reader.ReadToEnd()|ConvertFrom-Json}finally{$reader.Dispose()}
    foreach($row in $manifest){$entry=$zip.GetEntry($row.path);if(!$entry){throw 'Missing archived source'};$stream=$entry.Open();$hash=[Security.Cryptography.SHA256]::Create();try{$actual=([BitConverter]::ToString($hash.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}finally{$stream.Dispose();$hash.Dispose()};if($actual -cne $row.sha256){throw 'Archived source hash mismatch'}}
}finally{$zip.Dispose()}
if(!$Apply){Write-Output "REFRESH_PREPARED=$stagePath;exactRelease=$id;oldBackupVerified=true";return}
if(Test-Path "$stagePath/replacement-receipt.json"){throw 'Replacement already attempted; inspect saved IDs before resuming'}
$receipt=[ordered]@{state='STAGING';releaseId=$id;tag=$tag;oldBackup=$backup;oldBackupVerified=$true;sourceArchiveVerified=$true;newMainSha256=$plan.mainSha256;temporaryAssets=@();replaced=@();startedUtc=[DateTime]::UtcNow.ToString('o')}
Save $receipt "$stagePath/replacement-receipt.json"
try{
    [IO.Directory]::CreateDirectory("$stagePath/temporary-upload")|Out-Null
    # Stage and verify ALL new bytes before removing any old published asset.
    foreach($asset in $plan.assets){
        $revision=if($SelectionHotfixRevision){10017}elseif($UiRound2Revision){10015}else{10012}
        $temporary="revision$revision-$($asset.name)"
        [IO.File]::Copy("$stagePath/app/$($asset.name)","$stagePath/temporary-upload/$temporary",$false)
        Gh @('release','upload',$tag,"$stagePath/temporary-upload/$temporary",'--repo',$repo)|Out-Null
        $current=Api "releases/$id";$remote=@($current.assets|Where-Object name -CEQ $temporary)
        if($remote.Count -ne 1 -or $remote[0].digest -cne "sha256:$($asset.sha256)" -or $remote[0].size -ne $asset.bytes){throw 'Staged upload not verified; originals remain recoverable'}
        $receipt.temporaryAssets+=@{id=$remote[0].id;temporaryName=$temporary;finalName=$asset.name;sha256=$asset.sha256;bytes=$asset.bytes}
        Save $receipt "$stagePath/replacement-receipt.json"
        Write-Output "StagedVerified=$($asset.name)"
    }
    if((Shape $engine) -cne (Shape (Api 'releases/tags/engines-2026-10-13-1'))){throw 'Engine catalogue drift before replace'}
    $receipt.state='REPLACING';Save $receipt "$stagePath/replacement-receipt.json"
    foreach($entry in $receipt.temporaryAssets){
        $prior=@($before.assets|Where-Object name -CEQ $entry.finalName)[0]
        $current=Api "releases/$id";$currentPrior=@($current.assets|Where-Object id -EQ $prior.id)
        if($currentPrior.Count -ne 1 -or $currentPrior[0].digest -cne $prior.digest -or $current.tag_name -cne $tag){throw 'Exact original target changed; no delete'}
        # Human explicitly authorized replacement of these names; originals verified locally above.
        Gh @('api','--method','DELETE',"repos/$repo/releases/assets/$($prior.id)")|Out-Null
        Gh @('api','--method','PATCH',"repos/$repo/releases/assets/$($entry.id)",'-f',"name=$($entry.finalName)")|Out-Null
        $receipt.replaced+=$entry.finalName;Save $receipt "$stagePath/replacement-receipt.json"
    }
    $notes=[IO.File]::ReadAllText("$stagePath/NOTES.md")
    Gh @('api','--method','PATCH',"repos/$repo/releases/$id",'-f',"body=$notes",'-f','make_latest=false')|Out-Null
    $final=Api "releases/$id"
    if($final.tag_name -cne $tag -or $final.draft -or !$final.prerelease -or @($final.assets).Count -ne 7 -or $final.target_commitish -cne $before.target_commitish){throw 'Final existing release identity mismatch'}
    foreach($asset in $plan.assets){$remote=@($final.assets|Where-Object name -CEQ $asset.name);if($remote.Count -ne 1 -or $remote[0].digest -cne "sha256:$($asset.sha256)" -or $remote[0].size -ne $asset.bytes){throw 'Final asset digest/size mismatch'}}
    if((Api 'releases/latest').id -ne $latest.id -or (Shape (Api 'releases/tags/engines-2026-10-13-1')) -cne (Shape $engine)){throw 'Unrelated stable/catalogue drift'}
    Save $final "$stagePath/replace-release-after.json"
    $receipt.state='SAME_RELEASE_REFRESHED_VERIFIED';$receipt.url=$final.html_url;$receipt.engineCatalogueUnchanged=$true;$receipt.stableWindowsLatestPreserved=$true
}catch{$receipt.state='FAILED_PRESERVED';$receipt.error=$_.Exception.Message;throw}
finally{$receipt.finishedUtc=[DateTime]::UtcNow.ToString('o');Save $receipt "$stagePath/replacement-receipt.json";Write-Output "REFRESH=$($receipt.state);RECEIPT=$stagePath/replacement-receipt.json"}
