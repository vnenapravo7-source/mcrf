param([Parameter(Mandatory=$true)][string]$Stage,[Parameter(Mandatory=$true)][string]$Go)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot '../../maintenance/Assert-HumanBatchReady.ps1') -Operation native-build
$taskStage=[IO.Path]::GetFullPath($Stage)
if([IO.Path]::GetDirectoryName($taskStage) -ine [IO.Path]::GetFullPath('D:/MCRF/Builds/Android') -or [IO.Path]::GetFileName($taskStage) -cnotmatch '^s3bypass-native-[0-9a-f]{32}$' -or (Get-Item -LiteralPath $taskStage).LinkType){throw 'Owned S3 stage required'}
$taskReceipt=Get-Content -LiteralPath (Join-Path $taskStage 'receipt.json') -Raw|ConvertFrom-Json
if($taskReceipt.kind -cne 's3bypass' -or $taskReceipt.commit -cne 'b54ed13e3d7473ad8c25b5c39dfe127b004db540' -or @($taskReceipt.artifacts).Count -ne 3){throw 'Complete pinned native receipts required; no compiler is run here'}
if($taskReceipt.state -cnotin @('COMPILED_NOT_EXECUTED','COMPILED_NOTICES_PENDING') -or !$taskReceipt.sourceStable){throw 'Native producer is not complete/stable'}
if($taskReceipt.producerPid -and $taskReceipt.producerPid -ne $PID -and !$taskReceipt.producerComplete){
    $taskProcess=Get-Process -Id $taskReceipt.producerPid -ErrorAction SilentlyContinue
    if($taskProcess -and $taskProcess.StartTime.ToUniversalTime().Ticks -eq [long]$taskReceipt.producerStartTicks){throw 'Original producer still active; continue its session instead of duplicating metadata work'}
}
$taskSource=Join-Path $taskStage 'source'
function Assert-S3NoticeOrigin([string]$InputPath,[string]$Boundary){
    $taskOrigin=[IO.Path]::GetFullPath($InputPath);$taskBoundary=[IO.Path]::GetFullPath($Boundary)
    if($taskOrigin -ine $taskBoundary -and !$taskOrigin.StartsWith($taskBoundary+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Input escaped its declared source boundary'}
    $taskNode=Get-Item -LiteralPath $taskOrigin
    while($true){
        if($taskNode.LinkType){throw 'Linked input or ancestor rejected'}
        if($taskNode.FullName -ieq $taskBoundary){break}
        $taskNode=Get-Item -LiteralPath ([IO.Path]::GetDirectoryName($taskNode.FullName))
    }
}
Assert-S3NoticeOrigin $taskSource $taskStage
foreach($taskInput in (Get-Content -LiteralPath (Join-Path $taskStage 'source-manifest.json') -Raw|ConvertFrom-Json)){
    $taskFile=[IO.Path]::GetFullPath((Join-Path $taskSource $taskInput.path))
    Assert-S3NoticeOrigin $taskFile $taskSource
    if(!$taskFile.StartsWith($taskSource+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Get-Item -LiteralPath $taskFile).LinkType -or (Get-FileHash -LiteralPath $taskFile -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskInput.sha256){throw 'Pinned source changed; keep compiled inputs for review'}
}
$taskRoot=Join-Path $taskStage 'licenses'
if((Get-Item -LiteralPath $taskRoot).LinkType){throw 'Linked notice root rejected'}
$taskRows=[Collections.Generic.List[object]]::new()
function Add-S3Notice([string]$InputFile,[string]$Relative,[string]$Origin){
    $taskSourceFile=Get-Item -LiteralPath $InputFile
    if($taskSourceFile.PSIsContainer -or $taskSourceFile.LinkType -or $taskSourceFile.Length -gt 2097152){throw 'Regular bounded legal notice required'}
    $taskDestination=[IO.Path]::GetFullPath((Join-Path $taskRoot $Relative))
    if(!$taskDestination.StartsWith($taskRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Notice path escaped stage'}
    $taskParent=[IO.DirectoryInfo]::new([IO.Path]::GetDirectoryName($taskDestination))
    while($taskParent.FullName -ine $taskRoot){
        if((Test-Path -LiteralPath $taskParent.FullName) -and (Get-Item -LiteralPath $taskParent.FullName).LinkType){throw 'Linked notice parent rejected'}
        $taskParent=$taskParent.Parent
    }
    New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($taskDestination))|Out-Null
    if($taskSourceFile.FullName -ine $taskDestination){
        if(Test-Path -LiteralPath $taskDestination){
            if((Get-Item -LiteralPath $taskDestination).LinkType -or (Get-FileHash -LiteralPath $taskDestination -Algorithm SHA256).Hash -cne (Get-FileHash -LiteralPath $taskSourceFile.FullName -Algorithm SHA256).Hash){throw 'Existing nonidentical notice preserved for review'}
        }else{Copy-Item -LiteralPath $taskSourceFile.FullName -Destination $taskDestination}
    }
    $taskRows.Add(@{file=$Relative.Replace('\','/');origin=$Origin;bytes=$taskSourceFile.Length;sha256=(Get-FileHash -LiteralPath $taskDestination -Algorithm SHA256).Hash.ToLowerInvariant()})
}
Add-S3Notice (Join-Path $taskRoot 's3bypass-LICENSE') 's3bypass-LICENSE' 'Pinned Xray-core-fedarisha'
Add-S3Notice (Join-Path $taskRoot 'S3-Bypass-app-LICENSE') 'S3-Bypass-app-LICENSE' 'Pinned S3 Bypass application reference'
$taskGoRoot=[IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($Go)))
foreach($taskName in @('LICENSE','PATENTS','AUTHORS','CONTRIBUTORS')){
    $taskFile=Join-Path $taskGoRoot $taskName
    if(Test-Path -LiteralPath $taskFile -PathType Leaf){Add-S3Notice $taskFile ("go/"+$taskName) 'Pinned Go toolchain'}
}
if(!($taskRows|Where-Object {$_.file -ceq 'go/LICENSE'})){throw 'Go redistribution license missing'}
$taskLinked=@{}
$taskGoContext=@{}
foreach($taskKey in @('GOROOT','GOTOOLCHAIN','GOENV','GOWORK','GOFLAGS','GOPATH','GOMODCACHE','GOCACHE','GOTMPDIR')){$taskGoContext[$taskKey]=[Environment]::GetEnvironmentVariable($taskKey,'Process')}
try{
Remove-Item Env:GOROOT -ErrorAction SilentlyContinue
$env:GOTOOLCHAIN='local';$env:GOENV='off';$env:GOWORK='off';$env:GOFLAGS=''
$env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-path';$env:GOMODCACHE=Join-Path $env:GOPATH 'pkg/mod'
$env:GOCACHE='D:/MCRF/Projects/MCRF/work/toolchains/go-cache';$env:GOTMPDIR=Join-Path $taskStage 'tmp'
if($taskReceipt.toolchainSha256 -cne (Get-FileHash -LiteralPath $Go -Algorithm SHA256).Hash.ToLowerInvariant()){throw 'Use the exact compiler which produced the retained binaries; no recompilation needed'}
foreach($taskArtifact in $taskReceipt.artifacts){
    $taskBinary=[IO.Path]::GetFullPath((Join-Path $taskStage $taskArtifact.file))
    if(!$taskBinary.StartsWith($taskStage+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Get-Item -LiteralPath $taskBinary).LinkType -or (Get-FileHash -LiteralPath $taskBinary -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskArtifact.sha256){throw 'Native artifact changed before notice inventory'}
    $taskInfo=@(& $Go version -m $taskBinary)
    if($LASTEXITCODE){throw 'Cannot read native build info (binary was NOT executed)'}
    foreach($taskLine in $taskInfo){if($taskLine -match '^\s+dep\s+(\S+)\s+(\S+)'){$taskLinked[$Matches[1]]=$Matches[2]}}
}
$taskSource=Join-Path $taskStage 'source';$taskModCache=[IO.Path]::GetFullPath('D:/MCRF/Projects/MCRF/work/toolchains/go-path/pkg/mod')
Push-Location $taskSource
try{$taskModules=@(& $Go list -mod=readonly -m -f '{{.Path}}|{{.Version}}|{{if .Replace}}{{.Replace.Dir}}{{else}}{{.Dir}}{{end}}' all);if($LASTEXITCODE){throw 'Cannot resolve license origins; compiled native inputs are retained'}}finally{Pop-Location}
}finally{foreach($taskKey in $taskGoContext.Keys){[Environment]::SetEnvironmentVariable($taskKey,$taskGoContext[$taskKey],'Process')}}
if(!$taskLinked.Count){throw 'Missing linked-module build info; do not claim dependency notices complete'}
$taskMissing=[Collections.Generic.List[string]]::new()
foreach($taskPath in @($taskLinked.Keys|Sort-Object)){
    $taskRecord=@($taskModules|Where-Object {($_ -split '\|',3)[0] -ceq $taskPath})
    if($taskRecord.Count -ne 1){$taskMissing.Add($taskPath);continue}
    $taskParts=$taskRecord[0] -split '\|',3
    if($taskParts.Count -ne 3 -or !$taskParts[2]){$taskMissing.Add($taskPath);continue}
    $taskDirectory=[IO.Path]::GetFullPath($taskParts[2])
    $taskLocal=$taskDirectory -ieq $taskSource -or $taskDirectory.StartsWith($taskSource+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)
    if((!$taskDirectory.StartsWith($taskModCache+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -and !$taskLocal) -or (Get-Item -LiteralPath $taskDirectory).LinkType){throw 'Dependency notice origin outside approved module cache/source'}
    Assert-S3NoticeOrigin $taskDirectory $(if($taskLocal){$taskSource}else{$taskModCache})
    $taskNotices=@(Get-ChildItem -LiteralPath $taskDirectory -File|Where-Object {$_.Name -match '^(LICENSE|LICENCE|COPYING|NOTICE|AUTHORS)([._-].*)?$'}|Sort-Object Name)
    if(!$taskNotices.Count){$taskMissing.Add($taskPath);continue}
    $taskId=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($taskPath))).ToLowerInvariant()
    foreach($taskNotice in $taskNotices){Add-S3Notice $taskNotice.FullName ("dependencies/"+$taskId+"/"+$taskNotice.Name) ($taskPath+"@"+$taskLinked[$taskPath])}
}
$taskManifest=@{schema=1;binaryExecuted=$false;linkedModules=$taskLinked.Count;collectorSha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant();missingModules=@($taskMissing.ToArray());files=@($taskRows.ToArray())}
[IO.File]::WriteAllText((Join-Path $taskStage 'notice-manifest.json'),($taskManifest|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$taskReceipt|Add-Member -NotePropertyName noticeReviewRequired -NotePropertyValue ($taskMissing.Count -gt 0) -Force
$taskReceipt.state=if($taskMissing.Count){'COMPILED_NOTICES_PENDING'}else{'COMPILED_NOT_EXECUTED'}
[IO.File]::WriteAllText((Join-Path $taskStage 'receipt.json'),($taskReceipt|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
Write-Output "S3_NOTICES=$($taskRows.Count);REVIEW_REQUIRED=$($taskMissing.Count)"
