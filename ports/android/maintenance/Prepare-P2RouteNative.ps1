#requires -Version 7.0
param()
# Frozen source preparation ONLY. Never call an old P0/P1 producer/grant.
$ErrorActionPreference='Stop'
if($args.Count){throw 'Unknown P2 native-source argument'}
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation native-build
$taskRoot='D:/MCRF/Projects/MCRF'
$taskCurrentPath=Join-Path $taskRoot 'ports/android/coordination/current.json'
$taskCurrent=Get-Content -LiteralPath $taskCurrentPath -Raw|ConvertFrom-Json
if($taskCurrent.activeBatch -cne 'p2-progress-2026-10-20.json' -or $taskCurrent.jobs.build -or $taskCurrent.jobs.device -or $taskCurrent.jobs.hostTest -or $taskCurrent.jobs.emulator){throw 'Exact idle whole-P2 source batch required; continue an existing owned job'}
$taskGate=Join-Path $taskRoot 'ports/android/coordination/p2-progress-2026-10-20.json'
$taskGateSha=(Get-FileHash -LiteralPath $taskGate).Hash.ToLowerInvariant()
$taskSource=Join-Path $taskRoot 'work/android-upstream/sing-box-lx-mcrf-clash-20261014'
$taskCommit='3d3d3db3a2ccef3b1a8eb71b831ed2461c54b94d'
$taskPins=[ordered]@{'submodules/gvisor'='117243aa02fa2915cb53d1d4549bfca3a3ebf238';'submodules/sing-tun'='6f56eca9cb2e81d1c233b6104618ff30500e9f39';'submodules/utls'='59e89bb121d8cc7a0a1c87a930864122b5685d00';'submodules/wireguard-go'='64065e6826ad82aa28429dd829c1c65f29b7f6c9'}
$taskFamily=Join-Path $taskRoot 'ports/android/primary-cores/singbox/native-patches/p044-direct-family'
$taskPipes=Join-Path $taskRoot 'ports/android/primary-cores/singbox/native-patches/p2-route-pipes'
function Hash([string]$Path){return (Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
$taskPrepareSha=Hash $PSCommandPath
function SaveCurrent($Value){
    if((Get-Item -LiteralPath $taskCurrentPath).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Redirected current-state record refused'}
    $temporary=Join-Path ([IO.Path]::GetDirectoryName($taskCurrentPath)) ('current-p2-native-'+[guid]::NewGuid().ToString('N')+'.tmp')
    $bytes=[Text.UTF8Encoding]::new($false).GetBytes(($Value|ConvertTo-Json -Depth 20))
    $stream=[IO.File]::Open($temporary,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$stream.Write($bytes,0,$bytes.Length);$stream.Flush($true)}finally{$stream.Dispose()}
    [IO.File]::Move($temporary,$taskCurrentPath,$true)
}
function NativeGit([string]$Path,[string[]]$Arguments){
    $value=(& git -C $Path @Arguments 2>&1|Out-String).Trim();if($LASTEXITCODE){throw 'Pinned P2 native Git operation failed'};return $value
}
if((NativeGit $taskSource @('rev-parse','HEAD')) -cne $taskCommit -or (NativeGit $taskSource @('diff','--name-only','--ignore-submodules=all'))){throw 'Tracked core source differs from the pinned tree'}
foreach($row in $taskPins.GetEnumerator()){
    $path=Join-Path $taskSource $row.Key
    if((NativeGit $path @('rev-parse','HEAD')) -cne $row.Value -or (NativeGit $path @('diff','--name-only'))){throw 'Native submodule source/pin changed'}
}
$taskFamilyFiles=@('direct_family_lx.go','direct_family_stub_lx.go','direct_family_lx_test.go','direct_family_policy_lx_test.go','direct_family_stub_lx_test.go','router-seam.patch','README.md')
$taskPipeFiles=@('p2_pipe_policy_lx.go','p2_pipe_policy_lx_test.go','p2_pipe_stack_lx.go','p2_pipe_stack_lx_test.go','p2_pipe_registry_lx.go','p2_pipe_registry_lx_test.go','p2_pipe_registry_stub_lx.go','p2_numeric_socks_lx.go','p2_numeric_socks_lx_test.go','libbox-context-seam.patch','README.md')
if(@(Get-ChildItem -LiteralPath $taskPipes -File).Count -ne $taskPipeFiles.Count){throw 'Review every new native route-pipe source input'}
$taskPatchRows=@()
foreach($area in @('direct-family','route-pipes')){
    $directory=if($area -ceq 'direct-family'){$taskFamily}else{$taskPipes}
    $files=if($area -ceq 'direct-family'){$taskFamilyFiles}else{$taskPipeFiles}
    foreach($name in $files){
        $item=Get-Item -LiteralPath (Join-Path $directory $name)
        if($item.PSIsContainer -or $item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Ordinary pinned native patch input required'}
        $taskPatchRows+=[pscustomobject]@{area=$area;file=$name;sha256=(Hash $item.FullName)}
    }
}
$taskMutex=[Threading.Mutex]::new($false,'Local\MCRF-Singbox-P2-Native')
if(!$taskMutex.WaitOne(0)){throw 'P2 native producer active; continue that owned process'}
try {
    $taskStage='D:/MCRF/Builds/Android/p2-singbox-route-source-'+[guid]::NewGuid().ToString('N')
    if(Test-Path -LiteralPath $taskStage){throw 'New immutable source stage required; never overwrite'}
    New-Item -ItemType Directory -Path (Join-Path $taskStage 'source')|Out-Null
    $taskReceipt=[ordered]@{schema=1;priority=2;state='PREPARING_SOURCE_ONLY';stage=$taskStage;commit=$taskCommit;submodules=$taskPins;readinessSha256=$taskGateSha;patches=$taskPatchRows;archives=@();compiled=$false;tested=$false;installed=$false;published=$false;producerComplete=$false;authority='Whole-P2 source/common verification only. No old P0/P1 grant reuse. No device/provider/publication. Preparation is not runtime acceptance.'}
    function Save{[IO.File]::WriteAllText((Join-Path $taskStage 'receipt.json'),($taskReceipt|ConvertTo-Json -Depth 9),[Text.UTF8Encoding]::new($false))}
    function Archive([string]$Repository,[string]$Commit,[string]$Destination,[string]$Name){
        $archive=Join-Path $taskStage ($Name+'.tar')
        NativeGit $Repository @('archive','--format=tar','-o',$archive,$Commit)|Out-Null
        $paths=@(& tar -tf $archive)
        if($LASTEXITCODE -or @($paths|Where-Object {$_ -match '(^/|^[A-Za-z]:|(^|/)\.\.(/|$)|\\)'}).Count){throw 'Unsafe pinned archive path'}
        $listing=@(& tar -tvf $archive)
        if($LASTEXITCODE -or @($listing|Where-Object {$_ -cnotmatch '^[d-]'}).Count){throw 'Pinned archive contains links/special members; review explicitly, never extract through a link'}
        [IO.Directory]::CreateDirectory($Destination)|Out-Null
        & tar -xf $archive -C $Destination;if($LASTEXITCODE){throw 'Pinned source extraction failed'}
        if(@(Get-ChildItem -LiteralPath $Destination -Recurse -Force -Attributes ReparsePoint).Count){throw 'Extracted native source contains redirected paths'}
        $taskReceipt.archives+=@{name=$Name;commit=$Commit;sha256=(Hash $archive);bytes=(Get-Item -LiteralPath $archive).Length}
    }
    Save
    $taskOwner="P2_NATIVE_PREP:$taskStage";$taskCurrent.jobs.build=$taskOwner
    SaveCurrent $taskCurrent
    try {
        Archive $taskSource $taskCommit (Join-Path $taskStage 'source') 'core'
        foreach($row in $taskPins.GetEnumerator()){Archive (Join-Path $taskSource $row.Key) $row.Value (Join-Path (Join-Path $taskStage 'source') $row.Key) ($row.Key.Replace('/','-'))}
        New-Item -ItemType Directory -Path (Join-Path $taskStage 'direct-family'),(Join-Path $taskStage 'route-pipes')|Out-Null
        foreach($row in $taskPatchRows){
            $canonical=Join-Path $(if($row.area -ceq 'direct-family'){$taskFamily}else{$taskPipes}) $row.file
            if((Hash $canonical) -cne $row.sha256){throw 'Canonical patch changed during preparation'}
            $target=if($row.file.EndsWith('.go')){
                Join-Path (Join-Path $taskStage $(if($row.area -ceq 'direct-family'){'source/route'}else{'source/experimental/libbox'})) $row.file
            }else{Join-Path (Join-Path $taskStage $row.area) $row.file}
            [IO.File]::Copy($canonical,$target,$false)
            if((Hash $target) -cne $row.sha256){throw 'Frozen patch bytes changed'}
        }
        $route=Join-Path $taskStage 'source/route/route.go'
        if((Hash $route) -cne 'e9dd4dd9588bd733a44299ccb6bdde205f0a89920c844ad308d3f7c7de3ada40'){throw 'Pinned Direct-family seam changed'}
        [IO.File]::WriteAllText($route,([IO.File]::ReadAllText($route).Replace("`r`n","`n")),[Text.UTF8Encoding]::new($false))
        if((Hash $route) -cne 'f48527789b1c1c2b4ab00f7fcfc2c5182aa20445a4694a51883981478916cd28'){throw 'Direct-family normalization changed'}
        NativeGit (Join-Path $taskStage 'source') @('apply',(Join-Path $taskStage 'direct-family/router-seam.patch'))|Out-Null
        $config=Join-Path $taskStage 'source/experimental/libbox/config.go'
        $taskReceipt.originalConfigSha256=Hash $config
        [IO.File]::WriteAllText($config,([IO.File]::ReadAllText($config).Replace("`r`n","`n")),[Text.UTF8Encoding]::new($false))
        NativeGit (Join-Path $taskStage 'source') @('apply',(Join-Path $taskStage 'route-pipes/libbox-context-seam.patch'))|Out-Null
        $taskReceipt.routeSha256=Hash $route;$taskReceipt.configSha256=Hash $config
        if((Hash $PSCommandPath) -cne $taskPrepareSha){throw 'Native preparer changed during execution'}
        [IO.File]::Copy($PSCommandPath,(Join-Path $taskStage 'prepare-control.ps1'),$false)
        if((Hash (Join-Path $taskStage 'prepare-control.ps1')) -cne $taskPrepareSha){throw 'Frozen native preparer changed'}
        $taskReceipt.prepareSha256=$taskPrepareSha
        $prefix=[IO.Path]::GetFullPath((Join-Path $taskStage 'source'))+[IO.Path]::DirectorySeparatorChar
        $manifest=@(Get-ChildItem -LiteralPath (Join-Path $taskStage 'source') -Recurse -File|Sort-Object FullName|ForEach-Object {
            if($_.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked source refused before hashing'}
            [pscustomobject]@{path=$_.FullName.Substring($prefix.Length).Replace('\','/');bytes=$_.Length;sha256=(Hash $_.FullName)}
        })
        [IO.File]::WriteAllText((Join-Path $taskStage 'source-manifest.json'),($manifest|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
        $taskReceipt.sourceFiles=$manifest.Count;$taskReceipt.sourceManifestSha256=Hash (Join-Path $taskStage 'source-manifest.json')
        if((Hash $taskGate) -cne $taskGateSha){throw 'Whole-P2 readiness changed during source preparation'}
        if((NativeGit $taskSource @('rev-parse','HEAD')) -cne $taskCommit -or (NativeGit $taskSource @('diff','--name-only','--ignore-submodules=all'))){throw 'Canonical native source changed during preparation'}
        foreach($pin in $taskPins.GetEnumerator()){$path=Join-Path $taskSource $pin.Key;if((NativeGit $path @('rev-parse','HEAD')) -cne $pin.Value -or (NativeGit $path @('diff','--name-only'))){throw 'Canonical native submodule changed'}}
        if(@(Get-ChildItem -LiteralPath $taskPipes -File).Count -ne $taskPipeFiles.Count){throw 'Native patch source set changed'}
        foreach($row in $taskPatchRows){$directory=if($row.area -ceq 'direct-family'){$taskFamily}else{$taskPipes};if((Hash (Join-Path $directory $row.file)) -cne $row.sha256){throw 'Canonical native patch changed'}}
        $taskReceipt.state='SOURCE_FROZEN_UNCOMPILED_UNTESTED';$taskReceipt.producerComplete=$true;Save
        Write-Output "P2_ROUTE_NATIVE_SOURCE=$taskStage/receipt.json"
    }catch{$taskReceipt.state='SOURCE_PREPARATION_FAILED';$taskReceipt.producerComplete=$true;Save;throw}
    finally{
        $latest=Get-Content -LiteralPath $taskCurrentPath -Raw|ConvertFrom-Json
        if($latest.jobs.build -ceq $taskOwner){$latest.jobs.build=$null;SaveCurrent $latest}
    }
}finally{$taskMutex.ReleaseMutex();$taskMutex.Dispose()}
