param([string]$ScratchRoot='D:/MCRF-engine-diagnostic-v3')
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../..'))
$pristine=Join-Path $project 'work/android-upstream/openflux-core-v0.7.3'
$v1='D:/MCRF-engine-diagnostics/83c19dc199c040eb940eb7e573e07d89/diagnostic.patch'
$v2='D:/MCRF-engine-diagnostic-v2/a50e780b692040c4bd905d28ee794a14/diagnostic-v2.patch'
if((Get-FileHash $v1 -Algorithm SHA256).Hash.ToLowerInvariant() -ne '4764e6add52a853c0f5f9534eb2461d3bb8070b9a7a227f15848c5c840885fc6' -or (Get-FileHash $v2 -Algorithm SHA256).Hash.ToLowerInvariant() -ne '25ba2ab139cb0be27d5349e7be8429da14c94fa83b76c613e4e9d56786f677e5'){throw 'Reviewed source changed'}
if((& git -C $pristine rev-parse HEAD) -ne 'f12e981629a18c8c01007d59ac002254c02972bb' -or (& git -C $pristine status --porcelain|Out-String).Trim()){throw 'Upstream identity'}
$stage=Join-Path $ScratchRoot ([guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $stage -Force|Out-Null
Get-ChildItem $pristine -Force|Where-Object Name -ne '.git'|Copy-Item -Destination $stage -Recurse
& git -C $stage init --quiet
& git -C $stage add .
& git -C $stage apply $v1;if($LASTEXITCODE -ne 0){throw 'V1 apply'}
& git -C $stage apply $v2;if($LASTEXITCODE -ne 0){throw 'V2 apply'}
& git -C $stage add .
function Hook([string]$file,[string]$old,[string]$new){$path=Join-Path $stage $file;$txt=[IO.File]::ReadAllText($path).Replace("`r`n","`n");if(!$txt.Contains($old)){throw "Hook missing $file : $old"};[IO.File]::WriteAllText($path,$txt.Replace($old,$new),[Text.UTF8Encoding]::new($false))}
Copy-Item "$PSScriptRoot/events_v3*.go" "$stage/mcrfdiag/"
Copy-Item "$PSScriptRoot/wrapper_v3*.go" "$stage/mobile/cmd/mcrf-openflux/"
Copy-Item "$PSScriptRoot/session_pipeline_test.go" "$stage/transport/mcrf_v3_test.go"
Hook 'mcrfdiag/diagnostic.go' 'e>=v2EventCount || (r.version!=2 && e>=eventCount)' 'e>=v3EventCount || (r.version==1 && e>=eventCount) || (r.version==2 && e>=v2EventCount)'
Hook 'mcrfdiag/diagnostic.go' 'if r.version==2{keys=append(keys,namesV2...);prefix=PrefixV2}' 'if r.version>=2{keys=append(keys,namesV2...);prefix=PrefixV2};if r.version==3{keys=append(keys,namesV3...);prefix=PrefixV3}'
Hook 'transport/encrypted.go' 'mcrfClassicDiag *mcrfdiag.Run' "mcrfClassicDiag *mcrfdiag.Run`n mcrfSessionDiag *mcrfdiag.Run"
foreach($e in @(@('ClassicHeaderInvalid','SessHeaderInvalid'),@('ClassicDirectionInvalid','SessDirectionInvalid'),@('ClassicDecryptFailed','SessDecryptFailed'),@('ClassicDecryptOK','SessDecryptOK'),@('ClassicReplayRejected','SessReplayRejected'))){Hook 'transport/encrypted.go' "e.mcrfClassicDiag.Mark(mcrfdiag.$($e[0]))" "e.mcrfClassicDiag.Mark(mcrfdiag.$($e[0]))`n e.mcrfSessionDiag.Mark(mcrfdiag.$($e[1]))"}
Hook 'transport/session.go' 'enc.SetAlternateContexts(alternates)' "enc.SetAlternateContexts(alternates)`n enc.mcrfSessionDiag=mcrfdiag.Current()"
Hook 'transport/session.go' "`tif classic != ClassicOff {`n`t`tlink.classicCodec" "link.batched.mcrfSessionDiag=mcrfdiag.Current()`n`tif classic != ClassicOff {`n`t`tlink.classicCodec"
Hook 'transport/batched.go' 'import (' "import (`n `"github.com/p1neappleXpress/OpenFlux/mcrfdiag`""
Hook 'transport/batched.go' 'type BatchedTransport struct {' "type BatchedTransport struct {`n mcrfSessionDiag *mcrfdiag.Run"
Hook 'transport/batched.go' 'utils.Debugf("[BATCH] decode error' "b.mcrfSessionDiag.Mark(mcrfdiag.SessBatchInvalid)`n utils.Debugf(`"[BATCH] decode error"
Hook 'transport/batched.go' 'utils.Debugf("[BATCH] Recv: no callback' "b.mcrfSessionDiag.Mark(mcrfdiag.SessCallbackMissing)`n utils.Debugf(`"[BATCH] Recv: no callback"
Hook 'transport/session.go' 's.cntDecodeErr.Add(1)' "link.encrypted.mcrfSessionDiag.Mark(mcrfdiag.SessEnvelopeInvalid)`n s.cntDecodeErr.Add(1)"
Hook 'transport/session.go' 'if env.Role == s.roleLocked() {' "if env.Role == s.roleLocked() {`n link.encrypted.mcrfSessionDiag.Mark(mcrfdiag.SessRoleInvalid)"
Hook 'transport/session.go' 's.cntHelloReject.Add(1)' "link.encrypted.mcrfSessionDiag.Mark(mcrfdiag.SessHelloRejected)`n s.cntHelloReject.Add(1)"
Hook 'transport/session.go' 's.cntHelloAccept.Add(1)' "link.encrypted.mcrfSessionDiag.Mark(mcrfdiag.SessHelloAccepted)`n s.cntHelloAccept.Add(1)"
Hook 'mobile/cmd/mcrf-openflux/main.go' 'func main() {' "func main() {`n if len(os.Args)==3 && os.Args[1]==`"--mcrf-diagnostic-config-v3`"{if err:=mcrfRunDiagnosticV3(os.Args[2]);err!=nil{fmt.Fprintln(os.Stderr,`"OpenFlux diagnostic run failed`");os.Exit(1)};return}"
& git -C $stage add -N -- mcrfdiag transport mobile/cmd/mcrf-openflux
& git -C $stage diff --binary --output="$stage/diagnostic-v3.patch" -- mcrfdiag transport mobile/cmd/mcrf-openflux
if($LASTEXITCODE -ne 0){throw 'V3 diff failed'}
$stage
