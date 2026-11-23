param([Parameter(Mandatory=$true)][string]$Snapshot,[Parameter(Mandatory=$true)][string]$NativeStage)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskFrozen=[IO.Path]::GetFullPath((Join-Path $taskRoot $Snapshot))
if(!$taskFrozen.StartsWith((Join-Path $taskRoot 'work')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($taskFrozen) -cnotmatch '^android-integration-[0-9a-f]{32}$'){throw 'Exact frozen snapshot required'}
$taskBuild=Get-Content -LiteralPath (Join-Path $taskFrozen 'build-result.json') -Raw|ConvertFrom-Json
if($taskBuild.exitCode -ne 0 -or $taskBuild.sourceStable -ne $true -or ':app:assembleRelease' -notin $taskBuild.task){throw 'Completed stable common R8 build required'}
$taskNative=[IO.Path]::GetFullPath($NativeStage)
if([IO.Path]::GetDirectoryName($taskNative) -ine [IO.Path]::GetFullPath('D:/MCRF/Builds/Android') -or [IO.Path]::GetFileName($taskNative) -cnotmatch '^s3bypass-native-[0-9a-f]{32}$'){throw 'Exact owned native receipt required'}
$taskProducer=Get-Content -LiteralPath (Join-Path $taskNative 'receipt.json') -Raw|ConvertFrom-Json
if($taskProducer.state -cne 'COMPILED_NOT_EXECUTED' -or !$taskProducer.sourceStable -or !$taskProducer.producerComplete){throw 'Native producer incomplete'}
$taskNotices=Get-Content -LiteralPath (Join-Path $taskNative 'notice-manifest.json') -Raw|ConvertFrom-Json
if(@($taskNotices.missingModules).Count){throw 'Unresolved legal notices'}
$taskSdk='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
$taskRows=[Collections.Generic.List[object]]::new()
$taskSigner=$null
function Read-ApkSigner([string]$File){
    $taskOutput=(& (Join-Path $taskSdk 'apksigner.bat') verify --print-certs $File 2>&1|Out-String)
    if($LASTEXITCODE -or $taskOutput -notmatch 'certificate SHA-256 digest:\s*([0-9a-f]{64})'){throw 'APK signature verification failed'}
    return $Matches[1].ToLowerInvariant()
}
function Entry-Sha($Entry){
    $taskStream=$Entry.Open()
    try{return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($taskStream)).ToLowerInvariant()}finally{$taskStream.Dispose()}
}
$taskAppRoot=Join-Path $taskFrozen 'ports/android/app/build'
$taskMapping=Join-Path $taskAppRoot 'outputs/mapping/release/mapping.txt'
if(!(Test-Path -LiteralPath $taskMapping -PathType Leaf) -or !(Get-Item -LiteralPath $taskMapping).Length){throw 'R8 mapping missing'}
foreach($taskVariant in @('debug','release')){
    $taskApk=Join-Path $taskAppRoot "outputs/apk/$taskVariant/app-universal-$taskVariant.apk"
    $taskCert=Read-ApkSigner $taskApk
    if($taskSigner -and $taskCert -cne $taskSigner){throw 'App variants have different signers'};$taskSigner=$taskCert
    $taskBadging=(& (Join-Path $taskSdk 'aapt.exe') dump badging $taskApk 2>&1|Out-String)
    if($LASTEXITCODE -or $taskBadging -notmatch "package: name='org.mcrf.client'" -or ($taskVariant -eq 'release' -and $taskBadging -match 'application-debuggable')){throw 'Unexpected app identity/debuggability'}
    $taskZip=[IO.Compression.ZipFile]::OpenRead($taskApk)
    try{if(@($taskZip.Entries|Where-Object {$_.FullName -match '^lib/|\.so$'}).Count){throw 'Native payload leaked into base APK'}}finally{$taskZip.Dispose()}
    $taskRows.Add(@{kind="app-$taskVariant";bytes=(Get-Item -LiteralPath $taskApk).Length;sha256=(Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash.ToLowerInvariant();nativeEntries=0})
}
$taskModules=@(Get-ChildItem -LiteralPath (Join-Path $taskFrozen 'ports/android/engines/build/outputs/apk/s3bypass/release') -File -Filter '*.apk')
if($taskModules.Count -ne 4){throw 'Expected three ABI APKs and one universal S3 APK'}
$taskAllAbis=[Collections.Generic.HashSet[string]]::new()
foreach($taskApk in $taskModules){
    if((Read-ApkSigner $taskApk.FullName) -cne $taskSigner){throw 'External module signer differs from host'}
    $taskBadging=(& (Join-Path $taskSdk 'aapt.exe') dump badging $taskApk.FullName 2>&1|Out-String)
    if($LASTEXITCODE -or $taskBadging -notmatch "package: name='org.mcrf.engines.s3bypass'"){throw 'Wrong S3 module package'}
    $taskZip=[IO.Compression.ZipFile]::OpenRead($taskApk.FullName)
    try{
        $taskLibs=@($taskZip.Entries|Where-Object {$_.FullName -match '^lib/'})
        if($taskLibs.Count -notin @(1,3)){throw 'Unexpected external native inventory'}
        foreach($taskLib in $taskLibs){
            if($taskLib.FullName -cnotmatch '^lib/(arm64-v8a|armeabi-v7a|x86_64)/libs3bypass\.so$'){throw 'Unrelated native library in S3 APK'}
            $taskAbi=$Matches[1];[void]$taskAllAbis.Add($taskAbi)
            $taskExpected=@($taskProducer.artifacts|Where-Object {$_.abi -ceq $taskAbi})
            if($taskExpected.Count -ne 1 -or (Entry-Sha $taskLib) -cne $taskExpected[0].sha256){throw 'Packaged native differs from frozen ELF receipt'}
        }
        foreach($taskNotice in $taskNotices.files){
            $taskEntry=$taskZip.GetEntry('assets/licenses/'+$taskNotice.file)
            if(!$taskEntry -or (Entry-Sha $taskEntry) -cne $taskNotice.sha256){throw 'Packaged legal notice missing/changed'}
        }
    }finally{$taskZip.Dispose()}
    $taskRows.Add(@{kind='s3bypass';file=$taskApk.Name;bytes=$taskApk.Length;sha256=(Get-FileHash -LiteralPath $taskApk.FullName -Algorithm SHA256).Hash.ToLowerInvariant();nativeEntries=$taskLibs.Count;notices=@($taskNotices.files).Count})
}
if($taskAllAbis.Count -ne 3){throw 'ABI coverage incomplete'}
$taskResult=[ordered]@{state='PASS';snapshot=$taskFrozen;sourceStable=$true;testSignedOnly=$true;published=$false;signer=$taskSigner;r8Mapping=$taskMapping;baseNative=0;abis=@($taskAllAbis);files=@($taskRows.ToArray());nativeStage=$taskNative;deviceExecuted=$false;scope='New common P0 artifact signature/R8/base-native0/ABI/ELF-byte/notice-byte gates only; not real runtime/provider/Vivo/deployment acceptance'}
[IO.File]::WriteAllText((Join-Path $taskFrozen 'p0-artifacts.json'),($taskResult|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
Write-Output "P0_ARTIFACTS=PASS;BASE_NATIVE=0;ABIS=$($taskAllAbis.Count);NOTICES=$(@($taskNotices.files).Count);TEST_SIGNED_ONLY=true"
