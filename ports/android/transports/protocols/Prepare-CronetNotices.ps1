param([Parameter(Mandatory)][string]$Upstream)
$ErrorActionPreference='Stop'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
$taskRoot=(Resolve-Path -LiteralPath $Upstream).Path
$taskLock=Get-Content "$PSScriptRoot/licenses/provenance.json" -Raw|ConvertFrom-Json
if($taskRoot.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-cronet-notices-upstream-[a-f0-9]{32}$' -or (& git -C $taskRoot rev-parse HEAD).Trim() -cne $taskLock.cronet.commit -or (Get-Content "$taskRoot/CHROMIUM_VERSION" -Raw).Trim() -cne $taskLock.cronet.chromiumVersion){throw 'Original pinned Chromium notice source/version required'}
if((& git -C $taskRoot status --porcelain --untracked-files=all)){throw 'Notice source changed'}
$taskStage="D:/MCRF/Builds/Android/p3-cronet-notices-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path "$taskStage/files"|Out-Null
$taskRows=[Collections.Generic.List[object]]::new();$taskAdded=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
function Add-Original([string]$Path){
 $taskRelative=[IO.Path]::GetRelativePath($taskRoot,$Path).Replace([char]92,[char]47)
 if($taskRelative.StartsWith('../') -or [IO.Path]::IsPathRooted($taskRelative)){throw 'Notice origin escaped source root'}
 if(!$taskAdded.Add($taskRelative)){return}
 $taskFile=Get-Item -LiteralPath $Path
 if($taskFile.LinkType -or $taskFile.PSIsContainer -or $taskFile.Length -gt 4194304){throw 'Regular bounded original notice required'}
 $taskBlob=(& git -C $taskRoot rev-parse "HEAD:$taskRelative").Trim()
 if($LASTEXITCODE -or (& git -C $taskRoot hash-object -- $taskRelative).Trim() -cne $taskBlob){throw 'Original notice Git object changed'}
 $taskTo="$taskStage/files/$taskRelative";New-Item -ItemType Directory -Path (Split-Path $taskTo) -Force|Out-Null;Copy-Item -LiteralPath $Path -Destination $taskTo
 $taskRows.Add(@{file=$taskRelative;gitBlob=$taskBlob;bytes=$taskFile.Length;sha256=Hash $taskTo})
}
# Include the original source's licence/notice superset. No attempt to infer
# licences from object filenames or silently omit an unknown runtime component.
$taskOriginal=@(Get-ChildItem -LiteralPath $taskRoot -File -Recurse|Where-Object {![IO.Path]::GetRelativePath($taskRoot,$_.FullName).StartsWith('.git'+[IO.Path]::DirectorySeparatorChar) -and $_.Name -match '^(LICENSE|LICENCE|COPYING|COPYRIGHT|NOTICE|PATENTS)([._-].*)?$' -and $_.Extension -notin @('.py','.h','.c','.cc','.cpp')})
foreach($taskFile in $taskOriginal){Add-Original $taskFile.FullName}
$taskMissing=@();$taskClassified=@();$taskMetadata=@(Get-ChildItem "$taskRoot/src" -Recurse -File -Filter README.chromium)
foreach($taskReadme in $taskMetadata){
 Add-Original $taskReadme.FullName
 $taskText=[IO.File]::ReadAllText($taskReadme.FullName)
 $taskRelative=[IO.Path]::GetRelativePath($taskRoot,$taskReadme.FullName).Replace([char]92,[char]47)
 if($taskRelative -ceq 'src/build/internal/README.chromium'){
  # Upstream explicitly classifies its own Visual Studio property sheets as
  # not third-party. Retain both the README and that original classification.
  $taskConstants="$taskRoot/src/components/cronet/license/constants.py"
  if(![IO.File]::ReadAllText($taskConstants).Contains('"build/internal/README.chromium"')){throw 'Original internal-build classification missing'}
  Add-Original $taskConstants
  $taskClassified+=@{file=$taskRelative;reason='upstream-not-third-party';evidence='src/components/cronet/license/constants.py'}
  continue
 }
 if($taskRelative -cmatch '^src/components/cronet/license/tests/target_[abc]/README\.chromium$'){
  # Three empty metadata-parser fixtures contain only GN groups, not shipped
  # third-party source. Preserve their original GN definitions as evidence.
  $taskBuild=Join-Path $taskReadme.DirectoryName 'BUILD.gn'
  $taskBuildText=[IO.File]::ReadAllText($taskBuild)
  if($taskText.Length -ne 0 -or $taskBuildText -notmatch '(?s)group\("target_[abc]"\)\s*\{(?:\s*deps\s*=\s*\[\s*"//components/cronet/license/tests/target_[bc]"\s*\])?\s*\}\s*$'){throw 'Original licence fixture changed'}
  Add-Original $taskBuild
  $taskClassified+=@{file=$taskRelative;reason='empty-upstream-metadata-fixture-no-source';evidence=[IO.Path]::GetRelativePath($taskRoot,$taskBuild).Replace([char]92,[char]47)}
  continue
 }
 if($taskText -match '(?m)^Shipped:\s*no\s*$'){continue}
 $taskLicense=[regex]::Match($taskText,'(?m)^License File:\s*(.+)$').Groups[1].Value.Trim()
 if(!$taskLicense){$taskMissing+=[IO.Path]::GetRelativePath($taskRoot,$taskReadme.FullName);continue}
 if($taskLicense -notmatch '^[/A-Za-z0-9_.+-]+$'){$taskMissing+=$taskLicense;continue}
 $taskPath=if($taskLicense.StartsWith('//')){Join-Path "$taskRoot/src" $taskLicense.Substring(2)}else{Join-Path $taskReadme.DirectoryName $taskLicense}
 if(!(Test-Path -LiteralPath $taskPath -PathType Leaf)){$taskMissing+=$taskLicense;continue};Add-Original $taskPath
}
# Perfetto's trimmed Chromium tree omits its root COPYING. Its actual pinned
# source headers specify Apache2; retain ALL shipped copyright/SPDX headers,
# and the full unchanged Apache2 text already in the same source revision.
$taskApache="$taskRoot/src/third_party/abseil-cpp/LICENSE"
if(![IO.File]::ReadAllText($taskApache).Contains('Apache License')){throw 'Original full Apache2 text unavailable'}
Add-Original $taskApache
$taskCredits=[Collections.Generic.SortedSet[string]]::new([StringComparer]::Ordinal)
$taskHeaderOrigins=[Collections.Generic.List[object]]::new()
$taskGitBlobs=[Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
foreach($taskLine in (& git -C $taskRoot ls-tree -r HEAD)){if($taskLine -cmatch '^100[0-9]+ blob ([a-f0-9]{40})\t(.+)$'){$taskGitBlobs[$Matches[2]]=$Matches[1]}}
if($LASTEXITCODE -or !$taskGitBlobs.Count){throw 'Original copyright tree unavailable'}
foreach($taskArea in @('src/third_party/perfetto','src/third_party/lss')){
 foreach($taskFile in (Get-ChildItem -LiteralPath "$taskRoot/$taskArea" -Recurse -File|Where-Object {$_.Extension -in @('.h','.cc','.c','.proto','.gni','.gn')})){
  $taskHeader=@(Get-Content -LiteralPath $taskFile.FullName -TotalCount 64|Where-Object {$_ -match '(?i)copyright|SPDX-License-Identifier'})
  if($taskHeader.Count){$taskRelative=[IO.Path]::GetRelativePath($taskRoot,$taskFile.FullName).Replace([char]92,[char]47);if(!$taskGitBlobs.ContainsKey($taskRelative) -or $taskFile.LinkType){throw 'Original copyright source unavailable'};$taskHeaderOrigins.Add(@{file=$taskRelative;gitBlob=$taskGitBlobs[$taskRelative];sha256=Hash $taskFile.FullName});foreach($taskLine in $taskHeader){[void]$taskCredits.Add($taskLine.Trim())}}
 }
}
if(!$taskCredits.Count){throw 'Pinned original copyright notices missing'}
[IO.File]::WriteAllText("$taskStage/files/Perfetto-LSS-NOTICE.txt",("Conservative original-source copyright notices; Perfetto uses Apache2, LSS retains its full source licence below.`n"+($taskCredits -join "`n")+"`n"),[Text.UTF8Encoding]::new($false))
$taskLss="$taskRoot/src/third_party/lss/linux_syscall_support.h"
if(!(Test-Path -LiteralPath $taskLss)){throw 'Original LSS licence header required'}
$taskLssHeader=@(Get-Content -LiteralPath $taskLss -TotalCount 160);$taskEnd=-1
for($taskIndex=0;$taskIndex -lt $taskLssHeader.Count;$taskIndex++){if($taskLssHeader[$taskIndex] -match '\*/'){$taskEnd=$taskIndex;break}}
if($taskEnd -lt 0 -or ($taskLssHeader -join "`n") -notmatch 'Redistribution and use'){throw 'Complete original LSS licence unavailable'}
[IO.File]::WriteAllText("$taskStage/files/LSS-LICENSE.txt",(($taskLssHeader[0..$taskEnd] -join "`n")+"`n"),[Text.UTF8Encoding]::new($false))
if((Hash "$PSScriptRoot/licenses/Protobuf-BSD.txt") -cne $taskLock.protobuf.sha256){throw 'Pinned official protobuf notice changed'}
Copy-Item "$PSScriptRoot/licenses/Protobuf-BSD.txt" "$taskStage/files/Protobuf-BSD.txt"
Copy-Item "$PSScriptRoot/licenses/provenance.json" "$taskStage/provenance.json"
if((& git -C $taskRoot status --porcelain --untracked-files=all)){throw 'Original notice/header sources changed while copying'}
Save ($taskHeaderOrigins.ToArray()) "$taskStage/source-headers.json"
$taskFiles=@(Get-ChildItem "$taskStage/files" -Recurse -File|ForEach-Object {@{file=[IO.Path]::GetRelativePath("$taskStage/files",$_.FullName).Replace([char]92,[char]47);bytes=$_.Length;sha256=Hash $_.FullName}}|Sort-Object file)
Save @{files=$taskFiles;original=$taskRows.ToArray();missing=$taskMissing;classifiedNonThirdParty=$taskClassified;scope='conservative-source-notice-superset';repository=$taskLock.cronet.repository;commit=$taskLock.cronet.commit;chromiumVersion=$taskLock.cronet.chromiumVersion} "$taskStage/notice-manifest.json"
Save @{state=if($taskMissing.Count){'NOTICES_INCOMPLETE'}else{'SOURCE_NOTICE_SUPERSET_PREPARED'};complete=($taskMissing.Count -eq 0);missing=$taskMissing;manifestSha256=Hash "$taskStage/notice-manifest.json";provenanceSha256=Hash "$taskStage/provenance.json";headerSourcesSha256=Hash "$taskStage/source-headers.json";compiled=$false;nativeExecuted=$false} "$taskStage/receipt.json"
if($taskMissing.Count){throw "Original shipped licence references unresolved; retain $taskStage"}
Write-Output "P3_CRONET_ORIGINAL_NOTICE_SUPERSET=$taskStage"
