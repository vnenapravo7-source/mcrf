param([Parameter(Mandatory)][string]$PreparedSource)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../../maintenance/Assert-HumanBatchReady.ps1" -Operation test
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
$taskSource=Get-Content -LiteralPath "$PreparedSource/receipt.json" -Raw|ConvertFrom-Json
if($taskSource.state -cne 'P3_TURN_SOURCE_PREPARED_NOT_COMPILED' -or !$taskSource.sourceStable -or $taskSource.kind -cnotin @('csqtt','qwdtt','wdtt') -or $taskSource.manifestSha256 -cne (Hash "$PreparedSource/SOURCE_MANIFEST.json")){throw 'Frozen P3 performance source required'}
$taskRows=@(Get-Content -LiteralPath "$PreparedSource/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
function Assert-Source{
 if(@(Get-ChildItem -LiteralPath $taskSource.module -Recurse -File).Count -ne $taskRows.Count){throw 'Performance source set changed'}
 foreach($taskRow in $taskRows){
  if([IO.Path]::IsPathRooted($taskRow.path) -or '..' -in ($taskRow.path -split '[/\\]')){throw 'Unsafe performance source path'}
  $taskInput=Join-Path $taskSource.module $taskRow.path
  if((Get-Item -LiteralPath $taskInput).LinkType -or (Hash $taskInput) -cne $taskRow.sha256){throw 'Frozen performance source changed'}
 }
 if($taskSource.kind -eq 'wdtt'){
  if(!$taskSource.dependencyManifestSha256 -or (Hash "$PreparedSource/DEPENDENCY_MANIFEST.json") -cne $taskSource.dependencyManifestSha256){throw 'Frozen WDTT dependency manifest changed'}
  foreach($taskRow in (Get-Content "$PreparedSource/DEPENDENCY_MANIFEST.json" -Raw|ConvertFrom-Json)){
   $taskInput=Join-Path "$PreparedSource/pathprobe" $taskRow.path
   if((Get-Item -LiteralPath $taskInput).LinkType -or (Get-Item $taskInput).Length -ne $taskRow.bytes -or (Hash $taskInput) -cne $taskRow.sha256){throw 'Frozen WDTT pathprobe dependency changed'}
  }
 }
}
Assert-Source
$taskRequired=@('TestMcrfRawMTUCeilingPreservesSmallerServerValue','TestMcrfIPRelayBuffersOnOwnedSocket','TestMcrfSOCKSAdmissionQueuesAndResumes')+$(if($taskSource.kind -ceq 'wdtt'){'TestMcrfWdtt512ClientsAndOverflowBackpressure'}else{'TestMcrfRawMTUOnActualBulkUpload'})
if($taskSource.kind -ceq 'wdtt'){$taskRequired+=@('TestMcrfWdttRejectsPrivateConfigBeforeDNS')}
if($taskSource.kind -ceq 'csqtt'){$taskRequired+=@('TestMcrfStreamsRetainedLegacyStatsAndExactUpgrade','TestMcrfStreamsMalformedExactDoesNotDisableStats','TestMcrfStreamsSampledClientAndChildReplacement')}
if($taskSource.socksUdpSourceOverlay){$taskRequired+=@('TestMcrfSocksUdpSourcePinAndControlEOF')}
$taskStage="D:/MCRF/Builds/Android/p3-turn-host-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $taskStage|Out-Null
$taskProof=@{state='RUNNING';kind=$taskSource.kind;sourceManifestSha256=$taskSource.manifestSha256;failures=0;skipped=0;cases=@();nativeExecuted=$false;retainedTestsReplayed=$false}
$taskKeys=@('GOOS','GOARCH','GOARM','CGO_ENABLED','GOROOT','GOENV','GOWORK','GOFLAGS','GOTOOLCHAIN','GOPROXY','GOPATH','GOMODCACHE','GOCACHE','GOTMPDIR');$taskSaved=@{}
foreach($taskKey in $taskKeys){$taskSaved[$taskKey]=[Environment]::GetEnvironmentVariable($taskKey,'Process')}
try{
 [Environment]::SetEnvironmentVariable('GOROOT',$null,'Process');[Environment]::SetEnvironmentVariable('GOARM',$null,'Process')
 $env:GOOS='windows';$env:GOARCH='amd64';$env:CGO_ENABLED='0';$env:GOENV='off';$env:GOWORK='off';$env:GOFLAGS='';$env:GOTOOLCHAIN='local';$env:GOPROXY='off'
 $env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-home';$env:GOMODCACHE="$env:GOPATH/pkg/mod";$env:GOCACHE='D:/MCRF/Projects/MCRF/work/toolchains/go-build-cache';$env:GOTMPDIR='D:/MCRF/Projects/MCRF/work/toolchains/go-tmp'
 New-Item -ItemType Directory -Path $env:GOTMPDIR -Force|Out-Null
 $taskGo='D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/bin/go.exe';$taskProof.toolchainSha256=Hash $taskGo
 Push-Location $taskSource.module
 try{
  & $taskGo mod verify *> "$taskStage/dependencies.log";if($LASTEXITCODE){throw 'Performance dependencies changed'}
  $taskFilter='^('+($taskRequired -join '|')+')$'
  & $taskGo test -mod=readonly '-ldflags=-checklinkname=0' -count=1 -json -timeout 180s -run $taskFilter . > "$taskStage/turn-performance-tests.jsonl" 2> "$taskStage/test-build.stderr.log"
  $taskExit=$LASTEXITCODE
 }finally{Pop-Location}
 foreach($taskLine in Get-Content -LiteralPath "$taskStage/turn-performance-tests.jsonl"){
  $taskEvent=$taskLine|ConvertFrom-Json
  if($taskEvent.Action -ceq 'fail'){$taskProof.failures++};if($taskEvent.Action -ceq 'skip'){$taskProof.skipped++}
  if($taskEvent.Action -ceq 'pass' -and $taskEvent.Test){$taskProof.cases+=@($taskEvent.Test)}
 }
 if($taskExit -ne 0 -or $taskProof.failures -ne 0 -or $taskProof.skipped -ne 0 -or @($taskRequired|Where-Object {$_ -cnotin $taskProof.cases}).Count){throw 'All affected performance and startup cases required, none skipped'}
 Assert-Source;$taskProof.logSha256=Hash "$taskStage/turn-performance-tests.jsonl";$taskProof.state='PASS_P3_TURN_PERFORMANCE_HOST'
}catch{$taskProof.state='FAILED_PRESERVED';$taskProof.failure=$_.Exception.Message;throw}
finally{foreach($taskKey in $taskKeys){[Environment]::SetEnvironmentVariable($taskKey,$taskSaved[$taskKey],'Process')};[IO.File]::WriteAllText("$taskStage/receipt.json",($taskProof|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false));Write-Output "P3_TURN_HOST=$taskStage"}
