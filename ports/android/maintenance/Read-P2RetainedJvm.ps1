param([Parameter(Mandatory)][string]$Snapshot,[Parameter(Mandatory)][object[]]$CurrentManifest,[int]$Depth=0,[string]$NativeStage='')
$ErrorActionPreference='Stop'
function Hash([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
$root='D:/MCRF/Projects/MCRF'
$origin=[IO.Path]::GetFullPath($Snapshot)
if($Depth -notin 0..1){throw 'Only the reviewed two-stage retained JVM chain is allowed'}
if($origin -cnotmatch '^D:\\MCRF\\Projects\\MCRF\\work\\android-integration-[a-f0-9]{32}$' -or (Get-Item -LiteralPath $origin).LinkType){throw 'Exact ordinary retained common snapshot required'}
$built=Get-Content "$origin/build-result.json" -Raw|ConvertFrom-Json
if($origin.EndsWith('android-integration-af01e6015eab4e7f856cde389b377204')){
    if($built.exitCode -ne 1 -or !$built.sourceStable -or $built.sourceManifestSha256 -cne (Hash "$origin/source-manifest.json")){throw 'Original stable followup failure required'}
    $first=Get-Content "$origin/p2-jvm-retained.json" -Raw|ConvertFrom-Json
    $base=& "$PSScriptRoot/Read-P2SuccessfulJvm.ps1" -Snapshot $first.origin -CurrentManifest $CurrentManifest -NativeStage $NativeStage
    if($base.originalJvmEvidenceSha256 -cne $first.originalJvmEvidenceSha256 -or $base.origin -cne $first.origin){throw 'Original 238-case dependency chain changed'}
    $oldBy=@{};foreach($row in (Get-Content "$origin/source-manifest.json" -Raw|ConvertFrom-Json)){$oldBy[$row.path.Replace('\','/')]=$row.sha256}
    $newBy=@{};foreach($row in $CurrentManifest){$newBy[$row.path.Replace('\','/')]=$row.sha256}
    $allowed=@('ports/android/maintenance/Read-P2RetainedJvm.ps1','ports/android/maintenance/Read-P2SuccessfulJvm.ps1')
    $allowed+=@('BsPlanTest','CarrierChallengeFlightsTest','RetainedTunTransitionTest')|ForEach-Object {'ports/android/app/src/test/kotlin/org/mcrf/client/'+$_+'.kt'}
    foreach($name in @($oldBy.Keys+$newBy.Keys|Sort-Object -Unique)){if($name -cnotin $allowed -and (!$oldBy.ContainsKey($name) -or !$newBy.ContainsKey($name) -or $oldBy[$name] -cne $newBy[$name])){throw "Unexpected followup input delta: $name"}}
    $expected=@('org.mcrf.client.BsPlanTest.exactPriorityAndNoFasterProtocolReplacement','org.mcrf.client.CarrierChallengeFlightsTest.changingWaiterIdOrFallbackModeCannotReplayTheSameProviderSession','org.mcrf.client.RetainedTunTransitionTest.carrierAndOptionalWorkersPrepareBeforeStartAndOrdinaryCaptureIsPreserved')
    $passed=@();$failed=@();$reports=@();$seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach($file in Get-ChildItem "$origin/ports/android/app/build/test-results/testDebugUnitTest" -File -Filter 'TEST-*.xml'){
        if($file.LinkType -or $file.Length -gt 33554432){throw 'Ordinary bounded XML required'}
        $settings=[Xml.XmlReaderSettings]::new();$settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit;$settings.XmlResolver=$null
        $reader=[Xml.XmlReader]::Create($file.FullName,$settings)
        try{$xml=[Xml.XmlDocument]::new();$xml.XmlResolver=$null;$xml.Load($reader)}finally{$reader.Dispose()}
        foreach($case in $xml.SelectNodes('/testsuite/testcase')){
            $class=$case.GetAttribute('classname');$name=$case.GetAttribute('name');$id=$class+'.'+$name
            if(!$seen.Add($id) -or $case.SelectNodes('skipped').Count -or $class -cnotin $first.rerunClasses){throw 'Unexpected followup case'}
            if($case.SelectNodes('failure|error').Count){$failed+=$id}else{$passed+=@{class=$class;name=$name}}
        }
        $reports+=@{file=$file.FullName;sha256=Hash $file.FullName}
    }
    if($seen.Count -ne 90 -or $passed.Count -ne 87 -or (($failed|Sort-Object)-join ',') -cne (($expected|Sort-Object)-join ',')){throw 'Exact original 87PASS/3FAIL batch required'}
    [ordered]@{priority=2;sourceReadySha256=$built.p2ReadinessSha256;origin=$origin;originalResultSha256=Hash "$origin/build-result.json";originalManifestSha256=$built.sourceManifestSha256;producerSha256=Hash $PSCommandPath;rerunClasses=$expected;cases=@($base.cases)+$passed;reports=@($base.reports)+$reports;newExecution=$false;allRetained=$false}
    return
}
if($built.exitCode -eq 0){& "$PSScriptRoot/Read-P2SuccessfulJvm.ps1" -Snapshot $origin -CurrentManifest $CurrentManifest -NativeStage $NativeStage;return}
if($built.exitCode -ne 1 -or !$built.sourceStable -or $built.sourceManifestSha256 -cne (Hash "$origin/source-manifest.json")){throw 'Original stable common failure evidence required'}
$prefix='ports/android/app/src/'
$allowed=@('ports/android/integration-build.ps1','ports/android/maintenance/Read-P2RetainedJvm.ps1',
    ($prefix+'main/kotlin/org/mcrf/client/CarrierIpcWriter.kt'),($prefix+'main/kotlin/org/mcrf/client/ServerBootstrapPolicy.kt'),
    ($prefix+'test/kotlin/org/mcrf/client/CarrierIpcWriterTest.kt'),($prefix+'test/kotlin/org/mcrf/client/ClientCarrierRetirementTest.kt'),'ports/android/app/build.gradle.kts')
$prior=$null
if(Test-Path -LiteralPath "$origin/p2-jvm-retained.json"){
    $prior=Get-Content "$origin/p2-jvm-retained.json" -Raw|ConvertFrom-Json
    $base=& $PSCommandPath -Snapshot $prior.origin -CurrentManifest $CurrentManifest -Depth ($Depth+1)
    if($base.originalResultSha256 -cne $prior.originalResultSha256 -or $base.originalManifestSha256 -cne $prior.originalManifestSha256 -or
        ((@($base.cases|ForEach-Object {$_.class+'.'+$_.name})|Sort-Object)-join ',') -cne ((@($prior.cases|ForEach-Object {$_.class+'.'+$_.name})|Sort-Object)-join ',')){throw 'Retained first-stage passes differ from original reports'}
    $allowed=@('ports/android/integration-build.ps1','ports/android/maintenance/Read-P2RetainedJvm.ps1','ports/android/app/build.gradle.kts',($prefix+'test/kotlin/org/mcrf/client/ClientCarrierRetirementTest.kt'))
}
$old=@(Get-Content "$origin/source-manifest.json" -Raw|ConvertFrom-Json)
$oldBy=@{};foreach($row in $old){$oldBy[$row.path.Replace('\','/')]=$row}
$newBy=@{};foreach($row in $CurrentManifest){$newBy[$row.path.Replace('\','/')]=$row}
foreach($name in @($oldBy.Keys+$newBy.Keys|Sort-Object -Unique)){
    if($name -cnotin $allowed -and (!$oldBy.ContainsKey($name) -or !$newBy.ContainsKey($name) -or $oldBy[$name].sha256 -cne $newBy[$name].sha256)){throw "Unreviewed source/dependency change prevents JVM reuse: $name"}
}
$rerun=@('CarrierIpcProcessTest','CarrierIpcWriterTest','CarrierRetirementBackpressureTest','ClientCarrierRetirementTest','ServerBootstrapPolicyTest')|ForEach-Object {'org.mcrf.client.'+$_}
$selection=@(Get-Content "$origin/ports/android/coordination/p2-jvm-affected-2026-10-20.txt")
$cases=@();$allPassed=@();$failed=@();$reports=@();$seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach($file in Get-ChildItem "$origin/ports/android/app/build/test-results/testDebugUnitTest" -Filter 'TEST-*.xml' -File){
    if($file.LinkType -or $file.Length -gt 33554432){throw 'Ordinary bounded retained XML required'}
    $options=[Xml.XmlReaderSettings]::new();$options.DtdProcessing=[Xml.DtdProcessing]::Prohibit;$options.XmlResolver=$null
    $reader=[Xml.XmlReader]::Create($file.FullName,$options)
    try{$xml=[Xml.XmlDocument]::new();$xml.XmlResolver=$null;$xml.Load($reader)}finally{$reader.Dispose()}
    foreach($case in $xml.SelectNodes('/testsuite/testcase')){
        $class=$case.GetAttribute('classname');$name=$case.GetAttribute('name');$id=$class+'.'+$name
        if(!$seen.Add($id) -or $case.SelectNodes('skipped').Count -or !@($selection|Where-Object {$_ -ceq $class -or $_ -ceq $id}).Count){throw 'Unexpected/duplicate/skipped retained case'}
        if($case.SelectNodes('failure|error').Count){$failed+=$id;if($class -cnotin $rerun){throw 'Failed case cannot be retained'}}
        else{$allPassed+=@{class=$class;name=$name};if($class -cnotin $rerun){$cases+=@{class=$class;name=$name}}}
    }
    $reports+=@{file=$file.FullName;sha256=Hash $file.FullName}
}
$expected=@('org.mcrf.client.ClientCarrierRetirementTest.alreadyDeadAndRepeatedCloseDoNotWriteOrKill','org.mcrf.client.ServerBootstrapPolicyTest.nativeBootstrapPreservesChosenProtocol')
if($prior){
    $expected=@('org.mcrf.client.ClientCarrierRetirementTest.normalExitZeroAndOneKeepGraceAndNeverForce')
    if($seen.Count -ne 33 -or (($failed|Sort-Object)-join ',') -cne ($expected -join ',')){throw 'Exact related-case result required'}
    $cases=@($base.cases)+$allPassed;$reports=@($base.reports)+$reports;$rerun=$expected
}elseif($seen.Count -ne 237 -or (($failed|Sort-Object)-join ',') -cne (($expected|Sort-Object)-join ',')){throw 'Exact first common result required; never invent retained passes'}
[ordered]@{priority=2;sourceReadySha256=$built.p2ReadinessSha256;origin=$origin;originalResultSha256=Hash "$origin/build-result.json";originalManifestSha256=$built.sourceManifestSha256;
    producerSha256=Hash $PSCommandPath;rerunClasses=$rerun;cases=$cases;reports=$reports;newExecution=$false}
