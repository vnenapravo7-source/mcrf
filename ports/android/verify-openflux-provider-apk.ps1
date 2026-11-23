param([Parameter(Mandatory=$true)][string]$Artifacts)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskArtifacts=[IO.Path]::GetFullPath((Join-Path $taskRoot $Artifacts))
$taskWork=Join-Path $taskRoot 'work'
if(!$taskArtifacts.StartsWith($taskWork+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($taskArtifacts) -notmatch '^android-module-artifacts-[0-9a-f]{32}$'){throw 'Invalid private module artifact set'}
$taskApks=@(Get-ChildItem -LiteralPath $taskArtifacts -Recurse -File -Filter '*.apk')
if($taskApks.Count -ne 1 -or $taskApks[0].Name -ne 'engines-openflux-arm64-v8a-release.apk'){throw 'Expected only one private ARM64 diagnostic module'}
$taskApk=$taskApks[0]
$taskTools='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
$taskSigner=(& (Join-Path $taskTools 'apksigner.bat') verify --print-certs $taskApk.FullName 2>&1|Out-String)
if($LASTEXITCODE -ne 0 -or $taskSigner -notmatch 'Signer #1 certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Diagnostic APK signer mismatch'}
$taskBadging=(& (Join-Path $taskTools 'aapt2.exe') dump badging $taskApk.FullName 2>&1|Out-String)
if($LASTEXITCODE -ne 0 -or $taskBadging -notmatch "package: name='org.mcrf.engines.openflux.diagnostic' versionCode='8' versionName='0.7.3-mcrf-diagnostic.8'"){throw 'Wrong diagnostic package/version'}
$taskManifest=(& (Join-Path $taskTools 'aapt2.exe') dump xmltree --file AndroidManifest.xml $taskApk.FullName 2>&1|Out-String)
if($LASTEXITCODE -ne 0){throw 'Cannot inspect diagnostic manifest'}
foreach($taskPair in @(
    @('org.mcrf.engines.source.repository','p1neappleXpress/OpenFlux'),
    @('org.mcrf.engines.source.version','v0.7.3'),
    @('org.mcrf.engines.source.commit','f12e981629a18c8c01007d59ac002254c02972bb'),
    @('org.mcrf.engines.source.overlay','mcrf-provider-stage.1'),
    @('org.mcrf.engines.provider-diagnostic-cli','diagnostic-config-v1')
)){
    if($taskManifest -notmatch ('(?s)"'+[regex]::Escape($taskPair[0])+'"[^\r\n]*\r?\n[^\r\n]*"'+[regex]::Escape($taskPair[1])+'"')){throw "Diagnostic metadata mismatch: $($taskPair[0])"}
}
foreach($taskPair in @(@('org.mcrf.engines.provider-diagnostic','1'),@('org.mcrf.engines.script-updater','0'))){
    if($taskManifest -notmatch ('(?s)"'+[regex]::Escape($taskPair[0])+'"[^\r\n]*\r?\n[^\r\n]*=\s*(?:0x)?'+$taskPair[1]+'\b')){throw "Diagnostic capability mismatch: $($taskPair[0])"}
}
if($taskManifest -match '(?m)^\s+E: (activity|service|receiver|provider)\b'){throw 'Diagnostic container has an unexpected component'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskZip=[IO.Compression.ZipFile]::OpenRead($taskApk.FullName)
function Get-TaskEntryHash($name){
    $taskEntry=$taskZip.GetEntry($name)
    if(!$taskEntry){throw 'Missing diagnostic payload'}
    $taskStream=$taskEntry.Open();$taskDigest=[Security.Cryptography.SHA256]::Create()
    try{return ([BitConverter]::ToString($taskDigest.ComputeHash($taskStream))).Replace('-','').ToLowerInvariant()}finally{$taskStream.Dispose();$taskDigest.Dispose()}
}
try{
    $taskNative=@($taskZip.Entries|Where-Object {$_.FullName.StartsWith('lib/') -and !$_.FullName.EndsWith('/')})
    if($taskNative.Count -ne 1 -or $taskNative[0].FullName -ne 'lib/arm64-v8a/libopenflux.so' -or $taskNative[0].Length -ne 27428656){throw 'Unexpected diagnostic native payload'}
    if((Get-TaskEntryHash 'lib/arm64-v8a/libopenflux.so') -cne 'e687063d433e4e93f128a59de4af56356632cdd8da52198e6c7626aa71812518'){throw 'Diagnostic native hash mismatch'}
    if((Get-TaskEntryHash 'assets/licenses/OpenFlux-LICENSE') -cne '230184f60bae2feaf244f10a8bac053c8ff33a183bcc365b4d8b876d2b7f4809'){throw 'Diagnostic license mismatch'}
    $taskAssets=@($taskZip.Entries|Where-Object {$_.FullName.StartsWith('assets/') -and !$_.FullName.EndsWith('/')}|ForEach-Object {$_.FullName})
    if(($taskAssets -join '|') -cne 'assets/licenses/OpenFlux-LICENSE'){throw 'Unexpected private diagnostic assets'}
    if(@($taskZip.Entries|Where-Object {$_.FullName -match '(?i)fixture|config\.json|provider-diagnostic-candidate|\.patch$'}).Count){throw 'Private source/fixture leaked into diagnostic package'}
}finally{$taskZip.Dispose()}
$taskResult=[pscustomobject]@{state='static_verified_not_runtime';package='org.mcrf.engines.openflux.diagnostic';versionCode=8;apk=$taskApk.FullName;apkBytes=$taskApk.Length;apkSha256=(Get-FileHash -LiteralPath $taskApk.FullName).Hash.ToLowerInvariant();nativeSha256='e687063d433e4e93f128a59de4af56356632cdd8da52198e6c7626aa71812518';sourceCommit='f12e981629a18c8c01007d59ac002254c02972bb';signerVerified=$true;exactPayloadVerified=$true;runtimeVerified=$false}
[IO.File]::WriteAllText((Join-Path $taskArtifacts 'provider-static-gate.json'),($taskResult|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$taskResult|ConvertTo-Json
