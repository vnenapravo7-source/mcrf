param([Parameter(Mandatory)][string]$PreparedSource,[Parameter(Mandatory)][string]$Inputs,[Parameter(Mandatory)][string]$CDependencies,
      [string]$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-build
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
function Owned([string]$Path,[string]$Pattern){$taskFull=(Resolve-Path -LiteralPath $Path).Path;if($taskFull.Replace([char]92,[char]47) -notmatch $Pattern -or (Get-Item -LiteralPath $taskFull).LinkType){throw 'Exact owned Slipstream stage required'};return $taskFull}
$taskSource=Owned $PreparedSource '^D:/MCRF/Builds/Android/p3-slipstream-source-[a-f0-9]{32}$'
$taskInputs=Owned $Inputs '^D:/MCRF/Builds/Android/p3-slipstream-inputs-[a-f0-9]{32}$'
$taskDeps=Owned $CDependencies '^D:/MCRF/Builds/Android/p3-slipstream-cdeps-[a-f0-9]{32}$'
$taskSourceProof=Get-Content "$taskSource/receipt.json" -Raw|ConvertFrom-Json
$taskInputProof=Get-Content "$taskInputs/receipt.json" -Raw|ConvertFrom-Json
$taskDepsProof=Get-Content "$taskDeps/receipt.json" -Raw|ConvertFrom-Json
if($taskSourceProof.state -cne 'SOURCE_PREPARED_NOT_COMPILED' -or $taskSourceProof.commit -cne '7de506b222c83ae97a19a618e33fe26899af1bd5' -or !$taskSourceProof.privateUds -or !$taskSourceProof.mandatoryLeafPin -or $taskSourceProof.manifestSha256 -cne (Hash "$taskSource/SOURCE_MANIFEST.json")){throw 'Frozen private-UDS pinned-leaf adapter source required'}
if($taskInputProof.state -cne 'INPUTS_PREPARED_NOT_COMPILED' -or $taskInputProof.manifestSha256 -cne (Hash "$taskInputs/SOURCE_MANIFEST.json") -or $taskInputProof.lockSha256 -cne (Hash "$PSScriptRoot/dependencies.lock.json")){throw 'Frozen dependency input required'}
if($taskDepsProof.state -cne 'CDEPS_COMPILED_NOT_EXECUTED' -or !$taskDepsProof.compiled -or $taskDepsProof.inputReceiptSha256 -cne (Hash "$taskInputs/receipt.json") -or $taskDepsProof.inputManifestSha256 -cne $taskInputProof.manifestSha256){throw 'Matching complete C dependency stage required'}
$taskSourceRows=@(Get-Content "$taskSource/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
$taskInputRows=@(Get-Content "$taskInputs/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
function Assert-Tree([string]$Root,$Rows){
 if(@(Get-ChildItem -LiteralPath $Root -Recurse -Force|Where-Object {$_.LinkType}).Count){throw 'Linked frozen Slipstream input'}
 $taskFiles=@(Get-ChildItem -LiteralPath $Root -Recurse -File -Force|Where-Object {$_.FullName -notmatch '[/\\]\.git[/\\]' -and $_.Name -ne '.git' -and $_.FullName -ine [IO.Path]::GetFullPath("$Root/SOURCE_MANIFEST.json") -and $_.FullName -ine [IO.Path]::GetFullPath("$Root/receipt.json")})
 if($taskFiles.Count -ne @($Rows).Count){throw 'Frozen Slipstream input set changed'}
 foreach($taskRow in $Rows){
  if([IO.Path]::IsPathRooted($taskRow.path) -or '..' -in ($taskRow.path -split '[/\\]')){throw 'Unsafe frozen Slipstream source path'}
  if((Hash (Join-Path $Root $taskRow.path)) -cne $taskRow.sha256){throw 'Frozen Slipstream source hash changed'}
 }
}
function Assert-All{
 Assert-Tree $taskSource $taskSourceRows;Assert-Tree $taskInputs $taskInputRows
 foreach($taskRow in $taskDepsProof.artifacts){
  if([IO.Path]::IsPathRooted($taskRow.file) -or '..' -in ($taskRow.file -split '[/\\]')){throw 'Unsafe C dependency output path'}
  $taskInput=Get-Item -LiteralPath (Join-Path $taskDeps $taskRow.file)
  if($taskInput.LinkType -or $taskInput.PSIsContainer -or $taskInput.Length -ne $taskRow.bytes -or (Hash $taskInput.FullName) -cne $taskRow.sha256){throw 'C library/generated header identity changed'}
 }
}
Assert-All
$taskStage="D:/MCRF/Builds/Android/p3-slipstream-native-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path "$taskStage/licenses","$taskStage/fixtures","$taskStage/tmp"|Out-Null
$taskRust='D:/MCRF/Builds/Tools/rust-1.97.1-gnu/toolchain';$taskCargoNdk='D:/MCRF/Builds/Tools/cargo-ndk-4.1.2/bin'
$taskNdkTools="$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin";$taskGo='D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/bin/go.exe'
$taskReceipt=@{state='BUILDING';kind='slipstream';repository='Mygod/slipstream-rust';commit=$taskSourceProof.commit;picoquicCommit=$taskInputProof.picoquic;sourceManifestSha256=$taskSourceProof.manifestSha256;sourceStable=$false;producerComplete=$false;artifacts=@();fixtureArtifacts=@();nativeExecuted=$false;apkBuilt=$false;privateUds=$true;mandatoryLeafPin=$true;slipstreamContract=1;packageVersionCode=1;packageVersionName='slipstream-0.1.1-mcrf.1';dependencyReceiptSha256=Hash "$taskDeps/receipt.json";dependencyInputReceiptSha256=Hash "$taskInputs/receipt.json"}
Copy-Item -LiteralPath "$taskSource/SOURCE_MANIFEST.json" -Destination "$taskStage/source-manifest.json"
Copy-Item -LiteralPath "$taskSource/receipt.json" -Destination "$taskStage/prepared-source.json"
Copy-Item -LiteralPath "$taskDeps/receipt.json" -Destination "$taskStage/c-dependencies.json"
$taskNames=@('PATH','RUSTC','RUSTC_WRAPPER','RUSTC_WORKSPACE_WRAPPER','RUSTFLAGS','CARGO_ENCODED_RUSTFLAGS','CARGO_HOME','CARGO_TARGET_DIR','ANDROID_NDK_HOME','ANDROID_NDK_ROOT','PICOQUIC_AUTO_BUILD','PICOQUIC_DIR','PICOQUIC_INCLUDE_DIR','PICOQUIC_LIB_DIR','PICOTLS_INCLUDE_DIR','OPENSSL_DIR','OPENSSL_ROOT_DIR','OPENSSL_INCLUDE_DIR','OPENSSL_LIB_DIR','OPENSSL_STATIC','OPENSSL_NO_VENDOR','OPENSSL_LIBS','GOOS','GOARCH','GOARM','CGO_ENABLED','CC','GOROOT','GOTOOLCHAIN','GOENV','GOWORK','GOFLAGS','GOPATH','GOMODCACHE','GOPROXY','GOCACHE','GOTMPDIR','TEMP','TMP')
$taskSaved=@{};foreach($taskName in $taskNames){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
$taskSaved['RUST_ANDROID_GRADLE_CC']=[Environment]::GetEnvironmentVariable('RUST_ANDROID_GRADLE_CC','Process')
$taskSaved['RUST_ANDROID_GRADLE_AR']=[Environment]::GetEnvironmentVariable('RUST_ANDROID_GRADLE_AR','Process')
$taskNames+=@('RUST_ANDROID_GRADLE_CC','RUST_ANDROID_GRADLE_AR')
$taskBuiltCrates=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
function Assert-Elf([string]$Path,[int]$Class,[int]$Machine){
 $taskHeader=[IO.File]::ReadAllBytes($Path)
 if($taskHeader.Length -lt 64 -or [BitConverter]::ToString($taskHeader[0..3]) -cne '7F-45-4C-46' -or $taskHeader[4] -ne $Class -or $taskHeader[5] -ne 1 -or [BitConverter]::ToUInt16($taskHeader,16) -ne 3 -or [BitConverter]::ToUInt16($taskHeader,18) -ne $Machine){throw 'Slipstream Android ELF ABI mismatch'}
 $taskHeaders=@(& "$taskNdkTools/llvm-readelf.exe" --program-headers --wide $Path);if($LASTEXITCODE){throw 'Slipstream ELF inspection failed'}
 $taskLoads=@($taskHeaders|Where-Object {$_ -match '^\s*LOAD\s'})
 if(!$taskLoads.Count){throw 'Slipstream ELF has no LOAD segments'}
 foreach($taskLoad in $taskLoads){
  if($taskLoad -notmatch '^\s*LOAD\s+0x([0-9a-fA-F]+)\s+0x([0-9a-fA-F]+).*\s+0x([0-9a-fA-F]+)\s*$' -or [Convert]::ToUInt64($Matches[3],16) -lt 16384 -or [Convert]::ToUInt64($Matches[1],16)%16384 -ne [Convert]::ToUInt64($Matches[2],16)%16384){throw 'Slipstream ELF lacks16KiB alignment'}
 }
}
try{
 New-Item -ItemType Directory -Path "$taskStage/host-tools"|Out-Null
 Copy-Item -LiteralPath "$taskNdkTools/llvm-dlltool.exe" -Destination "$taskStage/host-tools/dlltool.exe"
 $taskReceipt.tools=@{};foreach($taskTool in @("$taskRust/bin/rustc.exe","$taskRust/bin/cargo.exe","$taskCargoNdk/cargo-ndk.exe",$taskGo,"$taskNdkTools/clang.exe","$taskStage/host-tools/dlltool.exe")){$taskReceipt.tools[[IO.Path]::GetFileName($taskTool)]=Hash $taskTool}
 $taskRustPath="$taskStage/host-tools;$taskRust/bin;$taskRust/lib/rustlib/x86_64-pc-windows-gnu/bin;$taskRust/lib/rustlib/x86_64-pc-windows-gnu/bin/self-contained;$taskCargoNdk;$taskNdkTools;"+$taskSaved.PATH
 $env:PATH=$taskRustPath
 $env:RUSTC="$taskRust/bin/rustc.exe";$env:CARGO_HOME='D:/MCRF/Projects/MCRF/work/toolchains/cargo-home';$env:CARGO_TARGET_DIR="$taskStage/rust-target"
 foreach($taskName in @('RUSTC_WRAPPER','RUSTC_WORKSPACE_WRAPPER','CARGO_ENCODED_RUSTFLAGS','GOROOT')){[Environment]::SetEnvironmentVariable($taskName,$null,'Process')}
 # Cargo forwards encoded flags to custom subcommands; an empty forwarded
 # value takes precedence over RUSTFLAGS in cargo-ndk's nested cargo build.
 $env:RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
 $env:CARGO_ENCODED_RUSTFLAGS="-C$([char]31)link-arg=-Wl,-z,max-page-size=16384"
 $env:ANDROID_NDK_HOME=$Ndk;$env:ANDROID_NDK_ROOT=$Ndk
 $env:PICOQUIC_AUTO_BUILD='0';$env:PICOQUIC_DIR="$taskInputs/picoquic";$env:PICOQUIC_INCLUDE_DIR="$taskInputs/picoquic/picoquic";$env:PICOTLS_INCLUDE_DIR="$taskInputs/picotls/include"
 $env:OPENSSL_STATIC='1';$env:OPENSSL_NO_VENDOR='1';$env:OPENSSL_LIBS='ssl:crypto';$env:TEMP="$taskStage/tmp";$env:TMP="$taskStage/tmp"
 # Fetch checksums from the exact Cargo.lock once inside the COMMON phase;
 # it does not run build scripts. Actual compilation below is locked/offline.
 Push-Location "$taskSource/rust"
 try{& "$taskRust/bin/cargo.exe" fetch --locked *> "$taskStage/cargo-fetch.log";if($LASTEXITCODE){throw 'Locked Rust dependencies unavailable'}}finally{Pop-Location}
 foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
  switch($taskAbi){
   'arm64-v8a'{$taskTarget='aarch64-linux-android';$taskTriple='aarch64-linux-android29';$taskClass=2;$taskMachine=183;$env:GOARCH='arm64'}
   'armeabi-v7a'{$taskTarget='armv7-linux-androideabi';$taskTriple='armv7a-linux-androideabi29';$taskClass=1;$taskMachine=40;$env:GOARCH='arm';$env:GOARM='7'}
   'x86_64'{$taskTarget='x86_64-linux-android';$taskTriple='x86_64-linux-android29';$taskClass=2;$taskMachine=62;$env:GOARCH='amd64';[Environment]::SetEnvironmentVariable('GOARM',$null,'Process')}
  }
  New-Item -ItemType Directory -Path "$taskStage/$taskAbi"|Out-Null
  # The original FFI's Android branch launches a raw compiler path and does
  # not forward cc::Tool flags. Give it the real NDK target-prefixed wrapper,
  # not cargo-ndk's generic CC shim without an Android target/sysroot.
  $env:RUST_ANDROID_GRADLE_CC="$taskNdkTools/$taskTriple-clang.cmd"
  $env:RUST_ANDROID_GRADLE_AR="$taskNdkTools/llvm-ar.exe"
  $taskReceipt.tools["$taskAbi-clang-wrapper"]=Hash $env:RUST_ANDROID_GRADLE_CC
  $env:PICOQUIC_LIB_DIR="$taskDeps/$taskAbi/lib";$env:OPENSSL_DIR="$taskDeps/$taskAbi/openssl";$env:OPENSSL_ROOT_DIR=$env:OPENSSL_DIR;$env:OPENSSL_INCLUDE_DIR="$env:OPENSSL_DIR/include";$env:OPENSSL_LIB_DIR="$env:OPENSSL_DIR/lib"
  Push-Location "$taskSource/rust"
  try{
   $env:PATH=$taskRustPath
   & "$taskRust/bin/cargo.exe" ndk -t $taskAbi -P 29 build --release --locked --offline -p slipstream-client -p slipstream-server --features 'slipstream-client/openssl-static,slipstream-client/picoquic-minimal-build,slipstream-server/openssl-static,slipstream-server/picoquic-minimal-build' --message-format=json > "$taskStage/rust-$taskAbi.jsonl" 2> "$taskStage/rust-$taskAbi.stderr.log"
   if($LASTEXITCODE){throw "Slipstream Rust native build failed: $taskAbi"}
  }finally{Pop-Location}
  foreach($taskLine in Get-Content "$taskStage/rust-$taskAbi.jsonl"){$taskEvent=$taskLine|ConvertFrom-Json;if($taskEvent.reason -ceq 'compiler-artifact' -and $taskEvent.package_id){[void]$taskBuiltCrates.Add($taskEvent.package_id)}}
  Copy-Item -LiteralPath "$env:CARGO_TARGET_DIR/$taskTarget/release/slipstream-client" -Destination "$taskStage/$taskAbi/libslipstream_core.so"
  # Do not expose Rust's host MinGW DLL/tool directories to Go's C linker.
  $env:PATH="$taskNdkTools;"+$taskSaved.PATH
  $env:GOOS='android';$env:CGO_ENABLED='1';$env:CC="$taskNdkTools/clang.exe --target=$taskTriple";$env:GOTOOLCHAIN='local';$env:GOENV='off';$env:GOWORK='off';$env:GOFLAGS='';$env:GOPROXY='off'
  $env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-home';$env:GOMODCACHE="$env:GOPATH/pkg/mod";$env:GOCACHE='D:/MCRF/Projects/MCRF/work/toolchains/go-build-cache';$env:GOTMPDIR="$taskStage/tmp"
  Push-Location "$taskSource/bridge"
  try{
   & $taskGo build -mod=readonly -trimpath -buildmode=pie '-ldflags=-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o "$taskStage/$taskAbi/libslipstream.so" . *> "$taskStage/go-$taskAbi.log";if($LASTEXITCODE){throw "Slipstream wrapper build failed: $taskAbi"}
   if($taskAbi -ceq 'x86_64'){
    & $taskGo test -c -mod=readonly -buildmode=pie '-ldflags=-linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o "$taskStage/fixtures/libslipstream_fixture.so" . *> "$taskStage/fixture-build.log";if($LASTEXITCODE){throw "Slipstream Unix fixture compilation failed (exit $LASTEXITCODE)"}
   }
  }finally{Pop-Location}
  foreach($taskName in @('libslipstream.so','libslipstream_core.so')){Assert-Elf "$taskStage/$taskAbi/$taskName" $taskClass $taskMachine;$taskReceipt.artifacts+=@{abi=$taskAbi;file="$taskAbi/$taskName";bytes=(Get-Item "$taskStage/$taskAbi/$taskName").Length;sha256=Hash "$taskStage/$taskAbi/$taskName";elfChecked=$true;nativeExecuted=$false}}
  if($taskAbi -ceq 'x86_64'){
   Copy-Item -LiteralPath "$env:CARGO_TARGET_DIR/$taskTarget/release/slipstream-server" -Destination "$taskStage/fixtures/libslipstream_testserver.so"
   foreach($taskName in @('libslipstream_fixture.so','libslipstream_testserver.so')){Assert-Elf "$taskStage/fixtures/$taskName" 2 62;$taskReceipt.fixtureArtifacts+=@{file="fixtures/$taskName";sha256=Hash "$taskStage/fixtures/$taskName";bytes=(Get-Item "$taskStage/fixtures/$taskName").Length;elfChecked=$true}}
  }
 }
 Assert-All;$taskReceipt.sourceStable=$true
 # Legal inventory and real Unix fixture promotion are separate required steps.
 # This state is deliberately NOT accepted by Gradle/install packaging.
 Save @($taskBuiltCrates|Sort-Object) "$taskStage/built-rust-package-ids.json"
 Push-Location "$taskSource/rust"
 try{& "$taskRust/bin/cargo.exe" metadata --format-version 1 --locked --offline > "$taskStage/rust-metadata.json" 2> "$taskStage/rust-metadata.stderr.log";if($LASTEXITCODE){throw 'Built Rust legal source identities unavailable'}}finally{Pop-Location}
 $taskReceipt.builtRustIdsSha256=Hash "$taskStage/built-rust-package-ids.json";$taskReceipt.rustMetadataSha256=Hash "$taskStage/rust-metadata.json"
 $taskReceipt.state='COMPILED_LEGAL_AND_FIXTURE_PENDING'
}catch{$taskReceipt.state='FAILED_PRESERVED';$taskReceipt.failure=$_.Exception.Message;throw}
finally{foreach($taskName in $taskNames){[Environment]::SetEnvironmentVariable($taskName,$taskSaved[$taskName],'Process')};Save $taskReceipt "$taskStage/receipt.json";Write-Output "P3_SLIPSTREAM_NATIVE=$taskStage"}
