param([Parameter(Mandatory=$true)][string]$BuildReceipt)
$ErrorActionPreference='Stop'
$taskRoot='D:/MCRF/Projects/MCRF'
$taskBuildPath=[IO.Path]::GetFullPath($BuildReceipt)
if($taskBuildPath -cnotmatch '^D:\\MCRF\\Builds\\Android\\p044-native-source-[a-f0-9]{32}\\build-receipt.json$'){throw 'Unexpected native candidate receipt'}
$taskBuild=Get-Content $taskBuildPath -Raw|ConvertFrom-Json
if($taskBuild.state -cne 'COMPILED_UNINSTALLED_API_ABI_GATES_OPEN' -or !$taskBuild.producerComplete -or !$taskBuild.sourceStable){throw 'Completed stable native candidate required'}
$taskStage=[IO.Path]::GetDirectoryName($taskBuildPath)
$taskRuntime="$taskStage/runtime.aar"
if((Get-FileHash $taskRuntime -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskBuild.aarSha256 -or (Get-Item $taskRuntime).Length -ne $taskBuild.aarBytes){throw 'Native artifact changed'}
$taskEvidence="$taskStage/runtime-static"
if(Test-Path -LiteralPath $taskEvidence){throw 'Existing static evidence: inspect it, do not repeat closed checks'}
[IO.Directory]::CreateDirectory($taskEvidence)|Out-Null
$taskBaseline="$taskRoot/work/android-primary-stage-f386e3b1f236427c8542666076c17d5e/sing-box/runtime.aar"
if((Get-FileHash $taskBaseline -Algorithm SHA256).Hash.ToLowerInvariant() -cne 'cc8089f5ac7731c0b18b4d41ee0baf9a6171f2283781e00850649d4eb16f9678'){throw 'Installed native API baseline changed'}
$taskResult=[ordered]@{schema=1;state='CHECKING';nativeBuild=$taskBuildPath;apiByteExact=$false;abis=@();installed=$false;published=$false;sourceReadyNotAcceptance=$true}
function SaveRuntimeCheck {$taskResult|ConvertTo-Json -Depth 7|Set-Content -LiteralPath "$taskEvidence/receipt.json" -Encoding utf8}
function ZipHash($Zip,[string]$Name){
    $entry=$Zip.GetEntry($Name);if(!$entry){throw 'Missing runtime entry'}
    $stream=$entry.Open();$hash=[Security.Cryptography.SHA256]::Create()
    try{([BitConverter]::ToString($hash.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}finally{$hash.Dispose();$stream.Dispose()}
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
SaveRuntimeCheck
$taskCandidateZip=$null;$taskBaselineZip=$null
try{
    $taskCandidateZip=[IO.Compression.ZipFile]::OpenRead($taskRuntime)
    $taskBaselineZip=[IO.Compression.ZipFile]::OpenRead($taskBaseline)
    $taskExpected=@('jni/arm64-v8a/libbox.so','jni/armeabi-v7a/libbox.so','jni/x86_64/libbox.so')|Sort-Object
    $taskActual=@($taskCandidateZip.Entries|Where-Object {$_.FullName.StartsWith('jni/') -and !$_.FullName.EndsWith('/')}|ForEach-Object {$_.FullName}|Sort-Object)
    if(($taskExpected -join '|') -cne ($taskActual -join '|')){throw 'Unexpected native payload'}
    foreach($pair in @(@{zip=$taskCandidateZip;name='candidate'},@{zip=$taskBaselineZip;name='baseline'})){
        [IO.Compression.ZipFileExtensions]::ExtractToFile($pair.zip.GetEntry('classes.jar'),"$taskEvidence/$($pair.name)-classes.jar")
    }
    $taskOldClasses=[IO.Compression.ZipFile]::OpenRead("$taskEvidence/baseline-classes.jar")
    $taskNewClasses=[IO.Compression.ZipFile]::OpenRead("$taskEvidence/candidate-classes.jar")
    try{
        $oldNames=@($taskOldClasses.Entries|Where-Object {$_.FullName.EndsWith('.class')}|ForEach-Object {$_.FullName}|Sort-Object)
        $newNames=@($taskNewClasses.Entries|Where-Object {$_.FullName.EndsWith('.class')}|ForEach-Object {$_.FullName}|Sort-Object)
        if(($oldNames -join '|') -cne ($newNames -join '|') -or $oldNames.Count -lt 180){throw 'Native Java class surface changed'}
        foreach($name in $oldNames){if((ZipHash $taskOldClasses $name) -cne (ZipHash $taskNewClasses $name)){throw 'Java class bytes changed: inspect actual API before packaging'}}
        $taskResult.apiClasses=$oldNames.Count;$taskResult.apiByteExact=$true
    }finally{$taskOldClasses.Dispose();$taskNewClasses.Dispose()}
    $taskReadelf='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
    foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){
        $file="$taskEvidence/libbox-$abi.so"
        [IO.Compression.ZipFileExtensions]::ExtractToFile($taskCandidateZip.GetEntry("jni/$abi/libbox.so"),$file)
        $header=(& $taskReadelf -h $file 2>&1|Out-String);if($LASTEXITCODE){throw 'ELF header inspection failed'}
        $machine=if($abi -eq 'arm64-v8a'){'AArch64'}elseif($abi -eq 'armeabi-v7a'){'ARM'}else{'(Advanced Micro Devices X86-64|x86-64|EM_X86_64)'}
        if($header -notmatch "Machine:\s+$machine\b" -or $header -notmatch 'Data:\s+2.s complement, little endian'){throw 'ELF ABI identity mismatch'}
        $segments=(& $taskReadelf -lW $file 2>&1|Out-String);if($LASTEXITCODE){throw 'ELF segments inspection failed'}
        $minimum=if($abi -eq 'armeabi-v7a'){4096}else{16384}
        $loads=[regex]::Matches($segments,'(?m)^\s*LOAD\s+[^\r\n]+\s+(0x[0-9a-fA-F]+)\s*$')
        if(!$loads.Count){throw 'No loadable ELF segments'}
        foreach($load in $loads){if([Convert]::ToInt64($load.Groups[1].Value.Substring(2),16) -lt $minimum){throw 'Insufficient ELF page alignment'}}
        $taskResult.abis+=@{abi=$abi;sha256=(Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant();bytes=(Get-Item $file).Length;minimumLoadAlignment=$minimum;loadSegments=$loads.Count}
    }
    $taskResult.state='PASS_API_BYTES_AND_3ABI_ONLY';SaveRuntimeCheck
    Write-Output "P044_RUNTIME_STATIC=$taskEvidence/receipt.json"
}catch{$taskResult.state='FAILED';$taskResult.error=$_.Exception.Message;SaveRuntimeCheck;throw}
finally{if($taskCandidateZip){$taskCandidateZip.Dispose()};if($taskBaselineZip){$taskBaselineZip.Dispose()}}
