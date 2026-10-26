param([Parameter(Mandatory)][string]$NativeStage,[Parameter(Mandatory)][string]$PreparedSource,[Parameter(Mandatory)][string]$Inputs)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-build
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
$taskRoot=(Resolve-Path -LiteralPath $NativeStage).Path
if($taskRoot.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-slipstream-native-[a-f0-9]{32}$'){throw 'Exact owned native stage required'}
$taskBefore=Get-Content "$taskRoot/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
if($taskBefore.state -cne 'COMPILED_FIXTURE_PENDING' -or !$taskBefore.sourceStable -or $taskBefore.producerComplete){throw 'Unexecuted legal-only recovery required'}
foreach($taskArtifact in @($taskBefore.artifacts)+@($taskBefore.fixtureArtifacts)){
 if([IO.Path]::IsPathRooted($taskArtifact.file) -or '..' -in ($taskArtifact.file -split '[/\\]') -or (Hash (Join-Path $taskRoot $taskArtifact.file)) -cne $taskArtifact.sha256){throw 'Compiled artifact changed before legal recovery'}
}
$taskOriginal="$PSScriptRoot/Complete-NativeNotices.ps1"
if((Hash $taskOriginal) -cne '4df78aad5133450756261b2c9868886aa4753723bff6d3d06c7ba3840a1a697f'){throw 'Reviewed original collector changed'}
$taskMeta=Get-Content "$taskRoot/rust-metadata.json" -Raw|ConvertFrom-Json
if((Hash "$taskRoot/rust-metadata.json") -cne $taskBefore.rustMetadataSha256){throw 'Compiled metadata changed'}
# cargo-ndk 4.1.2 consumes compiler-artifact messages instead of forwarding
# them to stdout. Retain that original empty inventory, and independently
# recover exact compiled package origins from rustc's saved dependency files.
$taskRows=@();$taskTexts=@()
foreach($taskDep in Get-ChildItem "$taskRoot/rust-target" -Recurse -File -Filter '*.d'){
 if($taskDep.LinkType){throw 'Linked compiler dep-info'}
 $taskRows+=@{file=[IO.Path]::GetRelativePath($taskRoot,$taskDep.FullName);sha256=Hash $taskDep.FullName}
 $taskTexts+=[IO.File]::ReadAllText($taskDep.FullName).Replace([char]92,[char]47)
}
if(!$taskRows.Count){throw 'Actual rustc dependency evidence required'}
$taskText=$taskTexts -join "`n";$taskIds=@()
foreach($taskPackage in $taskMeta.packages){
 $taskDir=(Split-Path $taskPackage.manifest_path).Replace([char]92,[char]47)
 $taskRelative=[IO.Path]::GetRelativePath("$PreparedSource/rust",$taskDir).Replace([char]92,[char]47)
 if($taskText.Contains($taskDir+'/') -or (!$taskRelative.StartsWith('../') -and $taskText.Contains($taskRelative+'/'))){$taskIds+=$taskPackage.id}
}
if($taskIds.Count -lt 20){throw 'Incomplete actual Rust package evidence'}
$taskInventory="$taskRoot/recovered-built-rust-package-ids.json"
if(Test-Path "$taskRoot/legal-inventory-recovery.json"){throw 'Preserve prior legal recovery; do not overwrite'}
Save @($taskIds|Sort-Object -Unique) $taskInventory
Save @{originalInventorySha256=Hash "$taskRoot/built-rust-package-ids.json";recoveredInventorySha256=Hash $taskInventory;depInfo=$taskRows;linkedModules=$taskIds.Count;compilerExecuted=$false;testsExecuted=$false} "$taskRoot/legal-inventory-recovery.json"
# Derive only the collector's inventory reader and allowed unexecuted state.
# All original source/hash/path/notice checks remain in place.
$taskText=[IO.File]::ReadAllText($taskOriginal)
$taskFrom='$taskIds=@(Get-Content "$taskRoot/built-rust-package-ids.json" -Raw|ConvertFrom-Json)'
if(($taskText.Split($taskFrom).Count-1) -ne 1){throw 'Exact inventory reader required'}
$taskText=$taskText.Replace($taskFrom,'$taskIds=@(Get-Content "$taskRoot/recovered-built-rust-package-ids.json" -Raw|ConvertFrom-Json)')
$taskText=$taskText.Replace("@('COMPILED_LEGAL_AND_FIXTURE_PENDING','COMPILED_NOTICES_PENDING')","@('COMPILED_LEGAL_AND_FIXTURE_PENDING','COMPILED_NOTICES_PENDING','COMPILED_FIXTURE_PENDING')")
$taskText=$taskText.Replace('$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1',"$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1")
$taskDerived="$taskRoot/recovered-notice-collector.ps1"
[IO.File]::WriteAllText($taskDerived,$taskText,[Text.UTF8Encoding]::new($false))
& $taskDerived -NativeStage $NativeStage -PreparedSource $PreparedSource -Inputs $Inputs
$taskAfter=Get-Content "$taskRoot/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
if($taskAfter.state -cne 'COMPILED_FIXTURE_PENDING'){throw 'Actual Rust legal closure remains incomplete'}
foreach($taskArtifact in @($taskBefore.artifacts)+@($taskBefore.fixtureArtifacts)){if((Hash (Join-Path $taskRoot $taskArtifact.file)) -cne $taskArtifact.sha256){throw 'Legal recovery changed compiled bytes'}}
Write-Output "P3_SLIPSTREAM_LEGAL_RECOVERY=$taskRoot"
