param([Parameter(Mandatory)][string]$PreparedSource,
 [string]$AcceptedStage='D:/MCRF/Builds/Android/p3-csqtt25-native-b72a5217a8754ac0823f9ab93870a43d')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../../maintenance/Assert-HumanBatchReady.ps1" -Operation test
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
$taskSource=Get-Content "$PreparedSource/receipt.json" -Raw|ConvertFrom-Json
$AdditionalInputs=$taskSource.negotiatedTransportStage
& python "$PSScriptRoot/prepare-negotiated-workers.py" --verify-built "$AdditionalInputs/source"
if($LASTEXITCODE -or $taskSource.workerNegotiationContract -ne 1 -or $taskSource.serverTunTxQueueContract -ne 1 -or
 $taskSource.negotiatedTransportReceiptSha256 -cne (Hash "$AdditionalInputs/receipt.json") -or
 $taskSource.negotiatedSourceManifestSha256 -cne (Hash "$AdditionalInputs/source-manifest.json")){throw 'Frozen negotiated Rust transport required'}
if($taskSource.state -cne 'P3_ABI_SOURCE_PREPARED_NOT_COMPILED' -or !$taskSource.sourceStable -or
 $taskSource.manifestSha256 -cne (Hash "$PreparedSource/SOURCE_MANIFEST.json")){throw 'Frozen CS25 ABI source and negotiated transport required'}
$taskRows=@(Get-Content "$PreparedSource/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
function Assert-Inputs {
 & python "$PSScriptRoot/p3_abi_provenance.py" --prepared $PreparedSource $AcceptedStage
 if($LASTEXITCODE){throw 'Verified rebased P3 source required'}
 if(@(Get-ChildItem -LiteralPath $taskSource.module -Recurse -File).Count -ne $taskRows.Count){throw 'Frozen CS25 source set changed'}
 foreach($row in $taskRows){
  if([IO.Path]::IsPathRooted($row.path) -or '..' -in ($row.path -split '[/\\]')){throw 'Unsafe frozen source path'}
  $file=Join-Path $taskSource.module $row.path
  if((Get-Item -LiteralPath $file).LinkType -or (Hash $file) -cne $row.sha256){throw 'Frozen CS25 source changed'}
 }
 & python "$PSScriptRoot/prepare-negotiated-workers.py" --verify-built "$AdditionalInputs/source"
 if($LASTEXITCODE -or (Hash "$AdditionalInputs/receipt.json") -cne $taskSource.negotiatedTransportReceiptSha256){throw 'Negotiated ABI input changed'}
}
Assert-Inputs
$taskTop=@('Test25AdditionalAbiDescriptorsAreExactAndDistinct','Test25AdditionalAbiAndroidMustMatchWrapperArchitecture',
 'Test25AdditionalAbiHeaderInspectionRejectsMalformedOrOtherGenerations','Test25AdditionalAbiRejectsCorrectHeaderWithWrongContent','Test25AdditionalAbiActualArtifacts',
 'TestMcrfRawMTUCeilingPreservesSmallerServerValue','TestMcrfIPRelayBuffersOnOwnedSocket','TestMcrfSOCKSAdmissionQueuesAndResumes','TestMcrfRawMTUOnActualBulkUpload')
$taskRequired=@($taskTop|Where-Object {$_ -cne 'Test25AdditionalAbiActualArtifacts'})+@('Test25AdditionalAbiActualArtifacts/arm64-v8a','Test25AdditionalAbiActualArtifacts/armeabi-v7a','Test25AdditionalAbiActualArtifacts/x86_64')
$taskStage="D:/MCRF/Builds/Android/p3-csqtt25-abi-host-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $taskStage|Out-Null
$taskProof=@{state='RUNNING';sourceManifestSha256=$taskSource.manifestSha256;inputReceiptSha256=Hash "$AdditionalInputs/receipt.json";failures=0;skipped=0;cases=@();nativeExecuted=$false;retainedTestsReplayed=$false}
$taskNames=@('GOOS','GOARCH','GOARM','CGO_ENABLED','GOROOT','GOENV','GOWORK','GOFLAGS','GOTOOLCHAIN','GOPROXY','GOPATH','GOMODCACHE','GOCACHE','GOTMPDIR','MCRF_CS25_ADDITIONAL_INPUTS','MCRF_CS25_ARM64_INPUT');$taskOld=@{}
foreach($name in $taskNames){$taskOld[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
 [Environment]::SetEnvironmentVariable('GOROOT',$null,'Process');[Environment]::SetEnvironmentVariable('GOARM',$null,'Process')
 $env:GOOS='windows';$env:GOARCH='amd64';$env:CGO_ENABLED='0';$env:GOENV='off';$env:GOWORK='off';$env:GOFLAGS='';$env:GOTOOLCHAIN='local';$env:GOPROXY='off'
 $env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-home';$env:GOMODCACHE="$env:GOPATH/pkg/mod";$env:GOCACHE='D:/MCRF/Projects/MCRF/work/toolchains/go-build-cache';$env:GOTMPDIR='D:/MCRF/Projects/MCRF/work/toolchains/go-tmp'
 $env:MCRF_CS25_ADDITIONAL_INPUTS=$AdditionalInputs;$env:MCRF_CS25_ARM64_INPUT="$AdditionalInputs/arm64-v8a/libcsqtt25_transport.so"
 New-Item -ItemType Directory -Force -Path $env:GOTMPDIR|Out-Null
 $taskGo='D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/bin/go.exe';$taskProof.toolchainSha256=Hash $taskGo
 Push-Location $taskSource.module
 try {
  & $taskGo mod verify *> "$taskStage/dependencies.log";if($LASTEXITCODE){throw 'CS25 dependencies changed'}
  $filter='^('+($taskTop -join '|')+')$'
  & $taskGo test -mod=readonly '-ldflags=-checklinkname=0' '-tags=mcrf_native_inputs' -count=1 -json -timeout 180s -run $filter . ./internal/csqtt25 > "$taskStage/abi-host-tests.jsonl" 2> "$taskStage/test-build.stderr.log"
  $taskExit=$LASTEXITCODE
 }finally{Pop-Location}
 foreach($line in Get-Content "$taskStage/abi-host-tests.jsonl"){
  $event=$line|ConvertFrom-Json
  if($event.Action -ceq 'fail'){$taskProof.failures++};if($event.Action -ceq 'skip'){$taskProof.skipped++}
  if($event.Action -ceq 'pass' -and $event.Test){$taskProof.cases+=@($event.Test)}
 }
 if($taskExit -ne 0 -or $taskProof.failures -ne 0 -or $taskProof.skipped -ne 0 -or @($taskRequired|Where-Object {$_ -cnotin $taskProof.cases}).Count){throw 'All new CS25 ABI and four performance cases required; no historical replay'}
 Assert-Inputs
 if((Hash "$AdditionalInputs/receipt.json") -cne $taskProof.inputReceiptSha256){throw 'ABI input evidence changed'}
 $taskProof.logSha256=Hash "$taskStage/abi-host-tests.jsonl";$taskProof.state='PASS_P3_CS25_ABI_HOST'
}catch{$taskProof.state='FAILED_PRESERVED';$taskProof.failure=$_.Exception.Message;throw}
finally{foreach($name in $taskNames){[Environment]::SetEnvironmentVariable($name,$taskOld[$name],'Process')};[IO.File]::WriteAllText("$taskStage/receipt.json",($taskProof|ConvertTo-Json -Depth 9),[Text.UTF8Encoding]::new($false));Write-Output "P3_CS25_HOST=$taskStage"}
