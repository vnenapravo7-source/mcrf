param([Parameter(Mandatory=$true)][string]$PreparedSource,
      [Parameter(Mandatory=$true)][string]$HostEvidence,
      [string]$AcceptedStage='D:/MCRF/Builds/Android/p3-csqtt25-native-b72a5217a8754ac0823f9ab93870a43d',
      [string]$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-build
function Hash([string]$path){(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}
function Save($value,[string]$path){[IO.File]::WriteAllText($path,($value|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
$baseJson=& python "$PSScriptRoot/p3_abi_provenance.py" --prepared $PreparedSource $AcceptedStage
if($LASTEXITCODE){throw 'Verified rebased P3 source required'}
$base=$baseJson|ConvertFrom-Json
$source=Get-Content "$PreparedSource/receipt.json" -Raw|ConvertFrom-Json
$AdditionalInputs=$source.negotiatedTransportStage
& python "$PSScriptRoot/prepare-negotiated-workers.py" --verify-built "$AdditionalInputs/source"
if($LASTEXITCODE -or $source.workerNegotiationContract -ne 1 -or $source.serverTunTxQueueContract -ne 1 -or
 $source.negotiatedTransportReceiptSha256 -cne (Hash "$AdditionalInputs/receipt.json") -or
 $source.negotiatedSourceManifestSha256 -cne (Hash "$AdditionalInputs/source-manifest.json")){throw 'Frozen negotiated Rust transport required'}
$additional=Get-Content "$AdditionalInputs/receipt.json" -Raw|ConvertFrom-Json
$hostProof=Get-Content -LiteralPath $HostEvidence -Raw|ConvertFrom-Json
if($source.state -cne 'P3_ABI_SOURCE_PREPARED_NOT_COMPILED' -or !$source.sourceStable -or !$source.ownerCommandUnchanged -or $source.manifestSha256 -cne (Hash "$PreparedSource/SOURCE_MANIFEST.json")){throw 'Frozen P3 ABI source required'}
if($additional.state -cne 'COMPILED_NOT_EXECUTED' -or $additional.nativeExecuted){throw 'Rebuilt unexecuted negotiated transport required'}
$required=@('Test25AdditionalAbiDescriptorsAreExactAndDistinct','Test25AdditionalAbiAndroidMustMatchWrapperArchitecture','Test25AdditionalAbiHeaderInspectionRejectsMalformedOrOtherGenerations','Test25AdditionalAbiRejectsCorrectHeaderWithWrongContent','Test25AdditionalAbiActualArtifacts/arm64-v8a','Test25AdditionalAbiActualArtifacts/armeabi-v7a','Test25AdditionalAbiActualArtifacts/x86_64')
$required+=@('TestMcrfRawMTUCeilingPreservesSmallerServerValue','TestMcrfIPRelayBuffersOnOwnedSocket','TestMcrfSOCKSAdmissionQueuesAndResumes','TestMcrfRawMTUOnActualBulkUpload')
if($hostProof.state -cne 'PASS_P3_CS25_ABI_HOST' -or $hostProof.sourceManifestSha256 -cne $source.manifestSha256 -or $hostProof.inputReceiptSha256 -cne (Hash "$AdditionalInputs/receipt.json") -or $hostProof.failures -ne 0 -or $hostProof.skipped -ne 0 -or @($required|Where-Object {$_ -cnotin $hostProof.cases}).Count){throw 'All new loader/header/pinned-input checks required; no retained/skipped substitution'}
if(!$hostProof.logSha256 -or (Hash (Join-Path (Split-Path $HostEvidence) 'abi-host-tests.jsonl')) -cne $hostProof.logSha256){throw 'Fresh loader execution log must match host evidence'}
$manifest=Get-Content "$PreparedSource/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json
function Assert-Source{
 & python "$PSScriptRoot/p3_abi_provenance.py" --prepared $PreparedSource $AcceptedStage
 if($LASTEXITCODE){throw 'Frozen rebased P3 source changed'}
}
Assert-Source
if(!$source.performanceSourceSha256 -or $source.performanceSourceSha256 -cne (Hash "$PSScriptRoot/../performance/Apply-PerformanceSource.ps1")){throw 'Prepared performance delta source changed'}
# Assert-Source proves exact preservation of the already-applied performance delta.
$pins=@{};foreach($row in $additional.artifacts){$pins[$row.abi]=$row.sha256}
$stage="D:/MCRF/Builds/Android/p3-csqtt25-native-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $stage|Out-Null
$baseline=Get-Content "$AcceptedStage/receipt.json" -Raw|ConvertFrom-Json
if($baseline.processGroupContract -ne 1 -or $baseline.turnHashPoolContract -ne 1){throw 'Do not regress existing process ownership or unlimited hash-pool capabilities'}
$receipt=[ordered]@{state='BUILDING';kind='csqtt25';source=$source.module;sourceStable=$false;sourceManifestSha256=$source.manifestSha256;hostEvidenceSha256=Hash $HostEvidence;artifacts=@();nativeExecuted=$false;apkBuilt=$false;packageVersionCode=([int]$baseline.packageVersionCode+1);packageVersionName="2.5.1-swgpanel-abi.$([int]$baseline.packageVersionCode+1)";retainedOwnerReceiptSha256=Hash "$AcceptedStage/receipt.json";ownerCommandUnchanged=$true}
$receipt.processGroupContract=1;$receipt.turnHashPoolContract=1;$receipt.p3AbiAuthority='human-20261023-finish-current-fixes-then-p3-p4'
$receipt.workerNegotiationContract=1;$receipt.negotiatedTransportStage=$AdditionalInputs
$receipt.serverTunTxQueueContract=1
$receipt.preparedSource=[IO.Path]::GetFullPath($PreparedSource)
$receipt.preparedReceiptSha256=Hash "$PreparedSource/receipt.json"
$receipt.producerSha256=Hash $PSCommandPath
$receipt.negotiatedTransportReceiptSha256=$source.negotiatedTransportReceiptSha256
$receipt.negotiatedSourceManifestSha256=$source.negotiatedSourceManifestSha256
$receipt.baselineSourceManifestSha256=$source.baselineSourceManifestSha256
$receipt.performanceAlreadyApplied=$true
$receipt.provenanceVerifierSha256=$source.provenanceVerifierSha256
$names=@('GOOS','GOARCH','GOARM','CGO_ENABLED','CC','GOPROXY','GOTOOLCHAIN','GOCACHE','GOPATH','GOTMPDIR');$envs=@{};foreach($name in $names){$envs[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
 $env:GOOS='android';$env:CGO_ENABLED='1';$env:GOPROXY='off';$env:GOTOOLCHAIN='local';$env:GOCACHE='D:/MCRF/Projects/MCRF/work/toolchains/go-build-cache';$env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-home';$env:GOTMPDIR='D:/MCRF/Projects/MCRF/work/toolchains/go-tmp'
 New-Item -ItemType Directory -Force -Path $env:GOTMPDIR|Out-Null
 Copy-Item -LiteralPath "$PreparedSource/SOURCE_MANIFEST.json" -Destination "$stage/source-manifest.json"
 Copy-Item -LiteralPath $HostEvidence -Destination "$stage/new-abi-host-evidence.json"
 Copy-Item -LiteralPath (Join-Path (Split-Path $HostEvidence) 'abi-host-tests.jsonl') -Destination "$stage/abi-host-tests.jsonl"
 Copy-Item -LiteralPath "$AdditionalInputs/receipt.json" -Destination "$stage/abi-input-receipt.json"
 foreach($pair in @(@('processGroupInputs','process-group-inputs'),@('turnFeatureInputs','turn-feature-inputs'))){
  $rows=@();foreach($row in $baseline.($pair[0])){
   $file=Join-Path $source.module $row.file
   $expected=if($pair[0] -ceq 'turnFeatureInputs' -and $row.file -ceq 'internal/csqtt25/profile.go'){Hash "$PSScriptRoot/profile.go"}else{$row.sha256}
   if((Hash $file) -cne $expected){throw 'Owner/hash-pool source differs from accepted baseline or exact version overlay'}
   $to=Join-Path "$stage/$($pair[1])" $row.file;New-Item -ItemType Directory -Force -Path (Split-Path $to)|Out-Null;Copy-Item -LiteralPath $file -Destination $to
   $rows+=@{file=$row.file;sha256=Hash $file}
  };$receipt.($pair[0])=$rows
 }
 Copy-Item -LiteralPath "$($base.module)/internal/csqtt25/runtime.go" -Destination "$stage/retained-native-runtime.go"
 Copy-Item -LiteralPath "$($source.module)/internal/csqtt25/runtime.go" -Destination "$stage/abi-runtime-input.go"
 Copy-Item -LiteralPath "$($base.module)/csqtt_bridge.go" -Destination "$stage/retained-performance-bridge.go"
 Copy-Item -LiteralPath "$($source.module)/csqtt_bridge.go" -Destination "$stage/performance-bridge-input.go"
 Copy-Item -LiteralPath "$AcceptedStage/retained-p3-owner-evidence.json" -Destination "$stage/retained-p3-owner-evidence.json"
 $origin=@{authority=$receipt.p3AbiAuthority;baselineReceipt=$source.baselineReceipt;baselineReceiptSha256=$source.baselineReceiptSha256;sourceManifestSha256=$source.manifestSha256;oldRuntimeSha256=Hash "$stage/retained-native-runtime.go";newRuntimeSha256=Hash "$stage/abi-runtime-input.go";retainedEvidenceSha256=Hash "$stage/retained-p3-owner-evidence.json";hostEvidenceSha256=Hash $HostEvidence;newOwnerExecution=$false;newAbiRuntimeAccepted=$false}
 $origin.oldPerformanceBridgeSha256=Hash "$stage/retained-performance-bridge.go"
 $origin.newPerformanceBridgeSha256=Hash "$stage/performance-bridge-input.go"
 $origin.performanceAlreadyApplied=$true
 $origin.baselineSourceManifestSha256=$source.baselineSourceManifestSha256
 $origin.baselinePreparedReceipt=$source.baselinePreparedReceipt
 $origin.baselinePreparedSha256=$source.baselinePreparedSha256
 if($origin.oldPerformanceBridgeSha256 -cne $origin.newPerformanceBridgeSha256){throw 'Retained performance bridge changed'}
 Save $origin "$stage/p3-abi-origin.json";$receipt.p3AbiOriginSha256=Hash "$stage/p3-abi-origin.json"
 $go='D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/bin/go.exe';$clang="$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
 foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){
  Assert-Source
  $folder="$stage/$abi";New-Item -ItemType Directory -Path $folder|Out-Null
  switch($abi){'arm64-v8a'{$env:GOARCH='arm64';$triple='aarch64-linux-android29';$machine=183;$class=2}
   'armeabi-v7a'{$env:GOARCH='arm';$env:GOARM='7';$triple='armv7a-linux-androideabi29';$machine=40;$class=1}
   'x86_64'{$env:GOARCH='amd64';[Environment]::SetEnvironmentVariable('GOARM',$null,'Process');$triple='x86_64-linux-android29';$machine=62;$class=2}}
  $env:CC="$clang --target=$triple";$output="$folder/libcsqtt25.so"
  Push-Location $source.module;try{& $go build -mod=readonly -trimpath -buildmode=pie '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o $output . *> "$stage/build-$abi.log";if($LASTEXITCODE){throw "Wrapper build failed: $abi"}}finally{Pop-Location}
  $elf=[IO.File]::ReadAllBytes($output);if([BitConverter]::ToString($elf[0..3]) -cne '7F-45-4C-46' -or $elf[4] -ne $class -or [BitConverter]::ToUInt16($elf,18) -ne $machine){throw 'Wrapper ELF ABI mismatch'}
  & python "$PSScriptRoot/prepare-negotiated-workers.py" --verify-built "$AdditionalInputs/source"
  if($LASTEXITCODE -or (Hash "$AdditionalInputs/receipt.json") -cne $source.negotiatedTransportReceiptSha256){throw 'Negotiated native receipt changed during wrapper build'}
  $transport="$AdditionalInputs/$abi/libcsqtt25_transport.so"
  if((Hash $transport) -cne $pins[$abi]){throw 'Pinned native transport changed'}
  Copy-Item -LiteralPath $transport -Destination "$folder/libcsqtt25_transport.so"
  if((Hash "$folder/libcsqtt25_transport.so") -cne $pins[$abi]){throw 'Copied negotiated transport changed'}
  foreach($name in @('libcsqtt25.so','libcsqtt25_transport.so')){$file="$folder/$name";$receipt.artifacts+=@{abi=$abi;file=$file;bytes=(Get-Item -LiteralPath $file).Length;sha256=Hash $file;executed=$false}}
 }
 New-Item -ItemType Directory -Path "$stage/licenses"|Out-Null
 Copy-Item -LiteralPath "$AdditionalInputs/CSQTT-LICENSE" -Destination "$stage/licenses/CSQTT-LICENSE"
 Copy-Item -LiteralPath "$AcceptedStage/licenses/qwdtt-LICENSE" -Destination "$stage/licenses/qwdtt-LICENSE"
 Copy-Item -LiteralPath "$PSScriptRoot/swg-panel-reference.json" -Destination "$stage/licenses/swg-panel-reference.json"
 Assert-Source;$receipt.sourceStable=$true;$receipt.state='COMPILED_NOT_EXECUTED'
 Save $receipt "$stage/receipt.json"
 & python "$PSScriptRoot/p3_abi_provenance.py" --native $stage
 if($LASTEXITCODE){throw 'Final native provenance closure failed'}
}catch{$receipt.state='FAILED_PRESERVED';$receipt.error=$_.Exception.Message;throw}finally{foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$envs[$name],'Process')};Save $receipt "$stage/receipt.json";Write-Output "P3_CS25_NATIVE=$stage"}
