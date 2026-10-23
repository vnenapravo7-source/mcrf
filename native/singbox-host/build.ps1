param([string]$WorkRoot='D:/MCRF/Work/Windows/singbox-private-control-20261022',[switch]$Resume)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath($WorkRoot);if(!$root.StartsWith('D:\MCRF\Work\Windows\',[StringComparison]::OrdinalIgnoreCase)){throw 'Windows-owned work root required'}
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'));$pristine=Join-Path $project 'work/android-upstream/sing-box-lx-mcrf-clash-20261014';$pin='3d3d3db3a2ccef3b1a8eb71b831ed2461c54b94d'
if((& git -C $pristine rev-parse HEAD) -ne $pin){throw 'Pinned core source mismatch'}
if((Test-Path -LiteralPath $root)-and !$Resume){throw 'Build exists; resume exact retained stage, never overwrite it'}
New-Item -ItemType Directory -Path $root -Force|Out-Null
$stage=Join-Path $root 'source';New-Item -ItemType Directory -Path $stage -Force|Out-Null
if(!$Resume){
& git -C $pristine archive --format=tar --output="$root\source.tar" $pin;if($LASTEXITCODE){throw 'Source export failed'}
& tar -xf "$root\source.tar" -C $stage;if($LASTEXITCODE){throw 'Source extraction failed'}
}
foreach($entry in (& git -C $pristine config --file .gitmodules --get-regexp path)){ $path=($entry -split '\s+',2)[1];if(!$path.StartsWith('submodules/')){continue};$sub=Join-Path $pristine $path;$target=Join-Path $stage $path;if(Test-Path -LiteralPath (Join-Path $target 'go.mod')){continue};New-Item -ItemType Directory -Path $target -Force|Out-Null;$archive=Join-Path $root (($path.Replace('/','-'))+'.tar');& git -C $sub archive --format=tar --output=$archive HEAD;if($LASTEXITCODE){throw "Submodule export failed: $path"};& tar -xf $archive -C $target;if($LASTEXITCODE){throw 'Submodule extraction failed'}}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'mcrf_control.go') -Destination (Join-Path $stage 'cmd/sing-box/mcrf_control.go')
foreach($taskFakeFile in @('mcrf_fakeip.go','mcrf_fakeip_test.go')){Copy-Item -LiteralPath (Join-Path $PSScriptRoot $taskFakeFile) -Destination (Join-Path $stage ('route/'+$taskFakeFile))}
foreach($taskV4File in @('mcrf_ipv4.go','mcrf_ipv4_test.go')){Copy-Item -LiteralPath (Join-Path $PSScriptRoot $taskV4File) -Destination (Join-Path $stage ('route/'+$taskV4File))}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'mcrf_fakeip_metadata.go') -Destination (Join-Path $stage 'adapter/mcrf_fakeip.go')
$taskRouteFile=Join-Path $stage 'route/route.go';$taskRouteText=[IO.File]::ReadAllText($taskRouteFile).Replace("`r`n","`n")
if(!$taskRouteText.Contains('mcrfRecoverExternalFakeIP(&metadata, selectedOutbound.Type())')){$taskRouteAnchor='metadata.RouteOutbound = selectedOutbound.Tag()';if(([regex]::Matches($taskRouteText,[regex]::Escape($taskRouteAnchor))).Count -ne 2){throw 'TCP/UDP dispatch boundary changed'};$taskRouteText=$taskRouteText.Replace($taskRouteAnchor,"mcrfRecoverExternalFakeIP(&metadata, selectedOutbound.Type())`n`t"+$taskRouteAnchor);[IO.File]::WriteAllText($taskRouteFile,$taskRouteText,[Text.UTF8Encoding]::new($false))}
if(!$taskRouteText.Contains('mcrfRecoverIPv4OnlyDestination(&metadata')){$taskV4Anchor='mcrfRecoverExternalFakeIP(&metadata, selectedOutbound.Type())';if(([regex]::Matches($taskRouteText,[regex]::Escape($taskV4Anchor))).Count -ne 2){throw 'IPv4-only dispatch boundary changed'};$taskRouteText=$taskRouteText.Replace($taskV4Anchor,$taskV4Anchor+"`n`tmcrfRecoverIPv4OnlyDestination(&metadata, selectedOutbound, r.outbound)");}
$taskRouteText=$taskRouteText.Replace("`t`t`tmcrfRecoverExternalFakeIP(metadata, `"`")`n",'');[IO.File]::WriteAllText($taskRouteFile,$taskRouteText,[Text.UTF8Encoding]::new($false))
if($taskRouteText.Contains('restore authoritative external DNS name')){$taskRouteText=$taskRouteText.Replace('if adapter.McrfIsExternalFakeIP(metadata.Destination.Addr) { if name, ok := r.dns.LookupReverseMapping(metadata.Destination.Addr); ok { metadata.Domain = name } }','mcrfRestoreExternalDNSName(metadata, r.dns)')}
if(!$taskRouteText.Contains('mcrfRestoreExternalDNSName(metadata, r.dns)')){$taskSniffBoundary="`t`tcase *R.RuleActionResolve:`n`t`t`tfatalErr = r.actionResolve";if(([regex]::Matches($taskRouteText,[regex]::Escape($taskSniffBoundary))).Count -ne 1){throw 'Sniff DNS mapping boundary changed'};$taskRouteText=$taskRouteText.Replace($taskSniffBoundary,"`t`t`tmcrfRestoreExternalDNSName(metadata, r.dns)`n"+$taskSniffBoundary)}
[IO.File]::WriteAllText($taskRouteFile,$taskRouteText,[Text.UTF8Encoding]::new($false))
$taskPrivateFile=Join-Path $stage 'route/rule/rule_item_ip_is_private.go';$taskPrivateText=[IO.File]::ReadAllText($taskPrivateFile).Replace("`r`n","`n")
if(!$taskPrivateText.Contains('adapter.McrfIsExternalFakeIP')){$taskPrivateAnchor="`tif metadata.DestinationAddressMatchFromResponse {";if(([regex]::Matches($taskPrivateText,[regex]::Escape($taskPrivateAnchor))).Count -ne 1){throw 'Private-address match boundary changed'};$taskPrivateText=$taskPrivateText.Replace($taskPrivateAnchor,"`tif metadata.InboundType == `"tun`" && adapter.McrfIsExternalFakeIP(metadata.Destination.Addr) { return false }`n"+$taskPrivateAnchor);[IO.File]::WriteAllText($taskPrivateFile,$taskPrivateText,[Text.UTF8Encoding]::new($false))}
$taskSocksOptionFile=Join-Path $stage 'option/simple.go';$taskSocksOptions=[IO.File]::ReadAllText($taskSocksOptionFile)
if(!$taskSocksOptions.Contains('McrfIPv4Only')){$taskAnchor='type SOCKSOutboundOptions struct {';if(!$taskSocksOptions.Contains($taskAnchor)){throw 'SOCKS options boundary changed'};$taskSocksOptions=$taskSocksOptions.Replace($taskAnchor,$taskAnchor+"`n McrfIPv4Only bool ``json:`"mcrf_ipv4_only,omitempty`"``");[IO.File]::WriteAllText($taskSocksOptionFile,$taskSocksOptions,[Text.UTF8Encoding]::new($false))}
$taskSocksFile=Join-Path $stage 'protocol/socks/outbound.go';$taskSocks=[IO.File]::ReadAllText($taskSocksFile).Replace("`r`n","`n")
if(!$taskSocks.Contains('mcrfIPv4Only')){if(!$taskSocks.Contains('type Outbound struct {')-or !$taskSocks.Contains('resolve:   version == socks.Version4,')){throw 'SOCKS implementation boundary changed'};$taskSocks=$taskSocks.Replace('type Outbound struct {',"type Outbound struct {`n mcrfIPv4Only bool").Replace('resolve:   version == socks.Version4,',"resolve:   version == socks.Version4,`n mcrfIPv4Only: options.McrfIPv4Only,")+"`nfunc (h *Outbound) McrfIPv4Only() bool { return h.mcrfIPv4Only }`n";[IO.File]::WriteAllText($taskSocksFile,$taskSocks,[Text.UTF8Encoding]::new($false))}
$file=Join-Path $stage 'cmd/sing-box/cmd_run.go';$text=[IO.File]::ReadAllText($file)
if(!$text.Contains('startMcrfControl()')){
$needle='startCtx, finishStart := context.WithCancel(context.Background())';if(!$text.Contains($needle)){throw 'Startup boundary changed'}
$text=$text.Replace($needle,$needle+"`n`tgo func(){ select {case <-mcrfStop: cancel(); closeMonitor(startCtx); case <-startCtx.Done():} }()")
$needle="func run() error {";if(!$text.Contains($needle)){throw 'Run boundary changed'};$text=$text.Replace($needle,$needle+"`n`tstartMcrfControl()")
$needle='osSignal := <-osSignals';if(!$text.Contains($needle)){throw 'Shutdown boundary changed'};$text=$text.Replace($needle,"var osSignal os.Signal`n`t`t`tselect {case osSignal = <-osSignals: case <-mcrfStop: osSignal = os.Interrupt}")
[IO.File]::WriteAllText($file,$text,[Text.UTF8Encoding]::new($false))
}
$go='D:/MCRF/Archive/Windows-original/work/toolchains/go1.26.8/go/bin/go.exe';$env:GOTOOLCHAIN='local';$env:CGO_ENABLED='0';$env:GOOS='windows';$env:GOARCH='amd64';$env:GOPATH='D:/MCRF/Archive/Windows-original/work/toolchains/go-home';$env:GOMODCACHE='D:/MCRF/Archive/Windows-original/work/toolchains/go-cache';$env:GOCACHE='D:/MCRF/Work/Windows/singbox-go-build-cache'
$tags='with_gvisor,with_quic,with_dhcp,with_wireguard,with_utls,with_clash_api,with_naive_outbound,with_purego,badlinkname,tfogo_checklinkname0,with_xhttp,with_awg,with_lx_command,with_lxd,with_openvpn,with_openconnect,with_lx_chain,with_tailscale'
Push-Location $stage;try{& $go build -buildvcs=false -trimpath -tags $tags -ldflags '-H windowsgui -s -w -buildid= -checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=1.14.2-lx.11-mcrf-stdin1' -o "$root\sing-box.exe" ./cmd/sing-box 2>&1|Tee-Object -FilePath "$root\build.log";if($LASTEXITCODE){throw 'Private Windows core build failed; stage retained'}}finally{Pop-Location}
Get-FileHash "$root\sing-box.exe"
