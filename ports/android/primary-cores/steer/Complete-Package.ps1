param([Parameter(Mandatory)][string]$NativeStage)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-package
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 14),[Text.UTF8Encoding]::new($false))}
$taskStage=(Resolve-Path -LiteralPath $NativeStage).Path
if($taskStage.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-steer-native-[a-f0-9]{32}$'){throw 'Exact owned Steer native stage required'}
$taskReceipt=Get-Content "$taskStage/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
$taskCases=@('mcrf_socks_udp_test','mcrf_socks_local_udp_test','mcrf_socks_udp_wire_test')
if($taskReceipt.newVersion -eq 16){$taskCases+='mcrf_private_socks_profile_test';if(!$taskReceipt.retainedSocks.unchangedFixtureSources){throw 'Retained exact SOCKS source proof required'}}
if($taskReceipt.state -cne 'COMPILED_SOCKS_FIXTURE_VERIFIED' -or !$taskReceipt.sourceStable -or $taskReceipt.newVersion -notin @(15,16) -or @($taskCases|Where-Object {$_ -cnotin $taskReceipt.cases}).Count){throw 'All required actual C fixtures required before packaging'}
$taskPrepared=$taskReceipt.preparedSource
if($taskPrepared.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-steer-source-[a-f0-9]{32}$' -or $taskReceipt.sourceManifestSha256 -cne (Hash "$taskPrepared/SOURCE_MANIFEST.json")){throw 'Frozen original C/source closure changed'}
foreach($taskRow in (Get-Content "$taskPrepared/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)){
 if([IO.Path]::IsPathRooted($taskRow.path) -or '..' -in ($taskRow.path -split '[/\\]')){throw 'Unsafe source closure path'}
 $taskInput=Join-Path $taskPrepared $taskRow.path
 if((Get-Item -LiteralPath $taskInput).LinkType -or (Get-Item $taskInput).Length -ne $taskRow.bytes -or (Hash $taskInput) -cne $taskRow.sha256){throw 'Native source/archive changed after compilation'}
}
$taskDestination="$taskStage/primary-stage";if(Test-Path -LiteralPath $taskDestination){throw 'Packaging already exists; retain it, never overwrite'}
New-Item -ItemType Directory -Path "$taskDestination/steer"|Out-Null
$taskDescriptor=Get-Content "$taskPrepared/retained/descriptor.json" -Raw|ConvertFrom-Json -AsHashtable
Copy-Item "$taskPrepared/retained/adapter.aar" "$taskDestination/steer/adapter.aar"
Copy-Item "$taskPrepared/retained/runtime.aar" "$taskDestination/steer/retained-runtime.aar"
Copy-Item "$taskPrepared/SOURCE_MANIFEST.json" "$taskDestination/steer/source-manifest.json"
$taskProof=@{state='VERIFIED_NEW_SOCKS_C_ONLY';version=1;sourceManifestSha256=$taskReceipt.sourceManifestSha256;retainedRuntimeSha256=Hash "$taskPrepared/retained/runtime.aar";retainedAdapterSha256=Hash "$taskPrepared/retained/adapter.aar";abiExecuted='x86_64';cases=$taskCases;attempts=@();nativeArtifacts=$taskReceipt.artifacts;nonNativeUnchanged=$false}
foreach($taskCase in $taskCases){
 $taskPass=@($taskReceipt.ownedAttempts|ForEach-Object {$_.cases}|Where-Object {$_.case -ceq $taskCase -and $_.exit -eq 0})|Select-Object -Last 1
 $taskFixture=@($taskReceipt.fixtures|Where-Object {$_.abi -ceq 'x86_64' -and $_.file -ceq "x86_64/$taskCase"})
 if(!$taskPass -or $taskFixture.Count -ne 1 -or $taskPass.sha256 -cne $taskFixture[0].sha256 -or $taskPass.logSha256 -cne (Hash $taskPass.log)){throw 'Actual owned C exit/log proof missing'}
 Copy-Item -LiteralPath $taskPass.log -Destination "$taskDestination/steer/$taskCase.log"
 $taskProof.attempts+=@{case=$taskCase;exit=0;abi='x86_64';binarySha256=$taskPass.sha256;logSha256=$taskPass.logSha256;log="$taskCase.log"}
}
$taskOld=[IO.Compression.ZipFile]::OpenRead("$taskPrepared/retained/runtime.aar")
$taskNew=[IO.Compression.ZipArchive]::new([IO.File]::Create("$taskDestination/steer/runtime.aar"),[IO.Compression.ZipArchiveMode]::Create)
try{
 $taskNames=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
 foreach($taskEntry in $taskOld.Entries){
  if(!$taskNames.Add($taskEntry.FullName)){throw 'Ambiguous retained AAR entries'}
  $taskTarget=$taskNew.CreateEntry($taskEntry.FullName,[IO.Compression.CompressionLevel]::Optimal)
  $taskInput=$null;$taskOutput=$taskTarget.Open()
  try{
   if($taskEntry.FullName -cmatch '^jni/(arm64-v8a|x86_64)/libsteer_vpn\.so$'){
    $taskAbi=$Matches[1];$taskRow=@($taskReceipt.artifacts|Where-Object {$_.abi -ceq $taskAbi -and $_.file -ceq "$taskAbi/libsteer_vpn.so"})
    if($taskRow.Count -ne 1 -or $taskRow[0].sha256 -cne (Hash "$taskStage/$taskAbi/libsteer_vpn.so")){throw 'New compiled Steer bytes changed'}
    $taskInput=[IO.File]::OpenRead("$taskStage/$taskAbi/libsteer_vpn.so")
   }else{$taskInput=$taskEntry.Open()}
   $taskInput.CopyTo($taskOutput)
  }finally{if($taskInput){$taskInput.Dispose()};$taskOutput.Dispose()}
 }
 if(@('jni/arm64-v8a/libsteer_vpn.so','jni/x86_64/libsteer_vpn.so')|Where-Object {!$taskNames.Contains($_)}){throw 'Both real Steer ABIs required'}
}finally{$taskOld.Dispose();$taskNew.Dispose()}
function Entry-Hash($Entry){$taskStream=$Entry.Open();try{[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($taskStream)).ToLowerInvariant()}finally{$taskStream.Dispose()}}
$taskOld=[IO.Compression.ZipFile]::OpenRead("$taskPrepared/retained/runtime.aar");$taskNew=[IO.Compression.ZipFile]::OpenRead("$taskDestination/steer/runtime.aar")
try{foreach($taskEntry in $taskOld.Entries){if($taskEntry.FullName -cnotmatch '^jni/(arm64-v8a|x86_64)/libsteer_vpn\.so$'){
 $taskOther=$taskNew.GetEntry($taskEntry.FullName);if(!$taskOther -or $taskEntry.Length -ne $taskOther.Length -or (Entry-Hash $taskEntry) -cne (Entry-Hash $taskOther)){throw 'Unchanged Java/assets/TLS helper not preserved'}
}}}finally{$taskOld.Dispose();$taskNew.Dispose()}
foreach($taskDependency in @($taskDescriptor.bundledDependencies)){
 $taskRelative=$taskDependency.file;if($taskRelative -cnotmatch '^bundled/[A-Za-z0-9_.-]+\.jar$' -or (Hash "$taskPrepared/retained/$taskRelative") -cne $taskDependency.sha256){throw 'Bundled Java dependency changed'}
 $taskTo="$taskDestination/steer/$taskRelative";New-Item -ItemType Directory -Path (Split-Path $taskTo) -Force|Out-Null;Copy-Item "$taskPrepared/retained/$taskRelative" $taskTo
}
$taskProof.nonNativeUnchanged=$true;Save $taskProof "$taskDestination/steer/p3-socks-proof.json"
if($taskReceipt.newVersion -eq 16){$taskProof.retainedSocks=$taskReceipt.retainedSocks;$taskProof.privateHelperProfile=1;Save $taskProof "$taskDestination/steer/p3-socks-proof.json"}
$taskDescriptor.Remove('p1Candidate');$taskDescriptor.Remove('p2Candidate')
$taskDescriptor.versionCode=15;$taskDescriptor.moduleVersion='0.3.3-mcrf.15';$taskDescriptor.sourceSha256=$taskReceipt.sourceManifestSha256
$taskDescriptor.sourceVersion='0.3.3-mcrf-socks-udp.15';$taskDescriptor.p3SocksCandidate=$true;$taskDescriptor.p3SocksProofSha256=Hash "$taskDestination/steer/p3-socks-proof.json"
$taskDescriptor.socksUdpSourcePort=1
if($taskReceipt.newVersion -eq 16){
 $taskDescriptor.versionCode=16;$taskDescriptor.moduleVersion='0.3.3-mcrf.16';$taskDescriptor.sourceVersion='0.3.3-mcrf-private-helper.16';$taskDescriptor.privateHelperProfile=1
}
foreach($taskArtifact in $taskDescriptor.artifacts){$taskFile="$taskDestination/steer/$($taskArtifact.file)";$taskArtifact.sha256=Hash $taskFile;$taskArtifact.bytes=(Get-Item $taskFile).Length}
Save @{version=1;modules=@{steer=$taskDescriptor}} "$taskDestination/receipt.json"
$taskReceipt.primaryStage=$taskDestination;$taskReceipt.primaryStageReceiptSha256=Hash "$taskDestination/receipt.json";$taskReceipt.producerComplete=$true;$taskReceipt.state='COMPILED_SOCKS_FIXTURE_PACKAGED_NOT_PHONE_VERIFIED';Save $taskReceipt "$taskStage/receipt.json"
Write-Output "P3_STEER_SOURCE_BOUND_PRIMARY_STAGE=$taskDestination"
