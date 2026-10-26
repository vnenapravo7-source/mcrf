param([Parameter(Mandatory)][string]$PreparedSource)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation test
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
$taskSource=Get-Content -LiteralPath "$PreparedSource/receipt.json" -Raw|ConvertFrom-Json
if($taskSource.state -cne 'SOURCE_PREPARED_NOT_COMPILED' -or $taskSource.commit -cne '8463d3397b0a8522f92811f5c0698679ab8efc77' -or $taskSource.manifestSha256 -cne (Hash "$PreparedSource/SOURCE_MANIFEST.json")){throw 'Frozen SSTP source required'}
$taskManifest=@(Get-Content -LiteralPath "$PreparedSource/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
function Assert-Source {
 if(@(Get-ChildItem -LiteralPath $taskSource.module -Recurse -File).Count -ne $taskManifest.Count){throw 'SSTP input set changed'}
 foreach($taskRow in $taskManifest){
  if([IO.Path]::IsPathRooted($taskRow.path) -or '..' -in ($taskRow.path -split '[/\\]')){throw 'Unsafe SSTP input path'}
  $taskFile=Join-Path $taskSource.module $taskRow.path
  if((Get-Item -LiteralPath $taskFile).LinkType -or (Hash $taskFile) -cne $taskRow.sha256){throw 'Frozen SSTP source changed'}
 }
}
Assert-Source
$taskStage="D:/MCRF/Builds/Android/p4-sstp-host-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $taskStage|Out-Null
$taskProof=@{state='RUNNING';sourceManifestSha256=$taskSource.manifestSha256;failures=0;skipped=0;cases=@();androidRuntimeAccepted=$false;retainedTestsReplayed=$false}
$taskRequired=@('TestMcrfSstpStrictConfigAndSecureDefaults','TestMcrfSstpRejectAmbiguousConfigAndEndpoint','TestMcrfSstpTypedCertificateFailureIsPreserved','TestMcrfSstpCloseUnblocksStalledDisconnect','TestMcrfSstpCancelDuringPostTLSHTTPHandshake')
$taskRequired+='TestMcrfSstpOwnedTLSMSCHAPIPData'
$taskNames=@('GOOS','GOARCH','GOARM','CGO_ENABLED','GOROOT','GOENV','GOWORK','GOFLAGS','GOTOOLCHAIN','GOPROXY','GOPATH','GOMODCACHE','GOCACHE','GOTMPDIR');$taskOld=@{}
foreach($taskName in $taskNames){$taskOld[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
try {
 [Environment]::SetEnvironmentVariable('GOROOT',$null,'Process');[Environment]::SetEnvironmentVariable('GOARM',$null,'Process')
 $env:GOOS='windows';$env:GOARCH='amd64';$env:CGO_ENABLED='0';$env:GOENV='off';$env:GOWORK='off';$env:GOFLAGS='';$env:GOTOOLCHAIN='local';$env:GOPROXY='off'
 $env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-home';$env:GOMODCACHE="$env:GOPATH/pkg/mod";$env:GOCACHE='D:/MCRF/Projects/MCRF/work/toolchains/go-build-cache';$env:GOTMPDIR='D:/MCRF/Projects/MCRF/work/toolchains/go-tmp'
 New-Item -ItemType Directory -Path $env:GOTMPDIR -Force|Out-Null
 $taskGo='D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/bin/go.exe';$taskProof.toolchainSha256=Hash $taskGo
 Push-Location $taskSource.module
 try {
  & $taskGo mod verify *> "$taskStage/dependencies.log";if($LASTEXITCODE){throw 'SSTP dependency verification failed'}
  # This is a NEW engine, not a replay of already accepted MCRF checks.
  # Run its original crypto/PPP/wire cases together with the new adapter cases.
  & $taskGo test -mod=readonly '-ldflags=-checklinkname=0' -count=1 -json -timeout 180s ./... > "$taskStage/sstp-host-tests.jsonl" 2> "$taskStage/test-build.stderr.log"
  $taskExit=$LASTEXITCODE
 }finally{Pop-Location}
 $taskPassed=@()
 foreach($taskLine in Get-Content -LiteralPath "$taskStage/sstp-host-tests.jsonl"){
  $taskEvent=$taskLine|ConvertFrom-Json
  if($taskEvent.Action -ceq 'fail'){$taskProof.failures++}
  if($taskEvent.Action -ceq 'skip' -and $taskEvent.Test){$taskProof.skipped++}
  if($taskEvent.Action -ceq 'pass' -and $taskEvent.Test){$taskPassed+=$taskEvent.Test}
 }
 $taskProof.cases=@($taskPassed|Sort-Object -Unique)
 if($taskExit -ne 0 -or $taskProof.failures -ne 0 -or $taskProof.skipped -ne 0 -or @($taskRequired|Where-Object {$_ -cnotin $taskProof.cases}).Count){throw 'All original and new SSTP checks must pass; no skipped substitutions'}
 Assert-Source;$taskProof.state='PASS_P4_SSTP_HOST';$taskProof.logSha256=Hash "$taskStage/sstp-host-tests.jsonl"
}catch{$taskProof.state='FAILED_PRESERVED';$taskProof.failure=$_.Exception.Message;throw}
finally{foreach($taskName in $taskNames){[Environment]::SetEnvironmentVariable($taskName,$taskOld[$taskName],'Process')};[IO.File]::WriteAllText("$taskStage/receipt.json",($taskProof|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false));Write-Output "P4_SSTP_HOST=$taskStage"}
