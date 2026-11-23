param([Parameter(Mandatory)][string]$Snapshot,[Parameter(Mandatory)]$CurrentManifest)
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF'
function Hash([string]$path){(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}
$origin=[IO.Path]::GetFullPath($Snapshot)
if([IO.Path]::GetDirectoryName($origin) -cne "$root/work".Replace('/',[IO.Path]::DirectorySeparatorChar) -or (Get-Item -LiteralPath $origin).LinkType){throw 'Ordinary owned integration snapshot required'}
$build=Get-Content "$origin/build-result.json" -Raw|ConvertFrom-Json
$evidence=Get-Content "$origin/p2-expansion-jvm-evidence.json" -Raw|ConvertFrom-Json
if($build.exitCode -ne 0 -or !$build.sourceStable -or !$evidence.passed -or $evidence.count -notin @(19,22,23,29,31,34) -or @($evidence.cases).Count -ne $evidence.count){throw 'Successful original affected batch required'}
if($evidence.count -in @(31,34)){
    if($evidence.count -eq 31 -and ($evidence.newExecutionCount -ne 2 -or @($evidence.retained.cases).Count -ne 29)){throw 'Accepted31 requires two fresh deviation cases and exact retained29'}
    if($evidence.count -eq 34 -and ($evidence.newExecutionCount -ne 3 -or @($evidence.retained.cases).Count -ne 31)){throw 'Accepted34 requires three fresh deploy cases and exact retained31'}
    $chain=$evidence
    $chainCounts=if($evidence.count -eq 34){@(31,29,22,19)}else{@(29,22,19)}
    foreach($expected in $chainCounts){
        $link=$chain.retained;$file="$($link.origin)/p2-expansion-jvm-evidence.json"
        if((Hash $file) -cne $link.originalEvidenceSha256){throw 'Retained JVM chain digest changed'}
        $chain=Get-Content $file -Raw|ConvertFrom-Json
        if(!$chain.passed -or $chain.count -ne $expected -or ($chain.cases|ConvertTo-Json -Compress) -cne ($link.cases|ConvertTo-Json -Compress)){throw 'Retained JVM case identities changed'}
        foreach($report in $chain.reports){if((Hash "$($link.origin)/ports/android/app/build/test-results/testDebugUnitTest/$($report.file)") -cne $report.sha256){throw 'Retained JVM XML changed'}}
    }
}
if($evidence.count -eq 29){
    $prior=$evidence.retained
    if($evidence.newExecutionCount -ne 7 -or @($prior.cases).Count -ne 22 -or (Hash "$($prior.origin)/p2-expansion-jvm-evidence.json") -cne $prior.originalEvidenceSha256){throw 'Accepted29 requires exact22 retained chain'}
    $accepted22=Get-Content "$($prior.origin)/p2-expansion-jvm-evidence.json" -Raw|ConvertFrom-Json
    if(!$accepted22.passed -or $accepted22.count -ne 22 -or ($accepted22.cases|ConvertTo-Json -Compress) -cne ($prior.cases|ConvertTo-Json -Compress)){throw 'Retained22 case identities changed'}
    foreach($report in $accepted22.reports){if((Hash "$($prior.origin)/ports/android/app/build/test-results/testDebugUnitTest/$($report.file)") -cne $report.sha256){throw 'Accepted22 XML changed'}}
    $chain19=$accepted22.retained
    if((Hash "$($chain19.origin)/p2-expansion-jvm-evidence.json") -cne $chain19.originalEvidenceSha256){throw 'Original19 evidence chain changed'}
    $accepted19=Get-Content "$($chain19.origin)/p2-expansion-jvm-evidence.json" -Raw|ConvertFrom-Json
    if(!$accepted19.passed -or $accepted19.count -ne 19 -or ($accepted19.cases|ConvertTo-Json -Compress) -cne ($chain19.cases|ConvertTo-Json -Compress)){throw 'Original19 identities changed'}
    foreach($report in $accepted19.reports){if((Hash "$($chain19.origin)/ports/android/app/build/test-results/testDebugUnitTest/$($report.file)") -cne $report.sha256){throw 'Accepted19 XML changed'}}
}
if($evidence.count -eq 22){
    $chain=$evidence.retained
    if($evidence.newExecutionCount -ne 3 -or @($chain.cases).Count -ne 19 -or (Hash "$($chain.origin)/p2-expansion-jvm-evidence.json") -cne $chain.originalEvidenceSha256){throw 'Original19 retention chain changed'}
    $original=Get-Content "$($chain.origin)/p2-expansion-jvm-evidence.json" -Raw|ConvertFrom-Json
    if(!$original.passed -or $original.count -ne 19 -or ($original.cases|ConvertTo-Json -Compress) -cne ($chain.cases|ConvertTo-Json -Compress)){throw 'Original retained case identities changed'}
    foreach($report in $original.reports){if((Hash "$($chain.origin)/ports/android/app/build/test-results/testDebugUnitTest/$($report.file)") -cne $report.sha256){throw 'Original19 XML changed'}}
}
$before=@{};foreach($row in (Get-Content "$origin/source-manifest.json" -Raw|ConvertFrom-Json)){
    if((Hash (Join-Path $origin $row.path)) -cne $row.sha256){throw 'Original frozen input changed'}
    $before[$row.path.Replace('\','/')]=$row.sha256
}
$after=@{};foreach($row in $CurrentManifest){$after[$row.path.Replace('\','/')]=$row.sha256}
$deployDelta=$null
if($evidence.count -eq 31 -and (Get-Content "$root/ports/android/coordination/p2-expansion-jvm-affected-2026-10-21.txt" | Select-Object -Last 1) -ceq 'org.mcrf.client.VkRelayTest.deploymentCallPoolsRespectActualAndroidImportLimits'){
    $deployDelta=Get-Content "$root/ports/android/coordination/p2-deploy-calls-reviewed-delta-20261022.json" -Raw|ConvertFrom-Json
    if([IO.Path]::GetFullPath($deployDelta.origin) -cne $origin -or @($deployDelta.files).Count -ne 8){throw 'Exact reviewed deploy-call delta required'}
    foreach($file in $deployDelta.files){if($before[$file.path] -cne $file.before -or $after[$file.path] -cne $file.after){throw "Unreviewed deploy dependency change: $($file.path)"}}
}
$ui=@('CarrierOption.kt','DropdownStyle.kt','DropdownPopup.kt','VkTurnUi.kt','CarrierStreamDefaultsUi.kt','ConnectionEditor.kt','RoutingGroupsUi.kt','LogRetentionUi.kt','ThemedActionMenu.kt')
$prefix='ports/android/app/src/main/kotlin/org/mcrf/client/'
$partial=$evidence.count -in @(19,22,29) -or $null -ne $deployDelta
if($partial){$ui+=@('MainActivity.kt','ServersPage.kt','UserThemeColorsUi.kt')}
if($evidence.count -in @(22,29)){
    # These Android-only integration changes require NEW phone checks, not a
    # replay of the unchanged pure hash/import/startup cases below.
    $ui+=@('AndroidBackup.kt','ClientSettingsRegistry.kt','ClientSettingsNavigation.kt','ConnectionScene.kt','EdgeConnectionControls.kt','ModeSwitch.kt','McrfVpnService.kt','AutoToleranceUi.kt','EdgePanelAppearance.kt','NotificationTrafficSettings.kt','NotificationTrafficUi.kt','NotificationTrafficSampler.kt','TelemetryCadence.kt','OutboundObservation.kt')
}
if($evidence.count -eq 29){$ui+=@('AutoSelectionDeviation.kt','EditorLayout.kt','RoutingPage.kt','RoutingDefaultsRussianAppsUi.kt','AndroidDeployUi.kt','UsageFeaturesUi.kt','BsWifiUi.kt','Loft.kt')}
if($evidence.count -eq 31){
    # This follow-up changes widgets only. No pure model/service/schema exception:
    # all test inputs and every other production dependency must remain exact.
    $ui=@('MainActivity.kt','RoutingPage.kt','CarrierOption.kt','DropdownPopup.kt','DropdownStyle.kt','DnsEditor.kt','HomeUiControls.kt','AutoToleranceUi.kt')
}
if($evidence.count -eq 34){
    $ui=@()
    $script=$prefix+'AndroidDeployScripts.kt'
    if($before[$script] -cne 'e4a909132c2325859956909af9f432bea641c94c1ba27d094fcc3d83b9693b05' -or $after[$script] -cne '4aaffddd9f3a8f8a732d6c6f093539f406a3f17f753162df768106b8f2aa1299'){throw 'Only the reviewed live installer syntax/toolchain/interface repair is allowed'}
    $ui=@('AndroidDeployScripts.kt')
}
foreach($path in @($before.Keys+$after.Keys|Sort-Object -Unique)){
    if($path -notlike 'ports/android/app/src/main/*' -and $path -notlike 'ports/android/app/src/test/*'){continue}
    if($before.ContainsKey($path) -and $after.ContainsKey($path) -and $before[$path] -ceq $after[$path]){continue}
    if($deployDelta -and @($deployDelta.files|Where-Object {$_.path -ceq $path}).Count -eq 1){continue}
    if($path -cin @($ui|ForEach-Object {$prefix+$_})){continue}
    if($partial -and $path -ceq 'ports/android/app/src/test/kotlin/org/mcrf/client/UnifiedAutoSelectionTest.kt' -and !$before.ContainsKey($path)){continue}
    if($evidence.count -eq 22 -and !$before.ContainsKey($path) -and $path -cin @('NotificationTrafficSettingsTest.kt','AutoSelectionOffTest.kt','OutboundObservationTest.kt'|ForEach-Object {'ports/android/app/src/test/kotlin/org/mcrf/client/'+$_})){continue}
    if($evidence.count -eq 29 -and !$before.ContainsKey($path) -and $path -ceq 'ports/android/app/src/test/kotlin/org/mcrf/client/AutoSelectionDeviationTest.kt'){continue}
    if($partial -and $path -ceq ($prefix+'BsAutoSelectionPolicy.kt')){
        function ExistingPolicy([string]$text){
            $text=$text.Replace("`r`n","`n")
            if($evidence.count -eq 19){
                $start=$text.IndexOf('    fun startup(')
                if($start -ge 0){$end=$text.IndexOf('    /** Always try',$start);if($end -lt 0){throw 'Unrecognized new startup boundary'};$text=$text.Remove($start,$end-$start)}
            }elseif($evidence.count -eq 22){
                $text=$text.Replace('const val OFF="off"','').Replace('val modes=setOf(OFF,FASTEST,RECENT)','')
                $text=$text.Replace('it in modes','it in setOf(FASTEST,RECENT)')
                $start=$text.IndexOf('    fun <T> targets(')
                if($start -ge 0){$end=$text.IndexOf('    fun startup(',$start);if($end -lt 0){throw 'Unrecognized OFF policy boundary'};$text=$text.Remove($start,$end-$start)}
            }
            ($text -split "`n"|Where-Object {$_ -notmatch '^/\*\* (Detector target choice|Shared choice)' -and $_ -notmatch '^    const val HELP=' -and $_.Trim()}) -join "`n"
        }
        if((ExistingPolicy ([IO.File]::ReadAllText((Join-Path $origin $path)))) -cne (ExistingPolicy ([IO.File]::ReadAllText((Join-Path $root $path))))){throw 'Previously tested detector policy implementation changed'}
        continue
    }
    if($path -ceq ($prefix+'VkCallSlots.kt')){
        $old=[IO.File]::ReadAllText((Join-Path $origin $path));$new=[IO.File]::ReadAllText((Join-Path $root $path))
        $marker='internal class VkCallSlots('
        if($old.IndexOf($marker) -lt 0 -or $new.IndexOf($marker) -lt 0){throw 'Tested VkCallPaste boundary changed'}
        $oldPure=$old.Substring(0,$old.IndexOf($marker)).Replace("`r`n","`n")
        $newPure=$new.Substring(0,$new.IndexOf($marker)).Replace("`r`n","`n")
        # The new fold needs View.GONE; it does not change the separately tested
        # pure paste parser. Ignore exactly this one Android UI import only.
        if($evidence.count -eq 29){$oldPure=$oldPure.Replace("import android.view.View`n",'');$newPure=$newPure.Replace("import android.view.View`n",'')}
        if($oldPure -cne $newPure){throw 'Tested VkCallPaste implementation changed'}
        continue
    }
    throw "Non-UI JVM dependency changed: $path"
}
$selection='ports/android/coordination/p2-expansion-jvm-affected-2026-10-21.txt'
if($evidence.selectionSha256 -cne $before[$selection]){throw 'Original retained test selection changed'}
if($partial){
    $oldSelection=@(Get-Content (Join-Path $origin $selection))
    $newSelection=@(Get-Content (Join-Path $root $selection))
    $added=if($deployDelta){@('org.mcrf.client.AndroidDeploySwgTest.legacyLongCallDraftOpensAndPreservesAllWdttCalls','org.mcrf.client.AndroidDeploySwgTest.expandedCallListsHaveNoProtocolCountCapAndKeepPayloadGuard','org.mcrf.client.VkRelayTest.deploymentCallPoolsRespectActualAndroidImportLimits')}elseif($evidence.count -eq 29){@('org.mcrf.client.AutoSelectionDeviationTest')}elseif($evidence.count -eq 22){@('org.mcrf.client.NotificationTrafficSettingsTest','org.mcrf.client.AutoSelectionOffTest','org.mcrf.client.OutboundObservationTest')}else{@('org.mcrf.client.UnifiedAutoSelectionTest')}
    if(($newSelection -join "`n") -cne (($oldSelection+$added) -join "`n")){throw 'Only the new source-bound affected classes may be added'}
}elseif($before[$selection] -cne $after[$selection]){throw 'Retained test selection changed'}
foreach($report in $evidence.reports){if((Hash "$origin/ports/android/app/build/test-results/testDebugUnitTest/$($report.file)") -cne $report.sha256){throw 'Original test XML changed'}}
[ordered]@{allRetained=!$partial;newExecution=$false;origin=$origin;cases=$evidence.cases;originalEvidenceSha256=Hash "$origin/p2-expansion-jvm-evidence.json";reviewedUiFiles=$ui;scope='Unchanged prior pure cases retained; added unified-selection class and UI require fresh checks'}
