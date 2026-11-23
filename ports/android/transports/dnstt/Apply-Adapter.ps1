param([Parameter(Mandatory)][string]$Module)
$ErrorActionPreference='Stop'
$taskModule=(Resolve-Path -LiteralPath $Module).Path
if($taskModule.Replace([char]92,[char]47) -notmatch '^D:/MCRF/(Builds/Android/|Projects/MCRF/work/(CompatibilityImportDnstt-native-|platform-dependencies/dnstt-mobile-))'){throw 'Owned DNSTT build copy required, never the Go module cache'}
$taskMain=Join-Path $taskModule 'dnstt-client/main.go'
$taskText=[IO.File]::ReadAllText($taskMain).Replace("`r`n","`n")
function Replace-Exact([string]$Old,[string]$New){
 if(($script:taskText.Split([string[]]@($Old),[StringSplitOptions]::None).Count-1) -ne 1){throw 'Pinned DNSTT source anchor changed'}
 $script:taskText=$script:taskText.Replace($Old,$New)
}
Replace-Exact 'func handle(local *net.TCPConn, sess *smux.Session, conv uint32) error {' "func handle(local *net.TCPConn, sess *smux.Session, conv uint32) error {`n`tif err := mobileAuth(local); err != nil { return err }"
Replace-Exact 'log.Printf("begin stream %08x:%d", conv, stream.ID())' "log.Printf(`"begin stream %08x:%d`", conv, stream.ID())`n`tstream.SetDeadline(time.Now().Add(30*time.Second))`n`tif err := mobileRemote(stream); err != nil { return err }`n`tstream.SetDeadline(time.Time{})"
$taskOld=@'
	rw, err := noise.NewClient(conn, pubkey)
	if err != nil {
		return err
	}
'@
$taskNew=@'
	if mobile != nil { conn.SetDeadline(time.Now().Add(20*time.Second)) }
	rw, err := noise.NewClient(conn, pubkey)
	if err != nil {
		if mobile != nil { mobileHandshakeFailure(err) }
		return err
	}
	if mobile != nil { conn.SetDeadline(time.Time{}) }
'@
Replace-Exact $taskOld.Replace("`r`n","`n") $taskNew.Replace("`r`n","`n")
Replace-Exact ("`tdefer sess.Close()") ("`tdefer sess.Close()`n`tif mobile != nil { defer mobileWatchSession(ln, sess.CloseChan())() } ")
$taskUnderlayMain=@'
func main() {
	if mobile != nil {
		var err error
		mobileResolverUnderlay, err = newDNSTTUnderlay(os.Getenv(dnsttUnderlayEnv), dnsttBindProcess, dnsttBindSocket)
		if err != nil {
			mobileReport("DNSTT_UNDERLAY_REJECTED")
			os.Exit(2)
		}
	}
'@
Replace-Exact 'func main() {' $taskUnderlayMain.Replace("`r`n","`n")
Replace-Exact 'transport.Proxy = nil' ("transport.Proxy = nil`n`t`t`t`tif mobileResolverUnderlay.control != nil { transport.DialContext = mobileResolverUnderlay.httpDialer().DialContext }")
Replace-Exact '(&tls.Dialer{}).DialContext' '(&tls.Dialer{NetDialer: mobileResolverUnderlay.dialer()}).DialContext'
Replace-Exact 'net.ListenUDP("udp", nil)' 'mobileResolverUnderlay.listenUDP(context.Background())'
[IO.File]::WriteAllText($taskMain,$taskText,[Text.UTF8Encoding]::new($false))
$taskUTLS=Join-Path $taskModule 'dnstt-client/utls.go'
$taskText=[IO.File]::ReadAllText($taskUTLS).Replace("`r`n","`n")
Replace-Exact 'dialer := &net.Dialer{}' 'dialer := mobileResolverUnderlay.dialer()'
[IO.File]::WriteAllText($taskUTLS,$taskText,[Text.UTF8Encoding]::new($false))
foreach($taskName in @('mcrf_config.go','mcrf_lifecycle.go','mcrf_underlay.go','mcrf_underlay_android.go','mcrf_underlay_other.go')){Copy-Item -LiteralPath "$PSScriptRoot/$taskName" -Destination "$taskModule/dnstt-client/$taskName" -Force}
