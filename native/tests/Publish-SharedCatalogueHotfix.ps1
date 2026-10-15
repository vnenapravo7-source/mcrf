$ErrorActionPreference='Stop'
$taskRoot='D:/MCRF/Projects/MCRF'
$taskStage='D:/MCRF/Builds/Windows/shared-catalogue-20261015'
$taskRepo='vnenapravo7-source/mcrf'
$taskHub='engines-2026-10-13-1'
$taskLegacy='engines-2026-10-02-1'
$taskOut="$taskStage/publication"
New-Item -ItemType Directory -Path $taskOut -Force|Out-Null
function Task-Gh([string[]]$Arguments){$taskResponse=(& gh @Arguments|Out-String);if($LASTEXITCODE -ne 0){throw "GitHub operation failed: $($Arguments[0])"};return $taskResponse}
function Task-Api([string]$Endpoint,$Payload,[string]$Method='POST'){
 $taskData=$Payload|ConvertTo-Json -Depth 18 -Compress
 $taskResponse=($taskData|& gh api $Endpoint --method $Method --input -|Out-String)
 if($LASTEXITCODE -ne 0){throw 'GitHub API write failed'};return $taskResponse|ConvertFrom-Json
}
function Task-VerifyAssets([string]$Tag,[string]$Directory,[string[]]$Names){
 $taskRelease=Task-Gh @('api',"repos/$taskRepo/releases/tags/$Tag")|ConvertFrom-Json
 foreach($taskName in $Names){$taskAsset=@($taskRelease.assets|Where-Object name -eq $taskName);$taskFile=Join-Path $Directory $taskName
  if($taskAsset.Count -ne 1 -or $taskAsset[0].size -ne (Get-Item -LiteralPath $taskFile).Length -or $taskAsset[0].digest -ne ('sha256:'+(Get-FileHash -LiteralPath $taskFile).Hash.ToLowerInvariant())){throw "Public asset verification failed: $taskName"}
 };return $taskRelease
}
# Archive exact previous public metadata and Windows executable/source before replacement.
$taskBefore=Task-Gh @('api',"repos/$taskRepo/releases/tags/v1.0.11")|ConvertFrom-Json
$taskLegacyBefore=Task-Gh @('api',"repos/$taskRepo/releases/tags/$taskLegacy")|ConvertFrom-Json
$taskBefore|ConvertTo-Json -Depth 20|Set-Content "$taskOut/windows-before.json" -Encoding UTF8
$taskLegacyBefore|ConvertTo-Json -Depth 20|Set-Content "$taskOut/legacy-before.json" -Encoding UTF8
New-Item -ItemType Directory -Path "$taskOut/previous" -Force|Out-Null
Task-Gh @('release','download','v1.0.11','--repo',$taskRepo,'--dir',"$taskOut/previous",'--pattern','mcrf.exe','--pattern','mcrf-1.0.11-source.zip','--pattern','SHA256SUMS.txt')|Out-Null
Task-VerifyAssets 'v1.0.11' "$taskOut/previous" @('mcrf.exe','mcrf-1.0.11-source.zip','SHA256SUMS.txt')|Out-Null
Copy-Item -LiteralPath "$taskStage/mcrf.exe" -Destination "$taskOut/mcrf.exe"
Copy-Item -LiteralPath "$taskOut/previous/mcrf-1.0.11-source.zip" -Destination "$taskOut/mcrf-1.0.11-source.zip"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskZip=[IO.Compression.ZipFile]::Open("$taskOut/mcrf-1.0.11-source.zip",[IO.Compression.ZipArchiveMode]::Update)
try{foreach($taskName in @('native/OptionalEngines.cs','native/assets/optional-engines.json','native/tests/SharedEngineCatalogCheck.cs','native/tests/Build-SharedCatalogueHotfix.ps1','native/tests/Publish-SharedCatalogueHotfix.ps1')){
 $taskEntryName='mcrf-1.0.11/'+$taskName;$taskEntry=$taskZip.GetEntry($taskEntryName);if($taskEntry){$taskEntry.Delete()}
 [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskZip,"$taskRoot/$taskName",$taskEntryName)|Out-Null
}}finally{$taskZip.Dispose()}
$taskSums=@('mcrf.exe','mcrf-1.0.11-source.zip')|ForEach-Object { (Get-FileHash -LiteralPath "$taskOut/$_").Hash.ToLowerInvariant()+'  '+$_ }
$taskSums|Set-Content "$taskOut/SHA256SUMS.txt" -Encoding ascii
# All legacy source/package bytes must already be present in the shared release.
$taskHubBefore=Task-Gh @('api',"repos/$taskRepo/releases/tags/$taskHub")|ConvertFrom-Json
foreach($taskAsset in $taskLegacyBefore.assets|Where-Object name -ne 'engine-catalog.json'){
 $taskMatching=@($taskHubBefore.assets|Where-Object name -eq $taskAsset.name)
 if($taskMatching.Count -ne 1 -or $taskMatching[0].digest -ne $taskAsset.digest -or $taskMatching[0].size -ne $taskAsset.size){throw "Legacy asset not preserved in shared release: $($taskAsset.name)"}
}
New-Item -ItemType Directory -Path "$taskOut/hub" -Force|Out-Null
Task-Gh @('release','download',$taskHub,'--repo',$taskRepo,'--dir',"$taskOut/hub",'--pattern','SHA256SUMS.txt','--pattern','engine-catalog.json')|Out-Null
Copy-Item -LiteralPath "$taskRoot/native/assets/optional-engines.json" -Destination "$taskOut/hub/engine-catalog.json"
$taskLines=Get-Content "$taskOut/hub/SHA256SUMS.txt"
if(@($taskLines|Where-Object {$_ -match '\s+\*?engine-catalog\.json$'}).Count -ne 1){throw 'Unexpected shared checksum format'}
$taskHash=(Get-FileHash "$taskOut/hub/engine-catalog.json").Hash.ToLowerInvariant()
$taskLines|ForEach-Object {if($_ -match '\s+\*?engine-catalog\.json$'){"$taskHash  engine-catalog.json"}else{$_}}|Set-Content "$taskOut/hub/SHA256SUMS.txt" -Encoding ascii
Task-Gh @('release','upload',$taskHub,"$taskOut/hub/engine-catalog.json","$taskOut/hub/SHA256SUMS.txt",'--repo',$taskRepo,'--clobber')|Out-Null
Task-VerifyAssets $taskHub "$taskOut/hub" @('engine-catalog.json','SHA256SUMS.txt')|Out-Null
Task-Gh @('release','upload','v1.0.11',"$taskOut/mcrf.exe","$taskOut/mcrf-1.0.11-source.zip","$taskOut/SHA256SUMS.txt",'--repo',$taskRepo,'--clobber')|Out-Null
$taskPublic=Task-VerifyAssets 'v1.0.11' $taskOut @('mcrf.exe','mcrf-1.0.11-source.zip','SHA256SUMS.txt')
$taskBody="MCRF Windows · v1.0.11`n`nЕдиный интерфейс, маршруты, WARP, Telegram WS, Zapret/ByeTube и автодеплой.`n`nИсправление от 15.10: все дополнительные движки используют один общий релиз Windows и Android. Сохранённые каталоги перенаправляются без повторной установки движков.`n`nЗаменён EXE в этом же релизе: скачайте mcrf.exe вручную — старый клиент не предложит обновление с тем же номером. Профили сохраняются.`n`nИсходники и SHA-256 приложены. Подпись Authenticode отсутствует."
Task-Api "repos/$taskRepo/releases/$($taskPublic.id)" @{body=$taskBody} 'PATCH'|Out-Null
# Delete only the explicitly retired release; retain its tag and verified local metadata.
Task-Gh @('release','delete',$taskLegacy,'--repo',$taskRepo,'--yes')|Out-Null
$taskReleases=Task-Gh @('api',"repos/$taskRepo/releases")|ConvertFrom-Json
if(@($taskReleases|Where-Object tag_name -eq $taskLegacy).Count){throw 'Legacy release still present'}
@{shared_release=$taskHub;windows_release='v1.0.11';windows_sha256=(Get-FileHash "$taskOut/mcrf.exe").Hash.ToLowerInvariant();windows_bytes=(Get-Item "$taskOut/mcrf.exe").Length;legacy_release_removed=$true;legacy_tag_retained=$true;manual_windows_update_required=$true;catalogue_checks=20;startup_checks=30}|ConvertTo-Json|Set-Content "$taskOut/receipt.json" -Encoding UTF8
Write-Output 'PUBLISHED_WINDOWS_HOTFIX_ONE_ENGINE_RELEASE_VERIFIED'
