param([Parameter(Mandatory)][string]$FinalStage,[Parameter(Mandatory)][string]$IsolationStage)
$ErrorActionPreference='Stop'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
foreach($taskPath in @($FinalStage,$IsolationStage)){
 if((Resolve-Path $taskPath).Path.Replace([char]92,[char]47) -cnotmatch '^D:/MCRF/Builds/Android/p3-(steer-runtime|isolated-phone)-[a-f0-9]{32}$'){throw 'Exact owned evidence stage required'}
}
$taskReceipt=Get-Content "$FinalStage/receipt.json" -Raw|ConvertFrom-Json
if($taskReceipt.state -cne 'TEST_ONLY_COMPILED_NOT_RUN' -or $taskReceipt.apkSha256 -cne (Hash $taskReceipt.apk) -or !$taskReceipt.isolatedPhone){throw 'Exact signed isolated framework helper required'}
$taskRaw=[IO.File]::ReadAllText("$FinalStage/protocol-instrument.txt")
if($taskRaw -notmatch '(?m)^INSTRUMENTATION_RESULT: passed=true\s*$' -or $taskRaw -match '(?m)^INSTRUMENTATION_RESULT: (?:error|cleanupError|stopError)='){throw 'Successful composed result and cleanup required'}
$taskCases=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal);$taskProofs=@()
foreach($taskProof in $taskReceipt.retainedProtocolCases){
 $taskLog=if($taskProof.log){$taskProof.log}else{"$($taskProof.stage)/protocol-instrument.txt"}
 if((Hash $taskLog) -cne $taskProof.logSha256 -or (Hash "$($taskProof.stage)/probe.apk") -cne $taskProof.helperApkSha256){throw 'Retained proof changed'}
 foreach($taskCase in $taskProof.cases){if(!$taskCases.Add($taskCase)){throw 'Duplicate retained phase'}}
 $taskProofs+=@{log=$taskLog;sha256=Hash $taskLog;scope='accepted on prior exact main51; not reexecuted'}
}
foreach($taskMatch in [regex]::Matches($taskRaw,'(?m)^INSTRUMENTATION_STATUS: phase=(COMPOSED_APP_[A-Z_]+_PASS)\s*$')){if(!$taskCases.Add($taskMatch.Groups[1].Value)){throw 'Closed phase replayed'}}
$taskExpected=@('TUIC_MANUAL_TUN','ANYTLS_MANUAL_TUN','NAIVE_MANUAL_TUN','SSH_MANUAL_TUN','AUTOMATIC_HELPER_TUN','INDEPENDENT_SSH_ROUTE_WITH_TUIC_MAIN')|ForEach-Object {"COMPOSED_APP_${_}_PASS"}
if($taskCases.Count -ne 6 -or @($taskExpected|Where-Object {!$taskCases.Contains($_)}).Count){throw 'All six actual composed phases required'}
$taskIsolation=Get-Content "$IsolationStage/unsigned.isolation.json" -Raw|ConvertFrom-Json
if($taskIsolation.isolatedPackage -cne 'org.mcrf.p3test' -or $taskIsolation.sourceSha256 -cne $taskReceipt.mainApkSha256 -or $taskIsolation.productionRecompiled){throw 'Isolated app is not exact frozen production payload'}
$taskResult=@{state='SIX_COMPOSED_ORIGINAL_PROTOCOL_APP_PHASES_ACCEPTED_WITH_SCOPED_RETENTION';cases=@($taskCases|Sort-Object);finalMainSha256=$taskReceipt.mainApkSha256;scopedRetention=$taskReceipt.scoped51Retention;retained=$taskProofs;newLogSha256=Hash "$FinalStage/protocol-instrument.txt";isolationManifestProofSha256=Hash "$IsolationStage/unsigned.isolation.json";oldCasesReplayed=$false;published=$false;mainPreservationProofSeparate=$true}
[IO.File]::WriteAllText("$FinalStage/composed-proof.json",($taskResult|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
Write-Output 'COMPOSED_APP_SIX_PHASES_ACCEPTED_WITH_EXPLICIT_SCOPED_RETENTION'
