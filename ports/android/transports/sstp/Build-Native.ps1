param([Parameter(Mandatory)][string]$PreparedSource,[Parameter(Mandatory)][string]$HostEvidence,
      [string]$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-build
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
$taskPrepared=Get-Content "$PreparedSource/receipt.json" -Raw|ConvertFrom-Json
$taskLock=(Get-Content "$PSScriptRoot/../transport-lock.json" -Raw|ConvertFrom-Json).sstp
if($taskPrepared.state -cne 'SOURCE_PREPARED_NOT_COMPILED' -or $taskPrepared.commit -cne $taskLock.commit -or $taskPrepared.manifestSha256 -cne (Hash "$PreparedSource/SOURCE_MANIFEST.json")){throw 'Pinned frozen SSTP source required'}
$taskManifest=@(Get-Content "$PreparedSource/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
function Assert-Source {
 if(@(Get-ChildItem -LiteralPath $taskPrepared.module -File -Recurse).Count -ne $taskManifest.Count){throw 'SSTP source set changed'}
 foreach($taskRow in $taskManifest){
  if([IO.Path]::IsPathRooted($taskRow.path) -or '..' -in ($taskRow.path -split '[/\\]')){throw 'Unsafe SSTP source path'}
  $taskInput=Join-Path $taskPrepared.module $taskRow.path
  if((Get-Item -LiteralPath $taskInput).LinkType -or (Hash $taskInput) -cne $taskRow.sha256){throw 'Frozen SSTP source changed'}
 }
}
Assert-Source
$taskRequired=@('TestMcrfSstpStrictConfigAndSecureDefaults','TestMcrfSstpRejectAmbiguousConfigAndEndpoint','TestMcrfSstpTypedCertificateFailureIsPreserved','TestMcrfSstpCloseUnblocksStalledDisconnect','TestMcrfSstpCancelDuringPostTLSHTTPHandshake')
$taskRequired+='TestMcrfSstpOwnedTLSMSCHAPIPData'
$taskHost=Get-Content -LiteralPath $HostEvidence -Raw|ConvertFrom-Json
if($taskHost.state -cne 'PASS_P4_SSTP_HOST' -or $taskHost.sourceManifestSha256 -cne $taskPrepared.manifestSha256 -or $taskHost.failures -ne 0 -or $taskHost.skipped -ne 0 -or @($taskRequired|Where-Object {$_ -cnotin $taskHost.cases}).Count){throw 'All new real-peer/lifecycle/auth checks required'}
$taskLog=Join-Path (Split-Path $HostEvidence) 'sstp-host-tests.jsonl'
if(!$taskHost.logSha256 -or (Hash $taskLog) -cne $taskHost.logSha256){throw 'Source-bound SSTP host log changed'}
$taskStage="D:/MCRF/Builds/Android/p4-sstp-native-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path "$taskStage/licenses"|Out-Null
$taskGo='D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/bin/go.exe';$taskTools="$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin"
$taskReceipt=@{state='BUILDING';kind='sstp';repository=$taskLock.repository;commit=$taskLock.commit;source=$taskPrepared.module;sourceStable=$false;sourceManifestSha256=$taskPrepared.manifestSha256;hostEvidenceSha256=Hash $HostEvidence;toolchainSha256=Hash $taskGo;artifacts=@();nativeExecuted=$false;apkBuilt=$false;producerComplete=$false;sstpContract=1;packageVersionCode=1;packageVersionName='sstp-8463d33-mcrf.1'}
Copy-Item -LiteralPath "$PreparedSource/SOURCE_MANIFEST.json" -Destination "$taskStage/source-manifest.json"
Copy-Item -LiteralPath "$PreparedSource/receipt.json" -Destination "$taskStage/prepared-source.json"
Copy-Item -LiteralPath $HostEvidence -Destination "$taskStage/host-evidence.json";Copy-Item -LiteralPath $taskLog -Destination "$taskStage/sstp-host-tests.jsonl"
$taskNames=@('GOOS','GOARCH','GOARM','CGO_ENABLED','CC','GOROOT','GOENV','GOWORK','GOFLAGS','GOTOOLCHAIN','GOPROXY','GOPATH','GOMODCACHE','GOCACHE','GOTMPDIR');$taskOld=@{}
foreach($taskName in $taskNames){$taskOld[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
$taskNotices=[Collections.Generic.List[object]]::new()
function Add-Notice([string]$From,[string]$Name,[string]$Origin){
 if([IO.Path]::IsPathRooted($Name) -or '..' -in ($Name -split '[/\\]')){throw 'Unsafe legal notice path'}
 $taskFile=Get-Item -LiteralPath $From
 if($taskFile.LinkType -or $taskFile.PSIsContainer -or $taskFile.Length -gt 2097152){throw 'Regular bounded legal notice required'}
 $taskTo=Join-Path "$taskStage/licenses" $Name;New-Item -ItemType Directory -Path (Split-Path $taskTo) -Force|Out-Null;Copy-Item -LiteralPath $From -Destination $taskTo
 $taskNotices.Add(@{file=$Name.Replace('\','/');sha256=Hash $taskTo;bytes=$taskFile.Length;origin=$Origin})
}
try {
 [Environment]::SetEnvironmentVariable('GOROOT',$null,'Process')
 $env:GOOS='android';$env:CGO_ENABLED='1';$env:GOENV='off';$env:GOWORK='off';$env:GOFLAGS='';$env:GOTOOLCHAIN='local';$env:GOPROXY='off'
 $env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-home';$env:GOMODCACHE="$env:GOPATH/pkg/mod";$env:GOCACHE='D:/MCRF/Projects/MCRF/work/toolchains/go-build-cache';$env:GOTMPDIR='D:/MCRF/Projects/MCRF/work/toolchains/go-tmp';New-Item -ItemType Directory -Path $env:GOTMPDIR -Force|Out-Null
 $taskLinked=@{}
 foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
  switch($taskAbi){
   'arm64-v8a'{$env:GOARCH='arm64';$taskTriple='aarch64-linux-android29';$taskClass=2;$taskMachine=183}
   'armeabi-v7a'{$env:GOARCH='arm';$env:GOARM='7';$taskTriple='armv7a-linux-androideabi29';$taskClass=1;$taskMachine=40}
   'x86_64'{$env:GOARCH='amd64';[Environment]::SetEnvironmentVariable('GOARM',$null,'Process');$taskTriple='x86_64-linux-android29';$taskClass=2;$taskMachine=62}
  }
  $env:CC="$taskTools/clang.exe --target=$taskTriple";New-Item -ItemType Directory -Path "$taskStage/$taskAbi"|Out-Null;$taskBinary="$taskStage/$taskAbi/libsstp.so"
  Push-Location $taskPrepared.module
  try {& $taskGo build -mod=readonly -trimpath -buildmode=pie '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o $taskBinary . *> "$taskStage/build-$taskAbi.log";if($LASTEXITCODE){throw "SSTP build failed: $taskAbi"}}finally{Pop-Location}
  $taskBytes=[IO.File]::ReadAllBytes($taskBinary)
  if([BitConverter]::ToString($taskBytes[0..3]) -cne '7F-45-4C-46' -or $taskBytes[4] -ne $taskClass -or $taskBytes[5] -ne 1 -or [BitConverter]::ToUInt16($taskBytes,16) -ne 3 -or [BitConverter]::ToUInt16($taskBytes,18) -ne $taskMachine){throw 'SSTP ELF identity mismatch'}
  $taskSegments=@(& "$taskTools/llvm-readelf.exe" --program-headers --wide $taskBinary);if($LASTEXITCODE){throw 'ELF read failed'};$taskLoads=@($taskSegments|Where-Object {$_ -match '^\s*LOAD\s'})
  if(!$taskLoads.Count){throw 'Missing ELF load segments'}
  foreach($taskLoad in $taskLoads){if($taskLoad -notmatch '^\s*LOAD\s+0x([0-9a-fA-F]+)\s+0x([0-9a-fA-F]+).*\s+0x([0-9a-fA-F]+)\s*$'){throw 'Unknown ELF format'};if([Convert]::ToUInt64($Matches[3],16) -lt 16384 -or [Convert]::ToUInt64($Matches[1],16)%16384 -ne [Convert]::ToUInt64($Matches[2],16)%16384){throw 'SSTP ELF is not16KiB aligned'}}
  $taskReceipt.artifacts+=@{abi=$taskAbi;file="$taskAbi/libsstp.so";bytes=(Get-Item -LiteralPath $taskBinary).Length;sha256=Hash $taskBinary;elfChecked=$true;nativeExecuted=$false}
  # Build info is read, never execute a target ELF to discover dependencies.
  $taskInfo=@(& $taskGo version -m $taskBinary);if($LASTEXITCODE){throw 'Native build info unavailable'}
  foreach($taskLine in $taskInfo){if($taskLine -match '^\s+dep\s+(\S+)\s+(\S+)'){$taskLinked[$Matches[1]]=$Matches[2]}}
 }
 Add-Notice "$($taskPrepared.module)/LICENSE" 'SSTP-LICENSE' 'ttrroyy/qThroneMobile SwiftSSTP MIT'
 $taskGoRoot=Split-Path (Split-Path $taskGo);Add-Notice "$taskGoRoot/LICENSE" 'go/LICENSE' 'Go toolchain'
 Push-Location $taskPrepared.module
 try {$taskModules=@(& $taskGo list -mod=readonly -m -f '{{.Path}}|{{if .Replace}}{{.Replace.Dir}}|{{.Replace.Path}}@{{.Replace.Version}}{{else}}{{.Dir}}|{{.Path}}@{{.Version}}{{end}}' all);if($LASTEXITCODE){throw 'Dependency legal origins unavailable'}}finally{Pop-Location}
 if(!$taskLinked.Count){throw 'Missing linked dependency inventory'}
 $taskMissing=@();$taskCache=[IO.Path]::GetFullPath($env:GOMODCACHE)
 foreach($taskPath in @($taskLinked.Keys|Sort-Object)){
  $taskMatch=@($taskModules|Where-Object {($_ -split '\|',3)[0] -ceq $taskPath})
  if($taskMatch.Count -ne 1){$taskMissing+=$taskPath;continue}
  $taskParts=$taskMatch[0] -split '\|',3
  if($taskParts.Count -ne 3 -or !$taskParts[1] -or !$taskParts[2]){throw 'Incomplete legal source identity'}
  $taskDir=[IO.Path]::GetFullPath($taskParts[1])
  if(!$taskDir.StartsWith($taskCache+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Dependency legal origin escaped approved cache'}
  $taskNode=Get-Item -LiteralPath $taskDir;while($taskNode.FullName -ine $taskCache){if($taskNode.LinkType){throw 'Linked legal source origin'};$taskNode=$taskNode.Parent}
  $taskLegal=@(Get-ChildItem -LiteralPath $taskDir -File|Where-Object {$_.Name -match '^(LICENSE|LICENCE|COPYING|NOTICE|AUTHORS)([._-].*)?$'})
  if(!$taskLegal.Count){$taskMissing+=$taskPath;continue}
  $taskId=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($taskPath))).ToLowerInvariant()
  foreach($taskLegalFile in $taskLegal){Add-Notice $taskLegalFile.FullName "dependencies/$taskId/$($taskLegalFile.Name)" $taskParts[2]}
 }
 Save @{files=@($taskNotices.ToArray());linkedModules=$taskLinked.Count;missingModules=$taskMissing;binaryExecuted=$false} "$taskStage/notice-manifest.json"
 Assert-Source;$taskReceipt.sourceStable=$true;$taskReceipt.producerComplete=$true
 $taskReceipt.state=if($taskMissing.Count){'COMPILED_NOTICES_PENDING'}else{'COMPILED_NOT_EXECUTED'}
}catch{$taskReceipt.state='FAILED_PRESERVED';$taskReceipt.failure=$_.Exception.Message;throw}
finally{foreach($taskName in $taskNames){[Environment]::SetEnvironmentVariable($taskName,$taskOld[$taskName],'Process')};Save $taskReceipt "$taskStage/receipt.json";Write-Output "P4_SSTP_NATIVE=$taskStage"}
