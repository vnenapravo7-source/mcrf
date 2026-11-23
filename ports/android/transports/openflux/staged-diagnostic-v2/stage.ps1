param([string]$ScratchRoot='D:/MCRF-engine-diagnostic-v2')
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../..'))
$pristine=Join-Path $project 'work/android-upstream/openflux-core-v0.7.3'
$v1='D:/MCRF-engine-diagnostics/83c19dc199c040eb940eb7e573e07d89/diagnostic.patch'
if((Get-FileHash $v1 -Algorithm SHA256).Hash.ToLowerInvariant() -ne '4764e6add52a853c0f5f9534eb2461d3bb8070b9a7a227f15848c5c840885fc6'){throw 'Reviewed V1 changed'}
if((& git -C $pristine rev-parse HEAD) -ne 'f12e981629a18c8c01007d59ac002254c02972bb' -or (& git -C $pristine status --porcelain|Out-String).Trim()){throw 'Upstream identity'}
$stage=Join-Path $ScratchRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage -Force|Out-Null
Get-ChildItem $pristine -Force|Where-Object Name -ne '.git'|Copy-Item -Destination $stage -Recurse
& git -C $stage init --quiet
& git -C $stage add .
& git -C $stage apply $v1
if($LASTEXITCODE -ne 0){throw 'V1 apply'}
# Keep the complete reviewed V1 as index baseline for the bounded V2 diff.
& git -C $stage add .
function Hook([string]$file,[string]$old,[string]$new){$path=Join-Path $stage $file;$txt=[IO.File]::ReadAllText($path).Replace("`r`n","`n");if(!$txt.Contains($old)){throw "Hook missing $file : $old"};[IO.File]::WriteAllText($path,$txt.Replace($old,$new),[Text.UTF8Encoding]::new($false))}
Copy-Item "$PSScriptRoot/events*.go" "$stage/mcrfdiag/"
Copy-Item "$PSScriptRoot/wrapper_v2*.go" "$stage/mobile/cmd/mcrf-openflux/"
Copy-Item "$PSScriptRoot/receive_chain_test.go" "$stage/transport/mcrf_v2_test.go"
Copy-Item "$PSScriptRoot/mailru_chain_test.go" "$stage/transport/mailru/mcrf_v2_test.go"
Hook 'mcrfdiag/diagnostic.go' 'type Run struct { bits atomic.Uint64 }' 'type Run struct { bits atomic.Uint64; version int }'
Hook 'mcrfdiag/diagnostic.go' 'r:=new(Run)' 'r:=&Run{version:1}'
Hook 'mcrfdiag/diagnostic.go' 'r==nil || e>=eventCount' 'r==nil || e>=v2EventCount || (r.version!=2 && e>=eventCount)'
Hook 'mcrfdiag/diagnostic.go' 'flags:=make(map[string]bool,len(names));for i,name:=range names{flags[name]=bits&(1<<i)!=0}' 'keys:=append([]string(nil),names[:]...);prefix:=Prefix;if r.version==2{keys=append(keys,namesV2...);prefix=PrefixV2};flags:=make(map[string]bool,len(keys));for i,name:=range keys{flags[name]=bits&(1<<i)!=0}'
Hook 'mcrfdiag/diagnostic.go' 'return Prefix+string(data)' 'return prefix+string(data)'
foreach($file in @('transport/transport.go','transport/framedemux.go','transport/codec.go')){Hook $file 'import (' "import (`n `"github.com/p1neappleXpress/OpenFlux/mcrfdiag`""}
Hook 'transport/transport.go' 'type BaseTransport struct {' "type BaseTransport struct {`n mcrfDiag *mcrfdiag.Run"
Hook 'transport/transport.go' 'return &BaseTransport{' "return &BaseTransport{`n mcrfDiag:mcrfdiag.Current(),"
Hook 'transport/transport.go' "if cb != nil {`n`t`tcb(data)`n`t}" "if cb != nil {`n`t`tcb(data)`n`t}else{b.mcrfDiag.Mark(mcrfdiag.RawCallbackMissing)}"
Hook 'transport/framedemux.go' 'type frameDemux struct {' "type frameDemux struct {`n mcrfDiag *mcrfdiag.Run"
Hook 'transport/framedemux.go' 'return &frameDemux{raw: raw, name: name}' 'return &frameDemux{raw:raw,name:name,mcrfDiag:mcrfdiag.Current()}'
Hook 'transport/framedemux.go' "if len(p) == 0 {`n`t`treturn" "if len(p) == 0 {`n d.mcrfDiag.Mark(mcrfdiag.DemuxEmpty)`n`t`treturn"
Hook 'transport/framedemux.go' 'if p[0] == encryptedMagic[0] {' "if p[0] == encryptedMagic[0] {`n d.mcrfDiag.Mark(mcrfdiag.DemuxSession)"
Hook 'transport/framedemux.go' "sessionCb(p)`n`t`t}" "sessionCb(p)`n`t`t}else{d.mcrfDiag.Mark(mcrfdiag.LayerCallbackMissing)}"
Hook 'transport/framedemux.go' 'if classicCb != nil {' "d.mcrfDiag.Mark(mcrfdiag.DemuxClassic)`n if classicCb != nil {"
Hook 'transport/framedemux.go' 'if len(p) == 1 && p[0] == 0x00 {' "d.mcrfDiag.Mark(mcrfdiag.LayerCallbackMissing)`n if len(p) == 1 && p[0] == 0x00 {"
Hook 'transport/codec.go' 'type CodecTransport struct {' "type CodecTransport struct {`n mcrfDiag *mcrfdiag.Run"
Hook 'transport/codec.go' 'c := &CodecTransport{' "c := &CodecTransport{`n mcrfDiag:mcrfdiag.Current(),"
Hook 'transport/codec.go' 'utils.Debugf("[CODEC] batch frame' "c.mcrfDiag.Mark(mcrfdiag.CodecDecodeFailed)`n utils.Debugf(`"[CODEC] batch frame"
Hook 'transport/codec.go' 'utils.Debugf("[CODEC] legacy frame' "c.mcrfDiag.Mark(mcrfdiag.CodecDecodeFailed)`n utils.Debugf(`"[CODEC] legacy frame"
Hook 'transport/codec.go' 'n := c.unknown.Add(1)' "c.mcrfDiag.Mark(mcrfdiag.CodecUnknown)`n n := c.unknown.Add(1)"
Hook 'transport/codec.go' "if len(data) == 0 {`n`t`treturn" "if len(data) == 0 {`n c.mcrfDiag.Mark(mcrfdiag.CodecNoPayload)`n`t`treturn"
Hook 'transport/codec.go' 'if len(data) == 1 {' "if len(data) == 1 {`n c.mcrfDiag.Mark(mcrfdiag.CodecNoPayload)"
Hook 'transport/codec.go' "if cb == nil {`n`t`treturn" "if cb == nil {`n c.mcrfDiag.Mark(mcrfdiag.LayerCallbackMissing)`n`t`treturn"
Hook 'transport/codec.go' "for _, p := range pkts {`n`t`tif len(p) > 0 {`n`t`t`tcb(p)`n`t`t}`n`t}" "hadPayload:=false`n for _, p := range pkts {`n`t`tif len(p) > 0 {`n hadPayload=true`n`t`t`tcb(p)`n`t`t}`n`t}`n if !hadPayload{c.mcrfDiag.Mark(mcrfdiag.CodecNoPayload)}"
foreach($hook in @('if len(packet) < minLen {','header[2] != encryptedMagic[2] {','if header[3] != encryptedVersion {')){Hook 'transport/encrypted.go' $hook ($hook+"`n e.mcrfClassicDiag.Mark(mcrfdiag.ClassicHeaderInvalid)")}
Hook 'transport/encrypted.go' 'if header[4] != r.recvDir {' "if header[4] != r.recvDir {`n e.mcrfClassicDiag.Mark(mcrfdiag.ClassicDirectionInvalid)"
Hook 'transport/encrypted.go' 'if !e.rememberNonce(nonce) {' "if !e.rememberNonce(nonce) {`n e.mcrfClassicDiag.Mark(mcrfdiag.ClassicReplayRejected)"
Hook 'mobile/cmd/mcrf-openflux/main.go' 'func main() {' "func main() {`n if len(os.Args)==3 && os.Args[1]==`"--mcrf-diagnostic-config-v2`"{if err:=mcrfRunDiagnosticV2(os.Args[2]);err!=nil{fmt.Fprintln(os.Stderr,`"OpenFlux diagnostic run failed`");os.Exit(1)};return}"
& git -C $stage add -N .
& git -C $stage diff --binary --output="$stage/diagnostic-v2.patch"
if($LASTEXITCODE -ne 0){throw 'V2 diff failed'}
$stage
