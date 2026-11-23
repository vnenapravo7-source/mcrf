param([Parameter(Mandatory)][string]$Stage,
      [string]$Go='D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/bin/go.exe',
      [string]$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018')
$ErrorActionPreference='Stop'
function Hash([string]$Path){$item=Get-Item -LiteralPath $Path;if($item.LinkType -or $item.PSIsContainer){throw 'Regular input required'};(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 14),[Text.UTF8Encoding]::new($false))}
function Assert-Source {
 & py -3.14 "$PSScriptRoot/../transports/swg/csqtt25/aligned_wrapper.py" --verify-source $Stage
 if($LASTEXITCODE){throw 'Aligned loader source provenance changed'}
}
if(Test-Path "$Stage/receipt.json"){throw 'Fresh uncompiled loader stage required'}
Assert-Source
$proof=Get-Content "$Stage/loader-source-proof.json" -Raw|ConvertFrom-Json
$base=Get-Content "$Stage/retained-receipt.json" -Raw|ConvertFrom-Json
$versions=$proof.versionStage
$result=@{state='BUILDING';kind='csqtt25';source="$Stage/module";sourceStable=$false;compiledABIs=@('armeabi-v7a');reusedABIs=@('arm64-v8a','x86_64');
 sourceManifestSha256=Hash "$Stage/source-manifest.json";sourceProofSha256=Hash "$Stage/loader-source-proof.json";producerSha256=Hash $PSCommandPath;
 toolchainSha256=Hash $Go;processGroupContract=1;turnHashPoolContract=1;nativeExecuted=$false;apkBuilt=$false;artifacts=@()}
$keys=@('GOOS','GOARCH','GOARM','CGO_ENABLED','CC','GOROOT','GOENV','GOWORK','GOFLAGS','GOTOOLCHAIN','GOPROXY','GOPATH','GOMODCACHE','GOCACHE','GOTMPDIR','MCRF_SWG_ALIGNED_INPUTS','MCRF_SWG_UPSTREAM_INPUTS');$saved=@{}
foreach($key in $keys){$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
try {
 [Environment]::SetEnvironmentVariable('GOROOT',$null,'Process');[Environment]::SetEnvironmentVariable('GOARM',$null,'Process')
 $env:GOOS='windows';$env:GOARCH='amd64';$env:CGO_ENABLED='0';$env:GOENV='off';$env:GOWORK='off';$env:GOFLAGS='';$env:GOTOOLCHAIN='local';$env:GOPROXY='off'
 $env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-home';$env:GOMODCACHE="$env:GOPATH/pkg/mod";$env:GOCACHE='D:/MCRF/Projects/MCRF/work/toolchains/go-build-cache';$env:GOTMPDIR='D:/MCRF/Projects/MCRF/work/toolchains/go-tmp'
 $env:MCRF_SWG_ALIGNED_INPUTS=$versions;$env:MCRF_SWG_UPSTREAM_INPUTS=(Get-Content "$versions/receipt.json" -Raw|ConvertFrom-Json).upstreamStage
 Push-Location "$Stage/module"
 try {
  & $Go mod verify *> "$Stage/dependencies.log";if($LASTEXITCODE){throw 'Loader dependency verification failed'}
  & $Go test -mod=readonly '-ldflags=-checklinkname=0' -tags=mcrf_native_inputs -count=1 -json -timeout 120s -run '^(TestAlignedSwg|TestSwgVersions|TestNewSwg)' ./internal/csqtt25 > "$Stage/host-tests.jsonl" 2> "$Stage/host-tests.stderr.log"
  if($LASTEXITCODE){throw 'Actual loader input host checks failed'}
 }finally{Pop-Location}
 $events=@(Get-Content "$Stage/host-tests.jsonl"|Where-Object{$_}|ForEach-Object{$_|ConvertFrom-Json})
 $passed=@($events|Where-Object{$_.Action -ceq 'pass' -and $_.Test}|ForEach-Object{$_.Test})
 $required=@('TestAlignedSwgNativeActualArtifacts/arm64-v8a','TestAlignedSwgNativeActualArtifacts/armeabi-v7a','TestAlignedSwgNativeActualArtifacts/x86_64','TestSwgVersionsSelectExactTransportWithoutLegacyFallback','TestNewSwgProfilesKeepImplementationAndRejectDuplicateOrWrongTypes','TestNewSwgNativePinsAreDistinctFrom251ForEveryAbi','TestNewSwgGateRejectsOldOrUnknownContentUnderNewFilename')
 if(@($events|Where-Object{$_.Action -cin @('fail','skip')}).Count -or @($required|Where-Object{$_ -cnotin $passed}).Count){throw 'Loader host cases missing/failed/skipped'}
 Save @{state='HOST_CHECKS_PASSED';sourceManifestSha256=$result.sourceManifestSha256;logSha256=Hash "$Stage/host-tests.jsonl";cases=$passed} "$Stage/host-evidence.json"
 $result.hostEvidenceSha256=Hash "$Stage/host-evidence.json"
 Assert-Source
 $env:GOOS='android';$env:GOARCH='arm';$env:GOARM='7';$env:CGO_ENABLED='1';$env:CC="$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe --target=armv7a-linux-androideabi29"
 New-Item -ItemType Directory -Path "$Stage/armeabi-v7a"|Out-Null
 $target="$Stage/armeabi-v7a/libcsqtt25.so"
 Push-Location "$Stage/module"
 try {& $Go build -mod=readonly -trimpath -buildmode=pie '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o $target . *> "$Stage/build-armeabi-v7a.log";if($LASTEXITCODE){throw 'Aligned ARMv7 loader build failed'}}finally{Pop-Location}
 foreach($row in $base.artifacts){
  $name=[IO.Path]::GetFileName($row.file.Replace([char]92,[char]47));$dest="$Stage/$($row.abi)/$name"
  if($row.abi -cne 'armeabi-v7a' -or $name -cne 'libcsqtt25.so'){
   New-Item -ItemType Directory -Path (Split-Path $dest) -Force|Out-Null
   Copy-Item "$($proof.retainedStage)/$($row.abi)/$name" $dest
   if((Hash $dest) -cne $row.sha256){throw 'Retained ABI copy changed'}
  }
  $result.artifacts+=@{abi=$row.abi;file="$($row.abi)/$name";bytes=(Get-Item $dest).Length;sha256=Hash $dest;executed=$false;reused=($row.abi -cne 'armeabi-v7a' -or $name -cne 'libcsqtt25.so')}
 }
 Copy-Item "$($proof.retainedStage)/licenses" "$Stage/licenses" -Recurse
 Assert-Source;$result.sourceStable=$true;$result.state='COMPILED_NOT_EXECUTED'
 Save $result "$Stage/receipt.json"
 & py -3.14 "$PSScriptRoot/../transports/swg/csqtt25/aligned_wrapper.py" --verify $Stage --versions $versions
 if($LASTEXITCODE){throw 'Final aligned loader verification failed'}
}catch{$result.state='FAILED_PRESERVED';$result.sourceStable=$false;$result.error=$_.Exception.Message;throw}
finally{foreach($key in $keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')};Save $result "$Stage/receipt.json"}
