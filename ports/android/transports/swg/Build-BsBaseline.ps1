param([Parameter(Mandatory)][string]$PreparedSource,
      [Parameter(Mandatory)][string]$PreparedSha256,
      [Parameter(Mandatory)][string]$RetainedStage,
      [Parameter(Mandatory)][string]$RetainedReceiptSha256,
      [Parameter(Mandatory)][string]$OutputDirectory,
      [string]$Go='D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/bin/go.exe',
      [string]$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-build
function Hash([string]$Path){$item=Get-Item -LiteralPath $Path;if($item.LinkType -or $item.PSIsContainer){throw 'Regular input required'};(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))}
$proofPath=Join-Path $PreparedSource 'prepared-expansion-source.json'
if((Hash $proofPath) -cne $PreparedSha256 -or (Hash "$RetainedStage/receipt.json") -cne $RetainedReceiptSha256){throw 'Explicit frozen source and retained receipt required'}
$proof=Get-Content $proofPath -Raw|ConvertFrom-Json
$retained=Get-Content "$RetainedStage/receipt.json" -Raw|ConvertFrom-Json
if($proof.state -cne 'PREPARED_NOT_COMPILED' -or !$proof.sourceStable -or $proof.kind -cne 'csqtt' -or
   $proof.preparerSha256 -cne (Hash "$PSScriptRoot/build.ps1") -or
   $retained.state -cne 'COMPILED_NOT_EXECUTED' -or !$retained.sourceStable -or $retained.kind -cne 'csqtt' -or
   $retained.processGroupContract -ne 1 -or $retained.accountOptionsContract -ne 1 -or $retained.turnHashPoolContract -ne 1){throw 'Canonical preparation and full-feature retained baseline required'}
