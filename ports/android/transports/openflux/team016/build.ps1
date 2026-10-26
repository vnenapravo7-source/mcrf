param()
$ErrorActionPreference='Stop'
$taskProject='D:/MCRF/Projects/MCRF'
$taskCanonical=Split-Path $PSScriptRoot
$taskPristine="$taskProject/work/android-upstream/openflux-team-v0.16.0"
$taskCommit='5a46f2f1f178637538542a36c7f086f26df86bcd'
if((& git -C $taskPristine rev-parse HEAD) -cne $taskCommit -or ((& git -C $taskPristine status --porcelain|Out-String).Trim())){throw 'Exact clean OpenFluxTeam0.16 source required'}
$taskId=[guid]::NewGuid().ToString('N');$taskSource="$taskProject/work/openflux016-source-$taskId";$taskOutput="D:/MCRF/Builds/Android/openflux016-native-$taskId"
$null=New-Item -ItemType Directory $taskSource,$taskOutput
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
$taskInputs=@{};foreach($taskName in @('main.go','main_test.go','mcrf_proxy.go','team016/build.ps1','team016/mailru_ready.go','team016/mailru_ready_test.go','team016/bridge_test.go','team016/testserver.go','team016/session_timeout.go','team016/session_timeout_test.go','team016/session_mobile.go')){$taskInputs[$taskName]=Hash "$taskCanonical/$taskName"}
Get-ChildItem -LiteralPath $taskPristine -Force|Where-Object Name -ne '.git'|Copy-Item -Destination $taskSource -Recurse
$null=New-Item -ItemType Directory "$taskSource/mobile/cmd/mcrf-openflux","$taskSource/mobile/cmd/mcrf-test-exit"
foreach($taskName in @('main.go','main_test.go')){Copy-Item "$taskCanonical/$taskName" "$taskSource/mobile/cmd/mcrf-openflux/$taskName"}
Copy-Item "$taskCanonical/mcrf_proxy.go" "$taskSource/mobile/mcrf_proxy.go"
Copy-Item "$PSScriptRoot/bridge_test.go" "$taskSource/mobile/mcrf_team016_test.go"
Copy-Item "$PSScriptRoot/testserver.go" "$taskSource/mobile/cmd/mcrf-test-exit/main.go"
Copy-Item "$PSScriptRoot/mailru_ready.go" "$taskSource/transport/mailru/mcrf_team016_ready.go"
Copy-Item "$PSScriptRoot/mailru_ready_test.go" "$taskSource/transport/mailru/mcrf_team016_ready_test.go"
Copy-Item "$PSScriptRoot/session_timeout.go" "$taskSource/transport/mcrf_team016_timeout.go"
Copy-Item "$PSScriptRoot/session_timeout_test.go" "$taskSource/transport/mcrf_team016_timeout_test.go"
Copy-Item "$PSScriptRoot/session_mobile.go" "$taskSource/mobile/mcrf_team016_session.go"
$taskSession="$taskSource/mobile/session.go"
if((Hash $taskSession) -cne '41d050482ea1f90accbe0813e2fab44efa8864ef5ee3f8505df9b427ecaf7610'){throw 'Reviewed mobile session source differs'}
$taskSessionText=Get-Content $taskSession -Raw
$taskSessionHook='sess.SetAlternateContexts(alternates)'
if([regex]::Matches($taskSessionText,[regex]::Escape($taskSessionHook)).Count -ne 1){throw 'Mobile pre-Start timeout hook missing'}
[IO.File]::WriteAllText($taskSession,$taskSessionText.Replace($taskSessionHook,"mcrf016ConfigureHandshake(sess,specs)`n`t$taskSessionHook"),[Text.UTF8Encoding]::new($false))
$taskMailru="$taskSource/transport/mailru/mailru.go"
if((Hash $taskMailru) -cne '22e5adec3305bf2f9286f13c3dca8e2552b06b2d66f7cfeb111a8eced86a9364'){throw 'Reviewed Mailru source differs'}
$taskText=(Get-Content $taskMailru -Raw).Replace("`r`n","`n")
$taskHooks=@(
 @('readyOnce sync.Once',"readyOnce sync.Once`n`tmcrfAckFailed atomic.Bool"),
 @('func (t *MailruDocsTransport) handleMessage(session *DocSession, data []byte) {',"func (t *MailruDocsTransport) handleMessage(session *DocSession, data []byte) {`n`tif mcrf016AuthChanges(session,data){return}"),
 @('session.safeWrite(websocket.TextMessage, []byte(fmt.Sprintf("42%s", string(messagePart))))','if err := mcrf016WriteAuth(session, []byte(fmt.Sprintf("42%s",string(messagePart)))); err != nil { return nil, err }'),
 @('time.AfterFunc(authChangesReadyFallback, session.markReady)','time.AfterFunc(authChangesReadyFallback, func(){mcrf016Fallback(session)})'),
 @('payload := chatDataTag + base64.StdEncoding.EncodeToString(pending)',("if !mcrf016Ready(session){time.Sleep(15*time.Millisecond);continue}`n`t`t"+'payload := chatDataTag + base64.StdEncoding.EncodeToString(pending)'))
)
foreach($taskHook in $taskHooks){if([regex]::Matches($taskText,[regex]::Escape($taskHook[0])).Count -ne 1){throw 'Exact native readiness hook missing'};$taskText=$taskText.Replace($taskHook[0],$taskHook[1])}
[IO.File]::WriteAllText($taskMailru,$taskText,[Text.UTF8Encoding]::new($false))
$taskFrozen=@(Get-ChildItem $taskSource -Recurse -File|ForEach-Object {@{path=[IO.Path]::GetRelativePath($taskSource,$_.FullName);sha256=(Hash $_.FullName)}})
$taskGo="$taskProject/work/toolchains/go1.26.8/go/bin/go.exe"
$taskPrevious=@{};foreach($taskKey in @('PATH','GOPATH','GOOS','GOARCH','GOARM','CGO_ENABLED','CC')){$taskPrevious[$taskKey]=[Environment]::GetEnvironmentVariable($taskKey)}
$taskResult=[ordered]@{state='BUILDING';repository='OpenFluxTeam/OpenFlux';version='v0.16.0';commit=$taskCommit;source=$taskSource;sourceStable=$false;inputs=$taskInputs;runtimeAccepted=$false;published=$false;files=@();tests=@()}
try{
 $env:GOPATH="$taskProject/work/toolchains/go-home";$env:GOOS='windows';$env:GOARCH='amd64';$env:CGO_ENABLED='0'
 Push-Location $taskSource
 try{
  & $taskGo mod verify *> "$taskOutput/dependencies.log";if($LASTEXITCODE){throw 'Pinned root dependencies differ'}
  $taskSuites=@(
   @('./transport','^TestMcrf016|^TestFluxFast|^TestDirect|^TestSession|^TestStriped|^TestEncrypted|^TestFram|^TestBatched','core'),
   @('./transport/registry','^Test','registry'),
   @('./transport/mailru','^TestMcrf016|^TestSameAccount|^TestFallsBack|^TestLearnConnID|^TestWaitMailruSocketIO','mailru'),
   @('./share','^Test','share')
  )
  foreach($taskSuite in $taskSuites){& $taskGo test -mod=readonly -short -json $taskSuite[0] -run $taskSuite[1] > "$taskOutput/$($taskSuite[2]).jsonl";if($LASTEXITCODE){throw "Changed core suite failed: $($taskSuite[2])"};$taskResult.tests+=@{file="$($taskSuite[2]).jsonl";sha256=(Hash "$taskOutput/$($taskSuite[2]).jsonl")}}
 }finally{Pop-Location}
 Push-Location "$taskSource/mobile"
 try{
  & $taskGo mod verify *> "$taskOutput/mobile-dependencies.log";if($LASTEXITCODE){throw 'Pinned mobile dependencies differ'}
  foreach($taskSuite in @(@('.','^TestMcrf016','bridge'),@('./cmd/mcrf-openflux','^TestMcrfStartup|^TestPrivateConfiguration','cli'))){
   & $taskGo test -mod=readonly '-ldflags=-checklinkname=0' -json $taskSuite[0] -run $taskSuite[1] > "$taskOutput/$($taskSuite[2]).jsonl";if($LASTEXITCODE){throw "Android bridge suite failed: $($taskSuite[2])"};$taskResult.tests+=@{file="$($taskSuite[2]).jsonl";sha256=(Hash "$taskOutput/$($taskSuite[2]).jsonl")}
  }
  $env:PATH='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin;'+$taskPrevious.PATH
  $env:GOOS='android';$env:CGO_ENABLED='1'
  foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
   switch($taskAbi){'arm64-v8a'{$env:GOARCH='arm64';$taskTriple='aarch64-linux-android29'}'armeabi-v7a'{$env:GOARCH='arm';$env:GOARM='7';$taskTriple='armv7a-linux-androideabi29'}'x86_64'{$env:GOARCH='amd64';$taskTriple='x86_64-linux-android29'}}
   $env:CC="clang --target=$taskTriple";$null=New-Item -ItemType Directory "$taskOutput/$taskAbi"
   & $taskGo build -mod=readonly -trimpath -buildmode=pie '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o "$taskOutput/$taskAbi/libopenflux.so" ./cmd/mcrf-openflux *> "$taskOutput/build-$taskAbi.log"
   if($LASTEXITCODE){throw "Native0.16 build failed: $taskAbi"}
   $taskResult.files+=@{abi=$taskAbi;sha256=(Hash "$taskOutput/$taskAbi/libopenflux.so");bytes=(Get-Item "$taskOutput/$taskAbi/libopenflux.so").Length}
   if($taskAbi -eq 'arm64-v8a'){
    $null=New-Item -ItemType Directory "$taskOutput/fixtures/arm64-v8a"
    & $taskGo build -mod=readonly -trimpath -buildmode=pie '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o "$taskOutput/fixtures/arm64-v8a/libopenflux_testserver.so" ./cmd/mcrf-test-exit *> "$taskOutput/exit-build.log"
    if($LASTEXITCODE){throw 'Owned direct/FluxFast exit compile failed'}
    $taskResult.fixtureSha256=Hash "$taskOutput/fixtures/arm64-v8a/libopenflux_testserver.so"
   }
  }
 }finally{Pop-Location}
 foreach($taskRow in $taskFrozen){if((Hash (Join-Path $taskSource $taskRow.path)) -cne $taskRow.sha256){throw 'Upstream/overlay source changed during build'}}
 foreach($taskKey in $taskInputs.Keys){if((Hash "$taskCanonical/$taskKey") -cne $taskInputs[$taskKey]){throw 'Canonical bridge source changed during build'}}
 [IO.File]::WriteAllText("$taskOutput/source-manifest.json",($taskFrozen|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
 $taskResult.sourceStable=$true;$taskResult.state='BUILT_HOST_VERIFIED_NOT_PHONE_ACCEPTED'
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{foreach($taskKey in $taskPrevious.Keys){[Environment]::SetEnvironmentVariable($taskKey,$taskPrevious[$taskKey])};[IO.File]::WriteAllText("$taskOutput/receipt.json",($taskResult|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false));Write-Output "OPENFLUX016=$($taskResult.state);STAGE=$taskOutput"}
