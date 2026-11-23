param([Parameter(Mandatory)][string]$Stage)
$ErrorActionPreference='Stop'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
$taskRoot=(Resolve-Path $Stage).Path.Replace([char]92,[char]47)
if($taskRoot -cnotmatch '^D:/MCRF/Builds/Android/p3-steer-runtime-[a-f0-9]{32}$'){throw 'Exact owned runtime stage required'}
$taskReceipt=Get-Content "$taskRoot/receipt.json" -Raw|ConvertFrom-Json
if($taskReceipt.productionRebuilt -or $taskReceipt.phoneTouched -or !$taskReceipt.steerOnly -or $taskReceipt.mainApkSha256 -cne (Hash "$($taskReceipt.snapshot)/ports/android/app/build/outputs/apk/release/app-universal-release.apk") -or $taskReceipt.apkSha256 -cne (Hash $taskReceipt.apk)){throw 'Exact unchanged production and test APKs required'}
$taskRetained=$taskReceipt.retainedEnabled
if(!$taskRetained -or $taskRetained.logSha256 -cne (Hash "$($taskRetained.stage)/instrument.txt")){throw 'Retained enabled phase proof missing'}
$taskPrior=Get-Content "$($taskRetained.stage)/receipt.json" -Raw|ConvertFrom-Json
if($taskPrior.mainApkSha256 -cne $taskReceipt.mainApkSha256 -or $taskPrior.apkSha256 -cne (Hash $taskPrior.apk)){throw 'Retained phase uses another production or APK'}
$taskLogs=@([IO.File]::ReadAllText("$($taskRetained.stage)/instrument.txt"),[IO.File]::ReadAllText("$taskRoot/instrument.txt"))
$taskExpected=@('ENABLED_ROUTE_TRAFFIC_PASS','DISABLED_ROUTE_RELOADED_PASS','REENABLED_ROUTE_TRAFFIC_PASS','HEALTHY_WAKE_NO_RESTART_PASS','FAILED_WAKE_RECOVERY_PASS','MANUAL_STOP_NOT_RESURRECTED_PASS')
$taskPass=@(foreach($taskLog in $taskLogs){foreach($taskMatch in [regex]::Matches($taskLog,'(?m)^INSTRUMENTATION_STATUS: phase=([A-Z_]+_PASS)\s*$')){$taskMatch.Groups[1].Value}})
if($taskPass.Count -ne 6 -or @($taskExpected|Where-Object {$_ -notin $taskPass}).Count -or @($taskPass|Sort-Object -Unique).Count -ne 6 -or $taskLogs[1] -notmatch '(?m)^INSTRUMENTATION_RESULT: passed=true\s*$' -or $taskLogs[1] -notmatch '(?m)^INSTRUMENTATION_RESULT: checks=5\s*$'){throw 'Six unique real runtime passes required'}
$taskProof=@{state='SIX_NEW_STEER_RUNTIME_CASES_PASS';mainVersionCode=10049;mainApkSha256=$taskReceipt.mainApkSha256;moduleVersion=15;phases=$taskExpected;retained=$taskRetained;newRun=@{stage=$taskRoot;logSha256=Hash "$taskRoot/instrument.txt"};oldTestsReplayed=$false;phoneVpnStarted=$false;fixture='Owned DNS/SOCKS/TUN route responder; allowlisted gstatic443 rawTLS relay via captured physical Network, original certificate verification unchanged';limits='x86 emulator runtime; phone ARM64 acceptance recorded separately; no VK/provider throughput claims'}
[IO.File]::WriteAllText("$taskRoot/runtime-proof.json",($taskProof|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
Write-Output 'STEER_RUNTIME=6_NEW_CASES_PASS_WITHOUT_REPLAY'
