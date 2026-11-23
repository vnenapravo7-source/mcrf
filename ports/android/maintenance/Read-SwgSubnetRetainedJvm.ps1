param([Parameter(Mandatory)]$CurrentManifest)
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF'
$origin="$root/work/android-integration-6e0a46c5e6b6401c9f2c51686980d438"
function Hash([string]$path){(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}
$build=Get-Content "$origin/build-result.json" -Raw|ConvertFrom-Json
$e=Get-Content "$origin/p2-expansion-jvm-evidence.json" -Raw|ConvertFrom-Json
if($build.exitCode -ne 0 -or !$build.sourceStable -or !$e.passed -or $e.count -ne 75 -or $e.newExecutionCount -ne 12 -or
   $e.retained.mode -cne 'csqtt-generations-swg25-accordion' -or
   (Hash "$origin/source-manifest.json") -cne $build.sourceManifestSha256 -or
   (Hash "$origin/p2-expansion-jvm-retained.json") -cne $e.retainedProofSha256){throw 'Exact successful75 source/evidence required'}
$base=& "$PSScriptRoot/Read-Swg25UxRetainedJvm.ps1" -CurrentManifest $CurrentManifest
$freshCases=@();$reports=@()
foreach($row in $e.reports){
    $path="$origin/ports/android/app/build/test-results/testDebugUnitTest/$($row.file)"
    if((Get-Item $path).LinkType -or (Hash $path) -cne $row.sha256){throw 'Accepted fresh XML changed'}
    $settings=[Xml.XmlReaderSettings]::new();$settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit;$settings.XmlResolver=$null
    $reader=[Xml.XmlReader]::Create($path,$settings)
    try{$xml=[Xml.XmlDocument]::new();$xml.XmlResolver=$null;$xml.Load($reader)}finally{$reader.Dispose()}
    foreach($case in $xml.SelectNodes('/testsuite/testcase')){
        if($case.SelectNodes('failure|error|skipped').Count){throw 'Nonpassing new case in accepted75'}
        $freshCases+=@{class=$case.GetAttribute('classname');name=$case.GetAttribute('name')}
    }
    $reports+=@{origin=$origin;file=$row.file;sha256=$row.sha256}
}
$cases=@($base.cases)+$freshCases
if($freshCases.Count -ne 12 -or @($cases|ForEach-Object {$_.class+'.'+$_.name}|Sort-Object -Unique).Count -ne 75 -or
   (($cases|ForEach-Object {$_.class+'.'+$_.name}|Sort-Object)-join "`n") -cne (($e.cases|ForEach-Object {$_.class+'.'+$_.name}|Sort-Object)-join "`n")){throw 'Exact63 plus12 XML chain required'}
$current=@{};foreach($row in $CurrentManifest){$current[$row.path.Replace('\','/')]=$row.sha256}
$deps=@();$changes=@()
foreach($row in (Get-Content "$origin/source-manifest.json" -Raw|ConvertFrom-Json)){
    $path=$row.path.Replace('\','/')
    if(!$path.StartsWith('ports/android/app/src/main/') -and !$path.StartsWith('ports/android/app/src/test/')){continue}
    if((Hash "$origin/$path") -cne $row.sha256 -or !$current[$path] -or (Hash "$root/$path") -cne $current[$path]){throw 'Source identity changed'}
    if($row.sha256 -cne $current[$path]){
        if($path -ceq 'ports/android/app/src/main/kotlin/org/mcrf/client/AndroidDeployScripts.kt'){
            $pattern='(?s)(internal val provisioning = """\r?\n).*?(\r?\n"""\.trimIndent\(\))'
            $old=[IO.File]::ReadAllText("$origin/$path");$new=[IO.File]::ReadAllText("$root/$path")
            if([regex]::Matches($old,$pattern).Count -ne 1 -or [regex]::Matches($new,$pattern).Count -ne 1 -or
               [regex]::Replace($old,$pattern,'$1EMBEDDED_PAYLOAD$2') -cne [regex]::Replace($new,$pattern,'$1EMBEDDED_PAYLOAD$2')){throw 'Kotlin behavior outside isolated provisioning literal changed'}
        }elseif($path -cne 'ports/android/app/src/test/AndroidDeploySwg25ProvisioningTest.py'){throw "JVM dependency changed: $path"}
        $changes+=@{path=$path;before=$row.sha256;sha256=$current[$path]}
    }else{$deps+=@{path=$path;sha256=$row.sha256}}
}
if($changes.Count -ne 2){throw 'Only embedded payload and three new Python assertions may change'}
[ordered]@{allRetained=$true;newExecution=$false;origin=$origin;cases=$cases;mode='csqtt-generations-swg-subnet-only';
 originalEvidenceSha256=Hash "$origin/p2-expansion-jvm-evidence.json";dependencies=$deps;freshDependencies=$changes;reports=$reports;
 scope='Actual75 JVM retained, Kotlin outside isolated Python literal byte-exact; execute only three new subnet/error Python boundaries and affected real server creation.'}
