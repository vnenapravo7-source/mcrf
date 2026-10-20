param([ValidateSet('build','test','native-build','native-package')][string]$Operation='build')
$ErrorActionPreference='Stop'
$taskCurrent=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../coordination/current.json') -Raw|ConvertFrom-Json
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
