param([Parameter(Mandatory=$true)][string]$Upstream,[Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $Output){throw 'Source archive already exists'}
if((& git -C $Upstream rev-parse HEAD) -ne '1789526ecfb9ff5397c94f9f54c1a3dc2fb60440' -or (& git -C $Upstream status --porcelain)){throw 'Expected pristine pinned source'}
Add-Type -AssemblyName System.IO.Compression,System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::Open($Output,[IO.Compression.ZipArchiveMode]::Create)
function Add-One([string]$File,[string]$Name){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$File,$Name,[IO.Compression.CompressionLevel]::Optimal)|Out-Null}
try{
  foreach($dir in @('dll','include')){foreach($file in Get-ChildItem -LiteralPath (Join-Path $Upstream $dir) -Recurse -File){Add-One $file.FullName ('upstream/'+$file.FullName.Substring($Upstream.Length+1).Replace('\','/'))}}
  foreach($name in @('LICENSE','VERSION','mingw-build.sh')){Add-One (Join-Path $Upstream $name) ('upstream/'+$name)}
  foreach($file in Get-ChildItem -LiteralPath $PSScriptRoot -File){Add-One $file.FullName ('mcrf/'+$file.Name)}
}finally{$zip.Dispose()}
Get-FileHash -LiteralPath $Output | Format-List
