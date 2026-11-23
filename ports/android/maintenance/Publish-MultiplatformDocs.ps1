param()
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
if($taskRoot -ne 'D:\MCRF\Projects\MCRF'){throw 'Unexpected root'}
$taskRepo='vnenapravo7-source/mcrf'
$taskCatalogue=(& gh release view engines-2026-10-13-1 --repo $taskRepo --json isDraft,assets|Out-String|ConvertFrom-Json)
if($LASTEXITCODE -ne 0 -or $taskCatalogue.isDraft -or $taskCatalogue.assets.Count -lt 40){throw 'Verified enduring catalogue must be public before README'}
$taskStage=Join-Path $taskRoot 'outputs/multiplatform-docs-publication'
New-Item -ItemType Directory -Path $taskStage -Force|Out-Null
$taskUtf8=[Text.UTF8Encoding]::new($false)
function Invoke-TaskApi([string]$endpoint,$payload){
    $taskPayload=Join-Path $taskStage ('payload-'+[guid]::NewGuid().ToString('N')+'.json')
    [IO.File]::WriteAllText($taskPayload,($payload|ConvertTo-Json -Depth 12),$taskUtf8)
    $taskResult=(& gh api $endpoint --method POST --input $taskPayload|Out-String)
    if($LASTEXITCODE -ne 0){throw 'GitHub documentation operation failed'}
    return $taskResult|ConvertFrom-Json
}
$taskRef=(& gh api "repos/$taskRepo/git/ref/heads/main"|Out-String|ConvertFrom-Json)
if($LASTEXITCODE -ne 0){throw 'Cannot inspect branch'}
$taskParent=$taskRef.object.sha
$taskCommit=(& gh api "repos/$taskRepo/git/commits/$taskParent"|Out-String|ConvertFrom-Json)
if($LASTEXITCODE -ne 0){throw 'Cannot inspect base tree'}
$taskTree=@()
foreach($taskName in @('README.md','docs/WINDOWS-GUIDE.md','docs/ANDROID-GUIDE.md','docs/screenshots/android-autodeploy.png','docs/screenshots/android-tour.gif')){
    $taskFile=Join-Path $taskRoot $taskName
    if(!(Test-Path -LiteralPath $taskFile -PathType Leaf)){throw 'Missing explicit documentation input'}
    $taskBlob=Invoke-TaskApi "repos/$taskRepo/git/blobs" @{content=[Convert]::ToBase64String([IO.File]::ReadAllBytes($taskFile));encoding='base64'}
    $taskTree+=@{path=$taskName;mode='100644';type='blob';sha=$taskBlob.sha}
}
$taskNewTree=Invoke-TaskApi "repos/$taskRepo/git/trees" @{base_tree=$taskCommit.tree.sha;tree=$taskTree}
$taskNewCommit=Invoke-TaskApi "repos/$taskRepo/git/commits" @{message='docs: Android interface tour and extended platform overview';tree=$taskNewTree.sha;parents=@($taskParent)}
$taskNow=(& gh api "repos/$taskRepo/git/ref/heads/main" --jq '.object.sha'|Out-String).Trim()
if($LASTEXITCODE -ne 0 -or $taskNow -ne $taskParent){throw 'Branch advanced; do not overwrite another change'}
$taskUpdate=Join-Path $taskStage 'update-ref.json'
[IO.File]::WriteAllText($taskUpdate,(@{sha=$taskNewCommit.sha;force=$false}|ConvertTo-Json),$taskUtf8)
& gh api "repos/$taskRepo/git/refs/heads/main" --method PATCH --input $taskUpdate --silent
if($LASTEXITCODE -ne 0){throw 'Non-fast-forward-safe documentation publication failed'}
foreach($taskEntry in $taskTree){
    $taskRemote=(& gh api "repos/$taskRepo/contents/$($taskEntry.path)?ref=main" --jq '.sha'|Out-String).Trim()
    if($LASTEXITCODE -ne 0 -or $taskRemote -ne $taskEntry.sha){throw 'Published documentation verification failed'}
}
$taskReceipt=@{commit=$taskNewCommit.sha;parent=$taskParent;files=$taskTree;code_changed=$false;old_downloads_deleted=$false}
[IO.File]::WriteAllText((Join-Path $taskStage 'receipt.json'),($taskReceipt|ConvertTo-Json -Depth 8),$taskUtf8)
Write-Output "DOCS_PUBLISHED=$($taskNewCommit.sha) FILES=$($taskTree.Count)"
