param([Parameter(Mandatory)][string]$NativeStage)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-package
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 16),[Text.UTF8Encoding]::new($false))}
$taskRoot=(Resolve-Path $NativeStage).Path
if($taskRoot.Replace([char]92,[char]47) -cnotmatch '^D:/MCRF/Builds/Android/p3-slipstream-native-[a-f0-9]{32}$'){throw 'Exact owned native stage required'}
$taskProof=Get-Content "$taskRoot/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
$taskEvidence=Get-Content "$taskRoot/fixture-evidence.json" -Raw|ConvertFrom-Json -AsHashtable
if($taskProof.state -cne 'COMPILED_OWNED_FIXTURE_VERIFIED' -or !$taskProof.producerComplete -or $taskProof.fixtureEvidenceSha256 -cne (Hash "$taskRoot/fixture-evidence.json") -or $taskEvidence.cases.Count -ne 11){throw 'All actual eleven cases required before evidence export'}
if(Test-Path "$taskRoot/slipstream-tests.jsonl"){throw 'Never overwrite a completed aggregate proof'}
$taskFound=@{};$taskLedger=@()
foreach($attempt in $taskEvidence.attempts){
 if($attempt.log -cnotmatch '^attempt-[a-f0-9]{32}/raw\.log$' -or (Hash "$taskRoot/$($attempt.log)") -cne $attempt.sha256){throw 'Exact immutable raw attempt required'}
 foreach($line in Get-Content "$taskRoot/$($attempt.log)"){
  if($line -cmatch '^--- PASS: (TestMcrfSlipstream[A-Za-z0-9_]+) \('){
   $name=$Matches[1];if($taskFound.ContainsKey($name)){throw 'An accepted case was replayed'}
   $taskFound[$name]=$true;$taskLedger+=@{Action='pass';Test=$name;rawLog=$attempt.log;rawSha256=$attempt.sha256;sourceManifestSha256=$taskEvidence.sourceManifestSha256;retainedOriginalSourceSha256=$taskEvidence.retainedSourceManifestSha256}
  }
 }
}
if((@($taskFound.Keys|Sort-Object)-join ',') -cne (@($taskEvidence.cases|Sort-Object)-join ',')){throw 'Aggregate case list disagrees with actual raw logs'}
Copy-Item -LiteralPath "$taskRoot/fixture-evidence.json" -Destination "$taskRoot/fixture-evidence-before-export.json"
[IO.File]::WriteAllText("$taskRoot/slipstream-tests.jsonl",(($taskLedger|ForEach-Object {$_|ConvertTo-Json -Compress}) -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
$taskEvidence.aggregate=@{cases=$taskFound.Count;source='immutable Go raw PASS lines only';testsExecuted=$false;priorEvidenceSha256=Hash "$taskRoot/fixture-evidence-before-export.json"}
$taskEvidence.logSha256=Hash "$taskRoot/slipstream-tests.jsonl"
Save $taskEvidence "$taskRoot/fixture-evidence.json"
$taskProof.fixtureEvidenceSha256=Hash "$taskRoot/fixture-evidence.json";$taskProof.recoveredRustIdsSha256=Hash "$taskRoot/recovered-built-rust-package-ids.json";$taskProof.legalInventoryRecoverySha256=Hash "$taskRoot/legal-inventory-recovery.json"
Save $taskProof "$taskRoot/receipt.json";Write-Output "P3_SLIPSTREAM_PROOF_EXPORTED=$taskRoot"
