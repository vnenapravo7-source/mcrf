param([Parameter(Mandatory)][string]$OriginalUpstream)
$ErrorActionPreference='Stop'
$taskOrigin=(Resolve-Path -LiteralPath $OriginalUpstream).Path
$taskCommit='397850b16e70a52513269134b2c7b9aa13a56745'
if($taskOrigin.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-slipstream-upstream-[a-f0-9]{32}$' -or (& git -C $taskOrigin rev-parse HEAD).Trim() -cne $taskCommit -or (& git -C $taskOrigin status --porcelain --untracked-files=all)){throw 'Pinned clean original C source required'}
$taskLock=(Get-Content "$PSScriptRoot/../transport-lock.json" -Raw|ConvertFrom-Json).slipstream
$taskStage="D:/MCRF/Builds/Android/p3-slipstream-cfixture-source-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $taskStage|Out-Null
# A local partial clone can lack promised blobs and lose the remote promisor
# when copied. Acquire directly so checkout can fetch its exact missing objects.
& git clone --filter=blob:none --no-checkout 'https://github.com/EndPositive/slipstream.git' "$taskStage/source";if($LASTEXITCODE){throw 'Owned original fixture source clone failed'}
& git -C "$taskStage/source" checkout --detach $taskCommit;if($LASTEXITCODE){throw 'Original source commit unavailable'}
& git -C "$taskStage/source" submodule update --init --recursive;if($LASTEXITCODE){throw 'Original C fixture pinned submodule unavailable'}
foreach($taskProperty in $taskLock.submodules.PSObject.Properties){
 if((& git -C "$taskStage/source/$($taskProperty.Name)" rev-parse HEAD).Trim() -cne $taskProperty.Value){throw 'Original C fixture submodule identity changed'}
}
if((& git -C "$taskStage/source" status --porcelain --untracked-files=all) -or @(& git -C "$taskStage/source" submodule status --recursive|Where-Object {$_ -match '^[+\-U]'}).Count){throw 'Dirty or mismatched original C source'}
Copy-Item -LiteralPath "$PSScriptRoot/fixture_loopback_bind.c" -Destination "$taskStage/fixture_loopback_bind.c"
$taskRows=@(Get-ChildItem -LiteralPath $taskStage -Recurse -File -Force|Where-Object {$_.FullName -notmatch '[/\\]\.git[/\\]' -and $_.Name -ne '.git'}|ForEach-Object {
 if($_.LinkType){throw 'Regular original C fixture source required'}
 @{path=[IO.Path]::GetRelativePath($taskStage,$_.FullName).Replace([char]92,[char]47);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
}|Sort-Object path)
[IO.File]::WriteAllText("$taskStage/SOURCE_MANIFEST.json",($taskRows|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$taskReceipt=@{state='C_FIXTURE_SOURCE_PREPARED';commit=$taskCommit;submodules=$taskLock.submodules;manifestSha256=(Get-FileHash "$taskStage/SOURCE_MANIFEST.json").Hash.ToLowerInvariant();loopbackOnly=$true;compiled=$false;executed=$false}
[IO.File]::WriteAllText("$taskStage/receipt.json",($taskReceipt|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false));Write-Output "P3_SLIPSTREAM_CFIXTURE_SOURCE=$taskStage"
