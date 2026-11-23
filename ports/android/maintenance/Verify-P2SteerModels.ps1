#requires -Version 7.0
param()
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation test
if($args.Count){throw 'No alternate model scope'}
$taskRoot='D:/MCRF/Projects/MCRF'
$taskProject="$taskRoot/work/steer-android-port/android-vpn"
$taskCurrentPath="$taskRoot/ports/android/coordination/current.json"
$taskGate="$taskRoot/ports/android/coordination/p2-progress-2026-10-20.json"
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
$taskGateSha=Hash $taskGate
$taskCurrent=Get-Content -LiteralPath $taskCurrentPath -Raw|ConvertFrom-Json
if($taskCurrent.activeBatch -cne 'p2-progress-2026-10-20.json' -or $taskCurrent.jobs.build -or $taskCurrent.jobs.hostTest -or $taskCurrent.jobs.device -or $taskCurrent.jobs.emulator){throw 'Exact idle whole-P2 batch required'}
$taskOldPath='D:/MCRF/Builds/Android/steer-ipv6-host-b448a2c1af4346408f66c232d602aeb1/receipt.json'
if((Hash $taskOldPath) -cne 'd81e86346b566a802ba9a3e8ee51251436a81fc5e4deb3de7b901a71c35c0ae4'){throw 'Retained completed model evidence changed'}
$taskOld=Get-Content -LiteralPath $taskOldPath -Raw|ConvertFrom-Json
if(!$taskOld.passed -or !$taskOld.sourceStable){throw 'Retained model evidence is not PASS'}
$taskChanged=@('core/src/main/java/org/steer/core/ProxyHandshake.java','tests/HostProxyHandshake.java')
$taskInputs=[ordered]@{}
function InputPath([string]$Relative){
    if([IO.Path]::IsPathRooted($Relative) -or '..' -in ($Relative -split '[/\\]')){throw 'Unsafe retained model input'}
    if($Relative.StartsWith('host-presets/')){return Join-Path "$taskRoot/ports/android/app/src/main/assets/presets" $Relative.Substring(13)}
    return Join-Path $taskProject $Relative
}
foreach($row in $taskOld.inputs.PSObject.Properties){
    $relative=$row.Name.Replace('\','/');$actual=Hash (InputPath $relative)
    if($relative -cnotin $taskChanged -and $actual -cne $row.Value){throw 'Another model dependency changed; accumulate it in the whole common scope before execution'}
    $taskInputs[$relative]=$actual
}
foreach($name in $taskChanged){if(!$taskInputs.Contains($name)){throw 'Affected model not in retained closure'}}
$taskJava='C:/Program Files/Java/jdk-17/bin'
if((Hash "$taskJava/javac.exe") -cne $taskOld.compilerSha256 -or (Hash "$taskJava/java.exe") -cne $taskOld.javaSha256){throw 'Retained Java toolchain changed'}
$taskJson="$taskRoot/work/toolchains/gradle-home/caches/modules-2/files-2.1/org.json/json/20250517/d67181bbd819ccceb929b580a4e2fcb0c8b17cd8/json-20250517.jar"
if((Hash $taskJson) -cne $taskOld.testDependencySha256){throw 'Retained model JSON dependency changed'}
$taskStage='D:/MCRF/Builds/Android/p2-retained-models-'+[guid]::NewGuid().ToString('N')
New-Item -ItemType Directory -Path "$taskStage/source","$taskStage/classes"|Out-Null
$taskState=[ordered]@{priority=2;passed=$false;sourceStable=$false;sourceReadySha256=$taskGateSha;retainedReceipt=$taskOldPath;retainedReceiptSha256=(Hash $taskOldPath);retainedUnchanged=$true;executedSuites=@();inputs=$taskInputs;producerSha256=(Hash $PSCommandPath);scope='Whole-P2 common phase: unchanged old models retained, only changed ProxyHandshake model executed; not native/Android acceptance'}
$taskOwner="P2_MODELS:$taskStage"
function SaveCurrent($value){[IO.File]::WriteAllText($taskCurrentPath,($value|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))}
$taskCurrent.jobs.hostTest=$taskOwner;SaveCurrent $taskCurrent
try{
    # Compile the complete old pure-Java dependency closure (not its old
    # tests). ProxyHandshake also uses DomainPolicy/HttpForwardBody and their
    # transitive types; never rely on unpinned old .class cache contents.
    $files=@($taskInputs.Keys|Where-Object { $_.EndsWith('.java') -and !$_.StartsWith('tests/') })+@('tests/HostProxyHandshake.java')
    $copies=@();foreach($relative in $files){$target=Join-Path "$taskStage/source" ([IO.Path]::GetFileName($relative));[IO.File]::Copy((InputPath $relative),$target,$false);if((Hash $target) -cne $taskInputs[$relative]){throw 'Frozen model input changed'};$copies+=$target}
    & "$taskJava/javac.exe" --release 8 -encoding UTF-8 -cp $taskJson -d "$taskStage/classes" @copies *> "$taskStage/compile.log"
    if($LASTEXITCODE){throw 'Common affected model compilation failed; retain log'}
    & "$taskJava/java.exe" -cp "$taskStage/classes;$taskJson" HostProxyHandshake *> "$taskStage/HostProxyHandshake.log"
    if($LASTEXITCODE -or [IO.File]::ReadAllText("$taskStage/HostProxyHandshake.log") -cnotmatch '^PASS authenticated proxy CONNECT'){throw 'Affected model failed; retain log'}
    foreach($row in $taskInputs.GetEnumerator()){if((Hash (InputPath $row.Key)) -cne $row.Value){throw 'Model closure changed during common phase'}}
    if((Hash $taskGate) -cne $taskGateSha){throw 'Whole-P2 readiness changed'}
    $taskState.executedSuites=@('HostProxyHandshake');$taskState.executedLogSha256=Hash "$taskStage/HostProxyHandshake.log"
    $taskState.passed=$true;$taskState.sourceStable=$true
}finally{
    [IO.File]::WriteAllText("$taskStage/receipt.json",($taskState|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
    $current=Get-Content -LiteralPath $taskCurrentPath -Raw|ConvertFrom-Json
    if($current.jobs.hostTest -ceq $taskOwner){$current.jobs.hostTest=$null;SaveCurrent $current}
    Write-Output "P2_MODEL_RECEIPT=$taskStage/receipt.json"
}
