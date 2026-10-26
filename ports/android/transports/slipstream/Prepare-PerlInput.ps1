$ErrorActionPreference='Stop'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))}
$taskName='Locale-Maketext-Simple-0.21'
$taskUrl='https://cpan.metacpan.org/authors/id/J/JE/JESSE/Locale-Maketext-Simple-0.21.tar.gz'
$taskSha='b009ff51f4fb108d19961a523e99b4373ccf958d37ca35bf1583215908dca9a9'
$taskStage="D:/MCRF/Builds/Android/p3-slipstream-perl-inputs-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path "$taskStage/source"|Out-Null
$taskArchive="$taskStage/$taskName.tar.gz"
& curl.exe -4 --fail --location --connect-timeout 10 --max-time 60 --silent --show-error --output $taskArchive $taskUrl
if($LASTEXITCODE -or (Hash $taskArchive) -cne $taskSha){throw 'Official CPAN input unavailable or checksum differs'}
$taskNames=@(& tar -tf $taskArchive);if($LASTEXITCODE -or !$taskNames.Count){throw 'CPAN input archive unreadable'}
foreach($name in $taskNames){if([IO.Path]::IsPathRooted($name) -or $name.Contains(':') -or '..' -in ($name -split '[/\\]') -or !$name.StartsWith("$taskName/",[StringComparison]::Ordinal)){throw 'Unsafe CPAN archive path'}}
$taskDetails=@(& tar -tvf $taskArchive);if($LASTEXITCODE -or @($taskDetails|Where-Object {$_ -cmatch '^[lh]'}).Count){throw 'Linked CPAN input refused'}
& tar -xf $taskArchive -C "$taskStage/source";if($LASTEXITCODE){throw 'CPAN source extraction failed'}
if(@(Get-ChildItem "$taskStage/source" -Recurse -Force|Where-Object {$_.LinkType}).Count){throw 'Linked CPAN source refused'}
$taskRows=@(Get-ChildItem "$taskStage/source" -Recurse -File|ForEach-Object {@{path=[IO.Path]::GetRelativePath("$taskStage/source",$_.FullName).Replace([char]92,[char]47);sha256=Hash $_.FullName;bytes=$_.Length}}|Sort-Object path)
Save $taskRows "$taskStage/SOURCE_MANIFEST.json"
Save @{state='PERL_SOURCE_PREPARED_NOT_EXECUTED';distribution=$taskName;url=$taskUrl;metadata='https://fastapi.metacpan.org/v1/release/Locale-Maketext-Simple';archiveSha256=$taskSha;manifestSha256=Hash "$taskStage/SOURCE_MANIFEST.json";lib="$taskStage/source/$taskName/lib";installScriptsExecuted=$false;systemModified=$false} "$taskStage/receipt.json"
Write-Output "P3_SLIPSTREAM_PERL_INPUTS=$taskStage"
