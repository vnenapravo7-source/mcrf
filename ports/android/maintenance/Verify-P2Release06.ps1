param([string]$Stage='D:/MCRF/Builds/Android/p2-release06-e7e81f3a12a14a48808710f07f92289e')
$ErrorActionPreference='Stop'
$source="$Stage/source";$tools='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
function Hash([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
if([IO.Path]::GetFullPath($Stage) -ine [IO.Path]::GetFullPath('D:/MCRF/Builds/Android/p2-release06-e7e81f3a12a14a48808710f07f92289e') -or (Get-Item $Stage).LinkType){throw 'Exact completed release stage required'}
$failed=Get-Content "$Stage/receipt.json" -Raw|ConvertFrom-Json
if($failed.state -cne 'FAILED_PRESERVED' -or $failed.exitCode -ne 0 -or [IO.File]::ReadAllText("$Stage/build.log") -notmatch 'BUILD SUCCESSFUL'){throw 'Actual successful compilation before cardinality verifier failure required'}
$rows=Get-Content "$Stage/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json
foreach($row in $rows){if((Hash "$source/$($row.path)") -cne $row.sha256){throw 'Frozen release source changed'}}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$items=@(@{kind='main';path="$source/ports/android/app/build/outputs/apk/release/app-universal-release.apk";abi='universal'})
foreach($kind in @('csqtt','csqtt25','qwdtt','wdtt','csqtt20','csqtt215')){
 $supportedAbis=if($kind -eq 'csqtt25'){@('arm64-v8a')}else{@('arm64-v8a','armeabi-v7a','x86_64')}
 foreach($abi in $supportedAbis){
  $items+=@{kind=$kind;abi=$abi;path="$source/ports/android/engines/build/outputs/apk/$kind/release/engines-$kind-$abi-release.apk"}
 }
}
foreach($item in $items){
 $file=Get-Item -LiteralPath $item.path;if($file.LinkType){throw 'Ordinary exact APK required'}
 $cert=(& "$tools/apksigner.bat" verify --print-certs $file.FullName 2>&1|Out-String)
 if($LASTEXITCODE -or $cert -notmatch 'certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Existing public signer mismatch'}
 $badging=(& "$tools/aapt.exe" dump badging $file.FullName 2>&1|Out-String)
 if($LASTEXITCODE -or $badging -notmatch "package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'"){throw 'APK identity unavailable'}
 $item.package=$Matches[1];$item.versionCode=[int]$Matches[2];$item.versionName=$Matches[3]
 if($badging -match 'application-debuggable'){throw 'Public release must not be debuggable'}
 $zip=[IO.Compression.ZipFile]::OpenRead($file.FullName)
 try{
  $libs=@($zip.Entries|Where-Object FullName -Match '^lib/.*\.so$')
  if($item.kind -eq 'main'){
   if($item.package -cne 'org.mcrf.client' -or $item.versionCode -ne 10043 -or $item.versionName -cne '0.6' -or $libs.Count){throw 'One native-free universal0.6 main required'}
  }else{
   if($item.package -cne "org.mcrf.engines.$($item.kind)" -or !$libs.Count -or @($libs|Where-Object {$_.FullName -notlike "lib/$($item.abi)/*"}).Count){throw 'Wrong optional engine package/ABI'}
   $native=if($item.kind -eq 'csqtt'){'native-stage-csqtt-legacy'}else{"native-stage-$($item.kind)"}
   foreach($lib in $libs){
    $name=[IO.Path]::GetFileName($lib.FullName)
    $candidates=@(@("$source/ports/android/$native/candidates/$($item.abi)/$name","$source/ports/android/$native/$($item.abi)/$name")|Where-Object {Test-Path -LiteralPath $_})
    if($candidates.Count -ne 1){throw "One source-bound native library required: $($item.kind)/$name"}
    $stream=$lib.Open();try{$sha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()}finally{$stream.Dispose()}
    if($sha -cne (Hash $candidates[0])){throw 'Published native bytes differ from actual accepted module'}
   }
  }
 }finally{$zip.Dispose()}
 $item.bytes=$file.Length;$item.sha256=Hash $file.FullName
}
$result=[ordered]@{state='PASS_RELEASE_PACKAGING';originalVerifierFailurePreserved=$true;compilationReplayed=$false;testsReplayed=$false;nativeRebuilt=$false;
 sourceManifestSha256=Hash "$Stage/SOURCE_MANIFEST.json";buildLogSha256=Hash "$Stage/build.log";sourceStable=$true;versionCode=10043;versionName='0.6';artifacts=$items;published=$false}
[IO.File]::WriteAllText("$Stage/verification-receipt.json",($result|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
Write-Output "RELEASE_VERIFIED=$($items.Count);SOURCE_STABLE=true;BUILD_REPLAYED=false"
