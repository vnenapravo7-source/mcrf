param([Parameter(Mandatory=$true)][string]$Snapshot)
$ErrorActionPreference='Stop'
$taskWorkspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskSnapshot=[IO.Path]::GetFullPath((Join-Path $taskWorkspace $Snapshot))
$taskWork=Join-Path $taskWorkspace 'work'
if(!$taskSnapshot.StartsWith($taskWork+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($taskSnapshot) -notmatch '^android-integration-[0-9a-f]{32}$'){throw 'Invalid integration snapshot'}
$taskBuild=Get-Content -LiteralPath (Join-Path $taskSnapshot 'build-result.json') -Raw|ConvertFrom-Json
if($taskBuild.exitCode -ne 0 -or !$taskBuild.sourceStable){throw 'Snapshot is not a successful immutable build'}
$taskAndroid=Join-Path $taskSnapshot 'ports/android'
$taskStage=Get-ChildItem -LiteralPath (Join-Path $taskSnapshot 'work') -Directory -Filter 'android-primary-stage-*'
if(@($taskStage).Count -ne 1){throw 'Exactly one frozen primary stage required'}
$taskReceipt=Get-Content -LiteralPath (Join-Path $taskStage.FullName 'receipt.json') -Raw|ConvertFrom-Json
$taskTelegram=Get-Content -LiteralPath (Join-Path $taskAndroid 'engines/EngineUpdateTelegramStage.json') -Raw|ConvertFrom-Json
$taskTools='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Get-TaskEntryHash($zip,$entry){
    $stream=$zip.GetEntry($entry).Open()
    $hash=[Security.Cryptography.SHA256]::Create()
    try{return ([BitConverter]::ToString($hash.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}finally{$stream.Dispose();$hash.Dispose()}
}
function Assert-TaskSigner($path){
    $output=(& (Join-Path $taskTools 'apksigner.bat') verify --print-certs $path 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $output -notmatch 'Signer #1 certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Unexpected APK signer'}
}
$taskRows=@()
foreach($taskKind in @('singbox','steer','tgws')){
    $taskId=if($taskKind -eq 'singbox'){'sing-box'}else{$taskKind}
    $taskDescriptor=$taskReceipt.modules.$taskId
    $taskAbis=if($taskKind -eq 'tgws'){@($taskTelegram.files.abi)}else{@($taskDescriptor.abis)}
    $taskFiles=@(Get-ChildItem -LiteralPath (Join-Path $taskAndroid "engines/build/outputs/apk/$taskKind/release") -Filter '*.apk')
    if($taskFiles.Count -ne ($taskAbis.Count+1)){throw 'Missing or unexpected ABI module APK'}
    foreach($taskApk in $taskFiles){
        Assert-TaskSigner $taskApk.FullName
        $taskVariant=($taskApk.BaseName -replace "^engines-$taskKind-",'') -replace '-release$',''
        $taskExpectedAbis=if($taskVariant -eq 'universal'){$taskAbis}else{@($taskVariant)}
        if(@($taskExpectedAbis|Where-Object {$_ -notin $taskAbis}).Count -ne 0){throw 'Unsupported ABI artifact'}
        $taskBadging=(& (Join-Path $taskTools 'aapt2.exe') dump badging $taskApk.FullName 2>&1|Out-String)
        $taskVersion=if($taskKind -eq 'tgws'){4}else{[int]$taskDescriptor.versionCode}
        if($LASTEXITCODE -ne 0 -or $taskBadging -notmatch "package: name='org.mcrf.engines.$taskKind' versionCode='$taskVersion'"){throw 'Wrong module identity'}
        $taskZip=[IO.Compression.ZipFile]::OpenRead($taskApk.FullName)
        try{
            $taskLibrary=if($taskKind -eq 'singbox'){'libbox.so'}elseif($taskKind -eq 'steer'){'libsteer_vpn.so'}else{'libtgws.so'}
            $taskActual=@($taskZip.Entries|Where-Object {$_.FullName.StartsWith('lib/') -and !$_.FullName.EndsWith('/')}|ForEach-Object {$_.FullName}|Sort-Object)
            $taskExpected=@($taskExpectedAbis|ForEach-Object {"lib/$_/$taskLibrary"}|Sort-Object)
            if(($taskActual -join '|') -cne ($taskExpected -join '|')){throw 'Unexpected native payload'}
            if(@($taskZip.Entries|Where-Object {$_.FullName -match '(?i)fixture|testserver|peer_fixture'}).Count){throw 'Fixture leaked into module'}
            foreach($taskAbi in $taskExpectedAbis){
                if($taskKind -eq 'tgws'){$taskExpectedHash=($taskTelegram.files|Where-Object {$_.abi -eq $taskAbi}).sha256}else{
                    $taskRuntime=[IO.Compression.ZipFile]::OpenRead((Join-Path $taskStage.FullName "$taskId/runtime.aar"))
                    try{$taskExpectedHash=Get-TaskEntryHash $taskRuntime "jni/$taskAbi/$taskLibrary"}finally{$taskRuntime.Dispose()}
                }
                if((Get-TaskEntryHash $taskZip "lib/$taskAbi/$taskLibrary") -cne $taskExpectedHash){throw 'APK native bytes differ from reviewed runtime'}
            }
            if(!@($taskZip.Entries|Where-Object {$_.FullName.StartsWith('assets/licenses/')}).Count){throw 'Missing module license notices'}
        }finally{$taskZip.Dispose()}
        $taskRows+=[pscustomobject]@{kind=$taskKind;variant=$taskVariant;path=$taskApk.FullName;bytes=$taskApk.Length;sha256=(Get-FileHash -LiteralPath $taskApk.FullName).Hash.ToLowerInvariant();native_exact=$true;signer=$true}
    }
}
foreach($taskApk in Get-ChildItem -LiteralPath (Join-Path $taskAndroid 'app/build/outputs/apk/release') -Filter '*.apk'){
    Assert-TaskSigner $taskApk.FullName
    foreach($taskPrimary in @($taskRows|Where-Object {$_.kind -in @('sing-box','steer')})){
        & python (Join-Path $PSScriptRoot 'verify-shared-kotlin.py') --host $taskApk.FullName --module $taskPrimary.path
        if($LASTEXITCODE -ne 0){throw 'Primary adapter Kotlin runtime is incomplete against the release host'}
    }
    $taskZip=[IO.Compression.ZipFile]::OpenRead($taskApk.FullName)
    try{if(@($taskZip.Entries|Where-Object {$_.FullName.StartsWith('lib/')}).Count){throw 'Base app embeds a native engine'}}finally{$taskZip.Dispose()}
    $taskRows+=[pscustomobject]@{kind='base';path=$taskApk.FullName;bytes=$taskApk.Length;sha256=(Get-FileHash -LiteralPath $taskApk.FullName).Hash.ToLowerInvariant();native_empty=$true;signer=$true}
}
$taskEvidence=Join-Path $taskWorkspace ('work/ports-test-2026-10-14/integration-'+[IO.Path]::GetFileName($taskSnapshot).Substring(20,4)+'-device')
New-Item -ItemType Directory -Path $taskEvidence -Force|Out-Null
$taskOutput=[pscustomobject]@{snapshot=$taskSnapshot;static_apks=$taskRows;device_runtime_verified=$false;time_utc=[DateTime]::UtcNow.ToString('o')}
[IO.File]::WriteAllText((Join-Path $taskEvidence 'primary-apk-static-gate.json'),($taskOutput|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
[pscustomobject]@{static_apks=$taskRows.Count;state='PASS';evidence=$taskEvidence}|ConvertTo-Json
