param([Parameter(Mandatory)][string]$RetainedPrimaryStage,[Parameter(Mandatory)][string]$CodecArchives)
$ErrorActionPreference='Stop'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
$taskCanonical='D:/MCRF/Projects/MCRF/work/steer-android-port'
$taskPrimary=(Resolve-Path -LiteralPath $RetainedPrimaryStage).Path
$taskRetained=Get-Content "$taskPrimary/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
$taskDescriptor=$taskRetained.modules.steer
if(!$taskDescriptor -or $taskDescriptor.api -ne 2 -or $taskDescriptor.versionCode -notin @(13,14) -or (@($taskDescriptor.abis|Sort-Object) -join ',') -cne 'arm64-v8a,x86_64'){throw 'Accepted Steer two-ABI source descriptor required'}
foreach($taskName in @('runtime.aar','adapter.aar')){
 $taskArtifact=@($taskDescriptor.artifacts|Where-Object file -CEQ $taskName)
 if($taskArtifact.Count -ne 1 -or $taskArtifact[0].sha256 -cne (Hash "$taskPrimary/steer/$taskName")){throw 'Retained Steer AAR digest changed'}
}
$taskStage="D:/MCRF/Builds/Android/p3-steer-source-$([guid]::NewGuid().ToString('N'))";$taskSource="$taskStage/source"
New-Item -ItemType Directory -Path "$taskStage/retained","$taskStage/inputs"|Out-Null
Copy-Item "$taskPrimary/steer/runtime.aar" "$taskStage/retained/runtime.aar";Copy-Item "$taskPrimary/steer/adapter.aar" "$taskStage/retained/adapter.aar"
Save $taskDescriptor "$taskStage/retained/descriptor.json"
foreach($taskDependency in @($taskDescriptor.bundledDependencies)){
 $taskRelative=$taskDependency.file
 if($taskRelative -cnotmatch '^bundled/[A-Za-z0-9_.-]+\.jar$' -or (Hash "$taskPrimary/steer/$taskRelative") -cne $taskDependency.sha256){throw 'Pinned fat-Java dependency changed'}
 $taskTo="$taskStage/retained/$taskRelative";New-Item -ItemType Directory -Path (Split-Path $taskTo) -Force|Out-Null;Copy-Item -LiteralPath "$taskPrimary/steer/$taskRelative" -Destination $taskTo
}
foreach($taskArea in @('src','tests','android-vpn/core/src/main/cpp','android-vpn/tests','build/ngtcp2','build/wolfssl')){
 $taskFrom=Join-Path $taskCanonical $taskArea
 foreach($taskFile in (Get-ChildItem -LiteralPath $taskFrom -Recurse -File|Where-Object {$_.Extension -in @('.c','.cc','.cpp','.h','.hpp','.S','.s','.inc','.cmake','.txt','.patch','.in','.def')})){
  if($taskFile.LinkType){throw 'Linked Steer source input refused'}
  $taskRelative=[IO.Path]::GetRelativePath($taskCanonical,$taskFile.FullName);$taskTo=Join-Path $taskSource $taskRelative
  New-Item -ItemType Directory -Path (Split-Path $taskTo) -Force|Out-Null;Copy-Item -LiteralPath $taskFile.FullName -Destination $taskTo
 }
}
Copy-Item "$taskCanonical/Android.bp" "$taskSource/Android.bp"
Get-ChildItem -LiteralPath $taskCanonical -File|Where-Object {$_.Name -match '^(LICENSE|LICENCE|COPYING|NOTICE)([._-].*)?$'}|ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination "$taskSource/$($_.Name)"}
& "$PSScriptRoot/Apply-SocksUdpSource.ps1" -Source $taskSource
$taskCmake="$taskSource/android-vpn/core/src/main/cpp/CMakeLists.txt"
$taskFixture=@'

