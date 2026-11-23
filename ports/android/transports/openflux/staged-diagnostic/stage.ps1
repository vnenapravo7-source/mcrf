param([string]$ScratchRoot='D:/MCRF-engine-diagnostics')
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../..'))
$pristine=Join-Path $project 'work/android-upstream/openflux-core-v0.7.3'
if((& git -C $pristine rev-parse HEAD) -ne 'f12e981629a18c8c01007d59ac002254c02972bb'){throw 'Wrong upstream'}
if((& git -C $pristine status --porcelain|Out-String).Trim()){throw 'Dirty upstream'}
$stage=Join-Path $ScratchRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage -Force|Out-Null
Get-ChildItem $pristine -Force|Where-Object Name -ne '.git'|Copy-Item -Destination $stage -Recurse
& git -C $stage init --quiet
& git -C $stage add .
if($LASTEXITCODE -ne 0){throw 'Cannot index pristine baseline'}
$ack=Join-Path $PSScriptRoot '../staged-mailru'
$ackPins=@{'mailru_ack.go'='84df42c087241e82cb5ad8839feb4d32e8c889f73c49445f64e58ed69d1c9799';'mailru_ack_test.go'='09506a8725c6b51574bf0d73e87b12b87e151f03b1c7d9d15f8d4809c16dce5c';'mailru_ack.patch'='eb3884cc313cef53f1b4837bcd46293d4e7e3be21f185d5dd825081278e05ef2'}
foreach($entry in $ackPins.GetEnumerator()){if((Get-FileHash "$ack/$($entry.Key)" -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.Value){throw 'Reviewed ACK changed'}}
Copy-Item "$ack/mailru_ack.go" "$stage/transport/mailru/mcrf_ack.go"
Copy-Item "$ack/mailru_ack_test.go" "$stage/transport/mailru/mcrf_ack_test.go"
& git -C $stage apply --unsafe-paths "$ack/mailru_ack.patch"
if($LASTEXITCODE -ne 0){throw 'ACK patch failed'}
function Replace-Exact([string]$relative,[string]$before,[string]$after){
 $path=Join-Path $stage $relative;$txt=[IO.File]::ReadAllText($path)
 if(!$txt.Contains($before)){throw "Missing diagnostic hook $relative : $before"}
 [IO.File]::WriteAllText($path,$txt.Replace($before,$after),[Text.UTF8Encoding]::new($false))
}
New-Item -ItemType Directory -Path "$stage/mcrfdiag","$stage/mobile/cmd/mcrf-openflux" -Force|Out-Null
Copy-Item "$PSScriptRoot/diagnostic*.go" "$stage/mcrfdiag/"
Copy-Item "$PSScriptRoot/wrapper*.go" "$stage/mobile/cmd/mcrf-openflux/"
Copy-Item "$PSScriptRoot/transport_test.go" "$stage/transport/mcrf_diag_test.go"
Copy-Item "$PSScriptRoot/mailru_test.go" "$stage/transport/mailru/mcrf_diag_test.go"
Copy-Item "$PSScriptRoot/../main*.go" "$stage/mobile/cmd/mcrf-openflux/"
Copy-Item "$PSScriptRoot/../mcrf_proxy.go" "$stage/mobile/mcrf_proxy.go"
foreach($file in @('transport/mailru/mailru.go','transport/mailru/mcrf_ack.go','transport/encrypted.go')){Replace-Exact $file 'import (' "import (`n `"github.com/p1neappleXpress/OpenFlux/mcrfdiag`""}
Replace-Exact 'transport/mailru/mailru.go' 'type DocSession struct {' "type DocSession struct {`n diag *mcrfdiag.Run"
Replace-Exact 'transport/mailru/mailru.go' 'session := &DocSession{' "session := &DocSession{`n diag: mcrfdiag.Current(),"
Replace-Exact 'transport/mailru/mailru.go' 't.session = session' "session.diag.Mark(mcrfdiag.SocketReady)`n t.session = session"
Replace-Exact 'transport/mailru/mailru.go' 'session.safeWrite(websocket.TextMessage, []byte(fmt.Sprintf("42%s", string(messagePart))))' "if err:=session.safeWrite(websocket.TextMessage, []byte(fmt.Sprintf(`"42%s`", string(messagePart))));err!=nil{session.diag.Mark(mcrfdiag.AuthWriteFailed)}else{session.diag.Mark(mcrfdiag.AuthWriteOK)}"
Replace-Exact 'transport/mailru/mailru.go' 'time.AfterFunc(authChangesReadyFallback, session.markReady)' 'time.AfterFunc(authChangesReadyFallback, func(){mcrfDiagnosticFallback(session)})'
Replace-Exact 'transport/mailru/mailru.go' 'utils.Debugf("[M-DOCS] Read error: %v", err)' "session.diag.Mark(mcrfdiag.ReadClosed)`n utils.Debugf(`"[M-DOCS] Read error: %v`", err)"
Replace-Exact 'transport/mailru/mailru.go' 'utils.Debugf("[M-DOCS] Write error: %v", err)' "session.diag.Mark(mcrfdiag.ChatWriteFailed)`n utils.Debugf(`"[M-DOCS] Write error: %v`", err)"
Replace-Exact 'transport/mailru/mailru.go' 'pending = nil' "session.diag.Mark(mcrfdiag.ChatWriteOK)`n pending = nil"
Replace-Exact 'transport/mailru/mailru.go' 't.RecordReceive(len(pkt))' "session.diag.Mark(mcrfdiag.PeerChat)`n t.RecordReceive(len(pkt))"
Replace-Exact 'transport/mailru/mcrf_ack.go' 'if session != nil && session.Conn != nil && session.ready != nil {' "if session!=nil {session.diag.Mark(mcrfdiag.AuthChanges)}`n if session != nil && session.Conn != nil && session.ready != nil {"
Replace-Exact 'transport/mailru/mcrf_ack.go' 'session.markReady()' "session.diag.Mark(mcrfdiag.AckOK)`n session.markReady()"
Replace-Exact 'transport/mailru/mcrf_ack.go' "`t`t}`n`t}`n`treturn true" "`t`t} else {session.diag.Mark(mcrfdiag.AckFailed)}`n`t}`n`treturn true"
Replace-Exact 'transport/mailru/mcrf_ack.go' 'func mcrfMailruWriteReady' "func mcrfDiagnosticFallback(s *DocSession){s.diag.Mark(mcrfdiag.Fallback);s.markReady()}`n`nfunc mcrfMailruWriteReady"
Replace-Exact 'transport/encrypted.go' 'type EncryptedTransport struct {' "type EncryptedTransport struct {`n mcrfClassicDiag *mcrfdiag.Run"
Replace-Exact 'transport/encrypted.go' 'e.recvFail.Add(1)' "e.mcrfClassicDiag.Mark(mcrfdiag.ClassicDecryptFailed)`n e.recvFail.Add(1)"
Replace-Exact 'transport/encrypted.go' 'e.recvOK.Add(1)' "e.mcrfClassicDiag.Mark(mcrfdiag.ClassicDecryptOK)`n e.recvOK.Add(1)"
Replace-Exact 'transport/session.go' 'link.classicEnc = enc.SharingKeys(link.classicCodec)' "link.classicEnc = enc.SharingKeys(link.classicCodec)`n link.classicEnc.mcrfClassicDiag = mcrfdiag.Current()"
Replace-Exact 'transport/session.go' 's.cntClassicDrop.Add(1)' "if link.classicEnc!=nil{link.classicEnc.mcrfClassicDiag.Mark(mcrfdiag.ClassicRejected)}`n s.cntClassicDrop.Add(1)"
Replace-Exact 'transport/session.go' 'n := s.cntClassicRecv.Add(1)' "if link.classicEnc!=nil{link.classicEnc.mcrfClassicDiag.Mark(mcrfdiag.ClassicAccepted)}`n n := s.cntClassicRecv.Add(1)"
Replace-Exact 'transport/session.go' 'import (' "import (`n `"github.com/p1neappleXpress/OpenFlux/mcrfdiag`""
Replace-Exact 'mobile/cmd/mcrf-openflux/main.go' 'func main() {' "func main() {`n if len(os.Args)==3 && os.Args[1]==`"--mcrf-diagnostic-config-v1`" {if err:=mcrfRunDiagnostic(os.Args[2]);err!=nil{fmt.Fprintln(os.Stderr,`"OpenFlux diagnostic run failed`");os.Exit(1)};return}"
# Ensure even early invalid-config returns complete StopProxy before a final snapshot.
Replace-Exact 'mobile/cmd/mcrf-openflux/main.go' '    defer mobile.StopProxy()' ''
Replace-Exact 'mobile/cmd/mcrf-openflux/main.go' 'func run(path string) error {' "func run(path string) error {`n    defer mobile.StopProxy()"
# No native build, provider call or packaging. Caller runs only explicit host tests.
& git -C $stage add -N .
& git -C $stage diff --binary --output="$stage/diagnostic.patch"
if($LASTEXITCODE -ne 0){throw 'Cannot record exact source patch'}
$stage
