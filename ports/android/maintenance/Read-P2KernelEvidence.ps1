#requires -Version 7.0
param([Parameter(Mandatory=$true)][string]$Path,[Parameter(Mandatory=$true)][ValidateSet('group','wg','dispatcher')][string]$Component)
$ErrorActionPreference='Stop'
$taskRoot='D:/MCRF/Projects/MCRF'
function Hash([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Json([string]$p){Get-Content -LiteralPath $p -Raw|ConvertFrom-Json}
$taskPath=[IO.Path]::GetFullPath($Path)
if($taskPath -cnotmatch '^D:\\MCRF\\Builds\\Android\\p2-kernel-[a-f0-9]{32}\\android-kernel.json$' -or (Get-Item -LiteralPath $taskPath).LinkType){throw 'Exact ordinary Android kernel evidence required'}
$taskProof=Json $taskPath;$taskPackage=Json $taskProof.package
$taskGate="$taskRoot/ports/android/coordination/p2-progress-2026-10-20.json"
if($taskProof.priority -ne 2 -or !$taskProof.passed -or !$taskProof.cleanupComplete -or $taskProof.cleanupPending -or
   $taskProof.environment -cne 'android-shell-kernel' -or $taskProof.device -cne 'R5CX73QYN3D' -or $taskProof.uid -ne 2000 -or
   (Hash $taskProof.package) -cne $taskProof.packageSha256 -or $taskPackage.sourceReadySha256 -cne (Hash $taskGate) -or
   !$taskPackage.compiled -or !$taskPackage.sourceStable -or
   $taskPackage.producerSha256 -cne (Hash "$PSScriptRoot/Prepare-P2KernelAndroid.ps1") -or
   $taskPackage.ownerTemplateSha256 -cne (Hash "$PSScriptRoot/p2-kernel-owner.go.in") -or
   $taskProof.producerSha256 -cne (Hash "$PSScriptRoot/Run-P2KernelAndroid.py")){throw 'Completed current source-bound kernel batch required; not app acceptance'}
$taskRow=@($taskProof.programs|Where-Object name -CEQ $Component)
$taskBinary=@($taskPackage.programs|Where-Object name -CEQ $Component)
if($taskRow.Count -ne 1 -or $taskBinary.Count -ne 1){throw 'Unique kernel component required'}
$taskRow=$taskRow[0];$taskBinary=$taskBinary[0]
$taskCount=switch($Component){'group'{8}'wg'{6}'dispatcher'{14}}
if(!$taskRow.passed -or !$taskRow.actualRetirement -or @($taskRow.cases).Count -ne $taskCount -or
   ((@($taskRow.cases)|Sort-Object)-join ',') -cne ((@($taskBinary.expectedCases)|Sort-Object)-join ',') -or
   (Hash $taskBinary.binary) -cne $taskRow.binarySha256 -or $taskRow.binarySha256 -cne $taskBinary.sha256){throw 'Exact actual kernel cases/binary/retirement required'}
foreach($row in $taskBinary.inputs.PSObject.Properties){if((Hash $row.Name) -cne $row.Value){throw 'Kernel production/fixture input changed'}}
$taskOrigin=$taskProof;$taskOriginPackage=$taskPackage;$taskOriginPath=$taskPath
if($taskRow.retainedFrom){
    $ref=$taskRow.retainedFrom
    if((Hash $ref.receipt) -cne $ref.sha256 -or (Hash $ref.package) -cne $ref.packageSha256){throw 'Retained actual origin changed'}
    $taskOrigin=Json $ref.receipt;$taskOriginPackage=Json $ref.package;$taskOriginPath=$ref.receipt
    if(!$taskOrigin.cleanupComplete -or $taskOrigin.cleanupPending){throw 'Retained kernel owner not retired'}
}
$taskActual=@($taskOrigin.programs|Where-Object name -CEQ $Component)[0]
$taskLog=Join-Path ([IO.Path]::GetDirectoryName($taskOriginPath)) ($Component+'-android.log')
if(!$taskActual.actualRetirement -or (Hash $taskLog) -cne $taskActual.logSha256){throw 'Actual executed kernel log changed'}
if($taskRow.retainedEvidence){
    $ref=$taskRow.retainedEvidence
    if((Hash $ref.receipt) -cne $ref.sha256 -or (Hash $ref.package) -cne $ref.packageSha256){throw 'Retained individual cases changed'}
    $prior=Json $ref.receipt;$priorCase=@($prior.programs|Where-Object name -CEQ $Component)[0]
    if(!$prior.cleanupComplete -or $prior.cleanupPending -or !$priorCase.actualRetirement -or
       (Hash (Join-Path ([IO.Path]::GetDirectoryName($ref.receipt)) ($Component+'-android.log'))) -cne $priorCase.logSha256 -or
       ((@($priorCase.cases)|Sort-Object)-join ',') -cne ((@($taskRow.retainedCases)|Sort-Object)-join ',')){throw 'Retained individual-case proof missing'}
}
$taskOriginBinary=@($taskOriginPackage.programs|Where-Object name -CEQ $Component)[0]
if((Hash $taskOriginBinary.binary) -cne $taskActual.binarySha256){throw 'Executed original kernel binary changed'}
[pscustomobject]@{priority=2;state='OWNED_ANDROID_KERNEL_PASS_APP_UNVERIFIED';passed=$true;sourceStable=$true;
    sourceReadySha256=$taskPackage.sourceReadySha256;cases=@($taskRow.cases);actual=$taskRow;program=$taskBinary;
    package=$taskPackage;origin=$taskProof;executedPackage=$taskOriginPackage;path=$taskPath;sha256=(Hash $taskPath);log=$taskLog;logSha256=(Hash $taskLog);
    sourceStage=([IO.Path]::GetDirectoryName($taskProof.package)).Replace('\','/');executedSourceStage=([IO.Path]::GetDirectoryName($taskOrigin.package)).Replace('\','/');
    producerSha256=(Hash $PSCommandPath);androidAppAccepted=$false}
