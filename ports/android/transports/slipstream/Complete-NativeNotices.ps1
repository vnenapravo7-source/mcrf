param([Parameter(Mandatory)][string]$NativeStage,[Parameter(Mandatory)][string]$PreparedSource,[Parameter(Mandatory)][string]$Inputs)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-build
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
$taskRoot=(Resolve-Path -LiteralPath $NativeStage).Path
$taskSource=(Resolve-Path -LiteralPath $PreparedSource).Path;$taskInputs=(Resolve-Path -LiteralPath $Inputs).Path
if($taskRoot.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-slipstream-native-[a-f0-9]{32}$' -or $taskSource.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-slipstream-source-[a-f0-9]{32}$' -or $taskInputs.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-slipstream-inputs-[a-f0-9]{32}$'){throw 'Exact owned native/source/input stages required'}
$taskReceipt=Get-Content "$taskRoot/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
if($taskReceipt.state -cnotin @('COMPILED_LEGAL_AND_FIXTURE_PENDING','COMPILED_NOTICES_PENDING') -or !$taskReceipt.sourceStable -or $taskReceipt.sourceManifestSha256 -cne (Hash "$taskSource/SOURCE_MANIFEST.json") -or $taskReceipt.dependencyInputReceiptSha256 -cne (Hash "$taskInputs/receipt.json")){throw 'Matching unpromoted native/source/dependency stages required'}
if($taskReceipt.builtRustIdsSha256 -cne (Hash "$taskRoot/built-rust-package-ids.json") -or $taskReceipt.rustMetadataSha256 -cne (Hash "$taskRoot/rust-metadata.json")){throw 'Actual built Rust legal inventory changed'}
$taskIds=@(Get-Content "$taskRoot/built-rust-package-ids.json" -Raw|ConvertFrom-Json)
$taskMetadata=Get-Content "$taskRoot/rust-metadata.json" -Raw|ConvertFrom-Json
$taskNotices=[Collections.Generic.List[object]]::new();$taskMissing=[Collections.Generic.List[string]]::new()
function Add-Notice([string]$From,[string]$Name,[string]$Origin){
 if([IO.Path]::IsPathRooted($Name) -or '..' -in ($Name -split '[/\\]')){throw 'Unsafe Slipstream legal target path'}
 $taskFile=Get-Item -LiteralPath $From
 if($taskFile.LinkType -or $taskFile.PSIsContainer -or $taskFile.Length -gt 2097152){throw 'Regular bounded legal source required'}
 $taskTo=Join-Path "$taskRoot/licenses" $Name;New-Item -ItemType Directory -Path (Split-Path $taskTo) -Force|Out-Null
 if(Test-Path -LiteralPath $taskTo){if((Hash $taskTo) -cne (Hash $From)){throw 'Never overwrite a different frozen legal notice'}}else{Copy-Item -LiteralPath $From -Destination $taskTo}
 $taskNotices.Add(@{file=$Name.Replace([char]92,[char]47);sha256=Hash $taskTo;bytes=$taskFile.Length;origin=$Origin})
}
function Root-Notice([string]$Directory,[string]$Target,[string]$Origin){
 $taskFiles=@(Get-ChildItem -LiteralPath $Directory -File|Where-Object {$_.Name -match '^(LICENSE|LICENCE|COPYING)([._-].*)?$'}|Sort-Object Name)
 if(!$taskFiles.Count){throw 'Missing original dependency licence'}
 Add-Notice $taskFiles[0].FullName $Target $Origin
 foreach($taskExtra in $taskFiles|Select-Object -Skip 1){Add-Notice $taskExtra.FullName "$Target.extra/$($taskExtra.Name)" $Origin}
}
function Header-Notice([string]$From,[string]$Name,[string]$Origin){
 $taskText=[IO.File]::ReadAllText($From)
 $taskHeader=[regex]::Match($taskText,'(?s)\A\s*/\*.*?\*/')
 if(!$taskHeader.Success -or $taskHeader.Value -notmatch '(?i)copyright' -or $taskHeader.Value -notmatch '(?i)permission|redistribution|license|licence|public domain'){throw "Missing embedded legal header: $Name"}
 $taskGenerated=Join-Path "$taskRoot/embedded-notices" $Name
 New-Item -ItemType Directory -Path (Split-Path $taskGenerated) -Force|Out-Null
 $taskBytes=[Text.UTF8Encoding]::new($false).GetBytes($taskHeader.Value)
 if(Test-Path -LiteralPath $taskGenerated){
  if([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($taskBytes)).ToLowerInvariant() -cne (Hash $taskGenerated)){throw 'Embedded legal notice changed'}
 }else{[IO.File]::WriteAllBytes($taskGenerated,$taskBytes)}
 Add-Notice $taskGenerated $Name "$Origin; exact leading comment from $([IO.Path]::GetFileName($From))"
}
Root-Notice "$taskSource/rust" 'Slipstream-LICENSE' 'Mygod/slipstream-rust 7de506b Apache-2.0'
Root-Notice "$taskInputs/picoquic" 'picoquic-LICENSE' 'Mygod/slipstream-picoquic d4a865c5'
# This pinned picotls tree distributes its MIT text inside source headers,
# not a root LICENSE. Preserve its original text, never invent a licence.
Header-Notice "$taskInputs/picotls/include/picotls.h" 'picotls-LICENSE' 'h2o/picotls 5a4461d8'
Root-Notice "$taskInputs/openssl-crate/openssl-src-300.6.0+3.6.2/openssl" 'openssl-LICENSE' 'openssl-src 300.6.0+3.6.2 pinned Cargo checksum'
foreach($taskExtra in Get-ChildItem -LiteralPath "$taskInputs/openssl-crate/openssl-src-300.6.0+3.6.2/openssl" -File|Where-Object {$_.Name -match '^(NOTICE|AUTHORS)([._-].*)?$'}){Add-Notice $taskExtra.FullName "openssl-extra/$($taskExtra.Name)" 'OpenSSL original notice'}
# Cifra/micro-ecc and any embedded copyright notices belong to the original
# static C dependency too, not just the Rust package metadata.
foreach($taskTree in @('picotls','picoquic')){
 foreach($taskLegal in Get-ChildItem -LiteralPath "$taskInputs/$taskTree" -Recurse -File|Where-Object {$_.FullName -notmatch '[/\\]\.git[/\\]' -and $_.Name -match '^(LICENSE|LICENCE|COPYING|NOTICE|AUTHORS)([._-].*)?$'}){
  $taskRelative=[IO.Path]::GetRelativePath("$taskInputs/$taskTree",$taskLegal.FullName).Replace([char]92,[char]47)
  Add-Notice $taskLegal.FullName "c-dependencies/$taskTree/$taskRelative" $taskTree
 }
 foreach($taskFile in Get-ChildItem -LiteralPath "$taskInputs/$taskTree" -Recurse -File|Where-Object {$_.Extension -cin @('.c','.h','.cpp') -and $_.FullName -notmatch '[/\\]\.git[/\\]'}){
  $taskText=[IO.File]::ReadAllText($taskFile.FullName)
  $taskHeader=[regex]::Match($taskText,'(?s)\A\s*/\*.*?\*/')
  if($taskHeader.Success -and $taskHeader.Value -match '(?i)copyright' -and $taskHeader.Value -match '(?i)permission|redistribution|license|licence|public domain'){
   $taskRelative=[IO.Path]::GetRelativePath("$taskInputs/$taskTree",$taskFile.FullName).Replace([char]92,[char]47)
   Header-Notice $taskFile.FullName "c-embedded/$taskTree/$taskRelative.txt" $taskTree
  }
 }
}
Add-Notice 'D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/LICENSE' 'go/LICENSE' 'Go standard library/toolchain'
foreach($taskId in $taskIds){
 $taskPackages=@($taskMetadata.packages|Where-Object {$_.id -ceq $taskId})
 if($taskPackages.Count -ne 1){$taskMissing.Add($taskId);continue}
 $taskPackage=$taskPackages[0];$taskDir=[IO.Path]::GetFullPath((Split-Path $taskPackage.manifest_path))
 $taskAllowed=@("$taskSource/rust",'D:/MCRF/Projects/MCRF/work/toolchains/cargo-home/registry/src')|ForEach-Object {[IO.Path]::GetFullPath($_)}
 $taskBoundary=@($taskAllowed|Where-Object {$taskDir -ieq $_ -or $taskDir.StartsWith($_+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)})
 if($taskBoundary.Count -ne 1){throw 'Built Rust package legal origin escaped frozen source/cache'}
 $taskNode=Get-Item -LiteralPath $taskDir
 while($true){if($taskNode.LinkType){throw 'Linked Rust legal origin'};if($taskNode.FullName -ieq $taskBoundary[0]){break};$taskNode=$taskNode.Parent;if(!$taskNode){throw 'Legal origin boundary missing'}}
 $taskLegal=@(Get-ChildItem -LiteralPath $taskDir -File|Where-Object {$_.Name -match '^(LICENSE|LICENCE|COPYING|NOTICE|AUTHORS)([._-].*)?$'})
 if($taskPackage.license_file){
  if([IO.Path]::IsPathRooted($taskPackage.license_file) -or '..' -in ($taskPackage.license_file -split '[/\\]')){throw 'Unsafe Rust license_file'}
  $taskDeclared=Get-Item -LiteralPath (Join-Path $taskDir $taskPackage.license_file)
  if($taskDeclared.FullName -cnotin $taskLegal.FullName){$taskLegal+=@($taskDeclared)}
 }
 # Workspace crates inherit the root Apache licence, not a missing notice.
 if(!$taskLegal.Count -and $taskDir.StartsWith([IO.Path]::GetFullPath("$taskSource/rust")+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){$taskLegal=@(Get-Item -LiteralPath "$taskSource/rust/LICENSE")}
 if(!$taskLegal.Count){$taskMissing.Add($taskId);continue}
 $taskKey=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($taskId))).ToLowerInvariant()
 foreach($taskFile in $taskLegal){Add-Notice $taskFile.FullName "rust/$taskKey/$($taskFile.Name)" $taskId}
}
Save @{files=@($taskNotices.ToArray());linkedModules=$taskIds.Count;missingModules=@($taskMissing.ToArray());binaryExecuted=$false} "$taskRoot/notice-manifest.json"
$taskReceipt.noticeManifestSha256=Hash "$taskRoot/notice-manifest.json"
$taskReceipt.state=if($taskMissing.Count){'COMPILED_NOTICES_PENDING'}else{'COMPILED_FIXTURE_PENDING'}
Save $taskReceipt "$taskRoot/receipt.json";Write-Output "P3_SLIPSTREAM_LEGAL=$taskRoot"
