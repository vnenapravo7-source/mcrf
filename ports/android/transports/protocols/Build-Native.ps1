param([Parameter(Mandatory)][string]$PreparedSource,
 [string]$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/28.2.13676358')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-build
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
$taskPreparedRoot=(Resolve-Path -LiteralPath $PreparedSource).Path.Replace([char]92,[char]47)
if($taskPreparedRoot.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-protocols-source-[a-f0-9]{32}$'){throw 'Fresh owned protocol source required'}
$taskPrepared=Get-Content "$taskPreparedRoot/receipt.json" -Raw|ConvertFrom-Json
$taskManifest=@(Get-Content "$taskPreparedRoot/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
if($taskPrepared.state -cne 'SOURCE_PREPARED_NOT_COMPILED' -or $taskPrepared.kind -cne 'protocols' -or $taskPrepared.commit -cne '3d3d3db3a2ccef3b1a8eb71b831ed2461c54b94d' -or $taskPrepared.manifestSha256 -cne (Hash "$taskPreparedRoot/SOURCE_MANIFEST.json")){throw 'Exact original protocol SDK source required'}
$taskSource=[IO.Path]::GetFullPath($taskPrepared.module).Replace([char]92,[char]47)
if($taskSource -ine "$taskPreparedRoot/module" -or (Get-Item -LiteralPath $taskSource).LinkType){throw 'Owned ordinary module required'}
function Assert-Source {
 if(@(Get-ChildItem -LiteralPath $taskSource -File -Recurse).Count -ne $taskManifest.Count -or @(Get-ChildItem -LiteralPath $taskSource -Force -Recurse -Attributes ReparsePoint).Count){throw 'Protocol input set changed'}
 foreach($taskRow in $taskManifest){
  if([IO.Path]::IsPathRooted($taskRow.path) -or '..' -in ($taskRow.path -split '[/\\]')){throw 'Unsafe source path'}
  $taskFile=Join-Path $taskSource $taskRow.path
  if((Get-Item -LiteralPath $taskFile).Length -ne $taskRow.bytes -or (Hash $taskFile) -cne $taskRow.sha256){throw 'Frozen protocol source changed'}
 }
}
Assert-Source
$taskStage="D:/MCRF/Builds/Android/p3-protocols-native-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path "$taskStage/licenses"|Out-Null
Copy-Item "$taskPreparedRoot/SOURCE_MANIFEST.json" "$taskStage/source-manifest.json";Copy-Item "$taskPreparedRoot/receipt.json" "$taskStage/prepared-source.json"
$taskGo='D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/bin/go.exe';$taskTools="$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin"
$taskTags='with_quic,with_utls,with_naive_outbound'
$taskReceipt=@{state='BUILDING';kind='protocols';repository='Leadaxe/sing-box-lx';commit=$taskPrepared.commit;contract=1;sourceStable=$false;producerComplete=$false;sourceManifestSha256=Hash "$taskStage/source-manifest.json";producerSha256=Hash $PSCommandPath;goSha256=Hash $taskGo;tags=$taskTags;artifacts=@();cronetLibraries=@();nativeExecuted=$false;apkBuilt=$false;failures=0;skipped=0;cases=@()}
$taskReceipt.ndkSourcePropertiesSha256=Hash "$Ndk/source.properties"
$taskReceipt.clangSha256=Hash "$taskTools/clang.exe"
Save $taskReceipt "$taskStage/receipt.json"
# Compile an explicitly recorded derived copy. The shared DNS implementation
# targets the newer Scope API; this pinned SDK owns Close through its transport
# manager and has Start(stage) instead. Never patch the shared file or frozen
# prepared input in place.
$taskCompileSource="$taskStage/module"
New-Item -ItemType Directory -Path $taskCompileSource|Out-Null
foreach($row in $taskManifest){
 $to=Join-Path $taskCompileSource $row.path
 New-Item -ItemType Directory -Path (Split-Path $to) -Force|Out-Null
 Copy-Item -LiteralPath (Join-Path $taskSource $row.path) -Destination $to
 if((Hash $to) -cne $row.sha256){throw 'Derived compilation copy differs'}
}
$taskDns="$taskCompileSource/cmd/mcrf-protocols/system_server_dns.go"
$taskDnsText=[IO.File]::ReadAllText($taskDns).Replace("`r`n","`n")
$taskOldStart=@'
func (s *systemServerDNS) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	if stage == adapter.StartStateStart {
		scope.Add(s.Close)
	}
	return nil
}
'@
if([regex]::Matches($taskDnsText,[regex]::Escape($taskOldStart)).Count -ne 1){throw 'Pinned DNS lifecycle adapter shape changed'}
(Get-Item $taskDns).IsReadOnly=$false
[IO.File]::WriteAllText($taskDns,$taskDnsText.Replace($taskOldStart,'func (s *systemServerDNS) Start(stage adapter.StartStage) error { return nil }'),[Text.UTF8Encoding]::new($false))
$taskDnsTest=@'
package main
import (
 "context"
 "errors"
 "testing"
 mdns "github.com/miekg/dns"
 "github.com/sagernet/sing-box/adapter"
)
var _ adapter.DNSTransport = (*systemServerDNS)(nil)
func TestMcrfProtocolsSystemDnsLifecycle(t *testing.T) {
 d := newSystemServerDNS(context.Background(), "owned-dns")
 for _, stage := range []adapter.StartStage{adapter.StartStateInitialize, adapter.StartStateStart} {
  if err := d.Start(stage); err != nil { t.Fatal(err) }
 }
 if err := d.Close(); err != nil { t.Fatal(err) }
 if err := d.Close(); err != nil { t.Fatal(err) }
 if _, err := d.Exchange(context.Background(), new(mdns.Msg).SetQuestion("example.invalid.", mdns.TypeA)); !errors.Is(err, context.Canceled) { t.Fatalf("closed resolver: %v", err) }
}
'@
[IO.File]::WriteAllText("$taskCompileSource/cmd/mcrf-protocols/system_dns_lifecycle_test.go",$taskDnsTest,[Text.UTF8Encoding]::new($false))
$taskCompileRows=@(Get-ChildItem $taskCompileSource -Recurse -File|ForEach-Object {@{path=[IO.Path]::GetRelativePath($taskCompileSource,$_.FullName).Replace([char]92,[char]47);bytes=$_.Length;sha256=Hash $_.FullName}}|Sort-Object path)
Save $taskCompileRows "$taskStage/compiled-source-manifest.json"
$taskReceipt.compiledSourceManifestSha256=Hash "$taskStage/compiled-source-manifest.json"
$taskReceipt.sourceDerivation='pinned-sdk-Start-stage-only-DNS-lifecycle-plus-new-owner-close-case'
Save $taskReceipt "$taskStage/receipt.json"
$taskEnv=@('GOOS','GOARCH','GOARM','CGO_ENABLED','CC','CXX','GOROOT','GOENV','GOWORK','GOFLAGS','GOTOOLCHAIN','GOPROXY','GOPATH','GOMODCACHE','GOCACHE','GOTMPDIR');$taskOld=@{}
foreach($taskName in $taskEnv){$taskOld[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
$taskLinked=@{}
try{
 [Environment]::SetEnvironmentVariable('GOROOT',$null,'Process');$env:GOOS='android';$env:CGO_ENABLED='1';$env:GOENV='off';$env:GOWORK='off';$env:GOFLAGS='';$env:GOTOOLCHAIN='local';$env:GOPROXY='off'
 $env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-home';$env:GOMODCACHE="$env:GOPATH/pkg/mod";$env:GOCACHE='D:/MCRF/Projects/MCRF/work/toolchains/go-build-cache';$env:GOTMPDIR='D:/MCRF/Projects/MCRF/work/toolchains/go-tmp'
 New-Item -ItemType Directory -Path $env:GOTMPDIR -Force|Out-Null
 Push-Location $taskCompileSource
 try{& $taskGo mod verify *> "$taskStage/dependency-intake-verify.log";if($LASTEXITCODE){throw 'Read-only dependency intake integrity failed'}}finally{Pop-Location}
 $taskReceipt.dependencyIntakeLogSha256=Hash "$taskStage/dependency-intake-verify.log"
 foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
  switch($taskAbi){
   'arm64-v8a'{$env:GOARCH='arm64';$taskTriple='aarch64-linux-android29';$taskClass=2;$taskMachine=183;[Environment]::SetEnvironmentVariable('GOARM',$null,'Process')}
   'armeabi-v7a'{$env:GOARCH='arm';$env:GOARM='7';$taskTriple='armv7a-linux-androideabi29';$taskClass=1;$taskMachine=40}
   'x86_64'{$env:GOARCH='amd64';$taskTriple='x86_64-linux-android29';$taskClass=2;$taskMachine=62;[Environment]::SetEnvironmentVariable('GOARM',$null,'Process')}
  }
  $env:CC="$taskTools/clang.exe --target=$taskTriple";$env:CXX="$taskTools/clang++.exe --target=$taskTriple"
  New-Item -ItemType Directory -Path "$taskStage/$taskAbi"|Out-Null;$taskBinary="$taskStage/$taskAbi/libprotocols.so"
  Push-Location $taskCompileSource
  try{
   $taskLibraryModule="github.com/sagernet/cronet-go/lib/android_$($env:GOARCH)"
   $taskLibraryDir=(& $taskGo list -mod=readonly -m -f '{{.Dir}}' $taskLibraryModule).Trim();if($LASTEXITCODE -or !$taskLibraryDir){throw 'Original static Cronet source unavailable'}
   $taskArchive="$taskLibraryDir/libcronet.a"
   if((Get-Item -LiteralPath $taskArchive).LinkType){throw 'Linked static Cronet archive refused'}
   $taskReceipt.cronetLibraries+=@{abi=$taskAbi;module=$taskLibraryModule;directory=$taskLibraryDir;sha256=Hash $taskArchive;bytes=(Get-Item $taskArchive).Length}
   & $taskGo build -mod=readonly -trimpath -buildvcs=false -buildmode=pie "-tags=$taskTags" '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o $taskBinary ./cmd/mcrf-protocols *> "$taskStage/build-$taskAbi.log"
   if($LASTEXITCODE){throw "Original protocol module build failed: $taskAbi"}
   if((Hash $taskArchive) -cne $taskReceipt.cronetLibraries[-1].sha256){throw 'Static Cronet archive changed during compilation'}
   if($taskAbi -ceq 'x86_64'){
    & $taskGo test -mod=readonly -trimpath -buildvcs=false -c "-tags=$taskTags" '-ldflags=-checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o "$taskStage/protocols-owned.test" ./cmd/mcrf-protocols *> "$taskStage/fixture-compile.log"
    if($LASTEXITCODE){throw 'Owned original-protocol fixture compilation failed'}
   }
  }finally{Pop-Location}
  $taskBytes=[IO.File]::ReadAllBytes($taskBinary)
  if([BitConverter]::ToString($taskBytes[0..3]) -cne '7F-45-4C-46' -or $taskBytes[4] -ne $taskClass -or $taskBytes[5] -ne 1 -or [BitConverter]::ToUInt16($taskBytes,16) -ne 3 -or [BitConverter]::ToUInt16($taskBytes,18) -ne $taskMachine){throw 'Protocol ELF identity mismatch'}
  $taskSegments=@(& "$taskTools/llvm-readelf.exe" --program-headers --wide $taskBinary);if($LASTEXITCODE){throw 'ELF read failed'};$taskLoads=@($taskSegments|Where-Object {$_ -match '^\s*LOAD\s'})
  if(!$taskLoads.Count){throw 'Missing ELF load segments'}
  foreach($taskLoad in $taskLoads){if($taskLoad -notmatch '^\s*LOAD\s+0x([0-9a-fA-F]+)\s+0x([0-9a-fA-F]+).*\s+0x([0-9a-fA-F]+)\s*$'){throw 'Unknown ELF format'};if([Convert]::ToUInt64($Matches[3],16) -lt 16384 -or [Convert]::ToUInt64($Matches[1],16)%16384 -ne [Convert]::ToUInt64($Matches[2],16)%16384){throw 'Protocol ELF needs16KiB alignment'}}
  $taskReceipt.artifacts+=@{abi=$taskAbi;file="$taskAbi/libprotocols.so";bytes=(Get-Item $taskBinary).Length;sha256=Hash $taskBinary;elfChecked=$true;nativeExecuted=$false}
  $taskInfo=@(& $taskGo version -m $taskBinary);if($LASTEXITCODE){throw 'Actual linked dependency information missing'}
  $taskInfo|Set-Content -LiteralPath "$taskStage/build-info-$taskAbi.txt" -Encoding utf8
  foreach($taskLine in $taskInfo){if($taskLine -match '^\s+dep\s+(\S+)\s+(\S+)'){$taskLinked[$Matches[1]]=$Matches[2]}}
 }
 Assert-Source
 if(@(Get-ChildItem $taskCompileSource -Recurse -File).Count -ne $taskCompileRows.Count){throw 'Compiled source closure changed'}
 foreach($row in $taskCompileRows){if((Hash (Join-Path $taskCompileSource $row.path)) -cne $row.sha256){throw 'Compiled derived source changed'}}
 $taskReceipt.fixtureSha256=Hash "$taskStage/protocols-owned.test";$taskReceipt.sourceStable=$true
 # Static Cronet includes Chromium and additional C/C++ dependencies. Merely
 # copying cronet-go's674-byte GPL wrapper LICENSE is NOT a complete notice.
 # Preserve actual build inventory; a separate source-provenance legal closure
 # and all owned wire/physical-network fixtures are required before promotion.
 Save @{linkedModules=$taskLinked;complete=$false;missing=@('original SDK/Go linked-module notices','Cronet Chromium/static C third-party notices');compiledOnly=$true} "$taskStage/notice-manifest.json"
 $taskReceipt.noticeManifestSha256=Hash "$taskStage/notice-manifest.json"
 $taskReceipt.state='COMPILED_LEGAL_AND_FIXTURE_PENDING';Save $taskReceipt "$taskStage/receipt.json"
}catch{
 $taskReceipt.state='FAILED_NATIVE_RETAINED';Save $taskReceipt "$taskStage/receipt.json";throw
}finally{foreach($taskName in $taskEnv){[Environment]::SetEnvironmentVariable($taskName,$taskOld[$taskName],'Process')}}
Write-Output "P3_PROTOCOLS_COMPILED_NOT_ADMITTED=$taskStage"
