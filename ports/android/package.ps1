param([string]$AndroidSdk="$env:LOCALAPPDATA/Android/Sdk",[switch]$AppOnly)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskTools=Join-Path $AndroidSdk 'build-tools/36.0.0'
$taskApp=Join-Path $taskRoot 'outputs/ports/android-0.1.0-dev'
New-Item -ItemType Directory -Path $taskApp -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskRows=@();$taskSigner=''
foreach($taskGroup in @('app')){
    $taskSums=@()
    foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64','universal')){
        $taskSource=Join-Path $PSScriptRoot "$taskGroup/build/outputs/apk/release/$taskGroup-$taskAbi-release.apk"
        if(!(Test-Path -LiteralPath $taskSource)){throw "Missing APK: $taskGroup $taskAbi"}
        $taskSignature=(& (Join-Path $taskTools 'apksigner.bat') verify --print-certs $taskSource | Out-String)
        if($LASTEXITCODE -ne 0){throw 'APK signature verification failed'}
        $taskCertificate=[regex]::Match($taskSignature,'Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})').Groups[1].Value
        if(!$taskCertificate){throw 'APK signing certificate not found'}
        if(!$taskSigner){$taskSigner=$taskCertificate}elseif($taskSigner -ne $taskCertificate){throw 'Main app and engine APK signers differ'}
        & (Join-Path $taskTools 'zipalign.exe') -c -P 16 4 $taskSource
        if($LASTEXITCODE -ne 0){throw 'APK ZIP16K alignment verification failed'}
        $taskZip=[IO.Compression.ZipFile]::OpenRead($taskSource)
        try{
            if(@($taskZip.Entries | Where-Object FullName -match 'testserver').Count){throw 'Debug fixture in release APK'}
            foreach($taskLibrary in @($taskZip.Entries|Where-Object Name -like '*.so')){if($taskLibrary.Name -notin @('libbox.so','libtgws.so')){throw "Optional executable embedded in the main APK: $($taskLibrary.FullName)"}}
        }finally{$taskZip.Dispose()}
        $taskFolder=$taskApp;$taskName=switch($taskAbi){'arm64-v8a'{'arm64-v8a-release.apk'}'armeabi-v7a'{'armeabi-v7a-release.apk'}'x86_64'{'androidApp-x86_64-release.apk'}'universal'{'mcrf-universal-release.apk'}}
        $taskDestination=Join-Path $taskFolder $taskName
        Copy-Item -LiteralPath $taskSource -Destination $taskDestination -Force
        $taskHash=(Get-FileHash -LiteralPath $taskDestination).Hash.ToLowerInvariant()
        $taskSums+="$taskHash  $taskName"
        $taskRows+=[PSCustomObject]@{name=$taskName;abi=$taskAbi;bytes=(Get-Item -LiteralPath $taskDestination).Length;sha256=$taskHash;group=$taskGroup;signer=$taskSigner}
    }
    # Generated build metadata, not hand-edited source or user data.
    [IO.File]::WriteAllLines((Join-Path $taskFolder 'SHA256SUMS.txt'),$taskSums,(New-Object Text.UTF8Encoding($false)))
}
Copy-Item -LiteralPath (Join-Path $taskApp 'arm64-v8a-release.apk') -Destination (Join-Path $taskRoot 'outputs/ports/mcrf-android-dev.apk') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'PACKAGE.md') -Destination (Join-Path $taskRoot 'outputs/ports/mcrf-android-dev.README.md') -Force
if(!$AppOnly){
    & (Join-Path $PSScriptRoot 'package-engines.ps1') -AndroidSdk $AndroidSdk
}
$taskRows | Format-Table name,bytes,sha256
