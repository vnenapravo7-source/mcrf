param([Parameter(Mandatory)][string]$NativeStage,[Parameter(Mandatory)][string]$CFixtureSource,
      [Parameter(Mandatory)][string]$Inputs,[Parameter(Mandatory)][string]$CDependencies,
      [string]$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-build
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
function Owned([string]$Path,[string]$Pattern){$taskFull=(Resolve-Path -LiteralPath $Path).Path;if($taskFull.Replace([char]92,[char]47) -notmatch $Pattern -or (Get-Item -LiteralPath $taskFull).LinkType){throw 'Exact owned source/native stage required'};return $taskFull}
$taskRoot=Owned $NativeStage '^D:/MCRF/Builds/Android/p3-slipstream-native-[a-f0-9]{32}$'
$taskSource=Owned $CFixtureSource '^D:/MCRF/Builds/Android/p3-slipstream-cfixture-source-[a-f0-9]{32}$'
$taskInputs=Owned $Inputs '^D:/MCRF/Builds/Android/p3-slipstream-inputs-[a-f0-9]{32}$'
$taskDeps=Owned $CDependencies '^D:/MCRF/Builds/Android/p3-slipstream-cdeps-[a-f0-9]{32}$'
$taskReceipt=Get-Content "$taskRoot/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
$taskSourceProof=Get-Content "$taskSource/receipt.json" -Raw|ConvertFrom-Json
if($taskReceipt.state -cnotin @('COMPILED_LEGAL_AND_FIXTURE_PENDING','COMPILED_FIXTURE_PENDING','COMPILED_NOTICES_PENDING') -or $taskReceipt.producerComplete -or $taskReceipt.originalCFixture){throw 'Unpromoted native stage without an existing C fixture required'}
if($taskSourceProof.state -cne 'C_FIXTURE_SOURCE_PREPARED' -or !$taskSourceProof.loopbackOnly -or $taskSourceProof.commit -cne '397850b16e70a52513269134b2c7b9aa13a56745' -or $taskSourceProof.manifestSha256 -cne (Hash "$taskSource/SOURCE_MANIFEST.json")){throw 'Pinned original C server fixture source required'}
if($taskReceipt.dependencyReceiptSha256 -cne (Hash "$taskDeps/receipt.json") -or $taskReceipt.dependencyInputReceiptSha256 -cne (Hash "$taskInputs/receipt.json")){throw 'Matching static TLS dependency inputs required'}
$taskRows=@(Get-Content "$taskSource/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
function Assert-Source{
 if(@(Get-ChildItem -LiteralPath $taskSource -Recurse -Force|Where-Object {$_.LinkType}).Count){throw 'Linked original C fixture source'}
 $taskFiles=@(Get-ChildItem -LiteralPath $taskSource -Recurse -File -Force|Where-Object {$_.FullName -notmatch '[/\\]\.git[/\\]' -and $_.Name -ne '.git' -and $_.FullName -ine [IO.Path]::GetFullPath("$taskSource/receipt.json") -and $_.FullName -ine [IO.Path]::GetFullPath("$taskSource/SOURCE_MANIFEST.json")})
 if($taskFiles.Count -ne $taskRows.Count){throw 'Original C fixture source set changed'}
 foreach($taskRow in $taskRows){if([IO.Path]::IsPathRooted($taskRow.path) -or '..' -in ($taskRow.path -split '[/\\]') -or (Hash (Join-Path $taskSource $taskRow.path)) -cne $taskRow.sha256){throw 'Original C fixture source identity changed'}}
}
Assert-Source
$taskBuild="$taskRoot/c-fixture-build";if(Test-Path -LiteralPath $taskBuild){throw 'Preserve the finished/failed original C producer; do not duplicate it'}
New-Item -ItemType Directory -Path "$taskBuild/objects"|Out-Null
$taskSDK="$taskSource/source";$taskPico="$taskSDK/subprojects/picoquic";$taskSSL="$taskDeps/x86_64/openssl";$taskPTLS="$taskDeps/x86_64/lib"
$taskTools="$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin";$taskCmake='C:/Users/dieonfire/AppData/Local/Android/Sdk/cmake/3.22.1/bin/cmake.exe'
$taskNinja='C:/Users/dieonfire/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe'
$taskCMakeArgs=@('-G','Ninja',"-DCMAKE_MAKE_PROGRAM=$taskNinja","-DCMAKE_TOOLCHAIN_FILE=$Ndk/build/cmake/android.toolchain.cmake",'-DANDROID_PLATFORM=android-29','-DANDROID_ABI=x86_64','-DCMAKE_BUILD_TYPE=Release','-DCMAKE_POSITION_INDEPENDENT_CODE=ON','-DCMAKE_POLICY_VERSION_MINIMUM=3.5',"-DOPENSSL_ROOT_DIR=$taskSSL","-DOPENSSL_INCLUDE_DIR=$taskSSL/include","-DOPENSSL_CRYPTO_LIBRARY=$taskSSL/lib/libcrypto.a","-DOPENSSL_SSL_LIBRARY=$taskSSL/lib/libssl.a",'-DOPENSSL_USE_STATIC_LIBS=TRUE',"-DPTLS_INCLUDE_DIR=$taskInputs/picotls/include","-DPTLS_CORE_LIBRARY=$taskPTLS/libpicotls-core.a","-DPTLS_OPENSSL_LIBRARY=$taskPTLS/libpicotls-openssl.a","-DPTLS_MINICRYPTO_LIBRARY=$taskPTLS/libpicotls-minicrypto.a",'-DPICOQUIC_FETCH_PTLS=OFF','-DPTLS_WITH_FUSION=OFF','-DWITH_FUSION=OFF','-DBUILD_DEMO=OFF','-DBUILD_HTTP=OFF','-DBUILD_LOGLIB=OFF','-DBUILD_LOGREADER=OFF','-Dpicoquic_BUILD_TESTS=OFF')
try{
 & $taskCmake -S $taskPico -B "$taskBuild/picoquic" @taskCMakeArgs *> "$taskBuild/configure.log";if($LASTEXITCODE){throw 'Original pinned picoquic fixture configure failed'}
 & $taskCmake --build "$taskBuild/picoquic" --target picoquic-core --parallel 4 *> "$taskBuild/picoquic-build.log";if($LASTEXITCODE){throw 'Original pinned picoquic fixture build failed'}
 $taskIncludes=@("$taskSDK/include","$taskSDK/extern","$taskSDK/extern/quick_arg_parser","$taskSDK/extern/SPCDNS/src","$taskSDK/extern/lua-resty-base-encoding","$taskPico/picoquic","$taskInputs/picotls/include","$taskSSL/include")|ForEach-Object {"-I$_"}
 $taskCommon=@('--target=x86_64-linux-android29','-D_GNU_SOURCE','-O2','-fPIE','-ffunction-sections','-fdata-sections')+@($taskIncludes)
 # Exact Meson common/server source sets; no client binary or unrelated tests.
 $taskSources=@('src/slipstream_sockloop.c','src/slipstream_utils.c','src/slipstream_inline_dots.c','extern/lua-resty-base-encoding/base32.c','extern/SPCDNS/src/codec.c','extern/SPCDNS/src/mappings.c','extern/SPCDNS/src/netsimple.c','extern/SPCDNS/src/output.c','src/slipstream_server.c','src/slipstream_server_cc.c','src/slipstream_server_cli.cpp')
 $taskObjects=@();$taskIndex=0
 foreach($taskFile in $taskSources+@('../fixture_loopback_bind.c')){
  $taskInput=[IO.Path]::GetFullPath((Join-Path $taskSDK $taskFile));$taskObject="$taskBuild/objects/$taskIndex.o";$taskIndex++
  $taskCxx=$taskFile.EndsWith('.cpp');$taskCompiler=if($taskCxx){"$taskTools/clang++.exe"}else{"$taskTools/clang.exe"};$taskStandard=if($taskCxx){'-std=gnu++11'}else{'-std=gnu11'}
  & $taskCompiler @taskCommon $taskStandard -c $taskInput -o $taskObject *> "$taskBuild/compile-$taskIndex.log";if($LASTEXITCODE){throw "Original C fixture source compilation failed: $taskFile"};$taskObjects+=$taskObject
 }
 $taskOutput="$taskRoot/fixtures/libslipstream_cserver.so"
 & "$taskTools/clang++.exe" --target=x86_64-linux-android29 -pie -static-libstdc++ '-Wl,-z,max-page-size=16384' '-Wl,--wrap=bind' '-Wl,--gc-sections' @taskObjects '-Wl,--start-group' "$taskBuild/picoquic/libpicoquic-core.a" "$taskPTLS/libpicotls-core.a" "$taskPTLS/libpicotls-openssl.a" "$taskPTLS/libpicotls-minicrypto.a" "$taskSSL/lib/libssl.a" "$taskSSL/lib/libcrypto.a" '-Wl,--end-group' -lm -ldl -llog -o $taskOutput *> "$taskBuild/link.log"
 if($LASTEXITCODE){throw 'Original C server fixture link failed'}
 $taskBytes=[IO.File]::ReadAllBytes($taskOutput)
 if($taskBytes.Length -lt 64 -or [BitConverter]::ToString($taskBytes[0..3]) -cne '7F-45-4C-46' -or $taskBytes[4] -ne 2 -or [BitConverter]::ToUInt16($taskBytes,16) -ne 3 -or [BitConverter]::ToUInt16($taskBytes,18) -ne 62){throw 'Original C fixture ELF identity mismatch'}
 $taskSegments=@(& "$taskTools/llvm-readelf.exe" --program-headers --wide $taskOutput);if($LASTEXITCODE){throw 'Original C fixture ELF inspection failed'}
 $taskLoads=@($taskSegments|Where-Object {$_ -match '^\s*LOAD\s'})
 if(!$taskLoads.Count){throw 'Original C fixture lacks LOAD segments'}
 foreach($taskLoad in $taskLoads){if($taskLoad -notmatch '^\s*LOAD\s+0x([0-9a-fA-F]+)\s+0x([0-9a-fA-F]+).*\s+0x([0-9a-fA-F]+)\s*$' -or [Convert]::ToUInt64($Matches[3],16) -lt 16384 -or [Convert]::ToUInt64($Matches[1],16)%16384 -ne [Convert]::ToUInt64($Matches[2],16)%16384){throw 'Original C fixture lacks16KiB alignment'}}
 Assert-Source
 Copy-Item -LiteralPath "$taskSource/SOURCE_MANIFEST.json" -Destination "$taskRoot/cfixture-source-manifest.json";Copy-Item -LiteralPath "$taskSource/receipt.json" -Destination "$taskRoot/cfixture-source.json"
 Copy-Item -LiteralPath "$taskSDK/LICENSE" -Destination "$taskRoot/fixtures/C-SERVER-LICENSE"
 $taskReceipt.originalCFixture=@{commit=$taskSourceProof.commit;picoquic=$taskSourceProof.submodules.'subprojects/picoquic';sourceManifestSha256=$taskSourceProof.manifestSha256;sourceReceiptSha256=Hash "$taskRoot/cfixture-source.json";loopbackOnly=$true;executed=$false}
 $taskReceipt.fixtureArtifacts+=@{file='fixtures/libslipstream_cserver.so';bytes=(Get-Item $taskOutput).Length;sha256=Hash $taskOutput;elfChecked=$true}
}catch{$taskReceipt.state='C_FIXTURE_BUILD_FAILED_PRESERVED';$taskReceipt.failure=$_.Exception.Message;throw}
finally{Save $taskReceipt "$taskRoot/receipt.json";Write-Output "P3_SLIPSTREAM_CFIXTURE=$taskRoot"}
