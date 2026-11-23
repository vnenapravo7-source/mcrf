param()
$ErrorActionPreference='Stop';$stage='D:/MCRF/Builds/Android/singbox10-delivery-fix-db1e29c8858c43f5bc0702033b6ce73d';$old='D:/MCRF/Builds/Android/p2-primary-release06-dfecc988d4a5433fbcd428501d77b36f';$tools='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
function Hash([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function EntryHash($e){$s=$e.Open();try{[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($s)).ToLowerInvariant()}finally{$s.Dispose()}}
$r=Get-Content "$stage/receipt.json" -Raw|ConvertFrom-Json;if($r.state -cne 'COMPILED_SINGBOX10_DELIVERY_FIX' -or $r.exitCode -ne 0 -or !$r.sourceStable){throw 'Completed frozen delivery build required'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$rows=@();foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){
 $path="$stage/source/ports/android/p2-route-dispatcher/build/outputs/apk/release/p2-route-dispatcher-$abi-release.apk"
 $badging=(& "$tools/aapt.exe" dump badging $path 2>&1|Out-String)
 if($LASTEXITCODE -or $badging -notmatch "package: name='org.mcrf.engines.singbox' versionCode='10' versionName='1.14.2-lx.11-mcrf.10'" -or $badging -match 'application-debuggable'){throw 'Wrong fixed module identity'}
 $sig=(& "$tools/apksigner.bat" verify --print-certs $path 2>&1|Out-String)
 if($LASTEXITCODE -or $sig -notmatch 'certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Existing public signer required'}
 $previous="$old/source/ports/android/p2-route-dispatcher/build/outputs/apk/release/p2-route-dispatcher-$abi-release.apk"
 $a=[IO.Compression.ZipFile]::OpenRead($path);$b=[IO.Compression.ZipFile]::OpenRead($previous)
 try{foreach($name in @('classes.dex',"lib/$abi/libbox.so")){if((EntryHash $a.GetEntry($name)) -cne (EntryHash $b.GetEntry($name))){throw 'Runtime/adapter changed in metadata-only delivery repair'}}}finally{$a.Dispose();$b.Dispose()}
 $xml=(& "$tools/aapt.exe" dump xmltree $path AndroidManifest.xml 2>&1|Out-String)
 if($LASTEXITCODE -or $xml -notmatch '(?s)org.mcrf.engines.interface.*?android:value[^\r\n]*0x1\b'){throw 'Delivery interface marker missing'}
 $rows+=@{kind='sing-box';abi=$abi;package='org.mcrf.engines.singbox';versionCode=10;versionName='1.14.2-lx.11-mcrf.10';path=$path;sha256=Hash $path;bytes=(Get-Item $path).Length}
}
foreach($row in (Get-Content "$stage/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)){if((Hash "$($r.source)/$($row.path)") -cne $row.sha256){throw 'Delivery source drift'}}
$result=@{state='PASS_DELIVERY_METADATA_SIGNER_RUNTIME_IDENTITY';nativeRebuilt=$false;mainRebuilt=$false;adapterAndNativeBytesUnchanged=$true;artifacts=$rows}
[IO.File]::WriteAllText("$stage/verification-receipt.json",($result|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false));'DELIVERY_METADATA_VERIFIED=3;RUNTIME_BYTES_UNCHANGED=true'
