param(
    [string]$JsonJar='work/toolchains/gradle-home/caches/modules-2/files-2.1/org.json/json/20250517/d67181bbd819ccceb929b580a4e2fcb0c8b17cd8/json-20250517.jar',
    [string]$RetainFromReceipt='',
    [string]$RetainModelPrefixReceipt='',
    [string]$RetainJvmArchive='',
    [switch]$DeviceRepair,
    [switch]$FinalFixtures,
    [switch]$FinalFrameworkRejection,
    [string]$RetainSteerPeerCapacity=''
)
$ErrorActionPreference='Stop'
# ONE common verification batch. Never opens the source-ready gate itself,
# installs anything, touches a phone, publishes, auto-retries or reruns P046.
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation test
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation native-build
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation build
if($args.Count){throw 'Unknown common P1 arguments'}
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$android=Join-Path $root 'ports/android';$builds='D:/MCRF/Builds/Android'
$currentPath=Join-Path $android 'coordination/current.json'
$current=Get-Content -LiteralPath $currentPath -Raw|ConvertFrom-Json
if($current.activeBatch -cne 'p1-progress-2026-10-17.json' -or $current.jobs.build -or $current.jobs.hostTest -or $current.jobs.device -or $current.jobs.emulator){throw 'Not an idle current P1 batch; continue existing work instead'}
$reviewPath=Join-Path $android 'coordination/p1-source-review-2026-10-19.json'
$review=Get-Content -LiteralPath $reviewPath -Raw|ConvertFrom-Json
if($review.sourceComplete -isnot [bool] -or !$review.sourceComplete -or $review.functionallyAccepted -ne $false){throw 'Whole-source manual review required; preparation is not accepted functionality'}
$java='C:/Program Files/Java/jdk-17'
$python='C:/Users/dieonfire/AppData/Local/Programs/Python/Python314/python.exe'
$json=[IO.Path]::GetFullPath((Join-Path $root $JsonJar))
if(!$json.StartsWith((Join-Path $root 'work')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
    (Get-FileHash -LiteralPath $json).Hash.ToLowerInvariant() -cne '3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796'){throw 'Exact pinned org.json fixture dependency required'}
$mutex=[Threading.Mutex]::new($false,'Local\MCRF-P1-Whole');$held=$false
try{$held=$mutex.WaitOne(0)}catch [Threading.AbandonedMutexException] {$mutex.Dispose();throw 'Abandoned P1 owner: inspect retained receipts before resuming'}
if(!$held){$mutex.Dispose();throw 'Common P1 verification already running; do not duplicate'}
$stage=Join-Path $builds ('p1-whole-'+[guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($stage)|Out-Null
$job='P1Whole:'+$stage
$state=[ordered]@{state='PREPARING';startedUtc=[DateTime]::UtcNow.ToString('o');sourceStable=$false;stage=$stage;
    fullP1Accepted=$false;phoneTouched=$false;installed=$false;published=$false;phases=[ordered]@{};
    scope='Whole P1 affected models/host/native candidates/main Kotlin and R8. Actual Android/UID/TLS/traffic/STOP acceptance remains separate and requires fresh authority.'}
function Save { [IO.File]::WriteAllText((Join-Path $stage 'receipt.json'),($state|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false)) }
function Sha([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
function SourceList {
    Push-Location $root
    try{
        $directories=@('ports/android/app/src','ports/android/primary-spi/src','ports/android/primary-adapter/src','ports/android/primary-cores/singbox/src',
            'ports/android/p1-owner-probe/src','ports/android/p1-owner-peer/src','ports/android/wg-module/src','ports/android/transports/wg-backend/android/src',
            'ports/android/WireGuardFixtures/cmd/WireGuardPeer','ports/catalogue','ports/fixtures')
        $files=@(& rg --files --hidden @directories);if($LASTEXITCODE){throw 'Common source inventory failed'}
        $files+=@(Get-ChildItem -LiteralPath (Join-Path $android 'transports/wg-backend') -File|Where-Object {$_.Extension -in @('.go','.ps1') -or $_.Name -in @('go.mod','go.sum')}|ForEach-Object {[IO.Path]::GetRelativePath($root,$_.FullName)})
        $files+=@('ports/android/settings.gradle.kts','ports/android/build.gradle.kts','ports/android/gradle.properties','ports/android/app/build.gradle.kts','ports/android/app/proguard-rules.pro',
            'ports/android/engines/build.gradle.kts','ports/android/engines/EngineUpdatePrimaryManifest.xml',
            'ports/android/primary-spi/build.gradle.kts','ports/android/primary-spi/contract-lock.json','ports/android/primary-adapter/build.gradle.kts',
            'ports/android/primary-cores/singbox/consumer-rules.pro','ports/android/p1-owner-probe/build.gradle.kts','ports/android/p1-owner-peer/build.gradle.kts','ports/android/wg-module/build.gradle.kts',
            'ports/android/gradle/wrapper/gradle-wrapper.jar','ports/android/gradle/wrapper/gradle-wrapper.properties',
            'ports/android/WireGuardFixtures/go.mod','ports/android/WireGuardFixtures/go.sum','ports/android/WireGuardFixtures/Build-PacketAuditPeer.ps1',
            'ports/android/integration-build.ps1','ports/android/integration-device-checks.ps1','ports/android/integration-p1-steer.ps1','ports/android/integration-packet-phone.ps1',
            'ports/android/maintenance/Assert-HumanBatchReady.ps1','ports/android/maintenance/Verify-P1Whole.ps1',
            'ports/android/coordination/current.json','ports/android/coordination/p1-progress-2026-10-17.json','ports/android/coordination/p1-source-review-2026-10-19.json')
        $steer=@(& git -C (Join-Path $root 'work/steer-android-port') ls-files --cached --others --exclude-standard)
        if($LASTEXITCODE){throw 'Steer working-tree source inventory failed'}
        foreach($relative in $steer){
            if(!$relative -or $relative -match '(^|/)(test-results|runtime|\.gradle|\.cxx|\.kotlin|outputs|\.git)(/|$)' -or $relative -match '/build/' -or $relative -match 'local\.properties$'){continue}
            if($relative -match '^(src/|build/|tests/.*\.[ch]$|Android\.bp$|LICENSE$|COPYING$)' -or
                $relative -match '^android-vpn/(core/src/|core/build\.gradle\.kts$|core/runtime-dependencies\.json$|integration-helper/|module-contract/|module-spi/|module-apk/src/|primary-adapter/|gradle/|[^/]+\.gradle\.kts$|gradle\.properties$|tests/(?:Host[^/]+\.java|[^/]+\.py|host_[^/]+\.c)$)'){
                $files+='work/steer-android-port/'+$relative
            }
        }
        @($files|ForEach-Object {$_ -replace '\\','/'}|Sort-Object -Unique)
    }finally{Pop-Location}
}
function Manifest {
    foreach($relative in SourceList){
        if($relative.Split('/') -contains '..'){throw 'Source path escape'}
        $file=Get-Item -LiteralPath (Join-Path $root $relative)
        if($file.PSIsContainer -or $file.LinkType){throw 'Ordinary source file required'}
        [pscustomobject]@{path=$relative;sha256=Sha $file.FullName;bytes=$file.Length}
    }
}
$baseline=@()
$prior=$null;$priorInputs=@()
if($RetainFromReceipt){
    $priorPath=[IO.Path]::GetFullPath($RetainFromReceipt)
    if([IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($priorPath)) -ine [IO.Path]::GetFullPath($builds) -or
       [IO.Path]::GetFileName([IO.Path]::GetDirectoryName($priorPath)) -cnotmatch '^p1-whole-[0-9a-f]{32}$' -or [IO.Path]::GetFileName($priorPath) -cne 'receipt.json'){throw 'Exact completed common P1 receipt required'}
    $prior=Get-Content -LiteralPath $priorPath -Raw|ConvertFrom-Json
    $priorManifest=Join-Path ([IO.Path]::GetDirectoryName($priorPath)) 'source-inputs.json'
    if(!$prior.finishedUtc -or !$prior.sourceStable -or (Sha $priorManifest) -cne $prior.sourceManifestSha256){throw 'Retained common source evidence is incomplete'}
    $priorInputs=@(Get-Content -LiteralPath $priorManifest -Raw|ConvertFrom-Json)
    $state.retainedFromReceiptSha256=Sha $priorPath
}
function Retain([string]$name,[string]$pattern){
    if(!$prior -or $prior.phases.$name.state -cne 'PASS'){return $false}
    if($FinalFrameworkRejection -and $name -ceq 'main_affected_models_and_compilation'){return $false}
    if($FinalFixtures -and $name -cin @('main_affected_models_and_compilation','independent_packet_peer_new_cases')){return $false}
    if($RetainSteerPeerCapacity -and $name -ceq 'independent_steer_peer_new_cases'){
        $capacity=[IO.Path]::GetFullPath($RetainSteerPeerCapacity)
        if($capacity -ine (Join-Path $root 'work/ports-test-2026-10-14/p1-peer-capacity-38a631d1914d4efbbd2d8ecf015b1cea/receipt.json')){throw 'Exact already-closed capacity evidence required'}
        $proof=Get-Content -LiteralPath $capacity -Raw|ConvertFrom-Json
        if(!$proof.passed -or $proof.executedCases -ne 1 -or $proof.retainedCases -ne 8 -or (Sha $python) -cne $proof.pythonSha256 -or (Sha $RetainFromReceipt) -cne $proof.wholeReceiptSha256){throw 'Closed capacity proof mismatch'}
        foreach($input in $proof.inputs){if($input.file -cnotin @('p1_owned_peer.py','test_p1_owned_peer.py') -or (Sha (Join-Path $root ('work/steer-android-port/android-vpn/tests/'+$input.file))) -cne $input.sha256){throw 'Closed capacity inputs changed'}}
        if(@($proof.inputs).Count -ne 2 -or (Sha $prior.phases.independent_steer_peer_new_cases.receipt) -cne $proof.priorEightLogSha256){throw 'Old eight-case peer evidence changed'}
        $state.phases[$name]=[ordered]@{state='PASS';receipt=$capacity;sha256=Sha $capacity;tests=9;retainedUnchanged=$true};Save;return $true
    }
    if($DeviceRepair -and $name -cin @('wg_awg_and_managed_kernel_dns_host','external_wg_three_abi_and_adapter')){
        $oldWg=@($priorInputs|Where-Object {$_.path -match $pattern});$freshWg=@($baseline|Where-Object {$_.path -match $pattern})
        if($oldWg.Count -ne $freshWg.Count){return $false}
        for($i=0;$i -lt $oldWg.Count;$i++){if($oldWg[$i].path -cne $freshWg[$i].path -or $oldWg[$i].sha256 -cne $freshWg[$i].sha256){return $false}}
    }
    $row=$prior.phases.$name
    if($name -ceq 'steer_models_and_actual_packaged_rule_sets'){
        if((Sha $row.receipt) -cne $row.sha256){throw 'Retained model receipt changed'}
        $models=Get-Content -LiteralPath $row.receipt -Raw|ConvertFrom-Json
        if(!$models.passed -or !$models.sourceStable -or !$models.inputs){throw 'Retained models incomplete'}
        foreach($input in $models.inputs.PSObject.Properties){
            $relative=$input.Name -replace '\\','/'
            $original=if($relative.StartsWith('host-presets/')){Join-Path $root ('ports/android/app/src/main/assets/presets/'+$relative.Substring(13))}else{Join-Path $root ('work/steer-android-port/android-vpn/'+$relative)}
            if((Sha $original) -cne $input.Value){throw 'Actual compiled model input changed; include its affected checks'}
        }
        if((Sha (Join-Path $java 'bin/javac.exe')) -cne $models.compilerSha256 -or (Sha (Join-Path $java 'bin/java.exe')) -cne $models.javaSha256 -or (Sha $json) -cne $models.testDependencySha256){throw 'Retained model toolchain changed'}
    }else{
        $old=@($priorInputs|Where-Object {$_.path -match $pattern});$fresh=@($baseline|Where-Object {$_.path -match $pattern})
        if(!$old.Count -or $old.Count -ne $fresh.Count){throw 'Retained phase source set changed'}
        $modelRepair=@('ports/android/app/src/main/kotlin/org/mcrf/client/McrfVpnService.kt',
            'ports/android/engines/build.gradle.kts',
            'ports/android/app/src/androidTest/kotlin/org/mcrf/client/ClientPrimaryVpnDeviceChecks.kt',
            'ports/android/app/src/androidTest/kotlin/org/mcrf/client/ClientSteerP1DnsChecks.kt',
            'ports/android/app/src/androidTest/kotlin/org/mcrf/client/ClientSteerVpnDeviceChecks.kt')
        for($i=0;$i -lt $old.Count;$i++){
            if($old[$i].path -cne $fresh[$i].path){throw 'Retained phase source set changed'}
            if($old[$i].sha256 -cne $fresh[$i].sha256 -and !($DeviceRepair -and $name -ceq 'main_affected_models_and_compilation' -and $old[$i].path.Replace('\','/') -cin $modelRepair)){throw 'Retained phase source changed; explicitly include affected checks'}
        }
    }
    $evidence=if($row.receipt){$row.receipt}elseif($name -ceq 'independent_steer_peer_new_cases'){Join-Path $prior.stage 'steer-peer.log'}elseif($name -ceq 'main_affected_models_and_compilation'){
        Join-Path $android ('coordination/build-history/'+[IO.Path]::GetFileName($row.snapshot)+'/build-result.json')
    }else{Join-Path $row.stage 'receipt.json'}
    if($row.sha256){if((Sha $evidence) -cne $row.sha256){throw 'Retained phase receipt changed'}}
    elseif($name -ceq 'main_affected_models_and_compilation'){
        $built=Get-Content -LiteralPath $evidence -Raw|ConvertFrom-Json
        if($built.exitCode -ne 0 -or !$built.sourceStable -or
            (Sha (Join-Path ([IO.Path]::GetDirectoryName($evidence)) 'source-manifest.json')) -cne $built.sourceManifestSha256){throw 'Retained main compilation evidence incomplete'}
        foreach($xml in @($row.tests)+@($row.retainedCases)){if((Sha $xml.xml) -cne $xml.sha256){throw 'Retained main test XML changed'}}
    }
    elseif($name -cne 'independent_steer_peer_new_cases' -or [IO.File]::ReadAllText($evidence) -cnotmatch '(?s)Ran 8 tests in .+\r?\n\r?\nOK\s*$'){throw 'Legacy retained peer run is not complete'}
    $copy=[ordered]@{};foreach($property in $row.PSObject.Properties){$copy[$property.Name]=$property.Value}
    $copy.retainedUnchanged=$true;$state.phases[$name]=$copy;Save
    if($DeviceRepair -and $name -ceq 'main_affected_models_and_compilation'){
        $copy.retainedUnchanged=$false;$copy.retainedPureModelsOnly=$true
        $copy.currentCompilationRequiredIn='common_main_r8_debug_runner_helpers_and_external_modules';Save
    }
    if(!$row.receipt -and $name -ceq 'independent_steer_peer_new_cases'){$copy.receipt=$evidence;$copy.sha256=Sha $evidence;Save}
    if(!$row.receipt -and $name -ceq 'main_affected_models_and_compilation'){$copy.receipt=$evidence;$copy.sha256=Sha $evidence;Save}
    Write-Output "P1_RETAINED=$name"|Out-Host
    return $true
}
function Stable {
    $fresh=@(Manifest)
    if($fresh.Count -ne $baseline.Count){throw 'Whole P1 source set changed'}
    for($i=0;$i -lt $fresh.Count;$i++){if($fresh[$i].path -cne $baseline[$i].path -or $fresh[$i].sha256 -cne $baseline[$i].sha256){throw 'Whole P1 source changed during common verification'}}
}
function Phase([string]$name,[scriptblock]$body){
    $state.phases[$name]=[ordered]@{state='RUNNING';startedUtc=[DateTime]::UtcNow.ToString('o')};Save
    Write-Output "P1_PHASE=$name"
    try{Stable;& $body;Stable;$state.phases[$name].state='PASS'}
    catch{
        $state.phases[$name].state='FAILED_PRESERVED'
        $state.phases[$name].error=$_.Exception.Message
        Write-Output "P1_PHASE_FAILED=$name; original evidence retained, collecting independent phases"
    }
    finally{$state.phases[$name].finishedUtc=[DateTime]::UtcNow.ToString('o');Save}
}
function ReceiptLine([string]$log,[string]$pattern){
    $matches=[regex]::Matches([IO.File]::ReadAllText($log),$pattern)
    if($matches.Count -ne 1){throw 'Exact producer receipt identity missing; inspect retained log'}
    return $matches[0].Groups[1].Value.Trim()
}
$baseSnapshot=$null;$modelEvidence=$null;$wgHost=$null;$wgStage=$null;$steerStage=$null;$peerStage=$null
try{
    $current.jobs.build=$job
    [IO.File]::WriteAllText($currentPath,($current|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
    $baseline=@(Manifest)
    [IO.File]::WriteAllText((Join-Path $stage 'source-inputs.json'),($baseline|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
    $state.sourceManifestSha256=Sha (Join-Path $stage 'source-inputs.json');Save
    if(Retain 'main_affected_models_and_compilation' '^(ports/android/(app/|primary-spi/|primary-adapter/|primary-cores/singbox/|engines/|gradle/|[^/]+\.gradle\.kts$|gradle\.properties$)|ports/(fixtures/|catalogue/)|work/steer-android-port/android-vpn/(module-contract/|integration-helper/))'){
        $baseSnapshot=$state.phases.main_affected_models_and_compilation.snapshot
    }else{Phase 'main_affected_models_and_compilation' {
        $suites=@('PacketRoutingSnapshotTest','PacketRouteEnvelopeTest','PacketManagedDnsPlanTest','PacketDnsWireTest','PacketDnsTransportTest',
            'PacketTunSnapshotTest','PacketIpRoutesTest','PacketPeerProjectionTest','PacketBlockProjectionTest','CompactWireGuardPlanTest','WireGuardProfileTest',
            'ClientPacketFixtureTest','ClientPacketSelectionTest','SteerSnapshotContractTest','SteerTunPlanPolicyTest','ClientSteerStartMetadataTest','ClientSteerFixtureTest','ClientP1OwnerScopePinTest')
        $retainedCases=@();$affected=$suites
        if($FinalFixtures -or $FinalFrameworkRejection){
            if(!$prior){throw 'Final fixtures require preceding common evidence'}
            $mainPattern='^(ports/android/(app/|primary-spi/|primary-adapter/|primary-cores/singbox/|engines/|gradle/|[^/]+\.gradle\.kts$|gradle\.properties$)|ports/(fixtures/|catalogue/)|work/steer-android-port/android-vpn/(module-contract/|integration-helper/))'
            $old=@($priorInputs|Where-Object {$_.path -match $mainPattern});$fresh=@($baseline|Where-Object {$_.path -match $mainPattern})
            if($old.Count -ne $fresh.Count){throw 'Final fixture main input set changed'}
            $allowed=@('ports/android/app/src/main/kotlin/org/mcrf/client/ClientPacketFixture.kt','ports/android/app/src/main/kotlin/org/mcrf/client/ClientSteerFixture.kt',
                'ports/android/app/src/main/kotlin/org/mcrf/client/PacketManagedDnsFixture.kt','ports/android/app/src/test/kotlin/org/mcrf/client/ClientPacketFixtureTest.kt',
                'ports/android/app/src/test/kotlin/org/mcrf/client/ClientSteerFixtureTest.kt','ports/android/app/src/androidTest/kotlin/org/mcrf/client/PacketVpnDeviceChecks.kt',
                'ports/android/app/src/androidTest/kotlin/org/mcrf/client/ClientSteerP1DnsChecks.kt','ports/android/app/src/androidTest/kotlin/org/mcrf/client/ClientSteerVpnDeviceChecks.kt',
                'ports/android/app/src/androidTest/java/org/mcrf/client/DeviceSmokeInstrumentation.java')
            if($FinalFrameworkRejection){$allowed=@('ports/android/app/src/main/kotlin/org/mcrf/client/ClientPacketFixture.kt','ports/android/app/src/main/kotlin/org/mcrf/client/PacketManagedDnsFixture.kt','ports/android/app/src/test/kotlin/org/mcrf/client/ClientPacketFixtureTest.kt')}
            for($i=0;$i -lt $old.Count;$i++){if($old[$i].path -cne $fresh[$i].path -or $old[$i].sha256 -cne $fresh[$i].sha256 -and $old[$i].path -cnotin $allowed){throw 'Unreviewed model input changed in final fixture batch'}}
            $affected=if($FinalFrameworkRejection){@('ClientPacketFixtureTest.frameworkServfailIsOnlyAnExactNegativeOutcomeNeverPositiveDns')}else{@('ClientPacketFixtureTest','ClientSteerFixtureTest')}
            $affectedSuites=if($FinalFrameworkRejection){@()}else{$affected}
            $seen=[Collections.Generic.HashSet[string]]::new();$allSeen=[Collections.Generic.HashSet[string]]::new();$oldAffected=0
            $priorMain=$prior.phases.main_affected_models_and_compilation
            foreach($row in @($priorMain.tests)+@($priorMain.retainedCases)){
                if((Sha $row.xml) -cne $row.sha256){throw 'Closed JVM XML changed'}
                [xml]$xml=Get-Content -LiteralPath $row.xml -Raw
                $cases=if($row.method){@($xml.testsuite.testcase|Where-Object {$_.name -ceq $row.method})}else{@($xml.testsuite.testcase)}
                if(!$cases.Count){throw 'Closed JVM method not found'}
                foreach($case in $cases){
                    if($case.failure -or $case.error -or $case.skipped){throw 'Closed JVM method incomplete'}
                    if(!$allSeen.Add($row.suite+'.'+$case.name)){throw 'Duplicate retained JVM identity'}
                    if($row.suite -cin $affectedSuites){$oldAffected++;continue}
                    if($seen.Add($row.suite+'.'+$case.name)){$retainedCases+=[ordered]@{suite=$row.suite;method=$case.name;xml=$row.xml;sha256=$row.sha256;retainedUnchanged=$true}}
                }
            }
            $expectedClosed=if($FinalFrameworkRejection){129}else{127}
            if($allSeen.Count -ne $expectedClosed -or $retainedCases.Count+$oldAffected -ne $expectedClosed -or !$FinalFrameworkRejection -and $oldAffected -lt 2){throw 'Unexpected lost closed JVM cases'}
            $state.phases.main_affected_models_and_compilation.retainedCases=$retainedCases
            $state.phases.main_affected_models_and_compilation.retainedSourceManifestSha256=$state.retainedFromReceiptSha256
        }
        if($RetainJvmArchive){
            $archive=[IO.Path]::GetFullPath($RetainJvmArchive)
            if([IO.Path]::GetDirectoryName($archive) -ine (Join-Path $android 'coordination/build-history') -or [IO.Path]::GetFileName($archive) -cnotmatch '^android-integration-[0-9a-f]{32}$'){throw 'Exact archived common JVM evidence required'}
            $oldBuild=Get-Content -LiteralPath (Join-Path $archive 'build-result.json') -Raw|ConvertFrom-Json
            $manifest=Join-Path $archive 'source-manifest.json'
            if(!$oldBuild.sourceStable -or (Sha $manifest) -cne $oldBuild.sourceManifestSha256){throw 'Archived source evidence incomplete'}
            $changed=@();foreach($row in @(Get-Content -LiteralPath $manifest -Raw|ConvertFrom-Json)){
                if((Sha (Join-Path $root $row.path)) -cne $row.sha256){$changed+=$row.path -replace '\\','/'}
            }
            if($changed.Count -ne 1 -or $changed[0] -cne 'ports/android/app/src/test/kotlin/org/mcrf/client/PacketBlockProjectionTest.kt'){throw 'Retained JVM production or unrelated fixture inputs changed'}
            $affected=@();foreach($suite in $suites){
                $xmlPath=Join-Path $archive "ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.$suite.xml"
                [xml]$xml=Get-Content -LiteralPath $xmlPath -Raw
                if([int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped -or [int]$xml.testsuite.tests -lt 1){throw 'Incomplete retained JVM result'}
                foreach($case in $xml.testsuite.testcase){
                    if($case.failure){$affected+="$suite.$($case.name)"}
                    else{$retainedCases+=[ordered]@{suite=$suite;method=$case.name;xml=$xmlPath;sha256=Sha $xmlPath;retainedUnchanged=$true}}
                }
            }
            if($affected.Count -ne 1 -or $affected[0] -cne 'PacketBlockProjectionTest.allBlockedDoesNotInventPeerRoutesAndCannotMutateOutput' -or $retainedCases.Count -ne 126){throw 'Exact failed-only JVM continuation required'}
            $state.phases.main_affected_models_and_compilation.retainedCases=$retainedCases
            $state.phases.main_affected_models_and_compilation.retainedSourceManifestSha256=Sha $manifest
        }
        $tasks=@(':app:testDebugUnitTest');foreach($suite in $affected){$tasks+=@('--tests',"org.mcrf.client.$suite")}
        $tasks+=@(':app:compileReleaseKotlin',':app:compileDebugAndroidTestKotlin',':app:compileDebugAndroidTestJavaWithJavac','--continue','--no-daemon')
        $log=Join-Path $stage 'main-verification.log'
        & (Join-Path $android 'integration-build.ps1') -Task $tasks -BuildScratchRoot $builds *> $log
        $script:baseSnapshot=ReceiptLine $log '(?m)^INTEGRATION_SNAPSHOT=(.+)$'
        $state.phases.main_affected_models_and_compilation.snapshot=$baseSnapshot
        # XML is independent of console PASS text; retain each exact suite's
        # evidence so a later unrelated packaging failure need not rerun it.
        $rows=@();foreach($suite in @($affected|ForEach-Object {($_ -split '\.')[0]}|Select-Object -Unique)){
            $xmlRelative="ports/android/app/build/test-results/testDebugUnitTest/TEST-org.mcrf.client.$suite.xml"
            $xmlPath=Join-Path $baseSnapshot $xmlRelative
            if(!(Test-Path -LiteralPath $xmlPath)){$xmlPath=Join-Path $android ('coordination/build-history/'+[IO.Path]::GetFileName($baseSnapshot)+'/'+$xmlRelative)}
            [xml]$xml=Get-Content -LiteralPath $xmlPath -Raw
            if([int]$xml.testsuite.tests -lt 1 -or [int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped){throw 'Affected common JVM suite failed/incomplete'}
            $rows+=[ordered]@{suite=$suite;tests=[int]$xml.testsuite.tests;xml=$xmlPath;sha256=Sha $xmlPath}
        }
        $state.phases.main_affected_models_and_compilation.tests=$rows
    }}
    if(Retain 'steer_models_and_actual_packaged_rule_sets' '^(work/steer-android-port/android-vpn/(core/src/main/java/|integration-helper/|tests/(Host[^/]+\.java|run_host_ipv6_routes\.py)$)|ports/android/app/src/main/assets/presets/)'){
        $modelEvidence=$state.phases.steer_models_and_actual_packaged_rule_sets.receipt
    }else{Phase 'steer_models_and_actual_packaged_rule_sets' {
        $log=Join-Path $stage 'steer-models.log'
        $modelExtra=@();if($RetainModelPrefixReceipt){$modelExtra=@('--retain-prefix-receipt',$RetainModelPrefixReceipt)}
        & $python (Join-Path $root 'work/steer-android-port/android-vpn/tests/run_host_ipv6_routes.py') --java-home $java --json-jar $json @modelExtra *> $log
        if($LASTEXITCODE){throw 'Common Steer models failed; original log retained'}
        $script:modelEvidence=ReceiptLine $log '(?m)^P1_IPV6_MODEL_PASS;RECEIPT=(.+)$'
        $state.phases.steer_models_and_actual_packaged_rule_sets.receipt=$modelEvidence
        $state.phases.steer_models_and_actual_packaged_rule_sets.sha256=Sha $modelEvidence
    }}
    if(!(Retain 'independent_steer_peer_new_cases' '^work/steer-android-port/android-vpn/tests/(p1_owned_peer|test_p1_owned_peer)\.py$')){Phase 'independent_steer_peer_new_cases' {
        $frozenRoot=Join-Path $stage 'peer-source'
        $relativeFiles=@('work/steer-android-port/android-vpn/tests/p1_owned_peer.py','work/steer-android-port/android-vpn/tests/test_p1_owned_peer.py',
            'ports/android/coordination/current.json','ports/android/coordination/p1-progress-2026-10-17.json')
        foreach($relative in $relativeFiles){
            $destination=Join-Path $frozenRoot $relative
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))|Out-Null
            [IO.File]::Copy((Join-Path $root $relative),$destination,$false)
            if((Sha $destination) -cne (Sha (Join-Path $root $relative))){throw 'Independent peer source changed while freezing'}
        }
        & $python -B (Join-Path $frozenRoot 'work/steer-android-port/android-vpn/tests/test_p1_owned_peer.py') -v *> (Join-Path $stage 'steer-peer.log')
        if($LASTEXITCODE){throw 'Independent Steer peer new cases failed; original log retained'}
        foreach($relative in $relativeFiles){if((Sha (Join-Path $frozenRoot $relative)) -cne (Sha (Join-Path $root $relative))){throw 'Independent peer source changed during common checks'}}
        $state.phases.independent_steer_peer_new_cases.receipt=Join-Path $stage 'steer-peer.log'
        $state.phases.independent_steer_peer_new_cases.sha256=Sha (Join-Path $stage 'steer-peer.log')
    }}
    if(Retain 'wg_awg_and_managed_kernel_dns_host' '^ports/android/transports/wg-backend/[^/]+$'){
        $wgHost=$state.phases.wg_awg_and_managed_kernel_dns_host.receipt
    }else{Phase 'wg_awg_and_managed_kernel_dns_host' {
        $log=Join-Path $stage 'wg-host.log'
        $extra=@{};if($DeviceRepair){if(!$prior){throw 'Device repair requires exact prior common evidence'};$extra.RetainFrom=$prior.phases.wg_awg_and_managed_kernel_dns_host.receipt}
        & (Join-Path $android 'transports/wg-backend/test-host.ps1') @extra *> $log
        $script:wgHost=ReceiptLine $log '(?m)^WG_HOST_PASS=\d+;RECEIPT=(.+)$'
        $state.phases.wg_awg_and_managed_kernel_dns_host.receipt=$wgHost
        $state.phases.wg_awg_and_managed_kernel_dns_host.sha256=Sha $wgHost
    }}
    if(!(Retain 'independent_packet_peer_new_cases' '^ports/android/WireGuardFixtures/')){Phase 'independent_packet_peer_new_cases' {
        $log=Join-Path $stage 'packet-peer.log'
        $peerExtra=@{};if($FinalFixtures){$peerExtra.RetainManagedEvidence=$prior.phases.independent_packet_peer_new_cases.stage}
        & (Join-Path $android 'WireGuardFixtures/Build-PacketAuditPeer.ps1') -P1Managed @peerExtra *> $log
        $script:peerStage=ReceiptLine $log '(?m)^PACKET_AUDIT_PEER_STAGE=(.+)$'
        $state.phases.independent_packet_peer_new_cases.stage=$peerStage
        $state.phases.independent_packet_peer_new_cases.sha256=Sha (Join-Path $peerStage 'receipt.json')
    }}
    if(Retain 'external_wg_three_abi_and_adapter' '^ports/android/(transports/wg-backend/|primary-spi/src/)'){
        $wgStage=$state.phases.external_wg_three_abi_and_adapter.stage
        $nativeReceipt=Get-Content -LiteralPath (Join-Path $wgStage 'receipt.json') -Raw|ConvertFrom-Json
        if((Sha (Join-Path $wgStage 'wg-backend.aar')) -cne $nativeReceipt.aarSha256){throw 'Retained WG native artifact changed'}
    }elseif($state.phases.wg_awg_and_managed_kernel_dns_host.state -ceq 'PASS'){Phase 'external_wg_three_abi_and_adapter' {
        $log=Join-Path $stage 'wg-native.log'
        & (Join-Path $android 'transports/wg-backend/build-android.ps1') -HostEvidence $wgHost *> $log
        $script:wgStage=ReceiptLine $log '(?m)^WG_ANDROID_CANDIDATE=(.+)$'
        & (Join-Path $android 'transports/wg-backend/verify-adapter.ps1') -Stage ([IO.Path]::GetRelativePath((Join-Path $android 'transports/wg-backend'),$wgStage)) *> (Join-Path $stage 'wg-adapter.log')
        $state.phases.external_wg_three_abi_and_adapter.stage=$wgStage
        $state.phases.external_wg_three_abi_and_adapter.sha256=Sha (Join-Path $wgStage 'receipt.json')
    }}
    if(Retain 'external_steer_two_abi_and_source_bound_stage' '^work/steer-android-port/(?!android-vpn/tests/(?:p1_owned_peer|test_p1_owned_peer)\.py$)'){
        $steerStage=$state.phases.external_steer_two_abi_and_source_bound_stage.stage
        $nativeReceipt=Get-Content -LiteralPath (Join-Path $steerStage 'receipt.json') -Raw|ConvertFrom-Json
        foreach($artifact in $nativeReceipt.modules.steer.artifacts){if((Sha (Join-Path $steerStage ('steer/'+$artifact.file))) -cne $artifact.sha256){throw 'Retained Steer artifact changed'}}
        foreach($fixture in $nativeReceipt.nativeFixtures){if((Sha (Join-Path $steerStage ('steer/'+$fixture.file))) -cne $fixture.sha256){throw 'Retained native fixture changed'}}
    }elseif($state.phases.steer_models_and_actual_packaged_rule_sets.state -ceq 'PASS'){Phase 'external_steer_two_abi_and_source_bound_stage' {
        $log=Join-Path $stage 'steer-native.log'
        & $python (Join-Path $root 'work/steer-android-port/android-vpn/tests/build_p1_candidate.py') --java-home $java --android-sdk 'C:/Users/dieonfire/AppData/Local/Android/Sdk' --gradle-cache (Join-Path $root 'work/toolchains/gradle-home') --model-evidence $modelEvidence *> $log
        if($LASTEXITCODE){throw 'External P1 Steer compilation failed; exact frozen log retained'}
        $script:steerStage=ReceiptLine $log '(?m)^P1_STEER_CANDIDATE=(.+)$'
        $state.phases.external_steer_two_abi_and_source_bound_stage.stage=$steerStage
        $state.phases.external_steer_two_abi_and_source_bound_stage.sha256=Sha (Join-Path $steerStage 'receipt.json')
    }}
    if($state.phases.main_affected_models_and_compilation.state -ceq 'PASS' -and
        $state.phases.external_wg_three_abi_and_adapter.state -ceq 'PASS' -and
        $state.phases.external_steer_two_abi_and_source_bound_stage.state -ceq 'PASS'){Phase 'common_main_r8_debug_runner_helpers_and_external_modules' {
        # New build inputs include the completed external AARs. The JVM phase
        # above is CLOSED and byte-bound; it is deliberately NOT rerun here.
        $tasks=@(':app:assembleRelease',':app:assembleDebug',':app:assembleDebugAndroidTest',':p1-owner-probe:assembleDebug',':p1-owner-peer:assembleDebug',':engines:assembleSteerDebug',':wg-module:assembleDebug','--continue')
        $log=Join-Path $stage 'common-packaging.log'
        & (Join-Path $android 'integration-build.ps1') -Task $tasks -PrimaryCoreStage ([IO.Path]::GetRelativePath($root,$steerStage)) -WgBackendStage ([IO.Path]::GetRelativePath($root,$wgStage)) -P1OwnerFixture -P1SteerCandidate -P1WgCandidate -BuildScratchRoot $builds *> $log
        $finalSnapshot=ReceiptLine $log '(?m)^INTEGRATION_SNAPSHOT=(.+)$'
        $state.phases.common_main_r8_debug_runner_helpers_and_external_modules.snapshot=$finalSnapshot
        $state.phases.common_main_r8_debug_runner_helpers_and_external_modules.artifactsSha256=Sha (Join-Path $finalSnapshot 'p1-owner-artifacts.json')
        $parsers=@();foreach($relative in @('ports/android/integration-device-checks.ps1','ports/android/integration-packet-phone.ps1','ports/android/integration-p1-steer.ps1')){
            $tokens=$null;$errors=$null
            [void][System.Management.Automation.Language.Parser]::ParseFile((Join-Path $finalSnapshot $relative),[ref]$tokens,[ref]$errors)
            if($errors.Count){throw 'Frozen device coordinator syntax errors; no phone access'}
            $parsers+=[ordered]@{path=$relative;sha256=Sha (Join-Path $finalSnapshot $relative);errors=0}
        }
        $state.phases.common_main_r8_debug_runner_helpers_and_external_modules.frozenCoordinatorSyntax=$parsers
    }}
    Stable;$state.sourceStable=$true
    if(@($state.phases.Values|Where-Object {$_.state -cne 'PASS'}).Count){throw 'Common verification collected failures; repair related source as one batch, preserve closed unchanged evidence'}
    if(!$state.phases.Contains('common_main_r8_debug_runner_helpers_and_external_modules')){throw 'Dependent common packaging was not executed'}
    $state.state='COMMON_HOST_BUILD_PASS_ANDROID_NATIVE_TRAFFIC_ACCEPTANCE_PENDING'
}catch{
    $state.state='FAILED_PRESERVED_NO_AUTORETRY';$state.error=$_.Exception.Message
    throw
}finally{
    $state.finishedUtc=[DateTime]::UtcNow.ToString('o');Save
    $fresh=Get-Content -LiteralPath $currentPath -Raw|ConvertFrom-Json
    if($fresh.jobs.build -ceq $job){$fresh.jobs.build=$null;[IO.File]::WriteAllText($currentPath,($fresh|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))}
    if($held){$mutex.ReleaseMutex()};$mutex.Dispose()
    Write-Output "P1_COMMON=$($state.state);RECEIPT=$(Join-Path $stage 'receipt.json')"
}