# New P3 SOCKS fixtures ONLY; old EXCLUDE_FROM_ALL suites are not invoked.
foreach(NAME mcrf_socks_udp_test mcrf_socks_local_udp_test mcrf_socks_udp_wire_test)
    add_executable(${NAME} EXCLUDE_FROM_ALL "${STEER}/${NAME}.c")
    target_include_directories(${NAME} PRIVATE "${STEER}" "${STEER}/src/proto/proxy" ${INCLUDES} "${CMAKE_CURRENT_LIST_DIR}")
    target_compile_options(${NAME} PRIVATE -UNDEBUG -O2 -ffunction-sections -fdata-sections)
    target_link_options(${NAME} PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
endforeach()
target_sources(mcrf_socks_udp_wire_test PRIVATE "${STEER}/src/proto/proxy/pxwire.c")
target_compile_definitions(mcrf_socks_udp_wire_test PRIVATE HAVE_CONFIG_H WOLFSSL_USER_SETTINGS STEER_DEFAULT_PLATFORM=android STEER_DUALSTACK)
'@
[IO.File]::AppendAllText($taskCmake,$taskFixture,[Text.UTF8Encoding]::new($false))
$taskPins=@{'v5.9.4-stable.tar.gz'='7256bfc89b183a75183806c7debfa203443873b0b4a562e1b80d68e01b45ac57';'ngtcp2-1.25.0.tar.xz'='2a34d2484ba17847a5d11965704e9dd0fac4c6d8efc75ffe1ec7de66d8c6b6fb';'brotli-v1.2.0.tar.gz'='816c96e8e8f193b40151dad7e8ff37b1221d019dbcb9c35cd3fadbfe6477dfec';'zstd-1.5.7.tar.gz'='eb33e51f49a15e023950cd7825ca74a4a2b43db8354825ac24fc1b7ee09e6fa3'}
foreach($taskPin in $taskPins.GetEnumerator()){
 $taskArchive=Join-Path $CodecArchives $taskPin.Key
 if((Get-Item -LiteralPath $taskArchive).LinkType -or (Hash $taskArchive) -cne $taskPin.Value){throw 'Original bounded C dependency archive changed'}
 Copy-Item -LiteralPath $taskArchive -Destination "$taskStage/inputs/$($taskPin.Key)"
}
$taskTls=@{abis=@{}}
$taskZip=[IO.Compression.ZipFile]::OpenRead("$taskStage/retained/runtime.aar")
try{foreach($taskAbi in @('arm64-v8a','x86_64')){
 $taskEntry=$taskZip.GetEntry("jni/$taskAbi/libmcrf_utls49.so");if(!$taskEntry){throw 'Retained genuine TLS helper missing'}
 New-Item -ItemType Directory -Path "$taskStage/tls/$taskAbi" -Force|Out-Null;$taskTo="$taskStage/tls/$taskAbi/libmcrf_utls49.so"
 $taskStream=$taskEntry.Open();try{$taskOut=[IO.File]::Create($taskTo);try{$taskStream.CopyTo($taskOut)}finally{$taskOut.Dispose()}}finally{$taskStream.Dispose()}
 if((Hash $taskTo) -cne $taskDescriptor.tls49.abiSha256[$taskAbi]){throw 'Retained TLS helper hash changed'}
 $taskTls.abis[$taskAbi]=@{path=$taskTo;sha256=Hash $taskTo}
}}finally{$taskZip.Dispose()}
Save $taskTls "$taskStage/tls.json"
$taskRows=@(Get-ChildItem -LiteralPath $taskStage -Recurse -File|ForEach-Object {@{path=[IO.Path]::GetRelativePath($taskStage,$_.FullName).Replace([char]92,[char]47);sha256=Hash $_.FullName;bytes=$_.Length}}|Sort-Object path)
Save $taskRows "$taskStage/SOURCE_MANIFEST.json"
Save @{state='SOURCE_PREPARED_NOT_COMPILED';source=$taskSource;kind='steer';baselineDescriptorVersion=$taskDescriptor.versionCode;baselinePackageVersionNotInferredFromAar=$true;newVersion=15;baselinePrimaryReceiptSha256=Hash "$taskPrimary/receipt.json";manifestSha256=Hash "$taskStage/SOURCE_MANIFEST.json";compiled=$false;executed=$false;producerComplete=$false} "$taskStage/receipt.json"
Write-Output "P3_STEER_SOURCE=$taskStage"
