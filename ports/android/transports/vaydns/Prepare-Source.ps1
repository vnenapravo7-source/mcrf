param([string]$ModuleCache='D:/MCRF/Projects/MCRF/work/toolchains/go-home/pkg/mod')
$ErrorActionPreference='Stop'
$taskRoot='D:/MCRF/Projects/MCRF'
$taskLock=(Get-Content -LiteralPath "$taskRoot/ports/android/transports/transport-lock.json" -Raw|ConvertFrom-Json).vaydns
$taskModule=Join-Path $ModuleCache "$($taskLock.module)@$($taskLock.version)"
$taskDownload=Join-Path $ModuleCache "cache/download/$($taskLock.module)/@v/$($taskLock.version)"
if(!(Test-Path -LiteralPath "$taskDownload.ziphash") -or (Get-Content -LiteralPath "$taskDownload.ziphash" -Raw).Trim() -cne $taskLock.sum){throw 'Pinned VayDNS module input required'}
if((Get-FileHash -LiteralPath "$taskDownload.zip" -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskLock.zipSha256){throw 'Pinned module ZIP changed'}
# Preparation does not compile, execute upstream code, or change the Go cache.
# Revalidate the extracted cache against its module ZIP before copying it.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskZip=[IO.Compression.ZipFile]::OpenRead("$taskDownload.zip")
$taskRows=@()
try {
 foreach($taskEntry in $taskZip.Entries){
  if($taskEntry.FullName.EndsWith('/')){continue}
  $taskPrefix="$($taskLock.module)@$($taskLock.version)/"
  if(!$taskEntry.FullName.StartsWith($taskPrefix,[StringComparison]::Ordinal)){throw 'Unexpected module archive root'}
  $taskRelative=$taskEntry.FullName.Substring($taskPrefix.Length)
  if([IO.Path]::IsPathRooted($taskRelative) -or '..' -in ($taskRelative -split '[/\\]')){throw 'Unsafe module entry'}
  $taskOriginal=Join-Path $taskModule $taskRelative
  if(!(Test-Path -LiteralPath $taskOriginal) -or (Get-Item -LiteralPath $taskOriginal).LinkType){throw 'Extracted module input missing or linked'}
  $taskStream=$taskEntry.Open();$taskSha=[Security.Cryptography.SHA256]::Create()
  try {$taskHash=[Convert]::ToHexString($taskSha.ComputeHash($taskStream)).ToLowerInvariant()}finally{$taskSha.Dispose();$taskStream.Dispose()}
  if((Get-FileHash -LiteralPath $taskOriginal -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskHash){throw 'Extracted module differs from pinned archive'}
  $taskRows+=@{path=$taskRelative;sha256=$taskHash}
 }
} finally {$taskZip.Dispose()}
$taskStage="D:/MCRF/Builds/Android/p3-vaydns-source-$([guid]::NewGuid().ToString('N'))"
$taskTarget="$taskStage/module"
New-Item -ItemType Directory -Path $taskTarget|Out-Null
foreach($taskRow in $taskRows){
 $taskTo=Join-Path $taskTarget $taskRow.path
 New-Item -ItemType Directory -Path (Split-Path $taskTo) -Force|Out-Null
 Copy-Item -LiteralPath (Join-Path $taskModule $taskRow.path) -Destination $taskTo
}
$taskOverlay=@()
New-Item -ItemType Directory -Path "$taskTarget/cmd/mcrf-vaydns"|Out-Null
foreach($taskName in @('main.go','main_test.go')){
 $taskFrom=Join-Path $PSScriptRoot $taskName
 Copy-Item -LiteralPath $taskFrom -Destination "$taskTarget/cmd/mcrf-vaydns/$taskName"
 $taskOverlay+=@{path="cmd/mcrf-vaydns/$taskName";sha256=(Get-FileHash -LiteralPath $taskFrom).Hash.ToLowerInvariant()}
}
foreach($taskName in @('mcrf_serve.go','mcrf_serve_test.go','mcrf_socks.go','mcrf_socks_test.go')){
 $taskFrom=Join-Path $PSScriptRoot "overlay/$taskName"
 Copy-Item -LiteralPath $taskFrom -Destination "$taskTarget/client/$taskName"
 $taskOverlay+=@{path="client/$taskName";sha256=(Get-FileHash -LiteralPath $taskFrom).Hash.ToLowerInvariant()}
}
New-Item -ItemType Directory -Path "$taskTarget/mcrf-integration"|Out-Null
Copy-Item -LiteralPath "$PSScriptRoot/integration/owned_peer_test.go" -Destination "$taskTarget/mcrf-integration/owned_peer_test.go"
$taskOverlay+=@{path='mcrf-integration/owned_peer_test.go';sha256=(Get-FileHash -LiteralPath "$PSScriptRoot/integration/owned_peer_test.go").Hash.ToLowerInvariant()}
$taskManifest=@{upstream=$taskRows;overlay=$taskOverlay}
[IO.File]::WriteAllText("$taskStage/SOURCE_MANIFEST.json",(@($taskRows)+@($taskOverlay)|Sort-Object path|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$taskReceipt=@{state='SOURCE_PREPARED_NOT_COMPILED';module=$taskTarget;upstream=$taskLock;manifest=$taskManifest;compiled=$false;executed=$false;androidIntegrated=$false}
$taskReceipt.manifestSha256=(Get-FileHash -LiteralPath "$taskStage/SOURCE_MANIFEST.json").Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$taskStage/receipt.json",($taskReceipt|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
Write-Output "P3_VAYDNS_SOURCE=$taskStage"
