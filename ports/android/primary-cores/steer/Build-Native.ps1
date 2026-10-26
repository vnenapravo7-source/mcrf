param([Parameter(Mandatory)][string]$PreparedSource,
 [string]$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018',
 [string]$Cmake='C:/Users/dieonfire/AppData/Local/Android/Sdk/cmake/3.22.1/bin/cmake.exe',
 [string]$Ninja='C:/Users/dieonfire/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe',
 [string]$RetainedSocksStage)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-build
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 14),[Text.UTF8Encoding]::new($false))}
$taskPreparedRoot=(Resolve-Path -LiteralPath $PreparedSource).Path.Replace([char]92,[char]47)
if($taskPreparedRoot.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-steer-source-[a-f0-9]{32}$'){throw 'New isolated Steer source required'}
$taskPrepared=Get-Content "$taskPreparedRoot/receipt.json" -Raw|ConvertFrom-Json
if($taskPrepared.state -cne 'SOURCE_PREPARED_NOT_COMPILED' -or $taskPrepared.newVersion -notin @(15,16) -or $taskPrepared.manifestSha256 -cne (Hash "$taskPreparedRoot/SOURCE_MANIFEST.json")){throw 'Frozen source-bound Steer source required'}
$taskRetained=$null
if($taskPrepared.newVersion -eq 16){
 if(!$RetainedSocksStage -or $taskPrepared.privateHelperProfile -ne 1){throw 'Private helper revision needs retained unchanged SOCKS evidence'}
 $taskRetainedRoot=(Resolve-Path $RetainedSocksStage).Path.Replace([char]92,[char]47)
 if($taskRetainedRoot -cnotmatch '^D:/MCRF/Builds/Android/p3-steer-native-[a-f0-9]{32}$'){throw 'Exact retained Steer stage required'}
 $taskRetained=Get-Content "$taskRetainedRoot/receipt.json" -Raw|ConvertFrom-Json
 if($taskRetained.newVersion -ne 15 -or !$taskRetained.producerComplete -or @($taskRetained.cases).Count -ne 3){throw 'Accepted exact parent SOCKS stage required'}
 $taskParentRows=@(Get-Content "$($taskRetained.preparedSource)/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json|Where-Object {$_.path.StartsWith('source/')})
 $taskFreshParentRows=@(Get-Content "$($taskPrepared.parentPreparedSource)/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json|Where-Object {$_.path.StartsWith('source/')})
 if($taskParentRows.Count -ne $taskFreshParentRows.Count){throw 'Retained parent source closure changed'}
 foreach($row in $taskParentRows){if((Hash "$($taskPrepared.parentPreparedSource)/$($row.path)") -cne $row.sha256){throw 'Retained parent C/source closure changed'}}
 foreach($name in @('mcrf_socks_udp_test.c','mcrf_socks_local_udp_test.c','mcrf_socks_udp_wire_test.c','src/proto/proxy/pxsocks.c','src/proto/proxy/mcrf_socks_udp.h','src/proto/proxy/mcrf_socks_local_udp.h','src/proto/proxy/pxwire.c')){
  if((Hash "$taskPreparedRoot/source/$name") -cne (Hash "$($taskRetained.preparedSource)/source/$name")){throw 'Retained SOCKS fixture source changed'}
 }
}
$taskManifest=@(Get-Content "$taskPreparedRoot/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
function Assert-Source{
 foreach($taskRow in $taskManifest){
  if([IO.Path]::IsPathRooted($taskRow.path) -or '..' -in ($taskRow.path -split '[/\\]')){throw 'Unsafe frozen source path'}
  $taskFile=Join-Path $taskPreparedRoot $taskRow.path
  if((Get-Item -LiteralPath $taskFile).LinkType -or (Get-Item $taskFile).Length -ne $taskRow.bytes -or (Hash $taskFile) -cne $taskRow.sha256){throw 'Frozen Steer source changed'}
 }
}
Assert-Source
# Short unique native output avoids Windows MAX_PATH without overwriting any
# old compiler/cache/source. This producer compiles only new source and3 NEW
# SOCKS fixtures, never runs the old P1/P2 TLS/route assertions.
$taskStage="D:/MCRF/Builds/Android/p3-steer-native-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $taskStage|Out-Null
$taskScratch="D:/MCRF/Builds/Android/p3c-$([guid]::NewGuid().ToString('N').Substring(0,12))";New-Item -ItemType Directory -Path $taskScratch|Out-Null
Copy-Item "$taskPreparedRoot/SOURCE_MANIFEST.json" "$taskStage/source-manifest.json";Copy-Item "$taskPreparedRoot/receipt.json" "$taskStage/prepared-source.json"
$taskReceipt=@{state='BUILDING';kind='steer';newVersion=15;baselineDescriptorVersion=$taskPrepared.baselineDescriptorVersion;sourceStable=$false;producerComplete=$false;sourceManifestSha256=Hash "$taskStage/source-manifest.json";preparedSource=$taskPreparedRoot;nativeScratch=$taskScratch;producerSha256=Hash $PSCommandPath;cmakeSha256=Hash $Cmake;ninjaSha256=Hash $Ninja;artifacts=@();fixtures=@();cases=@();nativeExecuted=$false;apkBuilt=$false}
$taskReceipt.newVersion=$taskPrepared.newVersion
if($taskRetained){$taskReceipt.cases=@($taskRetained.cases);$taskReceipt.ownedAttempts=@($taskRetained.ownedAttempts);$taskReceipt.retainedSocks=@{stage=$taskRetainedRoot;receiptSha256=Hash "$taskRetainedRoot/receipt.json";unchangedFixtureSources=$true;testsReplayed=$false}}
Save $taskReceipt "$taskStage/receipt.json"
try{
 foreach($taskAbi in @('arm64-v8a','x86_64')){
  $taskBuild="$taskScratch/$taskAbi";New-Item -ItemType Directory -Path $taskBuild|Out-Null
  $taskArgs=@('-S',"$taskPreparedRoot/source/android-vpn/core/src/main/cpp",'-B',$taskBuild,'-G','Ninja',"-DCMAKE_MAKE_PROGRAM=$Ninja","-DCMAKE_TOOLCHAIN_FILE=$Ndk/build/cmake/android.toolchain.cmake","-DANDROID_NDK=$Ndk","-DANDROID_ABI=$taskAbi",'-DANDROID_PLATFORM=android-29','-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON','-DCMAKE_BUILD_TYPE=Release','-DCMAKE_OBJECT_PATH_MAX=180',"-DMCRF_TLS49_MANIFEST=$taskPreparedRoot/tls.json","-DMCRF_CODEC49_ARCHIVES=$taskPreparedRoot/inputs",'-DFETCHCONTENT_UPDATES_DISCONNECTED=ON')
  & $Cmake @taskArgs *> "$taskStage/configure-$taskAbi.log";if($LASTEXITCODE){throw "Steer configure failed: $taskAbi"}
  $taskTargets=if($taskRetained){@('steer_vpn','mcrf_private_socks_profile_test')}else{@('steer_vpn','mcrf_socks_udp_test','mcrf_socks_local_udp_test','mcrf_socks_udp_wire_test')}
  & $Cmake --build $taskBuild --target @taskTargets --parallel 4 *> "$taskStage/build-$taskAbi.log";if($LASTEXITCODE){throw "Steer/SOCKS fixture compile failed: $taskAbi"}
  New-Item -ItemType Directory -Path "$taskStage/$taskAbi"|Out-Null
  $taskNames=if($taskRetained){@('libsteer_vpn.so','mcrf_private_socks_profile_test')}else{@('libsteer_vpn.so','mcrf_socks_udp_test','mcrf_socks_local_udp_test','mcrf_socks_udp_wire_test')}
  foreach($taskName in $taskNames){
   $taskFrom="$taskBuild/$taskName";$taskTo="$taskStage/$taskAbi/$taskName";Copy-Item -LiteralPath $taskFrom -Destination $taskTo
   $taskBytes=[IO.File]::ReadAllBytes($taskTo);$taskMachine=if($taskAbi -ceq 'arm64-v8a'){183}else{62}
   if([BitConverter]::ToString($taskBytes[0..5]) -cne '7F-45-4C-46-02-01' -or [BitConverter]::ToUInt16($taskBytes,18) -ne $taskMachine){throw 'Steer ELF ABI mismatch'}
   $taskProgram=@(& "$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe" --program-headers --wide $taskTo);if($LASTEXITCODE){throw 'ELF segment inspection failed'}
   $taskLoads=@($taskProgram|Where-Object {$_ -match '^\s*LOAD\s'});if(!$taskLoads.Count){throw 'No ELF load segments'}
   foreach($taskLoad in $taskLoads){if($taskLoad -notmatch '^\s*LOAD\s+0x([0-9a-fA-F]+)\s+0x([0-9a-fA-F]+).*\s+0x([0-9a-fA-F]+)\s*$'){throw 'Unknown ELF segment format'};if([Convert]::ToUInt64($Matches[3],16) -lt 16384 -or [Convert]::ToUInt64($Matches[1],16)%16384 -ne [Convert]::ToUInt64($Matches[2],16)%16384){throw 'Steer native must be16KiB aligned'}}
   $taskRow=@{abi=$taskAbi;file="$taskAbi/$taskName";bytes=(Get-Item $taskTo).Length;sha256=Hash $taskTo;elfChecked=$true;nativeExecuted=$false}
   if($taskName -ceq 'libsteer_vpn.so'){$taskReceipt.artifacts+=,$taskRow}else{$taskReceipt.fixtures+=,$taskRow}
  }
 }
 if($taskRetained){foreach($row in $taskRetained.fixtures){if((Hash "$taskRetainedRoot/$($row.file)") -cne $row.sha256){throw 'Retained fixture ELF changed'};Copy-Item "$taskRetainedRoot/$($row.file)" "$taskStage/$($row.file)";$taskReceipt.fixtures+=,$row}}
 Assert-Source;$taskReceipt.sourceStable=$true;$taskReceipt.state='COMPILED_SOCKS_FIXTURE_PENDING'
 Save $taskReceipt "$taskStage/receipt.json"
}catch{$taskReceipt.state='FAILED_NATIVE_RETAINED';Save $taskReceipt "$taskStage/receipt.json";throw}
Write-Output "P3_STEER_COMPILED_NOT_ADMITTED=$taskStage"
