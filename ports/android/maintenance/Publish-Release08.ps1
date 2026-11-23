param([Parameter(Mandatory)][string]$Stage,
 [string]$BuildTools='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0')
. "$PSScriptRoot/Release08.Common.ps1"
$root=Stage08 $Stage;$build=Build08 $root;$null=Acceptance08 $root $build
foreach($asset in $build.assets){Apk08 "$root/assets/$($asset.name)" $asset $build.signer $BuildTools}
$gh='C:/Program Files/GitHub CLI/gh.exe';$repo='vnenapravo7-source/mcrf'
$tag='android-v2.0';$catalogue='engines-2026-10-13-1'
function Gh08([string[]]$Arguments){Run08 $gh $Arguments}
function Api08([string]$Path){(Gh08 @('api',"repos/$repo/$Path"))|ConvertFrom-Json -AsHashtable}
function VerifyRemote08($Release,[string]$Name,[string]$Path){
 $row=@($Release.assets|Where-Object name -CEQ $Name)
 if($row.Count -ne 1 -or $row[0].digest -cne ('sha256:'+(Hash08 $Path)) -or $row[0].size -ne (Get-Item -LiteralPath $Path).Length){throw 'Remote asset digest/size differs'}
 $row[0]
}
if(Test-Path "$root/publication"){throw 'Existing publication attempt: inspect partial state, no automatic clobber/retry'}
$null=New-Item -ItemType Directory "$root/publication","$root/publication/backup"
$result=@{state='PREFLIGHT';versionName='2.0';versionCode=10065;published=$false;tag=$tag;buildReceiptSha256=Hash08 "$root/receipt.json";deviceAcceptanceSha256=Hash08 "$root/device-acceptance.json"}
try{
 $before=Api08 "releases/tags/$catalogue";$latest=Api08 'releases/latest'
 if($before.draft -or $before.prerelease){throw 'Existing ordinary engine catalogue required'}
 if(@(Gh08 @('release','list','--repo',$repo,'--limit','1000','--json','tagName')|ConvertFrom-Json|Where-Object tagName -CEQ $tag).Count){throw '2.0 already exists; no replacement'}
 Save08 "$root/publication/catalogue-before.json" $before;Save08 "$root/publication/latest-before.json" $latest
 $oldSums=@($before.assets|Where-Object name -CEQ 'SHA256SUMS.txt')
 if($oldSums.Count -ne 1){throw 'Existing catalogue checksum inventory required'}
 $null=Gh08 @('release','download',$catalogue,'--repo',$repo,'--pattern','SHA256SUMS.txt','--dir',"$root/publication/backup")
 if($oldSums[0].digest -cne ('sha256:'+(Hash08 "$root/publication/backup/SHA256SUMS.txt"))){throw 'Catalogue checksum backup differs'}
 $modules=@($build.assets|Where-Object kind -CNE 'app')
 $catalogueName='mcrf-android-2.0-engines.json'
 if(@($before.assets|Where-Object name -CEQ $catalogueName).Count){throw '2.0 catalogue metadata already exists'}
 foreach($asset in $modules){
  $old=@($before.assets|Where-Object name -CEQ $asset.name)
  if($old.Count -gt 1){throw 'Ambiguous catalogue asset'}
  if($old.Count){
   if($old[0].label -notmatch 'mcrf-version-code=([0-9]+)' -or [int]$Matches[1] -ge $asset.versionCode){throw 'Verified forward engine catalogue version required'}
   $null=Gh08 @('release','download',$catalogue,'--repo',$repo,'--pattern',$asset.name,'--dir',"$root/publication/backup")
   if($old[0].digest -cne ('sha256:'+(Hash08 "$root/publication/backup/$($asset.name)"))){throw 'Catalogue backup differs'}
  }
 }
 Save08 "$root/publication/$catalogueName" @{version='2.0';versionCode=10065;engines=$modules;steerPublishedSeparately=$true;sourceArchiveSha256=$build.sourceArchive.sha256}
 $appFiles=@($build.assets|Where-Object kind -CEQ 'app'|ForEach-Object {"$root/assets/$($_.name)"})+@($build.sourceArchive.path)
 $sums="$root/publication/SHA256SUMS.txt"
 [IO.File]::WriteAllText($sums,(($appFiles|ForEach-Object {(Hash08 $_)+'  '+[IO.Path]::GetFileName($_)}) -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
 $appFiles+=@($sums)
 $result.state='CREATING_DRAFT';Save08 "$root/publication/receipt.json" $result
 $null=Gh08 (@('release','create',$tag,'--repo',$repo,'--draft','--latest=false','--title','MCRF Android 2.0','--notes-file',$build.releaseNotes.path)+$appFiles)
 $draft=Api08 "releases/tags/$tag"
 foreach($file in $appFiles){$null=VerifyRemote08 $draft ([IO.Path]::GetFileName($file)) $file}
 foreach($asset in $modules){
  $current=Api08 "releases/tags/$catalogue"
  $old=@($before.assets|Where-Object name -CEQ $asset.name);$now=@($current.assets|Where-Object name -CEQ $asset.name)
  if($now.Count -ne $old.Count -or ($old.Count -and ($now[0].id -ne $old[0].id -or $now[0].digest -cne $old[0].digest))){throw 'Concurrent catalogue mutation'}
  $args08=@('release','upload',$catalogue,"$root/assets/$($asset.name)",'--repo',$repo)
  if($old.Count){$args08+='--clobber'}
  $null=Gh08 $args08
  $remote=VerifyRemote08 (Api08 "releases/tags/$catalogue") $asset.name "$root/assets/$($asset.name)"
  $null=Gh08 @('api',"repos/$repo/releases/assets/$($remote.id)",'--method','PATCH','-f',"label=MCRF Android 2.0; mcrf-version-code=$($asset.versionCode)")
 }
 $null=Gh08 @('release','upload',$catalogue,"$root/publication/$catalogueName",'--repo',$repo)
 $after=Api08 "releases/tags/$catalogue";$null=VerifyRemote08 $after $catalogueName "$root/publication/$catalogueName"
 foreach($old in $before.assets|Where-Object {$_.name -cnotin $modules.name}){
  $now=@($after.assets|Where-Object name -CEQ $old.name)
  if($now.Count -ne 1 -or $now[0].id -ne $old.id -or $now[0].digest -cne $old.digest){throw 'Unrelated catalogue asset changed'}
 }
 # Recompute the shared inventory, preserving every unrelated asset entry.
 $null=New-Item -ItemType Directory "$root/publication/catalogue"
 $catalogueSums="$root/publication/catalogue/SHA256SUMS.txt"
 $lines=@($after.assets|Where-Object name -CNE 'SHA256SUMS.txt'|Sort-Object name|ForEach-Object {
  if($_.digest -cnotmatch '^sha256:([a-f0-9]{64})$'){throw 'Catalogue SHA256 digest unavailable'}
  $Matches[1]+'  '+$_.name
 })
 [IO.File]::WriteAllText($catalogueSums,($lines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
 $current=Api08 "releases/tags/$catalogue"
 if(($current.assets|ConvertTo-Json -Depth 15 -Compress) -cne ($after.assets|ConvertTo-Json -Depth 15 -Compress)){throw 'Concurrent catalogue mutation before checksum update'}
 $null=Gh08 @('release','upload',$catalogue,$catalogueSums,'--repo',$repo,'--clobber')
 $after=Api08 "releases/tags/$catalogue";$null=VerifyRemote08 $after 'SHA256SUMS.txt' $catalogueSums
 foreach($asset in $modules){$null=VerifyRemote08 $after $asset.name "$root/assets/$($asset.name)"}
 # Source/build/device proofs are checked again immediately before visibility.
 $null=Build08 $root;$null=Acceptance08 $root $build
 $null=Gh08 @('release','edit',$tag,'--repo',$repo,'--draft=false','--prerelease=false','--latest=false')
 $final=Api08 "releases/tags/$tag"
 if($final.draft -or $final.prerelease -or (Api08 'releases/latest').id -ne $latest.id){throw 'Normal release/latest preservation verification failed'}
 foreach($file in $appFiles){$null=VerifyRemote08 $final ([IO.Path]::GetFileName($file)) $file}
 Save08 "$root/publication/catalogue-after.json" $after
 $result.state='PUBLISHED_20';$result.published=$true;$result.url=$final.html_url
}catch{$result.state='FAILED_PRESERVED';$result.error=$_.Exception.Message;throw}
finally{Save08 "$root/publication/receipt.json" $result}
