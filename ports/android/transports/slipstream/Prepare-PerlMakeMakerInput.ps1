$ErrorActionPreference='Stop'
# Reuse the reviewed safe CPAN source-extraction recipe, with a separately
# pinned pure-Perl distribution. No CPAN install/build scripts are executed.
$taskOriginal="$PSScriptRoot/Prepare-PerlInput.ps1"
$taskText=[IO.File]::ReadAllText($taskOriginal)
$taskReplacements=@(
 @('https://cpan.metacpan.org/authors/id/J/JE/JESSE/Locale-Maketext-Simple-0.21.tar.gz','https://cpan.metacpan.org/authors/id/B/BI/BINGOS/ExtUtils-MakeMaker-7.78.tar.gz'),
 @('Locale-Maketext-Simple-0.21','ExtUtils-MakeMaker-7.78'),
 @('b009ff51f4fb108d19961a523e99b4373ccf958d37ca35bf1583215908dca9a9','43b33c20f8d82dba7cc48f8cd702f8fc9811e9d07880886dfd31b7077bd4a3a6'),
 @('https://fastapi.metacpan.org/v1/release/Locale-Maketext-Simple','https://fastapi.metacpan.org/v1/release/ExtUtils-MakeMaker'))
foreach($pair in $taskReplacements){if([regex]::Matches($taskText,[regex]::Escape($pair[0])).Count -ne 1){throw 'Safe CPAN acquisition recipe changed'};$taskText=$taskText.Replace($pair[0],$pair[1])}
$taskBundle="D:/MCRF/Builds/Android/p3-perl-makemaker-producer-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $taskBundle|Out-Null
[IO.File]::WriteAllText("$taskBundle/Prepare.ps1",$taskText,[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText("$taskBundle/derivation.json",(@{base=$taskOriginal;baseSha256=(Get-FileHash $taskOriginal).Hash.ToLowerInvariant();derivedSha256=(Get-FileHash "$taskBundle/Prepare.ps1").Hash.ToLowerInvariant();changes='Exact official distribution URL/name/checksum/metadata only'}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
& "$taskBundle/Prepare.ps1"
