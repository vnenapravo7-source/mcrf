param([Parameter(Mandatory)][string]$PreparedSource)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation test
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
$taskSource=Get-Content "$PreparedSource/receipt.json" -Raw|ConvertFrom-Json
if($taskSource.state -cne 'SOURCE_PREPARED_NOT_COMPILED' -or $taskSource.upstream.commit -cne 'a0ff70110d5e96686ab8f4c8e1c44cd09be07a75' -or $taskSource.manifestSha256 -cne (Hash "$PreparedSource/SOURCE_MANIFEST.json")){throw 'Frozen VayDNS source required'}
$taskManifest=@(Get-Content "$PreparedSource/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
function Assert-Source {
 if(@(Get-ChildItem -LiteralPath $taskSource.module -Recurse -File).Count -ne $taskManifest.Count){throw 'VayDNS input set changed'}
 foreach($taskRow in $taskManifest){if([IO.Path]::IsPathRooted($taskRow.path) -or '..' -in ($taskRow.path -split '[/\\]')){throw 'Unsafe VayDNS input'};$taskFile=Join-Path $taskSource.module $taskRow.path;if((Get-Item -LiteralPath $taskFile).LinkType -or (Hash $taskFile) -cne $taskRow.sha256){throw 'Frozen VayDNS input changed'}}
}
Assert-Source
$taskStage="D:/MCRF/Builds/Android/p3-vaydns-host-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $taskStage|Out-Null
$taskProof=@{state='RUNNING';sourceManifestSha256=$taskSource.manifestSha256;failures=0;skipped=0;cases=@();androidRuntimeAccepted=$false}
$taskNames=@('GOOS','GOARCH','GOARM','CGO_ENABLED','GOROOT','GOENV','GOWORK','GOFLAGS','GOTOOLCHAIN','GOPROXY','GOPATH','GOMODCACHE','GOCACHE','GOTMPDIR','MCRF_VAYDNS_TEST_SERVER','MCRF_VAYDNS_TEST_CLIENT');$taskOld=@{}
foreach($taskName in $taskNames){$taskOld[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
try {
 [Environment]::SetEnvironmentVariable('GOROOT',$null,'Process');[Environment]::SetEnvironmentVariable('GOARM',$null,'Process')
 $env:GOOS='windows';$env:GOARCH='amd64';$env:CGO_ENABLED='0';$env:GOENV='off';$env:GOWORK='off';$env:GOFLAGS='';$env:GOTOOLCHAIN='local';$env:GOPROXY='off'
 $env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-home';$env:GOMODCACHE="$env:GOPATH/pkg/mod";$env:GOCACHE='D:/MCRF/Projects/MCRF/work/toolchains/go-build-cache';$env:GOTMPDIR='D:/MCRF/Projects/MCRF/work/toolchains/go-tmp';New-Item -ItemType Directory -Path $env:GOTMPDIR -Force|Out-Null
 $taskGo='D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/bin/go.exe';$taskProof.toolchainSha256=Hash $taskGo
 $env:MCRF_VAYDNS_TEST_SERVER="$taskStage/owned-vaydns-peer.exe";$env:MCRF_VAYDNS_TEST_CLIENT="$taskStage/owned-vaydns-client.exe"
 Push-Location $taskSource.module
 try {
  & $taskGo mod verify *> "$taskStage/dependencies.log";if($LASTEXITCODE){throw 'VayDNS dependency verification failed'}
  & $taskGo build -mod=readonly -trimpath '-ldflags=-checklinkname=0' -o $env:MCRF_VAYDNS_TEST_SERVER ./vaydns-server *> "$taskStage/build-peer.log";if($LASTEXITCODE){throw 'Owned VayDNS peer build failed'}
  & $taskGo build -mod=readonly -trimpath '-ldflags=-checklinkname=0' -o $env:MCRF_VAYDNS_TEST_CLIENT ./cmd/mcrf-vaydns *> "$taskStage/build-client.log";if($LASTEXITCODE){throw 'Owned VayDNS client build failed'}
  & $taskGo test -mod=readonly '-ldflags=-checklinkname=0' -count=1 -json -timeout 120s -run '^TestMcrf' ./client ./cmd/mcrf-vaydns ./mcrf-integration > "$taskStage/vaydns-host-tests.jsonl" 2> "$taskStage/test-build.stderr.log"
  $taskExit=$LASTEXITCODE
 }finally{Pop-Location}
 $taskPassed=@()
 foreach($taskLine in Get-Content -LiteralPath "$taskStage/vaydns-host-tests.jsonl"){
  $taskEvent=$taskLine|ConvertFrom-Json
  if($taskEvent.Action -ceq 'fail'){$taskProof.failures++}
  if($taskEvent.Action -ceq 'skip'){$taskProof.skipped++}
  if($taskEvent.Action -ceq 'pass' -and $taskEvent.Test){$taskPassed+=$taskEvent.Test}
 }
 $taskProof.cases=@($taskPassed|Sort-Object -Unique)
 if($taskExit -ne 0 -or $taskProof.failures -ne 0 -or $taskProof.skipped -ne 0 -or $taskProof.cases.Count -ne 16){throw 'All16 NEW VayDNS checks must pass, without skipped/retained substitutions'}
 Assert-Source;$taskProof.state='PASS_P3_VAYDNS_HOST';$taskProof.logSha256=Hash "$taskStage/vaydns-host-tests.jsonl"
 $taskProof.peerSha256=Hash $env:MCRF_VAYDNS_TEST_SERVER;$taskProof.clientSha256=Hash $env:MCRF_VAYDNS_TEST_CLIENT
}catch{$taskProof.state='FAILED_PRESERVED';$taskProof.failure=$_.Exception.Message;throw}
finally{foreach($taskName in $taskNames){[Environment]::SetEnvironmentVariable($taskName,$taskOld[$taskName],'Process')};[IO.File]::WriteAllText("$taskStage/receipt.json",($taskProof|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false));Write-Output "P3_VAYDNS_HOST=$taskStage"}
