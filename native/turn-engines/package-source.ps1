$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression,System.IO.Compression.FileSystem
$project=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$target=Join-Path $project 'work/turn-engines/turn-engines-source.zip'
$wdtt='work/turn-engines/WDTT-Plus/Ivan4537-WDTT-Plus-68bc4af'
$csqtt='work/turn-engines/focsq/luminescq-focsq-bfa176f'
$folders=@('native/turn-engines',"$wdtt/go_client","$wdtt/pathprobe","$csqtt/backend",'work/toolchains/cargo/registry/src','work/toolchains/go-cache/github.com','work/toolchains/go-cache/golang.org','work/toolchains/go-cache/golang.zx2c4.com','work/toolchains/go-cache/gvisor.dev')
$files=@('native/TurnEngines.cs','native/TurnProfile.cs',"$wdtt/LICENSE","$wdtt/README.md","$csqtt/README.md",'work/turn-engines/LICENSE.csqtt.txt')
$stream=[IO.File]::Create($target)
$archive=[IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create)
try{
 foreach($relative in $folders){
  $full=Join-Path $project $relative
  foreach($item in Get-ChildItem -LiteralPath $full -Recurse -File){
   if($item.FullName -match '\\(target|\.git)\\'){continue}
   $entry=$item.FullName.Substring($project.Length+1).Replace('\','/')
   [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$item.FullName,$entry,[IO.Compression.CompressionLevel]::Optimal)|Out-Null
  }
 }
 foreach($relative in $files){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,(Join-Path $project $relative),$relative,[IO.Compression.CompressionLevel]::Optimal)|Out-Null}
 $llvm=(Get-ChildItem (Join-Path $project 'work/toolchains/llvm-mingw') -Directory).FullName
 [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,(Join-Path $llvm 'LICENSE.TXT'),'LICENSE.llvm.txt',[IO.Compression.CompressionLevel]::Optimal)|Out-Null
}finally{$archive.Dispose();$stream.Dispose()}
Get-Item -LiteralPath $target | Select-Object FullName,Length
