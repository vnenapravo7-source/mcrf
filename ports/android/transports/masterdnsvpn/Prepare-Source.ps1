param([Parameter(Mandatory)][string]$Upstream)
$ErrorActionPreference='Stop'
$taskCommit='acbf1c61f90786f41b975d2e2f616afbce292b29'
$taskLock=(Get-Content -LiteralPath "$PSScriptRoot/../transport-lock.json" -Raw|ConvertFrom-Json).masterdnsvpn
if($taskLock.commit -cne $taskCommit -or $taskLock.repository -cne 'masterking32/MasterDnsVPN'){throw 'MasterDnsVPN source lock changed'}
$taskUpstream=(Resolve-Path -LiteralPath $Upstream).Path
if($taskUpstream.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p4-masterdnsvpn-upstream-[a-f0-9]{32}$'){throw 'Exact owned pinned source copy required'}
if((& git -C $taskUpstream rev-parse HEAD).Trim() -cne $taskCommit -or (& git -C $taskUpstream status --porcelain --untracked-files=all)){throw 'Exact clean MasterDnsVPN source required'}
$taskStage="D:/MCRF/Builds/Android/p4-masterdnsvpn-source-$([guid]::NewGuid().ToString('N'))"
$taskModule="$taskStage/module";New-Item -ItemType Directory -Path $taskModule|Out-Null
$taskRows=@()
foreach($taskRelative in (& git -C $taskUpstream ls-files)){
 $taskFrom=Join-Path $taskUpstream $taskRelative
 if(!(Test-Path -LiteralPath $taskFrom -PathType Leaf)){continue}
 if((Get-Item -LiteralPath $taskFrom).LinkType){throw 'Regular upstream source required'}
 $taskBlob=(& git -C $taskUpstream rev-parse "HEAD:$taskRelative").Trim()
 if((& git -C $taskUpstream hash-object -- $taskRelative).Trim() -cne $taskBlob){throw 'MasterDnsVPN pinned Git object mismatch'}
 $taskTo=Join-Path $taskModule $taskRelative;New-Item -ItemType Directory -Path (Split-Path $taskTo) -Force|Out-Null
 Copy-Item -LiteralPath $taskFrom -Destination $taskTo
 $taskRows+=@{path=$taskRelative;gitBlob=$taskBlob;sha256=(Get-FileHash -LiteralPath $taskFrom).Hash.ToLowerInvariant()}
}
function Replace-Exact([string]$Path,[string]$Old,[string]$New){
 $taskText=[IO.File]::ReadAllText($Path).Replace("`r`n","`n")
 if(($taskText.Split([string[]]@($Old),[StringSplitOptions]::None).Count-1) -ne 1){throw "MasterDnsVPN source anchor changed: $Path"}
 [IO.File]::WriteAllText($Path,$taskText.Replace($Old,$New),[Text.UTF8Encoding]::new($false))
}
New-Item -ItemType Directory -Path "$taskModule/cmd/mcrf"|Out-Null
Copy-Item -LiteralPath "$PSScriptRoot/main.go" -Destination "$taskModule/cmd/mcrf/main.go"
foreach($taskPair in @(@{name='mcrf_config.go';directory='config'},@{name='mcrf_logger.go';directory='logger'},@{name='mcrf_client.go';directory='client'},@{name='mcrf_listener.go';directory='client'})){
 Copy-Item -LiteralPath "$PSScriptRoot/overlay/$($taskPair.name)" -Destination "$taskModule/internal/$($taskPair.directory)/$($taskPair.name)"
}
Replace-Exact "$taskModule/internal/client/client.go" 'type Client struct {' "type Client struct {`n`tmcrfContext context.Context`n`tmcrfEvent func(string)`n`tmcrfWorkerLimit int"
Replace-Exact "$taskModule/internal/client/client.go" ("`t`"context`"") ("`t`"context`"`n`t`"errors`"")
Copy-Item -LiteralPath "$PSScriptRoot/overlay/mcrf_config_test.go" -Destination "$taskModule/internal/config/mcrf_config_test.go"
Copy-Item -LiteralPath "$PSScriptRoot/overlay/mcrf_client_test.go" -Destination "$taskModule/internal/client/mcrf_client_test.go"
New-Item -ItemType Directory -Path "$taskModule/mcrf-integration"|Out-Null
foreach($taskName in @('owned_socks_test.go','owned_wire_test.go')){Copy-Item -LiteralPath "$PSScriptRoot/integration/$taskName" -Destination "$taskModule/mcrf-integration/$taskName"}
Replace-Exact "$taskModule/internal/client/client.go" ("`t`t`tif !c.successMTUChecks {") ("`t`t`tif !c.successMTUChecks {`n`t`t`t`tc.mcrfStage(`"mtu`")")
Replace-Exact "$taskModule/internal/client/client.go" 'if err := c.RunInitialMTUTests(ctx); err != nil {' "if err := c.RunInitialMTUTests(ctx); err != nil {`n`t`t`t`t`tc.mcrfStage(`"mtu-failed`")"
Replace-Exact "$taskModule/internal/client/client.go" ("`t`t`t`tif err := c.InitializeSession(retries); err != nil {") ("`t`t`t`tc.mcrfStage(`"session`")`n`t`t`t`tif err := c.InitializeSession(retries); err != nil {")
Replace-Exact "$taskModule/internal/client/client.go" 'if err := c.InitializeSession(retries); err != nil {' "if err := c.InitializeSession(retries); err != nil {`n`t`t`t`t`tif errors.Is(err, ErrSessionInitBusy) { c.mcrfStage(`"busy`") } else { c.mcrfStage(`"session-failed`") }"
Replace-Exact "$taskModule/internal/client/client.go" ("`t`t`t`tc.InitVirtualStream0()") ("`t`t`t`tc.InitVirtualStream0()`n`t`t`t`tc.mcrfStage(`"ready`")")
Replace-Exact "$taskModule/internal/client/client.go" ("`t`t`tcase <-c.sessionResetSignal:") ("`t`t`tcase <-c.sessionResetSignal:`n`t`t`t`tc.mcrfStage(`"lost`")")
Replace-Exact "$taskModule/internal/client/session.go" 'ctx, cancel := context.WithCancel(context.Background())' 'ctx, cancel := context.WithCancel(c.mcrfRootContext())'
Replace-Exact "$taskModule/internal/client/session.go" ("`tc.cfg.RX_TX_Workers = settings.RXTXWorkers") ("`tsettings.RXTXWorkers = c.mcrfRXWorkers(settings.RXTXWorkers)`n`tc.cfg.RX_TX_Workers = settings.RXTXWorkers")
Replace-Exact "$taskModule/internal/client/session.go" ("`t`tcase <-time.After(30 * time.Second): // Hard safety timeout") ("`t`tcase <-ctx.Done():`n`t`t`treturn ctx.Err()`n`t`tcase <-time.After(30 * time.Second): // Hard safety timeout")
# Preserve the protocol handlers but transfer admission ownership to actual
# stream Close, not just return from the asynchronous SOCKS handshake function.
$taskListener="$taskModule/internal/client/tcp_listener.go"
$taskText=[IO.File]::ReadAllText($taskListener).Replace("`r`n","`n")
$taskStart=$taskText.IndexOf("`t`tgo func(activeListener net.Listener) {")
if($taskStart -lt 0){throw 'MasterDnsVPN listener anchor missing'}
$taskEnd=$taskText.IndexOf("`t`t}(listener)",$taskStart)
if($taskEnd -lt 0 -or $taskText.IndexOf("`t`tgo func(activeListener net.Listener) {",$taskStart+1) -ge 0){throw 'MasterDnsVPN listener anchor is not unique'}
$taskText=$taskText.Substring(0,$taskStart)+"`t`tgo l.mcrfAccept(ctx, listener)"+$taskText.Substring($taskEnd+"`t`t}(listener)".Length)
[IO.File]::WriteAllText($taskListener,$taskText,[Text.UTF8Encoding]::new($false))
Replace-Exact $taskListener ("`t`"time`"`n") ''
$taskFiles=@(Get-ChildItem -LiteralPath $taskModule -Recurse -File|ForEach-Object{@{path=[IO.Path]::GetRelativePath($taskModule,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}})
[IO.File]::WriteAllText("$taskStage/SOURCE_MANIFEST.json",($taskFiles|Sort-Object path|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$taskReceipt=@{state='SOURCE_PREPARED_NOT_COMPILED';kind='masterdnsvpn';repository=$taskLock.repository;commit=$taskCommit;module=$taskModule;upstream=$taskRows;manifestSha256=(Get-FileHash -LiteralPath "$taskStage/SOURCE_MANIFEST.json").Hash.ToLowerInvariant();compiled=$false;executed=$false;androidIntegrated=$false}
[IO.File]::WriteAllText("$taskStage/receipt.json",($taskReceipt|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
Write-Output "P4_MASTERDNS_SOURCE=$taskStage"