$module=Join-Path $PreparedSource 'qwdtt/qwdtt'
function Assert-Source {
 if((Hash $proofPath) -cne $PreparedSha256 -or (Hash "$RetainedStage/receipt.json") -cne $RetainedReceiptSha256){throw 'Frozen receipt changed'}
 $files=@(Get-ChildItem $module -Recurse -File|Where-Object{$_.Extension -ceq '.go' -or $_.Name -cin @('go.mod','go.sum')})
 if($files.Count -ne @($proof.inputs).Count){throw 'Canonical source inventory changed'}
 foreach($row in $proof.inputs){if([IO.Path]::IsPathRooted($row.path) -or '..' -in ($row.path -split '[/\\]') -or (Hash (Join-Path $module $row.path)) -cne $row.sha256){throw 'Prepared source changed'}}
 foreach($row in $proof.canonicalInputs.PSObject.Properties){if([IO.Path]::IsPathRooted($row.Name) -or '..' -in ($row.Name -split '[/\\]') -or (Hash (Join-Path $PSScriptRoot $row.Name)) -cne $row.Value){throw 'Canonical producer input changed'}}
 foreach($name in @('mcrf_process_group.go','mcrf_process_group_linux.go','mcrf_process_group_other.go','mcrf_child_input.go','mcrf_captcha.go')){if((Hash "$module/$name") -cne (Hash "$PSScriptRoot/$name")){throw 'Current ownership/waiter helper required'}}
}
Assert-Source
if(Test-Path $OutputDirectory){throw 'Fresh baseline stage required'}
New-Item -ItemType Directory -Path $OutputDirectory|Out-Null
$rows=@($proof.inputs|ForEach-Object{@{path=$_.path.Replace([char]92,[char]47);sha256=$_.sha256}}|Sort-Object path)
Save $rows "$OutputDirectory/source-manifest.json"
Copy-Item $proofPath "$OutputDirectory/canonical-prepared-source.json"
Copy-Item "$RetainedStage/receipt.json" "$OutputDirectory/retained-baseline-receipt.json"
$result=@{state='BUILDING';kind='csqtt';source=$module;sourceStable=$false;sourceManifestSha256=Hash "$OutputDirectory/source-manifest.json";canonicalPreparedSha256=$PreparedSha256;retainedReceiptSha256=$RetainedReceiptSha256;producerSha256=Hash $PSCommandPath;processGroupContract=1;accountOptionsContract=1;turnHashPoolContract=1;nativeExecuted=$false;apkBuilt=$false;artifacts=@()}
$keys=@('GOOS','GOARCH','GOARM','CGO_ENABLED','CC','GOROOT','GOENV','GOWORK','GOFLAGS','GOTOOLCHAIN','GOPROXY','GOPATH','GOMODCACHE','GOCACHE','GOTMPDIR');$saved=@{}
foreach($key in $keys){$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
try {
 [Environment]::SetEnvironmentVariable('GOROOT',$null,'Process');[Environment]::SetEnvironmentVariable('GOARM',$null,'Process')
 $env:GOOS='windows';$env:GOARCH='amd64';$env:CGO_ENABLED='0';$env:GOENV='off';$env:GOWORK='off';$env:GOFLAGS='';$env:GOTOOLCHAIN='local';$env:GOPROXY='off'
 $env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-home';$env:GOMODCACHE="$env:GOPATH/pkg/mod";$env:GOCACHE='D:/MCRF/Projects/MCRF/work/toolchains/go-build-cache';$env:GOTMPDIR='D:/MCRF/Projects/MCRF/work/toolchains/go-tmp'
 Push-Location $module
 try {
  & $Go mod verify *> "$OutputDirectory/dependencies.log";if($LASTEXITCODE){throw 'Dependency verification failed'}
  & $Go test -mod=readonly '-ldflags=-checklinkname=0' -count=1 -json -timeout 180s -run '^(TestMcrf|TestCSQTT|TestExtendedHashes)' ./... > "$OutputDirectory/host-tests.jsonl" 2> "$OutputDirectory/host-tests.stderr.log"
  if($LASTEXITCODE){throw 'Fresh baseline host tests failed'}
 } finally {Pop-Location}
 $events=@(Get-Content "$OutputDirectory/host-tests.jsonl"|Where-Object{$_}|ForEach-Object{$_|ConvertFrom-Json})
 $cases=@($events|Where-Object{$_.Action -ceq 'pass' -and $_.Test}|ForEach-Object{$_.Test})
 $required=@('TestCSQTTAccountOptionsRequirePrivateMatchingSession','TestCSQTTExtendedHashPoolSurvivesConfigAndLink','TestExtendedHashesKeepCompletePool','TestMcrfProcessGroupPrivateAckIsExactAndOneShot','TestMcrfChildInputFailureWipesAndNeverRetriesOrReopens','TestMcrfStreamsRetainedLegacyStatsAndExactUpgrade','TestMcrfRawMTUOnActualBulkUpload')
 if(@($events|Where-Object{$_.Action -cin @('fail','skip')}).Count -or @($required|Where-Object{$_ -cnotin $cases}).Count){throw 'Required source-bound baseline cases missing/failed/skipped'}
 Save @{state='HOST_CHECKS_PASSED';sourceManifestSha256=$result.sourceManifestSha256;logSha256=Hash "$OutputDirectory/host-tests.jsonl";cases=$cases;toolchainSha256=Hash $Go} "$OutputDirectory/host-evidence.json"
 $result.hostEvidenceSha256=Hash "$OutputDirectory/host-evidence.json"
 Assert-Source
 $env:GOOS='android';$env:CGO_ENABLED='1'
 foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){
  switch($abi){'arm64-v8a'{$env:GOARCH='arm64';$triple='aarch64-linux-android29'};'armeabi-v7a'{$env:GOARCH='arm';$env:GOARM='7';$triple='armv7a-linux-androideabi29'};'x86_64'{$env:GOARCH='amd64';[Environment]::SetEnvironmentVariable('GOARM',$null,'Process');$triple='x86_64-linux-android29'}}
  $env:CC="$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe --target=$triple"
  New-Item -ItemType Directory -Path "$OutputDirectory/$abi"|Out-Null
  $target="$OutputDirectory/$abi/libqwdtt.so"
  Push-Location $module
  try {& $Go build -mod=readonly -trimpath -buildmode=pie '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o $target . *> "$OutputDirectory/build-$abi.log";if($LASTEXITCODE){throw "Baseline compile failed: $abi"}}finally{Pop-Location}
  & py -3.14 -c 'import sys; sys.path.insert(0,sys.argv[3]); from bs_engine_inputs import elf; elf(sys.argv[1],sys.argv[2])' $target $abi $PSScriptRoot
  if($LASTEXITCODE){throw 'Baseline ELF/16KiB validation failed'}
  $result.artifacts+=@{abi=$abi;file="$abi/libqwdtt.so";bytes=(Get-Item $target).Length;sha256=Hash $target;elfChecked=$true;nativeExecuted=$false}
 }
 Copy-Item "$RetainedStage/licenses" "$OutputDirectory/licenses" -Recurse
 Assert-Source;$result.sourceStable=$true;$result.state='COMPILED_NOT_EXECUTED'
}catch{$result.state='FAILED_PRESERVED';$result.error=$_.Exception.Message;throw}
finally{foreach($key in $keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')};Save $result "$OutputDirectory/receipt.json"}
