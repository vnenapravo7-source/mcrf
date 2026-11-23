param([Parameter(Mandatory)][string]$Plan,[string]$Resume='',[switch]$Rebase)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation build
$taskRoot='D:/MCRF/Projects/MCRF'
$taskBuildRoot='D:/MCRF/Builds/Android'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 25),[Text.UTF8Encoding]::new($false))}
function Canonical($Value){
 if($Value -is [Collections.IDictionary]){
  $result=[ordered]@{};foreach($key in ($Value.Keys|Sort-Object -CaseSensitive)){$result[$key]=Canonical $Value[$key]};return $result
 }
 if($Value -is [array]){return ,@($Value|ForEach-Object {Canonical $_})}
 return $Value
}
function RetentionShape($Node){
 # The evidence-only SSTP reader consumes its own script, the authority and
 # immutable original logs/source manifests, not unrelated maintenance scripts.
 $copy=[ordered]@{};foreach($key in $Node.Keys){$copy[$key]=$Node[$key]}
 if($Node.script -cne 'maintenance/Read-P3RetainedJvm.ps1'){
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/maintenance/Read-P3RetainedJvm.ps1',[StringComparison]::Ordinal)})
 }
 if($Node.script -cne 'primary-cores/steer/Complete-NoticeFiles.ps1'){
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/steer/Complete-NoticeFiles.ps1',[StringComparison]::Ordinal)})
 }
 if($Node.script -cne 'transports/slipstream/Export-FixtureProof.ps1'){
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/slipstream/Export-FixtureProof.ps1',[StringComparison]::Ordinal)})
 }
 if($Node.script -cne 'transports/slipstream/Build-AndroidFixture.ps1'){
  # Earlier producers consumed their immutable prepared source, not this
  # later Android-only test discovery/diagnostic repair. Production sources,
  # original compiler proofs and all retained raw case logs remain bound.
  $copy.inputs=@($copy.inputs|Where-Object {
   $p=$_.path.Replace([char]92,[char]47)
   !$p.EndsWith('/slipstream/Build-AndroidFixture.ps1',[StringComparison]::Ordinal) -and
   !$p.EndsWith('/slipstream/bridge/process_linux_test.go',[StringComparison]::Ordinal) -and
   !$p.EndsWith('/slipstream/bridge/wire_linux_test.go',[StringComparison]::Ordinal)
  })
 }
 foreach($later in @('Advance-SignatureSource.ps1','Retain-IndependentEvidence.ps1')){
  if([IO.Path]::GetFileName($Node.script) -cne $later){$copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith("/slipstream/$later",[StringComparison]::Ordinal)})}
 }
 if($Node.script -cne 'maintenance/Build-P3SlipstreamFixtureApp.ps1'){
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/maintenance/Build-P3SlipstreamFixtureApp.ps1',[StringComparison]::Ordinal)})
 }
 if($Node.script -cne 'transports/slipstream/Recover-LegalInventory.ps1'){
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/slipstream/Recover-LegalInventory.ps1',[StringComparison]::Ordinal)})
 }
 if($Node.script.StartsWith('transports/slipstream/') -and $Node.script -cne 'transports/slipstream/Invoke-OwnedFixture.ps1'){
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/slipstream/Invoke-OwnedFixture.ps1',[StringComparison]::Ordinal)})
 }
 if($Node.script.StartsWith('transports/slipstream/') -and $Node.script -cne 'transports/slipstream/Complete-NativeNotices.ps1'){
  # Licence packaging is an independent later node. Its implementation is
  # not consumed by retained compilers; original artifact/proof hashes remain.
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/slipstream/Complete-NativeNotices.ps1',[StringComparison]::Ordinal)})
 }
 # The coordinator verifies and records the new frozen plan itself. It is not
 # an input to any producer: changing resume bookkeeping cannot change a
 # previously compiled artifact. Producer scripts and their data stay bound.
 $copy.inputs=@($copy.inputs|Where-Object {
  $path=$_.path.Replace([char]92,[char]47)
  !$path.EndsWith('/maintenance/Build-P3P4Common.ps1',[StringComparison]::Ordinal) -and
  !$path.EndsWith('/maintenance/New-P3P4CommonDefinition.ps1',[StringComparison]::Ordinal) -and
  !$path.EndsWith('/maintenance/Freeze-P3P4CommonPlan.ps1',[StringComparison]::Ordinal)
 })
 # Explicit independent diagnostic/recovery producers are not inputs of
 # historical source/native/APK nodes in the same maintenance directory.
 $copy.inputs=@($copy.inputs|Where-Object {
  $path=$_.path.Replace([char]92,[char]47)
  ($Node.script -ceq 'maintenance/Build-P3ProtocolDiagnosticApp.ps1' -or !$path.EndsWith('/maintenance/Build-P3ProtocolDiagnosticApp.ps1',[StringComparison]::Ordinal)) -and
  !$path.EndsWith('/maintenance/Capture-P3FailedFixture.ps1',[StringComparison]::Ordinal)
 })
 if($Node.script -cne 'maintenance/Build-P3ProtocolFixtureApp.ps1'){
  # This independent later fixture-app producer is not called by earlier
  # native/source producers, including the WDTT wrapper in the same directory.
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/maintenance/Build-P3ProtocolFixtureApp.ps1',[StringComparison]::Ordinal)})
 }
 if($Node.operation -ceq 'source'){
  # Later compiler/fixture/evidence-parser implementations do not create the
  # already-frozen source. Keep every data input and source overlay/helper,
  # plus this source producer itself; do not bind it to unrelated later tools.
  $own=[IO.Path]::GetFileName($Node.script)
  $copy.inputs=@($copy.inputs|Where-Object {
   $name=[IO.Path]::GetFileName($_.path)
   $name -ceq $own -or $name -notmatch '^(?:Build-|Invoke-.*(?:Checks|Fixture)|Complete-|Read-.*Evidence).*\.ps1$'
  })
 }
 if($Node.script -ceq 'maintenance/Read-P3SstpHostEvidence.ps1'){
  $copy.inputs=@($copy.inputs|Where-Object {
   $path=$_.path.Replace([char]92,[char]47)
   !$path.Contains('/maintenance/') -or $path.EndsWith('/maintenance/Read-P3SstpHostEvidence.ps1',[StringComparison]::Ordinal)
  })
 }
 if($Node.script -cin @('transports/protocols/Prepare-Source.ps1','transports/protocols/Prepare-CronetNotices.ps1')){
  # This later source-revision node is not called by the original source or
  # licence preparer. Its own node and new frozen manifest bind the revision.
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/protocols/Advance-PreparedSource.ps1',[StringComparison]::Ordinal)})
 }
 if($Node.script -ceq 'transports/protocols/Prepare-Source.ps1'){
  # Licence collection is a later independent graph node; it does not create
  # the frozen SDK/adapter source or participate in its preparation.
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/protocols/Prepare-CronetNotices.ps1',[StringComparison]::Ordinal)})
 }
 if($Node.script -ceq 'transports/protocols/Prepare-CronetNotices.ps1'){
  # Original licence collection never invokes the later native compiler.
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/protocols/Build-Native.ps1',[StringComparison]::Ordinal)})
 }
 if($Node.script -ceq 'transports/protocols/Build-Native.ps1'){
  # Legal promotion is a separate later node and never participates in the
  # already-completed compilation or generated native fixture.
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/protocols/Complete-NativeNotices.ps1',[StringComparison]::Ordinal)})
 }
 if($Node.script -ceq 'transports/slipstream/Prepare-Source.ps1'){
  # Dependency archive intake is a separate later producer, not part of the
  # frozen Rust/Go adapter source preparation.
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/slipstream/Prepare-BuildInputs.ps1',[StringComparison]::Ordinal)})
 }
 if($Node.script -cin @('transports/slipstream/Prepare-Source.ps1','transports/slipstream/Prepare-BuildInputs.ps1')){
  # Original-C interoperability source intake is an independent later node.
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/slipstream/Prepare-CFixtureInputs.ps1',[StringComparison]::Ordinal)})
 }
 if($Node.script -cin @('transports/slipstream/Prepare-Source.ps1','transports/slipstream/Prepare-BuildInputs.ps1','transports/slipstream/Prepare-CFixtureInputs.ps1')){
  # Isolated optional Perl metadata is consumed by the later C-dependency node.
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/slipstream/Prepare-PerlInput.ps1',[StringComparison]::Ordinal)})
 }
 if($Node.operation -ceq 'source' -and $Node.script.StartsWith('transports/slipstream/') -and $Node.script -cne 'transports/slipstream/Prepare-PerlMakeMakerInput.ps1'){
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/slipstream/Prepare-PerlMakeMakerInput.ps1',[StringComparison]::Ordinal)})
 }
 if($Node.operation -ceq 'source' -and $Node.script.StartsWith('transports/slipstream/') -and $Node.script -cne 'transports/slipstream/Prepare-PerlPodInputs.ps1'){
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/slipstream/Prepare-PerlPodInputs.ps1',[StringComparison]::Ordinal)})
 }
 if($Node.operation -ceq 'source' -and $Node.script.StartsWith('transports/slipstream/') -and $Node.script -cne 'transports/slipstream/Prepare-PkgconfInput.ps1'){
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/slipstream/Prepare-PkgconfInput.ps1',[StringComparison]::Ordinal)})
 }
 if($Node.script -ceq 'transports/slipstream/Build-CDependencies.ps1'){
  # The completed C dependencies never invoke the later Rust/Go producer.
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/slipstream/Build-Native.ps1',[StringComparison]::Ordinal)})
 }
 if($Node.script -cin @('transports/sstp/Prepare-Source.ps1','transports/sstp/Build-Native.ps1')){
  # Source preparation never invokes the later log parser; native compilation
  # consumes the exact retained host-proof hash, not the parser implementation.
  $copy.inputs=@($copy.inputs|Where-Object {!$_.path.Replace([char]92,[char]47).EndsWith('/transports/sstp/Invoke-HostChecks.ps1',[StringComparison]::Ordinal)})
 }
 return Canonical $copy
}
function Regular([string]$Path){
 $item=Get-Item -LiteralPath $Path
 if(!$item.PSIsContainer -and !($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){return $item.FullName}
 throw 'Expected regular evidence/source file, not a link or directory'
}
$taskPlanPath=Regular $Plan
$taskPlan=Get-Content -LiteralPath $taskPlanPath -Raw|ConvertFrom-Json -AsHashtable
if(!$taskPlan.sourceManifest -or (Hash (Regular $taskPlan.sourceManifest)) -cne $taskPlan.sourceManifestSha256){throw 'Frozen whole-source manifest required'}
$taskWholeSource=@(Get-Content -LiteralPath $taskPlan.sourceManifest -Raw|ConvertFrom-Json)
function Assert-WholeSource {
 foreach($row in $taskWholeSource){if((Hash (Regular $row.path)) -cne $row.sha256){throw 'Whole-source package changed after freeze; preserve completed evidence before explicitly rebasing'}}
}
Assert-WholeSource
if($taskPlan.schema -ne 1 -or $taskPlan.authority -cne 'human-20261023-finish-current-fixes-then-p3-p4' -or
 $taskPlan.state -cne 'WHOLE_SOURCE_FROZEN_COMMON_PLAN' -or $taskPlan.publish -ne $false -or $taskPlan.replayAccepted -ne $false){throw 'Exact whole-source P3/P4 plan required; no publication or accepted-test replay'}
$taskFamilies=@('app','csqtt','csqtt20','csqtt215','csqtt25','dnstt','masterdnsvpn','olcrtc','protocols','qwdtt','slipstream','sstp','steer','vaydns','wdtt')
if((@($taskPlan.families|Sort-Object -Unique) -join ',') -cne ($taskFamilies -join ',')){throw 'The common plan must cover the complete P3/P4 package'}
$taskSteps=@($taskPlan.steps);$taskIds=@($taskSteps|ForEach-Object {$_.id})
if(!$taskSteps.Count -or @($taskIds|Sort-Object -Unique).Count -ne $taskSteps.Count){throw 'Nonempty unique common steps required'}
if((@($taskSteps|ForEach-Object {$_.families}|Sort-Object -Unique) -join ',') -cne ($taskFamilies -join ',')){throw 'Family labels alone cannot replace actual producer coverage'}
$taskAllowed=@('integration-build.ps1','maintenance/Read-P3RetainedJvm.ps1','maintenance/Build-P3SlipstreamFixtureApp.ps1','maintenance/Invoke-P3WdttPerformance.ps1','maintenance/Read-P3SstpHostEvidence.ps1','maintenance/Prepare-P3SteerCodecInputs.ps1','maintenance/Build-P3ProtocolFixtureApp.ps1','maintenance/Build-P3ProtocolDiagnosticApp.ps1','primary-cores/steer/Prepare-Source.ps1','primary-cores/steer/Build-Native.ps1',
 'primary-cores/steer/Invoke-OwnedFixture.ps1','primary-cores/steer/Complete-Package.ps1','primary-cores/steer/Complete-NoticeFiles.ps1')
foreach($kind in @('vaydns','dnstt','olcrtc','masterdnsvpn','sstp')){
 foreach($file in @('Prepare-Source.ps1','Invoke-HostChecks.ps1','Build-Native.ps1')){$taskAllowed+="transports/$kind/$file"}
}
 foreach($file in @('Prepare-Source.ps1','Advance-PreparedSource.ps1','Prepare-CronetNotices.ps1','Build-Native.ps1','Complete-NativeNotices.ps1','Invoke-OwnedFixture.ps1')){$taskAllowed+="transports/protocols/$file"}
foreach($file in @('Export-FixtureProof.ps1','Build-AndroidFixture.ps1','Advance-SignatureSource.ps1','Retain-IndependentEvidence.ps1','Prepare-Source.ps1','Prepare-BuildInputs.ps1','Prepare-PerlInput.ps1','Prepare-PerlMakeMakerInput.ps1','Prepare-PerlPodInputs.ps1','Prepare-PkgconfInput.ps1','Prepare-CFixtureInputs.ps1','Build-CDependencies.ps1','Build-Native.ps1','Build-CFixture.ps1','Complete-NativeNotices.ps1','Recover-LegalInventory.ps1','Invoke-OwnedFixture.ps1')){$taskAllowed+="transports/slipstream/$file"}
$taskAllowed+=@('transports/swg/performance/Prepare-TurnSource.ps1','transports/swg/performance/Invoke-HostChecks.ps1','transports/swg/performance/Build-TurnNative.ps1',
 'transports/swg/csqtt25/Prepare-P3AbiSource.ps1','transports/swg/csqtt25/Invoke-P3HostChecks.ps1','transports/swg/csqtt25/Build-P3AbiNative.ps1','transports/swg/Prepare-CsqttHistoricalPackage.ps1')
foreach($step in $taskSteps){
 if($step.id -cnotmatch '^[a-z][a-z0-9-]{0,63}$' -or $step.script -cnotin $taskAllowed -or $step.operation -cnotin @('source','host','native','legal','fixture','app') -or
    $step.replayAccepted -ne $false -or !$step.inputs.Count -or !$step.receiptStates.Count){throw 'Unknown or unbounded common step'}
 if((!$step.outputStage -and $step.outputKey -cnotmatch '^[A-Z][A-Z0-9_]+$') -or $step.receiptSuffix -cnotin @('/receipt.json','/build-receipt.json','/build-result.json')){throw 'Explicit producer output/receipt contract required'}
 if($step.script -ceq 'integration-build.ps1'){
  $tasks=@($step.parameters.Task)
  if(!$tasks.Count -or $tasks -ccontains ':app:testDebugUnitTest' -and !($tasks -ccontains '--tests')){throw 'Do not run the default entire historical JVM suite'}
  for($index=0;$index -lt $tasks.Count;$index++){
   if($tasks[$index] -ceq '--tests' -and ($index+1 -ge $tasks.Count -or $tasks[$index+1] -cnotmatch '^org\.mcrf\.client\.[A-Za-z0-9]+(?:\.[A-Za-z0-9]+)?$')){throw 'Explicit named affected cases only; no wildcard historical replay'}
  }
 }
}
if($Resume){
 $taskStage=(Resolve-Path -LiteralPath $Resume).Path.Replace([char]92,[char]47)
 if($taskStage -cnotmatch '^D:/MCRF/Builds/Android/p3-p4-common-[a-f0-9]{32}$'){throw 'Exact existing owned common batch required'}
 $taskReceipt=Get-Content -LiteralPath (Regular "$taskStage/receipt.json") -Raw|ConvertFrom-Json -AsHashtable
 if($taskReceipt.planSha256 -cne (Hash $taskPlanPath)){
  if(!$Rebase){throw 'Resume cannot silently replace the frozen common recipe'}
  $oldFile=if($taskReceipt.latestPlan){$taskReceipt.latestPlan}else{"$taskStage/PLAN.json"}
  if((Hash (Regular $oldFile)) -cne $taskReceipt.planSha256){throw 'Previous exact plan proof required for explicit rebase'}
  $oldPlan=Get-Content -LiteralPath $oldFile -Raw|ConvertFrom-Json -AsHashtable
  foreach($id in $taskReceipt.steps.Keys){
   $before=@($oldPlan.steps|Where-Object id -CEQ $id);$after=@($taskSteps|Where-Object id -CEQ $id)
   if($before.Count -ne 1 -or $after.Count -ne 1 -or ((RetentionShape $before[0])|ConvertTo-Json -Depth 30 -Compress) -cne ((RetentionShape $after[0])|ConvertTo-Json -Depth 30 -Compress)){throw 'A completed step changed: explicit scoped evidence review required; never replay it automatically'}
  }
  $taskReceipt.previousPlans=@($taskReceipt.previousPlans)+@(@{path=$oldFile;sha256=$taskReceipt.planSha256})
  $taskReceipt.planSha256=Hash $taskPlanPath;$taskReceipt.latestPlan="$taskStage/PLAN-$($taskReceipt.planSha256).json"
  Copy-Item -LiteralPath $taskPlanPath -Destination $taskReceipt.latestPlan
 }
}else{
 $taskStage="$taskBuildRoot/p3-p4-common-$([guid]::NewGuid().ToString('N'))"
 New-Item -ItemType Directory -Path $taskStage|Out-Null
 Copy-Item -LiteralPath $taskPlanPath -Destination "$taskStage/PLAN.json"
 $taskReceipt=[ordered]@{state='COMMON_RUNNING_NOT_ACCEPTED';authority=$taskPlan.authority;planSha256=Hash $taskPlanPath;steps=@{};liveProofs=@{};published=$false;phoneVerified=$false;oldTestsReplayed=$false}
}
foreach($liveProof in $taskReceipt.liveProofs.Keys){if((Hash (Regular $liveProof)) -cne $taskReceipt.liveProofs[$liveProof]){throw 'Latest retained producer proof changed outside this common batch'}}
function Assert-Inputs($Step){
 foreach($row in $Step.inputs){
  $file=Regular $row.path
  if(!$file.Replace([char]92,[char]47).StartsWith('D:/MCRF/',[StringComparison]::OrdinalIgnoreCase) -or $row.sha256 -cnotmatch '^[a-f0-9]{64}$' -or (Hash $file) -cne $row.sha256){throw "Frozen common input changed: $($Step.id)"}
 }
 $script=Regular "$taskRoot/ports/android/$($Step.script)"
 if($Step.scriptSha256 -cne (Hash $script)){throw 'Producer source changed after whole-source freeze'}
}
function Resolve-Value($Value){
 if($Value -is [System.Collections.IDictionary]){
  if($Value.Count -eq 1 -and $Value.Contains('map')){
   $mapped=@{};foreach($mapKey in $Value.map.Keys){$mapped[$mapKey]=Resolve-Value $Value.map[$mapKey]};return $mapped
  }
  if(@($Value.Keys|Where-Object {$_ -cnotin @('stage','suffix')}).Count -or !$taskReceipt.steps.Contains($Value.stage)){throw 'Only explicit earlier-stage output references are accepted'}
  $saved=$taskReceipt.steps[$Value.stage]
  if($saved.state -cne 'PRODUCER_COMPLETED_NOT_PHONE_ACCEPTED' -or $Value.suffix -cnotmatch '^(/[a-zA-Z0-9_.-]+)*$'){throw 'Uncompleted stage or unsafe suffix'}
  return $saved.output+$Value.suffix
 }
 if($Value -is [array]){return ,@($Value|ForEach-Object {Resolve-Value $_})}
 if($Value -is [string] -or $Value -is [bool] -or $Value -is [long] -or $Value -is [int]){return $Value}
 throw 'Unsupported producer argument type'
}
Save $taskReceipt "$taskStage/receipt.json"
$taskReceipt.state='COMMON_RUNNING_NOT_ACCEPTED';$taskReceipt.failure=$null
Write-Output "P3_P4_COMMON=$taskStage"
try{
 foreach($step in $taskSteps){
  if($step.operation -ceq 'app'){Assert-WholeSource}
  Assert-Inputs $step
  $prior=$taskReceipt.steps[$step.id]
  if($prior -and $prior.state -ceq 'PRODUCER_COMPLETED_NOT_PHONE_ACCEPTED'){
   if((Hash (Regular $prior.receipt)) -cne $prior.receiptSha256 -or (Hash (Regular $prior.log)) -cne $prior.logSha256){throw 'Retained common step proof changed; never rerun a completed case to hide drift'}
   if($step.operation -ceq 'source'){& "$PSScriptRoot/Prepare-P3GoDependencyCache.ps1" -SourceStage $prior.output}
   continue
  }
  foreach($dependency in $step.dependsOn){if($taskReceipt.steps[$dependency].state -cne 'PRODUCER_COMPLETED_NOT_PHONE_ACCEPTED'){throw 'Common dependency is not complete'}}
  $arguments=@{};foreach($key in $step.parameters.Keys){$arguments[$key]=Resolve-Value $step.parameters[$key]}
  $attempt=[guid]::NewGuid().ToString('N');$log="$taskStage/$($step.id)-$attempt.log"
  $taskReceipt.active=$step.id;$taskReceipt.activeLog=$log;Save $taskReceipt "$taskStage/receipt.json"
  # Existing producers own exact source/native/legal/case verification. This
  # coordinator records their evidence, not invented successes or site speed.
  & "$taskRoot/ports/android/$($step.script)" @arguments 2>&1|Tee-Object -FilePath $log
  Assert-Inputs $step
  if($step.outputStage){$output=Resolve-Value $step.outputStage}else{
   $outputs=@(Get-Content -LiteralPath $log|Where-Object {$_ -cmatch "^$($step.outputKey)="})
   if($outputs.Count -ne 1){throw 'Producer must identify one exact owned result'}
   $output=$outputs[0].Substring($step.outputKey.Length+1)
  }
  $output=$output.Replace([char]92,[char]47)
  if($output -cnotmatch '^D:/MCRF/Builds/Android/[a-zA-Z0-9_.-]+$' -and $output -cnotmatch '^D:/MCRF/Projects/MCRF/work/android-integration-[a-f0-9]{32}$'){throw 'Producer output must stay inside an exact owned Android stage'}
  $proofPath=Regular ($output+$step.receiptSuffix)
  $proof=Get-Content -LiteralPath $proofPath -Raw|ConvertFrom-Json
  if($step.script -ceq 'integration-build.ps1'){
   if($proof.exitCode -cne 0 -or $proof.sourceStable -ne $true){throw 'Whole integration did not complete with frozen source'}
  }elseif($proof.state -cnotin $step.receiptStates){throw 'Producer did not reach its explicit expected state'}
  if($step.operation -ceq 'source'){& "$PSScriptRoot/Prepare-P3GoDependencyCache.ps1" -SourceStage $output}
  # Legal/fixture steps intentionally promote the SAME live native receipt.
  # Retain each historical step's exact immutable proof while validating the
  # latest promoted bytes on resume. No successful case is replayed.
  $snapshot="$taskStage/$($step.id)-$attempt-receipt.json"
  Copy-Item -LiteralPath $proofPath -Destination $snapshot
  $taskReceipt.liveProofs[$proofPath]=Hash $proofPath
  $taskReceipt.steps[$step.id]=@{state='PRODUCER_COMPLETED_NOT_PHONE_ACCEPTED';output=$output;receipt=$snapshot;receiptSha256=Hash $snapshot;log=$log;logSha256=Hash $log}
  Save $taskReceipt "$taskStage/receipt.json"
 }
 $taskReceipt.active=$null;$taskReceipt.state='COMMON_ARTIFACTS_RECORDED_PHONE_AND_LIVE_CASES_PENDING'
}catch{$taskReceipt.state='COMMON_FAILED_PRESERVED';$taskReceipt.failure=$_.Exception.Message;throw}
finally{Save $taskReceipt "$taskStage/receipt.json"}
