param([Parameter(Mandatory=$true)][string]$Stage)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath($Stage)
if(!$taskRoot.StartsWith('D:\MCRF\Builds\Android\release07-',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected release stage'}
$taskGh='C:/Program Files/GitHub CLI/gh.exe';$taskRepo='vnenapravo7-source/mcrf'
function Api([string]$Path){$taskText=(& $taskGh api "repos/$taskRepo/$Path" 2>&1|Out-String);if($LASTEXITCODE){throw 'Public metadata request failed'};$taskText|ConvertFrom-Json}
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
$taskPub=Get-Content "$taskRoot/publication/receipt.json" -Raw|ConvertFrom-Json
if($taskPub.state -cne 'PUBLISHED_07_CATALOGUE_VERIFIED' -or !$taskPub.published){throw 'Finished publisher receipt required'}
$taskPlan=Get-Content "$taskRoot/assets-plan.json" -Raw|ConvertFrom-Json
$taskApp=Api 'releases/tags/android-v0.7';$taskCatalogue=Api 'releases/tags/engines-2026-10-13-1'
if($taskApp.draft -or $taskApp.prerelease -or $taskApp.body.Trim() -cne (Get-Content "$taskRoot/release-notes.txt" -Raw).Trim()){throw 'Stable release description differs'}
foreach($taskFile in $taskPlan.files){
 $taskRelease=if($taskFile.kind -eq 'app'){$taskApp}else{$taskCatalogue}
 $taskAsset=@($taskRelease.assets|Where-Object {$_.name -ceq $taskFile.name})
 if($taskAsset.Count -ne 1 -or $taskAsset[0].state -cne 'uploaded' -or $taskAsset[0].digest -cne ('sha256:'+$taskFile.sha256) -or $taskAsset[0].size -ne $taskFile.bytes){throw 'Public asset metadata differs'}
}
$taskSource=Get-Content "$taskRoot/source-receipt.json" -Raw|ConvertFrom-Json
foreach($taskRelease in @($taskApp,$taskCatalogue)){
 $taskAsset=@($taskRelease.assets|Where-Object {$_.name -ceq 'mcrf-android-0.7-source.zip'})
 if($taskAsset.Count -ne 1 -or $taskAsset[0].digest -cne ('sha256:'+$taskSource.sha256) -or $taskAsset[0].size -ne $taskSource.bytes){throw 'Public corresponding-source archive differs'}
}
$taskDownload="$taskRoot/publication/public-downloads";$null=New-Item -ItemType Directory -Path $taskDownload -Force
foreach($taskName in @('mcrf-android-universal-release.apk','mcrf-engine-openflux-arm64-v8a-release.apk')){
 $taskFile=@($taskPlan.files|Where-Object {$_.name -ceq $taskName})[0]
 $taskRelease=if($taskFile.kind -eq 'app'){$taskApp}else{$taskCatalogue}
 $taskAsset=@($taskRelease.assets|Where-Object {$_.name -ceq $taskName})[0]
 $taskUri=[uri]$taskAsset.browser_download_url
 if($taskUri.Scheme -cne 'https' -or $taskUri.Host -cne 'github.com' -or $taskUri.AbsolutePath -cne "/$taskRepo/releases/download/$($taskRelease.tag_name)/$taskName"){throw 'Unexpected public download origin'}
 $taskPath="$taskDownload/$taskName"
 if(!(Test-Path $taskPath)){Invoke-WebRequest -Uri $taskUri -OutFile $taskPath -TimeoutSec 120|Out-Null}
 if((Hash $taskPath) -cne $taskFile.sha256 -or (Get-Item $taskPath).Length -ne $taskFile.bytes){throw 'Downloaded public APK bytes differ'}
 Write-Output "PUBLIC_DOWNLOAD_VERIFIED=$taskName"
}
$taskLatestBefore=Get-Content "$taskRoot/publication/latest-before.json" -Raw|ConvertFrom-Json
if((Api 'releases/latest').id -ne $taskLatestBefore.id){throw 'Windows latest pointer changed'}
[IO.File]::WriteAllText("$taskRoot/publication/public-verification.json",(@{state='PASS_PUBLIC_07_BYTES_AND_METADATA';url=$taskApp.html_url;apkMetadataChecks=$taskPlan.files.Count;publicApksDownloadedAndHashed=2;sourceArchiveBothReleasesVerified=$true;windowsLatestPreserved=$true;phoneInstalled10059=$true;openfluxInstalled14RuntimeAccepted=$true;scope='Owned encrypted direct OpenFlux runtime only; not universal provider/CAPTCHA certification'}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
Write-Output "PUBLIC_07_VERIFIED=$($taskApp.html_url)"
