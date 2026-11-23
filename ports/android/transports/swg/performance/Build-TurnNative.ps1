param([Parameter(Mandatory)][string]$PreparedSource,[Parameter(Mandatory)][string]$HostEvidence,
      [string]$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-build
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 15),[Text.UTF8Encoding]::new($false))}
$taskSource=Get-Content "$PreparedSource/receipt.json" -Raw|ConvertFrom-Json
if($taskSource.state -cne 'P3_TURN_SOURCE_PREPARED_NOT_COMPILED' -or !$taskSource.sourceStable -or $taskSource.kind -cnotin @('csqtt','qwdtt','wdtt') -or $taskSource.manifestSha256 -cne (Hash "$PreparedSource/SOURCE_MANIFEST.json")){throw 'Frozen CSQTT/QWDTT/WDTT P3 wrapper source required'}
if($taskSource.kind -eq 'wdtt' -and (!$taskSource.dependencyManifestSha256 -or (Hash "$PreparedSource/DEPENDENCY_MANIFEST.json") -cne $taskSource.dependencyManifestSha256)){throw 'Frozen WDTT dependency closure required'}
$taskKind=$taskSource.kind
$taskManifest=@(Get-Content "$PreparedSource/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
function Assert-Source {
 $taskActual=@(Get-ChildItem -LiteralPath $taskSource.module -Recurse -File)
 if($taskActual.Count -ne $taskManifest.Count){throw 'P3 source file set changed'}
 foreach($taskRow in $taskManifest){
  if([IO.Path]::IsPathRooted($taskRow.path) -or '..' -in ($taskRow.path -split '[/\\]')){throw 'Unsafe P3 source path'}
  $taskInput=Join-Path $taskSource.module $taskRow.path
  if((Get-Item -LiteralPath $taskInput).LinkType -or (Hash $taskInput) -cne $taskRow.sha256){throw 'Frozen P3 source changed'}
 }
}
Assert-Source
$taskBaseline=Get-Content -LiteralPath $taskSource.baselineReceipt -Raw|ConvertFrom-Json -AsHashtable
if((Hash $taskSource.baselineReceipt) -cne $taskSource.baselineReceiptSha256 -or $taskBaseline.kind -cne $taskKind -or !$taskBaseline.sourceStable -or $taskBaseline.state -cne 'COMPILED_NOT_EXECUTED'){throw 'Accepted baseline changed'}
if($taskSource.performanceSourceSha256 -cne (Hash "$PSScriptRoot/Apply-PerformanceSource.ps1")){throw 'Reviewed performance delta changed'}
$taskOldModule=if($taskKind -eq 'wdtt'){"$($taskBaseline.source)/wdtt/go_client"}else{"$($taskBaseline.source)/qwdtt/qwdtt"}
& "$PSScriptRoot/Apply-PerformanceSource.ps1" -ModuleSource $taskSource.module -Kind $taskKind -VerifyOnly -BaselineModule $taskOldModule -SocksUdpOverlay $taskSource.socksUdpSourceOverlay
$taskHost=Get-Content -LiteralPath $HostEvidence -Raw|ConvertFrom-Json
$taskRequired=@('TestMcrfRawMTUCeilingPreservesSmallerServerValue','TestMcrfIPRelayBuffersOnOwnedSocket','TestMcrfSOCKSAdmissionQueuesAndResumes')+$(if($taskKind -eq 'wdtt'){'TestMcrfWdtt512ClientsAndOverflowBackpressure'}else{'TestMcrfRawMTUOnActualBulkUpload'})
if($taskKind -ceq 'wdtt'){$taskRequired+=@('TestMcrfWdttRejectsPrivateConfigBeforeDNS')}
if($taskKind -ceq 'csqtt'){$taskRequired+=@('TestMcrfStreamsRetainedLegacyStatsAndExactUpgrade','TestMcrfStreamsMalformedExactDoesNotDisableStats','TestMcrfStreamsSampledClientAndChildReplacement')}
if($taskSource.socksUdpSourceOverlay){$taskRequired+=@('TestMcrfSocksUdpSourcePinAndControlEOF')}
if($taskHost.state -cne 'PASS_P3_TURN_PERFORMANCE_HOST' -or $taskHost.kind -cne $taskKind -or $taskHost.sourceManifestSha256 -cne $taskSource.manifestSha256 -or $taskHost.failures -ne 0 -or $taskHost.skipped -ne 0 -or @($taskRequired|Where-Object {$_ -cnotin $taskHost.cases}).Count){throw 'Fresh affected source-bound host tests required'}
$taskHostLog=Join-Path (Split-Path $HostEvidence) 'turn-performance-tests.jsonl'
if(!$taskHost.logSha256 -or (Hash $taskHostLog) -cne $taskHost.logSha256){throw 'Fresh performance log changed'}
$taskStage="D:/MCRF/Builds/Android/p3-turn-native-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $taskStage|Out-Null
# Preserve feature metadata, with FRESH matching source archives below. Keep
# old receipts as provenance, never relabel their native/runtime test results.
$taskResult=@{};foreach($taskKey in $taskBaseline.Keys){$taskResult[$taskKey]=$taskBaseline[$taskKey]}
foreach($taskKey in @('expansionAuthority','expansionReadinessSha256','retainedOwnerClosureSha256','currentABI')){$taskResult.Remove($taskKey)}
$taskResult.state='BUILDING';$taskResult.stage=$taskStage;$taskResult.source=$taskSource.module;$taskResult.sourceStable=$false;$taskResult.artifacts=@()
$taskResult.nativeExecuted=$false;$taskResult.apkBuilt=$false;$taskResult.p3PerformanceAuthority='human-20261023-finish-current-fixes-then-p3-p4'
$taskResult.sourceManifestSha256=$taskSource.manifestSha256;$taskResult.hostEvidenceSha256=Hash $HostEvidence
$taskResult.baselineReceiptSha256=$taskSource.baselineReceiptSha256
$taskResult.hostTests="PASS $($taskRequired.Count) new affected cases; unchanged feature evidence retained separately"
$taskResult.packageVersionCode=[int]$taskBaseline.packageVersionCode+1;$taskResult.packageVersionName="0.1.0-performance.$($taskResult.packageVersionCode)"
if($taskSource.socksUdpSourceOverlay){
 if($taskSource.socksUdpSourceOverlay.contract -ne 1 -or $taskSource.socksUdpSourceOverlay.mode -cne $taskKind){throw 'Invalid prepared SOCKS UDP source contract'}
 $taskResult.socksUdpSource=@{$taskKind=1}
 $taskResult.socksUdpSourceOverlay=$taskSource.socksUdpSourceOverlay
}
Copy-Item -LiteralPath "$PreparedSource/SOURCE_MANIFEST.json" -Destination "$taskStage/source-manifest.json"
Copy-Item -LiteralPath "$PreparedSource/receipt.json" -Destination "$taskStage/prepared-p3-turn-source.json"
if($taskKind -eq 'wdtt'){Copy-Item -LiteralPath "$PreparedSource/DEPENDENCY_MANIFEST.json" -Destination "$taskStage/dependency-manifest.json"}
Copy-Item -LiteralPath $taskSource.baselineReceipt -Destination "$taskStage/retained-baseline-receipt.json"
Copy-Item -LiteralPath $HostEvidence -Destination "$taskStage/turn-performance-host-evidence.json"
Copy-Item -LiteralPath $taskHostLog -Destination "$taskStage/turn-performance-tests.jsonl"
$taskNames=@('GOOS','GOARCH','GOARM','CGO_ENABLED','CC','GOROOT','GOTOOLCHAIN','GOENV','GOWORK','GOFLAGS','GOPROXY','GOPATH','GOMODCACHE','GOCACHE','GOTMPDIR')
$taskOldEnvironment=@{};foreach($taskName in $taskNames){$taskOldEnvironment[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
try {
 foreach($taskPair in @(@('fingerprintInputs','fingerprint-inputs'),@('turnFeatureInputs','turn-feature-inputs'),@('processGroupInputs','process-group-inputs'),@('accountBridgeInputs','account-options-inputs'))){
  if(!$taskBaseline.ContainsKey($taskPair[0])){continue};$taskRows=@()
  foreach($taskRow in $taskBaseline[$taskPair[0]]){
   $taskInput=Join-Path $taskSource.module $taskRow.file
   if($taskRow.file -cnotin @('bridge_config.go','csqtt_bridge.go','main.go','raw_netstack.go','socks5_server.go') -and (Hash $taskInput) -cne $taskRow.sha256){throw 'Retained feature source changed outside the reviewed performance/stream-metrics delta'}
   $taskTo=Join-Path "$taskStage/$($taskPair[1])" $taskRow.file
   New-Item -ItemType Directory -Path (Split-Path $taskTo) -Force|Out-Null;Copy-Item -LiteralPath $taskInput -Destination $taskTo
   $taskRows+=@{file=$taskRow.file;sha256=Hash $taskInput}
  };$taskResult[$taskPair[0]]=$taskRows
 }
 if($taskKind -ceq 'csqtt' -and $taskBaseline.accountOptionsContract -eq 1){
  $taskAdapter=Join-Path (Split-Path $taskSource.baselineReceipt) 'account-options-inputs/prepare_csqtt.py'
  if((Get-Item -LiteralPath $taskAdapter).LinkType -or (Hash $taskAdapter) -cne $taskBaseline.accountAdapterSha256){throw 'Accepted account adapter identity changed'}
  Copy-Item -LiteralPath $taskAdapter -Destination "$taskStage/account-options-inputs/prepare_csqtt.py"
 }
 if($taskKind -eq 'wdtt'){
  Copy-Item -LiteralPath "$($taskSource.module)/mcrf_wdtt.go" -Destination "$taskStage/provider-options-input.go"
  Copy-Item -LiteralPath "$($taskSource.module)/main.go" -Destination "$taskStage/masque-main-input.go"
  $taskResult.providerOptionsInputSha256=Hash "$taskStage/provider-options-input.go";$taskResult.masqueMainInputSha256=Hash "$taskStage/masque-main-input.go"
 }
 $taskLicenseRoot=Join-Path (Split-Path $taskSource.baselineReceipt) 'licenses'
 New-Item -ItemType Directory -Path "$taskStage/licenses"|Out-Null
 foreach($taskNotice in Get-ChildItem -LiteralPath $taskLicenseRoot -Recurse){if($taskNotice.LinkType){throw 'Linked accepted notice'};if($taskNotice.PSIsContainer){continue};$taskNoticeTo=Join-Path "$taskStage/licenses" ([IO.Path]::GetRelativePath($taskLicenseRoot,$taskNotice.FullName));New-Item -ItemType Directory -Path (Split-Path $taskNoticeTo) -Force|Out-Null;Copy-Item -LiteralPath $taskNotice.FullName -Destination $taskNoticeTo}
 [Environment]::SetEnvironmentVariable('GOROOT',$null,'Process')
 $env:GOOS='android';$env:CGO_ENABLED='1';$env:GOTOOLCHAIN='local';$env:GOENV='off';$env:GOWORK='off';$env:GOFLAGS='';$env:GOPROXY='off'
 $env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-home';$env:GOMODCACHE="$env:GOPATH/pkg/mod";$env:GOCACHE='D:/MCRF/Projects/MCRF/work/toolchains/go-build-cache';$env:GOTMPDIR='D:/MCRF/Projects/MCRF/work/toolchains/go-tmp'
 New-Item -ItemType Directory -Path $env:GOTMPDIR -Force|Out-Null
 $taskGo='D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/bin/go.exe';$taskTools="$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin"
 foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
  switch($taskAbi){
   'arm64-v8a'{$env:GOARCH='arm64';$taskTriple='aarch64-linux-android29';$taskClass=2;$taskMachine=183}
   'armeabi-v7a'{$env:GOARCH='arm';$env:GOARM='7';$taskTriple='armv7a-linux-androideabi29';$taskClass=1;$taskMachine=40}
   'x86_64'{$env:GOARCH='amd64';[Environment]::SetEnvironmentVariable('GOARM',$null,'Process');$taskTriple='x86_64-linux-android29';$taskClass=2;$taskMachine=62}
  }
  $env:CC="$taskTools/clang.exe --target=$taskTriple";New-Item -ItemType Directory -Path "$taskStage/$taskAbi"|Out-Null
  $taskName=if($taskKind -ceq 'csqtt'){'libqwdtt.so'}else{"lib$taskKind.so"}
  $taskOutput="$taskStage/$taskAbi/$taskName"
  Push-Location $taskSource.module
  try {& $taskGo build -mod=readonly -trimpath -buildmode=pie '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o $taskOutput . *> "$taskStage/build-$taskAbi.log";if($LASTEXITCODE){throw "TURN bridge build failed: $taskAbi"}}finally{Pop-Location}
  $taskBytes=[IO.File]::ReadAllBytes($taskOutput)
  if([BitConverter]::ToString($taskBytes[0..3]) -cne '7F-45-4C-46' -or $taskBytes[4] -ne $taskClass -or $taskBytes[5] -ne 1 -or [BitConverter]::ToUInt16($taskBytes,16) -ne 3 -or [BitConverter]::ToUInt16($taskBytes,18) -ne $taskMachine){throw 'TURN ELF ABI mismatch'}
  $taskSegments=@(& "$taskTools/llvm-readelf.exe" --program-headers --wide $taskOutput);if($LASTEXITCODE){throw 'ELF static read failed'}
  $taskLoads=@($taskSegments|Where-Object {$_ -match '^\s*LOAD\s'})
  if(!$taskLoads.Count){throw 'Missing ELF LOAD segments'}
  foreach($taskLoad in $taskLoads){
   if($taskLoad -notmatch '^\s*LOAD\s+0x([0-9a-fA-F]+)\s+0x([0-9a-fA-F]+).*\s+0x([0-9a-fA-F]+)\s*$'){throw 'Unexpected ELF LOAD format'}
   if([Convert]::ToUInt64($Matches[3],16) -lt 16384 -or [Convert]::ToUInt64($Matches[1],16)%16384 -ne [Convert]::ToUInt64($Matches[2],16)%16384){throw 'TURN ELF lacks16KiB alignment'}
  }
  $taskArtifactPath=if($taskKind -ceq 'csqtt'){$taskOutput}else{"$taskAbi/$taskName"}
  $taskResult.artifacts+=@{abi=$taskAbi;file=$taskArtifactPath;sha256=Hash $taskOutput;bytes=(Get-Item -LiteralPath $taskOutput).Length;elfChecked=$true;nativeExecuted=$false}
  if($taskKind -ceq 'csqtt'){
   # Only the shared Go IP/SOCKS adapter changed. Retain the exact accepted
   # Rust transport; never rebuild or re-label its old runtime acceptance.
   $taskNativeName='libcsqtt_transport.so'
   $taskRows=@($taskBaseline.artifacts|Where-Object {$_.abi -ceq $taskAbi -and [IO.Path]::GetFileName($_.file) -ceq $taskNativeName})
   if($taskRows.Count -ne 1 -or !$taskRows[0].elfChecked){throw 'One exact accepted Rust payload per ABI required'}
   $taskRetained=Join-Path (Split-Path $taskSource.baselineReceipt) "$taskAbi/$taskNativeName"
   $taskInfo=Get-Item -LiteralPath $taskRetained
   if($taskInfo.LinkType -or $taskInfo.PSIsContainer -or $taskInfo.Length -ne $taskRows[0].bytes -or (Hash $taskRetained) -cne $taskRows[0].sha256){throw 'Accepted Rust transport identity changed'}
   $taskCopy="$taskStage/$taskAbi/$taskNativeName";Copy-Item -LiteralPath $taskRetained -Destination $taskCopy
   $taskResult.artifacts+=@{abi=$taskAbi;file=$taskCopy;sha256=Hash $taskCopy;bytes=$taskInfo.Length;elfChecked=$true;nativeExecuted=$false;retainedBinary=$true;alignment=16384}
  }
 }
 Assert-Source;$taskResult.sourceStable=$true;$taskResult.state='COMPILED_NOT_EXECUTED'
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.failure=$_.Exception.Message;throw}
finally{foreach($taskName in $taskNames){[Environment]::SetEnvironmentVariable($taskName,$taskOldEnvironment[$taskName],'Process')};Save $taskResult "$taskStage/receipt.json";Write-Output "P3_TURN_NATIVE=$taskStage"}
