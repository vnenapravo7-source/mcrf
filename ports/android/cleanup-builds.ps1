param([switch]$Apply)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskLock=[IO.File]::Open((Join-Path $PSScriptRoot 'coordination/build-retention.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try {
$taskWork=Join-Path $taskRoot 'work'
$taskRegistry=Join-Path $PSScriptRoot 'coordination/build-retention.json'
$taskPolicy=Get-Content -LiteralPath $taskRegistry -Raw|ConvertFrom-Json
if($taskPolicy.keepCompleted -ne 3){throw 'Expected explicit three-build policy'}
$taskActive=@($taskPolicy.activeSnapshots)
$taskInstalledElsewhere=@($taskPolicy.additionalInstalledSnapshots|Where-Object {$_})
$taskVerification=@($taskPolicy.verificationSnapshots|Where-Object {$_})
$taskRejected=@($taskPolicy.rejectedCompletedSnapshots|Where-Object {$_})
if($taskRejected.Count -gt 8){throw 'Unexpected rejected candidate inventory'}
foreach($taskRejectedName in $taskRejected){
    if($taskRejectedName -isnot [string] -or $taskRejectedName -notmatch '^android-integration-[0-9a-f]{32}$'){throw 'Invalid rejected snapshot identity'}
}
if($taskVerification.Count -gt 3){
    if($Apply){throw 'Unexpected pending verification snapshot count'}
    Write-Warning 'Read-only retention plan deferred: unexpected pending verification inventory; all files preserved'
    return
}
foreach($taskPending in $taskVerification){
    if($taskPending -isnot [string] -or $taskPending -notmatch '^android-integration-[0-9a-f]{32}$'){throw 'Invalid pending verification snapshot identity'}
}
if($taskInstalledElsewhere.Count -gt 8){throw 'Unexpected installed snapshot inventory'}
foreach($taskInstalledName in $taskInstalledElsewhere){
    if($taskInstalledName -isnot [string] -or $taskInstalledName -notmatch '^android-integration-[0-9a-f]{32}$'){throw 'Invalid installed snapshot identity'}
}
$taskCandidates=@(Get-ChildItem -LiteralPath $taskWork -Directory -Filter 'android-integration-*'|Where-Object {$_.Name -match '^android-integration-[0-9a-f]{32}$'})
$taskFamilies=@{}
$taskReady=@(foreach($dir in $taskCandidates){
    $receipt=Join-Path $dir.FullName 'build-result.json'
    if(!(Test-Path -LiteralPath $receipt)){continue}
    $data=Get-Content -LiteralPath $receipt -Raw|ConvertFrom-Json
    if($dir.Name -notin $taskRejected -and $data.exitCode -eq 0 -and $data.sourceStable -and @($data.task|Where-Object {$_ -match '^:app:assemble(?:Debug|Release)$'}).Count -gt 0 -and
        (Test-Path -LiteralPath (Join-Path $dir.FullName 'ports/android/app/build/outputs/apk'))){
        # A disposable DEBUG test build cannot replace a release in the human
        # app's three-version family merely because both use the same package ID.
        $taskFamilies[$dir.Name]=if($data.firstLaunchFixture){'isolated-onboarding'}elseif(':app:assembleRelease' -in @($data.task)){'main-release'}else{'main-debug'}
        $dir
    }
})
$taskCompleted=@()
# A disposable test application has a different package/UID and must not evict
# the human application's verified release candidate from its own three-version family.
foreach($taskFamily in @('main-release','main-debug','isolated-onboarding')){
    $taskFamilyReady=@($taskReady|Where-Object {$taskFamilies[$_.Name] -eq $taskFamily})
    $taskFamilyKeep=@($taskFamilyReady|Sort-Object CreationTimeUtc -Descending|Select-Object -First 3|ForEach-Object {$_.Name})
    if($taskPolicy.installedSnapshot -and $taskPolicy.installedSnapshot -notin $taskFamilyKeep -and $taskPolicy.installedSnapshot -in @($taskFamilyReady.Name)){
        $taskFamilyKeep+= $taskPolicy.installedSnapshot
    }
    $taskCompleted+=$taskFamilyKeep
}
# A candidate installed on an owned emulator is protected just like the physical
# phone candidate. Release that protection only after its actual replacement.
$taskKeep=@(($taskCompleted+$taskActive+$taskInstalledElsewhere+$taskVerification)|Select-Object -Unique)
$taskDelete=@($taskCandidates|Where-Object {
    if($_.Name -in $taskKeep -or $_.Name -notin @($taskReady.Name)){return $false}
    $candidate=$_
    foreach($module in @('app','engines','primary-spi','primary-adapter','wg-module')){
        $link=Join-Path $candidate.FullName "ports/android/$module/build"
        if(!(Test-Path -LiteralPath $link)){continue}
        $item=Get-Item -LiteralPath $link
        if(!($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){continue}
        $expected=[IO.Path]::GetFullPath((Join-Path $taskPolicy.generatedScratchRoot "$($candidate.Name)/$module"))
        if([IO.Path]::GetFullPath([string]$item.Target) -ceq $expected -and @(Get-ChildItem -LiteralPath $expected -Force).Count){return $true}
    }
    return $false
})
[pscustomobject]@{keep=$taskKeep;remove=@($taskDelete|ForEach-Object {$_.Name});apply=[bool]$Apply}|ConvertTo-Json -Depth 4
if(!$Apply){return}
$taskArchiveRoot=Join-Path $PSScriptRoot 'coordination/build-history'
New-Item -ItemType Directory -Path $taskArchiveRoot -Force|Out-Null
$taskFreed=0L
foreach($dir in $taskDelete){
    $absolute=[IO.Path]::GetFullPath($dir.FullName)
    if([IO.Path]::GetDirectoryName($absolute) -cne [IO.Path]::GetFullPath($taskWork) -or [IO.Path]::GetFileName($absolute) -notmatch '^android-integration-[0-9a-f]{32}$'){throw 'Snapshot target escaped exact build inventory'}
    if($dir.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Snapshot root may not be a link'}
    $archive=Join-Path $taskArchiveRoot $dir.Name
    New-Item -ItemType Directory -Path $archive -Force|Out-Null
    $evidence=@('source-manifest.json','build-result.json','build.log','p1-owner-artifacts.json')
    $tests=Join-Path $absolute 'ports/android/app/build/test-results'
    if(Test-Path -LiteralPath $tests){$evidence+=@(Get-ChildItem -LiteralPath $tests -File -Filter '*.xml' -Recurse|ForEach-Object {[IO.Path]::GetRelativePath($absolute,$_.FullName)})}
    foreach($relative in $evidence){
        $source=Join-Path $absolute $relative
        if(!(Test-Path -LiteralPath $source -PathType Leaf)){continue}
        $destination=Join-Path $archive $relative
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force|Out-Null
        [IO.File]::Copy($source,$destination,$true)
        if((Get-FileHash -LiteralPath $source).Hash -cne (Get-FileHash -LiteralPath $destination).Hash){throw 'Evidence archive verification failed'}
    }
    # The only permitted links are known generated module outputs on the recorded D: scratch.
    foreach($module in @('app','engines','primary-spi','primary-adapter','wg-module')){
        $link=Join-Path $absolute "ports/android/$module/build"
        if(!(Test-Path -LiteralPath $link)){continue}
        $item=Get-Item -LiteralPath $link
        if(!($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){continue}
        $target=[IO.Path]::GetFullPath([string]$item.Target)
        $expected=[IO.Path]::GetFullPath((Join-Path $taskPolicy.generatedScratchRoot "$($dir.Name)/$module"))
        if($target -cne $expected){throw 'Unexpected generated junction target; no deletion'}
        # Keep source snapshots and junction anchors. Only verified generated
        # scratch contents are disposable; never recursively remove source trees.
        if(!$target.StartsWith(([IO.Path]::GetFullPath($taskPolicy.generatedScratchRoot).TrimEnd('\')+'\'),[StringComparison]::OrdinalIgnoreCase)){throw 'Scratch path escaped build root'}
        $walk=[Collections.Generic.Stack[IO.DirectoryInfo]]::new()
        $walk.Push((Get-Item -LiteralPath $target))
        $payload=[Collections.Generic.List[IO.FileInfo]]::new()
        while($walk.Count){
            $current=$walk.Pop()
            if($current.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Unexpected generated directory link'}
            foreach($child in $current.EnumerateDirectories()){$walk.Push($child)}
            foreach($file in $current.EnumerateFiles()){
                if($file.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Unexpected generated file link'}
                $probe=[IO.File]::Open($file.FullName,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None);$probe.Dispose()
                $payload.Add($file)
            }
        }
        $taskFreed += [long](($payload|Measure-Object Length -Sum).Sum)
        foreach($child in Get-ChildItem -LiteralPath $target -Force){Remove-Item -LiteralPath $child.FullName -Recurse -Force}
    }
    # No source snapshot deletion. Unknown and failed snapshots are retained.
}
[pscustomobject]@{removed=$taskDelete.Count;logicalBytesRemoved=$taskFreed;evidenceArchive=$taskArchiveRoot;remainingSnapshots=@($taskKeep)}|ConvertTo-Json -Depth 3
}finally{$taskLock.Dispose()}
