param([Parameter(Mandatory)][string]$PicoquicUpstream)
$ErrorActionPreference='Stop'
$taskLock=Get-Content -LiteralPath "$PSScriptRoot/dependencies.lock.json" -Raw|ConvertFrom-Json
$taskPico=(Resolve-Path -LiteralPath $PicoquicUpstream).Path
if($taskPico.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-slipstream-rust-picoquic-upstream-[a-f0-9]{32}$' -or (& git -C $taskPico rev-parse HEAD).Trim() -cne $taskLock.picoquic.commit -or (& git -C $taskPico status --porcelain --untracked-files=all)){throw 'Pinned clean owned picoquic source required'}
$taskStage="D:/MCRF/Builds/Android/p3-slipstream-inputs-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path "$taskStage/downloads","$taskStage/tools"|Out-Null
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Fetch([string]$Url,[string]$Path,[string]$Expected){
 & curl.exe -4 --fail --location --connect-timeout 10 --max-time 90 --retry 1 --silent --show-error --output $Path $Url
 if($LASTEXITCODE){throw 'Bounded Slipstream input download failed; preserve partial evidence'}
 if((Hash $Path) -cne $Expected){throw 'Pinned Slipstream build input checksum mismatch'}
}
function Extract([string]$Archive,[string]$Destination,[string]$Prefix){
 $taskNames=@(& tar -tf $Archive);if($LASTEXITCODE -or !$taskNames.Count){throw 'Build input archive unavailable'}
 foreach($taskName in $taskNames){
  if([IO.Path]::IsPathRooted($taskName) -or $taskName.Contains(':') -or '..' -in ($taskName -split '[/\\]')){throw 'Unsafe build input archive path'}
  if(!$taskName.StartsWith($Prefix,[StringComparison]::Ordinal) -and $taskName -cnotin @('.PKGINFO','.BUILDINFO','.MTREE','.INSTALL')){throw 'Unexpected build input archive root'}
 }
 $taskDetails=@(& tar -tvf $Archive);if($LASTEXITCODE -or @($taskDetails|Where-Object {$_ -cmatch '^[lh]'}).Count){throw 'Linked build input archive entry'}
 New-Item -ItemType Directory -Path $Destination -Force|Out-Null
 & tar -xf $Archive -C $Destination;if($LASTEXITCODE){throw 'Build input extraction failed'}
 if(@(Get-ChildItem -LiteralPath $Destination -Recurse -Force|Where-Object {$_.LinkType}).Count){throw 'Linked extracted build input'}
}
# Downloads/extraction only. Package install scripts are NEVER invoked and no
# PATH, system installation, compiler, upstream configure or build is executed.
foreach($taskPackage in $taskLock.windowsMakePackages){
 $taskArchive="$taskStage/downloads/$($taskPackage.file)"
 # Official mirror advertised by MSYS2 mirrorbits. Primary repo transfer
 # stalls on this network; package bytes remain pinned by SHA256.
 Fetch "https://mirror.yandex.ru/mirrors/msys2/mingw/mingw64/$($taskPackage.file)" $taskArchive $taskPackage.sha256
 Extract $taskArchive "$taskStage/tools" 'mingw64/'
}
$taskOpenSSL="$taskStage/downloads/openssl-src.crate"
Fetch $taskLock.opensslSource.url $taskOpenSSL $taskLock.opensslSource.sha256
Extract $taskOpenSSL "$taskStage/openssl-crate" "$($taskLock.opensslSource.crate)-$($taskLock.opensslSource.version)/"
$taskPicotls="$taskStage/picotls"
& git clone --filter=blob:none --no-checkout "https://github.com/$($taskLock.picotls.repository).git" $taskPicotls;if($LASTEXITCODE){throw 'Pinned picotls clone failed'}
& git -C $taskPicotls checkout --detach $taskLock.picotls.commit;if($LASTEXITCODE){throw 'Pinned picotls commit unavailable'}
& git -C $taskPicotls submodule update --init --recursive;if($LASTEXITCODE){throw 'Pinned picotls Git-link dependency unavailable'}
if(@(& git -C $taskPicotls submodule status --recursive|Where-Object {$_ -match '^[+\-U]'}).Count){throw 'Picotls Git-link identity mismatch'}
New-Item -ItemType Directory -Path "$taskStage/picoquic"|Out-Null
foreach($taskRelative in (& git -C $taskPico ls-files)){
 $taskFrom=Join-Path $taskPico $taskRelative
 if(!(Test-Path -LiteralPath $taskFrom -PathType Leaf)){continue}
 if((Get-Item -LiteralPath $taskFrom).LinkType -or (& git -C $taskPico hash-object -- $taskRelative).Trim() -cne (& git -C $taskPico rev-parse "HEAD:$taskRelative").Trim()){throw 'Pinned picoquic source differs from Git object'}
 $taskTo=Join-Path "$taskStage/picoquic" $taskRelative;New-Item -ItemType Directory -Path (Split-Path $taskTo) -Force|Out-Null;Copy-Item -LiteralPath $taskFrom -Destination $taskTo
}
$taskRows=@(Get-ChildItem -LiteralPath $taskStage -Recurse -File -Force|Where-Object {$_.FullName -notmatch '[/\\]\.git[/\\]' -and $_.Name -ne '.git'}|ForEach-Object {
 if($_.LinkType){throw 'Regular frozen input required'}
 @{path=[IO.Path]::GetRelativePath($taskStage,$_.FullName).Replace([char]92,[char]47);sha256=Hash $_.FullName}
}|Sort-Object path)
[IO.File]::WriteAllText("$taskStage/SOURCE_MANIFEST.json",($taskRows|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$taskReceipt=@{state='INPUTS_PREPARED_NOT_COMPILED';lockSha256=Hash "$PSScriptRoot/dependencies.lock.json";manifestSha256=Hash "$taskStage/SOURCE_MANIFEST.json";picoquic=$taskLock.picoquic.commit;picotls=$taskLock.picotls.commit;openssl=$taskLock.opensslSource.version;compiled=$false;executed=$false;systemInstalled=$false}
[IO.File]::WriteAllText("$taskStage/receipt.json",($taskReceipt|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false));Write-Output "P3_SLIPSTREAM_INPUTS=$taskStage"
