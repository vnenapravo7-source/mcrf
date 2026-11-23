param([Parameter(Mandatory)][string]$PreparedProof,[Parameter(Mandatory)][string]$PreparedSha256,
      [Parameter(Mandatory)][string]$OutputDirectory,
      [string]$Go='D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/bin/go.exe')
$ErrorActionPreference='Stop'
function Hash([string]$Path){$item=Get-Item -LiteralPath $Path;if($item.LinkType -or $item.PSIsContainer){throw 'Regular input required'};(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
if((Hash $PreparedProof) -cne $PreparedSha256){throw 'Pinned prepared source required'}
$proof=Get-Content $PreparedProof -Raw|ConvertFrom-Json
if($proof.state -cne 'BS_WRAPPER_PREPARED_NOT_COMPILED'){throw 'Prepared BS wrapper required'}
function Assert-Source {
 if((Hash $PreparedProof) -cne $PreparedSha256){throw 'Prepared proof changed'}
 $actual=@(Get-ChildItem $proof.root -Recurse -File|Where-Object{$_.Extension -ceq '.go' -or $_.Name -cin @('go.mod','go.sum')})
 if($actual.Count -ne @($proof.sources).Count){throw 'Prepared source set changed'}
 foreach($row in $proof.sources){if([IO.Path]::IsPathRooted($row.path) -or '..' -in ($row.path -split '[/\\]') -or (Hash (Join-Path $proof.root $row.path)) -cne $row.sha256){throw 'Prepared source changed'}}
}
Assert-Source
& python "$PSScriptRoot/prepare_bs_wrapper.py" --verify-proof $PreparedProof --sha256 $PreparedSha256
if($LASTEXITCODE){throw 'Multi-mode source proof rejected'}
if(Test-Path $OutputDirectory){throw 'Fresh host stage required'}
New-Item -ItemType Directory -Path $OutputDirectory|Out-Null
$result=@{state='RUNNING';preparedSourceSha256=$PreparedSha256;toolchainSha256=Hash $Go;producerSha256=Hash $PSCommandPath}
$keys=@('GOOS','GOARCH','GOARM','CGO_ENABLED','GOROOT','GOENV','GOWORK','GOFLAGS','GOTOOLCHAIN','GOPROXY','GOPATH','GOMODCACHE','GOCACHE','GOTMPDIR');$saved=@{}
foreach($key in $keys){$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
try {
 [Environment]::SetEnvironmentVariable('GOROOT',$null,'Process');[Environment]::SetEnvironmentVariable('GOARM',$null,'Process')
 $env:GOOS='windows';$env:GOARCH='amd64';$env:CGO_ENABLED='0';$env:GOENV='off';$env:GOWORK='off';$env:GOFLAGS='';$env:GOTOOLCHAIN='local';$env:GOPROXY='off'
 $env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-home';$env:GOMODCACHE="$env:GOPATH/pkg/mod";$env:GOCACHE='D:/MCRF/Projects/MCRF/work/toolchains/go-build-cache';$env:GOTMPDIR='D:/MCRF/Projects/MCRF/work/toolchains/go-tmp'
 Push-Location $proof.root
 try {
  & $Go mod verify *> "$OutputDirectory/dependencies.log";if($LASTEXITCODE){throw 'Dependency verification failed'}
  & $Go test -mod=readonly '-ldflags=-checklinkname=0' -count=1 -json -timeout 120s -run '^(TestBS|TestCSQTT|TestMcrf|TestExtended|Test25|TestNative|TestSwg|TestNewSwg|TestPrivatePacketUDPGoldenVectors$|TestPrivatePacketUDPRejectsForgeryWrongKeyDirectionAndReplay$|TestPrivateSOCKSUDPBoundSourceRejectsFirstSender$)' ./... > "$OutputDirectory/host-tests.jsonl" 2> "$OutputDirectory/host-tests.stderr.log"
  if($LASTEXITCODE){throw 'BS wrapper host tests failed'}
 }finally{Pop-Location}
 $events=@(Get-Content "$OutputDirectory/host-tests.jsonl"|Where-Object{$_}|ForEach-Object{$_|ConvertFrom-Json})
 if(@($events|Where-Object{$_.Action -cin @('fail','skip')}).Count){throw 'Failed/skipped BS wrapper checks'}
 foreach($case in @('TestBSTransportDefaultPreserved','TestBSTransportRejectsExternalPath','TestBSModeRejectsMismatch','TestBSModeParallelProcesses','TestBSModeSupersetProfiles')){if(@($events|Where-Object{$_.Action -ceq 'pass' -and $_.Test -ceq $case}).Count -ne 1){throw 'Missing/duplicate BS wrapper check'}}
 Assert-Source
 & python "$PSScriptRoot/prepare_bs_wrapper.py" --verify-proof $PreparedProof --sha256 $PreparedSha256
 if($LASTEXITCODE){throw 'Multi-mode source changed during host checks'}
 $result.log="$OutputDirectory/host-tests.jsonl";$result.logSha256=Hash $result.log;$result.state='HOST_CHECKS_PASSED'
}catch{$result.state='FAILED_PRESERVED';$result.error=$_.Exception.Message;throw}
finally{foreach($key in $keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')};[IO.File]::WriteAllText("$OutputDirectory/receipt.json",($result|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))}
