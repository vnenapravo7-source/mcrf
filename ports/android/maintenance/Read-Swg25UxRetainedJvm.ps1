param([Parameter(Mandatory)]$CurrentManifest)
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF'
$origin="$root/work/android-integration-cd6ab3cd83e14ad6b4d448d2f998c49d"
function Hash([string]$path){(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}
$build=Get-Content "$origin/build-result.json" -Raw|ConvertFrom-Json
$e=Get-Content "$origin/p2-expansion-jvm-evidence.json" -Raw|ConvertFrom-Json
if($build.exitCode -ne 0 -or !$build.sourceStable -or !$e.passed -or $e.count -ne 68 -or $e.newExecutionCount -ne 7 -or
   (Hash "$origin/source-manifest.json") -cne $build.sourceManifestSha256 -or
   (Hash "$origin/p2-expansion-jvm-retained.json") -cne $e.retainedProofSha256){throw 'Exact accepted10041/68 proof required'}
$script:seen=@{};$script:passed=@{};$script:reports=@()
function Reports([string]$at,$rows){
    foreach($row in $rows){
        $path="$at/ports/android/app/build/test-results/testDebugUnitTest/$($row.file)"
        $f=Get-Item -LiteralPath $path
        if($f.LinkType -or $f.Length -gt 33554432 -or (Hash $path) -cne $row.sha256){throw 'Accepted XML identity changed'}
        $settings=[Xml.XmlReaderSettings]::new();$settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit;$settings.XmlResolver=$null
        $reader=[Xml.XmlReader]::Create($path,$settings)
        try{$xml=[Xml.XmlDocument]::new();$xml.XmlResolver=$null;$xml.Load($reader)}finally{$reader.Dispose()}
        foreach($case in $xml.SelectNodes('/testsuite/testcase')){
            if(!$case.SelectNodes('failure|error|skipped').Count){$script:passed[$case.GetAttribute('classname')+'.'+$case.GetAttribute('name')]=$true}
        }
        $script:reports+=@{origin=$at;file=$row.file;sha256=$row.sha256}
    }
}
function Visit([string]$at,[int]$depth){
    $at=[IO.Path]::GetFullPath($at)
    if($depth -gt 16 -or [IO.Path]::GetDirectoryName($at) -ine [IO.Path]::GetFullPath("$root/work") -or (Get-Item $at).LinkType){throw 'Unexpected evidence origin'}
    if($script:seen[$at]){return};$script:seen[$at]=$true
    $proof=Get-Content "$at/p2-expansion-jvm-evidence.json" -Raw|ConvertFrom-Json
    if(!$proof.passed){throw 'Unaccepted evidence in retained chain'}
    Reports $at $proof.reports
    if($proof.retained){
        $r=$proof.retained
        if((Hash "$($r.origin)/p2-expansion-jvm-evidence.json") -cne $r.originalEvidenceSha256){throw 'Retained evidence chain changed'}
        if($r.partial){
            $p=$r.partial
            if($p.parentExitCode -ne 1 -or !$p.fixtureOnlyCorrection -or (Hash "$($p.origin)/build-result.json") -cne $p.buildResultSha256 -or (Hash "$($p.origin)/source-manifest.json") -cne $p.sourceManifestSha256){throw 'Failed parent provenance changed'}
            Reports $p.origin $p.reports
        }
        Visit $r.origin ($depth+1)
    }
}
Visit $origin 0
$affected=@(
 'org.mcrf.client.AndroidDeployQuickConnectionTest.quickChoicesCoverEveryExistingPanelWithoutInventingCsqttGeneration',
 'org.mcrf.client.AndroidDeployQuickConnectionTest.unknownProtocolRejectedBeforeAnyProvisioning',
 'org.mcrf.client.AndroidDeploySwgTest.legacyLongCallDraftOpensAndPreservesAllWdttCalls',
 'org.mcrf.client.AndroidDeploySwgTest.expandedCallListsHaveNoProtocolCountCapAndKeepPayloadGuard',
 'org.mcrf.client.VkRelayTest.deploymentCallPoolsRespectActualAndroidImportLimits'
)
$cases=@($e.cases|Where-Object {$_.class+'.'+$_.name -cnotin $affected})
if($cases.Count -ne 63){throw 'Exact63 unaffected cases required'}
foreach($case in $cases){if(!$script:passed[$case.class+'.'+$case.name]){throw 'Retained case lacks actual successful XML'}}
$allowedMain=@('AndroidDeployBackend.kt','AndroidDeployBackupSettings.kt','AndroidDeployBrowserUi.kt','AndroidDeployPlan.kt',
 'AndroidDeployQuickConnection.kt','AndroidDeployQuickAddUi.kt','AndroidDeployServerSettingsUi.kt','AndroidDeployScripts.kt',
 'AndroidDeployStore.kt','AndroidDeploySwg.kt','AndroidDeployUi.kt')
$allowedTests=@('AndroidDeployQuickConnectionTest.kt','AndroidDeploySwgTest.kt')
$current=@{};foreach($row in $CurrentManifest){$current[$row.path.Replace('\','/')]=$row.sha256}
$dependencies=@();$fresh=@();$known=@{}
foreach($row in (Get-Content "$origin/source-manifest.json" -Raw|ConvertFrom-Json)){
    $path=$row.path.Replace('\','/')
    if(!$path.StartsWith('ports/android/app/src/main/') -and !$path.StartsWith('ports/android/app/src/test/')){continue}
    $known[$path]=$true
    if((Hash "$origin/$path") -cne $row.sha256 -or !$current[$path] -or (Hash "$root/$path") -cne $current[$path]){throw "Source binding failed: $path"}
    if($current[$path] -cne $row.sha256){
        $name=[IO.Path]::GetFileName($path)
        if(($path.StartsWith('ports/android/app/src/main/kotlin/org/mcrf/client/') -and $name -cin $allowedMain) -or
           ($path.StartsWith('ports/android/app/src/test/kotlin/org/mcrf/client/') -and $name -cin $allowedTests)){
            $fresh+=@{path=$path;before=$row.sha256;sha256=$current[$path]};continue
        }
        throw "Unreviewed source delta outside new SWG/accordion scope: $path"
    }
    $dependencies+=@{path=$path;sha256=$row.sha256}
}
foreach($path in $current.Keys){
    if((!$path.StartsWith('ports/android/app/src/main/') -and !$path.StartsWith('ports/android/app/src/test/')) -or $known[$path]){continue}
    if($path -cnotin @('ports/android/app/src/test/kotlin/org/mcrf/client/AndroidDeploySwg25Test.kt','ports/android/app/src/test/AndroidDeploySwg25ProvisioningTest.py')){throw "Unreviewed added app source: $path"}
    if((Hash "$root/$path") -cne $current[$path]){throw 'New source binding failed'}
    $fresh+=@{path=$path;sha256=$current[$path]}
}
[ordered]@{allRetained=$false;newExecution=$false;origin=$origin;cases=$cases;mode='csqtt-generations-swg25-accordion';
 originalEvidenceSha256=Hash "$origin/p2-expansion-jvm-evidence.json";dependencies=$dependencies;freshDependencies=$fresh;reports=$script:reports;
 affectedCases=$affected;scope='63 actual unaffected cases retained. Twelve affected/new SWG model cases, four new provisioning checks, fresh Android accordion/shared SSH and real historical/modern traffic required.'}
