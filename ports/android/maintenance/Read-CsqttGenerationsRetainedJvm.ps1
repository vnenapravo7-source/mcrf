param([Parameter(Mandatory)][string]$Snapshot,[Parameter(Mandatory)]$CurrentManifest)
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF'
function Hash([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
$origin=[IO.Path]::GetFullPath($Snapshot)
if((Get-Content "$root/ports/android/coordination/p2-expansion-2026-10-21.json" -Raw|ConvertFrom-Json).phase -ceq 'CSQTT_GENERATIONS_SWG_SUBNET_WHOLE_SOURCE_READY'){
    if($origin -ine [IO.Path]::GetFullPath("$root/work/android-integration-6e0a46c5e6b6401c9f2c51686980d438")){throw 'Subnet repair requires exact successful75 origin'}
    return & "$PSScriptRoot/Read-SwgSubnetRetainedJvm.ps1" -CurrentManifest $CurrentManifest
}
if((Get-Content "$root/ports/android/coordination/p2-expansion-2026-10-21.json" -Raw|ConvertFrom-Json).phase -ceq 'CSQTT_GENERATIONS_SWG25_ACCORDION_WHOLE_SOURCE_READY'){
    if($origin -ine [IO.Path]::GetFullPath("$root/work/android-integration-cd6ab3cd83e14ad6b4d448d2f998c49d")){throw 'SWG25 scope requires installed10041 origin'}
    return & "$PSScriptRoot/Read-Swg25UxRetainedJvm.ps1" -CurrentManifest $CurrentManifest
}
if($origin -ieq [IO.Path]::GetFullPath("$root/work/android-integration-cd6ab3cd83e14ad6b4d448d2f998c49d")){
    $build=Get-Content "$origin/build-result.json" -Raw|ConvertFrom-Json
    $evidence=Get-Content "$origin/p2-expansion-jvm-evidence.json" -Raw|ConvertFrom-Json
    if((Get-Item $origin).LinkType -or $build.exitCode -ne 0 -or !$build.sourceStable -or !$evidence.passed -or $evidence.count -ne 68 -or $evidence.newExecutionCount -ne 7 -or $evidence.retained.mode -cne 'csqtt-generations-autodeploy-ux' -or (Hash "$origin/source-manifest.json") -cne $build.sourceManifestSha256 -or (Hash "$origin/p2-expansion-jvm-retained.json") -cne $evidence.retainedProofSha256){throw 'Exact accepted68 source and JVM proof required'}
    $base=& "$PSScriptRoot/Read-AutodeployUxRetainedJvm.ps1" -Snapshot "$root/work/android-integration-9f4ac9a5d866481d985e746dc9d9654e" -CurrentManifest $CurrentManifest
    $passed=@();$reports=@()
    foreach($row in $evidence.reports){
        $path="$origin/ports/android/app/build/test-results/testDebugUnitTest/$($row.file)"
        if((Get-Item $path).LinkType -or (Get-Item $path).Length -gt 33554432 -or (Hash $path) -cne $row.sha256){throw 'Accepted seven-case XML changed'}
        $settings=[Xml.XmlReaderSettings]::new();$settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit;$settings.XmlResolver=$null
        $reader=[Xml.XmlReader]::Create($path,$settings)
        try{$xml=[Xml.XmlDocument]::new();$xml.XmlResolver=$null;$xml.Load($reader)}finally{$reader.Dispose()}
        foreach($case in $xml.SelectNodes('/testsuite/testcase')){
            if($case.SelectNodes('failure|error|skipped').Count){throw 'Accepted case did not pass'}
            $passed+=@{class=$case.GetAttribute('classname');name=$case.GetAttribute('name')}
        }
        $reports+=@{origin=$origin;file=$row.file;sha256=$row.sha256}
    }
    $identities=@($base.cases)+$passed
    if($passed.Count -ne 7 -or @($identities|ForEach-Object {$_.class+'.'+$_.name}|Sort-Object -Unique).Count -ne 68 -or (($identities|ForEach-Object {$_.class+'.'+$_.name}|Sort-Object)-join "`n") -cne (($evidence.cases|ForEach-Object {$_.class+'.'+$_.name}|Sort-Object)-join "`n")){throw 'Exact61 plus actual7 accepted identities required'}
    $current=@{};foreach($row in $CurrentManifest){$current[$row.path.Replace('\','/')]=$row.sha256}
    $dependencies=@()
    foreach($row in (Get-Content "$origin/source-manifest.json" -Raw|ConvertFrom-Json)){
        $path=$row.path.Replace('\','/')
        if(!$path.StartsWith('ports/android/app/src/main/') -and !$path.StartsWith('ports/android/app/src/test/')){continue}
        if(!$current[$path] -or $row.sha256 -cne $current[$path] -or (Hash "$origin/$path") -cne $row.sha256 -or (Hash "$root/$path") -cne $row.sha256){throw "Unchanged68 dependency changed: $path"}
        $dependencies+=@{path=$path;sha256=$row.sha256}
    }
    return [ordered]@{allRetained=$true;newExecution=$false;origin=$origin;cases=$evidence.cases;originalEvidenceSha256=Hash "$origin/p2-expansion-jvm-evidence.json";mode='csqtt-generations-historical-ipc-only';dependencies=$dependencies;reports=$reports;scope='All68 exact model/import/UI model sources retained. Only historical Rust local-event adapter changes; four new standalone native adapter cases and real historical phone traffic remain fresh.'}
}
if($origin -ieq [IO.Path]::GetFullPath("$root/work/android-integration-9f4ac9a5d866481d985e746dc9d9654e")){
    return (& "$PSScriptRoot/Read-AutodeployUxRetainedJvm.ps1" -Snapshot $Snapshot -CurrentManifest $CurrentManifest)
}
$packagingOrigin=[IO.Path]::GetFullPath("$root/work/android-integration-8f1a6328b0b84b9ea46dab8b5e8e4af9")
if($origin -ieq $packagingOrigin){
    if((Get-Item -LiteralPath $origin).LinkType){throw 'Ordinary accepted packaging origin required'}
    $build=Get-Content "$origin/build-result.json" -Raw|ConvertFrom-Json
    $evidence=Get-Content "$origin/p2-expansion-jvm-evidence.json" -Raw|ConvertFrom-Json
    if($build.exitCode -ne 0 -or !$build.sourceStable -or !$evidence.passed -or $evidence.count -ne 61 -or $evidence.newExecutionCount -ne 1 -or $evidence.retained.mode -cne 'csqtt-generations-fixture-resume' -or @($evidence.retained.cases).Count -ne 60 -or (Hash "$origin/source-manifest.json") -cne $build.sourceManifestSha256 -or (Hash "$origin/p2-expansion-jvm-retained.json") -cne $evidence.retainedProofSha256){throw 'Exact successful61 identity and partial proof required'}
    $base=& $PSCommandPath -Snapshot "$root/work/android-integration-9d81ac1f38354e2f9e7a789266353967" -CurrentManifest $CurrentManifest
    $partial=$evidence.retained.partial
    if([IO.Path]::GetFullPath($partial.origin) -ine [IO.Path]::GetFullPath("$root/work/android-integration-34eefcad55ad43489c41da6764222cf9") -or $partial.parentExitCode -ne 1 -or !$partial.fixtureOnlyCorrection -or (Hash "$($partial.origin)/build-result.json") -cne $partial.buildResultSha256 -or (Hash "$($partial.origin)/source-manifest.json") -cne $partial.sourceManifestSha256){throw 'Original failed parent provenance changed'}
    $passed=@();$failed=@();$xmlProof=@()
    foreach($item in @($partial.reports|ForEach-Object {@{origin=$partial.origin;row=$_;partial=$true}})+@($evidence.reports|ForEach-Object {@{origin=$origin;row=$_;partial=$false}})){
        $path="$($item.origin)/ports/android/app/build/test-results/testDebugUnitTest/$($item.row.file)"
        if((Get-Item $path).LinkType -or (Get-Item $path).Length -gt 33554432 -or (Hash $path) -cne $item.row.sha256){throw 'Accepted JVM report changed'}
        $settings=[Xml.XmlReaderSettings]::new();$settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit;$settings.XmlResolver=$null
        $reader=[Xml.XmlReader]::Create($path,$settings)
        try{$xml=[Xml.XmlDocument]::new();$xml.XmlResolver=$null;$xml.Load($reader)}finally{$reader.Dispose()}
        foreach($case in $xml.SelectNodes('/testsuite/testcase')){
            if($case.SelectNodes('error|skipped').Count){throw 'Accepted case error or skip'}
            $identity=$case.GetAttribute('classname')+'.'+$case.GetAttribute('name')
            if($case.SelectNodes('failure').Count){if(!$item.partial){throw 'Failed final corrected fixture'};$failed+=$identity}else{$passed+=$identity}
        }
        $xmlProof+=@{origin=$item.origin;file=$item.row.file;sha256=$item.row.sha256}
    }
    $expectedFailure='org.mcrf.client.CsqttImportResolutionTest.narrowVersionChoiceDoesNotFreezeSubscriptionFieldsAndDetectsExplicitProtocolChange'
    if($passed.Count -ne 47 -or @($passed|Sort-Object -Unique).Count -ne 47 -or $failed.Count -ne 1 -or $failed[0] -cne $expectedFailure -or $expectedFailure -cnotin $passed){throw 'Exact46 initial successes plus corrected1 required'}
    $identities=@($evidence.cases|ForEach-Object {$_.class+'.'+$_.name})
    $verified=@($base.cases|ForEach-Object {$_.class+'.'+$_.name})+$passed
    if((($identities|Sort-Object)-join "`n") -cne (($verified|Sort-Object)-join "`n")){throw 'Accepted61 cases differ from actual XML chain'}
    $current=@{};foreach($row in $CurrentManifest){$current[$row.path.Replace('\','/')]=$row.sha256}
    $dependencies=@()
    foreach($row in (Get-Content "$origin/source-manifest.json" -Raw|ConvertFrom-Json)){
        $path=$row.path.Replace('\','/')
        if(!$path.StartsWith('ports/android/app/src/main/') -and !$path.StartsWith('ports/android/app/src/test/')){continue}
        if((Hash "$origin/$path") -cne $row.sha256 -or !$current[$path] -or (Hash "$root/$path") -cne $current[$path]){throw 'Accepted dependency source identity changed'}
        if($path -ceq 'ports/android/app/src/main/kotlin/org/mcrf/client/AndroidDeployBrowserUi.kt'){
            $reviewed=[IO.File]::ReadAllText("$origin/$path")
            foreach($pair in @(@('"terminal"','"logs"'),@('"vpn"','"shield"'),@('"open"','"share"'),@('"key"','"clipboard"'))){
                if([regex]::Matches($reviewed,[regex]::Escape($pair[0])).Count -ne 1){throw 'Expected one reviewed glyph replacement'}
                $reviewed=$reviewed.Replace($pair[0],$pair[1])
            }
            if($reviewed -cne [IO.File]::ReadAllText("$root/$path")){throw 'Only four icon IDs may change in untested Android layout; require new visual capture'}
        }elseif($row.sha256 -cne $current[$path]){throw "Accepted JVM dependency changed: $path"}
        $dependencies+=@{path=$path;sha256=$current[$path]}
    }
    return [ordered]@{allRetained=$true;newExecution=$false;origin=$origin;cases=$evidence.cases;originalEvidenceSha256=Hash "$origin/p2-expansion-jvm-evidence.json";mode='csqtt-generations-packaging-visual-only';dependencies=$dependencies;reports=$xmlProof;scope='Exact accepted61 retained; only frozen module manifest inclusion and four Android-only glyph IDs changed. Native module runtime and visual captures remain fresh.'}
}
$partialOrigin=[IO.Path]::GetFullPath("$root/work/android-integration-34eefcad55ad43489c41da6764222cf9")
if($origin -ieq $partialOrigin){
    if((Get-Item -LiteralPath $origin).LinkType){throw 'Ordinary immutable partial origin required'}
    $base=& $PSCommandPath -Snapshot "$root/work/android-integration-9d81ac1f38354e2f9e7a789266353967" -CurrentManifest $CurrentManifest
    $result=Get-Content "$origin/build-result.json" -Raw|ConvertFrom-Json
    if($result.exitCode -ne 1 -or !$result.sourceStable -or (Hash "$origin/source-manifest.json") -cne $result.sourceManifestSha256){throw 'Exact stable failed parent required; do not relabel it'}
    $current=@{};foreach($row in $CurrentManifest){$current[$row.path.Replace('\','/')]=$row.sha256}
    $fixture='ports/android/app/src/test/kotlin/org/mcrf/client/CsqttImportResolutionTest.kt'
    $old='        val newer=SwgProfile.fromJson(original.original,JSONObject(changed.toString()).put("csqtt_generation","2.5").put("workers",10))'
    $new='        val newer=SwgProfile.fromJson(original.original,JSONObject().put("type","csqtt").put("csqtt_generation","2.5").put("server","fixture.invalid").put("server_port",46002).put("password","fixture-only").put("hashes",JSONArray(listOf(call))).put("workers",10))'
    $deps=@($base.dependencies)
    foreach($row in (Get-Content "$origin/source-manifest.json" -Raw|ConvertFrom-Json)){
        $path=$row.path.Replace('\','/')
        if(!$path.StartsWith('ports/android/app/src/main/') -and !$path.StartsWith('ports/android/app/src/test/')){continue}
        if((Hash "$origin/$path") -cne $row.sha256 -or !$current[$path] -or (Hash "$root/$path") -cne $current[$path]){throw 'Partial source identity changed'}
        if($path -ceq $fixture){
            $beforeText=[IO.File]::ReadAllText("$origin/$path")
            if(!$beforeText.Contains($old) -or $beforeText.Replace($old,$new) -cne [IO.File]::ReadAllText("$root/$path")){throw 'Only reviewed failed-case fixture construction may differ'}
        }elseif($current[$path] -cne $row.sha256){throw "Accepted partial JVM dependency changed: $path"}
        $deps+=@{path=$path;sha256=$current[$path]}
    }
    $passed=@();$failed=@();$reports=@();$seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach($report in (Get-ChildItem "$origin/ports/android/app/build/test-results/testDebugUnitTest" -File -Filter 'TEST-*.xml')){
        if($report.LinkType -or $report.Length -gt 33554432){throw 'Ordinary bounded partial XML required'}
        $settings=[Xml.XmlReaderSettings]::new();$settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit;$settings.XmlResolver=$null
        $reader=[Xml.XmlReader]::Create($report.FullName,$settings)
        try{$xml=[Xml.XmlDocument]::new();$xml.XmlResolver=$null;$xml.Load($reader)}finally{$reader.Dispose()}
        foreach($case in $xml.SelectNodes('/testsuite/testcase')){
            $class=$case.GetAttribute('classname');$name=$case.GetAttribute('name');$identity="$class.$name"
            if(!$seen.Add($identity) -or $case.SelectNodes('error|skipped').Count){throw 'Duplicate/error/skipped partial case'}
            if($case.SelectNodes('failure').Count){$failed+=$identity}else{$passed+=@{class=$class;name=$name}}
        }
        $reports+=@{file=$report.Name;sha256=Hash $report.FullName}
    }
    if($passed.Count -ne 46 -or $failed.Count -ne 1 -or $failed[0] -cne 'org.mcrf.client.CsqttImportResolutionTest.narrowVersionChoiceDoesNotFreezeSubscriptionFieldsAndDetectsExplicitProtocolChange'){throw 'Exact partial46 pass/one failed fixture required'}
    $base.cases=@($base.cases)+$passed
    if(@($base.cases|ForEach-Object {$_.class+'.'+$_.name}|Sort-Object -Unique).Count -ne 60){throw 'Partial/retained case overlap'}
    $base.mode='csqtt-generations-fixture-resume';$base.baseRetainedCaseCount=14;$base.resumedCaseCount=46
    $base.dependencies=@($deps|Sort-Object path -Unique)
    $base.partial=@{origin=$origin;parentExitCode=1;buildResultSha256=Hash "$origin/build-result.json";sourceManifestSha256=$result.sourceManifestSha256;reports=$reports;excludedFailedCase=$failed[0];fixtureOnlyCorrection=$true}
    $base.scope='Retain unchanged14 and actual46 successful affected cases from failed parent; execute only corrected failed fixture. Parent failure preserved.'
    return $base
}
if($origin -ine [IO.Path]::GetFullPath("$root/work/android-integration-9d81ac1f38354e2f9e7a789266353967") -or (Get-Item -LiteralPath $origin).LinkType){throw 'Exact installed 10038 acceptance origin required'}
$build=Get-Content "$origin/build-result.json" -Raw|ConvertFrom-Json
$evidence=Get-Content "$origin/p2-expansion-jvm-evidence.json" -Raw|ConvertFrom-Json
if($build.exitCode -ne 0 -or !$build.sourceStable -or !$evidence.passed -or $evidence.count -ne 34 -or $evidence.newExecutionCount -ne 0 -or !$evidence.retained.allRetained){throw 'Exact accepted unchanged34 receipt required'}
$chain=$evidence
for($depth=0;$depth -lt 10;$depth++){
    foreach($report in $chain.reports){if((Hash "$origin/ports/android/app/build/test-results/testDebugUnitTest/$($report.file)") -cne $report.sha256){throw 'Accepted JVM XML changed'}}
    if(!$chain.retained){break}
    $link=$chain.retained;$origin=[IO.Path]::GetFullPath($link.origin)
    if([IO.Path]::GetDirectoryName($origin) -ine [IO.Path]::GetFullPath("$root/work") -or (Get-Item -LiteralPath $origin).LinkType){throw 'Owned immutable retention origin required'}
    if((Hash "$origin/p2-expansion-jvm-evidence.json") -cne $link.originalEvidenceSha256){throw 'Original retention evidence changed'}
    $chain=Get-Content "$origin/p2-expansion-jvm-evidence.json" -Raw|ConvertFrom-Json
    if(!$chain.passed -or ($chain.cases|ConvertTo-Json -Depth 4 -Compress) -cne ($link.cases|ConvertTo-Json -Depth 4 -Compress)){throw 'Original retained identities changed'}
    if($depth -eq 9){throw 'Unexpected retention chain depth'}
}
$origin=[IO.Path]::GetFullPath($Snapshot)
$before=@{};foreach($row in (Get-Content "$origin/source-manifest.json" -Raw|ConvertFrom-Json)){$before[$row.path.Replace('\','/')]=$row.sha256}
$after=@{};foreach($row in $CurrentManifest){$after[$row.path.Replace('\','/')]=$row.sha256}
$deps=@{
    NotificationTrafficSettingsTest=@('NotificationTrafficSettings.kt','TelemetryCadence.kt')
    OutboundObservationTest=@('OutboundObservation.kt')
    AutoSelectionDeviationTest=@('AutoSelectionDeviation.kt')
    AutoSelectionOffTest=@('BsAutoSelectionPolicy.kt')
    VkCallPasteTest=@('VkCallSlots.kt','VkHashPool.kt')
}
$identities=@('org.mcrf.client.TurnHashPoolTest.recentChoiceMovesOnlyKnownMemberAndKeepsFastFallbackOrder','org.mcrf.client.TurnHashPoolTest.boundedAttemptsKeepPreferredButEventuallyReachEveryFallback')
$retained=@($evidence.cases|Where-Object {$deps.ContainsKey($_.class.Substring($_.class.LastIndexOf('.')+1)) -or ($_.class+'.'+$_.name) -cin $identities})
if($retained.Count -ne 14){throw 'Exact fourteen unchanged notification/paste/deviation/generic-order cases required'}
$paths=@('ports/android/app/src/test/kotlin/org/mcrf/client/TurnHashPoolTest.kt','ports/android/app/src/main/kotlin/org/mcrf/client/BsAutoSelectionPolicy.kt')
foreach($key in $deps.Keys){
    $paths+="ports/android/app/src/test/kotlin/org/mcrf/client/$key.kt"
    $paths+=@($deps[$key]|ForEach-Object {"ports/android/app/src/main/kotlin/org/mcrf/client/$_"})
}
$proof=@()
foreach($path in ($paths|Sort-Object -Unique)){
    if(!$before[$path] -or $before[$path] -cne $after[$path] -or (Hash "$origin/$path") -cne $before[$path] -or (Hash "$root/$path") -cne $before[$path]){throw "Unchanged pure dependency proof failed: $path"}
    $proof+=@{path=$path;sha256=$before[$path]}
}
[ordered]@{allRetained=$false;newExecution=$false;origin=$origin;cases=$retained;originalEvidenceSha256=Hash "$origin/p2-expansion-jvm-evidence.json";mode='csqtt-generations-affected-only';dependencies=$proof;scope='Retain14 unchanged pure cases. Shared CSQTT/import/module/stream dependencies and new management diagnostics execute fresh.'}
