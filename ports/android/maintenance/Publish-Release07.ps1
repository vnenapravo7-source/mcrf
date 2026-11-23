param([Parameter(Mandatory=$true)][string]$Stage)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath($Stage)
if(!$taskRoot.StartsWith('D:\MCRF\Builds\Android\release07-',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected release root'}
$taskGh='C:/Program Files/GitHub CLI/gh.exe';$taskRepo='vnenapravo7-source/mcrf';$taskTag='android-v0.7';$taskCatalogue='engines-2026-10-13-1'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Gh([string[]]$Arguments){$taskText=(& $taskGh @Arguments 2>&1|Out-String).Trim();if($LASTEXITCODE){throw "GitHub operation failed at $($Arguments[0]): $taskText"};$taskText}
function Api([string]$Path){(Gh @('api',"repos/$taskRepo/$Path"))|ConvertFrom-Json}
function Save([string]$Name,$Value){[IO.File]::WriteAllText("$taskRoot/publication/$Name",($Value|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))}
$taskBuild=Get-Content "$taskRoot/receipt.json" -Raw|ConvertFrom-Json
$taskPlan=Get-Content "$taskRoot/assets-plan.json" -Raw|ConvertFrom-Json
$taskSource=Get-Content "$taskRoot/source-receipt.json" -Raw|ConvertFrom-Json
$taskPhone=Get-Content "$taskRoot/phone/receipt.json" -Raw|ConvertFrom-Json
$taskRuntime=Get-Content 'D:/MCRF/Builds/Android/openflux13-validation-778d06b8108c4b1d81e0122576b46ab0/phone/receipt.json' -Raw|ConvertFrom-Json
if(!$taskBuild.sourceStable -or $taskBuild.state -cne 'BUILT_NOT_INSTALLED_OR_PUBLISHED' -or $taskPlan.state -cne 'APK_ASSETS_VERIFIED_NOT_PUBLISHED'){throw 'Complete verified release required'}
if($taskPhone.state -cne 'UPDATED_10059_OPENFLUX14_DNSTT5_PRESERVED' -or !$taskPhone.userSettingsAndConnectionsPreserved){throw 'Phone update/preservation not accepted'}
if($taskRuntime.state -cne 'PASS_FRESH_OPENFLUX_LOCAL_ANDROID_RUNTIME' -or !$taskRuntime.installedProductionModuleTested -or !$taskRuntime.userDataPreserved){throw 'Actual installed production OpenFlux runtime required'}
if($taskSource.state -cne 'CORRESPONDING_SOURCE_ARCHIVE_CREATED' -or !$taskSource.includesPatchedOpenfluxSource -or (Hash $taskSource.archive) -cne $taskSource.sha256){throw 'Corresponding source archive differs'}
foreach($taskFile in $taskPlan.files){if((Hash "$taskRoot/assets/$($taskFile.name)") -cne $taskFile.sha256){throw 'Prepared APK changed'}}
$taskPublication="$taskRoot/publication";$null=New-Item -ItemType Directory -Path $taskPublication -Force
$taskInitialPath="$taskPublication/catalogue-before.json"
if(Test-Path $taskInitialPath){$taskBefore=Get-Content $taskInitialPath -Raw|ConvertFrom-Json;$taskLatest=Get-Content "$taskPublication/latest-before.json" -Raw|ConvertFrom-Json}
else{
 $taskBefore=Api "releases/tags/$taskCatalogue";$taskLatest=Api 'releases/latest'
 if($taskBefore.draft -or $taskBefore.prerelease){throw 'Existing catalogue not ordinary public release'}
 if(@(Api 'releases?per_page=100'|Where-Object {$_.tag_name -ceq $taskTag}).Count){throw '0.7 release already exists: inspect before mutation'}
 Save 'catalogue-before.json' $taskBefore;Save 'latest-before.json' $taskLatest
}
$taskModules=@($taskPlan.files|Where-Object {$_.kind -ne 'app'})
$taskSelectedNames=@($taskModules.name)+@('mcrf-android-0.7-source.zip','SHA256SUMS.txt')
$null=New-Item -ItemType Directory -Path "$taskPublication/catalogue-backup" -Force
# Back up every replaced Android artifact before any public mutation. Windows
# files/body and all unrelated asset IDs/digests remain untouched.
foreach($taskOld in $taskBefore.assets|Where-Object {$_.name -in $taskSelectedNames}){
 $taskBackup="$taskPublication/catalogue-backup/$($taskOld.name)"
 if(!(Test-Path $taskBackup)){$null=Gh @('release','download',$taskCatalogue,'--repo',$taskRepo,'--pattern',$taskOld.name,'--dir',"$taskPublication/catalogue-backup")}
 if('sha256:'+(Hash $taskBackup) -cne $taskOld.digest){throw 'Catalogue backup hash mismatch'}
}
$taskAppFiles=@($taskPlan.files|Where-Object {$_.kind -eq 'app'}|ForEach-Object {"$taskRoot/assets/$($_.name)"})+@($taskSource.archive)
$taskSums="$taskRoot/assets/SHA256SUMS.txt"
[IO.File]::WriteAllText($taskSums,(($taskAppFiles|ForEach-Object {(Hash $_)+'  '+[IO.Path]::GetFileName($_)}) -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
$taskAppFiles+=@($taskSums)
$taskResult=[ordered]@{state='PREPARING_DRAFT';tag=$taskTag;catalogue=$taskCatalogue;phonePreserved=$true;installedProductionRuntimeAccepted=$true;published=$false;latestBefore=$taskLatest.tag_name}
try{
 $taskReleases=@(Api 'releases?per_page=100');$taskDraft=@($taskReleases|Where-Object {$_.tag_name -ceq $taskTag})
 if(!$taskDraft.Count){
  $taskCreate=@('release','create',$taskTag,'--repo',$taskRepo,'--draft','--latest=false','--title','MCRF Android 0.7','--notes-file',"$taskRoot/release-notes.txt")+$taskAppFiles
  $null=Gh $taskCreate
 }else{if($taskDraft.Count -ne 1 -or !$taskDraft[0].draft){throw 'Existing0.7 is not this pending draft'}}
 $taskRelease=@(Api 'releases?per_page=100'|Where-Object {$_.tag_name -ceq $taskTag})[0];Save 'draft.json' $taskRelease
 foreach($taskPath in $taskAppFiles){
  $taskName=[IO.Path]::GetFileName($taskPath);$taskAsset=@($taskRelease.assets|Where-Object {$_.name -ceq $taskName})
  if(!$taskAsset.Count){$null=Gh @('release','upload',$taskTag,$taskPath,'--repo',$taskRepo);$taskRelease=@(Api 'releases?per_page=100'|Where-Object {$_.tag_name -ceq $taskTag})[0];$taskAsset=@($taskRelease.assets|Where-Object {$_.name -ceq $taskName})}
  if($taskAsset.Count -ne 1 -or $taskAsset[0].digest -cne ('sha256:'+(Hash $taskPath)) -or $taskAsset[0].size -ne (Get-Item $taskPath).Length){throw 'Draft asset mismatch'}
 }
 $taskResult.state='UPDATING_SELECTED_ANDROID_CATALOGUE';Save 'receipt.json' $taskResult
 foreach($taskFile in $taskModules){
  $taskCurrent=Api "releases/tags/$taskCatalogue";$taskExisting=@($taskCurrent.assets|Where-Object {$_.name -ceq $taskFile.name})
  if($taskExisting.Count -eq 1 -and $taskExisting[0].label -match 'mcrf-version-code=([0-9]+)' -and [int]$Matches[1] -gt $taskFile.versionCode){throw 'Refuse catalogue version downgrade'}
  if($taskExisting.Count -eq 0 -or $taskExisting[0].digest -cne ('sha256:'+$taskFile.sha256)){
   $taskOld=@($taskBefore.assets|Where-Object {$_.name -ceq $taskFile.name})
   if($taskExisting.Count -and (!$taskOld.Count -or $taskExisting[0].digest -cne $taskOld[0].digest)){throw 'Concurrent catalogue mutation: stop'}
   $null=Gh @('release','upload',$taskCatalogue,"$taskRoot/assets/$($taskFile.name)",'--repo',$taskRepo,'--clobber')
  }
  $taskCurrent=Api "releases/tags/$taskCatalogue";$taskAsset=@($taskCurrent.assets|Where-Object {$_.name -ceq $taskFile.name})
  if($taskAsset.Count -ne 1 -or $taskAsset[0].digest -cne ('sha256:'+$taskFile.sha256) -or $taskAsset[0].size -ne $taskFile.bytes){throw 'Published module size/digest mismatch'}
  $null=Gh @('api',"repos/$taskRepo/releases/assets/$($taskAsset[0].id)",'--method','PATCH','-f',"label=MCRF Android 0.7; mcrf-version-code=$($taskFile.versionCode)")
  Write-Output "CATALOGUE_OK=$($taskFile.name)"
 }
 $taskCurrent=Api "releases/tags/$taskCatalogue";$taskExisting=@($taskCurrent.assets|Where-Object {$_.name -ceq 'mcrf-android-0.7-source.zip'})
 if(!$taskExisting.Count){$null=Gh @('release','upload',$taskCatalogue,$taskSource.archive,'--repo',$taskRepo)}
 elseif($taskExisting[0].digest -cne ('sha256:'+$taskSource.sha256)){throw 'Existing source archive differs; never replace blindly'}
 $taskAfter=Api "releases/tags/$taskCatalogue"
 foreach($taskOld in $taskBefore.assets|Where-Object {$_.name -notin $taskSelectedNames}){
  $taskNew=@($taskAfter.assets|Where-Object {$_.name -ceq $taskOld.name})
  if($taskNew.Count -ne 1 -or $taskNew[0].id -ne $taskOld.id -or $taskNew[0].digest -cne $taskOld.digest){throw 'Unrelated/Windows catalogue asset changed'}
 }
 $taskCatalogueSums="$taskPublication/SHA256SUMS.txt"
 $taskLines=@($taskAfter.assets|Where-Object {$_.name -cne 'SHA256SUMS.txt'}|Sort-Object name|ForEach-Object {if($_.digest -cnotmatch '^sha256:([a-f0-9]{64})$'){throw 'Catalogue digest unavailable'};$Matches[1]+'  '+$_.name})
 [IO.File]::WriteAllText($taskCatalogueSums,($taskLines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
 $null=Gh @('release','upload',$taskCatalogue,$taskCatalogueSums,'--repo',$taskRepo,'--clobber')
 Save 'catalogue-after.json' (Api "releases/tags/$taskCatalogue")
 $taskResult.state='CATALOGUE_VERIFIED_PUBLISHING_APP';Save 'receipt.json' $taskResult
 $null=Gh @('release','edit',$taskTag,'--repo',$taskRepo,'--draft=false','--prerelease=false','--latest=false')
 $taskFinal=Api "releases/tags/$taskTag"
 if($taskFinal.draft -or $taskFinal.prerelease){throw 'App release not public stable'}
 $taskLatestAfter=Api 'releases/latest'
 if($taskLatestAfter.id -ne $taskLatest.id){throw 'Windows latest pointer changed unexpectedly'}
 Save 'release-after.json' $taskFinal
 $taskResult.state='PUBLISHED_07_CATALOGUE_VERIFIED';$taskResult.published=$true;$taskResult.url=$taskFinal.html_url;$taskResult.moduleApks=$taskModules.Count;$taskResult.moduleKinds=$taskPlan.moduleKinds;$taskResult.windowsLatestPreserved=$true
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{Save 'receipt.json' $taskResult;Write-Output "RELEASE07_PUBLICATION=$($taskResult.state);RECEIPT=$taskPublication/receipt.json"}
