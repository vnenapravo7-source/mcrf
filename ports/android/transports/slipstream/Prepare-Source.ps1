param([Parameter(Mandatory)][string]$Upstream)
$ErrorActionPreference='Stop'
$taskRoot='D:/MCRF/Projects/MCRF'
$taskCommit='7de506b222c83ae97a19a618e33fe26899af1bd5'
$taskUpstream=(Resolve-Path -LiteralPath $Upstream).Path
if($taskUpstream.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-slipstream-rust-upstream-[a-f0-9]{32}$'){throw 'Exact owned pinned source copy required'}
if((& git -C $taskUpstream rev-parse HEAD).Trim() -cne $taskCommit){throw 'Wrong Slipstream source commit'}
if((& git -C $taskUpstream status --porcelain --untracked-files=all)){throw 'Pinned upstream source is dirty'}
$taskLock=(Get-Content -LiteralPath "$taskRoot/ports/android/transports/transport-lock.json" -Raw|ConvertFrom-Json).slipstream.compatibilityBackend
if(!$taskLock -or $taskLock.commit -cne $taskCommit){throw 'Backend lock required'}
$taskStage="D:/MCRF/Builds/Android/p3-slipstream-source-$([guid]::NewGuid().ToString('N'))"
$taskTarget="$taskStage/rust"
New-Item -ItemType Directory -Path $taskTarget|Out-Null
# Copy tracked regular files only; no Git config, dirty worktree or executable.
$taskRows=@()
foreach($taskRelative in (& git -C $taskUpstream ls-files)){
 $taskFrom=Join-Path $taskUpstream $taskRelative
 if(!(Test-Path -LiteralPath $taskFrom -PathType Leaf)){continue} # sparse/submodule input is separately required by native producer
 $taskInfo=Get-Item -LiteralPath $taskFrom
 if($taskInfo.LinkType){throw 'Linked upstream source is not allowed'}
 $taskBlob=(& git -C $taskUpstream rev-parse "HEAD:$taskRelative").Trim()
 if((& git -C $taskUpstream hash-object -- $taskRelative).Trim() -cne $taskBlob){throw 'Source differs from pinned Git object'}
 $taskTo=Join-Path $taskTarget $taskRelative
 New-Item -ItemType Directory -Path (Split-Path $taskTo) -Force|Out-Null
 Copy-Item -LiteralPath $taskFrom -Destination $taskTo
 $taskRows+=@{path=$taskRelative;gitBlob=$taskBlob;sha256=(Get-FileHash -LiteralPath $taskFrom).Hash.ToLowerInvariant()}
}
$taskClient="$taskTarget/crates/slipstream-client/src"
function Replace-Exact([string]$Path,[string]$Old,[string]$New){
 $taskText=[IO.File]::ReadAllText($Path).Replace("`r`n","`n")
 if(($taskText.Split([string[]]@($Old),[StringSplitOptions]::None).Count-1) -ne 1){throw "Source anchor not unique: $Path"}
 [IO.File]::WriteAllText($Path,$taskText.Replace($Old,$New),[Text.UTF8Encoding]::new($false))
}
Replace-Exact "$taskClient/main.rs" 'mod dns;' "mod private_listener;`nmod dns;"
# Mandatory leaf pin even when an external caller bypasses the Go frontend.
$taskOld=@'
    if cert.is_none() {
        tracing::warn!(
            "Server certificate pinning is disabled; this allows MITM. Provide --cert to pin the server leaf, or dismiss this if your underlying tunnel provides authentication."
        );
    }
'@
$taskNew=@'
    if cert.is_none() {
        eprintln!("MCRF_BACKEND CERTIFICATE_REQUIRED");
        std::process::exit(2);
    }
'@
Replace-Exact "$taskClient/main.rs" ($taskOld.Replace("`r`n","`n")) ($taskNew.Replace("`r`n","`n"))
Replace-Exact "$taskClient/runtime/setup.rs" 'use tokio::net::{TcpListener as TokioTcpListener, UdpSocket as TokioUdpSocket};' 'use tokio::net::{UnixListener as TokioTcpListener, UdpSocket as TokioUdpSocket};'
Replace-Exact "$taskClient/runtime/setup.rs" '    bind_first_resolved_with_ipv4_fallback(host, port, bind_tcp_listener_addr, "TCP listener").await' "    let _ = (host, port);`n    crate::private_listener::bind_private_listener().await"
Replace-Exact "$taskClient/streams/acceptor.rs" 'use tokio::net::TcpListener as TokioTcpListener;' 'use tokio::net::UnixListener as TokioTcpListener;'
Replace-Exact "$taskClient/streams/state.rs" 'use tokio::net::TcpStream as TokioTcpStream;' 'use tokio::net::UnixStream as TokioTcpStream;'
Replace-Exact "$taskClient/streams/io_tasks.rs" 'tokio::net::tcp::OwnedReadHalf' 'tokio::net::unix::OwnedReadHalf'
Replace-Exact "$taskClient/streams/io_tasks.rs" 'tokio::net::tcp::OwnedWriteHalf' 'tokio::net::unix::OwnedWriteHalf'
Replace-Exact "$taskClient/streams/command_dispatch.rs" '            let _ = stream.set_nodelay(true);' '            // Private Unix stream: TCP_NODELAY is not applicable.'
$taskOld=@'
    if leaf_bytes != verifier.pinned_der.as_slice() {
        return -1;
    }
'@
$taskNew=@'
    if leaf_bytes != verifier.pinned_der.as_slice() {
        eprintln!("MCRF_BACKEND CERTIFICATE_MISMATCH");
        return -1;
    }
'@
Replace-Exact "$taskClient/pinning.rs" ($taskOld.Replace("`r`n","`n")) ($taskNew.Replace("`r`n","`n"))
Copy-Item -LiteralPath "$PSScriptRoot/private_listener.rs" -Destination "$taskClient/private_listener.rs"
New-Item -ItemType Directory -Path "$taskStage/bridge"|Out-Null
Get-ChildItem -LiteralPath "$PSScriptRoot/bridge" -File|ForEach-Object{Copy-Item -LiteralPath $_.FullName -Destination "$taskStage/bridge/$($_.Name)"}
# One shared authentication implementation; do not fork the credential parser.
$taskSocks="$taskRoot/ports/android/transports/vaydns/overlay/mcrf_socks.go"
$taskText=[IO.File]::ReadAllText($taskSocks)
if(!$taskText.StartsWith('package client')){throw 'Shared SOCKS package anchor changed'}
[IO.File]::WriteAllText("$taskStage/bridge/mcrf_socks.go",$taskText.Replace('package client','package main'),[Text.UTF8Encoding]::new($false))
$taskFiles=@(Get-ChildItem -LiteralPath $taskStage -Recurse -File|ForEach-Object{@{path=[IO.Path]::GetRelativePath($taskStage,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}})
[IO.File]::WriteAllText("$taskStage/SOURCE_MANIFEST.json",($taskFiles|Sort-Object path|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$taskReceipt=@{state='SOURCE_PREPARED_NOT_COMPILED';repository='Mygod/slipstream-rust';commit=$taskCommit;upstreamArchived=$true;upstream=$taskRows;sharedSocksSha256=(Get-FileHash -LiteralPath $taskSocks).Hash.ToLowerInvariant();manifestSha256=(Get-FileHash -LiteralPath "$taskStage/SOURCE_MANIFEST.json").Hash.ToLowerInvariant();privateUds=$true;mandatoryLeafPin=$true;compiled=$false;executed=$false;androidIntegrated=$false}
[IO.File]::WriteAllText("$taskStage/receipt.json",($taskReceipt|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
Write-Output "P3_SLIPSTREAM_SOURCE=$taskStage"
