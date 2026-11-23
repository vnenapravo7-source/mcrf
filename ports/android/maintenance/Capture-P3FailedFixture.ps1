param([Parameter(Mandatory)][string]$CommonStage)
$ErrorActionPreference='Stop'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 30),[Text.UTF8Encoding]::new($false))}
function Canonical($Value){
 if($Value -is [Collections.IDictionary]){$d=[ordered]@{};foreach($k in ($Value.Keys|Sort-Object -CaseSensitive)){$d[$k]=Canonical $Value[$k]};return $d}
 if($Value -is [array]){return ,@($Value|ForEach-Object {Canonical $_})};return $Value
}
$taskRoot=(Resolve-Path $CommonStage).Path.Replace([char]92,[char]47)
if($taskRoot -cnotmatch '^D:/MCRF/Builds/Android/p3-p4-common-[a-f0-9]{32}$'){throw 'Owned common failure root required'}
$taskReceipt=Get-Content "$taskRoot/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
if($taskReceipt.state -ceq 'COMMON_FAILED_PRESERVED' -and $taskReceipt.active -ceq 'slipstream-fixture'){
 $taskPrior=$taskReceipt.steps['slipstream-legal-recovery']
 if($taskReceipt.steps.ContainsKey('slipstream-signature-inventory')){$taskPrior=$taskReceipt.steps['slipstream-signature-inventory']}
 if($taskReceipt.steps.ContainsKey('slipstream-android-fixture')){$taskPrior=$taskReceipt.steps['slipstream-android-fixture']}
 if($taskPrior.receiptSha256 -cne (Hash $taskPrior.receipt)){throw 'Original Slipstream legal proof changed'}
 $taskBefore=Get-Content $taskPrior.receipt -Raw|ConvertFrom-Json -AsHashtable
 $taskNative=$taskReceipt.steps['slipstream-native'].output
 if($taskReceipt.steps.ContainsKey('slipstream-signature-native')){$taskNative=$taskReceipt.steps['slipstream-signature-native'].output}
 $taskPath="$taskNative/receipt.json"
 $taskAfter=Get-Content $taskPath -Raw|ConvertFrom-Json -AsHashtable
 if($taskAfter.state -cne 'FIXTURE_FAILED_PRESERVED'){throw 'Actual failed Slipstream fixture required'}
 foreach($k in @((@($taskBefore.Keys)+@($taskAfter.Keys))|Sort-Object -Unique)){
  if($k -ceq 'state'){continue}
  if(((Canonical $taskBefore[$k])|ConvertTo-Json -Depth 30 -Compress) -cne ((Canonical $taskAfter[$k])|ConvertTo-Json -Depth 30 -Compress)){throw "Failed Slipstream fixture changed non-runtime proof: $k"}
 }
 foreach($row in @($taskAfter.artifacts)+@($taskAfter.fixtureArtifacts)){if((Hash "$taskNative/$($row.file)") -cne $row.sha256){throw 'Compiled Slipstream artifact changed'}}
 $taskEvidence=Get-Content "$taskNative/fixture-evidence.json" -Raw|ConvertFrom-Json
 $taskPassed=@()
 foreach($attempt in $taskEvidence.attempts|Where-Object {$_}){
  if((Hash "$taskNative/$($attempt.log)") -cne $attempt.sha256){throw 'Slipstream raw attempt changed'}
  $taskPassed+=@(Get-Content "$taskNative/$($attempt.log)"|Where-Object {$_ -cmatch '^--- PASS: (TestMcrfSlipstream[A-Za-z0-9_]+) \('}|ForEach-Object {[regex]::Match($_,'^--- PASS: (\w+)').Groups[1].Value})
 }
 if((@($taskPassed|Sort-Object -Unique)-join ',') -cne (@($taskEvidence.cases|Sort-Object -Unique)-join ',')){throw 'Retained Slipstream case receipts disagree'}
 $taskCapture="$taskRoot/failed-slipstream-fixture-$([guid]::NewGuid().ToString('N')).json"
 Copy-Item -LiteralPath $taskPath -Destination $taskCapture
 $taskLiveKeys=@($taskReceipt.liveProofs.Keys|Where-Object {$_.Replace([char]92,[char]47) -ceq $taskPath.Replace([char]92,[char]47)})
 if($taskLiveKeys.Count -ne 1){throw 'Original live Slipstream proof missing'}
 if(!$taskReceipt.ContainsKey('failedProofCaptures')){$taskReceipt.failedProofCaptures=@()}
 $taskReceipt.failedProofCaptures+=@{step='slipstream-fixture';priorLiveSha256=$taskReceipt.liveProofs[$taskLiveKeys[0]];captured=$taskCapture;sha256=Hash $taskCapture;fixtureEvidenceSha256=Hash "$taskNative/fixture-evidence.json";attempts=$taskEvidence.attempts;accepted=$false;testsExecuted=$false}
 $taskReceipt.liveProofs[$taskLiveKeys[0]]=Hash $taskPath
 Save $taskReceipt "$taskRoot/receipt.json"
 Write-Output 'FAILED_SLIPSTREAM_EVIDENCE_CAPTURED; accepted=false; testsExecuted=false'
 exit
}
if($taskReceipt.state -cne 'COMMON_FAILED_PRESERVED' -or $taskReceipt.active -cne 'protocols-fixture'){throw 'Only the recorded failed protocol fixture can be captured'}
$taskPrior=$taskReceipt.steps['protocols-legal']
if($taskPrior.receiptSha256 -cne (Hash $taskPrior.receipt)){throw 'Original immutable legal proof changed'}
$taskBefore=Get-Content $taskPrior.receipt -Raw|ConvertFrom-Json -AsHashtable
$taskPath=$taskReceipt.steps['protocols-native'].output+'/receipt.json'
$taskAfter=Get-Content $taskPath -Raw|ConvertFrom-Json -AsHashtable
if($taskAfter.kind -cne 'protocols' -or $taskAfter.state -cne 'OWNED_FIXTURE_FAILED_RETAINED' -or !$taskAfter.ownedAttempts.Count){throw 'Actual failed owned protocol receipt required'}
$taskMutable=@('state','cases','failures','skipped','ownedAttempts','ownedLogSha256')
foreach($k in @((@($taskBefore.Keys)+@($taskAfter.Keys))|Sort-Object -Unique)){
 if($k -cin $taskMutable){continue}
 if(((Canonical $taskBefore[$k])|ConvertTo-Json -Depth 30 -Compress) -cne ((Canonical $taskAfter[$k])|ConvertTo-Json -Depth 30 -Compress)){throw "Failed fixture changed non-runtime proof: $k"}
}
$taskNative=$taskReceipt.steps['protocols-native'].output
if((Hash "$taskNative/protocols-owned.test") -cne $taskAfter.fixtureSha256){throw 'Native fixture changed'}
foreach($row in $taskAfter.artifacts){if((Hash "$taskNative/$($row.file)") -cne $row.sha256){throw 'Compiled module changed'}}
$taskPass=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach($attempt in $taskAfter.ownedAttempts){
 $base="$taskNative/owned-$($attempt.runId)"
 if((Hash "$base/instrument.txt") -cne $attempt.instrumentSha256 -or (Hash "$base/native.log") -cne $attempt.logSha256){throw 'Failed owned raw evidence changed'}
 if(![IO.File]::ReadAllText("$base/instrument.txt").Contains("INSTRUMENTATION_RESULT: binarySha256=$($taskAfter.fixtureSha256)")){throw 'Actual failed Android binary unconfirmed'}
 $passed=@(Get-Content "$base/native.log"|Where-Object {$_ -cmatch '^--- PASS: (TestMcrfProtocols[A-Za-z0-9_]+) \('}|ForEach-Object {[regex]::Match($_,'^--- PASS: (\w+)').Groups[1].Value})
 if((@($passed|Sort-Object)-join ',') -cne (@($attempt.passed|Sort-Object)-join ',')){throw 'Failed attempt pass log differs'}
 foreach($name in $passed){[void]$taskPass.Add($name)}
}
if((@($taskPass|Sort-Object)-join ',') -cne (@($taskAfter.cases|Sort-Object)-join ',') -or (Hash "$taskNative/owned-tests.jsonl") -cne $taskAfter.ownedLogSha256){throw 'Retained passed-case evidence differs'}
$taskCapture="$taskRoot/failed-protocol-fixture-$([guid]::NewGuid().ToString('N')).json"
 $taskLiveKeys=@($taskReceipt.liveProofs.Keys|Where-Object {$_.Replace([char]92,[char]47) -ceq $taskPath.Replace([char]92,[char]47)})
 if($taskLiveKeys.Count -ne 1){throw 'Original live native proof key missing'}
 $taskLiveKey=$taskLiveKeys[0]
Copy-Item -LiteralPath $taskPath -Destination $taskCapture
if(!$taskReceipt.ContainsKey('failedProofCaptures')){$taskReceipt.failedProofCaptures=@()}
$taskReceipt.failedProofCaptures+=@{step='protocols-fixture';priorLiveSha256=$taskReceipt.liveProofs[$taskLiveKey];captured=$taskCapture;sha256=Hash $taskCapture;recoveryProducerSha256=Hash $PSCommandPath;passedRetained=$taskAfter.cases;accepted=$false}
$taskReceipt.liveProofs[$taskLiveKey]=Hash $taskPath
Save $taskReceipt "$taskRoot/receipt.json"
Write-Output "FAILED_NATIVE_EVIDENCE_CAPTURED; retained=$($taskAfter.cases.Count); accepted=false; testsExecuted=false"
