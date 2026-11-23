param([Parameter(Mandatory=$true)][string]$Stage)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskRoot=[IO.Path]::GetFullPath($Stage)
if(!$taskRoot.StartsWith('D:\MCRF\Builds\Android\release07-',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected release root'}
$taskReceipt=Get-Content "$taskRoot/receipt.json" -Raw|ConvertFrom-Json
if(!$taskReceipt.sourceStable -or $taskReceipt.state -cne 'BUILT_NOT_INSTALLED_OR_PUBLISHED'){throw 'Green frozen release required'}
$taskTarget="$taskRoot/assets/mcrf-android-0.7-source.zip"
if(Test-Path $taskTarget){throw 'Source archive exists; verify it before retry'}
$taskBase='D:/MCRF/Builds/Android/release06-publication-20261023/assets/mcrf-android-0.6-source.zip'
$taskFresh='D:/MCRF/Projects/MCRF/work/ports-test-2026-10-14/openflux-build-v0.13.0-a871260fa0024ca089b77c3dccdc3dbc'
$taskPristine='D:/MCRF/Projects/MCRF/work/android-upstream/openflux-core-v0.13.0'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
$taskFrozen=Get-Content "$($taskReceipt.snapshot)/source-manifest.json" -Raw|ConvertFrom-Json
$taskOverlay=@{};foreach($taskRow in $taskFrozen){
 $taskName=$taskRow.path.Replace('\','/')
 if($taskName.StartsWith('openflux-native/')){continue} # release JNI payload is not source
 $taskPath=Join-Path $taskReceipt.snapshot $taskRow.path
 if((Hash $taskPath) -cne $taskRow.sha256){throw 'Frozen corresponding source changed'}
 $taskOverlay[$taskName]=$taskPath
}
$taskTracked=@(& git -C $taskPristine ls-tree -r --name-only HEAD)
if($LASTEXITCODE -or (& git -C $taskPristine rev-parse HEAD) -cne '4f9c786cdd1d9fcd0c133e2e0fec0c5a12522610'){throw 'OpenFlux source identity differs'}
foreach($taskName in $taskTracked){$taskPath=Join-Path $taskFresh $taskName;if(!(Test-Path -LiteralPath $taskPath -PathType Leaf)){throw "OpenFlux tracked source missing: $taskName"};$taskOverlay['upstream/openflux-v0.13.0/'+$taskName]=$taskPath}
# Include the added compiled bridge/overlays, not only the pristine tag files.
foreach($taskFile in Get-ChildItem -LiteralPath $taskFresh -Recurse -File -Filter '*.go'){
 $taskName=[IO.Path]::GetRelativePath($taskFresh,$taskFile.FullName).Replace('\','/')
 $taskOverlay['upstream/openflux-v0.13.0/'+$taskName]=$taskFile.FullName
}
foreach($taskName in @('fresh-core-candidate.json','script-updater-capability.json')){$taskOverlay['build-evidence/openflux/'+$taskName]="$taskFresh/verified-native/$taskName"}
$taskOverlay['build-evidence/openflux/phone-runtime.txt']='D:/MCRF/Builds/Android/openflux13-validation-385c4578d00e4c9aa2be74e47d03e6ab/phone/validation.txt'
$taskOverlay['build-evidence/release07.json']="$($taskReceipt.snapshot)/release07.json"
$taskOverlay['build-evidence/openflux-acceptance.json']="$($taskReceipt.snapshot)/openflux-acceptance.json"
$taskOverlay['ports/android/maintenance/Build-Release07.ps1']='D:/MCRF/Projects/MCRF/ports/android/maintenance/Build-Release07.ps1'
$taskArchive=[IO.Compression.ZipFile]::Open($taskTarget,[IO.Compression.ZipArchiveMode]::Create)
$taskOriginal=[IO.Compression.ZipFile]::OpenRead($taskBase)
$taskManifest=[Collections.Generic.List[object]]::new()
try{
 foreach($taskEntry in $taskOriginal.Entries){
  if($taskEntry.FullName.EndsWith('/') -or $taskEntry.FullName -in @('CORRESPONDING_SOURCE_MANIFEST.json','APP_BUILD_INPUT_MANIFEST.json') -or $taskOverlay.ContainsKey($taskEntry.FullName)){continue}
  if($taskEntry.FullName -match '(^/|(^|/)\.\.(/|$))'){throw 'Source archive path escaped'}
  $taskNew=$taskArchive.CreateEntry($taskEntry.FullName,[IO.Compression.CompressionLevel]::Optimal)
  $taskIn=$taskEntry.Open();$taskOut=$taskNew.Open();try{$taskIn.CopyTo($taskOut)}finally{$taskOut.Dispose();$taskIn.Dispose()}
  $taskManifest.Add(@{path=$taskEntry.FullName;origin='retained0.6-corresponding-source';bytes=$taskEntry.Length})
 }
 foreach($taskName in ($taskOverlay.Keys|Sort-Object)){
  if($taskName -match '(^/|(^|/)\.\.(/|$))'){throw 'Source overlay path escaped'}
  $taskPath=$taskOverlay[$taskName];$taskDigest=Hash $taskPath
  $null=[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskArchive,$taskPath,$taskName,[IO.Compression.CompressionLevel]::Optimal)
  $taskManifest.Add(@{path=$taskName;sha256=$taskDigest;bytes=(Get-Item $taskPath).Length})
 }
 $taskEntry=$taskArchive.CreateEntry('CORRESPONDING_SOURCE_MANIFEST.json');$taskWriter=[IO.StreamWriter]::new($taskEntry.Open(),[Text.UTF8Encoding]::new($false));try{$taskWriter.Write(($taskManifest|ConvertTo-Json -Depth 5))}finally{$taskWriter.Dispose()}
}finally{$taskOriginal.Dispose();$taskArchive.Dispose()}
[IO.File]::WriteAllText("$taskRoot/source-receipt.json",(@{state='CORRESPONDING_SOURCE_ARCHIVE_CREATED';archive=$taskTarget;sha256=(Hash $taskTarget);bytes=(Get-Item $taskTarget).Length;files=$taskManifest.Count;openfluxCommit='4f9c786cdd1d9fcd0c133e2e0fec0c5a12522610';includesPatchedOpenfluxSource=$true;upstreamDependencies='Pinned go.mod/go.sum; fetched using normal Go module toolchain'}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
Write-Output "RELEASE07_SOURCE=$taskTarget;FILES=$($taskManifest.Count)"
