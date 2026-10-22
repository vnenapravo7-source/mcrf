param(
    [Parameter(Mandatory=$true)][string]$NativeExecutable,
    [string]$LegacyWorkRoot='D:/MCRF/Archive/Windows-original/work',
    [string]$OutputRoot='D:/MCRF/Builds/Windows/csqtt25-20261021/engines',
    [string]$LegacyExecutable='',
    [string]$PackageVersion='csqtt25-2.5.0-mcrf1'
)
$ErrorActionPreference='Stop'
if($args.Count){throw 'Unknown CSQTT package arguments'}
$taskProject=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskOutput=[IO.Path]::GetFullPath($OutputRoot)
if(!$taskOutput.StartsWith('D:\MCRF\Builds\Windows\',[StringComparison]::OrdinalIgnoreCase)){throw 'Output must remain in Windows builds'}
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression,System.IO.Compression.FileSystem
$taskPath=Join-Path $taskOutput 'mcrf-csqtt25-windows-amd64.zip'
if(Test-Path -LiteralPath $taskPath){throw 'Existing package must be verified or replaced in a new build version, not overwritten'}
$taskPairs=@(
    @($NativeExecutable,'csqtt25-client.exe'),
    @($(if($LegacyExecutable){$LegacyExecutable}else{Join-Path $LegacyWorkRoot 'turn-engines/csqtt-client.exe'}),'csqtt-client.exe'),
    @((Join-Path $LegacyWorkRoot 'turn-engines/wdtt-client.exe'),'wdtt-client.exe'),
    @((Join-Path $LegacyWorkRoot 'turn-engines/libunwind.dll'),'libunwind.dll'),
    @((Join-Path $taskProject 'native/turn-engines/NOTICES.txt'),'NOTICES.txt'),
    @((Join-Path $LegacyWorkRoot 'turn-engines/LICENSE.csqtt.txt'),'LICENSE.csqtt'),
    @((Join-Path (Split-Path -Parent (Split-Path -Parent $NativeExecutable)) 'upstream/LICENSE'),'LICENSE.csqtt25'),
    @((Join-Path $LegacyWorkRoot 'turn-engines/WDTT-Plus/Ivan4537-WDTT-Plus-68bc4af/LICENSE'),'LICENSE.wdtt'),
    @((Join-Path $LegacyWorkRoot 'toolchains/llvm-mingw/llvm-mingw-20260922-ucrt-x86_64/LICENSE.TXT'),'LICENSE.llvm.txt')
)
$taskArchive=[IO.Compression.ZipFile]::Open($taskPath,[IO.Compression.ZipArchiveMode]::Create)
$taskFiles=@()
try{foreach($pair in $taskPairs){$item=Get-Item -LiteralPath $pair[0];[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskArchive,$item.FullName,$pair[1],[IO.Compression.CompressionLevel]::Optimal)|Out-Null;$taskFiles+=@{Name=$pair[1];Size=$item.Length;Sha256=(Get-FileHash -LiteralPath $item.FullName).Hash.ToLowerInvariant()}}}finally{$taskArchive.Dispose()}
$taskPackage=@{Id='csqtt';Name='CSQTT 2.1 / 2.5';Version=$PackageVersion;Url='https://github.com/vnenapravo7-source/mcrf/releases/download/engines-2026-10-13-1/mcrf-csqtt25-windows-amd64.zip';Size=(Get-Item -LiteralPath $taskPath).Length;Sha256=(Get-FileHash -LiteralPath $taskPath).Hash.ToLowerInvariant();Files=$taskFiles}
# Generated candidate metadata; promoting it into source is a separate explicit
# edit after inspecting the package. No GitHub writes or app-state installation.
$taskPackage | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $taskOutput 'csqtt25-package.json') -Encoding UTF8
Get-Item -LiteralPath $taskPath | Select-Object FullName,Length
Get-FileHash -LiteralPath $taskPath
