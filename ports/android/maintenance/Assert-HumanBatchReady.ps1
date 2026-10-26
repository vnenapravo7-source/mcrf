param([ValidateSet('build','test','native-build','native-package')][string]$Operation='build')
$ErrorActionPreference='Stop'
$taskCurrent=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../coordination/current.json') -Raw|ConvertFrom-Json
if($taskCurrent.p3p4Batch){
    if($taskCurrent.p3p4Batch -cne 'p3-p4-2026-10-23.json'){throw 'Unexpected P3/P4 batch'}
    $taskNext=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../coordination/p3-p4-2026-10-23.json') -Raw|ConvertFrom-Json
    if($taskNext.authority -cne 'human-20261023-finish-current-fixes-then-p3-p4'){throw 'Exact P3/P4 source authority required'}
    if($taskNext.sourceReady -ne $true -or $taskNext.allowCommonVerification -ne $true -or $taskNext.commonRecipe -cne 'maintenance/Build-P3P4Common.ps1'){throw 'P3/P4 source package is incomplete; do not reuse previous candidate authority'}
    if($Operation -eq 'build' -and $taskNext.allowApkBuild -ne $true){throw 'P3/P4 main APK gate closed'}
    if($Operation -in @('native-build','native-package') -and $taskNext.allowNativeCandidateBuild -ne $true){throw 'P3/P4 native gate closed'}
    return
}
if($taskCurrent.nextReleaseBatch){
    if($taskCurrent.nextReleaseBatch -cne 'next-release-2026-10-23.json'){throw 'Unexpected next-release source package'}
    $taskNext=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../coordination/next-release-2026-10-23.json') -Raw|ConvertFrom-Json
    if($taskNext.authority -cne 'human-20261023-next-release-fixes-then-p3-p4'){throw 'Current human source-package authority required'}
    if($taskNext.verificationScope -cne 'independent-six-only' -or $taskNext.scopeAuthority -cne 'human-20261023-continue-other-fixes-while-vk-blocked' -or
        $taskNext.commonRecipe -cne 'maintenance/Build-NextReleaseIndependent.ps1' -or $taskNext.publish -ne $false){throw 'Only the independent verification candidate is authorized'}
    if((@($taskNext.deferredLiveTasks|Sort-Object) -join ',') -cne 'bs-upload-throughput,wdtt-empty-route-group'){throw 'Live blocked tasks must remain explicitly deferred'}
    foreach($taskFlag in @('sourceReady','readyForBuild','allowCommonVerification')){
        if($taskNext.$taskFlag -isnot [bool] -or !$taskNext.$taskFlag){throw "Finish the complete next-release source package before $Operation; accepted P2 receipts do not authorize new partial builds or repeated checks."}
    }
    if($Operation -eq 'build' -and $taskNext.allowApkBuild -ne $true){throw 'Next-release APK gate closed'}
    if($Operation -in @('native-build','native-package') -and $taskNext.allowNativeCandidateBuild -ne $true){throw 'Next-release native gate closed'}
    return
}
if($taskCurrent.activeBatch -ceq 'p2-expansion-2026-10-21.json'){
    $taskExpanded=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../coordination/p2-expansion-2026-10-21.json') -Raw|ConvertFrom-Json
    if($taskExpanded.priority -ne 2 -or $taskExpanded.verificationAuthority -cne 'human-20261021-expanded-p2-whole-source-before-common-verification'){
        throw 'Exact NEW expanded-P2 authority required; completed P2 grants do not reopen'
    }
    foreach($taskFlag in @('sourceReady','readyForBuild','allowCommonVerification')){
        if($taskExpanded.$taskFlag -isnot [bool] -or !$taskExpanded.$taskFlag){throw "Finish ALL new P2 source tasks51-57 before $Operation; no per-fix compiler/parser/host/JVM/native runs"}
    }
    $taskIds=@($taskExpanded.tasks|ForEach-Object {$_.id}|Sort-Object)
    if(($taskIds -join ',') -cne '51,52,53,54,55,56,57' -or @($taskExpanded.tasks|Where-Object {$_.source -cne 'ready'}).Count){throw 'Every expanded-P2 task must be source-ready before common verification'}
    if($taskExpanded.commonRecipe -cne 'maintenance/Build-ExpandedP2Common.ps1'){throw 'Explicit NEW expanded-P2 common coordinator required'}
    if($Operation -eq 'build' -and ($taskExpanded.allowApkBuild -isnot [bool] -or !$taskExpanded.allowApkBuild)){throw 'Expanded-P2 APK gate closed'}
    if($Operation -in @('native-build','native-package') -and
       ($taskExpanded.nativeSourceReady -isnot [bool] -or !$taskExpanded.nativeSourceReady -or $taskExpanded.allowNativeCandidateBuild -isnot [bool] -or !$taskExpanded.allowNativeCandidateBuild)){throw 'Expanded-P2 native source gate closed'}
    return
}
if($taskCurrent.activeBatch -ceq 'p2-progress-2026-10-20.json'){
    $taskP2=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../coordination/p2-progress-2026-10-20.json') -Raw|ConvertFrom-Json
    if($taskP2.priority -ne 2 -or $taskP2.verificationAuthority -cne 'human-20261020-p2-whole-source-before-common-verification'){
        throw 'Exact common-P2 authority required; closed P0/P1 grants do not apply'
    }
    foreach($taskFlag in @('sourceReady','readyForBuild','allowCommonVerification')){
        if($taskP2.$taskFlag -isnot [bool] -or !$taskP2.$taskFlag){throw "Finish ALL P2 source tasks before $Operation; no compiler/parser/host/JVM/native micro-runs"}
    }
    $taskP2Ids=@($taskP2.items|ForEach-Object {$_.id}|Sort-Object)
    if(($taskP2Ids -join ',') -cne '2,6,7,11,20' -or @($taskP2.items|Where-Object {$_.sourceReady -isnot [bool] -or !$_.sourceReady}).Count){throw 'Every named P2 item must be source-ready; a top-level flag alone is not whole-package readiness'}
    if($Operation -eq 'build' -and ($taskP2.allowApkBuild -isnot [bool] -or !$taskP2.allowApkBuild)){throw 'Common-P2 APK build gate is closed'}
    if($Operation -in @('native-build','native-package') -and
        ($taskP2.nativeSourceReady -isnot [bool] -or !$taskP2.nativeSourceReady -or
         $taskP2.allowNativeCandidateBuild -isnot [bool] -or !$taskP2.allowNativeCandidateBuild)){throw 'Common-P2 external native source/package gate is closed'}
    return
}
if($taskCurrent.activeBatch -ceq 'p0-progress-2026-10-19.json'){
    $taskP046=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../coordination/p0-progress-2026-10-19.json') -Raw|ConvertFrom-Json
    if($taskP046.priority -ne 0 -or $taskP046.verificationAuthority -cne 'human-20261019-p046-startup-bs-additive-minute-monitor'){
        throw 'Exact P046 source/common-verification authority required'
    }
    foreach($taskFlag in @('sourceReady','readyForBuild','allowCommonVerification')){
        if($taskP046.$taskFlag -isnot [bool] -or !$taskP046.$taskFlag){throw 'Finish the WHOLE P046 source package before any compiler/test/build'}
    }
    if($Operation -ne 'test'){throw 'P046 permits affected common verification/compilation only; no APK or native authorization'}
    return
}
# P1 has its OWN whole-source/common-verification authority. An already closed
# P0 release record must neither authorize P1 nor permanently mask its gate.
# These booleans remain false while any agreed P1 source task is incomplete.
if($taskCurrent.activeBatch -ceq 'p1-progress-2026-10-17.json'){
    $taskP1=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../coordination/p1-progress-2026-10-17.json') -Raw|ConvertFrom-Json
    if($taskP1.priority -ne 1 -or $taskP1.verificationAuthority -cne 'human-20261019-p1-whole-source-before-common-verification'){
        throw 'Exact common-P1 authority required; historical P0 grants do not apply'
    }
    foreach($taskFlag in @('sourceReady','readyForBuild','allowCommonVerification')){
        if($taskP1.$taskFlag -isnot [bool] -or !$taskP1.$taskFlag){throw "Finish ALL P1 source tasks before $Operation; no host/JVM/native/parser micro-runs"}
    }
    if($Operation -eq 'build' -and ($taskP1.allowApkBuild -isnot [bool] -or !$taskP1.allowApkBuild)){
        throw 'Common-P1 APK build gate is closed'
    }
    if($Operation -in @('native-build','native-package') -and
        ($taskP1.nativeSourceReady -isnot [bool] -or !$taskP1.nativeSourceReady -or
         $taskP1.allowNativeCandidateBuild -isnot [bool] -or !$taskP1.allowNativeCandidateBuild)){
        throw 'Common-P1 external native source/package gate is closed'
    }
    return
}
$taskProgress=Join-Path $PSScriptRoot '../coordination/p0-progress-2026-10-17.json'
if(!(Test-Path -LiteralPath $taskProgress -PathType Leaf)){throw 'Human batch readiness record missing; do not infer permission to build/test'}
if(Test-Path -LiteralPath $taskProgress){
    $taskBatch=Get-Content -LiteralPath $taskProgress -Raw|ConvertFrom-Json
    if($taskBatch.readyForBuild -isnot [bool] -or !$taskBatch.readyForBuild){
        throw "Human priority batch is NOT ready: $Operation blocked. Finish ALL P0 source tasks before builds/tests; do not bypass via a smaller host/native/emulator run."
    }
}
if($taskBatch.activeNetworkBatch){
    if($taskBatch.activeNetworkBatch -cne 'p0-network-42-45-2026-10-19.json'){throw 'Unexpected network batch pointer'}
    $taskNetwork=Get-Content -LiteralPath (Join-Path $PSScriptRoot "../coordination/$($taskBatch.activeNetworkBatch)") -Raw|ConvertFrom-Json
    if($taskNetwork.readyForBuild -isnot [bool] -or !$taskNetwork.readyForBuild){throw 'Whole P0 network source batch is not ready'}
    if($Operation -eq 'build' -and $taskNetwork.allowApkBuild -ne $true){throw 'Network batch permits affected tests/compilation only; no new APK authorization'}
    if($Operation -in @('native-build','native-package') -and ($taskNetwork.allowNativeCandidateBuild -ne $true -or $taskNetwork.nativeDirectSourceReady -ne $true -or $taskNetwork.nativeBuildAuthority -cne 'human-20261019-independent-open-close-check-until-fixed')){throw 'No fresh narrowly scoped P044 external native candidate authority'}
}
elseif($Operation -in @('native-build','native-package')){throw 'Native P044 build requires the exact current network batch'}
if($taskCurrent.activeBatch){
    if($taskCurrent.activeBatch -cnotmatch '^p[0-4]-progress-[0-9]{4}-[0-9]{2}-[0-9]{2}\.json$'){throw 'Unexpected active human priority-batch pointer'}
    $taskActive=Get-Content -LiteralPath (Join-Path $PSScriptRoot "../coordination/$($taskCurrent.activeBatch)") -Raw|ConvertFrom-Json
    if($taskActive.readyForBuild -isnot [bool] -or !$taskActive.readyForBuild){throw "Active human priority batch is NOT ready: $Operation blocked; finish the whole same-priority source batch first."}
}
