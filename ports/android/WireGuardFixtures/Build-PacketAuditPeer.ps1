param([string]$BuildScratchRoot='D:/MCRF/Builds/Android',[switch]$P1Managed,[string]$RetainManagedEvidence='')
$ErrorActionPreference='Stop'
$taskRecipeSha=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
& (Join-Path $PSScriptRoot '../maintenance/Assert-HumanBatchReady.ps1') -Operation test
$taskCurrent=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../coordination/current.json') -Raw|ConvertFrom-Json
if($taskCurrent.activeBatch -ceq 'p1-progress-2026-10-17.json' -and !$P1Managed){throw 'P1 builds only the new managed peer checks, not the closed legacy DNS fixture suite'}
$taskRepo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskUpstream=Join-Path $taskRepo 'work/android-upstream/sing-box-lx-mcrf-clash-20261014/submodules/wireguard-go'
$taskHead=(& git -C $taskUpstream rev-parse HEAD|Out-String).Trim()
if($LASTEXITCODE -ne 0 -or $taskHead -cne '64065e6826ad82aa28429dd829c1c65f29b7f6c9' -or ((& git -C $taskUpstream status --porcelain|Out-String).Trim())){throw 'Final peer requires the same exact pristine AWG3 upstream'}
if($RetainManagedEvidence -and !$P1Managed){throw 'Managed retention only in whole P1'}
$taskScratch=[IO.Path]::GetFullPath($BuildScratchRoot)
if($taskScratch -ine 'D:\MCRF\Builds\Android'){throw 'Explicit Android generated-artifact root required'}
if(![Environment]::Is64BitOperatingSystem -or ![Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([Runtime.InteropServices.OSPlatform]::Windows)){throw 'Windows x64 owned fixture only'}
$taskGo=Join-Path $taskRepo 'work/toolchains/go1.26.8/go/bin/go.exe'
if(!(Test-Path -LiteralPath $taskGo -PathType Leaf)){throw 'Pinned Go tool unavailable'}
$taskLease=[IO.File]::Open((Join-Path $taskScratch 'packet-peer-audit.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
$taskEnvironment=@{}
foreach($taskName in @('GOOS','GOARCH','GOARM','CGO_ENABLED','GOPATH','GOCACHE','GOMODCACHE','GOTOOLCHAIN','GOFLAGS','GOWORK','GOENV')){$taskEnvironment[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
try {
    $taskStage=Join-Path $taskScratch ('packet-dns-peer-audit-'+[guid]::NewGuid().ToString('N'))
    if(Test-Path -LiteralPath $taskStage){throw 'Fresh peer snapshot required'}
    $taskSource=Join-Path $taskStage 'source'
    New-Item -ItemType Directory -Path (Join-Path $taskSource 'cmd/WireGuardPeer') | Out-Null
    $taskFiles=@((Join-Path $PSScriptRoot 'go.mod'),(Join-Path $PSScriptRoot 'go.sum'))
    $taskFiles+=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'cmd/WireGuardPeer') -File -Filter '*.go' | ForEach-Object FullName)
    $taskManifest=@()
    foreach($taskFile in $taskFiles){
        $taskInfo=Get-Item -LiteralPath $taskFile
        if(($taskInfo.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Source links are not permitted'}
        $taskRelative=[IO.Path]::GetRelativePath($PSScriptRoot,$taskInfo.FullName)
        if($taskRelative.StartsWith('..') -or [IO.Path]::IsPathRooted($taskRelative)){throw 'Source escaped fixture root'}
        $taskHash=(Get-FileHash -LiteralPath $taskFile -Algorithm SHA256).Hash.ToLowerInvariant()
        $taskCopy=Join-Path $taskSource $taskRelative
        Copy-Item -LiteralPath $taskFile -Destination $taskCopy
        if((Get-FileHash -LiteralPath $taskCopy -Algorithm SHA256).Hash -ine $taskHash){throw 'Fixture source copy mismatch'}
        $taskManifest+=@{path=$taskRelative;sha256=$taskHash}
    }
    $env:GOOS='windows';$env:GOARCH='amd64';$env:CGO_ENABLED='0'
    Remove-Item Env:GOARM -ErrorAction SilentlyContinue
    $env:GOPATH=Join-Path $taskRepo 'work/toolchains/go-home'
    $env:GOCACHE=Join-Path $taskRepo 'work/toolchains/go-build-cache'
    $env:GOMODCACHE=Join-Path $env:GOPATH 'pkg/mod'
    $env:GOTOOLCHAIN='local'
    $env:GOFLAGS='-mod=readonly -p=4';$env:GOWORK='off';$env:GOENV='off'
    $taskRetained=@();$taskPrior=$null;$taskPriorPath=$null
    if($RetainManagedEvidence){
        $taskPriorRoot=[IO.Path]::GetFullPath($RetainManagedEvidence)
        if([IO.Path]::GetDirectoryName($taskPriorRoot) -ine $taskScratch -or [IO.Path]::GetFileName($taskPriorRoot) -cnotmatch '^packet-dns-peer-audit-[0-9a-f]{32}$'){throw 'Exact prior peer stage required'}
        $taskPriorPath=Join-Path $taskPriorRoot 'receipt.json';$taskPrior=Get-Content -LiteralPath $taskPriorPath -Raw|ConvertFrom-Json
        if(!$taskPrior.sourceStable -or !$taskPrior.managed_dns -or $taskPrior.compiler -cne 'go version go1.26.8 windows/amd64'){throw 'Prior managed peer incomplete'}
        # These two cases depend only on the four unchanged stdlib test/source
        # files, not the new device/TLS implementation or its added Go modules.
        foreach($relative in @('cmd/WireGuardPeer/managed_dns.go','cmd/WireGuardPeer/managed_dns_test.go','cmd/WireGuardPeer/audit.go','cmd/WireGuardPeer/dns.go')){
            $old=@($taskPrior.source|Where-Object {$_.path.Replace('\','/') -ceq $relative})
            if($old.Count -ne 1 -or (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $relative)).Hash.ToLowerInvariant() -cne $old[0].sha256){throw 'Retained managed test dependency changed'}
        }
        $taskOldEvents=@(Get-Content -LiteralPath (Join-Path $taskPriorRoot 'test.jsonl')|ForEach-Object {try{$_|ConvertFrom-Json -ErrorAction Stop}catch{}})
        $taskRetained=@('TestManagedPeerDnsTcpUdpBothFamiliesAndRealClose','TestManagedPeerAuditDetectsInternalAliasWithoutChangingPermittedPackets')
        foreach($name in $taskRetained){if($name -cnotin $taskPrior.tests -or !@($taskOldEvents|Where-Object {$_.Test -ceq $name -and $_.Action -ceq 'pass'}).Count){throw 'Missing actual old managed test event'}}
        if(@($taskOldEvents|Where-Object {$_.Action -cin @('fail','skip')}).Count){throw 'Old peer event log incomplete'}
    }
    Push-Location $taskSource
    try {
        $taskVersion=(& $taskGo version | Out-String).Trim()
        if($LASTEXITCODE -ne 0 -or $taskVersion -notmatch '^go version go1\.26\.8 windows/amd64$'){throw 'Unexpected Go compiler'}
        & $taskGo mod edit "-replace=github.com/sagernet/wireguard-go=$taskUpstream"
        if($LASTEXITCODE){throw 'Frozen final peer upstream binding failed'}
        $taskFrozenInputs=@($taskManifest|ForEach-Object {@{path=$_.path;sha256=(Get-FileHash -LiteralPath (Join-Path $taskSource $_.path)).Hash.ToLowerInvariant()}})
        & $taskGo mod verify
        if($LASTEXITCODE -ne 0){throw 'Fixture dependency verification failed'}
        $taskFilter=if($RetainManagedEvidence){'^TestP1Final'}elseif($P1Managed){'^(TestManaged|TestP1Final)'}else{'^(TestAudit|TestFixture)'}
        $taskTestOutput=@(& $taskGo test ./cmd/WireGuardPeer -run $taskFilter -count=1 -json 2>&1)
        $taskTestExit=$LASTEXITCODE
        [IO.File]::WriteAllLines((Join-Path $taskStage 'test.jsonl'),[string[]]$taskTestOutput,[Text.UTF8Encoding]::new($false))
        $taskTestEvents=@($taskTestOutput|ForEach-Object {try{$_|ConvertFrom-Json -ErrorAction Stop}catch{}})
        $taskPassed=@($taskTestEvents|Where-Object {$_.Action -ceq 'pass' -and $_.Test}|ForEach-Object Test)
        $taskExecuted=$taskPassed;$taskPassed=@($taskRetained)+@($taskPassed)
        if($taskTestExit -ne 0 -or @($taskTestEvents|Where-Object {$_.Action -in @('fail','skip')}).Count){throw 'Peer scoped tests failed/skipped; retain original JSON log'}
        if($P1Managed){foreach($taskRequired in @('TestManagedPeerDnsTcpUdpBothFamiliesAndRealClose','TestManagedPeerAuditDetectsInternalAliasWithoutChangingPermittedPackets')){if($taskRequired -cnotin $taskPassed){throw 'Actual common managed peer result missing'}}}
        foreach($taskRequired in @('TestP1FinalExactAwg3And31Parameters','TestP1FinalIndependentNetstackEventBridgeCloses','TestP1FinalUntrustedTlsCertificateHasExactSanAndNoSystemTrust')){if($P1Managed -and $taskRequired -cnotin $taskExecuted){throw 'Final fixture case missing'}}
        $taskOutput=Join-Path $taskStage 'WireGuardPeer.exe'
        & $taskGo build -trimpath -o $taskOutput ./cmd/WireGuardPeer
        if($LASTEXITCODE -ne 0){throw 'Audit peer build failed'}
    }finally{Pop-Location}
    foreach($taskFile in $taskFrozenInputs){
        if((Get-FileHash -LiteralPath (Join-Path $taskSource $taskFile.path) -Algorithm SHA256).Hash -ine $taskFile.sha256){throw 'Frozen peer source changed during build'}
    }
    foreach($taskFile in $taskManifest){
        if((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $taskFile.path) -Algorithm SHA256).Hash -ine $taskFile.sha256){throw 'Canonical peer source changed during common build'}
    }
    if((@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'cmd/WireGuardPeer') -File -Filter '*.go').Count+2) -ne $taskManifest.Count -or
        (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskRecipeSha){throw 'Peer compiler input set or recipe changed during build'}
    if((& git -C $taskUpstream rev-parse HEAD|Out-String).Trim() -cne $taskHead -or ((& git -C $taskUpstream status --porcelain|Out-String).Trim())){throw 'Pinned upstream changed during producer'}
    $taskReceipt=@{utc=[DateTime]::UtcNow.ToString('o');sha256=(Get-FileHash -LiteralPath $taskOutput -Algorithm SHA256).Hash.ToLowerInvariant();bytes=(Get-Item -LiteralPath $taskOutput).Length;packet_audit=$true;nonce_dns=$true;managed_dns=[bool]$P1Managed;final_tls_awg3=[bool]$P1Managed;compiler=$taskVersion;compilerSha256=(Get-FileHash -LiteralPath $taskGo).Hash.ToLowerInvariant();source=$taskManifest;frozenInputs=$taskFrozenInputs;upstreamCommit=$taskHead;sourceStable=$true;recipeSha256=$taskRecipeSha;tests=$taskPassed;executedTests=$taskExecuted;retainedTests=$taskRetained;network_started=$false;firewall_changed=$false;android_acceptance=$false}
    if($taskPriorPath){$taskReceipt.retainedReceipt=$taskPriorPath;$taskReceipt.retainedReceiptSha256=(Get-FileHash -LiteralPath $taskPriorPath).Hash.ToLowerInvariant();$taskReceipt.retainedLogSha256=(Get-FileHash -LiteralPath (Join-Path $taskPriorRoot 'test.jsonl')).Hash.ToLowerInvariant()}
    [IO.File]::WriteAllText((Join-Path $taskStage 'receipt.json'),($taskReceipt|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
    Write-Output "PACKET_AUDIT_PEER_STAGE=$taskStage"
}finally{
    foreach($taskName in $taskEnvironment.Keys){[Environment]::SetEnvironmentVariable($taskName,$taskEnvironment[$taskName],'Process')}
    $taskLease.Dispose()
}
