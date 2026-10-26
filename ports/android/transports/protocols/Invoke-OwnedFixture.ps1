param([Parameter(Mandatory)][string]$NativeStage,[Parameter(Mandatory)][string]$FixtureApk,[Parameter(Mandatory)][string]$EmulatorSerial,
 [string]$Adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation test
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
$taskStage=(Resolve-Path -LiteralPath $NativeStage).Path
if($taskStage.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-protocols-native-[a-f0-9]{32}$' -or $EmulatorSerial -cnotmatch '^emulator-[0-9]+$'){throw 'Owned native stage and disposable emulator required'}
$taskReceipt=Get-Content "$taskStage/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
if($taskReceipt.kind -cne 'protocols' -or !$taskReceipt.sourceStable -or $taskReceipt.fixtureSha256 -cne (Hash "$taskStage/protocols-owned.test")){throw 'Exact compiled fixture required'}
$taskRequired=@('TestMcrfProtocolsStrictConfig','TestMcrfProtocolsSshHostTrust','TestMcrfProtocolsDnsNotSilentlyDropped','TestMcrfProtocolsAddressFraming',
 'TestMcrfProtocolsAnonymousAndBadPasswordRejected','TestMcrfProtocolsOwnerEofAndStop','TestMcrfProtocolsDiagnosticPrivacy',
 'TestMcrfProtocolsTuicOriginalWire','TestMcrfProtocolsAnyTlsOriginalWire','TestMcrfProtocolsNaiveCronetOriginalWire','TestMcrfProtocolsSshOriginalWire',
 'TestMcrfProtocolsUdpSourcePortPin','TestMcrfProtocolsDescriptor','TestMcrfProtocolsPhysicalNetworkBinding','TestMcrfProtocolsSystemDnsLifecycle')
$taskPassed=@($taskReceipt.cases)
if(@($taskPassed|Where-Object {$_ -cnotin $taskRequired}).Count){throw 'Unknown retained fixture case'}
$taskCases=@($taskRequired|Where-Object {$_ -cnotin $taskPassed})
if(!$taskCases.Count){Write-Output 'PROTOCOLS_OWNED_CASES_ALREADY_RETAINED_NO_REPLAY';return}
if($taskReceipt.state -cnotin @('COMPILED_LEGAL_AND_FIXTURE_PENDING','COMPILED_FIXTURE_PENDING','OWNED_FIXTURE_FAILED_RETAINED')){throw 'Not an unaccepted compiled fixture'}
if((& $Adb -s $EmulatorSerial shell getprop ro.kernel.qemu).Trim() -cne '1' -or (& $Adb -s $EmulatorSerial shell getprop ro.product.cpu.abi).Trim() -cne 'x86_64'){throw 'Actual disposable x86 emulator required; never user phone'}
$taskId=[guid]::NewGuid().ToString('N');$taskAttempt="$taskStage/owned-$taskId";New-Item -ItemType Directory -Path $taskAttempt|Out-Null
$taskInstall=@(& $Adb -s $EmulatorSerial install -r $FixtureApk);if($LASTEXITCODE -or ($taskInstall -join "`n") -notmatch '(?m)^Success\s*$'){throw 'Owned fixture APK installation failed'}
$taskInstrument=@(& $Adb -s $EmulatorSerial shell am instrument -w -e cases ($taskCases -join ',') -e runId $taskId org.mcrf.protocolfixtures/.Runner)
[IO.File]::WriteAllText("$taskAttempt/instrument.txt",($taskInstrument -join "`n"),[Text.UTF8Encoding]::new($false))
if(($taskInstrument -join "`n") -notmatch "INSTRUMENTATION_RESULT: binarySha256=$($taskReceipt.fixtureSha256)"){throw 'Actual Android fixture binary was not confirmed; preserve instrument output'}
$taskRaw=@(& $Adb -s $EmulatorSerial shell run-as org.mcrf.protocolfixtures cat "files/protocols-owned-$taskId.log");if($LASTEXITCODE){throw 'Owned fixture log unavailable'}
$taskRawText=($taskRaw -join "`n")+"`n";[IO.File]::WriteAllText("$taskAttempt/native.log",$taskRawText,[Text.UTF8Encoding]::new($false))
$taskNewPass=@();$taskFail=@();$taskSkip=@()
foreach($taskLine in $taskRaw){
 if($taskLine -cmatch '^--- PASS: (TestMcrfProtocols[A-Za-z0-9_]+) \('){$taskNewPass+=$Matches[1]}
 elseif($taskLine -cmatch '^--- FAIL: (TestMcrfProtocols[A-Za-z0-9_]+) \('){$taskFail+=$Matches[1]}
 elseif($taskLine -cmatch '^--- SKIP: (TestMcrfProtocols[A-Za-z0-9_]+) \('){$taskSkip+=$Matches[1]}
}
if(@($taskNewPass|Where-Object {$_ -cnotin $taskCases}).Count -or @($taskNewPass|Select-Object -Unique).Count -ne $taskNewPass.Count){throw 'Unexpected or duplicate owned cases'}
$taskReceipt.cases=@($taskPassed+$taskNewPass|Sort-Object -Unique);$taskReceipt.failures=$taskFail.Count;$taskReceipt.skipped=$taskSkip.Count
$taskEvents=@();foreach($taskName in $taskReceipt.cases){$taskEvents+=(@{Action='pass';Test=$taskName;FixtureSha256=$taskReceipt.fixtureSha256;SourceManifestSha256=$taskReceipt.sourceManifestSha256}|ConvertTo-Json -Compress)}
[IO.File]::WriteAllText("$taskStage/owned-tests.jsonl",($taskEvents -join "`n")+"`n",[Text.UTF8Encoding]::new($false));$taskReceipt.ownedLogSha256=Hash "$taskStage/owned-tests.jsonl"
if(!$taskReceipt.ContainsKey('ownedAttempts')){$taskReceipt.ownedAttempts=@()}
$taskReceipt.ownedAttempts+=@{runId=$taskId;fixtureApkSha256=Hash $FixtureApk;instrumentSha256=Hash "$taskAttempt/instrument.txt";logSha256=Hash "$taskAttempt/native.log";cases=$taskCases;passed=$taskNewPass;failed=$taskFail;skipped=$taskSkip}
$taskAll=@($taskRequired|Where-Object {$_ -cnotin $taskReceipt.cases}).Count -eq 0
if(!$taskAll -or $taskFail.Count -or $taskSkip.Count -or ($taskInstrument -join "`n") -notmatch 'INSTRUMENTATION_CODE: -1'){
 $taskReceipt.state='OWNED_FIXTURE_FAILED_RETAINED';Save $taskReceipt "$taskStage/receipt.json";throw 'New protocol fixture incomplete; passed cases retained, no automatic replay'
}
$taskReceipt.nativeExecuted=$true;$taskReceipt.executedAbi='x86_64'
$taskNotices=Get-Content "$taskStage/notice-manifest.json" -Raw|ConvertFrom-Json
if($taskNotices.complete -eq $true -and !$taskNotices.missing.Count -and $taskReceipt.noticeManifestSha256 -ceq (Hash "$taskStage/notice-manifest.json")){
 $taskReceipt.state='COMPILED_OWNED_FIXTURE_VERIFIED';$taskReceipt.producerComplete=$true
}else{$taskReceipt.state='OWNED_FIXTURE_VERIFIED_LEGAL_PENDING';$taskReceipt.producerComplete=$false}
Save $taskReceipt "$taskStage/receipt.json"
Write-Output "P3_PROTOCOLS_OWNED_RESULT=$($taskReceipt.state); arm ABIs compiled only, not executed"
