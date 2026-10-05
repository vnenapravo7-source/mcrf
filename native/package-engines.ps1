param([string]$ReleaseTag = 'engines-2026-10-02-1')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression,System.IO.Compression.FileSystem
$project = Split-Path -Parent $PSScriptRoot
$output = Join-Path $project 'outputs/engine-packages'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$sets = @(
  @{Id='openflux';Name='OpenFlux';Files=@(@('work/openflux/openflux-windows-amd64.exe','openflux.exe'),@('work/openflux/LICENSE','LICENSE.openflux'))},
  @{Id='wdtt';Name='WDTT';Files=@(@('work/turn-engines/wdtt-client.exe','wdtt-client.exe'),@('native/turn-engines/NOTICES.txt','NOTICES.txt'),@('work/turn-engines/WDTT-Plus/Ivan4537-WDTT-Plus-68bc4af/LICENSE','LICENSE.wdtt'))},
  @{Id='csqtt';Name='CSQTT';Files=@(@('work/turn-engines/csqtt-client.exe','csqtt-client.exe'),@('work/turn-engines/wdtt-client.exe','wdtt-client.exe'),@('work/turn-engines/libunwind.dll','libunwind.dll'),@('native/turn-engines/NOTICES.txt','NOTICES.txt'),@('work/turn-engines/LICENSE.csqtt.txt','LICENSE.csqtt'),@('work/turn-engines/WDTT-Plus/Ivan4537-WDTT-Plus-68bc4af/LICENSE','LICENSE.wdtt'))}
)
$engines = @()
$llvmLicense=(Get-ChildItem (Join-Path $project 'work/toolchains/llvm-mingw') -Directory | Select-Object -First 1).FullName
foreach ($set in $sets) {
  $asset = "mcrf-$($set.Id)-windows-amd64.zip"
  $path = Join-Path $output $asset
  $temp=$path+'.'+[Guid]::NewGuid().ToString('N')+'.new'
  $zip = [IO.Compression.ZipFile]::Open($temp,[IO.Compression.ZipArchiveMode]::Create)
  try {
    $files = @()
    foreach ($pair in $set.Files) {
      $full = Join-Path $project $pair[0]
      [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$full,$pair[1],[IO.Compression.CompressionLevel]::Optimal) | Out-Null
      $files += @{Name=$pair[1];Size=(Get-Item -LiteralPath $full).Length;Sha256=(Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
    if($set.Id -eq 'csqtt') {
      $full=Join-Path $llvmLicense 'LICENSE.TXT'
      [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$full,'LICENSE.llvm.txt',[IO.Compression.CompressionLevel]::Optimal) | Out-Null
      $files += @{Name='LICENSE.llvm.txt';Size=(Get-Item -LiteralPath $full).Length;Sha256=(Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
  } finally { $zip.Dispose() }
  if(Test-Path -LiteralPath $path){[IO.File]::Replace($temp,$path,$path+'.previous')}else{[IO.File]::Move($temp,$path)}
  $engines += @{Id=$set.Id;Name=$set.Name;Version=$ReleaseTag;Url="https://github.com/vnenapravo7-source/mcrf/releases/download/$ReleaseTag/$asset";Size=(Get-Item -LiteralPath $path).Length;Sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant();Files=$files}
}
# This is a pinned, build-time catalog, not an executable manifest fetched from the internet.
$catalog = @{Version=1;Engines=$engines} | ConvertTo-Json -Depth 8
[IO.File]::WriteAllText((Join-Path $output 'engine-catalog.json'),$catalog,[Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $project 'work/turn-engines/turn-engines-source.zip') -Destination (Join-Path $output 'turn-engines-source.zip') -Force
Get-ChildItem -LiteralPath $output | Select-Object Name,Length
