param([Parameter(Mandatory)][string]$NativeStage,[Parameter(Mandatory)][string]$EmulatorSerial,
 [string]$Adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation test
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
$taskStage=(Resolve-Path -LiteralPath $NativeStage).Path
if($taskStage.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-steer-native-[a-f0-9]{32}$' -or $EmulatorSerial -cnotmatch '^emulator-[0-9]+$'){throw 'Owned Steer native stage and emulator only'}
if((& $Adb -s $EmulatorSerial shell getprop ro.kernel.qemu).Trim() -cne '1' -or (& $Adb -s $EmulatorSerial shell getprop ro.product.cpu.abi).Trim() -cne 'x86_64'){throw 'Never run the source fixture on the user phone'}
$taskReceipt=Get-Content "$taskStage/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
if($taskReceipt.kind -cne 'steer' -or !$taskReceipt.sourceStable -or $taskReceipt.state -cnotin @('COMPILED_SOCKS_FIXTURE_PENDING','SOCKS_FIXTURE_FAILED_RETAINED','COMPILED_SOCKS_FIXTURE_VERIFIED')){throw 'Exact compiled Steer fixture required'}
$taskRequired=@('mcrf_socks_udp_test','mcrf_socks_local_udp_test','mcrf_socks_udp_wire_test')
if($taskReceipt.newVersion -eq 16){$taskRequired+='mcrf_private_socks_profile_test';if(!$taskReceipt.retainedSocks.unchangedFixtureSources){throw 'New private profile fixture requires retained unchanged SOCKS source proof'}}
$taskPassed=@($taskReceipt.cases);$taskCases=@($taskRequired|Where-Object {$_ -cnotin $taskPassed})
if(!$taskCases.Count){Write-Output 'STEER_SOCKS_CASES_RETAINED_NO_REPLAY';return}
$taskRemote="/data/local/tmp/mcrf-steer-socks-$([guid]::NewGuid().ToString('N'))"
if($taskRemote -cnotmatch '^/data/local/tmp/mcrf-steer-socks-[a-f0-9]{32}$'){throw 'Unsafe fixture directory'}
& $Adb -s $EmulatorSerial shell mkdir -m 700 $taskRemote;if($LASTEXITCODE){throw 'Owned fixture directory creation failed'}
$taskAttempts=@();$taskFailed=$false
foreach($taskCase in $taskCases){
 $taskRow=@($taskReceipt.fixtures|Where-Object {$_.abi -ceq 'x86_64' -and $_.file -ceq "x86_64/$taskCase"});if($taskRow.Count -ne 1 -or $taskRow[0].sha256 -cne (Hash "$taskStage/x86_64/$taskCase")){throw 'Compiled C fixture digest changed'}
 & $Adb -s $EmulatorSerial push "$taskStage/x86_64/$taskCase" "$taskRemote/$taskCase" *> "$taskStage/push-$taskCase.log";if($LASTEXITCODE){throw 'Fixture transfer failed'}
 $taskRemoteSha=@(& $Adb -s $EmulatorSerial shell toybox sha256sum "$taskRemote/$taskCase");if($LASTEXITCODE -or ($taskRemoteSha -join "`n") -notmatch "^$($taskRow[0].sha256)\s"){throw 'Android fixture bytes differ'}
 & $Adb -s $EmulatorSerial shell chmod 700 "$taskRemote/$taskCase";if($LASTEXITCODE){throw 'Fixture permission failed'}
 $taskOutput=@(& $Adb -s $EmulatorSerial shell "$taskRemote/$taskCase" 2>&1);$taskExit=$LASTEXITCODE
 $taskLog="$taskStage/$taskCase-$([guid]::NewGuid().ToString('N')).log";[IO.File]::WriteAllText($taskLog,($taskOutput -join "`n"),[Text.UTF8Encoding]::new($false))
 $taskAttempts+=@{case=$taskCase;abi='x86_64';sha256=$taskRow[0].sha256;exit=$taskExit;log=$taskLog;logSha256=Hash $taskLog}
 if($taskExit -eq 0){$taskPassed+=$taskCase}else{$taskFailed=$true}
}
$taskReceipt.cases=@($taskPassed|Sort-Object -Unique)
if(!$taskReceipt.ContainsKey('ownedAttempts')){$taskReceipt.ownedAttempts=@()};$taskReceipt.ownedAttempts+=@{remote=$taskRemote;serial=$EmulatorSerial;cases=$taskAttempts}
if($taskFailed -or @($taskRequired|Where-Object {$_ -cnotin $taskReceipt.cases}).Count){$taskReceipt.state='SOCKS_FIXTURE_FAILED_RETAINED';Save $taskReceipt "$taskStage/receipt.json";throw 'Retain failed fixture root and completed cases; no automatic replay'}
$taskReceipt.state='COMPILED_SOCKS_FIXTURE_VERIFIED';$taskReceipt.nativeExecuted=$true;$taskReceipt.executedAbi='x86_64';Save $taskReceipt "$taskStage/receipt.json"
# Three exact owned files only, no recursive deletion or wildcard expansion.
foreach($taskCase in $taskCases){& $Adb -s $EmulatorSerial shell rm -- "$taskRemote/$taskCase";if($LASTEXITCODE){$taskReceipt.cleanupPending=$true;Save $taskReceipt "$taskStage/receipt.json";throw 'Fixture passed; cleanup pending, do not replay cases'}}
& $Adb -s $EmulatorSerial shell rmdir $taskRemote;if($LASTEXITCODE){$taskReceipt.cleanupPending=$true;Save $taskReceipt "$taskStage/receipt.json";throw 'Fixture passed; retain directory for cleanup-only retry'}
Write-Output 'P3_STEER_NEW_SOCKS_C_FIXTURES_VERIFIED_X86_ONLY'
