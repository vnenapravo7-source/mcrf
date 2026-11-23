param([Parameter(Mandatory)][string]$Snapshot,[Parameter(Mandatory)]$CurrentManifest)
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF'
function Hash([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
$origin=[IO.Path]::GetFullPath($Snapshot)
if($origin -ine [IO.Path]::GetFullPath("$root/work/android-integration-9f4ac9a5d866481d985e746dc9d9654e") -or (Get-Item $origin).LinkType){throw 'Exact installed10040 immutable acceptance required'}
$build=Get-Content "$origin/build-result.json" -Raw|ConvertFrom-Json
$evidence=Get-Content "$origin/p2-expansion-jvm-evidence.json" -Raw|ConvertFrom-Json
if($build.exitCode -ne 0 -or !$build.sourceStable -or !$evidence.passed -or $evidence.count -ne 61 -or $evidence.newExecutionCount -ne 0 -or $evidence.retained.mode -cne 'csqtt-generations-packaging-visual-only' -or (Hash "$origin/source-manifest.json") -cne $build.sourceManifestSha256 -or (Hash "$origin/p2-expansion-jvm-retained.json") -cne $evidence.retainedProofSha256){throw 'Accepted61 packaging proof changed'}
# Follow the actual immutable XML chain, including successful cases in a failed
# parent. Failures stay failures; only successful testcase identities are retained.
$script:seen=@{};$script:passed=@{};$script:reports=@()
function ReadReports([string]$at,$rows){
    foreach($row in $rows){
        $path="$at/ports/android/app/build/test-results/testDebugUnitTest/$($row.file)"
        $file=Get-Item -LiteralPath $path
        if($file.LinkType -or $file.Length -gt 33554432 -or (Hash $path) -cne $row.sha256){throw 'Accepted JVM XML changed'}
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
    if($depth -gt 16 -or [IO.Path]::GetDirectoryName($at) -ine [IO.Path]::GetFullPath("$root/work") -or (Get-Item $at).LinkType){throw 'Unexpected accepted evidence origin/depth'}
    if($script:seen[$at]){return};$script:seen[$at]=$true
    $e=Get-Content "$at/p2-expansion-jvm-evidence.json" -Raw|ConvertFrom-Json
    if(!$e.passed){throw 'Only accepted JVM evidence can link retention'}
    ReadReports $at $e.reports
    if($e.retained){
        $r=$e.retained
        if((Hash "$($r.origin)/p2-expansion-jvm-evidence.json") -cne $r.originalEvidenceSha256){throw 'Retained evidence chain changed'}
        if($r.partial){
            $p=$r.partial
            if($p.parentExitCode -ne 1 -or !$p.fixtureOnlyCorrection -or (Hash "$($p.origin)/build-result.json") -cne $p.buildResultSha256 -or (Hash "$($p.origin)/source-manifest.json") -cne $p.sourceManifestSha256){throw 'Failed parent provenance changed'}
            ReadReports $p.origin $p.reports
        }
        Visit $r.origin ($depth+1)
    }
}
Visit $origin 0
$cases=@($evidence.cases)
if($cases.Count -ne 61 -or @($cases|ForEach-Object {$_.class+'.'+$_.name}|Sort-Object -Unique).Count -ne 61){throw 'Exact61 unique retained cases required'}
foreach($case in $cases){if(!$script:passed[$case.class+'.'+$case.name]){throw 'Retained case lacks actual successful XML'}}
$current=@{};foreach($row in $CurrentManifest){$current[$row.path.Replace('\','/')]=$row.sha256}
$prefix='ports/android/app/src/main/kotlin/org/mcrf/client/'
$freshAndroid=@('AndroidDeployBrowserUi.kt','AndroidDeployStore.kt','MainActivity.kt')
$dependencies=@();$fresh=@()
foreach($row in (Get-Content "$origin/source-manifest.json" -Raw|ConvertFrom-Json)){
    $path=$row.path.Replace('\','/')
    if(!$path.StartsWith('ports/android/app/src/main/') -and !$path.StartsWith('ports/android/app/src/test/')){continue}
    if((Hash "$origin/$path") -cne $row.sha256 -or !$current[$path] -or (Hash "$root/$path") -cne $current[$path]){throw "Source binding failed: $path"}
    if($current[$path] -cne $row.sha256){
        if($path -in @($freshAndroid|ForEach-Object {$prefix+$_}) -or $path -ceq 'ports/android/app/src/test/kotlin/org/mcrf/client/AndroidDeployAddTransportTest.kt'){
            $fresh+=@{path=$path;before=$row.sha256;sha256=$current[$path]};continue
        }
        if($path -in @("${prefix}AndroidDeployBackend.kt","${prefix}AndroidDeployScripts.kt")){
            $old=[IO.File]::ReadAllText("$origin/$path")
            $before='setOf("3xui","swg")';$after='setOf("3xui","swg","openflux")'
            if([regex]::Matches($old,[regex]::Escape($before)).Count -ne 1 -or $old.Replace($before,$after) -cne [IO.File]::ReadAllText("$root/$path")){throw 'Only reviewed existing-panel OpenFlux admission may change'}
            $fresh+=@{path=$path;before=$row.sha256;sha256=$current[$path]};continue
        }
        throw "Unreviewed retained JVM dependency changed: $path"
    }
    $dependencies+=@{path=$path;sha256=$row.sha256}
}
foreach($name in @('AndroidDeployQuickConnection.kt','AndroidDeployQuickAddUi.kt','AndroidDeployPanelAccessUi.kt','AndroidDeployServerSettingsUi.kt')){
    $path=$prefix+$name
    if(!$current[$path] -or (Hash "$root/$path") -cne $current[$path]){throw 'New UX dependency missing from frozen input'}
    $fresh+=@{path=$path;sha256=$current[$path]}
}
[ordered]@{allRetained=$false;newExecution=$false;origin=$origin;cases=$cases;originalEvidenceSha256=Hash "$origin/p2-expansion-jvm-evidence.json";mode='csqtt-generations-autodeploy-ux';dependencies=$dependencies;freshDependencies=$fresh;reports=$script:reports;scope='Retain exact61 accepted protocol/import/cadence cases; execute new quick-model/navigation and affected OpenFlux provisioning case. Changed Android browser/access/credential UI requires fresh phone acceptance.'}
