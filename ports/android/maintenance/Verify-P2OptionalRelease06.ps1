param([string]$Stage='D:/MCRF/Builds/Android/p2-optional-release06-413efb66829146af8e0897522e648c33')
$ErrorActionPreference='Stop'
function Hash([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
$r=Get-Content "$Stage/receipt.json" -Raw|ConvertFrom-Json
if($r.state -cne 'COMPILED_OPTIONAL_RELEASE_AWAITING_VERIFY' -or !$r.sourceStable -or $r.exitCode -ne 0){throw 'Completed optional packaging required'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$sdk='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0';$rows=@()
foreach($kind in @('xray','tlscore')){foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){
 $path="$($r.source)/ports/android/engines/build/outputs/apk/$kind/release/engines-$kind-$abi-release.apk"
 $b=(& "$sdk/aapt.exe" dump badging $path 2>&1|Out-String)
 if($LASTEXITCODE -or $b -notmatch "package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'"){throw 'Release package identity required'}
 $pkg=$Matches[1];$code=[int]$Matches[2];$name=$Matches[3];$expected=if($kind -eq 'xray'){6}else{7}
 if($pkg -cne "org.mcrf.engines.$kind" -or $code -ne $expected -or $b -match 'application-debuggable'){throw 'Optional release identity mismatch'}
 $cert=(& "$sdk/apksigner.bat" verify --print-certs $path 2>&1|Out-String)
 if($LASTEXITCODE -or $cert -notmatch 'certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Optional signer mismatch'}
 $publicKind=if($kind -eq 'tlscore'){'tls-core'}else{$kind};$lib=if($kind -eq 'tlscore'){'libtls_core.so'}else{'libxray.so'}
 $zip=[IO.Compression.ZipFile]::OpenRead($path)
 try{$libs=@($zip.Entries|Where-Object FullName -Match '^lib/.*\.so$');if($libs.Count -ne 1 -or $libs[0].FullName -cne "lib/$abi/$lib"){throw 'Exact native allowlist required'}
  $s=$libs[0].Open();try{$sha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($s)).ToLowerInvariant()}finally{$s.Dispose()}
  if($sha -cne (Hash "$($r.nativeStage)/candidates/$publicKind/$abi/$lib")){throw 'Accepted optional runtime bytes changed'}
 }finally{$zip.Dispose()}
 $rows+=@{kind=$publicKind;abi=$abi;path=$path;package=$pkg;versionCode=$code;versionName=$name;bytes=(Get-Item $path).Length;sha256=Hash $path}
}}
foreach($row in (Get-Content "$Stage/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)){if((Hash "$($r.source)/$($row.path)") -cne $row.sha256){throw 'Optional source drift'}}
$proof=@{state='PASS_OPTIONAL_RELEASE_PACKAGING';sourceStable=$true;nativeRebuilt=$false;testsReplayed=$false;artifacts=$rows}
[IO.File]::WriteAllText("$Stage/verification-receipt.json",($proof|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
Write-Output "OPTIONAL_VERIFIED=$($rows.Count);NATIVE_BYTES=ACCEPTED_EXACT"
