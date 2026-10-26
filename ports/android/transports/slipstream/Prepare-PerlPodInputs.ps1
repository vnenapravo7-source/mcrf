$ErrorActionPreference='Stop'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
# Primary official MetaCPAN release API checksum_sha256 and download_url.
$taskPackages=@(
 @{name='Pod-Usage-2.05';author='M/MA/MAREKR';sha256='100c27908757c56ebfeca8b7bf15a9867e449df663ff013de3855d183dfbea30'},
 @{name='podlators-v6.1.1';author='R/RR/RRA';sha256='709a7dc4a2259683eb2f89e085ec70cf743710d950510e489ec6ddca3eb80c68'},
 @{name='Pod-Simple-3.48';author='K/KH/KHW';sha256='3297cf3c078de9d8297942423ec6ab59e85e30dfb38b782242699e386727c63a'},
 @{name='Pod-Escapes-1.07';author='N/NE/NEILB';sha256='dbf7c827984951fb248907f940fd8f19f2696bc5545c0a15287e0fbe56a52308'},
 @{name='Pod-Perldoc-3.28';author='M/MA/MALLEN';sha256='cc41e605b8e13c40a8ee6504ff46347b5ba7fbd92203b3bb055422051befc64d'})
$taskStage="D:/MCRF/Builds/Android/p3-slipstream-perl-pod-inputs-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path "$taskStage/source","$taskStage/downloads"|Out-Null
foreach($package in $taskPackages){
 $archive="$taskStage/downloads/$($package.name).tar.gz"
 $package.url="https://cpan.metacpan.org/authors/id/$($package.author)/$($package.name).tar.gz"
 & curl.exe -4 --fail --location --connect-timeout 10 --max-time 60 --silent --show-error --output $archive $package.url
 if($LASTEXITCODE -or (Hash $archive) -cne $package.sha256){throw 'Official pinned POD source unavailable/changed'}
 $names=@(& tar -tf $archive);if($LASTEXITCODE -or !$names.Count){throw 'POD archive unreadable'}
 foreach($name in $names){if([IO.Path]::IsPathRooted($name) -or $name.Contains(':') -or '..' -in ($name -split '[/\\]') -or !$name.StartsWith($package.name+'/',[StringComparison]::Ordinal)){throw 'Unsafe POD archive path'}}
 $details=@(& tar -tvf $archive);if($LASTEXITCODE -or @($details|Where-Object {$_ -cmatch '^[lh]'}).Count){throw 'Linked POD archive entry'}
 & tar -xf $archive -C "$taskStage/source";if($LASTEXITCODE){throw 'POD extraction failed'}
 if(!(Test-Path "$taskStage/source/$($package.name)/lib" -PathType Container)){throw 'Expected pure-Perl library missing'}
}
if(@(Get-ChildItem "$taskStage/source" -Recurse -Force|Where-Object {$_.LinkType}).Count){throw 'Linked POD source refused'}
$rows=@(Get-ChildItem "$taskStage/source" -Recurse -File|ForEach-Object {@{path=[IO.Path]::GetRelativePath("$taskStage/source",$_.FullName).Replace([char]92,[char]47);bytes=$_.Length;sha256=Hash $_.FullName}}|Sort-Object path)
Save $rows "$taskStage/SOURCE_MANIFEST.json"
Save @{state='PERL_POD_SOURCE_PREPARED_NOT_EXECUTED';packages=$taskPackages;manifestSha256=Hash "$taskStage/SOURCE_MANIFEST.json";installScriptsExecuted=$false;systemModified=$false} "$taskStage/receipt.json"
Write-Output "P3_SLIPSTREAM_PERL_POD_INPUTS=$taskStage"
