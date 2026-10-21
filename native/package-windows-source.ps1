param([Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $Output){throw 'Source snapshot already exists; do not overwrite'}
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Add-Type -AssemblyName System.IO.Compression,System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::Open($Output,[IO.Compression.ZipArchiveMode]::Create)
try{foreach($part in @('native','wpf')){foreach($file in Get-ChildItem -LiteralPath (Join-Path $project $part) -Recurse -File){
  $relative=$file.FullName.Substring($project.Length+1).Replace('\','/')
  if($relative -match '/(bin|obj|target|outputs|\.git)/' -or $file.Extension -match '^\.(exe|dll|pdb|log)$'){continue}
  [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file.FullName,$relative,[IO.Compression.CompressionLevel]::Optimal)|Out-Null
}}}finally{$zip.Dispose()}
Get-FileHash -LiteralPath $Output | Format-List
