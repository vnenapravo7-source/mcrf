param(
    [string]$Upstream='D:/MCRF/Work/Windows/csqtt25-20261021/upstream',
    [string]$LegacyWorkRoot='D:/MCRF/Archive/Windows-original/work',
    [string]$Output='D:/MCRF/Builds/Windows/csqtt25-20261021/engines-final/mcrf-csqtt25-windows-source.zip'
)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $Output){throw 'Existing source archive will not be overwritten'}
if((& git -C $Upstream rev-parse HEAD) -ne 'd02da9bd9d8926969b766e197c434d830a83dbd8' -or ((& git -C $Upstream status --porcelain | Out-String).Trim())){throw 'Pristine upstream revision mismatch'}
Add-Type -AssemblyName System.IO.Compression,System.IO.Compression.FileSystem
$taskProject=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskZip=[IO.Compression.ZipFile]::Open($Output,[IO.Compression.ZipArchiveMode]::Create)
function Add-SourceFile([string]$File,[string]$Entry){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskZip,$File,$Entry,[IO.Compression.CompressionLevel]::Optimal)|Out-Null}
function Add-SourceTree([string]$Directory,[string]$Prefix){foreach($item in Get-ChildItem -LiteralPath $Directory -Recurse -File){if($item.FullName -match '\\(target|\.git|build)\\'){continue};Add-SourceFile $item.FullName ($Prefix+'/'+$item.FullName.Substring($Directory.Length+1).Replace('\','/'))}}
try{
  foreach($name in @('rust-client','shared')){Add-SourceTree (Join-Path $Upstream $name) ('upstream/'+$name)}
  foreach($name in @('LICENSE','domains.txt')){Add-SourceFile (Join-Path $Upstream $name) ('upstream/'+$name)}
  Add-SourceTree $PSScriptRoot 'mcrf/native/turn-engines/csqtt25'
  foreach($name in @('Csqtt25Link.cs','TurnProfile.cs','TurnEngines.cs','OptionalEngines.cs')){Add-SourceFile (Join-Path $taskProject ('native/'+$name)) ('mcrf/native/'+$name)}
  Add-SourceFile (Join-Path $taskProject 'native/turn-engines/NOTICES.txt') 'NOTICES.txt'
  Add-SourceTree (Join-Path $LegacyWorkRoot 'turn-engines/focsq/luminescq-focsq-bfa176f/backend') 'legacy/csqtt21/backend'
  $taskWdtt=Join-Path $LegacyWorkRoot 'turn-engines/WDTT-Plus/Ivan4537-WDTT-Plus-68bc4af'
  foreach($name in @('go_client','pathprobe')){Add-SourceTree (Join-Path $taskWdtt $name) ('legacy/WDTT-Plus/'+$name)}
  foreach($name in @('LICENSE','README.md')){Add-SourceFile (Join-Path $taskWdtt $name) ('legacy/WDTT-Plus/'+$name)}
  Add-SourceFile (Join-Path $LegacyWorkRoot 'turn-engines/LICENSE.csqtt.txt') 'legacy/LICENSE.csqtt'
}finally{$taskZip.Dispose()}
Get-Item -LiteralPath $Output | Select-Object FullName,Length
Get-FileHash -LiteralPath $Output | ForEach-Object {$_.Hash.ToLowerInvariant()}
