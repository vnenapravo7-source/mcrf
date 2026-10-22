param([string]$Csqtt25Executable,[string]$OutputRoot='D:/MCRF/Builds/Windows/csqtt-generations-20261022/engines')
$ErrorActionPreference='Stop';$root=[IO.Path]::GetFullPath($OutputRoot);if(!$root.StartsWith('D:\MCRF\Builds\Windows\',[StringComparison]::OrdinalIgnoreCase)){throw 'Windows build output required'}
New-Item -ItemType Directory -Path $root -Force|Out-Null;Add-Type -AssemblyName System.IO.Compression.FileSystem
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'));$tools='D:/MCRF/Archive/Windows-original/work/toolchains';$unwind=Join-Path $tools 'llvm-mingw/llvm-mingw-20260922-ucrt-x86_64/bin/libunwind.dll'
$common=@(@($unwind,'libunwind.dll'),@((Join-Path $project 'native/turn-engines/NOTICES.txt'),'NOTICES.txt'),@((Join-Path $tools 'llvm-mingw/llvm-mingw-20260922-ucrt-x86_64/LICENSE.TXT'),'LICENSE.llvm.txt'))
$versions=@(
 @('csqtt20','CSQTT 2.0','D:/MCRF/Work/Windows/csqtt-generations-20261022/stage-2.0/csqtt20-client.exe','2ff8a1a4fbe031fb76b799e172dce25cfe1ca942'),
 @('csqtt215','CSQTT 2.1.5','D:/MCRF/Work/Windows/csqtt-generations-20261022/stage-2.1.5/csqtt215-client.exe','de7afc23cec7481f98845bf24fc8943af19b4cf8'),
 @('csqtt','CSQTT 2.1.9 (WIRE-3)','D:/MCRF/Work/Windows/vkturn-workers-20261022/csqtt-client.exe','446293aa2e873ac5323ef6fd2316d9b81d966c11'),
 @('csqtt25','CSQTT 2.5 / Rebirth',$Csqtt25Executable,'d02da9bd9d8926969b766e197c434d830a83dbd8'))
$packages=@();foreach($v in $versions){$name='mcrf-'+$v[0]+'-windows-amd64-20261022.zip';$zip=Join-Path $root $name;if(Test-Path -LiteralPath $zip){throw 'Existing package retained; do not overwrite accepted artifacts'};$files=@();$archive=[IO.Compression.ZipFile]::Open($zip,[IO.Compression.ZipArchiveMode]::Create);try{
 $license=if($v[0] -eq 'csqtt25'){Join-Path (Split-Path (Split-Path $Csqtt25Executable)) 'upstream/LICENSE'}else{'D:/MCRF/Archive/Windows-original/work/turn-engines/LICENSE.csqtt.txt'}
 $entries=@(,@($v[2],[IO.Path]::GetFileName($v[2])))+$common+@(,@($license,'LICENSE.csqtt'))
 foreach($pair in $entries){$item=Get-Item -LiteralPath $pair[0];[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$item.FullName,$pair[1],[IO.Compression.CompressionLevel]::Optimal)|Out-Null;$files+=@{Name=$pair[1];Size=$item.Length;Sha256=(Get-FileHash -LiteralPath $item.FullName).Hash.ToLowerInvariant()}}
 }finally{$archive.Dispose()}
 $packages+=@{Id=$v[0];Name=$v[1];Version=('csqtt-generations-'+$v[3].Substring(0,8)+'-20261022');Url=('https://github.com/vnenapravo7-source/mcrf/releases/download/engines-2026-10-13-1/'+$name);Size=(Get-Item -LiteralPath $zip).Length;Sha256=(Get-FileHash -LiteralPath $zip).Hash.ToLowerInvariant();Files=$files}
}
$catalog=Get-Content -LiteralPath (Join-Path $project 'native/assets/optional-engines.json') -Raw|ConvertFrom-Json
$catalog.Engines=@($catalog.Engines|Where-Object {$_.Id -notin @('csqtt','csqtt20','csqtt215','csqtt25')})+$packages
$catalog|ConvertTo-Json -Depth 9|Set-Content -LiteralPath (Join-Path $root 'optional-engines.candidate.json') -Encoding UTF8
$packages|ForEach-Object {'{0}: {1} bytes, {2}' -f $_.Id,$_.Size,$_.Sha256}
