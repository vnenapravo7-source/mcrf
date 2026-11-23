param([Parameter(Mandatory=$true)][string]$Stage)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot '../../maintenance/Assert-HumanBatchReady.ps1') -Operation test
$stageRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot $Stage))
$allowed=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build'))
if(!$stageRoot.StartsWith($allowed+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Candidate outside WG backend build directory'}
$receipt=Get-Content (Join-Path $stageRoot 'receipt.json') -Raw|ConvertFrom-Json
$aar=Join-Path $stageRoot 'wg-backend.aar'
if($receipt.state -ne 'built_awaiting_native_and_device_gates' -or (Get-FileHash -LiteralPath $aar).Hash.ToLowerInvariant() -cne $receipt.aarSha256){throw 'Native artifact is not a verified completed build'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($aar)
$rows=@();$verified=Join-Path $stageRoot 'verified'
New-Item -ItemType Directory -Path $verified -Force|Out-Null
try {
    $native=@($zip.Entries|Where-Object {$_.FullName.StartsWith('jni/')})
    if($native.Count -ne 3){throw 'Unexpected native payload count'}
    foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){
        $entry=$zip.GetEntry("jni/$abi/libmcrfwg.so");if(!$entry){throw 'Missing native ABI'}
        $folder=Join-Path $verified $abi;New-Item -ItemType Directory -Path $folder -Force|Out-Null
        $file=Join-Path $folder 'libmcrfwg.so';[IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$file,$true)
        $elf=(& 'C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe' -lW $file|Out-String)
        if($LASTEXITCODE -ne 0){throw 'ELF read failed'}
        $loads=@($elf -split "`n"|Where-Object {$_ -match '^\s*LOAD\s'})
        if(!$loads.Count){throw 'ELF has no load segments'}
        foreach($load in $loads){if($load -notmatch '(0x[0-9a-fA-F]+)\s*$' -or [Convert]::ToInt64($Matches[1].Substring(2),16) -lt 16384){throw "ELF load alignment below16KiB: $abi"}}
        $rows+=[ordered]@{abi=$abi;nativeBytes=$entry.Length;aarCompressedBytes=$entry.CompressedLength;sha256=(Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant();elf16KiB=$true}
    }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('classes.jar'),(Join-Path $verified 'classes.jar'),$true)
}finally{$zip.Dispose()}
$classes=[IO.Compression.ZipFile]::OpenRead((Join-Path $verified 'classes.jar'))
try {
    $names=@($classes.Entries|ForEach-Object FullName)
    if($names -match 'io/nekohasekai|singbox|libbox'){throw 'Full primary core classes present'}
    if('org/mcrf/wgbackend/wgbackend/Wgbackend.class' -notin $names){throw 'Missing generated bridge API'}
}finally{$classes.Dispose()}
$api=(& 'C:/Program Files/Java/jdk-17/bin/javap.exe' -classpath (Join-Path $verified 'classes.jar') org.mcrf.wgbackend.wgbackend.Wgbackend|Out-String)
if($LASTEXITCODE -ne 0 -or $api -notmatch 'start\(int, int, java.lang.String, org.mcrf.wgbackend.wgbackend.Protector\)'){throw 'Android borrowed-TUN Start interface missing'}
if($receipt.managedDnsContract -cne 'owned_kernel_dns_nat_v1' -or $api -notmatch 'startManaged\(int, int, java.lang.String, org.mcrf.wgbackend.wgbackend.Protector, java.lang.String, java.lang.String, java.lang.String, int\)'){throw 'Source-bound managed DNS native entry missing'}
$dnsApi=(& 'C:/Program Files/Java/jdk-17/bin/javap.exe' -classpath (Join-Path $verified 'classes.jar') org.mcrf.wgbackend.wgbackend.DnsClient|Out-String)
if($LASTEXITCODE -ne 0 -or $dnsApi -notmatch 'getProtocol\(' -or $dnsApi -notmatch 'getSource\(' -or $dnsApi -notmatch 'getSourcePort\(' -or $dnsApi -notmatch 'getDestination\('){throw 'Managed DNS original tuple binding missing'}
$result=[ordered]@{aarSha256=$receipt.aarSha256;aarBytes=$receipt.aarBytes;files=$rows;generatedStartInterfaceVerified=$true;noFullCoreClasses=$true;androidRuntimeTested=$false;published=$false}
[IO.File]::WriteAllText((Join-Path $stageRoot 'verification.json'),($result|ConvertTo-Json -Depth 5))
$result|ConvertTo-Json -Depth 5
