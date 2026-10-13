param([string]$ReleaseTag = 'engines-2026-10-13-1')
$ErrorActionPreference='Stop'
if ($ReleaseTag -notmatch '^engines-\d{4}-\d{2}-\d{2}-\d+$') { throw 'Invalid engine release tag.' }
Add-Type -AssemblyName System.IO.Compression,System.IO.Compression.FileSystem
$project=Split-Path -Parent $PSScriptRoot
$output=Join-Path $project 'outputs/engine-packages'
$source=Join-Path $project 'work/qwdtt-source'
$commit='a296c57eaba69bb9479a24f9157856490890e47d'
if ((& git -C $source rev-parse HEAD).Trim() -ne $commit) { throw 'Unexpected QWDTT revision.' }
New-Item -ItemType Directory -Path $output -Force | Out-Null
$files=@(
  @('work/qwdtt-client.exe','qwdtt-client.exe'),
  @('work/qwdtt-source/LICENSE','LICENSE.qwdtt'),
  @('native/assets/Qwdtt-NOTICES.txt','NOTICES.txt')
)
$asset='mcrf-qwdtt-windows-amd64.zip'
$archive=Join-Path $output $asset
$temp=$archive+'.'+[Guid]::NewGuid().ToString('N')+'.new'
$zip=[IO.Compression.ZipFile]::Open($temp,[IO.Compression.ZipArchiveMode]::Create)
$manifest=@()
try {
  foreach ($file in $files) {
    $full=Join-Path $project $file[0]
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$full,$file[1],[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    $manifest+=@{Name=$file[1];Size=(Get-Item -LiteralPath $full).Length;Sha256=(Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()}
  }
} finally { $zip.Dispose() }
if (Test-Path -LiteralPath $archive) { [IO.File]::Replace($temp,$archive,$archive+'.previous') } else { [IO.File]::Move($temp,$archive) }
$catalog=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'assets/optional-engines.json') -Raw | ConvertFrom-Json
$q=@{Id='qwdtt';Name='QWDTT';Version=$ReleaseTag;Url="https://github.com/vnenapravo7-source/mcrf/releases/download/$ReleaseTag/$asset";Size=(Get-Item -LiteralPath $archive).Length;Sha256=(Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant();Files=$manifest}
$catalog.Engines=@($catalog.Engines | Where-Object {$_.Id -ne 'qwdtt'})+@($q)
[IO.File]::WriteAllText((Join-Path $output 'engine-catalog.json'),($catalog | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
# Publish corresponding pristine source plus the Windows patch and build script.
# Never archive a working tree, .git, user profiles or credentials.
$sourceArchive=Join-Path $output 'QWDTT-source.zip'
$sourceTemp=$sourceArchive+'.'+[Guid]::NewGuid().ToString('N')+'.new'
& git -C $source archive --format=zip --output=$sourceTemp $commit
if ($LASTEXITCODE -ne 0) { throw 'QWDTT source archive failed.' }
$zip=[IO.Compression.ZipFile]::Open($sourceTemp,[IO.Compression.ZipArchiveMode]::Update)
try {
  foreach ($name in @('native/qwdtt-windows.patch','native/build-qwdtt.ps1','native/assets/Qwdtt-NOTICES.txt')) {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,(Join-Path $project $name),$name,[IO.Compression.CompressionLevel]::Optimal) | Out-Null
  }
} finally { $zip.Dispose() }
if (Test-Path -LiteralPath $sourceArchive) { [IO.File]::Replace($sourceTemp,$sourceArchive,$sourceArchive+'.previous') } else { [IO.File]::Move($sourceTemp,$sourceArchive) }
$q | ConvertTo-Json -Depth 8
Get-FileHash -LiteralPath $sourceArchive -Algorithm SHA256
