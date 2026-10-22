param([string]$Ndk='', [switch]$HostTest, [switch]$CaptchaHostTest, [switch]$Csqtt25HostTest, [switch]$WdttHostTest, [ValidateSet('all','captcha-deadline')][string]$WdttHostScope='all',
      [switch]$PrepareP2,[ValidateSet('csqtt','csqtt25')][string]$P2SourceKind='csqtt',
      [switch]$PrepareExpansion,[ValidateSet('csqtt','csqtt25','qwdtt','wdtt')][string]$ExpansionKind='csqtt')
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot '../../maintenance/Assert-HumanBatchReady.ps1') -Operation $(if($PrepareP2 -or $PrepareExpansion){'native-build'}elseif($HostTest -or $CaptchaHostTest -or $Csqtt25HostTest -or $WdttHostTest){'test'}else{'build'})
$taskCoordination=Join-Path $PSScriptRoot '../../coordination'
$taskLive=Get-Content -LiteralPath (Join-Path $taskCoordination 'current.json') -Raw|ConvertFrom-Json
if(($taskLive.activeBatch -ceq 'p2-expansion-2026-10-21.json') -ne [bool]$PrepareExpansion){throw 'Expanded P2 requires its distinct source-only preparation recipe'}
if($PrepareExpansion -and ($PrepareP2 -or $HostTest -or $CaptchaHostTest -or $Csqtt25HostTest -or $WdttHostTest)){throw 'Source preparation must not run a legacy test or compile branch'}
if($taskLive.activeBatch -ceq 'p2-progress-2026-10-20.json' -and !$PrepareP2){throw 'P2 uses a source-only CS snapshot and exact affected native recipe, not legacy full-suite/live-JNI builds'}
if($PrepareP2){
    if($taskLive.activeBatch -cne 'p2-progress-2026-10-20.json' -or $HostTest -or $CaptchaHostTest -or $Csqtt25HostTest -or $WdttHostTest){throw 'Exact source-only P2 preparation required, no compiler/test flags'}
    & (Join-Path $PSScriptRoot '../../maintenance/Assert-HumanBatchReady.ps1') -Operation native-build
}
if($WdttHostScope -ne 'all' -and !$WdttHostTest){throw 'Narrow WDTT scope requires host-test mode; no native build implied'}
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$taskGo=Join-Path $taskRoot 'work/toolchains/go1.26.8/go/bin/go.exe'
$taskOldGoPath=$env:GOPATH
$taskOldGoCache=$env:GOCACHE
$taskOldGoTmp=$env:GOTMPDIR
$env:GOPATH=Join-Path $taskRoot 'work/toolchains/go-home'
$env:GOCACHE=Join-Path $taskRoot 'work/toolchains/go-build-cache'
$env:GOTMPDIR=Join-Path $taskRoot 'work/toolchains/go-tmp'
New-Item -ItemType Directory -Path $env:GOTMPDIR -Force|Out-Null
$taskLock=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../transport-lock.json') -Raw | ConvertFrom-Json
$taskCaptchaAdapter=Join-Path $taskRoot 'work/android-upstream/qthrone-v1.4.2-beta.2/scripts/prepare_android_captcha.py'
if(!(Test-Path -LiteralPath $taskCaptchaAdapter) -or (Get-FileHash -LiteralPath $taskCaptchaAdapter -Algorithm SHA256).Hash -ne 'D1022A35026AF70633400DA86A0952A6F1954D5B2DE39F6ECA81C90264BD08A6'){throw 'Reviewed Android captcha adapter missing or changed'}
if(!$Ndk){$Ndk=Join-Path $taskRoot 'work/toolchains/ndk-r27/android-ndk-r27'}
$taskCompiler=Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$taskOldPath=$env:PATH
$taskCandidate=Join-Path $taskRoot ('work/ports-test-2026-10-14/swg-mobile-'+[Guid]::NewGuid().ToString('N'))
if(Test-Path -LiteralPath $taskCandidate){throw 'Fresh SWG source snapshot already exists'}
New-Item -ItemType Directory -Path $taskCandidate | Out-Null
if($HostTest -or $CaptchaHostTest -or $Csqtt25HostTest -or $WdttHostTest){Write-Output "SWG_HOST_STAGE=$taskCandidate"}
try {
 foreach($taskKind in $(if($PrepareExpansion){if($ExpansionKind -ceq 'wdtt'){@('wdtt')}else{@('qwdtt')}}elseif($WdttHostTest){@('wdtt')}elseif($PrepareP2 -or $CaptchaHostTest -or $Csqtt25HostTest){@('qwdtt')}else{@('qwdtt','wdtt')})){
    $taskInfo=$taskLock.$taskKind
    $taskPristine=Join-Path $taskRoot "work/android-upstream/$($taskInfo.checkout)"
    if(($WdttHostTest -or ($PrepareExpansion -and $ExpansionKind -ceq 'wdtt')) -and !(Test-Path -LiteralPath $taskPristine)){$taskPristine=Join-Path $taskRoot 'work/linux-upstream/wdtt'}
    if(!(Test-Path -LiteralPath $taskPristine)){
        if($PrepareP2 -or $PrepareExpansion){throw 'Existing pinned pristine P2 input required; no implicit source fetch'}
        & git clone --filter=blob:none --no-checkout "https://github.com/$($taskInfo.repository).git" $taskPristine
        if($LASTEXITCODE -ne 0){throw 'SWG source fetch failed'}
        & git -C $taskPristine checkout --detach $taskInfo.commit
        if($LASTEXITCODE -ne 0){throw 'SWG source checkout failed'}
    }
    if((& git -C $taskPristine rev-parse HEAD) -ne $taskInfo.commit -or ((& git -C $taskPristine status --porcelain | Out-String).Trim())){throw 'SWG pristine source revision mismatch or local edits'}
    # Never merge a pristine revision into an earlier patched build directory.
    # Repeated runs must start from independent inputs, not apply the patch twice.
    $taskSource=Join-Path $taskCandidate $taskKind
    New-Item -ItemType Directory -Path $taskSource | Out-Null
    Get-ChildItem -LiteralPath $taskPristine -Force | Where-Object Name -ne '.git' | Copy-Item -Destination $taskSource -Recurse -Force
    Push-Location $taskSource
    try {
        & git apply --recount (Join-Path $PSScriptRoot "$taskKind-mobile.patch")
        if($LASTEXITCODE -ne 0){throw "SWG patch failed: $taskKind"}
        if($taskKind -eq 'qwdtt'){
            & python $taskCaptchaAdapter (Join-Path $taskSource 'qwdtt')
            if($LASTEXITCODE -ne 0){throw 'SWG captcha adaptation failed'}
            & git apply --recount (Join-Path $PSScriptRoot 'qwdtt-captcha.patch')
            if($LASTEXITCODE -ne 0){throw 'SWG exact CAPTCHA waiter adaptation failed'}
            & git apply --recount (Join-Path $PSScriptRoot 'csqtt-account-options.patch')
            if($LASTEXITCODE -ne 0){throw 'CSQTT private linked-account/fingerprint adaptation failed'}
            & git apply --recount (Join-Path $PSScriptRoot 'qwdtt-fingerprint.patch')
            if($LASTEXITCODE -ne 0){throw 'QWDTT TLS/browser fingerprint adaptation failed'}
            foreach($taskFingerprintFile in @('mcrf_qwdtt_fingerprint.go','mcrf_qwdtt_fingerprint_test.go')){
                Copy-Item -LiteralPath (Join-Path $PSScriptRoot $taskFingerprintFile) -Destination (Join-Path $taskSource "qwdtt/$taskFingerprintFile")
            }
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'mcrf_csqtt_account_options_test.go') -Destination (Join-Path $taskSource 'qwdtt/mcrf_csqtt_account_options_test.go')
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'mcrf_captcha.go') -Destination (Join-Path $taskSource 'qwdtt/mcrf_captcha.go') -Force
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'mcrf_captcha_test.go') -Destination (Join-Path $taskSource 'qwdtt/mcrf_captcha_test.go') -Force
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'mcrf_probe_test.go') -Destination (Join-Path $taskSource 'qwdtt/mcrf_probe_test.go') -Force
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'mcrf_qwdtt_test.go') -Destination (Join-Path $taskSource 'qwdtt/mcrf_qwdtt_test.go') -Force
            if($Csqtt25HostTest -or ($PrepareP2 -and $P2SourceKind -ceq 'csqtt25') -or ($PrepareExpansion -and $ExpansionKind -ceq 'csqtt25')){
                & git apply --recount (Join-Path $PSScriptRoot 'csqtt25-bridge.patch')
                if($LASTEXITCODE -ne 0){throw 'Separate CSQTT 2.5 bridge patch failed'}
                Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'mcrf_csqtt25.go') -Destination (Join-Path $taskSource 'qwdtt/mcrf_csqtt25.go') -Force
                Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'mcrf_csqtt25_test.go') -Destination (Join-Path $taskSource 'qwdtt/mcrf_csqtt25_test.go') -Force
                $task25Internal=Join-Path $taskSource 'qwdtt/internal/csqtt25'
                New-Item -ItemType Directory -Path $task25Internal | Out-Null
                Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'csqtt25') -Filter '*.go' -File | Copy-Item -Destination $task25Internal
            }
            & git apply --recount (Join-Path $PSScriptRoot 'csqtt-process-group.patch')
            if($LASTEXITCODE -ne 0){throw 'CSQTT exact-owned process-group handshake patch failed'}
            & git apply --recount (Join-Path $PSScriptRoot 'csqtt-failure-detail.patch')
            if($LASTEXITCODE -ne 0){throw 'CSQTT sanitized child failure forwarding patch failed'}
            & git apply --recount (Join-Path $PSScriptRoot 'csqtt-tunnel-frame.patch')
            if($LASTEXITCODE -ne 0){throw 'CSQTT versioned tunnel frame parsing patch failed'}
            & git apply --recount (Join-Path $PSScriptRoot 'csqtt-hash-pool-tests.patch')
            if($LASTEXITCODE -ne 0){throw 'CSQTT complete hash pool expectation patch failed'}
            foreach($taskFailureFile in @('mcrf_child_failure.go','mcrf_child_failure_test.go')){
                Copy-Item -LiteralPath (Join-Path $PSScriptRoot $taskFailureFile) -Destination (Join-Path $taskSource "qwdtt/$taskFailureFile")
            }
            foreach($taskGroupFile in @('mcrf_process_group.go','mcrf_process_group_linux.go','mcrf_process_group_other.go','mcrf_process_group_test.go','mcrf_process_group_linux_test.go','mcrf_child_input.go','mcrf_child_input_test.go')){
                Copy-Item -LiteralPath (Join-Path $PSScriptRoot $taskGroupFile) -Destination (Join-Path $taskSource "qwdtt/$taskGroupFile")
            }
        }else{
            & git apply --recount (Join-Path $PSScriptRoot 'wdtt-address-family.patch')
            if($LASTEXITCODE -ne 0){throw 'WDTT dual-family SOCKS patch failed'}
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'mcrf_wdtt.go') -Destination (Join-Path $taskSource 'go_client/mcrf_wdtt.go') -Force
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'mcrf_wdtt_test.go') -Destination (Join-Path $taskSource 'go_client/mcrf_wdtt_test.go') -Force
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'mcrf_wdtt_family_test.go') -Destination (Join-Path $taskSource 'go_client/mcrf_wdtt_family_test.go')
        }
    }finally{Pop-Location}
    if($PrepareP2 -or $PrepareExpansion){
        $taskPreparedModule=Join-Path $taskSource $(if($taskKind -ceq 'wdtt'){'go_client'}else{'qwdtt'})
        $taskPreparedInputs=@(Get-ChildItem -LiteralPath $taskPreparedModule -Recurse -File|Where-Object {$_.Extension -eq '.go' -or $_.Name -in @('go.mod','go.sum')}|Sort-Object FullName|ForEach-Object {
            if($_.LinkType){throw 'Redirected P2 source refused'}
            [ordered]@{path=[IO.Path]::GetRelativePath($taskPreparedModule,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
        })
        $taskCanonicalInputs=@{}
        foreach($input in Get-ChildItem -LiteralPath $PSScriptRoot -Recurse -File|Where-Object {$_.Extension -in @('.go','.patch','.ps1','.json')}){
            if($input.LinkType){throw 'Redirected canonical preparation input refused'}
            $taskCanonicalInputs[[IO.Path]::GetRelativePath($PSScriptRoot,$input.FullName)]=(Get-FileHash -LiteralPath $input.FullName).Hash.ToLowerInvariant()
        }
        $taskPrepared=[ordered]@{priority=2;kind=$(if($PrepareExpansion){$ExpansionKind}else{$P2SourceKind});source=$taskCandidate;sourceStable=$true;state='PREPARED_NOT_COMPILED';sourceReadySha256=(Get-FileHash -LiteralPath (Join-Path $taskCoordination $(if($PrepareExpansion){'p2-expansion-2026-10-21.json'}else{'p2-progress-2026-10-20.json'}))).Hash.ToLowerInvariant();inputs=$taskPreparedInputs;canonicalInputs=$taskCanonicalInputs;preparerSha256=(Get-FileHash -LiteralPath $PSCommandPath).Hash.ToLowerInvariant();upstreamCommit=$taskInfo.commit}
        if($PrepareExpansion){$taskPrepared.authority='human-20261021-expanded-p2-whole-source-before-common-verification'}
        [IO.File]::WriteAllText((Join-Path $taskCandidate $(if($PrepareExpansion){'prepared-expansion-source.json'}else{'prepared-p2-source.json'})),($taskPrepared|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
        Write-Output "P2_SOURCE=$taskCandidate"
        continue
    }
    $taskModule=Join-Path $taskSource $(if($taskKind -eq 'qwdtt'){'qwdtt'}else{'go_client'})
    Push-Location $taskModule
    try {
        Remove-Item Env:GOOS,Env:GOARCH,Env:GOARM,Env:CC -ErrorAction SilentlyContinue
        $env:CGO_ENABLED='0'
        & $taskGo mod download; if($LASTEXITCODE -ne 0){throw 'SWG dependency fetch failed'}
        & $taskGo mod verify; if($LASTEXITCODE -ne 0){throw 'SWG dependency checksum failed'}
        if($WdttHostTest){
            # Preserve the owned test executable: Windows can retain an image
            # handle after exit. Never retry a denied deletion of a TEMP image.
            $taskHostExe=Join-Path $taskCandidate 'wdtt-host-tests.exe'
            & $taskGo test -c '-ldflags=-checklinkname=0' -o $taskHostExe .
            if($LASTEXITCODE -ne 0){throw 'WDTT private host test compilation failed'}
            $taskHostPattern=if($WdttHostScope -eq 'captcha-deadline'){'^TestMcrfWdttDeadline'}else{'^(TestMcrf|TestCaptchaResult|TestCaptchaRequest|TestCaptchaSolveStage)'}
            & $taskHostExe '-test.v' '-test.timeout=30s' "-test.run=$taskHostPattern" | Tee-Object -FilePath (Join-Path $taskCandidate 'wdtt-host-tests.log')
            if($LASTEXITCODE -ne 0){throw 'WDTT private CAPTCHA/probe checks failed'};continue
        }
        if($Csqtt25HostTest){& $taskGo test '-ldflags=-checklinkname=0' -v -run '^(Test25|TestRuntime|TestCSQTT|TestMcrfCaptcha|TestMcrfProcessGroup|TestMcrfChildInput)' ./...;if($LASTEXITCODE -ne 0){throw 'Separate CSQTT 2.5 bridge host checks failed'};continue}
        if($CaptchaHostTest){& $taskGo test '-ldflags=-checklinkname=0' -v -run '^TestMcrfCaptcha' ./...;if($LASTEXITCODE -ne 0){throw 'Exact CAPTCHA waiter host tests failed'};continue}
        if($HostTest){& $taskGo test '-ldflags=-checklinkname=0' -run '^(TestMcrf|TestSOCKS|TestRaw|TestTURNConnectHonorsCancelledContext|TestCSQTT)' ./...;if($LASTEXITCODE -ne 0){throw 'SWG host tests failed'};continue}
        $env:PATH=$taskCompiler+';'+$taskOldPath;$env:GOOS='android';$env:CGO_ENABLED='1'
        foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
            switch($taskAbi){'arm64-v8a'{$env:GOARCH='arm64';$taskTriple='aarch64-linux-android29'}'armeabi-v7a'{$env:GOARCH='arm';$env:GOARM='7';$taskTriple='armv7a-linux-androideabi29'}'x86_64'{$env:GOARCH='amd64';$taskTriple='x86_64-linux-android29'}}
            $env:CC="clang --target=$taskTriple"
            $taskOutput=Join-Path $PSScriptRoot "../../engines/src/main/jniLibs/$taskAbi/lib$taskKind.so"
            & $taskGo build -trimpath -buildmode=pie '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o $taskOutput .
            if($LASTEXITCODE -ne 0){throw "SWG native build failed: $taskKind $taskAbi"}
        }
    }finally{Pop-Location}
    $taskLicense=Join-Path $taskPristine $(if($taskKind -eq 'qwdtt'){'qwdtt/LICENSE'}else{'LICENSE'})
    Copy-Item -LiteralPath $taskLicense -Destination (Join-Path $PSScriptRoot "../../engines/src/main/assets/licenses/$taskKind-LICENSE") -Force
 }
}finally{$env:PATH=$taskOldPath;$env:GOPATH=$taskOldGoPath;$env:GOCACHE=$taskOldGoCache;$env:GOTMPDIR=$taskOldGoTmp;Remove-Item Env:GOOS,Env:GOARCH,Env:GOARM,Env:CGO_ENABLED,Env:CC -ErrorAction SilentlyContinue}
