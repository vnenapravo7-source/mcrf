param([Parameter(Mandatory)][string]$Upstream)
$ErrorActionPreference='Stop'
$taskCommit='8463d3397b0a8522f92811f5c0698679ab8efc77'
$taskLock=(Get-Content -LiteralPath "$PSScriptRoot/../transport-lock.json" -Raw|ConvertFrom-Json).sstp
if($taskLock.commit -cne $taskCommit -or $taskLock.repository -cne 'ttrroyy/qThroneMobile'){throw 'SSTP source lock changed'}
$taskUpstream=(Resolve-Path -LiteralPath $Upstream).Path
if($taskUpstream.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p4-sstp-upstream-[a-f0-9]{32}$'){throw 'Exact owned pinned source copy required'}
if((& git -C $taskUpstream rev-parse HEAD).Trim() -cne $taskCommit -or (& git -C $taskUpstream status --porcelain --untracked-files=all)){throw 'Exact clean qThrone SSTP source required'}
$taskStage="D:/MCRF/Builds/Android/p4-sstp-source-$([guid]::NewGuid().ToString('N'))"
$taskModule="$taskStage/module";New-Item -ItemType Directory -Path $taskModule|Out-Null
$taskRows=@()
foreach($taskRelative in (& git -C $taskUpstream ls-files -- carriers/sstp)){
 $taskFrom=Join-Path $taskUpstream $taskRelative
 if(!(Test-Path -LiteralPath $taskFrom -PathType Leaf) -or (Get-Item -LiteralPath $taskFrom).LinkType){throw 'Regular pinned SSTP source required'}
 $taskBlob=(& git -C $taskUpstream rev-parse "HEAD:$taskRelative").Trim()
 if((& git -C $taskUpstream hash-object -- $taskRelative).Trim() -cne $taskBlob){throw 'Pinned SSTP Git object mismatch'}
 $taskRelative=$taskRelative.Substring('carriers/sstp/'.Length)
 $taskTo=Join-Path $taskModule $taskRelative
 New-Item -ItemType Directory -Path (Split-Path $taskTo) -Force|Out-Null
 Copy-Item -LiteralPath $taskFrom -Destination $taskTo
 $taskRows+=@{path=$taskRelative;gitBlob=$taskBlob;sha256=(Get-FileHash -LiteralPath $taskFrom).Hash.ToLowerInvariant()}
}
function Replace-Exact([string]$Path,[string]$Old,[string]$New){
 $taskText=[IO.File]::ReadAllText($Path).Replace("`r`n","`n")
 if(($taskText.Split([string[]]@($Old),[StringSplitOptions]::None).Count-1) -ne 1){throw "SSTP source anchor changed: $Path"}
 [IO.File]::WriteAllText($Path,$taskText.Replace($Old,$New),[Text.UTF8Encoding]::new($false))
}
foreach($taskName in @('main.go','config.go','config_test.go')){Copy-Item -LiteralPath "$PSScriptRoot/$taskName" -Destination "$taskModule/$taskName"}
Copy-Item -LiteralPath "$PSScriptRoot/mcrf_close.go" -Destination "$taskModule/tunnel/mcrf_close.go"
Copy-Item -LiteralPath "$PSScriptRoot/mcrf_lifecycle_test.go" -Destination "$taskModule/tunnel/mcrf_lifecycle_test.go"
Copy-Item -LiteralPath "$PSScriptRoot/mcrf_wire_test.go" -Destination "$taskModule/tunnel/mcrf_wire_test.go"
Replace-Exact "$taskModule/socks5.go" 'type SOCKSProxy struct {' "type SOCKSProxy struct {`n`tctx context.Context"
Replace-Exact "$taskModule/socks5.go" 'context.WithTimeout(context.Background(), 30*time.Second)' 'context.WithTimeout(p.ctx, 30*time.Second)'
$taskAnchor=''+"`tvar certFP string"
$taskDeadline=@'
	// MCRF: cancellation/deadline also covers post-TLS HTTP and SSTP negotiation.
	stopHandshake := context.AfterFunc(ctx, func() { tlsConn.Close() })
	defer stopHandshake()
	handshakeDeadline := time.Now().Add(30 * time.Second)
	if deadline, ok := ctx.Deadline(); ok && deadline.Before(handshakeDeadline) { handshakeDeadline = deadline }
	_ = tlsConn.SetDeadline(handshakeDeadline)
	var certFP string
'@
Replace-Exact "$taskModule/tunnel/tunnel.go" $taskAnchor ($taskDeadline.Replace("`r`n","`n"))
$taskOld=@'
	case <-estCtx.Done():
		s.Close()
'@
$taskNew=@'
	case <-estCtx.Done():
		s.McrfClose()
'@
Replace-Exact "$taskModule/tunnel/tunnel.go" ($taskOld.Replace("`r`n","`n")) ($taskNew.Replace("`r`n","`n"))
$taskOld='fmt.Errorf("tunnel: establish: %w (%v)", s.establishError(), estCtx.Err())'
$taskNew='fmt.Errorf("tunnel: establish: %w", errors.Join(s.establishError(), estCtx.Err()))'
Replace-Exact "$taskModule/tunnel/tunnel.go" $taskOld $taskNew
$taskOld=@'
	case <-s.done:
		s.Close()
'@
$taskNew=@'
	case <-s.done:
		s.McrfClose()
'@
Replace-Exact "$taskModule/tunnel/tunnel.go" ($taskOld.Replace("`r`n","`n")) ($taskNew.Replace("`r`n","`n"))
$taskNew=@'
	if !stopHandshake() || ctx.Err() != nil {
		s.McrfClose()
		return nil, zero, context.Canceled
	}
	_ = tlsConn.SetDeadline(time.Time{})
	s.startKeepAlive()
'@
Replace-Exact "$taskModule/tunnel/tunnel.go" ("`ts.startKeepAlive()") ($taskNew.Replace("`r`n","`n"))
$taskFiles=@(Get-ChildItem -LiteralPath $taskModule -Recurse -File|ForEach-Object{@{path=[IO.Path]::GetRelativePath($taskModule,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}})
[IO.File]::WriteAllText("$taskStage/SOURCE_MANIFEST.json",($taskFiles|Sort-Object path|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$taskReceipt=@{state='SOURCE_PREPARED_NOT_COMPILED';kind='sstp';repository='ttrroyy/qThroneMobile';commit=$taskCommit;license='MIT';module=$taskModule;upstream=$taskRows;manifestSha256=(Get-FileHash -LiteralPath "$taskStage/SOURCE_MANIFEST.json").Hash.ToLowerInvariant();localAuthRequired=$true;tlsVerificationRequired=$true;compiled=$false;executed=$false;androidIntegrated=$false}
[IO.File]::WriteAllText("$taskStage/receipt.json",($taskReceipt|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
Write-Output "P4_SSTP_SOURCE=$taskStage"
