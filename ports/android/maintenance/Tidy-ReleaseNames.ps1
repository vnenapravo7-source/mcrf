$ErrorActionPreference='Stop'
$taskRepo='vnenapravo7-source/mcrf'
$taskRoot='D:/MCRF/Projects/MCRF'
$taskBefore=@(& gh api "repos/$taskRepo/releases" | ConvertFrom-Json)
if($LASTEXITCODE -ne 0){throw 'Cannot read releases'}
$taskNames=@{
 'v1.0.11'='MCRF Windows · v1.0.11'
 'v0.3.10'='MCRF Windows · v0.3.10'
 'android-v0.1.0-preview.20261015.1'='MCRF Android · v0.1.0 preview'
 'engines-2026-10-13-1'='MCRF · Движки Windows и Android'
 'engines-2026-10-02-1'='MCRF · Старые движки Windows (совместимость)'
}
foreach($taskRelease in $taskBefore){
 if($taskNames.ContainsKey($taskRelease.tag_name) -and $taskRelease.name -ne $taskNames[$taskRelease.tag_name]){
  & gh release edit $taskRelease.tag_name --repo $taskRepo --title $taskNames[$taskRelease.tag_name]
  if($LASTEXITCODE -ne 0){throw 'Release title update failed'}
 }
}
$taskReadme=(& gh api "repos/$taskRepo/contents/README.md?ref=main" | ConvertFrom-Json)
if($LASTEXITCODE -ne 0){throw 'Cannot read public README'}
$taskText=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($taskReadme.content))
$taskOld='<summary>Android — плавный тур по интерфейсу</summary>'
if($taskText.Contains($taskOld)){
 $taskText=$taskText.Replace($taskOld,'<summary>Android</summary>')
 $taskPayload=@{message='docs: simplify Android interface heading';sha=$taskReadme.sha;branch='main';content=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($taskText))}|ConvertTo-Json -Compress
 $taskPayload | & gh api "repos/$taskRepo/contents/README.md" --method PUT --input - --silent
 if($LASTEXITCODE -ne 0){throw 'README update failed'}
}
$taskAfter=@(& gh api "repos/$taskRepo/releases" | ConvertFrom-Json)
if($LASTEXITCODE -ne 0){throw 'Cannot verify releases'}
foreach($taskPrior in $taskBefore){
 $taskCurrent=@($taskAfter | Where-Object id -eq $taskPrior.id)
 if($taskCurrent.Count -ne 1){throw 'Release missing'}
 foreach($taskField in @('tag_name','draft','prerelease','target_commitish')){
  if($taskCurrent[0].$taskField -ne $taskPrior.$taskField){throw "Unexpected change: $taskField"}
 }
 if(($taskPrior.assets|ConvertTo-Json -Depth 12 -Compress) -ne ($taskCurrent[0].assets|ConvertTo-Json -Depth 12 -Compress)){throw 'Assets changed'}
}
$taskPublished=(& gh api "repos/$taskRepo/contents/README.md?ref=main" | ConvertFrom-Json)
$taskPublishedText=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($taskPublished.content))
if(!$taskPublishedText.Contains('<summary>Android</summary>') -or $taskPublishedText.Contains($taskOld)){throw 'README heading verification failed'}
$taskReceipt=@{releases=$taskAfter|Select-Object id,tag_name,name;release_assets_and_tags_unchanged=$true;readme_sha=$taskPublished.sha;verified_at=[DateTime]::UtcNow.ToString('o')}
$taskReceipt|ConvertTo-Json -Depth 8|Set-Content -LiteralPath "$taskRoot/ports/android/coordination/release-list-tidy-2026-10-15.json" -Encoding UTF8
$taskAfter|Select-Object tag_name,name|Format-Table -AutoSize
Write-Output 'VERIFIED: titles and README updated; all release assets, tags and release flags unchanged'
