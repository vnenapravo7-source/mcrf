param([Parameter(Mandatory)][string]$PreparedProof,[Parameter(Mandatory)][string]$PreparedSha256,
      [Parameter(Mandatory)][string]$BaselineReceipt,[Parameter(Mandatory)][string]$BaselineSha256,
      [Parameter(Mandatory)][string]$HostEvidence,[Parameter(Mandatory)][string]$HostEvidenceSha256,
      [Parameter(Mandatory)][string]$OutputDirectory,
      [string]$OriginalPacketEvidence,[string]$OriginalPacketEvidenceSha256,
      [string]$Go='D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/bin/go.exe',
      [string]$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018')
$ErrorActionPreference='Stop'
function Hash([string]$Path){$item=Get-Item -LiteralPath $Path;if($item.LinkType -or $item.PSIsContainer){throw 'Regular input required'};(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 15),[Text.UTF8Encoding]::new($false))}
if((Hash $PreparedProof) -cne $PreparedSha256 -or (Hash $BaselineReceipt) -cne $BaselineSha256 -or (Hash $HostEvidence) -cne $HostEvidenceSha256){throw 'Frozen source/baseline/host gate mismatch'}
$taskPrepared=Get-Content -LiteralPath $PreparedProof -Raw|ConvertFrom-Json
$taskBaseline=Get-Content -LiteralPath $BaselineReceipt -Raw|ConvertFrom-Json
$taskEvidence=Get-Content -LiteralPath $HostEvidence -Raw|ConvertFrom-Json
if($taskPrepared.state -cne 'BS_WRAPPER_PREPARED_NOT_COMPILED' -or $taskBaseline.kind -cne 'csqtt' -or !$taskBaseline.sourceStable -or $taskBaseline.state -cne 'COMPILED_NOT_EXECUTED' -or $taskBaseline.processGroupContract -ne 1 -or $taskBaseline.accountOptionsContract -ne 1 -or $taskBaseline.turnHashPoolContract -ne 1){throw 'Reviewed full-feature baseline required'}
if($taskPrepared.baselineManifestSha256 -cne $taskBaseline.sourceManifestSha256 -or $taskEvidence.preparedSourceSha256 -cne $PreparedSha256){throw 'Evidence belongs to another source'}
if($taskPrepared.modeContract -notin @(2,3)){throw 'Fresh multi-mode source proof required'}
if($taskPrepared.modeContract -eq 3 -and $taskPrepared.privatePacketBridgeContract -ne 1){throw 'Authenticated CSQTT bridge source required'}
& python "$PSScriptRoot/prepare_bs_wrapper.py" --verify-proof $PreparedProof --sha256 $PreparedSha256
if($LASTEXITCODE){throw 'Multi-mode source proof rejected'}
function Assert-OriginalPacketEvidence {
 if($taskPrepared.originalPacketContract -eq 1){
  if(!$OriginalPacketEvidence -or !$OriginalPacketEvidenceSha256){throw 'Source-bound Android original packet execution required'}
  & python "$PSScriptRoot/original_packet_evidence.py" --receipt $OriginalPacketEvidence --sha256 $OriginalPacketEvidenceSha256 --prepared-sha256 $PreparedSha256
  if($LASTEXITCODE){throw 'Original packet Android execution rejected'}
 }elseif($OriginalPacketEvidence -or $OriginalPacketEvidenceSha256){throw 'Unexpected original packet evidence'}
}
Assert-OriginalPacketEvidence
if((Hash "$PSScriptRoot/prepare_bs_wrapper.py") -cne $taskPrepared.adapterSha256 -or (Hash "$PSScriptRoot/mcrf_bs_transport.go") -cne $taskPrepared.helperSha256){throw 'Canonical wrapper source changed'}
if((Hash $taskEvidence.log) -cne $taskEvidence.logSha256){throw 'Host log changed'}
$taskEvents=@(Get-Content -LiteralPath $taskEvidence.log|Where-Object {$_}|ForEach-Object{$_|ConvertFrom-Json})
if($taskEvidence.state -cne 'HOST_CHECKS_PASSED' -or @($taskEvents|Where-Object{$_.Action -cin @('fail','skip')}).Count -or !@($taskEvents|Where-Object{ $_.Action -ceq 'pass' -and !$_.Test}).Count){throw 'Host tests not green'}
foreach($case in @('TestBSTransportDefaultPreserved','TestBSTransportRejectsExternalPath','TestBSModeRejectsMismatch','TestBSModeParallelProcesses','TestBSModeSupersetProfiles','TestMcrfChildFailureForwardsOnlyBoundedTypedVKLimits')){if(!@($taskEvents|Where-Object{ $_.Action -ceq 'pass' -and $_.Test -ceq $case}).Count){throw 'Missing affected wrapper test'}}
foreach($case in @('Test25OmittedSRTPRetainsNativeAutoAndOldProfileShape','Test252253ExplicitSRTPReachesCLIWithoutChangingOtherOptions','Test25ExplicitInvalidSRTPJSONCannotBecomeAuto','Test25DirectLaunchAlsoRejectsUnsupportedSRTP')){if(@($taskEvents|Where-Object{ $_.Action -ceq 'pass' -and $_.Test -ceq $case}).Count -ne 1){throw 'Missing/duplicate strict SRTP wrapper test'}}
if($taskPrepared.privatePacketBridgeContract -eq 1){
 foreach($case in @('TestPrivatePacketUDPGoldenVectors','TestPrivatePacketUDPRejectsForgeryWrongKeyDirectionAndReplay','TestPrivateSOCKSUDPBoundSourceRejectsFirstSender','TestMcrfSocksUdpSourcePinAndControlEOF','TestMcrfChildPrivatePacketKeyNeverCrossesOutputBoundary')){
  if(@($taskEvents|Where-Object{$_.Action -ceq 'pass' -and $_.Test -ceq $case}).Count -ne 1){throw 'Authenticated packet bridge host test missing'}
 }
}
$taskModule=[IO.Path]::GetFullPath($taskPrepared.root)
function Assert-Source {
 $files=@(Get-ChildItem -LiteralPath $taskModule -Recurse -File|Where-Object{$_.Extension -ceq '.go' -or $_.Name -cin @('go.mod','go.sum')})
 if($files.Count -ne @($taskPrepared.sources).Count){throw 'Wrapper source set changed'}
 foreach($row in $taskPrepared.sources){if([IO.Path]::IsPathRooted($row.path) -or '..' -in ($row.path -split '[/\\]') -or (Hash (Join-Path $taskModule $row.path)) -cne $row.sha256){throw 'Wrapper source changed'}}
}
Assert-Source
if(Test-Path -LiteralPath $OutputDirectory){throw 'Fresh native stage required'}
New-Item -ItemType Directory -Path $OutputDirectory|Out-Null
$taskProof=Join-Path $OutputDirectory 'bs-wrapper-source.json';Copy-Item -LiteralPath $PreparedProof -Destination $taskProof
Save @($taskPrepared.sources) "$OutputDirectory/source-manifest.json"
Copy-Item -LiteralPath $BaselineReceipt -Destination "$OutputDirectory/baseline-receipt.json"
Copy-Item -LiteralPath $HostEvidence -Destination "$OutputDirectory/host-evidence.json"
Copy-Item -LiteralPath $taskEvidence.log -Destination "$OutputDirectory/host-tests.jsonl"
$taskResult=@{kind='csqtt';bsTransportContract=1;bsModeContract=$taskPrepared.modeContract;processGroupContract=1;accountOptionsContract=1;turnHashPoolContract=1;sourceStable=$false;preparedSourceSha256=Hash $taskProof;sourceManifestSha256=Hash "$OutputDirectory/source-manifest.json";baselineReceiptSha256=$BaselineSha256;baselineReceipt=[IO.Path]::GetFullPath($BaselineReceipt);hostEvidenceSha256=$HostEvidenceSha256;toolchainSha256=Hash $Go;producerSha256=Hash $PSCommandPath;state='BUILDING';artifacts=@()}
$taskResult.csqtt25SrtpModeContract=1 # Current canonical profile overlay + four source-bound host checks above.
$taskResult.csqttGoobyBuiltinContract=0
if($taskPrepared.rust){
 $taskRustProof=Get-Content -LiteralPath $taskPrepared.rust.path -Raw|ConvertFrom-Json
 if($taskRustProof.csqttGoobyBuiltinContract -eq 1){
  foreach($case in @('Test25GoobyPresetsReachNativeAndDisabledKeepsOriginalArgv','Test25GoobyPrivateJsonRejectsCoercionsNullAndUnknownModes')){
   if(@($taskEvents|Where-Object{$_.Action -ceq 'pass' -and $_.Test -ceq $case}).Count -ne 1){throw 'Missing/duplicate strict Gooby wrapper test'}
  }
  $taskResult.csqttGoobyBuiltinContract=1
 }
}
if($taskPrepared.originalPacketContract -eq 1){
 Copy-Item -LiteralPath $OriginalPacketEvidence -Destination "$OutputDirectory/original-packet-evidence.json"
 $taskResult.originalPacketContract=1
 $taskResult.originalPacketEvidenceSha256=$OriginalPacketEvidenceSha256
}
$taskNames=@('GOOS','GOARCH','GOARM','CGO_ENABLED','CC','GOTOOLCHAIN','GOENV','GOWORK','GOFLAGS','GOPROXY','GOPATH','GOMODCACHE','GOCACHE','GOTMPDIR','GOROOT');$taskOld=@{}
foreach($name in $taskNames){$taskOld[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
 [Environment]::SetEnvironmentVariable('GOROOT',$null,'Process');$env:GOOS='android';$env:CGO_ENABLED='1';$env:GOTOOLCHAIN='local';$env:GOENV='off';$env:GOWORK='off';$env:GOFLAGS='';$env:GOPROXY='off'
 $env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-home';$env:GOMODCACHE="$env:GOPATH/pkg/mod";$env:GOCACHE='D:/MCRF/Projects/MCRF/work/toolchains/go-build-cache';$env:GOTMPDIR='D:/MCRF/Projects/MCRF/work/toolchains/go-tmp'
 foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){
  switch($abi){'arm64-v8a'{$env:GOARCH='arm64';$triple='aarch64-linux-android29';$cls=2;$machine=183};'armeabi-v7a'{$env:GOARCH='arm';$env:GOARM='7';$triple='armv7a-linux-androideabi29';$cls=1;$machine=40};'x86_64'{$env:GOARCH='amd64';[Environment]::SetEnvironmentVariable('GOARM',$null,'Process');$triple='x86_64-linux-android29';$cls=2;$machine=62}}
  $env:CC="$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe --target=$triple"
  New-Item -ItemType Directory -Path "$OutputDirectory/$abi"|Out-Null
  $target="$OutputDirectory/$abi/libqwdtt.so"
  Push-Location $taskModule
  try{& $Go build -mod=readonly -trimpath -buildmode=pie '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o $target . *> "$OutputDirectory/build-$abi.log";if($LASTEXITCODE){throw 'BS wrapper compiler failed'}}finally{Pop-Location}
  $header=[IO.File]::ReadAllBytes($target)
  if([BitConverter]::ToString($header[0..3]) -cne '7F-45-4C-46' -or $header[4] -ne $cls -or $header[5] -ne 1 -or [BitConverter]::ToUInt16($header,16) -ne 3 -or [BitConverter]::ToUInt16($header,18) -ne $machine){throw 'BS wrapper ELF/ABI mismatch'}
  if($taskPrepared.privatePacketBridgeContract -eq 1 -and ![Text.Encoding]::Latin1.GetString($header).Contains('MCRF_CSQTT_PRIVATE_UDP_KEY_V1')){throw 'Authenticated packet bridge ELF marker missing'}
  foreach($kind in $taskPrepared.socksUdpSource.PSObject.Properties.Name){
   if(![Text.Encoding]::Latin1.GetString($header).Contains("MCRF_SOCKS_UDP_SOURCE_V1_$kind")){throw "Private SOCKS UDP ELF marker missing: $kind"}
  }
  $taskResult.artifacts+=@{abi=$abi;file="$abi/libqwdtt.so";bytes=$header.Length;sha256=Hash $target;elfChecked=$true;nativeExecuted=$false}
 }
 Assert-Source
 Assert-OriginalPacketEvidence
 & python "$PSScriptRoot/prepare_bs_wrapper.py" --verify-proof $PreparedProof --sha256 $PreparedSha256
 if($LASTEXITCODE){throw 'Multi-mode source changed during build'}
 if((Hash $PreparedProof) -cne $PreparedSha256 -or (Hash $BaselineReceipt) -cne $BaselineSha256 -or (Hash $HostEvidence) -cne $HostEvidenceSha256 -or (Hash $taskEvidence.log) -cne $taskEvidence.logSha256){throw 'Frozen build inputs changed'}
 Copy-Item -LiteralPath (Join-Path (Split-Path $BaselineReceipt) 'licenses') -Destination "$OutputDirectory/licenses" -Recurse
 $taskResult.privatePacketBridgeContract=$taskPrepared.privatePacketBridgeContract
 $taskResult.socksUdpSource=$taskPrepared.socksUdpSource
 $taskResult.sourceStable=$true;$taskResult.state='COMPILED_NOT_EXECUTED'
}catch{$taskResult.state='FAILED_PRESERVED';throw}
finally{foreach($name in $taskNames){[Environment]::SetEnvironmentVariable($name,$taskOld[$name],'Process')};Save $taskResult "$OutputDirectory/receipt.json"}
