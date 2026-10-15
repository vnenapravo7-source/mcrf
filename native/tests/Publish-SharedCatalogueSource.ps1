$ErrorActionPreference='Stop'
$taskRoot='D:/MCRF/Projects/MCRF'
$taskRepo='vnenapravo7-source/mcrf'
function Task-WriteApi([string]$Endpoint,$Payload){
 $taskText=$Payload|ConvertTo-Json -Depth 14 -Compress
 $taskResult=($taskText|& gh api "repos/$taskRepo/$Endpoint" --method POST --input -|Out-String)
 if($LASTEXITCODE -ne 0){throw 'Git source write failed'};return $taskResult|ConvertFrom-Json
}
$taskRef=(& gh api "repos/$taskRepo/git/ref/heads/main"|Out-String|ConvertFrom-Json)
if($LASTEXITCODE -ne 0){throw 'Cannot inspect branch'}
$taskParent=$taskRef.object.sha
$taskCommit=(& gh api "repos/$taskRepo/git/commits/$taskParent"|Out-String|ConvertFrom-Json)
if($LASTEXITCODE -ne 0){throw 'Cannot inspect tree'}
$taskTree=@()
foreach($taskName in @('native/OptionalEngines.cs','native/assets/optional-engines.json','native/package-engines.ps1','native/tests/SharedEngineCatalogCheck.cs','native/tests/Build-SharedCatalogueHotfix.ps1','native/tests/Publish-SharedCatalogueHotfix.ps1','native/tests/Publish-SharedCatalogueSource.ps1','docs/BUILD.md')){
 $taskBlob=Task-WriteApi 'git/blobs' @{content=[Convert]::ToBase64String([IO.File]::ReadAllBytes("$taskRoot/$taskName"));encoding='base64'}
 $taskTree+=@{path=$taskName;mode='100644';type='blob';sha=$taskBlob.sha}
}
$taskNewTree=Task-WriteApi 'git/trees' @{base_tree=$taskCommit.tree.sha;tree=$taskTree}
$taskNewCommit=Task-WriteApi 'git/commits' @{message='fix(windows): migrate cached engine URLs to the shared platform catalogue';tree=$taskNewTree.sha;parents=@($taskParent)}
$taskCurrent=(& gh api "repos/$taskRepo/git/ref/heads/main" --jq '.object.sha'|Out-String).Trim()
if($LASTEXITCODE -ne 0 -or $taskCurrent -ne $taskParent){throw 'Branch advanced; refusing overwrite'}
@{sha=$taskNewCommit.sha;force=$false}|ConvertTo-Json -Compress|& gh api "repos/$taskRepo/git/refs/heads/main" --method PATCH --input - --silent
if($LASTEXITCODE -ne 0){throw 'Safe source publication failed'}
foreach($taskEntry in $taskTree){$taskActual=(& gh api "repos/$taskRepo/contents/$($taskEntry.path)?ref=main" --jq '.sha'|Out-String).Trim();if($LASTEXITCODE -ne 0 -or $taskActual -ne $taskEntry.sha){throw 'Source verification failed'}}
@{commit=$taskNewCommit.sha;parent=$taskParent;files=$taskTree}|ConvertTo-Json -Depth 8|Set-Content 'D:/MCRF/Builds/Windows/shared-catalogue-20261015/source-publication.json' -Encoding UTF8
Write-Output "SOURCE_PUBLISHED_VERIFIED $($taskNewCommit.sha)"
