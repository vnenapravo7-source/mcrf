param([Parameter(Mandatory)][string]$NativeStage,[Parameter(Mandatory)][string]$RetainedPrimaryStage)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-package
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 16),[Text.UTF8Encoding]::new($false))}
$taskRoot=(Resolve-Path $NativeStage).Path
if($taskRoot.Replace([char]92,[char]47) -cnotmatch '^D:/MCRF/Builds/Android/p3-steer-native-[a-f0-9]{32}$'){throw 'Exact compiled Steer stage required'}
$taskProof=Get-Content "$taskRoot/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
if($taskProof.state -cne 'COMPILED_SOCKS_FIXTURE_PACKAGED_NOT_PHONE_VERIFIED' -or !$taskProof.producerComplete -or !$taskProof.sourceStable){throw 'Completed Steer source/native/fixture stage required'}
$taskPrepared=Get-Content "$($taskProof.preparedSource)/receipt.json" -Raw|ConvertFrom-Json
if((Hash "$RetainedPrimaryStage/receipt.json") -cne $taskPrepared.baselinePrimaryReceiptSha256){throw 'Exact source-bound retained primary baseline required'}
if((Hash "$taskRoot/primary-stage/receipt.json") -cne $taskProof.primaryStageReceiptSha256){throw 'Packaged primary descriptor changed'}
$taskDescriptor=(Get-Content "$taskRoot/primary-stage/receipt.json" -Raw|ConvertFrom-Json).modules.steer
$taskBaseline=(Get-Content "$RetainedPrimaryStage/receipt.json" -Raw|ConvertFrom-Json).modules.steer
$taskFiles=@()
foreach($notice in $taskDescriptor.licenses){
 if($notice.file -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'){throw 'Bounded original notice filename required'}
 $prior=@($taskBaseline.licenses|Where-Object file -CEQ $notice.file)
 if($prior.Count -ne 1 -or $prior[0].sha256 -cne $notice.sha256 -or $prior[0].bytes -ne $notice.bytes){throw 'Retained license descriptor differs'}
 $from=Get-Item -LiteralPath "$RetainedPrimaryStage/steer/licenses/$($notice.file)"
 if($from.LinkType -or $from.Length -ne $notice.bytes -or (Hash $from.FullName) -cne $notice.sha256){throw 'Original licence bytes differ'}
 $to="$taskRoot/primary-stage/steer/licenses/$($notice.file)"
 New-Item -ItemType Directory -Path (Split-Path $to) -Force|Out-Null
 if(Test-Path $to){if((Hash $to) -cne $notice.sha256){throw 'Never overwrite different legal bytes'}}else{Copy-Item -LiteralPath $from.FullName -Destination $to}
 $taskFiles+=@{file=$notice.file;sha256=Hash $to;bytes=(Get-Item $to).Length}
}
if($taskFiles.Count -ne 11){throw 'All retained native/Java dependency notices required'}
Save @{files=$taskFiles;baselineReceiptSha256=$taskPrepared.baselinePrimaryReceiptSha256;nativeChanged=$false;testsExecuted=$false} "$taskRoot/primary-stage/steer/notice-closure.json"
$taskProof.noticeFilesClosureSha256=Hash "$taskRoot/primary-stage/steer/notice-closure.json"
Save $taskProof "$taskRoot/receipt.json";Write-Output "P3_STEER_NOTICE_FILES=$taskRoot"
