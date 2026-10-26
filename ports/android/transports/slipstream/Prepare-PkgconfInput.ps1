$ErrorActionPreference='Stop'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))}
$taskFile='mingw-w64-x86_64-pkgconf-1~3.0.7-1-any.pkg.tar.zst'
$taskSha='37b97f372409fa7cf54d709bab892f34e9c62d2658230a09f783a89a55ae590e'
$taskStage="D:/MCRF/Builds/Android/p3-slipstream-pkgconf-inputs-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path "$taskStage/tools"|Out-Null
$archive="$taskStage/$taskFile"
& curl.exe -4 --fail --location --connect-timeout 10 --max-time 60 --silent --show-error --output $archive "https://mirror.yandex.ru/mirrors/msys2/mingw/mingw64/$taskFile"
if($LASTEXITCODE -or (Hash $archive) -cne $taskSha){throw 'Official pinned pkgconf download failed/changed'}
$names=@(& tar -tf $archive);if($LASTEXITCODE -or !$names.Count){throw 'Pkgconf archive unreadable'}
foreach($name in $names){if([IO.Path]::IsPathRooted($name) -or $name.Contains(':') -or '..' -in ($name -split '[/\\]') -or (!$name.StartsWith('mingw64/',[StringComparison]::Ordinal) -and $name -cnotin @('.PKGINFO','.BUILDINFO','.MTREE','.INSTALL'))){throw 'Unsafe pkgconf archive path'}}
$details=@(& tar -tvf $archive);if($LASTEXITCODE -or @($details|Where-Object {$_ -cmatch '^[lh]'}).Count){throw 'Linked pkgconf archive entry'}
& tar -xf $archive -C "$taskStage/tools";if($LASTEXITCODE){throw 'Pkgconf extraction failed'}
if(@(Get-ChildItem "$taskStage/tools" -Recurse -Force|Where-Object {$_.LinkType}).Count){throw 'Linked pkgconf input'}
$rows=@(Get-ChildItem "$taskStage/tools" -Recurse -File -Force|ForEach-Object {@{path=[IO.Path]::GetRelativePath("$taskStage/tools",$_.FullName).Replace([char]92,[char]47);bytes=$_.Length;sha256=Hash $_.FullName}}|Sort-Object path)
Save $rows "$taskStage/SOURCE_MANIFEST.json"
Save @{state='PKGCONF_INPUT_PREPARED_NOT_EXECUTED';package=$taskFile;archiveSha256=$taskSha;manifestSha256=Hash "$taskStage/SOURCE_MANIFEST.json";provenance='https://packages.msys2.org/packages/mingw-w64-x86_64-pkgconf';installScriptsExecuted=$false;systemModified=$false} "$taskStage/receipt.json"
Write-Output "P3_SLIPSTREAM_PKGCONF_INPUTS=$taskStage"
