param([Parameter(Mandatory=$true)][string]$BuildRoot,[Parameter(Mandatory=$true)][string]$QwdttSource,[Parameter(Mandatory=$true)][string]$Go,[string]$PackageDirectory='packages-final')
$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression,System.IO.Compression.FileSystem
$out=Join-Path $BuildRoot $PackageDirectory;New-Item -ItemType Directory -Path $out -Force | Out-Null
$notices=New-Object Text.StringBuilder
[void]$notices.AppendLine('MCRF optional IP adapter / QWDTT. Source and license notices for statically linked Go dependencies.')
$moduleDirs=@()
foreach($dir in @((Join-Path $PSScriptRoot 'csqtt-netstack'),(Join-Path $QwdttSource 'go_client'))){Push-Location $dir;try{$moduleDirs+=& $Go list -deps -f '{{with .Module}}{{.Dir}}{{end}}' .;if($LASTEXITCODE -ne 0){throw 'Dependency license inventory failed'}}finally{Pop-Location}}
foreach($dir in ($moduleDirs|Where-Object {$_}|Sort-Object -Unique)){foreach($file in (Get-ChildItem -LiteralPath $dir -File|Where-Object {$_.Name -match '^(LICENSE|COPYING|NOTICE)(\.|$)' -and $_.Length -lt 524288})){[void]$notices.AppendLine("`n--- $([IO.Path]::GetFileName($dir)) / $($file.Name) ---`n");[void]$notices.AppendLine([IO.File]::ReadAllText($file.FullName))}}
$noticeFile=Join-Path $out 'THIRD-PARTY-NOTICES.txt';[IO.File]::WriteAllText($noticeFile,$notices.ToString(),[Text.UTF8Encoding]::new($false))
$catalog=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'assets/optional-engines.json') -Raw | ConvertFrom-Json
foreach($id in @('csqtt-bridge','qwdtt')){
 $exe=if($id -eq 'qwdtt'){'qwdtt-client.exe'}else{'csqtt-netstack.exe'}
 $license=if($id -eq 'qwdtt'){Join-Path $QwdttSource 'LICENSE'}else{Join-Path $PSScriptRoot 'csqtt-netstack/LICENSE'}
 $files=@(@((Join-Path $BuildRoot $exe),$exe),@($license,'LICENSE'),@($noticeFile,'THIRD-PARTY-NOTICES.txt'))
 $archive=Join-Path $out ('mcrf-'+$id+'-windows-amd64-20261022.zip')
 if(Test-Path -LiteralPath $archive){throw 'Package exists: do not overwrite accepted bytes'}
 $zip=[IO.Compression.ZipFile]::Open($archive,[IO.Compression.ZipArchiveMode]::Create);$manifest=@()
 try{foreach($f in $files){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$f[0],$f[1],[IO.Compression.CompressionLevel]::Optimal)|Out-Null;$manifest+=@{Name=$f[1];Size=(Get-Item -LiteralPath $f[0]).Length;Sha256=(Get-FileHash -LiteralPath $f[0]).Hash.ToLowerInvariant()}}}finally{$zip.Dispose()}
 $item=@{Id=$id;Name=$(if($id -eq 'qwdtt'){'QWDTT · RAW / WireGuard'}else{'Локальный IP-адаптер · CSQTT / QWDTT RAW'});Version='vkturn-20261022-1';Url=('https://github.com/vnenapravo7-source/mcrf/releases/download/engines-2026-10-13-1/'+[IO.Path]::GetFileName($archive));Size=(Get-Item -LiteralPath $archive).Length;Sha256=(Get-FileHash -LiteralPath $archive).Hash.ToLowerInvariant();Files=$manifest}
 $catalog.Engines=@($catalog.Engines|Where-Object {$_.Id -ne $id})+@($item)
}
[IO.File]::WriteAllText((Join-Path $out 'engine-catalog.json'),($catalog|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
$source=Join-Path $out 'mcrf-vkturn-components-source-20261022.zip'
if(Test-Path -LiteralPath $source){throw 'Source archive exists'}
$zip=[IO.Compression.ZipFile]::Open($source,[IO.Compression.ZipArchiveMode]::Create)
try{
 [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$noticeFile,'THIRD-PARTY-NOTICES.txt',[IO.Compression.CompressionLevel]::Optimal)|Out-Null
 foreach($f in (Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'csqtt-netstack') -File)){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$f.FullName,('csqtt-netstack/'+$f.Name),[IO.Compression.CompressionLevel]::Optimal)|Out-Null}
 foreach($f in (Get-ChildItem -LiteralPath (Join-Path $QwdttSource 'go_client') -Recurse -File)){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$f.FullName,('qwdtt/go_client/'+$f.FullName.Substring((Join-Path $QwdttSource 'go_client').Length+1).Replace('\','/')),[IO.Compression.CompressionLevel]::Optimal)|Out-Null}
 [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,(Join-Path $QwdttSource 'LICENSE'),'qwdtt/LICENSE',[IO.Compression.CompressionLevel]::Optimal)|Out-Null
 foreach($name in @('qwdtt-windows.patch','qwdtt-raw-windows.patch','build-qwdtt.ps1','package-vkturn-fixes.ps1')){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,(Join-Path $PSScriptRoot $name),('native/'+$name),[IO.Compression.CompressionLevel]::Optimal)|Out-Null}
}finally{$zip.Dispose()}
Get-ChildItem -LiteralPath $out -Filter '*.zip' | Select-Object Name,Length
