$ErrorActionPreference='Stop'
$stage="D:/MCRF/Builds/Android/p3-csqtt25-abi-inputs-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $stage|Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$inputs=@(
 @{abi='armeabi-v7a';apkSha256='4ebbcf22370bd2f52a6ae08105577c650bd8f7eca732285afc68775746750ebd';apiAssetId=666575466;elfClass=1;machine=40;retained='D:/MCRF/Builds/Android/p3-csqtt25-abi-inputs-467da4361a644cbd8fbd88a876d29f2f/CSQTT-2.5.1-armeabi-v7a.apk'},
 @{abi='x86_64';apkSha256='85643dbd8e232ae07886f6fe6a68879db257455226de8aa4ac3aeb270314514d';apiAssetId=666575463;elfClass=2;machine=62;retained=''}
)
$receipt=[ordered]@{state='ACQUIRING_PINNED_INPUTS';upstream='SanityProtocol/swg-panel';release='csqtt-app-2.5.1';releaseId=418086617;apiVerifiedOn='2026-10-23';assetsUpdatedOn='2026-10-22T17:33:52Z';pinSource='GitHub release asset API digest; body table still lists withdrawn assets';artifacts=@();nativeExecuted=$false;compiled=$false}
try{
 foreach($row in $inputs){
  $name="CSQTT-2.5.1-$($row.abi).apk";$apk="$stage/$name"
  if($row.retained -and (Test-Path -LiteralPath $row.retained) -and (Get-FileHash -LiteralPath $row.retained).Hash.ToLowerInvariant() -ceq $row.apkSha256){Copy-Item -LiteralPath $row.retained -Destination $apk}
  else{
   & gh release download csqtt-app-2.5.1 --repo SanityProtocol/swg-panel --pattern $name --dir $stage
   if($LASTEXITCODE){throw 'Pinned upstream APK download failed; preserve this attempt'}
  }
  if((Get-FileHash -LiteralPath $apk).Hash.ToLowerInvariant() -cne $row.apkSha256){throw 'Upstream APK digest mismatch'}
  $folder="$stage/$($row.abi)";New-Item -ItemType Directory -Path $folder|Out-Null
  $native="$folder/libcsqtt25_transport.so"
  $zip=[IO.Compression.ZipFile]::OpenRead($apk)
  try{
   $entries=@($zip.Entries|Where-Object {$_.FullName -ceq "lib/$($row.abi)/libclient.so"})
   if($entries.Count -ne 1 -or $entries[0].Length -lt 1000000 -or $entries[0].Length -gt 50000000){throw 'One bounded native client for the exact ABI required'}
   [IO.Compression.ZipFileExtensions]::ExtractToFile($entries[0],$native,$false)
  }finally{$zip.Dispose()}
  $elf=[IO.File]::ReadAllBytes($native)
  if($elf[0] -ne 127 -or $elf[1] -ne 69 -or $elf[2] -ne 76 -or $elf[3] -ne 70 -or $elf[4] -ne $row.elfClass -or $elf[5] -ne 1 -or [BitConverter]::ToUInt16($elf,16) -ne 3 -or [BitConverter]::ToUInt16($elf,18) -ne $row.machine){throw 'ELF class/endian/type/machine mismatch'}
  $is64=$row.elfClass -eq 2
  $offset=if($is64){[BitConverter]::ToUInt64($elf,32)}else{[BitConverter]::ToUInt32($elf,28)}
  $size=[BitConverter]::ToUInt16($elf,$(if($is64){54}else{42}));$count=[BitConverter]::ToUInt16($elf,$(if($is64){56}else{44}))
  if($count -lt 1 -or $offset+$size*$count -gt $elf.Length){throw 'Invalid ELF program headers'}
  # Android's 16KiB ABI checks apply to arm64/x86_64. Preserve honest 4KiB-only
  # metadata for 32-bit ARM instead of claiming that input supports 16KiB.
  $requiredAlignment=if($is64){16384}else{4096}
  $alignments=@();for($i=0;$i -lt $count;$i++){
   $at=[int]($offset+$i*$size);if([BitConverter]::ToUInt32($elf,$at) -ne 1){continue}
   $align=if($is64){[BitConverter]::ToUInt64($elf,$at+48)}else{[BitConverter]::ToUInt32($elf,$at+28)}
   if($align -lt $requiredAlignment){throw "Native client lacks required $requiredAlignment LOAD alignment for $($row.abi)"};$alignments+=$align
  }
  if(!$alignments.Count){throw 'ELF must contain LOAD segments'}
  $receipt.artifacts+=@{abi=$row.abi;file="$($row.abi)/libcsqtt25_transport.so";bytes=$elf.Length;sha256=(Get-FileHash -LiteralPath $native).Hash.ToLowerInvariant();apk=$name;apkSha256=$row.apkSha256;apiAssetId=$row.apiAssetId;elfClass=$row.elfClass;elfMachine=$row.machine;loadAlignments=$alignments;requiredAlignment=$requiredAlignment}
 }
 $receipt.state='PINNED_ABI_INPUTS_STATIC_VERIFIED_NOT_EXECUTED'
}catch{$receipt.state='FAILED_PRESERVED';$receipt.error=$_.Exception.Message;throw}finally{
 [IO.File]::WriteAllText("$stage/receipt.json",($receipt|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false));Write-Output "CSQTT25_ABI_INPUTS=$stage"
}
