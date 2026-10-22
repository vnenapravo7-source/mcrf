param([Parameter(Mandatory=$true)][string]$Replacement,[switch]$Apply)
$ErrorActionPreference='Stop'
# One reviewed superseded, never-installed candidate. Not a general directory cleaner.
$taskOld='D:/MCRF/Builds/Android/qwdtt-captcha-e6afc79c302545a4881f290f82669a75'
$taskKeep='D:/MCRF/Builds/Android/qwdtt-captcha-ff786b933ac74d34826fed369f391e86'
$taskRoot=[IO.Path]::GetFullPath('D:/MCRF/Builds/Android')
$taskNew=[IO.Path]::GetFullPath($Replacement)
if([IO.Path]::GetDirectoryName($taskNew) -ine $taskRoot -or [IO.Path]::GetFileName($taskNew) -notmatch '^qwdtt-captcha-[0-9a-f]{32}$' -or
   $taskNew -ieq [IO.Path]::GetFullPath($taskOld) -or $taskNew -ieq [IO.Path]::GetFullPath($taskKeep)){throw 'A different completed QWDTT replacement required'}
foreach($taskStage in @($taskOld,$taskKeep,$taskNew)){
    if((Get-Item -LiteralPath $taskStage).LinkType){throw 'Linked stage rejected'}
    $taskReceipt=Get-Content -LiteralPath (Join-Path $taskStage 'receipt.json') -Raw|ConvertFrom-Json
    if($taskReceipt.state -ne 'COMPILED_NOT_EXECUTED' -or !$taskReceipt.sourceStable -or $taskReceipt.apkBuilt -or @($taskReceipt.artifacts).Count -ne 3){throw 'Candidate receipt not a complete uninstalled native-only result'}
    foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
        $taskFile=Get-Item -LiteralPath (Join-Path $taskStage "$taskAbi/libqwdtt.so")
        $taskRow=@($taskReceipt.artifacts|Where-Object abi -eq $taskAbi)
        if($taskRow.Count -ne 1 -or $taskFile.LinkType -or $taskFile.Length -ne $taskRow[0].bytes -or
           (Get-FileHash -LiteralPath $taskFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskRow[0].sha256){throw 'Native artifact identity mismatch'}
    }
}
# An active producer/consumer always wins. Do not terminate it or delete its inputs.
$taskOldName=[IO.Path]::GetFileName($taskOld)
if(Get-CimInstance Win32_Process|Where-Object {$_.ProcessId -ne $PID -and $_.CommandLine -like "*$taskOldName*"}){throw 'Superseded candidate still referenced by an active process'}
$taskTargets=@('arm64-v8a','armeabi-v7a','x86_64')|ForEach-Object {Join-Path $taskOld "$_/libqwdtt.so"}
foreach($taskTarget in $taskTargets){
    $taskResolved=[IO.Path]::GetFullPath($taskTarget)
    if(!$taskResolved.StartsWith([IO.Path]::GetFullPath($taskOld)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Retirement escaped exact candidate'}
    $taskStream=[IO.File]::Open($taskResolved,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None);$taskStream.Dispose()
}
$taskResult=[ordered]@{old=$taskOld;keep=@($taskKeep,$taskNew);targets=$taskTargets;apply=[bool]$Apply;removed=0;receiptsAndSourcesPreserved=$true;recoverable='Rebuild from retained frozen source and manifests; no user data or installed libraries removed'}
if($Apply){
    try{foreach($taskTarget in $taskTargets){Remove-Item -LiteralPath $taskTarget;$taskResult.removed++}}
    finally{[IO.File]::WriteAllText((Join-Path $taskOld 'native-retirement.json'),($taskResult|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))}
}
$taskResult|ConvertTo-Json -Depth 4
