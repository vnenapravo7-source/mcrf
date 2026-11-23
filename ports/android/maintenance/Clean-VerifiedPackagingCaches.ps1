param([switch]$Apply,[string]$Plan='',[switch]$FinishEmptyDirectories)
$ErrorActionPreference='Stop'
$taskRoot='D:/MCRF/Projects/MCRF';$taskScratch=[IO.Path]::GetFullPath('D:/MCRF/Builds/Android')
if($FinishEmptyDirectories -and !$Apply){throw 'Finishing requires an existing applied plan'}
$taskRelative=@('ports/android/app/build/intermediates/merged_native_libs','ports/android/app/build/intermediates/stripped_native_libs','ports/android/app/build/intermediates/merged_jni_libs','ports/android/engines/build/intermediates/merged_native_libs','ports/android/engines/build/intermediates/stripped_native_libs','ports/android/engines/build/intermediates/merged_jni_libs')
function Idle {
    $policy=Get-Content -LiteralPath "$taskRoot/ports/android/coordination/build-retention.json" -Raw|ConvertFrom-Json
    if(@($policy.activeSnapshots).Count -or @(Get-CimInstance Win32_Process|Where-Object {$_.Name -match '^(java|javaw|go|cmake|ninja|cargo|rustc|gradle|robocopy)\.exe$'}).Count){throw 'Producer active; no cleanup'}
}
function Files([string]$path){
    if((Get-Item -LiteralPath $path).LinkType -or @(Get-ChildItem -LiteralPath $path -Recurse -Force -Attributes ReparsePoint).Count){throw 'Unexpected link in cleanup/protected tree'}
    @(Get-ChildItem -LiteralPath $path -Recurse -File -Force|Sort-Object FullName|ForEach-Object {[pscustomobject][ordered]@{path=$_.FullName;bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}})
}
function Protected {
    $paths=@('ports/android/app/build/outputs','ports/android/engines/build/outputs','ports/android/app/build/intermediates/built_in_kotlinc','ports/android/app/build/intermediates/javac','ports/android/engines/src/main/jniLibs','ports/android/app/libs')
    $rows=@(foreach($relative in $paths){$absolute="$taskRoot/$relative";if(Test-Path -LiteralPath $absolute){Files $absolute}})
    $json=$rows|ConvertTo-Json -Depth 4 -Compress
    @{files=$rows.Count;bytes=($rows.bytes|Measure-Object -Sum).Sum;sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($json))).ToLowerInvariant()}
}
Idle
if(!$Apply){
    $taskEvidence="$taskRoot/work/ports-test-2026-10-14/project-cleanup-"+[guid]::NewGuid().ToString('N')
    New-Item -ItemType Directory -Path $taskEvidence|Out-Null
    $targets=@(foreach($relative in $taskRelative){
        $absolute=[IO.Path]::GetFullPath("$taskRoot/$relative")
        foreach($parent in @($absolute,[IO.Path]::GetDirectoryName($absolute),[IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($absolute)))){if((Get-Item -LiteralPath $parent).LinkType){throw 'Linked parent rejected'}}
        $files=@(Files $absolute)
        if(!$files.Count -or @($files|Where-Object {[IO.Path]::GetFileName($_.path) -notmatch '^lib[A-Za-z0-9_.-]+\.so$'}).Count){throw 'Unknown/non-native file in fixed packaging cache; preserve it'}
        @{root=$absolute;files=$files;bytes=($files.bytes|Measure-Object -Sum).Sum}
    })
    $empty=@(foreach($directory in Get-ChildItem -LiteralPath $taskScratch -Directory -Filter 'android-integration-*'){
        if($directory.Name -notmatch '^android-integration-[0-9a-f]{32}$' -or $directory.LinkType -or (Get-ChildItem -LiteralPath $directory.FullName -Force|Select-Object -First 1)){continue}
        $receiptPath="$taskRoot/ports/android/coordination/build-history/$($directory.Name)/build-result.json"
        if(!(Test-Path -LiteralPath $receiptPath) -or (Test-Path -LiteralPath "$taskRoot/work/$($directory.Name)")){continue}
        $receipt=Get-Content -LiteralPath $receiptPath -Raw|ConvertFrom-Json
        if($receipt.generatedBuildScratch -and [IO.Path]::GetFullPath($receipt.generatedBuildScratch) -ieq $directory.FullName){$directory.FullName}
    })
    $planObject=@{schema=1;targets=$targets;emptyScratch=$empty;protected=Protected;timeUtc=[DateTime]::UtcNow.ToString('o');scope='Six literal old packaging caches only; originals/APKs/classes/licenses/reports/receipts and dependency caches preserved'}
    [IO.File]::WriteAllText("$taskEvidence/plan.json",($planObject|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    Write-Output "PLAN=$taskEvidence/plan.json"
    [pscustomobject]@{apply=$false;cacheRoots=$targets.Count;nativeCopies=@($targets.files).Count;bytes=($targets.bytes|Measure-Object -Sum).Sum;emptyScratch=$empty.Count;protectedFiles=$planObject.protected.files}|ConvertTo-Json
    return
}
$taskPlanPath=[IO.Path]::GetFullPath($Plan)
if($taskPlanPath -notmatch '^D:\\MCRF\\Projects\\MCRF\\work\\ports-test-2026-10-14\\project-cleanup-[0-9a-f]{32}\\plan.json$'){throw 'Exact prior dry-run plan required'}
$taskPlan=Get-Content -LiteralPath $taskPlanPath -Raw|ConvertFrom-Json
$taskPreviousReceipt=Join-Path ([IO.Path]::GetDirectoryName($taskPlanPath)) 'receipt.json'
if((Test-Path -LiteralPath $taskPreviousReceipt) -and (Get-Content -LiteralPath $taskPreviousReceipt -Raw|ConvertFrom-Json).passed){throw 'Plan already completed; never reapply or overwrite its receipt'}
$taskAllowed=@($taskRelative|ForEach-Object {[IO.Path]::GetFullPath("$taskRoot/$_")})
if($taskPlan.schema -ne 1 -or @($taskPlan.targets).Count -ne 6 -or @($taskPlan.targets.root|Select-Object -Unique).Count -ne 6){throw 'Unexpected plan'}
foreach($target in $taskPlan.targets){
    if($target.root -cnotin $taskAllowed){throw 'Target outside literal allowlist'}
    if($FinishEmptyDirectories){
        if(Test-Path -LiteralPath $target.root){throw 'Finishing only already-removed six cache roots; no repeat deletion'}
        continue
    }
    $actual=@(Files $target.root)
    if(($actual|ConvertTo-Json -Depth 5 -Compress) -cne ($target.files|ConvertTo-Json -Depth 5 -Compress)){throw 'Cache changed after dry-run; no cleanup'}
    foreach($file in $actual){$handle=[IO.File]::Open($file.path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None);$handle.Dispose()}
}
foreach($path in $taskPlan.emptyScratch){
    $absolute=[IO.Path]::GetFullPath($path)
    if([IO.Path]::GetDirectoryName($absolute) -ine $taskScratch -or [IO.Path]::GetFileName($absolute) -notmatch '^android-integration-[0-9a-f]{32}$'){throw 'Empty-directory target escaped scratch root'}
}
$taskBefore=Protected
if($taskBefore.sha256 -cne $taskPlan.protected.sha256){throw 'Protected source libraries/APKs/classes changed after plan'}
Idle
$taskRemoved=if($FinishEmptyDirectories){[long](($taskPlan.targets.bytes|Measure-Object -Sum).Sum)}else{0L};$taskEmptyRemoved=0
$taskSucceeded=$false
try {
if(!$FinishEmptyDirectories){foreach($target in $taskPlan.targets){
    # Absolute literal paths and complete unchanged file hashes verified above.
    Remove-Item -LiteralPath $target.root -Recurse -Force
    $taskRemoved+=[long]$target.bytes
}}
foreach($path in $taskPlan.emptyScratch){
    $absolute=[IO.Path]::GetFullPath($path)
    if([IO.Path]::GetDirectoryName($absolute) -ine $taskScratch -or [IO.Path]::GetFileName($absolute) -notmatch '^android-integration-[0-9a-f]{32}$'){throw 'Empty-directory target escaped scratch root'}
    if(!(Test-Path -LiteralPath $absolute)){continue}
    if((Get-Item -LiteralPath $absolute).LinkType -or (Get-ChildItem -LiteralPath $absolute -Force|Select-Object -First 1)){continue}
    # Nonrecursive: newly nonempty or unknown folders are never removed.
    Remove-Item -LiteralPath $absolute
    $taskEmptyRemoved++
}
$taskSucceeded=$true
}finally{
$taskAfter=Protected
$receipt=@{passed=($taskSucceeded -and $taskBefore.sha256 -ceq $taskAfter.sha256);removedBytes=$taskRemoved;removedCacheRoots=6;removedEmptyScratch=$taskEmptyRemoved;finishedAfterPartial=$FinishEmptyDirectories.IsPresent;protectedBefore=$taskBefore;protectedAfter=$taskAfter;timeUtc=[DateTime]::UtcNow.ToString('o');recoverable='Only generated merged/stripped JNI copies removed; regenerate with normal immutable Gradle build. Original libraries, APKs, classes, licenses and all test evidence retained.'}
[IO.File]::WriteAllText((Join-Path ([IO.Path]::GetDirectoryName($taskPlanPath)) 'receipt.json'),($receipt|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
}
if(!$receipt.passed){throw 'Protected-file verification failed; inspect retained receipt'}
$receipt|Select-Object passed,removedBytes,removedCacheRoots,removedEmptyScratch|ConvertTo-Json
