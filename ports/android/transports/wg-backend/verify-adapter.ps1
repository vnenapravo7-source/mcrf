param([Parameter(Mandatory=$true)][string]$Stage)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot '../../maintenance/Assert-HumanBatchReady.ps1') -Operation test
$taskRoot=[IO.Path]::GetFullPath($PSScriptRoot)
$taskStage=[IO.Path]::GetFullPath((Join-Path $taskRoot $Stage))
if(!$taskStage.StartsWith((Join-Path $taskRoot 'build')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'WG adapter stage outside generated build directory'}
& (Join-Path $taskRoot 'verify-android.ps1') -Stage ([IO.Path]::GetRelativePath($taskRoot,$taskStage))
if($LASTEXITCODE -ne 0){throw 'Native candidate verification failed'}
$taskJava='C:/Program Files/Java/jdk-17/bin'
$taskSpi=[IO.Path]::GetFullPath((Join-Path $taskRoot '../../primary-spi/src/main/java'))
$taskInputs=@(
    (Join-Path $taskSpi 'org/mcrf/engine/protocol/v1/PacketBackend.java'),
    (Join-Path $taskSpi 'org/mcrf/engine/protocol/v1/OwnedPacketBackend.java'),
    (Join-Path $taskSpi 'org/mcrf/engine/protocol/dns/v1/ManagedDnsPacketBackend.java'),
    (Join-Path $taskSpi 'org/mcrf/engine/protocol/pipe/v1/PacketPipeBackend.java'),
    (Join-Path $taskRoot 'android/src/org/mcrf/engines/wg/WgPacketBackend.java')
)
$taskOutput=Join-Path $taskStage ('adapter-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskOutput | Out-Null
$taskClasses=Join-Path $taskOutput 'classes'
New-Item -ItemType Directory -Path $taskClasses | Out-Null
$taskFrozen=@(); $taskHashes=@()
foreach($taskInput in $taskInputs){
    $taskHash=(Get-FileHash -LiteralPath $taskInput -Algorithm SHA256).Hash.ToLowerInvariant()
    $taskCopy=Join-Path $taskOutput ([IO.Path]::GetFileName($taskInput))
    Copy-Item -LiteralPath $taskInput -Destination $taskCopy
    if((Get-FileHash -LiteralPath $taskCopy -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskHash){throw 'Source changed while freezing adapter'}
    $taskFrozen+=$taskCopy
    $taskHashes+=@{file=[IO.Path]::GetFileName($taskInput);sha256=$taskHash}
}
$taskJar=Join-Path $taskStage 'verified/classes.jar'
if(!(Test-Path -LiteralPath $taskJar)){throw 'Verified generated Java binding missing'}
& (Join-Path $taskJava 'javac.exe') --release 8 -cp "$taskJar;C:/Users/dieonfire/AppData/Local/Android/Sdk/platforms/android-34/android.jar" -d $taskClasses @taskFrozen
if($LASTEXITCODE -ne 0){throw 'WG Java adapter compile failed'}
$taskApi=& (Join-Path $taskJava 'javap.exe') -classpath $taskClasses org.mcrf.engines.wg.WgPacketBackend
if($LASTEXITCODE -ne 0 -or ($taskApi -join "`n") -notmatch 'implements org.mcrf.engine.protocol.v1.PacketBackend'){throw 'Optional packet backend entry missing'}
if(($taskApi -join "`n") -notmatch 'org.mcrf.engine.protocol.dns.v1.ManagedDnsPacketBackend' -or ($taskApi -join "`n") -notmatch 'startManaged\(' -or ($taskApi -join "`n") -notmatch 'dnsClient\('){throw 'Managed DNS optional adapter entry missing'}
if(($taskApi -join "`n") -notmatch 'org.mcrf.engine.protocol.pipe.v1.PacketPipeBackend' -or ($taskApi -join "`n") -notmatch 'startPacketPipe\('){throw 'Internal packet pipe optional entry missing; this is not runtime acceptance'}
$taskReceipt=@{sourceHashes=$taskHashes;generatedBindingSha256=(Get-FileHash -LiteralPath $taskJar).Hash.ToLowerInvariant();javaRelease=8;adapterCompiled=$true;androidRuntimeTested=$false;packaged=$false;published=$false}
[IO.File]::WriteAllText((Join-Path $taskOutput 'receipt.json'),($taskReceipt|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
Write-Output "WG_ADAPTER_VERIFIED=$taskOutput"
