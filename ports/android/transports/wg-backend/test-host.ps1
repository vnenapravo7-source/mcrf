param([string]$RetainFrom='')
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
if(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'route_pipe.go')){throw 'Current private packet-pipe source needs test-p2-pipe.ps1 in the common P2 batch; the Windows wrapper does not execute Linux channel cases. Retain closed P1 evidence instead of rerunning it.'}
& (Join-Path $root 'ports/android/maintenance/Assert-HumanBatchReady.ps1') -Operation test
if($args.Count){throw 'Unknown WG host gate arguments'}
$source=Join-Path $root 'work/android-upstream/sing-box-lx-mcrf-clash-20261014/submodules/wireguard-go'
$head=(& git -C $source rev-parse HEAD|Out-String).Trim()
if($LASTEXITCODE -ne 0 -or $head -cne '64065e6826ad82aa28429dd829c1c65f29b7f6c9' -or ((& git -C $source status --porcelain|Out-String).Trim())){throw 'WG/AWG source pin or pristine source check failed'}
$taskLock=[Threading.Mutex]::new($false,'Local\MCRF-WG-Pipeline')
if(!$taskLock.WaitOne(0)){throw 'WG host gate already active; continue that process'}
try {
$taskStage=Join-Path $root ('work/wg-host-'+[guid]::NewGuid().ToString('N'))
$taskFrozen=Join-Path $taskStage 'source'
New-Item -ItemType Directory -Path $taskFrozen|Out-Null
$taskInputs=@(Get-ChildItem -LiteralPath $PSScriptRoot -File|Where-Object {$_.Extension -ceq '.go' -or $_.Name -in @('go.mod','go.sum')})
$taskManifest=@($taskInputs|ForEach-Object {
    if($_.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked WG source refused'}
    $taskHash=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()
    [IO.File]::Copy($_.FullName,(Join-Path $taskFrozen $_.Name),$false)
    if((Get-FileHash -LiteralPath (Join-Path $taskFrozen $_.Name)).Hash.ToLowerInvariant() -cne $taskHash){throw 'WG frozen source copy changed'}
    [pscustomobject]@{name=$_.Name;sha256=$taskHash}
})
$names=@('GOPATH','GOCACHE','GOTMPDIR','GOTOOLCHAIN','GOFLAGS','GOWORK','GOENV');$saved=@{};foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$taskState=[ordered]@{passed=$false;sourceStable=$false;upstreamCommit=$head;inputs=$taskManifest;tests=@();deviceTested=$false;scope='Owned host WG/AWG only; no Android/provider acceptance'}
$retained=@()
if($RetainFrom){
    $priorPath=[IO.Path]::GetFullPath($RetainFrom)
    if([IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($priorPath)) -ine (Join-Path $root 'work') -or [IO.Path]::GetFileName([IO.Path]::GetDirectoryName($priorPath)) -cnotmatch '^wg-host-[0-9a-f]{32}$' -or [IO.Path]::GetFileName($priorPath) -cne 'receipt.json'){throw 'Exact prior WG host evidence required'}
    $prior=Get-Content -LiteralPath $priorPath -Raw|ConvertFrom-Json
    if(!$prior.passed -or !$prior.sourceStable -or $prior.upstreamCommit -cne $head -or @($prior.inputs).Count -ne $taskManifest.Count){throw 'Retained WG host evidence incomplete'}
    $changed=@()
    foreach($row in $taskManifest){$old=@($prior.inputs|Where-Object name -CEQ $row.name);if($old.Count -ne 1){throw 'Retained WG input set changed'};if($old[0].sha256 -cne $row.sha256){$changed+=$row.name}}
    if((($changed|Sort-Object) -join ',') -cne 'dns_nat_test.go,dns_tun.go'){throw 'Only reviewed DNS batch wrapper repair may retain old WG/AWG cases'}
    $events=@(Get-Content -LiteralPath (Join-Path ([IO.Path]::GetDirectoryName($priorPath)) 'go-test.jsonl')|ForEach-Object {try{$_|ConvertFrom-Json -ErrorAction Stop}catch{}})
    if(@($events|Where-Object {$_.Action -in @('fail','skip')}).Count){throw 'Retained WG event log incomplete'}
    $retained=@($prior.tests|Where-Object {$_ -cnotmatch '^TestManagedDns'})
    foreach($test in $retained){if(!@($events|Where-Object {$_.Test -ceq $test -and $_.Action -ceq 'pass'}).Count){throw 'Missing actual retained WG case'}}
    $taskState.retainedFrom=$priorPath;$taskState.retainedReceiptSha256=(Get-FileHash -LiteralPath $priorPath).Hash.ToLowerInvariant();$taskState.retainedTests=$retained
}
try {
    $env:GOPATH=Join-Path $root 'work/toolchains/go-home';$env:GOCACHE=Join-Path $root 'work/toolchains/go-cache-wgbackend';$env:GOTMPDIR=Join-Path $taskStage 'tmp'
    $env:GOTOOLCHAIN='local';$env:GOFLAGS='-mod=readonly -p=4';$env:GOWORK='off';$env:GOENV='off'
    New-Item -ItemType Directory -Path $env:GOTMPDIR -Force|Out-Null
    $go=Join-Path $root 'work/toolchains/go1.26.8/go/bin/go.exe'
    $taskState.goSha256=(Get-FileHash -LiteralPath $go).Hash.ToLowerInvariant()
    if($RetainFrom -and $taskState.goSha256 -cne $prior.goSha256){throw 'WG retained compiler changed'}
    Push-Location $taskFrozen
    try {
        # Preparation finishes before hashing compiler inputs. Never tidy/mutate canonical sources.
        & $go mod edit "-replace=github.com/sagernet/wireguard-go=$source";if($LASTEXITCODE){throw 'Frozen WG source pin failed'}
        $taskState.frozenInputs=@($taskInputs|ForEach-Object {[pscustomobject]@{name=$_.Name;sha256=(Get-FileHash -LiteralPath (Join-Path $taskFrozen $_.Name)).Hash.ToLowerInvariant()}})
        & $go mod verify;if($LASTEXITCODE -ne 0){throw 'Standalone WG dependency checksum failed'}
        $dependencies=(& $go list -deps .|Out-String)
        if($LASTEXITCODE -ne 0 -or $dependencies -match '(?m)^(gvisor.dev/|github.com/(sagernet|Leadaxe)/sing-box)'){throw 'WG bridge unexpectedly depends on a full core/netstack'}
        $selection=@();if($RetainFrom){$selection=@('-run','^TestManagedDns')}
        $taskOutput=@(& $go test -json -count=1 -timeout 30s @selection ./... 2>&1)
        $taskExit=$LASTEXITCODE
        [IO.File]::WriteAllLines((Join-Path $taskStage 'go-test.jsonl'),[string[]]$taskOutput,[Text.UTF8Encoding]::new($false))
        $taskEvents=@($taskOutput|ForEach-Object {try{$_|ConvertFrom-Json -ErrorAction Stop}catch{}})
        $taskState.executedTests=@($taskEvents|Where-Object {$_.Action -ceq 'pass' -and $_.Test}|ForEach-Object Test)
        $taskState.tests=@($retained)+@($taskState.executedTests)
        if($taskExit -ne 0 -or !$taskState.tests.Count -or @($taskEvents|Where-Object {$_.Action -in @('fail','skip')}).Count){throw 'Standalone WG bridge host gate failed or skipped; original log retained'}
        foreach($taskRequired in @('TestAmneziaVariantsEncryptedDualStack/AWG2','TestAmneziaVariantsEncryptedDualStack/AWG3','TestAmneziaVariantsEncryptedDualStack/AWG3_1')){if($taskRequired -cnotin $taskState.tests){throw 'AWG generation gate missing from actual results'}}
        foreach($taskRequired in @('TestManagedDnsNatDualStackKernelRoundTrip','TestManagedDnsNatOutOfOrderFragments','TestManagedDnsNatBoundsExpiryAndStop','TestManagedDnsNatNewSynRetiresOldOriginalTupleLease','TestManagedDnsTunNoAliasPeerLeakAndPermittedAfter','TestManagedDnsTunAcceptsBindBatchCapacity')){if($taskRequired -cnotin $taskState.tests){throw 'Managed DNS native gate missing from actual results'}}
        foreach($taskRow in $taskState.frozenInputs){if((Get-FileHash -LiteralPath (Join-Path $taskFrozen $taskRow.name)).Hash.ToLowerInvariant() -cne $taskRow.sha256){throw 'Frozen WG host input changed'}}
        foreach($taskRow in $taskManifest){if((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $taskRow.name)).Hash.ToLowerInvariant() -cne $taskRow.sha256){throw 'Canonical WG source changed during host gate'}}
        if(@(Get-ChildItem -LiteralPath $taskFrozen -File).Count -ne $taskManifest.Count -or @(Get-ChildItem -LiteralPath $PSScriptRoot -File|Where-Object {$_.Extension -ceq '.go' -or $_.Name -in @('go.mod','go.sum')}).Count -ne $taskManifest.Count){throw 'WG compiler source set changed during host gate'}
        if(((& git -C $source rev-parse HEAD|Out-String).Trim()) -cne $head -or ((& git -C $source status --porcelain|Out-String).Trim())){throw 'Pinned upstream changed during WG host gate'}
        $taskState.sourceStable=$true;$taskState.passed=$true
        Write-Output "WG_HOST_PASS=$($taskState.tests.Count);RECEIPT=$taskStage/receipt.json"
    }finally{Pop-Location}
}finally{
    [IO.File]::WriteAllText((Join-Path $taskStage 'receipt.json'),($taskState|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
}
}finally{$taskLock.ReleaseMutex();$taskLock.Dispose()}
