param([Parameter(Mandatory)][string]$NativeStage,[Parameter(Mandatory)][string]$FixtureApk,[Parameter(Mandatory)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
      [string]$Adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation test
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
$taskRoot=(Resolve-Path -LiteralPath $NativeStage).Path
if($taskRoot.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-slipstream-native-[a-f0-9]{32}$' -or (Get-Item -LiteralPath $taskRoot).LinkType){throw 'Exact owned native stage required'}
$taskReceipt=Get-Content "$taskRoot/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
if($taskReceipt.state -cnotin @('COMPILED_FIXTURE_PENDING','FIXTURE_FAILED_PRESERVED') -or !$taskReceipt.sourceStable -or $taskReceipt.noticeManifestSha256 -cne (Hash "$taskRoot/notice-manifest.json")){throw 'Complete unexecuted source-bound legal/native stage required; never replay a closed fixture'}
if(!$taskReceipt.originalCFixture.loopbackOnly -or $taskReceipt.originalCFixture.commit -cne '397850b16e70a52513269134b2c7b9aa13a56745' -or $taskReceipt.originalCFixture.sourceManifestSha256 -cne (Hash "$taskRoot/cfixture-source-manifest.json") -or $taskReceipt.originalCFixture.sourceReceiptSha256 -cne (Hash "$taskRoot/cfixture-source.json")){throw 'Pinned loopback-only original C server fixture required'}
$taskNotices=Get-Content "$taskRoot/notice-manifest.json" -Raw|ConvertFrom-Json
if(@($taskNotices.missingModules).Count -or $taskNotices.linkedModules -lt 1){throw 'All actual dependency legal notices required'}
$taskRequired=@('TestMcrfSlipstreamStrictConfigAndMandatoryLeaf','TestMcrfSlipstreamRejectDuplicateAndTrailingConfig','TestMcrfSlipstreamEndpointAndKeepAliveBounds','TestMcrfSlipstreamUnauthenticatedClientCannotOpenRemoteStream','TestMcrfSlipstreamPrivateRelayPreservesHalfClose','TestMcrfSlipstreamCancellationClosesPendingRemoteAuth','TestMcrfSlipstreamOwnedWireData','TestMcrfSlipstreamWrongLeafStopsBeforeRemoteSOCKS','TestMcrfSlipstreamParentDeathStopsCore','TestMcrfSlipstreamStdinEOFStopsCore')
$taskRequired+='TestMcrfSlipstreamOriginalCServerInterop'
$taskPassed=@();$taskAttempts=@()
if(Test-Path "$taskRoot/fixture-evidence.json"){
 $taskPrior=Get-Content "$taskRoot/fixture-evidence.json" -Raw|ConvertFrom-Json -AsHashtable
 if($taskPrior.state -cne 'FAILED_PRESERVED' -or $taskPrior.sourceManifestSha256 -cne $taskReceipt.sourceManifestSha256){throw 'Only retained failed fixture evidence may be resumed'}
 $taskPassed=@($taskPrior.cases|Where-Object {$_});$taskAttempts=@($taskPrior.attempts|Where-Object {$_})
 foreach($taskAttempt in $taskAttempts){if((Hash "$taskRoot/$($taskAttempt.log)") -cne $taskAttempt.sha256){throw 'Retained successful cases changed'}}
 if($taskPassed.Count -and !$taskAttempts.Count){throw 'Passed cases require immutable raw logs'}
}
$taskRemaining=@($taskRequired|Where-Object {$_ -cnotin $taskPassed})
if(!$taskRemaining.Count){throw 'Never repeat an already complete fixture'}
$taskRun=[guid]::NewGuid().ToString('N')
$taskLogRoot="$taskRoot/attempt-$taskRun";New-Item -ItemType Directory -Path $taskLogRoot|Out-Null
foreach($taskRow in @($taskReceipt.artifacts)+@($taskReceipt.fixtureArtifacts)){
 if([IO.Path]::IsPathRooted($taskRow.file) -or '..' -in ($taskRow.file -split '[/\\]')){throw 'Unsafe owned fixture input path'}
 $taskInput=Get-Item -LiteralPath (Join-Path $taskRoot $taskRow.file)
 if($taskInput.LinkType -or $taskInput.Length -ne $taskRow.bytes -or (Hash $taskInput.FullName) -cne $taskRow.sha256 -or !$taskRow.elfChecked){throw 'Native fixture input identity changed'}
}
$taskAbi=(& $Adb -s $Serial shell getprop ro.product.cpu.abi|Out-String).Trim();if($LASTEXITCODE -or $taskAbi -cne 'x86_64'){throw 'Owned x86_64 Android emulator required; no real phone mutation here'}
$taskRemote='/data/local/tmp/ms'+[guid]::NewGuid().ToString('N').Substring(0,12)
if($taskRemote -cnotmatch '^/data/local/tmp/ms[a-f0-9]{12}$'){throw 'Unsafe generated fixture directory'}
$taskEvidence=@{state='RUNNING';sourceManifestSha256=$taskReceipt.sourceManifestSha256;failures=0;skipped=0;cases=$taskPassed;attempts=$taskAttempts;abi='x86_64';remote=$taskRemote;serial=$Serial;originalCServerInterop=$false;phoneAccepted=$false;userSettingsChanged=$false;retainedTestsReplayed=$false;inputs=@()}
$taskPairs=@(@{file='x86_64/libslipstream.so';name='libslipstream.so'},@{file='x86_64/libslipstream_core.so';name='libslipstream_core.so'},@{file='fixtures/libslipstream_testserver.so';name='libslipstream_testserver.so'},@{file='fixtures/libslipstream_fixture.so';name='libslipstream_fixture.so'})
$taskPairs+=@{file='fixtures/libslipstream_cserver.so';name='libslipstream_cserver.so'}
try{
 $taskInstall=@(& $Adb -s $Serial install -r $FixtureApk)
 if($LASTEXITCODE -or ($taskInstall -join "`n") -notmatch '(?m)^Success\s*$'){throw 'Owned Slipstream fixture APK install failed'}
 # Eleven NEW loopback/UDS/QUIC-DNS/pin/process-owner/original-C cases. No account,
 # external resolver, VPN switch, application install or UI action is involved.
 $taskInstrument=@(& $Adb -s $Serial shell am instrument -w -e cases ($taskRemaining -join ',') -e runId $taskRun org.mcrf.slipstreamfixtures/.Runner)
 [IO.File]::WriteAllText("$taskLogRoot/instrument.txt",($taskInstrument -join "`n"),[Text.UTF8Encoding]::new($false))
 $taskFixture=@($taskReceipt.fixtureArtifacts|Where-Object file -CEQ $(if($taskReceipt.androidFixture){'fixtures/libslipstream_fixture_android.so'}else{'fixtures/libslipstream_fixture.so'}))
 if(($taskInstrument -join "`n") -notmatch "INSTRUMENTATION_RESULT: binarySha256=$($taskFixture[0].sha256)"){throw 'Actual app-UID fixture binary not confirmed'}
 $taskRaw=@(& $Adb -s $Serial shell run-as org.mcrf.slipstreamfixtures cat "files/slipstream-owned-$taskRun.log")
 if($LASTEXITCODE){throw 'App-UID native fixture log unavailable'}
 [IO.File]::WriteAllText("$taskLogRoot/raw.log",($taskRaw -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
 [IO.File]::WriteAllText("$taskLogRoot/stderr.log",'', [Text.UTF8Encoding]::new($false))
 $taskExit=if(($taskInstrument -join "`n") -match 'INSTRUMENTATION_CODE: -1'){0}else{1}
 $taskGo='D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/bin/go.exe'
 Get-Content -LiteralPath "$taskLogRoot/raw.log"|& $taskGo tool test2json -p mcrf/slipstream > "$taskLogRoot/tests.jsonl"
 if($LASTEXITCODE){throw 'Fixture JSON evidence conversion failed'}
 foreach($taskLine in Get-Content "$taskLogRoot/tests.jsonl"){
  $taskEvent=$taskLine|ConvertFrom-Json
  if($taskEvent.Action -ceq 'fail'){$taskEvidence.failures++};if($taskEvent.Action -ceq 'skip'){$taskEvidence.skipped++}
  if($taskEvent.Action -ceq 'pass' -and $taskEvent.Test){$taskEvidence.cases+=@($taskEvent.Test)}
 }
 $taskEvidence.attempts+=@{log="attempt-$taskRun/raw.log";sha256=Hash "$taskLogRoot/raw.log";instrumentSha256=Hash "$taskLogRoot/instrument.txt";apkSha256=Hash $FixtureApk;stderrSha256=Hash "$taskLogRoot/stderr.log";requested=$taskRemaining;exit=$taskExit}
 if($taskExit -ne 0 -or $taskEvidence.failures -ne 0 -or $taskEvidence.skipped -ne 0 -or @($taskRequired|Where-Object {$_ -cnotin $taskEvidence.cases}).Count){throw 'All eleven owned Slipstream cases must pass, none skipped'}
 $taskEvidence.originalCServerInterop=$true;$taskEvidence.originalCCommit=$taskReceipt.originalCFixture.commit;$taskEvidence.originalPicoquicCommit=$taskReceipt.originalCFixture.picoquic
 $taskEvidence.logSha256=Hash "$taskLogRoot/tests.jsonl";$taskEvidence.state='PASS_P3_SLIPSTREAM_OWNED_FIXTURE'
 Save $taskEvidence "$taskRoot/fixture-evidence.json"
 $taskReceipt.fixtureEvidenceSha256=Hash "$taskRoot/fixture-evidence.json";$taskReceipt.fixtureExecuted=$true;$taskReceipt.executedAbis=@('x86_64');$taskReceipt.producerComplete=$true;$taskReceipt.state='COMPILED_OWNED_FIXTURE_VERIFIED'
 $taskEvidence.cleanupPending=$false
}catch{$taskEvidence.state='FAILED_PRESERVED';$taskEvidence.failure=$_.Exception.Message;$taskReceipt.state='FIXTURE_FAILED_PRESERVED';throw}
finally{Save $taskEvidence "$taskRoot/fixture-evidence.json";if($taskEvidence.state -ceq 'PASS_P3_SLIPSTREAM_OWNED_FIXTURE'){$taskReceipt.fixtureEvidenceSha256=Hash "$taskRoot/fixture-evidence.json"};Save $taskReceipt "$taskRoot/receipt.json";Write-Output "P3_SLIPSTREAM_FIXTURE=$taskRoot"}
