#requires -Version 7.0
param(
    [Parameter(Mandatory=$true)][string]$Distribution,
    [Parameter(Mandatory=$true)][string]$RetainedHostEvidence,
    [Parameter(Mandatory=$true)][string]$RetainedSource
)
$ErrorActionPreference='Stop'
if($args.Count){throw 'Unknown P2 packet-pipe gate argument'}
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
& (Join-Path $taskRoot 'ports/android/maintenance/Assert-HumanBatchReady.ps1') -Operation test
& (Join-Path $taskRoot 'ports/android/maintenance/Assert-HumanBatchReady.ps1') -Operation native-build
$taskCurrent=Get-Content -LiteralPath (Join-Path $taskRoot 'ports/android/coordination/current.json') -Raw|ConvertFrom-Json
if($taskCurrent.activeBatch -cne 'p2-progress-2026-10-20.json'){throw 'Only the whole P2 verification batch may use this runner'}
$taskGate=Join-Path $taskRoot 'ports/android/coordination/p2-progress-2026-10-20.json'
$taskGateSha=(Get-FileHash -LiteralPath $taskGate).Hash.ToLowerInvariant()
if($Distribution -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'){throw 'Explicit existing WSL distribution required; no install or default distro inference'}
function Ordinary([string]$Path){
    $full=[IO.Path]::GetFullPath($Path)
    if(!$full.StartsWith('D:\MCRF\',[StringComparison]::OrdinalIgnoreCase)){throw 'P2 evidence/source must stay inside D:/MCRF'}
    $item=Get-Item -LiteralPath $full -Force
    while($item){
        if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Redirected P2 input refused'}
        $parent=[IO.Path]::GetDirectoryName($item.FullName)
        if(!$parent -or $parent -eq $item.FullName){break};$item=Get-Item -LiteralPath $parent -Force
    }
    return $full
}
function Hash([string]$Path){return (Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function LinuxPath([string]$Path){
    if($Path -cnotmatch '^[dD]:\\[A-Za-z0-9_ .\\-]+$'){throw 'Ordinary D-drive WSL input required'}
    return '/mnt/d/'+$Path.Substring(3).Replace('\','/')
}
$taskPriorPath=Ordinary $RetainedHostEvidence
$taskOld=Ordinary $RetainedSource
$taskReceiptSize=(Get-Item -LiteralPath $taskPriorPath).Length
if($taskReceiptSize -lt 1 -or $taskReceiptSize -gt 1048576){throw 'Bounded retained WG receipt required'}
$taskPrior=Get-Content -LiteralPath $taskPriorPath -Raw|ConvertFrom-Json
if(!$taskPrior.passed -or !$taskPrior.sourceStable -or $taskPrior.upstreamCommit -cne '64065e6826ad82aa28429dd829c1c65f29b7f6c9'){throw 'Retained closed WG host evidence required'}
foreach($required in @('TestAmneziaVariantsEncryptedDualStack/AWG2','TestAmneziaVariantsEncryptedDualStack/AWG3','TestAmneziaVariantsEncryptedDualStack/AWG3_1','TestManagedDnsTunAcceptsBindBatchCapacity')){
    if($required -cnotin $taskPrior.tests){throw 'Retained closed WG protocol/DNS evidence incomplete'}
}
$taskGo=Join-Path $taskRoot 'work/toolchains/go1.26.8/go/bin/go.exe'
if((Hash $taskGo) -cne $taskPrior.goSha256){throw 'Retained WG compiler pin changed'}
$taskUpstream=Join-Path $taskRoot 'work/android-upstream/sing-box-lx-mcrf-clash-20261014/submodules/wireguard-go'
$taskHead=(& git -C $taskUpstream rev-parse HEAD|Out-String).Trim()
if($LASTEXITCODE -or $taskHead -cne $taskPrior.upstreamCommit -or ((& git -C $taskUpstream status --porcelain|Out-String).Trim())){throw 'WG upstream commit/pristine source changed'}
$taskSources=@(Get-ChildItem -LiteralPath $PSScriptRoot -File|Where-Object {$_.Extension -ceq '.go' -or $_.Name -in @('go.mod','go.sum')})
$taskAdded=@('route_pipe.go','route_pipe_test.go')
if(@($taskPrior.inputs.name|Select-Object -Unique).Count -ne @($taskPrior.inputs).Count){throw 'Duplicate retained WG input'}
if($taskSources.Count -ne @($taskPrior.inputs).Count+2){throw 'Review the P2 WG source closure, not only the new test file'}
foreach($row in $taskPrior.inputs){
    if($row.name -cnotmatch '^([a-z0-9_]+\.go|go\.(mod|sum))$' -or $row.sha256 -cnotmatch '^[0-9a-f]{64}$'){throw 'Invalid retained WG input identity'}
    $oldPath=Ordinary (Join-Path $taskOld $row.name)
    if((Hash $oldPath) -cne $row.sha256){throw 'Retained original source bytes do not match their receipt'}
    $now=Ordinary (Join-Path $PSScriptRoot $row.name)
    if($row.name -ceq 'start_android.go'){
        # Only the additive private-pipe entry can differ. Preserve and compare
        # every old Start/StartManaged byte modulo CRLF; no AST/compiler run here.
        $text=[IO.File]::ReadAllText($now).Replace("`r`n","`n")
        $begin=$text.IndexOf('// Separate INTERNAL per-server packet role.',[StringComparison]::Ordinal)
        $end=$text.IndexOf('// Separate opt-in extension:',[StringComparison]::Ordinal)
        if($begin -lt 0 -or $end -le $begin -or $text.LastIndexOf('// Separate INTERNAL per-server packet role.',[StringComparison]::Ordinal) -ne $begin){throw 'Unreviewed WG Android entry change'}
        if(($text.Substring(0,$begin)+$text.Substring($end)) -cne [IO.File]::ReadAllText($oldPath).Replace("`r`n","`n")){throw 'Old WG Android starts changed; retained evidence cannot be reused'}
    }elseif((Hash $now) -cne $row.sha256){throw 'Old WG source changed; expand affected verification instead of rerunning or reusing blindly'}
}
foreach($file in $taskSources){if($file.Name -cnotin @($taskPrior.inputs.name)+$taskAdded){throw 'Unknown added WG input'}}
$taskWsl=Join-Path $env:WINDIR 'System32/wsl.exe'
$taskArch=(& $taskWsl --distribution $Distribution --exec /usr/bin/uname -m|Out-String).Trim()
if($LASTEXITCODE){throw 'Existing owned Linux verification environment unavailable; do not install or change WSL'}
$taskGoArch=switch -CaseSensitive ($taskArch){'x86_64'{'amd64'}'aarch64'{'arm64'}default{throw 'Unsupported actual Linux fixture architecture'}}
$taskMutex=[Threading.Mutex]::new($false,'Local\MCRF-WG-Pipeline')
if(!$taskMutex.WaitOne(0)){throw 'WG pipeline active; continue that owned process instead'}
try {
    $taskStage=Join-Path $taskRoot ('work/wg-host-'+[guid]::NewGuid().ToString('N'))
    $taskFrozen=Join-Path $taskStage 'source';New-Item -ItemType Directory -Path $taskFrozen|Out-Null
    $taskState=[ordered]@{priority=2;passed=$false;sourceStable=$false;sourceReadySha256=$taskGateSha;producerSha256=(Hash $PSCommandPath);upstreamCommit=$taskHead;goSha256=(Hash $taskGo);retainedFrom=$taskPriorPath;retainedReceiptSha256=(Hash $taskPriorPath);retainedTests=@($taskPrior.tests);executedTests=@();tests=@();deviceTested=$false;scope='New Linux private packet-channel cases only; closed unchanged WG crypto/DNS evidence retained';cleanupPending=$false}
    function Save { [IO.File]::WriteAllText((Join-Path $taskStage 'receipt.json'),($taskState|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false)) }
    $taskState.inputs=@($taskSources|ForEach-Object {
        $path=Ordinary $_.FullName;$sha=Hash $path
        [IO.File]::Copy($path,(Join-Path $taskFrozen $_.Name),$false)
        if((Hash (Join-Path $taskFrozen $_.Name)) -cne $sha){throw 'WG source changed during freeze'}
        [pscustomobject]@{name=$_.Name;sha256=$sha}
    })
    $taskNames=@('GOPATH','GOCACHE','GOTMPDIR','GOTOOLCHAIN','GOFLAGS','GOWORK','GOENV','GOOS','GOARCH','CGO_ENABLED','GOPROXY','GOSUMDB');$taskSaved=@{}
    foreach($name in $taskNames){$taskSaved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
    try {
        $env:GOPATH=Join-Path $taskRoot 'work/toolchains/go-home';$env:GOCACHE=Join-Path $taskRoot 'work/toolchains/go-cache-wgbackend'
        $env:GOTMPDIR=Join-Path $taskStage 'tmp';New-Item -ItemType Directory -Path $env:GOTMPDIR|Out-Null
        $env:GOTOOLCHAIN='local';$env:GOFLAGS='-mod=readonly -p=4';$env:GOWORK='off';$env:GOENV='off';$env:GOOS='linux';$env:GOARCH=$taskGoArch;$env:CGO_ENABLED='0';$env:GOPROXY='off';$env:GOSUMDB='off'
        Push-Location $taskFrozen
        try {
            & $taskGo mod edit "-replace=github.com/sagernet/wireguard-go=$taskUpstream";if($LASTEXITCODE){throw 'Frozen WG pin preparation failed'}
            $taskState.frozenInputs=@($taskSources|ForEach-Object {[pscustomobject]@{name=$_.Name;sha256=(Hash (Join-Path $taskFrozen $_.Name))}})
            & $taskGo mod verify;if($LASTEXITCODE){throw 'Frozen WG dependencies changed'}
            $deps=(& $taskGo list -deps .|Out-String)
            if($LASTEXITCODE -or $deps -match '(?m)^(gvisor.dev/|github.com/(sagernet|Leadaxe)/sing-box)'){throw 'Compact WG gained a full core/netstack dependency'}
            $taskBinary=Join-Path $taskStage 'packet-pipe-tests'
            & $taskGo test -c -o $taskBinary -trimpath . 2>&1|Tee-Object -FilePath (Join-Path $taskStage 'compile.log')
            if($LASTEXITCODE){throw 'P2 packet-pipe common compilation failed; retain this attempt'}
            $taskState.binarySha256=Hash $taskBinary;$taskState.target='linux/'+$taskGoArch
            $info=[Diagnostics.ProcessStartInfo]::new($taskWsl);$info.UseShellExecute=$false;$info.CreateNoWindow=$true
            $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
            foreach($arg in @('--distribution',$Distribution,'--exec',(LinuxPath $taskBinary),'-test.run=^TestPacketPipe','-test.v','-test.count=1','-test.timeout=30s')){$info.ArgumentList.Add($arg)}
            $child=$null;$out=$null;$err=$null
            # Persist immutable process inputs BEFORE execution. Any later IO
            # failure still waits/reaps this exact launcher in finally below.
            $taskState.cleanupPending=$true;Save
            try {
                $child=[Diagnostics.Process]::Start($info)
                $out=$child.StandardOutput.ReadToEndAsync();$err=$child.StandardError.ReadToEndAsync()
                $taskState.launcherPid=$child.Id;Save
                # No timeout masquerades as process death; retain the exact
                # process and mutex. Go's bounded case timer is independent.
                while(!$child.WaitForExit(1000)){
                    try {if((Hash $taskGate) -cne $taskGateSha){$taskState.inputChanged=$true}}catch{$taskState.inputChanged=$true}
                }
            }finally{
                if($child){
                    while(!$child.WaitForExit(1000)){} # Includes source/receipt IO failure; no abandoned guest process.
                    $taskState.exitCode=$child.ExitCode;$taskState.cleanupPending=$false
                    $taskLog='';if($out){$taskLog+=$out.GetAwaiter().GetResult()};if($err){$taskLog+=$err.GetAwaiter().GetResult()}
                    $child.Dispose()
                }else{$taskState.cleanupPending=$false}
            }
            [IO.File]::WriteAllText((Join-Path $taskStage 'pipe-tests.log'),$taskLog,[Text.UTF8Encoding]::new($false))
            $taskState.testLogSha256=Hash (Join-Path $taskStage 'pipe-tests.log')
            $taskState.executedTests=@([regex]::Matches($taskLog,'(?m)^--- PASS: (TestPacketPipe[A-Za-z0-9_]+) \(')|ForEach-Object {$_.Groups[1].Value})
            $required=@('TestPacketPipeOwnershipAndBothIpFamilies','TestPacketPipeRefusesOtherOwnerTypeAndSharedBlockingFlag','TestPacketPipeNeverAdmitsTruncatedOrNonIpDatagram','TestPacketPipeCloseUnblocksActualPendingRead','TestPacketPipeCloseUnblocksActualBackpressuredWrite','TestPacketPipeEncryptedDualStack')
            $taskState.encryptedPipeProtocols=@([regex]::Matches($taskLog,'(?m)^\s+--- PASS: TestPacketPipeEncryptedDualStack/(WG|AWG3|AWG3_1) \(')|ForEach-Object {$_.Groups[1].Value})
            if((($taskState.encryptedPipeProtocols|Sort-Object)-join ',') -cne 'AWG3,AWG3_1,WG'){throw 'Actual encrypted private-pipe WG/AWG protocol cases incomplete'}
            if($taskState.exitCode -ne 0 -or $taskLog -match '(?m)^(--- (FAIL|SKIP):|FAIL)' -or (($taskState.executedTests|Sort-Object)-join ',') -cne (($required|Sort-Object)-join ',')){throw 'P2 packet-pipe cases failed/skipped/incomplete; retain actual log'}
            if($taskState.inputChanged -or (Hash $PSCommandPath) -cne $taskState.producerSha256 -or (Hash $taskBinary) -cne $taskState.binarySha256 -or (Hash $taskGate) -cne $taskGateSha -or (Hash $taskPriorPath) -cne $taskState.retainedReceiptSha256){throw 'P2 verification inputs changed'}
            foreach($row in $taskState.inputs){if((Hash (Join-Path $PSScriptRoot $row.name)) -cne $row.sha256){throw 'Canonical WG source changed'}}
            foreach($row in $taskState.frozenInputs){if((Hash (Join-Path $taskFrozen $row.name)) -cne $row.sha256){throw 'Frozen WG source changed'}}
            if(((& git -C $taskUpstream rev-parse HEAD|Out-String).Trim()) -cne $taskHead -or ((& git -C $taskUpstream status --porcelain|Out-String).Trim())){throw 'WG upstream changed during common check'}
            $taskState.tests=@($taskPrior.tests)+@($taskState.executedTests);$taskState.sourceStable=$true;$taskState.passed=$true
            Write-Output "P2_WG_PIPE_PASS=$($taskState.executedTests.Count);RETAINED=$(@($taskPrior.tests).Count);RECEIPT=$taskStage/receipt.json"
        }finally{Pop-Location}
    }finally{Save;foreach($name in $taskNames){[Environment]::SetEnvironmentVariable($name,$taskSaved[$name],'Process')}}
}finally{$taskMutex.ReleaseMutex();$taskMutex.Dispose()}
