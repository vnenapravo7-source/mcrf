param([string]$ModuleCache='D:/MCRF/Projects/MCRF/work/toolchains/go-home/pkg/mod')
$ErrorActionPreference='Stop'
$taskLock=(Get-Content -LiteralPath "$PSScriptRoot/../transport-lock.json" -Raw|ConvertFrom-Json).dnstt
$taskDownload=Join-Path $ModuleCache "cache/download/$($taskLock.module)/@v/$($taskLock.version)"
if((Get-Content -LiteralPath "$taskDownload.ziphash" -Raw).Trim() -cne $taskLock.sum -or (Get-FileHash -LiteralPath "$taskDownload.zip").Hash.ToLowerInvariant() -cne $taskLock.zipSha256){throw 'Pinned DNSTT module archive required'}
# Extract only checksum-pinned archive bytes into a new owned tree. Never edit
# the accepted native stage or the Go module cache. No compilation/execution.
$taskStage="D:/MCRF/Builds/Android/p3-dnstt-source-$([guid]::NewGuid().ToString('N'))"
$taskTarget="$taskStage/module";New-Item -ItemType Directory -Path $taskTarget|Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskZip=[IO.Compression.ZipFile]::OpenRead("$taskDownload.zip")
$taskSeen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
try{
 foreach($taskEntry in $taskZip.Entries){
  if($taskEntry.FullName.EndsWith('/')){continue}
  $taskPrefix="$($taskLock.module)@$($taskLock.version)/"
  if(!$taskEntry.FullName.StartsWith($taskPrefix,[StringComparison]::Ordinal)){throw 'Unexpected DNSTT archive root'}
  $taskRelative=$taskEntry.FullName.Substring($taskPrefix.Length)
  if([IO.Path]::IsPathRooted($taskRelative) -or $taskRelative.Contains(':') -or '..' -in ($taskRelative -split '[/\\]') -or !$taskSeen.Add($taskRelative)){throw 'Unsafe or duplicate DNSTT source entry'}
  if(($taskEntry.ExternalAttributes -shr 16 -band 0xf000) -eq 0xa000){throw 'Linked DNSTT source entry'}
  $taskTo=Join-Path $taskTarget $taskRelative
  New-Item -ItemType Directory -Path (Split-Path $taskTo) -Force|Out-Null
  $taskInput=$taskEntry.Open();$taskOutput=[IO.File]::Open($taskTo,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write)
  try{$taskInput.CopyTo($taskOutput)}finally{$taskInput.Dispose();$taskOutput.Dispose()}
 }
}finally{$taskZip.Dispose()}
& "$PSScriptRoot/Apply-Adapter.ps1" -Module $taskTarget
foreach($taskName in @('mcrf_config_test.go','mcrf_lifecycle_test.go')){Copy-Item -LiteralPath "$PSScriptRoot/$taskName" -Destination "$taskTarget/dnstt-client/$taskName"}
$taskRows=@(Get-ChildItem -LiteralPath $taskTarget -File -Recurse|ForEach-Object {@{path=[IO.Path]::GetRelativePath($taskTarget,$_.FullName).Replace([char]92,[char]47);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}}|Sort-Object path)
[IO.File]::WriteAllText("$taskStage/SOURCE_MANIFEST.json",($taskRows|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$taskReceipt=@{state='SOURCE_PREPARED_NOT_COMPILED';module=$taskTarget;commit=$taskLock.commit;upstream=$taskLock;manifestSha256=(Get-FileHash "$taskStage/SOURCE_MANIFEST.json").Hash.ToLowerInvariant();compiled=$false;executed=$false;androidIntegrated=$false}
[IO.File]::WriteAllText("$taskStage/receipt.json",($taskReceipt|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
Write-Output "P3_DNSTT_SOURCE=$taskStage"
