param([Parameter(Mandatory)][string]$Stage)
$ErrorActionPreference='Stop'
function Hash([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
$r=Get-Content "$Stage/receipt.json" -Raw|ConvertFrom-Json
if($r.state -cne 'COMPILED_RELEASE_AWAITING_VERIFY' -or !$r.sourceStable -or $r.exitCode -ne 0){throw 'Actual completed packaging required'}
$source=$r.source;$tools='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$rows=@();$mapping=@(
 @{kind='steer';folder='engines/build/outputs/apk/steer/release';code=14;library='libsteer_vpn.so';aar='work/p2-primary-p2-steer-candidate-d3992f56f32d41438c654d3d785162a1/steer/runtime.aar';abis=@('arm64-v8a','x86_64')},
 @{kind='sing-box';folder='p2-route-dispatcher/build/outputs/apk/release';code=9;library='libbox.so';aar='work/p2-dispatcher-native-p2-singbox-route-source-2f8e34d8ca304776b0b0cb3eb452f1f1/runtime.aar';abis=@('arm64-v8a','armeabi-v7a','x86_64')},
 @{kind='wg-awg';folder='wg-module/build/outputs/apk/release';code=5;library='libmcrfwg.so';aar='ports/android/transports/wg-backend/build/candidate-0a6d6707d0ad443595d82da34ea85e1a/wg-backend.aar';abis=@('arm64-v8a','armeabi-v7a','x86_64')})
function EntryHash($entry){$s=$entry.Open();try{[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($s)).ToLowerInvariant()}finally{$s.Dispose()}}
foreach($m in $mapping){
 # AGP strips the sing-box AAR in both accepted debug and release packages.
 # Compare its shipped bytes to the actual accepted APK, not the unstripped AAR.
 $reference=if($m.kind -eq 'sing-box'){'D:/MCRF/Projects/MCRF/work/android-integration-0dcead46dd0e473fa46510ffe38f6dea/ports/android/p2-route-dispatcher/build/outputs/apk/debug/p2-route-dispatcher-debug.apk'}else{"$source/$($m.aar)"}
 if($m.kind -eq 'sing-box' -and (Hash $reference) -cne 'a28af5645572f974cde34e959709e355a60d61ae8ad376fa0c473d78cf0205ec'){throw 'Accepted dispatcher APK drift'}
 $aar=[IO.Compression.ZipFile]::OpenRead($reference)
 try{foreach($abi in $m.abis){
  $files=@(Get-ChildItem "$source/ports/android/$($m.folder)" -Filter "*-$abi-release.apk" -File)
  if($files.Count -ne 1){throw 'Exact supported ABI APK required'};$path=$files[0].FullName
  $cert=(& "$tools/apksigner.bat" verify --print-certs $path 2>&1|Out-String)
  if($LASTEXITCODE -or $cert -notmatch 'certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Public signer mismatch'}
  $b=(& "$tools/aapt.exe" dump badging $path 2>&1|Out-String)
  if($LASTEXITCODE -or $b -notmatch "package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'"){throw 'Package identity missing'}
  $pkg=$Matches[1];$code=[int]$Matches[2];$name=$Matches[3]
  $expected=if($m.kind -eq 'wg-awg'){'org.mcrf.engines.wg'}elseif($m.kind -eq 'sing-box'){'org.mcrf.engines.singbox'}else{'org.mcrf.engines.steer'}
  if($pkg -cne $expected -or $code -ne $m.code -or $b -match 'application-debuggable'){throw 'Wrong primary release identity'}
  $apk=[IO.Compression.ZipFile]::OpenRead($path)
  try{
   $libs=@($apk.Entries|Where-Object FullName -Match '^lib/.*\.so$')
   if(!$libs.Count -or @($libs|Where-Object FullName -NotLike "lib/$abi/*").Count){throw 'Wrong primary native ABI'}
   foreach($lib in $libs){$entryName=if($m.kind -eq 'sing-box'){$lib.FullName}else{$lib.FullName.Replace('lib/','jni/')};$native=$aar.GetEntry($entryName);if(!$native -or (EntryHash $native) -cne (EntryHash $lib)){throw 'Native runtime changed from accepted reference'}}
   if(!($libs.FullName -contains "lib/$abi/$($m.library)")){throw 'Expected primary library missing'}
  }finally{$apk.Dispose()}
  $rows+=@{kind=$m.kind;abi=$abi;path=$path;package=$pkg;versionCode=$code;versionName=$name;sha256=Hash $path;bytes=$files[0].Length}
 }}finally{$aar.Dispose()}
}
foreach($row in (Get-Content "$Stage/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)){if((Hash "$source/$($row.path)") -cne $row.sha256){throw 'Primary source drift'}}
$proof=@{state='PASS_PRIMARY_RELEASE_PACKAGING';sourceStable=$true;nativeRebuilt=$false;testsReplayed=$false;artifacts=$rows;sourceManifestSha256=Hash "$Stage/SOURCE_MANIFEST.json"}
[IO.File]::WriteAllText("$Stage/verification-receipt.json",($proof|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
Write-Output "PRIMARY_RELEASE_VERIFIED=$($rows.Count);NATIVE_BYTES=ACCEPTED_EXACT"
