param([Parameter(Mandatory)][string]$Upstream,[Parameter(Mandatory)][string]$AdapterUpstream)
$ErrorActionPreference='Stop'
$taskLock=(Get-Content -LiteralPath "$PSScriptRoot/../transport-lock.json" -Raw|ConvertFrom-Json).olcrtc
$taskUpstream=(Resolve-Path -LiteralPath $Upstream).Path
$taskAdapter=(Resolve-Path -LiteralPath $AdapterUpstream).Path
if($taskUpstream.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-olcrtc-upstream-[a-f0-9]{32}$' -or $taskAdapter.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p4-sstp-upstream-[a-f0-9]{32}$'){throw 'Exact owned pinned source copies required'}
foreach($taskPair in @(@{path=$taskUpstream;commit=$taskLock.commit},@{path=$taskAdapter;commit=$taskLock.adapterCommit})){
 if((& git -C $taskPair.path rev-parse HEAD).Trim() -cne $taskPair.commit -or (& git -C $taskPair.path status --porcelain --untracked-files=all)){throw 'Pinned clean OLC/adapter source required'}
}
$taskStage="D:/MCRF/Builds/Android/p3-olcrtc-source-$([guid]::NewGuid().ToString('N'))"
$taskModule="$taskStage/module";New-Item -ItemType Directory -Path $taskModule|Out-Null
foreach($taskRelative in (& git -C $taskUpstream ls-files)){
 $taskFrom=Join-Path $taskUpstream $taskRelative
 if(!(Test-Path -LiteralPath $taskFrom -PathType Leaf)){throw 'Complete pinned OLC checkout required'}
 if((Get-Item -LiteralPath $taskFrom).LinkType -or (& git -C $taskUpstream hash-object -- $taskRelative).Trim() -cne (& git -C $taskUpstream rev-parse "HEAD:$taskRelative").Trim()){throw 'Pinned OLC Git object changed'}
 $taskTo=Join-Path $taskModule $taskRelative;New-Item -ItemType Directory -Path (Split-Path $taskTo) -Force|Out-Null;Copy-Item -LiteralPath $taskFrom -Destination $taskTo
}
New-Item -ItemType Directory -Path "$taskModule/cmd/mcrf-olcrtc"|Out-Null
foreach($taskName in @('config.go','private_config.go','main.go','failure.go','mcrf_test.go')){Copy-Item -LiteralPath "$PSScriptRoot/$taskName" -Destination "$taskModule/cmd/mcrf-olcrtc/$taskName"}
# Keep the GPL adapter notice as well as the original WTFPL protocol library.
$taskLicense=(& git -C $taskAdapter show HEAD:LICENSE) -join "`n";if($LASTEXITCODE -or !$taskLicense.Contains('GNU GENERAL PUBLIC LICENSE')){throw 'Pinned adapter licence unavailable'}
[IO.File]::WriteAllText("$taskModule/MCRF-ADAPTER-LICENSE",$taskLicense+"`n",[Text.UTF8Encoding]::new($false))
$taskRows=@(Get-ChildItem -LiteralPath $taskModule -Recurse -File|ForEach-Object {@{path=[IO.Path]::GetRelativePath($taskModule,$_.FullName).Replace([char]92,[char]47);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}}|Sort-Object path)
[IO.File]::WriteAllText("$taskStage/SOURCE_MANIFEST.json",($taskRows|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$taskProof=@{state='SOURCE_PREPARED_NOT_COMPILED';module=$taskModule;commit=$taskLock.commit;adapterCommit=$taskLock.adapterCommit;manifestSha256=(Get-FileHash "$taskStage/SOURCE_MANIFEST.json").Hash.ToLowerInvariant();compiled=$false;executed=$false;androidIntegrated=$false}
[IO.File]::WriteAllText("$taskStage/receipt.json",($taskProof|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false));Write-Output "P3_OLCRTC_SOURCE=$taskStage"
