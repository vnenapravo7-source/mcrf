param([Parameter(Mandatory=$true)][string]$NativeStage,[Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $Output){throw 'Fresh package directory required'}
New-Item -ItemType Directory -Path $Output|Out-Null
$taskProject=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskVersion='calls32-20261023-1'
$taskName='mcrf-csqtt25-windows-amd64-'+$taskVersion+'.zip'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskFiles=@();$taskZip=[IO.Compression.ZipFile]::Open((Join-Path $Output $taskName),[IO.Compression.ZipArchiveMode]::Create)
try{foreach($taskPair in @(@((Join-Path $NativeStage 'csqtt25-client.exe'),'csqtt25-client.exe'),@((Join-Path $NativeStage 'libunwind.dll'),'libunwind.dll'),@((Join-Path $PSScriptRoot '../NOTICES.txt'),'NOTICES.txt'),@((Join-Path $NativeStage '../upstream/LICENSE'),'LICENSE.transport'))){$taskItem=Get-Item -LiteralPath $taskPair[0];[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskZip,$taskItem.FullName,$taskPair[1],[IO.Compression.CompressionLevel]::Optimal)|Out-Null;$taskFiles+=@{Name=$taskPair[1];Size=$taskItem.Length;Sha256=(Get-FileHash -LiteralPath $taskItem.FullName).Hash.ToLowerInvariant()}}}finally{$taskZip.Dispose()}
$taskArchive=Get-Item -LiteralPath (Join-Path $Output $taskName)
$taskPackage=@{Id='csqtt25';Name='CSQTT 2.5 / Rebirth';Version=$taskVersion;Url='https://github.com/vnenapravo7-source/mcrf/releases/download/engines-2026-10-13-1/'+$taskName;Size=$taskArchive.Length;Sha256=(Get-FileHash -LiteralPath $taskArchive.FullName).Hash.ToLowerInvariant();Files=$taskFiles}
$taskCatalog=Get-Content -LiteralPath (Join-Path $taskProject 'native/assets/optional-engines.json') -Raw|ConvertFrom-Json
$taskCatalog.Engines=@($taskCatalog.Engines|ForEach-Object {if($_.Id -eq 'csqtt25'){$taskPackage}else{$_}})
$taskCatalog|ConvertTo-Json -Depth 9|Set-Content -LiteralPath (Join-Path $Output 'engine-catalog.json') -Encoding UTF8
$taskSource=[IO.Compression.ZipFile]::Open((Join-Path $Output 'mcrf-csqtt25-calls32-20261023-1-source.zip'),[IO.Compression.ZipArchiveMode]::Create)
try{foreach($taskPart in @('rust-client','shared')){foreach($taskFile in Get-ChildItem -LiteralPath (Join-Path $NativeStage $taskPart) -Recurse -File){$taskRelative=$taskFile.FullName.Substring([IO.Path]::GetFullPath($NativeStage).TrimEnd('\').Length+1).Replace('\','/');if($taskRelative -match '(^|/)(target|\.git)(/|$)' -or $taskFile.Extension -match '^\.(exe|dll|log|pdb)$'){continue};[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskSource,$taskFile.FullName,'patched/'+$taskRelative,[IO.Compression.CompressionLevel]::Optimal)|Out-Null}};foreach($taskFile in Get-ChildItem -LiteralPath $PSScriptRoot -File){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskSource,$taskFile.FullName,'mcrf/native/turn-engines/csqtt25/'+$taskFile.Name,[IO.Compression.CompressionLevel]::Optimal)|Out-Null};[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskSource,(Join-Path $NativeStage '../upstream/LICENSE'),'LICENSE.transport',[IO.Compression.CompressionLevel]::Optimal)|Out-Null}finally{$taskSource.Dispose()}
Get-ChildItem -LiteralPath $Output -File|Select-Object Name,Length
Get-FileHash -LiteralPath $taskArchive.FullName
