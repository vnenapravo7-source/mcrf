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
