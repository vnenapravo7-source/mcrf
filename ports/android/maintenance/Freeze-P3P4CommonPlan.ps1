param([Parameter(Mandatory)][string]$Definition)
$ErrorActionPreference='Stop'
# This freezes a complete declarative graph. It never invokes a producer,
# compiler, test, installer or cleanup. Missing graph coverage is an error.
$taskRoot='D:/MCRF/Projects/MCRF/ports/android'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
$taskDefinition=Get-Content -LiteralPath $Definition -Raw|ConvertFrom-Json -AsHashtable
$taskFamilies=@('app','csqtt','csqtt20','csqtt215','csqtt25','dnstt','masterdnsvpn','olcrtc','protocols','qwdtt','slipstream','sstp','steer','vaydns','wdtt')
if($taskDefinition.schema -ne 1 -or !$taskDefinition.steps.Count -or
 (@($taskDefinition.steps|ForEach-Object {$_.families}|Sort-Object -Unique) -join ',') -cne ($taskFamilies -join ',')){throw 'Whole fifteen-family common graph required'}
$taskGate=Get-Content "$taskRoot/coordination/p3-p4-2026-10-23.json" -Raw|ConvertFrom-Json
if($taskGate.authority -cne 'human-20261023-finish-current-fixes-then-p3-p4' -or $taskGate.publish -ne $false){throw 'Current non-publication authority required'}
$taskRoots=@('app/src','engines/src','primary-spi/src','primary-adapter/src','transports','primary-cores/steer','protocols-owned-fixture','maintenance')
$taskFiles=@('settings.gradle.kts','build.gradle.kts','app/build.gradle.kts','engines/build.gradle.kts','integration-build.ps1',
 'coordination/p3-p4-2026-10-23.json','coordination/current.json')
foreach($directory in $taskRoots){
 foreach($file in Get-ChildItem -LiteralPath "$taskRoot/$directory" -Recurse -File){
  if($file.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked file in whole-source package'}
  $relative=[IO.Path]::GetRelativePath($taskRoot,$file.FullName).Replace([char]92,[char]47)
  if($relative -match '/(?:build|\.gradle|__pycache__)/'){continue}
  $taskFiles+=$relative
 }
}
$taskRows=@($taskFiles|Sort-Object -Unique|ForEach-Object {
 $item=Get-Item -LiteralPath "$taskRoot/$_"
 if($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Regular whole-source input required'}
 @{path=$item.FullName;sha256=Hash $item.FullName}
})
$taskIds=@()
foreach($step in $taskDefinition.steps){
 if($step.id -cnotmatch '^[a-z][a-z0-9-]{0,63}$' -or $step.id -cin $taskIds -or
  $step.script -cnotmatch '^(?:maintenance|transports|primary-cores)/[A-Za-z0-9_./-]+\.ps1$' -and $step.script -cne 'integration-build.ps1'){throw 'Unsafe common graph node'}
 if('..' -in ($step.script -split '/')){throw 'Parent traversal in common script'}
 foreach($dependency in $step.dependsOn){if($dependency -cnotin $taskIds){throw 'Common graph must be dependency ordered'}}
 $step.scriptSha256=Hash "$taskRoot/$($step.script)"
 # A frozen whole-source manifest accompanies every graph. Individual input
 # assertions remain scoped to the producer's own directory plus authority;
 # producers themselves verify copied-source and artifact closures.
 $directory=Split-Path $step.script -Parent
 $step.inputs=@($taskRows|Where-Object {
  $relative=[IO.Path]::GetRelativePath($taskRoot,$_.path).Replace([char]92,[char]47)
  $relative -ceq 'coordination/p3-p4-2026-10-23.json' -or $relative -ceq 'coordination/current.json' -or
   $relative.StartsWith(($directory.Replace([char]92,[char]47)+'/'),[StringComparison]::Ordinal)
 })
 if(!$step.inputs.Count -or !$step.receiptStates.Count -or $step.replayAccepted -ne $false){throw 'Explicit source/proof/no-replay contract required'}
 $taskIds+=@($step.id)
}
$taskPlan=[ordered]@{schema=1;authority=$taskGate.authority;state='WHOLE_SOURCE_FROZEN_COMMON_PLAN';families=$taskFamilies;publish=$false;replayAccepted=$false;steps=$taskDefinition.steps}
$taskStage="D:/MCRF/Builds/Android/p3-p4-plan-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $taskStage|Out-Null
[IO.File]::WriteAllText("$taskStage/SOURCE_MANIFEST.json",($taskRows|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
$taskPlan.sourceManifest="$taskStage/SOURCE_MANIFEST.json";$taskPlan.sourceManifestSha256=Hash $taskPlan.sourceManifest
[IO.File]::WriteAllText("$taskStage/PLAN.json",($taskPlan|ConvertTo-Json -Depth 30),[Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath $Definition -Destination "$taskStage/DEFINITION.json"
Write-Output "P3_P4_FROZEN_PLAN=$taskStage"
