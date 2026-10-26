param([string]$Ndk='', [switch]$TestServer, [switch]$HostOnly, [string]$Commit='', [string]$Version='', [switch]$StageOnly, [switch]$ScriptUpdaterCandidate, [switch]$MailruAckCandidate, [switch]$FreshCoreCandidate, [string]$RetainStartupEvidenceFrom='')
$ErrorActionPreference='Stop'
$taskProject=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$taskGo=Join-Path $taskProject 'work/toolchains/go1.26.8/go/bin/go.exe'
$env:GOPATH=Join-Path $taskProject 'work/toolchains/go-home'
$taskLock=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../transport-lock.json') -Raw | ConvertFrom-Json
if(!$Commit){$Commit=$taskLock.openflux.commit}
if(!$Version){$Version=$taskLock.openflux.version}
if($Commit -notmatch '^[0-9a-f]{40}$' -or $Version -notmatch '^v[0-9]+\.[0-9]+\.[0-9]+$'){throw 'Invalid source identity'}
if(!$StageOnly -and ($Commit -ne $taskLock.openflux.commit -or $Version -ne $taskLock.openflux.version)){throw 'Unpinned candidate must use StageOnly; verified promotion is separate'}
if($FreshCoreCandidate -and (!$StageOnly -or $ScriptUpdaterCandidate -or $MailruAckCandidate -or $Version -ne 'v0.13.0' -or $Commit -ne '4f9c786cdd1d9fcd0c133e2e0fec0c5a12522610')){throw 'Fresh core requires isolated exact v0.13.0 StageOnly; old v0.7.3 overlays cannot be reused'}
if($Version -eq 'v0.13.0' -and !$FreshCoreCandidate){throw 'Fresh core requires its explicit source-bound candidate workflow'}
if($ScriptUpdaterCandidate -and (!$StageOnly -or $Version -ne 'v0.7.3' -or $Commit -ne 'f12e981629a18c8c01007d59ac002254c02972bb')){throw 'Script updater capability is restricted to the isolated exact v0.7.3 candidate; no production build/promotion'}
if($MailruAckCandidate -and (!$StageOnly -or $ScriptUpdaterCandidate -or $Version -ne 'v0.7.3' -or $Commit -ne 'f12e981629a18c8c01007d59ac002254c02972bb')){throw 'Mailru ACK candidate requires exact v0.7.3 StageOnly and no script-update overlay'}
$taskFreshSources=@{}
if($FreshCoreCandidate){
    # Capture BEFORE any copy/test/build, not after the artifact was produced.
    foreach($taskName in @('mcrf_proxy.go','main.go','main_test.go','staged-v013/bridge_review_test.go','staged-v013/captcha_ticket.go','staged-v013/own_http_ticket.go','staged-v013/own_http_ticket_review_test.go','staged-v013/captcha_owner.go','staged-v013/captcha_owner_review_test.go','staged-v013/own_http_admission.go','staged-v013/own_http_exchange.go','staged-v013/own_http_exchange_review_test.go','staged-v013/own_http_admission_review_test.go','staged-v013/captcha_ticket_review_test.go','staged-v013/script_boundary_review_test.go','staged-v013/provider_state.go','staged-v013/provider_state_review_test.go','staged-v013/verification_ipc_review_test.go','staged-v013/reply_worker_review_test.go','staged-v013/http_gateway.go','staged-v013/http_gateway_review_test.go','staged-v013/http_worker.go','staged-v013/http_worker_review_test.go','staged-v013/document_worker.go','staged-v013/document_worker_review_test.go','staged-v013/session_observation.go','staged-v013/session_observation_review_test.go','staged-v013/session_pipeline_review_test.go','staged-v013/mobile_observation.go','staged-v013/observation_finish.go','staged-v013/observation_finish_review_test.go','staged-script/script_update.go','staged-script/script_update_test.go','staged-script/script_lock_unix.go','staged-script/script_lock_windows.go','staged-v013/deep-diagnostic/recorder.go','staged-v013/deep-diagnostic/recorder_review_test.go','staged-v013/deep-diagnostic/session_owner.go','staged-v013/deep-diagnostic/session_pipeline_review_test.go','staged-v013/deep-diagnostic/raw_owner.go','staged-v013/deep-diagnostic/lower_pipeline_review_test.go','staged-v013/deep-diagnostic/mailru_ack.go','staged-v013/deep-diagnostic/mailru_ack_review_test.go','staged-v013/deep-diagnostic/session-hook-recipe.json','build.ps1')){
        $taskFreshSources[$taskName]=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $taskName)).Hash.ToLowerInvariant()
    }
}
$taskPristine=Join-Path $taskProject "work/android-upstream/openflux-core-$Version"
if(!(Test-Path -LiteralPath $taskPristine)){
    & git clone --filter=blob:none --no-checkout "https://github.com/$($taskLock.openflux.repository).git" $taskPristine
    if($LASTEXITCODE -ne 0){throw 'OpenFlux source fetch failed'}
    & git -C $taskPristine checkout --detach $Commit
    if($LASTEXITCODE -ne 0){throw 'OpenFlux source checkout failed'}
}
if((& git -C $taskPristine rev-parse HEAD) -ne $Commit){throw 'OpenFlux source revision mismatch'}
if((& git -C $taskPristine status --porcelain | Out-String).Trim()){throw 'Pristine OpenFlux checkout has local edits'}
$taskSource=Join-Path $taskProject ('work/ports-test-2026-10-14/openflux-build-'+$Version+'-'+[guid]::NewGuid().ToString('N'))
$taskCandidate=Join-Path $taskSource 'verified-native'
New-Item -ItemType Directory -Path $taskSource -Force | Out-Null
Get-ChildItem -LiteralPath $taskPristine -Force | Where-Object Name -ne '.git' | Copy-Item -Destination $taskSource -Recurse -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'mcrf_proxy.go') -Destination (Join-Path $taskSource 'mobile/mcrf_proxy.go') -Force
$taskCommand=Join-Path $taskSource 'mobile/cmd/mcrf-openflux';$taskExit=Join-Path $taskSource 'mobile/cmd/mcrf-test-exit'
New-Item -ItemType Directory -Path $taskCommand,$taskExit -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'main.go') -Destination (Join-Path $taskCommand 'main.go') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'main_test.go') -Destination (Join-Path $taskCommand 'main_test.go') -Force
if($FreshCoreCandidate){
    # Final human-authorized package: apply exact fresh-only recipe in disposable
    # source; never the pristine checkout or historical0.7 overlays.
    $taskDeepRoot=Join-Path $PSScriptRoot 'staged-v013/deep-diagnostic'
    $taskDeep=Get-Content -LiteralPath (Join-Path $taskDeepRoot 'session-hook-recipe.json') -Raw|ConvertFrom-Json
    if($taskDeep.upstreamCommit -cne $Commit){throw 'Fresh deep recipe identity mismatch'}
    foreach($taskFile in $taskDeep.files){
        $taskTarget=Join-Path $taskSource $taskFile.path
        if((Get-FileHash -LiteralPath $taskTarget).Hash.ToLowerInvariant() -cne $taskFile.sha256){throw 'Fresh deep recipe source hash mismatch'}
        $taskText=[IO.File]::ReadAllText($taskTarget).Replace("`r`n","`n")
        foreach($taskHook in $taskFile.replacements){
            if([regex]::Matches($taskText,[regex]::Escape($taskHook.old)).Count -ne $taskHook.expectedMatches){throw "Fresh deep recipe anchor mismatch: $($taskFile.path)"}
            $taskText=$taskText.Replace($taskHook.old,$taskHook.new)
        }
        [IO.File]::WriteAllText($taskTarget,$taskText,[Text.UTF8Encoding]::new($false))
    }
    foreach($taskCopy in $taskDeep.copies){
        $taskDestination=[IO.Path]::GetFullPath((Join-Path $taskSource $taskCopy.destination))
        if(!$taskDestination.StartsWith($taskSource+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Deep overlay destination outside disposable source'}
        New-Item -ItemType Directory -Path (Split-Path $taskDestination) -Force|Out-Null
        Copy-Item -LiteralPath (Join-Path $taskDeepRoot $taskCopy.source) -Destination $taskDestination -Force
    }
    foreach($taskName in @('captcha_owner.go','captcha_owner_review_test.go')){Copy-Item -LiteralPath (Join-Path $PSScriptRoot "staged-v013/$taskName") -Destination (Join-Path $taskSource "transport/manager/mcrf_$taskName") -Force}
    $taskManagerPath=Join-Path $taskSource 'transport/manager/manager.go'
    if((Get-FileHash -LiteralPath $taskManagerPath).Hash.ToLowerInvariant() -ne '7b13909d0c00f6601ffd8cc8b42e9f3367b599029da9c1f25aa4e3eb34808e63'){throw 'Fresh manager owner-hook source identity mismatch'}
    $taskManagerText=[IO.File]::ReadAllText($taskManagerPath)
    foreach($taskHook in @('captchaNotifier CaptchaNotifier','m.NotifyCaptcha(name, url, html, reason)')){if([regex]::Matches($taskManagerText,[regex]::Escape($taskHook)).Count -ne 1){throw 'Fresh manager owner-hook count mismatch'}}
    $taskManagerText=$taskManagerText.Replace('captchaNotifier CaptchaNotifier',"captchaNotifier CaptchaNotifier`n`tmcrfCaptchaNotifier McrfCaptchaNotifier").Replace('m.NotifyCaptcha(name, url, html, reason)','m.mcrfNotifyCaptcha(name, raw, url, html, reason)')
    if([regex]::Matches($taskManagerText,[regex]::Escape('e.Raw = raw')).Count -ne 1){throw 'Fresh manager raw/provider commit hook mismatch'}
    $taskManagerText=$taskManagerText.Replace('e.Raw = raw','mcrfReplaceRawLocked(e,raw)')
    $taskProviderPattern='(?ms)^func \(m \*Manager\) (?:FetchCookiesFor|ApplyCookiesFor)\([^\r\n]*\{.*?^}'
    if([regex]::Matches($taskManagerText,$taskProviderPattern).Count -ne 2){throw 'Fresh manager provider snapshot hook mismatch'}
    $taskManagerText=[regex]::Replace($taskManagerText,$taskProviderPattern,[Text.RegularExpressions.MatchEvaluator]{param($taskMatch)
        $taskMethod=$taskMatch.Value.Replace('e.Provider','provider')
        if([regex]::Matches($taskMethod,[regex]::Escape('m.mu.RUnlock()')).Count -ne 1){throw 'Fresh manager provider unlock hook mismatch'}
        $taskMethod.Replace('m.mu.RUnlock()',"var provider CookieProvider`n`tif ok {provider=e.Provider}`n`tm.mu.RUnlock()")
    })
    [IO.File]::WriteAllText($taskManagerPath,$taskManagerText,[Text.UTF8Encoding]::new($false))
    foreach($taskName in @('own_http_admission.go','own_http_admission_review_test.go')){Copy-Item -LiteralPath (Join-Path $PSScriptRoot "staged-v013/$taskName") -Destination (Join-Path $taskSource "transport/script/mcrf_$taskName") -Force}
    foreach($taskName in @('own_http_exchange.go','own_http_exchange_review_test.go')){Copy-Item -LiteralPath (Join-Path $PSScriptRoot "staged-v013/$taskName") -Destination (Join-Path $taskSource "transport/script/mcrf_$taskName") -Force}
    $taskHttpServerPath=Join-Path $taskSource 'transport/script/host_httpserver.go'
    if((Get-FileHash -LiteralPath $taskHttpServerPath).Hash.ToLowerInvariant() -ne 'c82ce15ea48bb07aa44f785bb971abb5cc8fe09bbd32025fcbd387aa0a3d3670'){throw 'Fresh script HTTP wait source identity mismatch'}
    $taskHttpServerText=[IO.File]::ReadAllText($taskHttpServerPath)
    if([regex]::Matches($taskHttpServerText,[regex]::Escape('res := <-done')).Count -ne 1){throw 'Fresh script HTTP context hook mismatch'}
    $taskHttpServerText=$taskHttpServerText.Replace('res := <-done',"res,completed := mcrfAwaitHTTPResult(r.Context(),done)`n`tif !completed {http.Error(w, `"verification cancelled`", http.StatusRequestTimeout);return}")
    [IO.File]::WriteAllText($taskHttpServerPath,$taskHttpServerText,[Text.UTF8Encoding]::new($false))
    foreach($taskName in @('session_observation.go','session_observation_review_test.go','session_pipeline_review_test.go')){Copy-Item -LiteralPath (Join-Path $PSScriptRoot "staged-v013/$taskName") -Destination (Join-Path $taskSource "transport/mcrf_$taskName") -Force}
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-v013/mobile_observation.go') -Destination (Join-Path $taskSource 'mobile/mcrf_observation.go') -Force
    foreach($taskName in @('observation_finish.go','observation_finish_review_test.go')){Copy-Item -LiteralPath (Join-Path $PSScriptRoot "staged-v013/$taskName") -Destination (Join-Path $taskCommand $taskName) -Force}
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-v013/captcha_ticket.go') -Destination (Join-Path $taskSource 'mobile/mcrf_captcha_ticket.go') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-v013/captcha_ticket_review_test.go') -Destination (Join-Path $taskSource 'mobile/mcrf_captcha_ticket_review_test.go') -Force
    foreach($taskName in @('own_http_ticket.go','own_http_ticket_review_test.go')){Copy-Item -LiteralPath (Join-Path $PSScriptRoot "staged-v013/$taskName") -Destination (Join-Path $taskSource "mobile/mcrf_$taskName") -Force}
    $taskCaptchaPath=Join-Path $taskSource 'mobile/captcha.go'
    if((Get-FileHash -LiteralPath $taskCaptchaPath).Hash.ToLowerInvariant() -ne '9a71567d79f9ac787e5d001a33eaf94e4f957616ff68be10a03befd056df71a3'){throw 'Fresh captcha source identity mismatch'}
    $taskCaptchaText=[IO.File]::ReadAllText($taskCaptchaPath)
    $taskCaptchaPattern='(?m)^([\t ]*)(captcha\.(?:url(?:,[^\r\n=]*)?|key,\s*captcha\.url[^\r\n=]*|apply)\s*=)'
    if([regex]::Matches($taskCaptchaText,$taskCaptchaPattern).Count -ne 12){throw 'Fresh captcha generation hook count mismatch'}
    $taskCaptchaText=[regex]::Replace($taskCaptchaText,$taskCaptchaPattern,('${1}mcrfCaptchaGeneration++'+"`n"+'${1}mcrfRetireVerificationTicket()'+"`n"+'${1}${2}'))
    $taskRawOwnerHook='exchanger, _ := raw.(transport.CookieExchanger)'
    $taskSessionOwnerHook='m.SetCaptchaNotifier(func(name, url, html, reason string) {'
    $taskSessionApplyHook='captcha.apply = func(jar map[string]string) error { return m.ApplyCookiesFor(name, jar) }'
    $taskRemoteApplyHook='captcha.apply = func(jar map[string]string) error { return m.OfferCookies(name, jar) }'
    $taskSessionInitHook='captcha.key, captcha.url, captcha.html, captcha.reason, captcha.apply = "", "", "", "", nil'
    foreach($taskHook in @($taskRawOwnerHook,$taskSessionOwnerHook,$taskSessionApplyHook,$taskRemoteApplyHook,$taskSessionInitHook)){if([regex]::Matches($taskCaptchaText,[regex]::Escape($taskHook)).Count -ne 1){throw 'Fresh captcha exact-owner hook mismatch'}}
    $taskCaptchaText=$taskCaptchaText.Replace($taskRawOwnerHook,$taskRawOwnerHook+"`n`tmcrfCaptchaOwnerCurrent=nil; mcrfCaptchaOwnHTTP=nil`n`tif own,ok:=raw.(*script.ScriptTransport);ok {mcrfCaptchaOwnHTTP=own.McrfCaptureOwnHTTP}")
    $taskCaptchaText=$taskCaptchaText.Replace($taskSessionInitHook,$taskSessionInitHook+"`n`tmcrfCaptchaOwnerCurrent=nil; mcrfCaptchaOwnHTTP=nil")
    $taskCaptchaText=$taskCaptchaText.Replace($taskSessionOwnerHook,"m.McrfSetCaptchaNotifier(func(name string, raw transport.Transport, url, html, reason string) {`n`t`tif !m.McrfRawCurrent(name,raw) {return}`n`t`texchanger,_:=raw.(transport.CookieExchanger)")
    $taskCaptchaText=$taskCaptchaText.Replace($taskSessionApplyHook,"mcrfCaptchaOwnerCurrent=func()bool{return m.McrfRawCurrent(name,raw)}`n`t`tmcrfCaptchaOwnHTTP=nil`n`t`tif own,ok:=raw.(*script.ScriptTransport);ok {mcrfCaptchaOwnHTTP=own.McrfCaptureOwnHTTP}`n`t`tcaptcha.apply = nil; if exchanger!=nil {captcha.apply=exchanger.ApplyCookies}")
    $taskCaptchaText=$taskCaptchaText.Replace($taskRemoteApplyHook,"mcrfCaptchaOwnerCurrent=nil; mcrfCaptchaOwnHTTP=nil`n`t`t"+$taskRemoteApplyHook)
    [IO.File]::WriteAllText($taskCaptchaPath,$taskCaptchaText,[Text.UTF8Encoding]::new($false))
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-v013/bridge_review_test.go') -Destination (Join-Path $taskSource 'mobile/mcrf_bridge_review_test.go') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-v013/script_boundary_review_test.go') -Destination (Join-Path $taskCommand 'script_boundary_review_test.go') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-v013/provider_state.go') -Destination (Join-Path $taskCommand 'provider_state.go') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-v013/provider_state_review_test.go') -Destination (Join-Path $taskCommand 'provider_state_review_test.go') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-v013/verification_ipc_review_test.go') -Destination (Join-Path $taskCommand 'verification_ipc_review_test.go') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-v013/reply_worker_review_test.go') -Destination (Join-Path $taskCommand 'reply_worker_review_test.go') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-v013/document_worker.go') -Destination (Join-Path $taskCommand 'document_worker.go') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-v013/document_worker_review_test.go') -Destination (Join-Path $taskCommand 'document_worker_review_test.go') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-v013/http_worker.go') -Destination (Join-Path $taskCommand 'http_worker.go') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-v013/http_worker_review_test.go') -Destination (Join-Path $taskCommand 'http_worker_review_test.go') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-v013/http_gateway.go') -Destination (Join-Path $taskCommand 'http_gateway.go') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-v013/http_gateway_review_test.go') -Destination (Join-Path $taskCommand 'http_gateway_review_test.go') -Force
}
if($ScriptUpdaterCandidate -or $FreshCoreCandidate){
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-script/script_update.go') -Destination (Join-Path $taskCommand 'script_update.go') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-script/script_update_test.go') -Destination (Join-Path $taskCommand 'script_update_test.go') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-script/script_lock_unix.go') -Destination (Join-Path $taskCommand 'script_lock_unix.go') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-script/script_lock_windows.go') -Destination (Join-Path $taskCommand 'script_lock_windows.go') -Force
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'testserver/main.go') -Destination (Join-Path $taskExit 'main.go') -Force
if($MailruAckCandidate){
    $taskAckPins=@{'mailru_ack.go'='84df42c087241e82cb5ad8839feb4d32e8c889f73c49445f64e58ed69d1c9799';'mailru_ack_test.go'='09506a8725c6b51574bf0d73e87b12b87e151f03b1c7d9d15f8d4809c16dce5c';'mailru_ack.patch'='eb3884cc313cef53f1b4837bcd46293d4e7e3be21f185d5dd825081278e05ef2'}
    foreach($taskName in $taskAckPins.Keys){if((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot "staged-mailru/$taskName")).Hash.ToLowerInvariant() -ne $taskAckPins[$taskName]){throw 'ACK overlay differs from reviewed source'}}
    $taskMailru=Join-Path $taskSource 'transport/mailru/mailru.go'
    if((Get-FileHash -LiteralPath $taskMailru).Hash.ToLowerInvariant() -ne '615a57133816e33da1a091d008715bd76f588d2913c6a0d9342224a215d6ecaf'){throw 'Mailru base differs from reviewed upstream'}
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-mailru/mailru_ack.go') -Destination (Join-Path $taskSource 'transport/mailru/mcrf_ack.go')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'staged-mailru/mailru_ack_test.go') -Destination (Join-Path $taskSource 'transport/mailru/mcrf_ack_test.go')
    $taskMailruText=Get-Content -LiteralPath $taskMailru -Raw
    $taskHook='func (t *MailruDocsTransport) handleMessage(session *DocSession, data []byte) {'
    $taskWriter='payload := chatDataTag + base64.StdEncoding.EncodeToString(pending)'
    if(!$taskMailruText.Contains($taskHook) -or !$taskMailruText.Contains($taskWriter)){throw 'ACK hook source mismatch'}
    $taskMailruText=$taskMailruText.Replace($taskHook,$taskHook+"`n`tif mcrfMailruAuthChanges(session, data) { return }")
    $taskMailruText=$taskMailruText.Replace($taskWriter,"if !mcrfMailruWriteReady(session) { time.Sleep(15 * time.Millisecond); continue }`n`t`t"+$taskWriter)
    [IO.File]::WriteAllText($taskMailru,$taskMailruText,[Text.UTF8Encoding]::new($false))
    & $taskGo -C $taskSource test -json ./transport/mailru -run '^TestMcrf|^TestWaitMailruSocketIOHandshakeOrder|^TestCursorPayloadsBatch|^TestSecondReconnectWhileOneIsWaitingReturnsAtOnce|^TestCanEdit' > (Join-Path $taskSource 'mailru-ack-host-tests.jsonl')
    if($LASTEXITCODE -ne 0){throw 'ACK nonlive regression tests failed before native build'}
}
$taskOldPath=$env:PATH
$taskRetained=$null
if($RetainStartupEvidenceFrom){
    if(!$FreshCoreCandidate){throw 'Retained evidence is restricted to exact fresh startup correction'}
    $taskRetained=[IO.Path]::GetFullPath($RetainStartupEvidenceFrom)
    if(!$taskRetained.StartsWith((Join-Path $taskProject 'work/ports-test-2026-10-14/openflux-build-v0.13.0-'),[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected retained source root'}
    $taskOldReceipt=Get-Content -LiteralPath (Join-Path $taskRetained 'verified-native/fresh-core-candidate.json') -Raw|ConvertFrom-Json
    if(!$taskOldReceipt.sourceStable -or $taskOldReceipt.commit -cne $Commit){throw 'Retained source identity differs'}
    foreach($taskName in $taskFreshSources.Keys){
        if($taskName -notin @('main.go','main_test.go','build.ps1') -and $taskOldReceipt.sources.$taskName -cne $taskFreshSources[$taskName]){throw "Changed non-startup input cannot retain evidence: $taskName"}
    }
    # Compare the actual complete dependency source trees, not only overlay pins.
    foreach($taskFile in Get-ChildItem -LiteralPath $taskSource -Recurse -File|Where-Object {$_.Extension -eq '.go' -or $_.Name -in @('go.mod','go.sum')}){
        $taskRelative=[IO.Path]::GetRelativePath($taskSource,$taskFile.FullName)
        if($taskRelative -in @('mobile\cmd\mcrf-openflux\main.go','mobile\cmd\mcrf-openflux\main_test.go')){continue}
        $taskPrevious=Join-Path $taskRetained $taskRelative
        if(!(Test-Path -LiteralPath $taskPrevious) -or (Get-FileHash -LiteralPath $taskFile.FullName).Hash -cne (Get-FileHash -LiteralPath $taskPrevious).Hash){throw "Retained dependency differs: $taskRelative"}
    }
}
try {
    Push-Location (Join-Path $taskSource 'mobile')
    & $taskGo mod download;if($LASTEXITCODE -ne 0){throw 'OpenFlux module download failed'}
    & $taskGo mod verify;if($LASTEXITCODE -ne 0){throw 'OpenFlux module verification failed'}
    if($FreshCoreCandidate -and $taskRetained){
        foreach($taskLog in $taskOldReceipt.tests){
            $taskEvents=@(Get-Content -LiteralPath (Join-Path $taskRetained $taskLog)|ForEach-Object {$_|ConvertFrom-Json})
            if(@($taskEvents|Where-Object {$_.Action -eq 'fail'}).Count -or !@($taskEvents|Where-Object {$_.Action -eq 'pass'}).Count){throw "Retained log not green: $taskLog"}
            Copy-Item -LiteralPath (Join-Path $taskRetained $taskLog) -Destination (Join-Path $taskSource $taskLog)
        }
        $taskNewEvents=@(& $taskGo test '-ldflags=-checklinkname=0' -json ./cmd/mcrf-openflux -run '^TestMcrfStartup')
        if($LASTEXITCODE){throw 'New startup cancellation checks failed'}
        $taskMerged=@(Get-Content -LiteralPath (Join-Path $taskSource 'fresh-script-host-tests.jsonl'))+$taskNewEvents
        [IO.File]::WriteAllLines((Join-Path $taskSource 'fresh-script-host-tests.jsonl'),[string[]]$taskMerged,[Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText((Join-Path $taskSource 'retained-startup-evidence.json'),(@{parent=$taskRetained;newTests=2;retainedLogs=$taskOldReceipt.tests;scope='only cmd startup changed; complete other Go source trees byte-identical'}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    }
    if($FreshCoreCandidate -and !$taskRetained){
        foreach($taskDeepSuite in @(@('mcrfdiag','fresh-deep-recorder-host-tests.jsonl'),@('transport/mailru','fresh-mailru-host-tests.jsonl'))){
            $taskFreshEvents=@(& $taskGo -C $taskSource test -json ('./'+$taskDeepSuite[0]) -run '^TestMcrfFreshDeep')
            $taskFreshExit=$LASTEXITCODE
            [IO.File]::WriteAllLines((Join-Path $taskSource $taskDeepSuite[1]),[string[]]$taskFreshEvents,[Text.UTF8Encoding]::new($false))
            if($taskFreshExit -ne 0){throw 'Fresh diagnostic/ACK foundation tests failed'}
        }
        $taskFreshEvents=@(& $taskGo -C $taskSource test -json ./transport -run '^TestMcrfFresh')
        $taskFreshExit=$LASTEXITCODE
        [IO.File]::WriteAllLines((Join-Path $taskSource 'fresh-observation-host-tests.jsonl'),[string[]]$taskFreshEvents,[Text.UTF8Encoding]::new($false))
        if($taskFreshExit -ne 0){throw 'Fresh counter observation tests failed before native build'}
        $taskFreshEvents=@(& $taskGo -C $taskSource test -json ./transport/script -run '^TestMcrfFreshOwnHTTP|^TestHTTPServerSyncAndAsyncHandlers$|^TestHTTPServerClosedOnStop$')
        $taskFreshExit=$LASTEXITCODE
        [IO.File]::WriteAllLines((Join-Path $taskSource 'fresh-own-http-host-tests.jsonl'),[string[]]$taskFreshEvents,[Text.UTF8Encoding]::new($false))
        if($taskFreshExit -ne 0){throw 'Fresh script-owned HTTP admission tests failed before native build'}
        $taskFreshEvents=@(& $taskGo -C $taskSource test -json ./transport/manager -run '^TestMcrfFreshCaptchaOwner')
        $taskFreshExit=$LASTEXITCODE
        [IO.File]::WriteAllLines((Join-Path $taskSource 'fresh-captcha-owner-host-tests.jsonl'),[string[]]$taskFreshEvents,[Text.UTF8Encoding]::new($false))
        if($taskFreshExit -ne 0){throw 'Fresh exact captcha owner tests failed before native build'}
        $taskFreshEvents=@(& $taskGo test '-ldflags=-checklinkname=0' -json . -run '^TestMcrfFresh')
        $taskFreshExit=$LASTEXITCODE
        [IO.File]::WriteAllLines((Join-Path $taskSource 'fresh-bridge-host-tests.jsonl'),[string[]]$taskFreshEvents,[Text.UTF8Encoding]::new($false))
        if($taskFreshExit -ne 0){throw 'Fresh bridge source-bound tests failed before native build'}
        $taskFreshEvents=@(& $taskGo test '-ldflags=-checklinkname=0' -json ./cmd/mcrf-openflux -run '^TestScript|^TestMcrfFresh')
        $taskFreshExit=$LASTEXITCODE
        [IO.File]::WriteAllLines((Join-Path $taskSource 'fresh-script-host-tests.jsonl'),[string[]]$taskFreshEvents,[Text.UTF8Encoding]::new($false))
        if($taskFreshExit -ne 0){throw 'Fresh signed-script tests failed before native build'}
    }
    if($HostOnly){
        $env:GOOS='windows';$env:GOARCH='amd64';$env:CGO_ENABLED='0'
        & $taskGo build -trimpath '-ldflags=-s -w -checklinkname=0' -o (Join-Path $taskSource 'mcrf-openflux.exe') ./cmd/mcrf-openflux
        if($LASTEXITCODE -ne 0){throw 'OpenFlux host build failed'}
        & $taskGo test '-ldflags=-checklinkname=0' ./cmd/mcrf-openflux
        if($LASTEXITCODE -ne 0){throw 'OpenFlux private configuration tests failed'}
        Write-Output "Host candidate executable: $(Join-Path $taskSource 'mcrf-openflux.exe')"
        return
    }
    if(!$Ndk){$Ndk=Join-Path $taskProject 'work/toolchains/ndk-r27/android-ndk-r27'}
    $taskCompiler=Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
    if(!(Test-Path -LiteralPath (Join-Path $taskCompiler 'clang.exe'))){throw 'NDK r27 missing'}
    $env:PATH=$taskCompiler+';'+$taskOldPath;$env:GOOS='android';$env:CGO_ENABLED='1'
    foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
        switch($taskAbi){'arm64-v8a'{$env:GOARCH='arm64';$taskTriple='aarch64-linux-android29'}'armeabi-v7a'{$env:GOARCH='arm';$env:GOARM='7';$taskTriple='armv7a-linux-androideabi29'}'x86_64'{$env:GOARCH='amd64';$taskTriple='x86_64-linux-android29'}}
        $env:CC="clang --target=$taskTriple"
        $taskOutput=Join-Path $taskCandidate "$taskAbi/libopenflux.so"
        New-Item -ItemType Directory -Path (Split-Path $taskOutput) -Force | Out-Null
        & $taskGo build -trimpath -buildmode=pie '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o $taskOutput ./cmd/mcrf-openflux
        if($LASTEXITCODE -ne 0){throw "OpenFlux native build failed: $taskAbi"}
        if($TestServer){
            $taskOutput=Join-Path $taskCandidate "fixtures/$taskAbi/libopenflux_testserver.so"
            New-Item -ItemType Directory -Path (Split-Path $taskOutput) -Force | Out-Null
            & $taskGo build -trimpath -buildmode=pie '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o $taskOutput ./cmd/mcrf-test-exit
            if($LASTEXITCODE -ne 0){throw "OpenFlux fixture build failed: $taskAbi"}
        }
    }
} finally { Pop-Location; $env:PATH=$taskOldPath; Remove-Item Env:GOOS,Env:GOARCH,Env:GOARM,Env:CGO_ENABLED,Env:CC -ErrorAction SilentlyContinue }
if(!$StageOnly){
    foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
        Copy-Item -LiteralPath (Join-Path $taskCandidate "$taskAbi/libopenflux.so") -Destination (Join-Path $PSScriptRoot "../../engines/src/main/jniLibs/$taskAbi/libopenflux.so") -Force
        if($TestServer){Copy-Item -LiteralPath (Join-Path $taskCandidate "fixtures/$taskAbi/libopenflux_testserver.so") -Destination (Join-Path $PSScriptRoot "../../engines/src/debug/jniLibs/$taskAbi/libopenflux_testserver.so") -Force}
    }
    Copy-Item -LiteralPath (Join-Path $taskSource 'LICENSE') -Destination (Join-Path $PSScriptRoot '../../engines/src/main/assets/licenses/OpenFlux-LICENSE') -Force
}
if(($ScriptUpdaterCandidate -or $FreshCoreCandidate) -and !$HostOnly){
    $taskDigests=@{}
    foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){$taskDigests[$taskAbi]=(Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $taskCandidate "$taskAbi/libopenflux.so")).Hash.ToLowerInvariant()}
    $taskCapabilityJson=@{interface=1;scriptUpdater=1;version=$Version;commit=$Commit;sha256=$taskDigests}|ConvertTo-Json -Depth 4
    [IO.File]::WriteAllText((Join-Path $taskCandidate 'script-updater-capability.json'),$taskCapabilityJson,[Text.UTF8Encoding]::new($false))
}
Write-Output "Verified source: $Version $Commit"
if($FreshCoreCandidate -and !$HostOnly){
    $taskFreshFiles=@{}
    foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
        $taskBinary=Join-Path $taskCandidate "$taskAbi/libopenflux.so"
        $taskFreshFiles[$taskAbi]=@{sha256=(Get-FileHash -LiteralPath $taskBinary).Hash.ToLowerInvariant();bytes=(Get-Item -LiteralPath $taskBinary).Length}
    }
    foreach($taskName in $taskFreshSources.Keys){
        if((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $taskName)).Hash.ToLowerInvariant() -ne $taskFreshSources[$taskName]){throw "Fresh OpenFlux source changed during stage: $taskName"}
    }
    foreach($taskEvidence in @('fresh-bridge-host-tests.jsonl','fresh-script-host-tests.jsonl','fresh-observation-host-tests.jsonl','fresh-own-http-host-tests.jsonl','fresh-captcha-owner-host-tests.jsonl','fresh-deep-recorder-host-tests.jsonl','fresh-mailru-host-tests.jsonl')){
        Copy-Item -LiteralPath (Join-Path $taskSource $taskEvidence) -Destination (Join-Path $taskCandidate $taskEvidence)
    }
    $taskFreshReceipt=@{interface=1;upstream='p1neappleXpress/OpenFlux';tag=$Version;commit=$Commit;cli='--mcrf-config';sources=$taskFreshSources;sourceStable=$true;files=$taskFreshFiles;tests=@('fresh-bridge-host-tests.jsonl','fresh-script-host-tests.jsonl','fresh-observation-host-tests.jsonl','fresh-own-http-host-tests.jsonl','fresh-captcha-owner-host-tests.jsonl','fresh-deep-recorder-host-tests.jsonl','fresh-mailru-host-tests.jsonl');scriptUpdater=1;verificationUi=1;counterObservation=1;providerDiagnostic=0;runtimeAccepted=$false;modulePackaged=$false;productionPromotion=$false}
    [IO.File]::WriteAllText((Join-Path $taskCandidate 'fresh-core-candidate.json'),($taskFreshReceipt|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
}
if($MailruAckCandidate -and !$HostOnly){
    $taskFiles=@{}
    foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){$taskBinary=Join-Path $taskCandidate "$taskAbi/libopenflux.so";$taskFiles[$taskAbi]=@{sha256=(Get-FileHash -LiteralPath $taskBinary).Hash.ToLowerInvariant();bytes=(Get-Item -LiteralPath $taskBinary).Length}}
    $taskAckReceipt=@{version=1;upstream='p1neappleXpress/OpenFlux';tag=$Version;commit=$Commit;overlay='mcrf-mailru-ack.1';moduleVersionCode=7;scriptUpdater=0;cli='--mcrf-config';overlays=$taskAckPins;files=$taskFiles;tests='mailru-ack-host-tests.jsonl';runtime_verified=$false}
    [IO.File]::WriteAllText((Join-Path $taskCandidate 'mailru-ack-candidate.json'),($taskAckReceipt|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
}
Write-Output "Candidate native libraries: $taskCandidate"
