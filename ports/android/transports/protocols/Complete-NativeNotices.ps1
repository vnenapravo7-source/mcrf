param([Parameter(Mandatory)][string]$NativeStage,[Parameter(Mandatory)][string]$CronetNotices)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-package
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 14),[Text.UTF8Encoding]::new($false))}
$taskStage=(Resolve-Path -LiteralPath $NativeStage).Path;$taskNoticeRoot=(Resolve-Path -LiteralPath $CronetNotices).Path
if($taskStage.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-protocols-native-[a-f0-9]{32}$' -or $taskNoticeRoot.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-cronet-notices-[a-f0-9]{32}$'){throw 'Owned compiled/native notice stages required'}
$taskReceipt=Get-Content "$taskStage/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
if($taskReceipt.kind -cne 'protocols' -or !$taskReceipt.sourceStable -or $taskReceipt.state -cnotin @('COMPILED_LEGAL_AND_FIXTURE_PENDING','OWNED_FIXTURE_VERIFIED_LEGAL_PENDING','COMPILED_FIXTURE_PENDING')){throw 'Exact compiled module required; legal closure never builds/reruns native cases'}
if($taskReceipt.dependencyIntakeLogSha256 -cne (Hash "$taskStage/dependency-intake-verify.log")){throw 'Dependency intake integrity record changed'}
$taskPrepared=Get-Content "$taskStage/prepared-source.json" -Raw|ConvertFrom-Json
$taskModule=$taskPrepared.module
if($taskModule.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-protocols-source-[a-f0-9]{32}/module$' -or $taskReceipt.sourceManifestSha256 -cne (Hash "$taskStage/source-manifest.json")){throw 'Frozen compiled source required'}
$taskNoticeReceipt=Get-Content "$taskNoticeRoot/receipt.json" -Raw|ConvertFrom-Json
$taskNotice=Get-Content "$taskNoticeRoot/notice-manifest.json" -Raw|ConvertFrom-Json
if(!$taskNoticeReceipt.complete -or $taskNoticeReceipt.state -cne 'SOURCE_NOTICE_SUPERSET_PREPARED' -or $taskNoticeReceipt.missing.Count -or $taskNotice.commit -cne '72a06c9fca0e2d228588c7f3074bf7efff3ff686' -or $taskNotice.chromiumVersion -cne '150.0.7871.63' -or $taskNoticeReceipt.manifestSha256 -cne (Hash "$taskNoticeRoot/notice-manifest.json") -or $taskNoticeReceipt.provenanceSha256 -cne (Hash "$taskNoticeRoot/provenance.json") -or $taskNoticeReceipt.headerSourcesSha256 -cne (Hash "$taskNoticeRoot/source-headers.json")){throw 'Original static notices incomplete/changed'}
$taskOriginalNotices=Get-Content "$taskStage/notice-manifest.json" -Raw|ConvertFrom-Json -AsHashtable
$taskLinked=$taskOriginalNotices.linkedModules
if(!$taskLinked.Count){throw 'Actual Go/native module inventory missing'}
$taskFiles=[Collections.Generic.List[object]]::new();$taskMissing=@()
function Add-Notice([string]$From,[string]$Name,[string]$Origin){
 if([IO.Path]::IsPathRooted($Name) -or '..' -in ($Name -split '[/\\]')){throw 'Unsafe notice destination'}
 $taskInput=Get-Item -LiteralPath $From;if($taskInput.PSIsContainer -or $taskInput.LinkType -or $taskInput.Length -gt 4194304){throw 'Regular bounded licence required'}
 $taskTo="$taskStage/licenses/$Name";New-Item -ItemType Directory -Path (Split-Path $taskTo) -Force|Out-Null
 if(Test-Path -LiteralPath $taskTo){if((Hash $taskTo) -cne (Hash $From)){throw 'Do not overwrite a different retained notice'}}else{Copy-Item -LiteralPath $From -Destination $taskTo}
 $taskFiles.Add(@{file=$Name.Replace([char]92,[char]47);origin=$Origin;bytes=$taskInput.Length;sha256=Hash $taskTo})
}
foreach($taskRow in $taskNotice.files){
 if([IO.Path]::IsPathRooted($taskRow.file) -or '..' -in ($taskRow.file -split '[/\\]') -or (Hash "$taskNoticeRoot/files/$($taskRow.file)") -cne $taskRow.sha256){throw 'Original notice bytes changed'}
 Add-Notice "$taskNoticeRoot/files/$($taskRow.file)" "cronet/$($taskRow.file)" 'Pinned Chromium150 source-notice superset'
}
Copy-Item "$taskNoticeRoot/notice-manifest.json" "$taskStage/cronet-notices.json";Copy-Item "$taskNoticeRoot/provenance.json" "$taskStage/cronet-provenance.json";Copy-Item "$taskNoticeRoot/source-headers.json" "$taskStage/cronet-source-headers.json"
Add-Notice "$taskModule/LICENSE" 'SDK-LICENSE.txt' 'Leadaxe/sing-box-lx3d3d3db original SDK and GPL3 frontend'
$taskFullGpl="$PSScriptRoot/../../wg-module/src/main/assets/licenses/GPL-3.0.txt"
$taskGplText=[IO.File]::ReadAllText($taskFullGpl)
if((Hash $taskFullGpl) -cne '3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986' -or $taskGplText.Length -lt 30000 -or !$taskGplText.Contains('GNU GENERAL PUBLIC LICENSE') -or !$taskGplText.Contains('END OF TERMS AND CONDITIONS')){throw 'Full GPL3 text required in addition to short copyright notices'}
Add-Notice $taskFullGpl 'GPL-3.0.txt' 'Full GNU GPL3 licence, not just an SDK notice pointer'
$taskGo='D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/bin/go.exe';Add-Notice 'D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/LICENSE' 'Go-LICENSE.txt' 'Pinned Go toolchain'
$taskEnv=@('GOROOT','GOENV','GOWORK','GOFLAGS','GOTOOLCHAIN','GOPROXY','GOPATH','GOMODCACHE');$taskOld=@{};foreach($taskName in $taskEnv){$taskOld[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
try{
 [Environment]::SetEnvironmentVariable('GOROOT',$null,'Process');$env:GOENV='off';$env:GOWORK='off';$env:GOFLAGS='';$env:GOTOOLCHAIN='local';$env:GOPROXY='off';$env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-home';$env:GOMODCACHE="$env:GOPATH/pkg/mod"
 Push-Location $taskModule
 try{$taskModules=@(& $taskGo list -mod=readonly -m -f '{{.Path}}|{{if .Replace}}{{.Replace.Dir}}{{else}}{{.Dir}}{{end}}' all);if($LASTEXITCODE){throw 'Actual dependency origin inventory unavailable'}}finally{Pop-Location}
 foreach($taskPath in @($taskLinked.Keys|Sort-Object)){
  $taskRows=@($taskModules|Where-Object {($_ -split '\|',2)[0] -ceq $taskPath});if($taskRows.Count -ne 1){$taskMissing+=$taskPath;continue}
  $taskDirectory=[IO.Path]::GetFullPath(($taskRows[0] -split '\|',2)[1]);$taskCache=[IO.Path]::GetFullPath($env:GOMODCACHE)
  if(!$taskDirectory.StartsWith($taskCache+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -and !$taskDirectory.StartsWith([IO.Path]::GetFullPath($taskModule)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Dependency origin escaped cache/frozen SDK'}
  $taskLegal=@(Get-ChildItem -LiteralPath $taskDirectory -File|Where-Object {$_.Name -match '^(LICENSE|LICENCE|COPYING|NOTICE|AUTHORS)([._-].*)?$'})
  if(!$taskLegal.Count -and $taskPath -ceq 'mcrf.local/optionalbridge'){$taskLegal=@(Get-Item "$taskModule/LICENSE")}
  if(!$taskLegal.Count){$taskMissing+=$taskPath;continue}
  $taskId=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($taskPath))).ToLowerInvariant()
  foreach($taskFile in $taskLegal){Add-Notice $taskFile.FullName "go/$taskId/$($taskFile.Name)" "$taskPath@$($taskLinked[$taskPath])"}
 }
 foreach($taskLibrary in $taskReceipt.cronetLibraries){
  if($taskLibrary.module -cnotin @('github.com/sagernet/cronet-go/lib/android_arm','github.com/sagernet/cronet-go/lib/android_arm64','github.com/sagernet/cronet-go/lib/android_amd64') -or $taskLinked[$taskLibrary.module] -cne 'v0.0.0-20260912104006-c10c03c318db' -or (Hash "$($taskLibrary.directory)/libcronet.a") -cne $taskLibrary.sha256){throw 'Compiled static library provenance changed'}
  if([IO.File]::ReadAllText("$($taskLibrary.directory)/libcronet_cgo.go") -notmatch 'Version = "150\.0\.7871\.63"'){throw 'Static library Chromium version differs from notices'}
 }
 if(@($taskReceipt.cronetLibraries).Count -ne 3){throw 'All three actual Cronet archive inputs required'}
}finally{foreach($taskName in $taskEnv){[Environment]::SetEnvironmentVariable($taskName,$taskOld[$taskName],'Process')}}
Save @{files=$taskFiles.ToArray();linkedModules=$taskLinked;missing=$taskMissing;complete=($taskMissing.Count -eq 0);cronetScope='conservative-source-notice-superset';cronetSourceManifestSha256=Hash "$taskStage/cronet-notices.json";cronetProvenanceSha256=Hash "$taskStage/cronet-provenance.json";cronetCopyrightSourcesSha256=Hash "$taskStage/cronet-source-headers.json"} "$taskStage/notice-manifest.json"
$taskReceipt.noticeManifestSha256=Hash "$taskStage/notice-manifest.json";$taskReceipt.producerComplete=$false
if($taskMissing.Count){Save $taskReceipt "$taskStage/receipt.json";throw 'Required linked Go notices unresolved; legal retry does not replay native cases'}
if($taskReceipt.state -ceq 'OWNED_FIXTURE_VERIFIED_LEGAL_PENDING'){
 if(!$taskReceipt.ownedLogSha256 -or $taskReceipt.ownedLogSha256 -cne (Hash "$taskStage/owned-tests.jsonl") -or @($taskReceipt.cases).Count -ne 15 -or $taskReceipt.failures -ne 0 -or $taskReceipt.skipped -ne 0){throw 'Actual retained15case proof missing; never infer success from legal closure'}
 $taskReceipt.producerComplete=$true;$taskReceipt.state='COMPILED_OWNED_FIXTURE_VERIFIED'
}else{$taskReceipt.state='COMPILED_FIXTURE_PENDING'}
Save $taskReceipt "$taskStage/receipt.json"
Write-Output "PROTOCOLS_NOTICES_COMPLETE_NATIVE_STATE=$($taskReceipt.state)"
