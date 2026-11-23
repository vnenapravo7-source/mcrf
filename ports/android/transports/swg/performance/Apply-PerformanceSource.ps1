param([Parameter(Mandatory)][string]$ModuleSource,[Parameter(Mandatory)][ValidateSet('csqtt','csqtt25','qwdtt','wdtt')][string]$Kind,
      [switch]$VerifyOnly,[string]$BaselineModule='', [switch]$CanonicalProducer,[object]$SocksUdpOverlay)
$ErrorActionPreference='Stop'
$taskModule=[IO.Path]::GetFullPath($ModuleSource)
$taskBoundary=if($CanonicalProducer){'^D:\\MCRF\\Projects\\MCRF\\work\\ports-test-2026-10-14\\swg-mobile-[0-9a-f]{32}\\(qwdtt\\qwdtt|wdtt\\go_client)$'}else{'^D:\\MCRF\\Builds\\Android\\p3-(turn|csqtt25)-source-[0-9a-f]{32}\\module$'}
if($taskModule -cnotmatch $taskBoundary){throw 'Performance changes require a new owned producer source copy, not accepted/pristine sources'}
# Reject redirected ancestors as well as the module itself. Never follow a
# junction into an accepted build input or an upstream checkout.
for($taskParent=Get-Item -LiteralPath $taskModule; $null -ne $taskParent; $taskParent=$taskParent.Parent){
 if($taskParent.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Redirected producer source refused'}
}
$taskPending=@{}
if($VerifyOnly -and (!$BaselineModule -or !(Test-Path -LiteralPath $BaselineModule -PathType Container))){throw 'Delta verification requires the exact retained source module'}
function Replace-Exact([string]$Path,[string]$Old,[string]$New){
 $taskRead=if($VerifyOnly){Join-Path $BaselineModule ([IO.Path]::GetRelativePath($taskModule,$Path))}else{$Path}
 if((Get-Item -LiteralPath $taskRead).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Redirected source file refused'}
 $taskText=if($taskPending.ContainsKey($Path)){$taskPending[$Path]}else{[IO.File]::ReadAllText($taskRead).Replace("`r`n","`n")}
 if($taskText.Split(@($Old),[StringSplitOptions]::None).Count -ne 2){throw "Performance source anchor changed: $([IO.Path]::GetFileName($Path))"}
 $taskPending[$Path]=$taskText.Replace($Old,$New)
}
function Copy-Overlay([string]$Name,[string]$SourceDirectory=$PSScriptRoot){
 $taskTarget=Join-Path $taskModule $Name
 if($VerifyOnly){
  if((Get-FileHash -LiteralPath $taskTarget).Hash -cne (Get-FileHash -LiteralPath "$SourceDirectory/$Name").Hash){throw 'Performance overlay drift'}
 }else{
  if(Test-Path -LiteralPath $taskTarget){throw 'Performance overlay already applied; preserve this source copy'}
  Copy-Item -LiteralPath "$SourceDirectory/$Name" -Destination $taskTarget
 }
}
foreach($taskName in @('mcrf_transport_performance.go','mcrf_transport_performance_test.go')){
 Copy-Overlay $taskName
}
foreach($taskName in @('mcrf_streams.go','mcrf_streams_test.go')){ Copy-Overlay $taskName }
# Count data-stream owners only after dispatcher registration; failed allocation,
# handshakes, probes and SOCKS clients are not active TURN streams.
Replace-Exact "$taskModule/session.go" 'd.Register(slot)' "d.Register(slot)`n`tmcrfReleaseStream := mcrfNativeStreams.acquire()"
Replace-Exact "$taskModule/session.go" 'defer d.Unregister(slot)' 'defer func() { d.Unregister(slot); mcrfReleaseStream() }()'
Replace-Exact "$taskModule/main.go" 'func main() {' "func main() {`n`tmcrfNativeStreams.report(0)"
if($Kind -ne 'wdtt'){
 # Registered before all cleanup defers: final zero follows child reap AND
 # scanner drain, including startup error, STOP, stdin EOF and forced child exit.
 Replace-Exact "$taskModule/csqtt_bridge.go" 'processOwner, err := mcrfPrepareProcessGroup()' "mcrfNativeStreams.useChild()`n`tdefer mcrfNativeStreams.report(0)`n`tprocessOwner, err := mcrfPrepareProcessGroup()"
 Replace-Exact "$taskModule/bridge_config.go" 'type bridgeConfig struct {' ("type bridgeConfig struct {`n`tMcrfSampleStreamStats bool " + '`json:"-"`' + "`n")
 Replace-Exact "$taskModule/csqtt_bridge.go" 'line := scanner.Text()' "line := scanner.Text()`n`t`tif mcrfForwardChildStreams(line, c.McrfSampleStreamStats, mcrfNativeStreams) { continue }"
 if($Kind -eq 'csqtt25'){
  Replace-Exact "$taskModule/mcrf_csqtt25.go" 'NormalizeTunnel: csqtt25.NormalizeTunnel,' "NormalizeTunnel: csqtt25.NormalizeTunnel,`n`t`tMcrfSampleStreamStats: true,"
 }
 Copy-Overlay 'mcrf_raw_upload_test.go'
 Replace-Exact "$taskModule/raw_netstack.go" 'netstack.CreateNetTUN(addresses, dns, mtu)' 'netstack.CreateNetTUN(addresses, dns, mcrfRawTransportMTU(mtu))'
 $taskRelay='relay := make(chan struct{})'
 $taskTune=@'
// One loopback IP relay, not every TURN worker. Keep a functioning
// socket if Android refuses an optional buffer request.
if tuneErr := mcrfConfigureIPRelaySocket(socket); tuneErr != nil {
 fmt.Printf("[IP-адаптер] Не удалось увеличить буфер: %v\n", tuneErr)
}
relay := make(chan struct{})
'@
 # csqtt_bridge.go already imports fmt; retain process/IPC ownership code.
 Replace-Exact "$taskModule/csqtt_bridge.go" $taskRelay $taskTune
}
# The loopback WG IP socket also serves QWDTT/WDTT. Leave session.go's
# per-worker625KiB and transport queues untouched (126*8MiB would hurt Android).
Replace-Exact "$taskModule/main.go" "_ = uc.SetReadBuffer(socketBufSize)`n`t`t_ = uc.SetWriteBuffer(socketBufSize)" @'
if tuneErr := mcrfConfigureIPRelaySocket(uc); tuneErr != nil {
 log.Printf("[IP-адаптер] Предупреждение настройки буфера: %v", tuneErr)
}
'@
if($Kind -eq 'wdtt'){
 foreach($taskName in @('mcrf_underlay.go','mcrf_underlay_android.go','mcrf_underlay_test.go','mcrf_underlay_android_test.go')){
  Copy-Overlay $taskName (Split-Path $PSScriptRoot -Parent)
 }
 Replace-Exact "$taskModule/main.go" 'mcrfNativeStreams.report(0)' "mcrfNativeStreams.report(0)`n`tif err := mcrfConfigureWdttUnderlay(); err != nil { log.Print(err); os.Exit(1) }"
 Replace-Exact "$taskModule/main.go" "`tsetupGlobalResolver()`n" ''
 Replace-Exact "$taskModule/main.go" 'func main() {' "var mcrfWdttSetupResolver = setupGlobalResolver`n`nfunc main() {"
 Replace-Exact "$taskModule/main.go" "`tif *checkHashes {" @'
	if ctx.Err() != nil {
		return
	}
	mcrfWdttSetupResolver()
	if *checkHashes {
'@
 Replace-Exact "$taskModule/session.go" 'dialer := &net.Dialer{Timeout: 6 * time.Second}' 'dialer := mcrfUnderlayDialer(net.Dialer{Timeout: 6 * time.Second})'
 Replace-Exact "$taskModule/session.go" 'dialer := &net.Dialer{Timeout: 6 * time.Second, KeepAlive: 30 * time.Second}' 'dialer := mcrfUnderlayDialer(net.Dialer{Timeout: 6 * time.Second, KeepAlive: 30 * time.Second})'
 Replace-Exact "$taskModule/session.go" 'NetDialer: &net.Dialer{Timeout: 8 * time.Second, KeepAlive: 30 * time.Second},' 'NetDialer: &transportDialer,'
 Replace-Exact "$taskModule/session.go" 'dialer := &tls.Dialer{' "transportDialer := mcrfUnderlayDialer(net.Dialer{Timeout: 8 * time.Second, KeepAlive: 30 * time.Second})`n`tdialer := &tls.Dialer{"
 foreach($taskCredentials in @('creds.go','creds_vkcalls.go')){
  Replace-Exact "$taskModule/$taskCredentials" 'tlsclient.NewHttpClient(' 'mcrfNewUnderlayHTTPClient('
 }
 Replace-Exact "$taskModule/dns_resolver.go" @'
	dialer := &net.Dialer{
		Timeout:   1200 * time.Millisecond,
		KeepAlive: 30 * time.Second,
	}
'@ @'
	dialer := mcrfUnderlayDialer(net.Dialer{Timeout: 1200 * time.Millisecond, KeepAlive: 30 * time.Second})
'@
 Replace-Exact "$taskModule/masque_warp.go" @'
	dialer := &net.Dialer{
		Timeout:   warpAPIDialTimeout,
		KeepAlive: 30 * time.Second,
		Resolver:  deviceSystemResolver,
	}
'@ @'
	dialer := mcrfUnderlayDialer(net.Dialer{Timeout: warpAPIDialTimeout, KeepAlive: 30 * time.Second, Resolver: deviceSystemResolver})
'@
 Replace-Exact "$taskModule/masque_warp.go" 'dialer := &net.Dialer{Timeout: warpAPIDialTimeout, KeepAlive: 30 * time.Second}' 'dialer := mcrfUnderlayDialer(net.Dialer{Timeout: warpAPIDialTimeout, KeepAlive: 30 * time.Second})'
 Replace-Exact "$taskModule/masque_warp.go" 'raw, err := (&net.Dialer{Timeout: warpConnectTimeout, KeepAlive: 30 * time.Second}).DialContext(dialCtx, network, endpoint.String())' "dialer := mcrfUnderlayDialer(net.Dialer{Timeout: warpConnectTimeout, KeepAlive: 30 * time.Second})`n`t`t`t`traw, err := dialer.DialContext(dialCtx, network, endpoint.String())"
 Replace-Exact "$taskModule/masque_warp.go" 'net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero})' 'mcrfUnderlayListenUDP4(ctx)'
 Replace-Exact "$taskModule/socks5_server.go" 'defer clients.Wait()' 'defer func() { cancelServer(); clients.Wait() }()'
 Replace-Exact "$taskModule/socks5_server.go" 'clientSlots := make(chan struct{}, 64)' 'clientSlots := make(chan struct{}, mcrfSOCKSMaxClients)'
 Replace-Exact "$taskModule/socks5_server.go" "for {`n`t`tclient, err := listener.Accept()" "for {`n`t`tif !mcrfAcquireSOCKSSlot(serverCtx, clientSlots) { return nil }`n`t`tclient, err := listener.Accept()"
 Replace-Exact "$taskModule/socks5_server.go" "if err != nil {`n`t`t`tif ctx.Err() != nil || errors.Is(err, net.ErrClosed) {`n`t`t`t`treturn nil`n`t`t`t}`n`t`t`treturn fmt.Errorf(`"приём SOCKS5-клиента: %w`", err)" "if err != nil {`n`t`t`t<-clientSlots`n`t`t`tif ctx.Err() != nil || errors.Is(err, net.ErrClosed) {`n`t`t`t`treturn nil`n`t`t`t}`n`t`t`treturn fmt.Errorf(`"приём SOCKS5-клиента: %w`", err)"
 Replace-Exact "$taskModule/socks5_server.go" @'
		select {
		case clientSlots <- struct{}{}:
		default:
			_ = client.Close()
			continue
		}
'@ ''
 Copy-Overlay 'mcrf_wdtt_capacity_test.go'
}
# CSQTT/QWDTT's authenticated bridge has no fixed64 reset path. Do not replace
# it with Windows' server or downgrade its half-close/UDP/auth handling.
foreach($taskPath in $taskPending.Keys){
 if($VerifyOnly){
  if([IO.File]::ReadAllText($taskPath).Replace("`r`n","`n") -cne $taskPending[$taskPath]){
   if(!$SocksUdpOverlay -or $Kind -cne 'wdtt' -or [IO.Path]::GetFileName($taskPath) -cne 'socks5_server.go' -or
      $SocksUdpOverlay.contract -ne 1 -or $SocksUdpOverlay.mode -cne $Kind -or
      $SocksUdpOverlay.overlaySha256 -cne (Get-FileHash -LiteralPath "$PSScriptRoot/../../socks-owner/overlay.py").Hash.ToLowerInvariant() -or
      $SocksUdpOverlay.inputs.'socks5_server.go' -cne [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($taskPending[$taskPath]))).ToLowerInvariant() -or
      $SocksUdpOverlay.files.'socks5_server.go' -cne (Get-FileHash -LiteralPath $taskPath).Hash.ToLowerInvariant()){throw 'Source differs from exact reviewed performance delta'}
  }
 }else{[IO.File]::WriteAllText($taskPath,$taskPending[$taskPath],[Text.UTF8Encoding]::new($false))}
}
Write-Output $(if($VerifyOnly){"P3_PERFORMANCE_DELTA_VERIFIED=$Kind"}else{"P3_PERFORMANCE_SOURCE=$Kind"})
