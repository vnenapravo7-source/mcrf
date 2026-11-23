param([Parameter(Mandatory)][string]$Snapshot,[Parameter(Mandatory)][object[]]$CurrentManifest,[Parameter(Mandatory)][string]$NativeStage)
$ErrorActionPreference='Stop'
function Hash([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Json([string]$p){Get-Content -LiteralPath $p -Raw|ConvertFrom-Json}
$origin=[IO.Path]::GetFullPath($Snapshot)
if($origin -cnotmatch '^D:\\MCRF\\Projects\\MCRF\\work\\android-integration-[a-f0-9]{32}$' -or (Get-Item $origin).LinkType){throw 'Ordinary successful common snapshot required'}
$built=Json "$origin/build-result.json";$common=Json "$origin/p2-candidate-artifacts.json";$evidence=Json "$origin/p2-jvm-evidence.json"
$networkFollowup=$origin.EndsWith('android-integration-b5bb4db42b5b42f9b9a6efc7976d67a3')
$expectedCount=if($networkFollowup){328}else{238}
if($built.exitCode -ne 0 -or !$built.sourceStable -or (Hash "$origin/source-manifest.json") -cne $built.sourceManifestSha256 -or $common.jvmEvidenceSha256 -cne (Hash "$origin/p2-jvm-evidence.json") -or !$evidence.passed -or $evidence.count -ne $expectedCount){throw 'Exact completed source-bound common proof required'}
$old=@(Json "$origin/source-manifest.json")
$oldPrefix=@($old.path|ForEach-Object {$_.Replace('\','/')}|Where-Object {$_ -cmatch '^work/p2-primary-p2-steer-candidate-[a-f0-9]{32}/receipt.json$'})
if($oldPrefix.Count -ne 1){throw 'Unique original native source required'}
$oldStage=$oldPrefix[0] -replace '/receipt.json$',''
$oldProducerPath='D:/MCRF/Builds/Android/'+($oldStage -replace '^work/p2-primary-','')+'/producer-receipt.json'
$newProducerPath=Join-Path ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($NativeStage))) 'producer-receipt.json'
$oldProducer=Json $oldProducerPath;$newProducer=Json $newProducerPath
if((Hash $oldProducerPath) -cne $common.steerOrigin.producerReceiptSha256 -or !$newProducer.sourceStable -or $newProducer.state -cne 'compiled_candidate_not_runtime_accepted' -or $oldProducer.modelEvidenceSha256 -cne $newProducer.modelEvidenceSha256 -or $oldProducer.readinessSha256 -cne $newProducer.readinessSha256){throw 'Source-bound native continuation and unchanged model/priority proof required'}
$nativeChanges=@('work/steer-android-port/src/proto/tls/tls49.c','work/steer-android-port/android-vpn/core/src/main/cpp/CMakeLists.txt','work/steer-android-port/android-vpn/tests/p2_probe_native_android.py')
foreach($name in @($oldProducer.inputs.PSObject.Properties.Name+$newProducer.inputs.PSObject.Properties.Name|Sort-Object -Unique)){
    if($name -cnotin $nativeChanges -and ($null -eq $oldProducer.inputs.$name -or $null -eq $newProducer.inputs.$name -or $oldProducer.inputs.$name.sha256 -cne $newProducer.inputs.$name.sha256)){throw "Native continuation changed a JVM/compiler input: $name"}
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Bytecode([string]$Path){
    $outer=[IO.Compression.ZipFile]::OpenRead($Path);$jar=$outer;$memory=$null
    try{
        if($Path.EndsWith('.aar')){$entry=$outer.GetEntry('classes.jar');if(!$entry){throw 'AAR bytecode missing'};$memory=[IO.MemoryStream]::new();$stream=$entry.Open();try{$stream.CopyTo($memory)}finally{$stream.Dispose()};$memory.Position=0;$jar=[IO.Compression.ZipArchive]::new($memory,[IO.Compression.ZipArchiveMode]::Read,$true)}
        $result=@{};foreach($entry in $jar.Entries|Where-Object FullName -CLike '*.class'){
            if($entry.Length -gt 16777216 -or $result.ContainsKey($entry.FullName)){throw 'Unbounded/duplicate bytecode'}
            $stream=$entry.Open();try{$result[$entry.FullName]=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream))}finally{$stream.Dispose()}
        };if(!$result.Count){throw 'Empty native Java API'};return $result
    }finally{if($jar -ne $outer){$jar.Dispose()};$outer.Dispose();if($memory){$memory.Dispose()}}
}
foreach($file in @('spi.jar','steer/adapter.aar','steer/runtime.aar')){
    $a=Bytecode "$origin/$oldStage/$file";$b=Bytecode (Join-Path $NativeStage $file)
    if($a.Count -ne $b.Count -or @($a.Keys|Where-Object {$a[$_] -cne $b[$_]}).Count){throw "Native continuation changed Java bytecode: $file"}
}
function Logical([string]$path){$path.Replace('\','/') -replace '^work/p2-primary-p2-steer-candidate-[a-f0-9]{32}/','native-steer/'}
$oldBy=@{};foreach($row in $old){$oldBy[(Logical $row.path)]=$row}
$newBy=@{};foreach($row in $CurrentManifest){$newBy[(Logical $row.path)]=$row}
$controls=@('ports/android/integration-build.ps1','ports/android/app/build.gradle.kts','ports/android/maintenance/Read-P2RetainedJvm.ps1','ports/android/maintenance/Read-P2SuccessfulJvm.ps1','ports/android/coordination/current.json')
$appDelta=@('CarrierChallenge.kt','CarrierChallengeFlights.kt','NativeCarrier.kt','WarpScanner.kt','WarpProgressPhase.kt')|ForEach-Object {'ports/android/app/src/main/kotlin/org/mcrf/client/'+$_}
$appDelta+=@('CarrierChallengeFlightsTest.kt','WarpProgressPhaseTest.kt','BsPlanTest.kt','RetainedTunTransitionTest.kt')|ForEach-Object {'ports/android/app/src/test/kotlin/org/mcrf/client/'+$_}
$appDelta+='ports/android/coordination/p2-jvm-affected-2026-10-20.txt'
if($networkFollowup){$appDelta=@('ports/android/app/src/main/kotlin/org/mcrf/client/McrfVpnService.kt','ports/android/app/src/test/kotlin/org/mcrf/client/SteerNetworkRecoveryTest.kt','ports/android/coordination/p2-jvm-affected-2026-10-20.txt')}
$changed=@()
foreach($name in @($oldBy.Keys+$newBy.Keys|Sort-Object -Unique)){
    if($name.StartsWith('native-steer/') -or $name -cin $controls){continue}
    if(!$oldBy.ContainsKey($name) -or !$newBy.ContainsKey($name) -or $oldBy[$name].sha256 -cne $newBy[$name].sha256){
        if($name -cnotin $appDelta -or !$newBy.ContainsKey($name)){throw "Unreviewed app/test/dependency change invalidates JVM retention: $name"}
        $changed+=@{path=$name;sha256=$newBy[$name].sha256}
    }
}
$rerun=@()
if($changed.Count){
    # All direct test consumers of the changed challenge/native bridge, plus
    # the new scanner parser. Every other source/dependency must match above.
    $rerun=@('BsPlanTest','BsStartupPolicyTest','CarrierCaptchaAutoPageTest','CarrierCaptchaExpiryTest','CarrierChallengeAdmissionTest','CarrierChallengeAsyncTest','CarrierChallengeBrowserOwnerTest','CarrierChallengeDeliveryTest','CarrierChallengeFlightsTest','CarrierChallengeTimerOwnershipTest','CarrierCsqttWaiterTest','CarrierManualReplyTest','CarrierQwdttWaiterTest','CompatibilityImportTest','RetainedTunTransitionTest','VkApiChallengeTest','WarpProgressPhaseTest')|ForEach-Object {'org.mcrf.client.'+$_}
    if($networkFollowup){$rerun=@('org.mcrf.client.SteerNetworkRecoveryTest')}
    $selected=@(Get-Content "$origin/ports/android/coordination/p2-jvm-affected-2026-10-20.txt")
    $currentSelected=@(Get-Content 'D:/MCRF/Projects/MCRF/ports/android/coordination/p2-jvm-affected-2026-10-20.txt')
    if(@($selected|Where-Object {$_ -cnotin $currentSelected}).Count -or @($rerun|Where-Object {$_ -cnotin $currentSelected}).Count){throw 'Original selected cases and every reviewed affected consumer must remain selected'}
}
$positive=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$reports=@($evidence.reports|ForEach-Object {@{file="$origin/ports/android/app/build/test-results/testDebugUnitTest/$($_.file)";sha256=$_.sha256}})+@($evidence.retained.reports)
foreach($report in $reports){
    if((Get-Item $report.file).LinkType -or (Hash $report.file) -cne $report.sha256){throw 'Retained actual XML changed'}
    $settings=[Xml.XmlReaderSettings]::new();$settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit;$settings.XmlResolver=$null;$reader=[Xml.XmlReader]::Create($report.file,$settings)
    try{$xml=[Xml.XmlDocument]::new();$xml.XmlResolver=$null;$xml.Load($reader)}finally{$reader.Dispose()}
    foreach($case in $xml.SelectNodes('/testsuite/testcase')){if(!$case.SelectNodes('failure|error|skipped').Count){[void]$positive.Add($case.GetAttribute('classname')+'.'+$case.GetAttribute('name'))}}
}
$seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach($case in $evidence.cases){$id=$case.class+'.'+$case.name;if(!$seen.Add($id) -or !$positive.Contains($id)){throw 'A retained case lacks an actual positive result'}}
if($seen.Count -ne $expectedCount){throw 'Every retained unique JVM case requires its actual positive source-bound result'}
$retained=@($evidence.cases|Where-Object {$_.class -cnotin $rerun})
[ordered]@{priority=2;sourceReadySha256=$common.readinessSha256;origin=$origin;originalResultSha256=Hash "$origin/build-result.json";originalManifestSha256=$built.sourceManifestSha256;originalJvmEvidenceSha256=Hash "$origin/p2-jvm-evidence.json";producerSha256=Hash $PSCommandPath;rerunClasses=$rerun;cases=$retained;reports=$reports;newExecution=$false;allRetained=($rerun.Count -eq 0);nativeProducerSha256=Hash $newProducerPath;javaBytecodeUnchanged=$true;reviewedAppDelta=$(if($rerun.Count){'warp-progress-carrier-session'}else{''});changedSources=$changed}
