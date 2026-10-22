param([Parameter(Mandatory=$true)][string]$Stage)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath('D:/MCRF/Builds/Android')
$taskStage=[IO.Path]::GetFullPath($Stage)
if([IO.Path]::GetDirectoryName($taskStage) -ine $taskRoot -or [IO.Path]::GetFileName($taskStage) -notmatch '^(qwdtt|wdtt|csqtt|csqtt25)-captcha-[0-9a-f]{32}$' -or
   (Get-Item -LiteralPath $taskStage).LinkType){throw 'Only an owned completed immutable CAPTCHA candidate is accepted'}
$taskReceipt=Get-Content -LiteralPath (Join-Path $taskStage 'receipt.json') -Raw|ConvertFrom-Json
if($taskReceipt.state -ne 'COMPILED_NOT_EXECUTED' -or !$taskReceipt.sourceStable){throw 'Native producer has not completed'}
$taskReadelf='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
$taskRows=@()
foreach($taskArtifact in $taskReceipt.artifacts){
    $taskFile=[IO.Path]::GetFullPath($taskArtifact.file)
    if(!$taskFile.StartsWith($taskStage+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
       (Get-Item -LiteralPath $taskFile).LinkType -or (Get-Item -LiteralPath (Split-Path $taskFile)).LinkType -or
       (Get-Item -LiteralPath $taskFile).Length -ne $taskArtifact.bytes -or
       (Get-FileHash -LiteralPath $taskFile -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskArtifact.sha256){throw 'Native candidate path/identity mismatch'}
    $taskSegments=@(& $taskReadelf --program-headers --wide $taskFile)
    if($LASTEXITCODE -ne 0){throw 'Read-only ELF inspection failed'}
    $taskDynamic=@(& $taskReadelf --dynamic --wide $taskFile)
    if($LASTEXITCODE -ne 0 -or !(($taskDynamic -join "`n") -match 'FLAGS_1.*PIE')){throw 'Native executable lacks explicit PIE flag'}
    $taskLoads=@($taskSegments|Where-Object {$_ -match '^\s*LOAD\s'})
    if(!$taskLoads.Count){throw 'No native LOAD segments'}
    foreach($taskLoad in $taskLoads){
        if($taskLoad -notmatch '^\s*LOAD\s+0x([0-9a-fA-F]+)\s+0x([0-9a-fA-F]+).*\s+0x([0-9a-fA-F]+)\s*$'){throw 'Unexpected ELF LOAD format'}
        $taskOffset=[Convert]::ToUInt64($Matches[1],16);$taskAddress=[Convert]::ToUInt64($Matches[2],16);$taskAlignment=[Convert]::ToUInt64($Matches[3],16)
        if($taskAlignment -lt 16384 -or $taskOffset%16384 -ne $taskAddress%16384){throw 'Native LOAD is not 16KiB compatible'}
    }
    $taskRows+=@{abi=$taskArtifact.abi;file=[IO.Path]::GetFileName($taskFile);sha256=$taskArtifact.sha256;bytes=$taskArtifact.bytes;loadSegments=$taskLoads.Count;pie=$true;load16KiB=$true}
}
$taskResult=[ordered]@{state='PASS_STATIC_ELF_NOT_RUNTIME';stage=$taskStage;nativeExecuted=$false;apkBuilt=$false;artifacts=$taskRows}
[IO.File]::WriteAllText((Join-Path $taskStage 'elf-static-verification.json'),($taskResult|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$taskResult|ConvertTo-Json -Depth 5
