param(
    [string]$AdapterSnapshot='',
    [string]$OwnerIsolationAdapterReceipt='',
    [string]$SteerStage='outputs/steer-android/primary-stage-dev.5',
    [ValidateRange(1,2100000000)][int]$SingboxVersionCode=6,
    [string]$OwnerIsolationNativeStage='',
    [switch]$OwnerIsolationOriginalCarrier
)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if([bool]$AdapterSnapshot -eq [bool]$OwnerIsolationAdapterReceipt){throw 'Specify exactly one verified adapter source'}
$taskOwnerAdapter=$null
if($OwnerIsolationAdapterReceipt){
    if(!$OwnerIsolationNativeStage){throw 'An owner adapter requires the fresh owner native runtime'}
    $ownerProof=& (Join-Path $PSScriptRoot 'build-owner-adapter.ps1') -VerifyReceipt $OwnerIsolationAdapterReceipt
    if($LASTEXITCODE){throw 'Owner adapter receipt verification failed'}
    $taskOwnerAdapter=$ownerProof|ConvertFrom-Json
    $taskFrozen=$taskRoot
}else{
    $taskFrozen=[IO.Path]::GetFullPath((Join-Path $taskRoot $AdapterSnapshot))
    if(!$taskFrozen.StartsWith((Join-Path $taskRoot 'work')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($taskFrozen) -notmatch '^android-integration-[0-9a-f]{32}$'){throw 'Only a verified integration snapshot can supply the adapter'}
    $taskBuild=Get-Content (Join-Path $taskFrozen 'build-result.json') -Raw|ConvertFrom-Json
    if($taskBuild.exitCode -ne 0 -or !$taskBuild.sourceStable -or ':primary-adapter:verifyReleaseAdapter' -notin $taskBuild.task){throw 'Actual adapter verification required'}
}
$taskStage=Join-Path $taskRoot ('work/android-primary-stage-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskStage|Out-Null
$taskSteer=[IO.Path]::GetFullPath($(if([IO.Path]::IsPathRooted($SteerStage)){$SteerStage}else{Join-Path $taskRoot $SteerStage}))
$taskSteerOwner=$taskSteer -match '^D:\\MCRF\\Builds\\Android\\steer-owner-[A-Za-z0-9.-]+\\primary-stage$'
if(!$taskSteerOwner -and !$taskSteer.StartsWith((Join-Path $taskRoot 'outputs/steer-android')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected Steer stage'}
if($taskSteerOwner){
    $taskSteerVerifier=Join-Path $PSScriptRoot 'primary-cores/steer/owner_isolation_producer.py'
    & python -B $taskSteerVerifier --verify $taskSteer
    if($LASTEXITCODE){throw 'Fresh Steer owner admission stage verification failed'}
}
$taskSteerReceipt=Get-Content (Join-Path $taskSteer 'receipt.json') -Raw|ConvertFrom-Json
$taskSteerModule=$taskSteerReceipt.modules.steer
if($taskSteerReceipt.version -ne 1 -or !$taskSteerModule){throw 'Missing Steer descriptor'}
function Copy-TaskArtifact([string]$source,[string]$target,[string]$hash,[long]$bytes) {
    if((Get-Item -LiteralPath $source).Length -ne $bytes -or (Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant() -cne $hash){throw 'Stage input differs from descriptor'}
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($target)) -Force|Out-Null
    [IO.File]::Copy($source,$target,$false)
    if((Get-FileHash -LiteralPath $target).Hash.ToLowerInvariant() -cne $hash){throw 'Stage copy changed'}
}
foreach($group in @('artifacts','licenses','bundledDependencies')) {
    foreach($row in $taskSteerModule.$group) {
        $safeName=if($group -eq 'bundledDependencies'){$row.file -cmatch '^bundled/[A-Za-z0-9_-][A-Za-z0-9._-]*\.jar$'}else{$row.file -cmatch '^[A-Za-z0-9_-][A-Za-z0-9._-]*$'}
        if(!$safeName -or $row.file -in @('.','..')){throw 'Unsafe artifact basename'}
        $suffix=if($group -eq 'licenses'){"steer/licenses/$($row.file)"}else{"steer/$($row.file)"}
        Copy-TaskArtifact (Join-Path $taskSteer $suffix) (Join-Path $taskStage $suffix) $row.sha256 $row.bytes
    }
}
if($taskSteerOwner){
    foreach($row in @(@{file='source-manifest.json';sha=$taskSteerModule.sourceSha256},@{file='owner-proof.json';sha=$taskSteerModule.ownerProofSha256})){
        $source=Join-Path $taskSteer "steer/$($row.file)"
        Copy-TaskArtifact $source (Join-Path $taskStage "steer/$($row.file)") $row.sha (Get-Item -LiteralPath $source).Length
    }
}
$taskAdapter=if($taskOwnerAdapter){$taskOwnerAdapter.adapter}else{Join-Path $taskFrozen 'ports/android/primary-adapter/build/outputs/aar/primary-adapter-release.aar'}
$taskRuntime=Join-Path $taskFrozen 'ports/android/app/libs/libbox.aar'
$taskLock=Get-Content (Join-Path $taskFrozen 'ports/android/android-core-lock.json') -Raw|ConvertFrom-Json
if($OwnerIsolationOriginalCarrier -and !$OwnerIsolationNativeStage){throw 'Specify the fresh native owner admission stage'}
$taskNativeReceiptPath=$null
if($OwnerIsolationNativeStage){
    $native=[IO.Path]::GetFullPath((Join-Path $taskRoot 'work'))
    $candidate=[IO.Path]::GetFullPath($OwnerIsolationNativeStage)
    if([IO.Path]::GetDirectoryName($candidate) -ine $native -or [IO.Path]::GetFileName($candidate) -cnotmatch '^android-core-clash-build-[0-9a-f]{32}$' -or
       (Get-Item -LiteralPath $candidate).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Exact ordinary candidate path required'}
    $taskNativeReceiptPath=Join-Path $candidate 'receipt.json'
    $taskNativeReceipt=Get-Content -LiteralPath $taskNativeReceiptPath -Raw|ConvertFrom-Json
    if($taskNativeReceipt.state -cne 'built_awaiting_abi_api_and_device_gates' -or !$taskNativeReceipt.vpnOwnerIsolation -or
       $taskNativeReceipt.version -cnotmatch '\.owner2$' -or $taskNativeReceipt.commit -cne $taskLock.commit -or
       $taskNativeReceipt.producerSha256 -cne (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'native-core/build.ps1')).Hash.ToLowerInvariant() -or
       $taskNativeReceipt.ownerListenerTestsSha256 -cne (Get-FileHash -LiteralPath (Join-Path $candidate 'owner-listener-tests.log')).Hash.ToLowerInvariant() -or
       $taskNativeReceipt.aarSha256 -cnotmatch '^[a-f0-9]{64}$' -or $taskNativeReceipt.aarBytes -le 0 -or
       (@($taskNativeReceipt.targets|Sort-Object)-join ',') -cne 'amd64,arm,arm64'){throw 'Source-bound three-ABI owner admission runtime required'}
    if($OwnerIsolationOriginalCarrier -and (!$taskNativeReceipt.rawOverlay -or
       'with_lx_carrier_stack' -cnotin $taskNativeReceipt.rawOverlay.tags -or $SingboxVersionCode -lt 10)){throw 'Original carrier requires a compatible pinned p4 candidate'}
    $taskRuntime=Join-Path $candidate 'libbox-mcrf-clash.aar'
    Copy-TaskArtifact $taskRuntime (Join-Path $taskStage 'sing-box/runtime.aar') $taskNativeReceipt.aarSha256 $taskNativeReceipt.aarBytes
    $taskCoreVersion=$taskNativeReceipt.version
}else{
    Copy-TaskArtifact $taskRuntime (Join-Path $taskStage 'sing-box/runtime.aar') $taskLock.sha256 $taskLock.bytes
    $taskCoreVersion=$taskLock.version
}
if($taskOwnerAdapter){
    Copy-TaskArtifact $taskAdapter (Join-Path $taskStage 'sing-box/adapter.aar') $taskOwnerAdapter.adapterSha256 $taskOwnerAdapter.adapterBytes
    $taskSources=@(Get-Content -LiteralPath $taskOwnerAdapter.sourceManifest -Raw|ConvertFrom-Json)
}else{
    Copy-TaskArtifact $taskAdapter (Join-Path $taskStage 'sing-box/adapter.aar') (Get-FileHash $taskAdapter).Hash.ToLowerInvariant() (Get-Item $taskAdapter).Length
    $taskManifest=Get-Content (Join-Path $taskFrozen 'source-manifest.json') -Raw|ConvertFrom-Json
    $taskSources=@($taskManifest|Where-Object {$_.path -match '^ports/android/primary-cores/singbox/src/|^ports/android/primary-spi/contract-lock.json$|^ports/android/android-core-lock.json$|^ports/android/app/libs/libbox.aar$'})
}
if($taskSources.Count -lt 8){throw 'Missing adapter source provenance'}
$taskNotices=Join-Path $taskStage 'sing-box/licenses'
New-Item -ItemType Directory -Path $taskNotices|Out-Null
$taskSourceIndex=Join-Path $taskNotices 'source-manifest.json'
if($taskNativeReceiptPath){
    $savedNative=Join-Path $taskNotices 'native-owner-receipt.json'
    [IO.File]::Copy($taskNativeReceiptPath,$savedNative,$false)
    $taskSources+= [ordered]@{path='native-owner-receipt.json';sha256=(Get-FileHash $savedNative).Hash.ToLowerInvariant()}
}
if($taskOwnerAdapter){
    $savedAdapter=Join-Path $taskNotices 'adapter-owner-receipt.json'
    [IO.File]::Copy($taskOwnerAdapter.receipt,$savedAdapter,$false)
    $taskSources+= [ordered]@{path='adapter-owner-receipt.json';sha256=(Get-FileHash $savedAdapter).Hash.ToLowerInvariant()}
}
[IO.File]::WriteAllText($taskSourceIndex,($taskSources|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
foreach($name in @('sing-box-GPL-3.0.txt','sing-box-NOTICE.txt')){[IO.File]::Copy((Join-Path $taskFrozen "ports/android/app/src/main/assets/licenses/$name"),(Join-Path $taskNotices $name),$false)}
[IO.File]::Copy((Join-Path $taskSteer 'steer/licenses/steer-GPL-3.0.txt'),(Join-Path $taskNotices 'GPL-3.0.txt'),$false)
$taskSourceSha=(Get-FileHash $taskSourceIndex).Hash.ToLowerInvariant()
function Get-TaskRow([string]$file) {[ordered]@{file=[IO.Path]::GetFileName($file);bytes=(Get-Item $file).Length;sha256=(Get-FileHash $file).Hash.ToLowerInvariant()}}
$taskSingbox=[ordered]@{spi=1;api=1;entry='org.mcrf.engines.singbox.SingBoxEngine';versionCode=$SingboxVersionCode;moduleVersion="1.14.2-lx.11-mcrf-module.$SingboxVersionCode";sourceRepository='https://github.com/Leadaxe/sing-box-lx';sourceVersion=($taskCoreVersion+'-adapter-'+$taskSourceSha.Substring(0,12));sourceSha256=$taskSourceSha;abis=@('arm64-v8a','armeabi-v7a','x86_64');artifacts=@((Get-TaskRow (Join-Path $taskStage 'sing-box/runtime.aar')),(Get-TaskRow (Join-Path $taskStage 'sing-box/adapter.aar')));licenses=@(Get-ChildItem -LiteralPath $taskNotices -File|ForEach-Object {Get-TaskRow $_.FullName})}
if($OwnerIsolationOriginalCarrier){$taskSingbox.originalCarrierContract=1}
$taskReceipt=[ordered]@{version=1;modules=[ordered]@{'sing-box'=$taskSingbox;steer=$taskSteerModule};scope='Local development modules; static gated stage, not runtime/device acceptance or a public release';adapterSnapshot=$taskFrozen;steerReceiptSha256=(Get-FileHash (Join-Path $taskSteer 'receipt.json')).Hash.ToLowerInvariant()}
if($taskOwnerAdapter){$taskReceipt.adapterSnapshot=$null;$taskReceipt.ownerAdapterReceiptSha256=(Get-FileHash -LiteralPath $taskOwnerAdapter.receipt).Hash.ToLowerInvariant()}
[IO.File]::WriteAllText((Join-Path $taskStage 'receipt.json'),($taskReceipt|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
if($taskSteerOwner){
    & python -B $taskSteerVerifier --verify $taskStage
    if($LASTEXITCODE){throw 'Combined primary stage changed Steer owner admission payload'}
}
Write-Output "PRIMARY_CORE_STAGE=$taskStage"
