param([string]$Output='D:/MCRF/Builds/Windows/csqtt-generations-20261022/engines-final/mcrf-csqtt-generations-windows-source-20261022.zip')
$ErrorActionPreference='Stop';$outputPath=[IO.Path]::GetFullPath($Output);if(!$outputPath.StartsWith('D:\MCRF\Builds\Windows\',[StringComparison]::OrdinalIgnoreCase)-or(Test-Path -LiteralPath $outputPath)){throw 'New Windows build source archive required'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$sources=@(
 @('D:/MCRF/Work/Windows/csqtt-generations-20261022/stage-2.0/rust-client','legacy-2.0/rust-client'),
 @('D:/MCRF/Work/Windows/csqtt-generations-20261022/stage-2.1.5/rust-client','legacy-2.1.5/rust-client'),
 @('D:/MCRF/Work/Windows/csqtt25-20261021/build-a33065fb5d974089b5de46036fed6899/rust-client','2.5/rust-client'),
 @('D:/MCRF/Work/Windows/csqtt25-20261021/build-a33065fb5d974089b5de46036fed6899/shared','2.5/shared'),
 @('D:/MCRF/Work/Windows/vkturn-workers-20261022/legacy-final/native/turn-engines/csqtt-bridge','native/turn-engines/csqtt-bridge'),
 @('D:/MCRF/Archive/Windows-original/work/turn-engines/focsq/luminescq-focsq-bfa176f/backend','work/turn-engines/focsq/luminescq-focsq-bfa176f/backend'),
 @((Join-Path $project 'native/turn-engines/csqtt-generations'),'native/turn-engines/csqtt-generations'),
 @((Join-Path $project 'native/turn-engines/csqtt25'),'native/turn-engines/csqtt25'))
$files=@(@((Join-Path $project 'native/turn-engines/NOTICES.txt'),'NOTICES.txt'),@('D:/MCRF/Archive/Windows-original/work/turn-engines/LICENSE.csqtt.txt','LICENSE.csqtt'),@((Join-Path $PSScriptRoot 'SOURCE.md'),'README.md'))
$archive=[IO.Compression.ZipFile]::Open($outputPath,[IO.Compression.ZipArchiveMode]::Create);$seen=@{}
try{foreach($pair in $sources){$base=[IO.Path]::GetFullPath($pair[0]);foreach($file in Get-ChildItem -LiteralPath $base -File -Recurse){$relative=$file.FullName.Substring($base.Length).TrimStart('\').Replace('\','/');if($relative -match '(^|/)(target|\.git)(/|$)' -or $relative -match '\.(exe|dll|pdb|log)$'){continue};$entry=$pair[1]+'/'+$relative;if($seen.ContainsKey($entry)){throw 'Duplicate source archive entry'};$seen[$entry]=$true;[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$file.FullName,$entry,[IO.Compression.CompressionLevel]::Optimal)|Out-Null}}
 foreach($pair in $files){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$pair[0],$pair[1],[IO.Compression.CompressionLevel]::Optimal)|Out-Null}
}finally{$archive.Dispose()}
Get-FileHash -LiteralPath $outputPath
