param([string]$BuildScratchRoot='D:/MCRF/Builds/Android',[string]$RetainedLegacyTransportStage='',
    [hashtable]$RetainedCarrierStages=@{},[string]$RetainedCsqtt25AffectedStage='',
    [string]$RetainedExpansionJvmSnapshot='',[hashtable]$RetainedFailureStages=@{},
    [hashtable]$CsqttHistoricalSources=@{},[string]$RetainedManagementDiagnosticsStage='',[string]$RetainedHistoricalTunnelCheckStage='',
    [string]$RetainedSwg25ProvisioningStage='')
$ErrorActionPreference='Stop'
$android=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$root=[IO.Path]::GetFullPath((Join-Path $android '../..'))
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation native-build
$current=Get-Content -LiteralPath "$android/coordination/current.json" -Raw|ConvertFrom-Json
if($current.activeBatch -cne 'p2-expansion-2026-10-21.json' -or @($current.jobs.PSObject.Properties|Where-Object {$_.Value}).Count){throw 'Expanded common batch requires idle coordinator; never duplicate an active run'}
$lease=[IO.File]::Open((Join-Path ([IO.Path]::GetFullPath($BuildScratchRoot)) 'p2-expansion-common.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
$stage=Join-Path ([IO.Path]::GetFullPath($BuildScratchRoot)) ('p2-expansion-common-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage|Out-Null
$readiness="$android/coordination/p2-expansion-2026-10-21.json"
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
$readySha=Hash $readiness
$receipt=[ordered]@{state='PREPARING';authority='human-20261021-expanded-p2-whole-source-before-common-verification';
    readinessSha256=$readySha;controllerSha256=Hash $PSCommandPath;steps=@();common=$null;installed=$false;published=$false}
function Save{[IO.File]::WriteAllText("$stage/receipt.json",($receipt|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))}
function Invoke-Step([string]$name,[string]$script,[hashtable]$parameters,[string]$marker=''){
    if((Hash $readiness) -cne $readySha){throw 'Whole source gate changed; do not continue this common batch'}
    $log="$stage/$name.log";$result=$null
    & $script @parameters 2>&1|Tee-Object -FilePath $log|ForEach-Object {
        $line="$_"
        if($marker -and $line.StartsWith($marker,[StringComparison]::Ordinal)){$result=$line.Substring($marker.Length)}
        if($line -match '^(P2_SOURCE|CSQTT_LEGACY_STAGE|CSQTT_SOURCE_FROZEN|QWDTT_CAPTCHA_STAGE|QWDTT_CAPTCHA_COMPILED|Integration snapshot):'){Write-Output $line}
    }
    $receipt.steps+=@{name=$name;logSha256=Hash $log;scriptSha256=Hash $script};Save
    if($marker -and !$result){throw "Completed $name did not provide its exact result marker"}
    if($result){return $result}
}
Save
Write-Output "EXPANDED_P2_COMMON=$stage"
try{
    if($CsqttHistoricalSources.Count -and (($CsqttHistoricalSources.Keys|Sort-Object) -join ',') -cne 'csqtt20,csqtt215'){throw 'Historical sources must include both distinct CSQTT families'}
    if((Get-Content $readiness -Raw|ConvertFrom-Json).phase -match 'CSQTT_GENERATIONS' -and $CsqttHistoricalSources.Count -ne 2){throw 'The generations package must not omit historical modules'}
    # Freeze the complete related source set before any producer. No user/app
    # data, private runtime files or generated binary outputs enter this list.
    Push-Location $root
    try{
        $files=@(& rg --files ports/android/app/src ports/android/transports/swg ports/android/engines ports/android/maintenance -g '!**/build/**' -g '!**/*.exe' -g '!**/.gradle/**' -g '!**/__pycache__/**' -g '!**/*.pyc')
        if($LASTEXITCODE){throw 'Unable to inventory common expanded source'}
    }finally{Pop-Location}
    $files+=@('ports/android/app/build.gradle.kts','ports/android/integration-build.ps1','ports/android/optional-cores/upstream-throne/script/prepare_csqtt.py','ports/android/coordination/p2-expansion-jvm-affected-2026-10-21.txt')
    $inputs=@($files|Sort-Object -Unique|ForEach-Object {
        $file=Get-Item -LiteralPath (Join-Path $root $_)
        if($file.LinkType -or $file.PSIsContainer){throw 'Ordinary common source inputs required'}
        if($file.Extension -in @('.kt','.kts','.java','.ps1','.py','.go','.rs','.xml')){
            if([Array]::IndexOf([IO.File]::ReadAllBytes($file.FullName),[byte]0) -ge 0){throw "NUL byte in source input; preserve and recover before building: $_"}
        }
        @{path=$_;sha256=Hash $file.FullName}
    })
    [IO.File]::WriteAllText("$stage/source-manifest.json",($inputs|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
    $receipt.sourceManifestSha256=Hash "$stage/source-manifest.json";Save
    function Stable{
        if((Hash $readiness) -cne $readySha){throw 'Expanded source gate changed during common preparation'}
        foreach($input in $inputs){if((Hash (Join-Path $root $input.path)) -cne $input.sha256){throw "Common source changed: $($input.path)"}}
    }
    if($RetainedLegacyTransportStage){
        $legacy=[IO.Path]::GetFullPath($RetainedLegacyTransportStage)
        if([IO.Path]::GetDirectoryName($legacy) -cne [IO.Path]::GetFullPath('D:/MCRF/Builds/Android') -or
            [IO.Path]::GetFileName($legacy) -cnotmatch '^csqtt-legacy-captcha-[0-9a-f]{32}$' -or (Get-Item -LiteralPath $legacy).LinkType){throw 'Exact owned completed CS transport stage required'}
        $compiled=Get-Content -LiteralPath "$legacy/receipt.json" -Raw|ConvertFrom-Json
        if($compiled.state -cne 'COMPILED_NOT_EXECUTED' -or !$compiled.sourceStable -or !$compiled.transportOnly -or
            $compiled.accountOptionsContract -ne 1 -or $compiled.revision -cne '71712b07dbabe16fa43f5a248dc69e1ab9f3a270' -or
            $compiled.privateAdapterSha256 -cne (Hash "$android/optional-cores/upstream-throne/script/prepare_csqtt.py") -or
            $compiled.helperSha256 -cne (Hash "$android/transports/swg/mcrf_captcha_waiters.rs")){throw 'Retained CS transport has changed source or is incomplete'}
        $manifest=Get-Content -LiteralPath "$legacy/source-manifest.json" -Raw|ConvertFrom-Json
        foreach($input in $manifest){
            if([IO.Path]::IsPathRooted($input.path) -or '..' -cin ($input.path -split '[/\\]')){throw 'Unsafe retained source path'}
            $file=Get-Item -LiteralPath (Join-Path "$legacy/source" $input.path)
            if($file.LinkType -or $file.PSIsContainer -or (Hash $file.FullName) -cne $input.sha256){throw 'Retained compiled CS source changed'}
        }
        if(@($compiled.artifacts).Count -ne 3){throw 'Retained CS transport ABI set incomplete'}
        foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){
            $row=@($compiled.artifacts|Where-Object {$_.abi -ceq $abi})
            if($row.Count -ne 1 -or (Hash "$legacy/candidates/$abi/libcsqtt_transport.so") -cne $row[0].sha256){throw 'Retained CS ELF changed'}
        }
        $receipt.retainedLegacy=[ordered]@{stage=$legacy;receiptSha256=Hash "$legacy/receipt.json";newExecution=$false};Save
    }else{
        $legacy=Invoke-Step 'legacy-source' "$android/transports/swg/prepare-csqtt-waiters.ps1" @{} 'CSQTT_LEGACY_STAGE='
        Stable
        Invoke-Step 'legacy-native' "$android/transports/swg/build-csqtt-waiters.ps1" @{Stage=$legacy}|Out-Null
    }
    Stable
    $prepared=@{}
    foreach($kind in @('csqtt','csqtt25','qwdtt','wdtt')){
        $prepared[$kind]=Invoke-Step "$kind-source" "$android/transports/swg/build.ps1" @{PrepareExpansion=$true;ExpansionKind=$kind} 'P2_SOURCE='
    }
    Stable
    # Reference APK is data only: never installed/executed. Extract exactly the
    # pinned supplied arm64 ELF; the producer repeats its bytes/digest checks.
    $apk='D:/MCRF/Builds/Android/reference-csqtt25-swgpanel-20261021/CSQTT-2.5.1-arm64-v8a.apk'
    if((Hash $apk) -cne '733962c7a09f6d811797ca09c03ac5040ec21b0d6623ea21201a2c85762e7c12'){throw 'Exact swg-panel reference APK required'}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive=[IO.Compression.ZipFile]::OpenRead($apk)
    try{
        $entry=$archive.GetEntry('lib/arm64-v8a/libclient.so')
        if(!$entry -or $entry.Length -ne 9306144){throw 'Exact supplied transport entry required'}
        $transport="$stage/libcsqtt25_transport.so"
        $source=$entry.Open();$destination=[IO.File]::Open($transport,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try{$source.CopyTo($destination)}finally{$destination.Dispose();$source.Dispose()}
    }finally{$archive.Dispose()}
    if((Hash $transport) -cne '9dad51554bafb2e6cf386a48cc278fb08c434a78ece40baff00fb51f203b237c'){throw 'Supplied transport digest mismatch'}
    $native=@{}
    $retainedOrigins=@{}
    foreach($kind in @('csqtt','csqtt25','qwdtt','wdtt')){
        if($RetainedCarrierStages.ContainsKey($kind)){
            $oldStage=[IO.Path]::GetFullPath($RetainedCarrierStages[$kind])
            if([IO.Path]::GetDirectoryName($oldStage) -cne [IO.Path]::GetFullPath('D:/MCRF/Builds/Android') -or [IO.Path]::GetFileName($oldStage) -cnotmatch "^$kind-captcha-[0-9a-f]{32}$" -or (Get-Item -LiteralPath $oldStage).LinkType){throw 'Exact retained native carrier stage required'}
            $old=Get-Content -LiteralPath "$oldStage/receipt.json" -Raw|ConvertFrom-Json
            $fresh=Get-Content -LiteralPath (Join-Path $prepared[$kind] 'prepared-expansion-source.json') -Raw|ConvertFrom-Json
            if($old.kind -cne $kind -or $old.state -cne 'COMPILED_NOT_EXECUTED' -or !$old.sourceStable){throw 'Retained carrier did not finish successfully'}
            $oldInputs=Get-Content -LiteralPath "$oldStage/source-manifest.json" -Raw|ConvertFrom-Json
            $left=(@($oldInputs|ForEach-Object {$_.path.Replace('\','/')+'='+$_.sha256})|Sort-Object) -join "`n"
            $right=(@($fresh.inputs|ForEach-Object {$_.path.Replace('\','/')+'='+$_.sha256})|Sort-Object) -join "`n"
            if($left -cne $right){throw 'Do not retain a carrier with different freshly prepared source inputs'}
            foreach($artifact in $old.artifacts){
                $file=Get-Item -LiteralPath $artifact.file
                if($file.LinkType -or $file.PSIsContainer -or !$file.FullName.StartsWith($oldStage+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Hash $file.FullName) -cne $artifact.sha256){throw 'Retained native artifact changed'}
            }
            $native[$kind]=$oldStage;$retainedOrigins[$kind]=Join-Path $prepared[$kind] 'prepared-expansion-source.json'
            $receipt["retained-$kind"]=@{stage=$oldStage;receiptSha256=Hash "$oldStage/receipt.json";newExecution=$false}
            Save;continue
        }
        $options=@{SourceSnapshot=$prepared[$kind];Kind=$kind}
        if($RetainedFailureStages.ContainsKey($kind)){$options.RetainedFailureStage=$RetainedFailureStages[$kind]}
        if($kind -ceq 'csqtt'){$options.LegacyTransportStage=$legacy}
        if($kind -ceq 'csqtt25'){$options.TransportPath=$transport}
        if($kind -ceq 'csqtt25' -and $RetainedCsqtt25AffectedStage){$options.RetainedCsqtt25AffectedStage=$RetainedCsqtt25AffectedStage}
        $native[$kind]=Invoke-Step "$kind-native" "$android/transports/swg/build-captcha-native.ps1" $options 'QWDTT_CAPTCHA_STAGE='
        Stable
    }
    if((Get-Content $readiness -Raw|ConvertFrom-Json).phase -cin @('CSQTT_GENERATIONS_HISTORICAL_IPC_WHOLE_SOURCE_READY','CSQTT_GENERATIONS_SWG25_ACCORDION_WHOLE_SOURCE_READY','CSQTT_GENERATIONS_SWG_SUBNET_WHOLE_SOURCE_READY')){
        if($RetainedHistoricalTunnelCheckStage){
            $accepted=[IO.Path]::GetFullPath($RetainedHistoricalTunnelCheckStage)
            if([IO.Path]::GetDirectoryName($accepted) -ine [IO.Path]::GetFullPath('D:/MCRF/Builds/Android') -or [IO.Path]::GetFileName($accepted) -cnotmatch '^historical-tunnel-checks-[0-9a-f]{32}$' -or (Get-Item $accepted).LinkType){throw 'Exact owned historical adapter test origin required'}
            $proof=Get-Content "$accepted/receipt.json" -Raw|ConvertFrom-Json
            if($proof.state -cne 'PASS' -or $proof.cases -ne 4 -or !$proof.executed -or $proof.sourceSha256 -cne (Hash "$android/transports/swg/mcrf_historical_tunnel.rs") -or $proof.logSha256 -cne (Hash "$accepted/test.log")){throw 'Historical local-event adapter proof changed'}
            $receipt.retainedHistoricalTunnelChecks=@{origin=$accepted;cases=4;newExecution=$false};Save
        }else{
            Invoke-Step 'historical-tunnel-new' "$PSScriptRoot/Run-HistoricalTunnelChecks.ps1" @{}
        }
        Stable
    }
    $historical=@{}
    foreach($kind in @('csqtt20','csqtt215')){
        if(!$CsqttHistoricalSources.ContainsKey($kind)){continue}
        $historicalSource=[IO.Path]::GetFullPath($CsqttHistoricalSources[$kind])
        $historicalReceipt=Get-Content -LiteralPath "$historicalSource/receipt.json" -Raw|ConvertFrom-Json
        if([IO.Path]::GetDirectoryName($historicalSource) -ine [IO.Path]::GetFullPath('D:/MCRF/Builds/Android') -or [IO.Path]::GetFileName($historicalSource) -cnotmatch '^csqtt-historical-[0-9a-f]{32}$' -or (Get-Item -LiteralPath $historicalSource).LinkType -or $historicalReceipt.engine -cne $kind){throw 'Exact owned historical source identity required'}
        if($historicalReceipt.state -ceq 'COMPILED_NOT_EXECUTED'){
            if(!$historicalReceipt.sourceStable -or $historicalReceipt.adapterSha256 -cne (Hash "$android/transports/swg/prepare-csqtt-historical.py") -or @($historicalReceipt.artifacts).Count -ne 3){throw 'Historical source/adapter changed; cannot retain its compiled artifacts'}
            foreach($row in (Get-Content "$historicalSource/source-manifest.json" -Raw|ConvertFrom-Json)){
                if([IO.Path]::IsPathRooted($row.path) -or '..' -cin ($row.path -split '[/\\]')){throw 'Unsafe historical source path'}
                $file=Get-Item -LiteralPath "$historicalSource/source/$($row.path)"
                if($file.LinkType -or $file.PSIsContainer -or (Hash $file.FullName) -cne $row.sha256){throw 'Retained historical source changed'}
            }
            foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){
                $rows=@($historicalReceipt.artifacts|Where-Object {$_.abi -ceq $abi})
                $file=Get-Item -LiteralPath "$historicalSource/candidates/$abi/libcsqtt_transport.so"
                if($rows.Count -ne 1 -or $file.LinkType -or $file.Length -ne $rows[0].bytes -or (Hash $file.FullName) -cne $rows[0].sha256 -or !$rows[0].elfPIE -or $rows[0].alignment -ne 16384){throw 'Retained historical binary identity changed'}
            }
            $receipt["retained-$kind"]=@{stage=$historicalSource;receiptSha256=Hash "$historicalSource/receipt.json";newExecution=$false};Save
        }elseif($historicalReceipt.state -ceq 'SOURCE_PREPARED'){
            Invoke-Step "$kind-native" "$android/transports/swg/Build-CsqttHistoricalNative.ps1" @{Stage=$historicalSource}|Out-Null
        }else{throw 'Historical producer incomplete; do not duplicate a running/failed producer'}
        Stable
        $historical[$kind]=Invoke-Step "$kind-package" "$android/transports/swg/Prepare-CsqttHistoricalPackage.ps1" @{NativeStage=$historicalSource;WrapperStage=$native.csqtt} 'HISTORICAL_PACKAGE_STAGE='
        Stable
    }
    $receipt.state='BUILDING_COMMON_APP';$receipt.native=$native;$receipt.historical=$historical;Save
    if($historical.Count){
        if($RetainedManagementDiagnosticsStage){
            $diagnostics=[IO.Path]::GetFullPath($RetainedManagementDiagnosticsStage)
            if($diagnostics -ine [IO.Path]::GetFullPath('D:/MCRF/Builds/Android/p2-expansion-common-beda2e35c1e9427cb21d51fba3b0459e') -or (Get-Item -LiteralPath $diagnostics).LinkType){throw 'Exact original diagnostics origin required'}
            $old=Get-Content "$diagnostics/receipt.json" -Raw|ConvertFrom-Json
            $row=@($old.steps|Where-Object {$_.name -ceq 'management-diagnostics-new'})
            $log="$diagnostics/management-diagnostics-new.log"
            if($old.state -cne 'FAILED' -or $row.Count -ne 1 -or (Hash $log) -cne $row[0].logSha256){throw 'Original passed diagnostics log identity changed'}
            $content=[IO.File]::ReadAllText($log)
            if($content -notmatch 'Ran 3 tests' -or $content -notmatch '(?m)^OK\s*$' -or $content -notmatch 'MANAGEMENT_DIAGNOSTICS_NEW=3'){throw 'Original three diagnostics checks did not pass'}
            $manifest=Get-Content "$diagnostics/source-manifest.json" -Raw|ConvertFrom-Json
            foreach($path in @('ports/android/app/src/main/assets/server-management-swg.py','ports/android/app/src/test/ServerManagementDiagnosticsTest.py')){
                $original=@($manifest|Where-Object {$_.path.Replace('\','/') -ceq $path})
                if($original.Count -ne 1 -or (Hash "$root/$path") -cne $original[0].sha256){throw 'Tested production/assertion source changed; do not retain diagnostics'}
            }
            $receipt.retainedManagementDiagnostics=@{origin=$diagnostics;logSha256=Hash $log;count=3;newExecution=$false;parentState='FAILED_PRESERVED_GENERATED_BYTECODE_DRIFT'};Save
        }else{
            Invoke-Step 'management-diagnostics-new' "$PSScriptRoot/Run-ManagementDiagnosticsChecks.ps1" @{}
        }
        Stable
    }
    if((Get-Content $readiness -Raw|ConvertFrom-Json).phase -ceq 'DEPLOY_CALL_PAYLOAD_WHOLE_SOURCE_READY_FINAL_AFFECTED_CHECKS'){
        Invoke-Step 'deploy-call-python-new' "$PSScriptRoot/Run-DeployCallProvisioningChecks.ps1" @{}
        Stable
    }
    if((Get-Content $readiness -Raw|ConvertFrom-Json).phase -ceq 'CSQTT_GENERATIONS_SWG25_ACCORDION_WHOLE_SOURCE_READY'){
        if($RetainedSwg25ProvisioningStage){
            $accepted=[IO.Path]::GetFullPath($RetainedSwg25ProvisioningStage)
            if($accepted -ine [IO.Path]::GetFullPath('D:/MCRF/Builds/Android/p2-expansion-common-ddb60893f2f341bc97ad9438283b814d') -or (Get-Item $accepted).LinkType){throw 'Exact first SWG25 provisioning execution required'}
            $proof=Get-Content "$accepted/receipt.json" -Raw|ConvertFrom-Json
            $row=@($proof.steps|Where-Object {$_.name -ceq 'swg25-provisioning-new'})
            $log="$accepted/swg25-provisioning-new.log"
            if($proof.state -cne 'FAILED' -or $row.Count -ne 1 -or (Hash $log) -cne $row[0].logSha256){throw 'Preserved provisioning execution changed'}
            $content=[IO.File]::ReadAllText($log)
            if($content -notmatch 'Ran 4 tests' -or $content -notmatch '(?m)^OK\s*$' -or $content -notmatch 'SWG25_PROVISIONING_NEW=4'){throw 'Four new provisioning cases did not pass'}
            $manifest=Get-Content "$accepted/source-manifest.json" -Raw|ConvertFrom-Json
            foreach($path in @('ports/android/app/src/main/kotlin/org/mcrf/client/AndroidDeployScripts.kt','ports/android/app/src/test/AndroidDeploySwg25ProvisioningTest.py')){
                $source=@($manifest|Where-Object {$_.path.Replace('\','/') -ceq $path})
                if($source.Count -ne 1 -or (Hash "$root/$path") -cne $source[0].sha256){throw 'Accepted provisioning production/assertions changed'}
            }
            $receipt.retainedSwg25Provisioning=@{origin=$accepted;count=4;newExecution=$false;logSha256=Hash $log};Save
        }else{
            Invoke-Step 'swg25-provisioning-new' "$PSScriptRoot/Run-Swg25ProvisioningChecks.ps1" @{}
        }
        Stable
    }
    if((Get-Content $readiness -Raw|ConvertFrom-Json).phase -ceq 'CSQTT_GENERATIONS_SWG_SUBNET_WHOLE_SOURCE_READY'){
        Invoke-Step 'swg-subnet-provisioning-new' "$PSScriptRoot/Run-SwgSubnetProvisioningChecks.ps1" @{}
        Stable
    }
    $tasks=@(':app:testDebugUnitTest',':app:assembleDebug',':app:assembleDebugAndroidTest',':engines:assembleCsqttDebug',':engines:assembleCsqtt25Debug',':engines:assembleQwdttDebug',':engines:assembleWdttDebug')
    if($historical.Count){$tasks+=@(':engines:assembleCsqtt20Debug',':engines:assembleCsqtt215Debug')}
    $receipt.common=Invoke-Step 'common-app' "$android/integration-build.ps1" @{P2Expansion=$true;Task=$tasks;CsqttLegacyStage=$native.csqtt;Csqtt25Stage=$native.csqtt25;
        SwgNativeStages=@{qwdtt=$native.qwdtt;wdtt=$native.wdtt};CsqttHistoricalStages=$historical;RetainedCarrierOrigins=$retainedOrigins;BuildScratchRoot=$BuildScratchRoot;RetainedExpansionJvmSnapshot=$RetainedExpansionJvmSnapshot} 'INTEGRATION_SNAPSHOT='
    Stable
    $receipt.state='COMMON_BUILD_COMPLETED_DEVICE_UNVERIFIED';Save
}catch{
    $receipt.state='FAILED';Save
    throw
}finally{$lease.Dispose()}
