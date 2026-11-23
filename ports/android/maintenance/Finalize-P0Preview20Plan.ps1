param([Parameter(Mandatory=$true)][string]$Stage,[Parameter(Mandatory=$true)][string]$PhoneReceipt)
$ErrorActionPreference='Stop';$utf8=[Text.UTF8Encoding]::new($false)
$stagePath=[IO.Path]::GetFullPath($Stage)
if($stagePath -cnotmatch '^D:\\MCRF\\Builds\\Android\\p0-preview20-release-[a-f0-9]{32}$'){throw 'Exact prepared stage required'}
$plan=Get-Content "$stagePath/plan.json" -Raw|ConvertFrom-Json;$phone=Get-Content $PhoneReceipt -Raw|ConvertFrom-Json
if($plan.tag -cne 'android-v0.1.0-preview.20261019.2' -or !$phone.passed -or !$phone.appInstalled -or !$phone.protectedUnchanged -or !$phone.modulesUnchanged -or $phone.versionCode -ne 10020 -or $phone.sha256 -cne $plan.mainSha){throw 'Exact guarded20 install proof required'}
$remote=(& 'C:/Program Files/GitHub CLI/gh.exe' api 'repos/vnenapravo7-source/mcrf/releases?per_page=50' 2>&1|Out-String)
if($LASTEXITCODE -or @(($remote|ConvertFrom-Json)|Where-Object tag_name -CEQ $plan.tag).Count){throw 'Remote creation began or read failed; immutable assets may not change'}
$build=Get-Content $plan.buildReceipt -Raw|ConvertFrom-Json
$native='D:/MCRF/Builds/Android/p044-native-source-632c2b80b4b9433e85fb1163085a5ae4'
$module='D:/MCRF/Builds/Android/p044-module-apk-3a54d325f3f84aa1a0941a95eb5371f9'
function Sha([string]$p){(Get-FileHash $p -Algorithm SHA256).Hash.ToLowerInvariant()}
function EntryHash($zip,[string]$name){$entry=$zip.GetEntry($name);if(!$entry){throw 'ZIP source missing'};$s=$entry.Open();$h=[Security.Cryptography.SHA256]::Create();try{[Convert]::ToHexString($h.ComputeHash($s)).ToLowerInvariant()}finally{$h.Dispose();$s.Dispose()}}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$rows=@(Get-Content "$($build.stage)/source-manifest.json" -Raw|ConvertFrom-Json)
$zip=[IO.Compression.ZipFile]::OpenRead("$stagePath/assets/mcrf-android-source.zip")
try{if($zip.Entries.Count -ne $rows.Count+1){throw 'Main source count mismatch'};foreach($row in $rows){if((EntryHash $zip ($row.path -replace '\\','/')) -cne $row.sha256){throw 'Main ZIP entry drift'}};if((EntryHash $zip 'SOURCE_MANIFEST.json') -cne (Sha "$($build.stage)/source-manifest.json")){throw 'Main manifest drift'}}finally{$zip.Dispose()}
$nrows=@(Get-Content "$native/source-manifest.json" -Raw|ConvertFrom-Json)
$mrows=@(Get-Content "$module/source-manifest.json" -Raw|ConvertFrom-Json|Where-Object {$_.path -match '^ports/android/(engines|primary-cores/singbox)/'})
$zip=[IO.Compression.ZipFile]::OpenRead("$stagePath/assets/mcrf-sing-box-7-source.zip")
try{if($zip.Entries.Count -ne $nrows.Count+$mrows.Count+3){throw 'Native source count mismatch'};foreach($row in $nrows){if((EntryHash $zip ('native/'+($row.path -replace '\\','/'))) -cne $row.sha256){throw 'Native ZIP drift'}};foreach($row in $mrows){if((EntryHash $zip ('adapter/'+($row.path -replace '\\','/'))) -cne $row.sha256){throw 'Adapter ZIP drift'}};if((EntryHash $zip 'NATIVE_SOURCE_MANIFEST.json') -cne (Sha "$native/source-manifest.json") -or (EntryHash $zip 'MODULE_BUILD_MANIFEST.json') -cne (Sha "$module/source-manifest.json") -or (EntryHash $zip 'runtime-sources.jar') -cne (Sha "$native/runtime-sources.jar")){throw 'Native manifest/runtime source drift'}}finally{$zip.Dispose()}
$evidence=Get-Content "$stagePath/assets/BUILD_EVIDENCE.json" -Raw|ConvertFrom-Json
$evidence.phone.new20Installed=$true;$evidence.phone.protectedExactAtInstall=$true
$nativeBuild=Get-Content "$native/build-receipt.json" -Raw|ConvertFrom-Json
$evidence.native|Add-Member tags $nativeBuild.tags
$evidence|Add-Member sourceArchivesVerified @{mainFiles=$rows.Count;nativeFiles=$nrows.Count;adapterFiles=$mrows.Count;allEntryHashesExact=$true}
[IO.File]::WriteAllText("$stagePath/assets/BUILD_EVIDENCE.json",($evidence|ConvertTo-Json -Depth 12),$utf8)
$notes=[IO.File]::ReadAllText("$stagePath/NOTES.md")
$notes=$notes -replace 'Native build tags: [^\r\n]*?with_mcrf_direct_family\.', 'Полный список native build tags указан в BUILD_EVIDENCE.json.'
[IO.File]::WriteAllText("$stagePath/NOTES.md",$notes,$utf8)
$sums=@(Get-ChildItem "$stagePath/assets" -File|Where-Object Name -CNE 'SHA256SUMS.txt'|Sort-Object Name|ForEach-Object {(Sha $_.FullName)+'  '+$_.Name})
[IO.File]::WriteAllText("$stagePath/assets/SHA256SUMS.txt",($sums -join "`n")+"`n",$utf8)
foreach($a in $plan.assets){$a.bytes=(Get-Item "$stagePath/assets/$($a.name)").Length;$a.sha256=Sha "$stagePath/assets/$($a.name)"}
$plan|Add-Member phoneReceipt $PhoneReceipt
[IO.File]::WriteAllText("$stagePath/plan.json",($plan|ConvertTo-Json -Depth 12),$utf8)
"PUBLIC_PLAN_FINALIZED=$stagePath;MAIN_SOURCES=$($rows.Count);NATIVE_SOURCES=$($nrows.Count);ADAPTER_SOURCES=$($mrows.Count)"
