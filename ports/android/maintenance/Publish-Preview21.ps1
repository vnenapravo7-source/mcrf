param()
$ErrorActionPreference='Stop'
$repo='vnenapravo7-source/mcrf';$stage='D:/MCRF/Builds/Android/preview21-6f19501d734e48e19f543bda2b5f1265';$gh='C:/Program Files/GitHub CLI/gh.exe';$utf8=[Text.UTF8Encoding]::new($false)
function Gh([string[]]$a){$out=(& $gh @a 2>&1|Out-String).Trim();if($LASTEXITCODE){throw ("GitHub $($a[0]) failed: $out")};$out}
function Api([string]$p){(Gh @('api',"repos/$repo/$p"))|ConvertFrom-Json}
function Sha([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Save($v,[string]$p){[IO.File]::WriteAllText($p,($v|ConvertTo-Json -Depth 20),$utf8)}
function Shape($r){@{id=$r.id;tag=$r.tag_name;target=$r.target_commitish;draft=$r.draft;preview=$r.prerelease;name=$r.name;body=$r.body;assets=@($r.assets|Sort-Object name|ForEach-Object {@{id=$_.id;name=$_.name;bytes=$_.size;sha=$_.digest;label=$_.label}})}|ConvertTo-Json -Depth 6 -Compress}
$plan=Get-Content -LiteralPath "$stage/publication-plan.json" -Raw|ConvertFrom-Json
$source=Get-Content -LiteralPath "$stage/source-publication-receipt.json" -Raw|ConvertFrom-Json
if($plan.tag -cne 'android-v0.1.0-preview.20261020.1' -or $plan.versionCode -ne 10021 -or @($plan.assets).Count -ne 20 -or $source.state -cne 'SOURCE_COMMIT_PUSHED_VERIFIED' -or !$source.excludedP2){throw 'Exact reviewed source/release plan required'}
foreach($a in $plan.assets){if((Sha "$stage/assets/$($a.name)") -cne $a.sha256 -or (Get-Item -LiteralPath "$stage/assets/$($a.name)").Length -ne $a.bytes){throw 'Prepared asset drift'}}
if((Api 'git/ref/heads/main').object.sha -cne $source.commit){throw 'Branch advanced; inspect before choosing release target'}
if(!(Test-Path -LiteralPath "$stage/old-releases-before.json")){
 $before=@((Api 'releases/417520114'),(Api 'releases/416790557'),(Api 'releases/tags/engines-2026-10-13-1'),(Api 'releases/latest'))
 if($before[0].tag_name -cne 'android-v0.1.0-preview.20261019.2' -or $before[3].tag_name -cne 'v1.0.11'){throw 'Protected baseline changed'}
 Save $before "$stage/old-releases-before.json"
}
$protected=@(Get-Content -LiteralPath "$stage/old-releases-before.json" -Raw|ConvertFrom-Json)
foreach($r in $protected){if((Shape (Api "releases/$($r.id)")) -cne (Shape $r)){throw 'Protected release/catalogue drift'}}
$receiptPath="$stage/publication-receipt.json"
$receipt=if(Test-Path -LiteralPath $receiptPath){Get-Content -LiteralPath $receiptPath -Raw|ConvertFrom-Json -AsHashtable}else{@{state='PUBLISHING';tag=$plan.tag;published=$false;startedUtc=[DateTime]::UtcNow.ToString('o');sourceCommit=$source.commit}}
try{
 $found=@((Api 'releases?per_page=50')|Where-Object tag_name -CEQ $plan.tag)
 if(!$found.Count){
  if($receipt.releaseId){throw 'Previously created release missing; do not create another'}
  Gh @('release','create',$plan.tag,'--repo',$repo,'--target',$source.commit,'--title','MCRF Android 0.1.0-preview.20261020.1','--notes-file',"$stage/NOTES.md",'--draft','--prerelease','--latest=false')|Out-Null
  $found=@((Api 'releases?per_page=50')|Where-Object tag_name -CEQ $plan.tag)
 }
 if($found.Count -ne 1 -or !$found[0].prerelease -or $found[0].target_commitish -cne $source.commit -or $receipt.releaseId -and $receipt.releaseId -ne $found[0].id){throw 'Concrete new prerelease identity mismatch'}
 $id=$found[0].id;$receipt.releaseId=$id;Save $receipt $receiptPath
 foreach($a in $plan.assets){
  $current=Api "releases/$id";$remote=@($current.assets|Where-Object name -CEQ $a.name)
  if(!$remote.Count){Gh @('release','upload',$plan.tag,"$stage/assets/$($a.name)",'--repo',$repo)|Out-Null;$remote=@((Api "releases/$id").assets|Where-Object name -CEQ $a.name)}
  if($remote.Count -ne 1 -or $remote[0].size -ne $a.bytes -or $remote[0].digest -cne "sha256:$($a.sha256)" -or $remote[0].state -cne 'uploaded'){throw 'Uploaded asset SHA256/size/state mismatch'}
  if($a.label -and $remote[0].label -cne $a.label){Gh @('api','--method','PATCH',"repos/$repo/releases/assets/$($remote[0].id)",'-f',"label=$($a.label)")|Out-Null}
  Write-Output "VERIFIED=$($a.name)"
 }
 $current=Api "releases/$id";if(@($current.assets).Count -ne 20){throw 'Unexpected final asset count'}
 foreach($a in $plan.assets){$remote=@($current.assets|Where-Object name -CEQ $a.name);if($remote.Count -ne 1 -or $remote[0].digest -cne "sha256:$($a.sha256)" -or $remote[0].size -ne $a.bytes -or $a.label -and $remote[0].label -cne $a.label){throw 'Final asset verification failed'}}
 foreach($r in $protected){if((Shape (Api "releases/$($r.id)")) -cne (Shape $r)){throw 'Protected release changed'}}
 if($current.draft){Gh @('api','--method','PATCH',"repos/$repo/releases/$id",'-F','draft=false','-F','prerelease=true','-f','make_latest=false')|Out-Null}
 $final=Api "releases/$id"
 if($final.draft -or !$final.prerelease -or (Api 'releases/latest').tag_name -cne 'v1.0.11' -or (Api "git/ref/tags/$($plan.tag)").object.sha -cne $source.commit){throw 'Final public release/tag/latest mismatch'}
 Save $final "$stage/release-after.json"
 $receipt.state='PUBLISHED_VERIFIED';$receipt.published=$true;$receipt.url=$final.html_url;$receipt.assetsVerified=20;$receipt.oldReleasesUnchanged=$true;$receipt.engineCatalogueUnchanged=$true;$receipt.windowsLatestUnchanged=$true
}catch{$receipt.state='FAILED_PRESERVED';$receipt.error=$_.Exception.Message;throw}
finally{$receipt.finishedUtc=[DateTime]::UtcNow.ToString('o');Save $receipt $receiptPath;Write-Output "PUBLICATION=$($receipt.state);RECEIPT=$receiptPath"}
