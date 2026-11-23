#requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$AndroidSerial,
    [string]$Adb="$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
if($AndroidSerial -cnotmatch '^[A-Za-z0-9_.:-]{1,64}$'){throw 'An explicit Android fixture serial is required'}
$go=Join-Path $root 'work/toolchains/go1.26.8/go/bin/go.exe'
$upstream=Join-Path $root 'work/android-upstream/sing-box-lx-mcrf-clash-20261014/submodules/wireguard-go'
$commit='64065e6826ad82aa28429dd829c1c65f29b7f6c9'
function Hash([string]$file){(Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant()}
function Verify-Upstream {
    if(((& git -C $upstream rev-parse HEAD)|Out-String).Trim() -cne $commit -or
       ((& git -C $upstream status --porcelain)|Out-String).Trim()){throw 'Pinned WG upstream differs'}
}
function Device([string[]]$command) {
    $result=& $Adb -s $AndroidSerial @command
    if($LASTEXITCODE){throw "Owned Android fixture command failed ($($command[0]))"}
    return $result
}
Verify-Upstream
if(((Device @('get-state'))|Out-String).Trim() -cne 'device' -or
   ((Device @('shell','id','-u'))|Out-String).Trim() -cne '2000'){throw 'Authorized unprivileged Android shell required'}
$abi=((Device @('shell','getprop','ro.product.cpu.abi'))|Out-String).Trim()
$arch=switch -CaseSensitive ($abi){
    'arm64-v8a' {'arm64'}
    'x86_64' {'amd64'}
    default {throw 'Use the supported 64-bit Android fixture for the full WG suite'}
}
$mutex=[Threading.Mutex]::new($false,'Local\MCRF-WG-Pipeline')
if(!$mutex.WaitOne(0)){throw 'WG pipeline already active'}
$nonce=[guid]::NewGuid().ToString('N')
$stage=Join-Path $root "work/wg-host-$nonce"
$frozen=Join-Path $stage 'source'
$temp=Join-Path $stage 'tmp'
$cache=Join-Path $root 'work/toolchains/go-cache-wgbackend'
$remote="/data/local/tmp/mcrf-wg-owner-$nonce"
$binary=Join-Path $stage 'wg-tests'
$remoteCopied=$false
$names=@('GOPATH','GOCACHE','GOTMPDIR','GOTOOLCHAIN','GOFLAGS','GOWORK','GOENV','GOOS','GOARCH','CGO_ENABLED')
$saved=@{}
foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
    New-Item -ItemType Directory -Path $frozen,$temp,$cache -Force|Out-Null
    $inputs=@(Get-ChildItem -LiteralPath $PSScriptRoot -File|Where-Object {$_.Extension -ceq '.go' -or $_.Name -in @('go.mod','go.sum')})
    $receipt=[ordered]@{state='COMPILING';passed=$false;sourceStable=$false;cleanupPending=$false;deviceTested=$true;androidAppAccepted=$false;environment='android-shell-kernel';device=$AndroidSerial;target="linux/$arch";ownerIsolationBatch=$true;upstreamCommit=$commit;producerSha256=(Hash $PSCommandPath);goSha256=(Hash $go);adbSha256=(Hash $Adb);tests=@();executedTests=@();inputs=@()}
    function Save {
        [IO.File]::WriteAllText((Join-Path $stage 'receipt.json'),($receipt|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
    }
    $receipt.inputs=@($inputs|ForEach-Object {
        if($_.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked WG source refused'}
        $hash=Hash $_.FullName
        [IO.File]::Copy($_.FullName,(Join-Path $frozen $_.Name),$false)
        if((Hash (Join-Path $frozen $_.Name)) -cne $hash){throw 'Source changed during capture'}
        [pscustomobject]@{name=$_.Name;sha256=$hash}
    })
    Save
    $env:GOPATH=Join-Path $root 'work/toolchains/go-home'
    $env:GOCACHE=$cache; $env:GOTMPDIR=$temp; $env:GOTOOLCHAIN='local'
    $env:GOFLAGS='-mod=readonly -p=4'; $env:GOWORK='off'; $env:GOENV='off'
    $env:GOOS='linux'; $env:GOARCH=$arch; $env:CGO_ENABLED='0'
    Push-Location $frozen
    try {
        & $go mod edit "-replace=github.com/sagernet/wireguard-go=$upstream"
        if($LASTEXITCODE){throw 'Failed to bind pinned WG upstream'}
        $receipt.frozenInputs=@($inputs|ForEach-Object {[pscustomobject]@{name=$_.Name;sha256=(Hash (Join-Path $frozen $_.Name))}})
        & $go mod verify
        if($LASTEXITCODE){throw 'WG dependencies do not match go.sum'}
        & $go test -c -o $binary -trimpath . 2>&1|Tee-Object -FilePath (Join-Path $stage 'compile.log')
        if($LASTEXITCODE){throw 'Full WG owner-admission fixture compilation failed'}
    }finally{Pop-Location}
    $receipt.binarySha256=Hash $binary
    $receipt.state='RUNNING'; $receipt.cleanupPending=$true; Save
    $remoteCopied=$true
    Device @('push',$binary,$remote)|Out-Null
    if((((Device @('shell','sha256sum',$remote))|Out-String).Trim() -split '\s+')[0] -cne $receipt.binarySha256){throw 'Android binary hash differs'}
    Device @('shell','chmod','700',$remote)|Out-Null
    & $Adb -s $AndroidSerial shell -T $remote '-test.v' '-test.count=1' '-test.timeout=120s' 2>&1|
        Tee-Object -FilePath (Join-Path $stage 'pipe-tests.log')
    if($LASTEXITCODE){throw 'Full WG Android kernel fixture failed'}
    Device @('shell','rm','-f',$remote)|Out-Null
    $remoteCopied=$false; $receipt.cleanupPending=$false
    $log=Get-Content -LiteralPath (Join-Path $stage 'pipe-tests.log') -Raw
    $receipt.testLogSha256=Hash (Join-Path $stage 'pipe-tests.log')
    $receipt.tests=@([regex]::Matches($log,'(?m)^\s*--- PASS: (Test[A-Za-z0-9_/]+) \(')|ForEach-Object {$_.Groups[1].Value})
    $receipt.executedTests=@($receipt.tests|Where-Object {$_ -cmatch '^TestPacketPipe[A-Za-z0-9_]+$'})
    $receipt.encryptedPipeProtocols=@([regex]::Matches($log,'(?m)^\s*--- PASS: TestPacketPipeEncryptedDualStack/(WG|AWG3|AWG3_1) \(')|ForEach-Object {$_.Groups[1].Value})
    $required=@('TestOwnerGateChecksTcpReuseUdpAndUnknownOwner','TestOwnerGateRejectsFragmentsAndOtherProtocols','TestOwnerGateIpv6UdpWithDestinationOptionsAndNewGeneration')
    if(@($required|Where-Object {$_ -cnotin $receipt.tests}).Count -or $log -match '(?m)^\s*(--- (FAIL|SKIP):|FAIL)'){throw 'Expected complete WG owner fixture cases did not pass'}
    Verify-Upstream
    foreach($row in $receipt.inputs){if((Hash (Join-Path $PSScriptRoot $row.name)) -cne $row.sha256){throw 'Canonical WG input changed'}}
    foreach($row in $receipt.frozenInputs){if((Hash (Join-Path $frozen $row.name)) -cne $row.sha256){throw 'Frozen WG input changed'}}
    if((Hash $binary) -cne $receipt.binarySha256 -or (Hash $PSCommandPath) -cne $receipt.producerSha256 -or
       (Hash $go) -cne $receipt.goSha256 -or (Hash $Adb) -cne $receipt.adbSha256){throw 'WG producer inputs changed'}
    $receipt.state='OWNED_ANDROID_OWNER_ISOLATION_PASS';$receipt.passed=$true;$receipt.sourceStable=$true;Save
    Write-Output "WG_OWNER_EVIDENCE=$stage/receipt.json"
}catch{
    if($null -ne $receipt){$receipt.state='FAILED';$receipt.failure=$_.Exception.Message;Save}
    throw
}finally{
    if($remoteCopied){
        try{
            Device @('shell','rm','-f',$remote)|Out-Null
            $receipt.cleanupPending=$false
            Save
        }catch{Write-Warning "Exact temporary Android fixture cleanup pending: $remote"}
    }
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
    $mutex.ReleaseMutex();$mutex.Dispose()
}
