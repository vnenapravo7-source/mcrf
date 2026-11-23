# Source-only preparation, including Linux server TUN policy. The native producer
# builds rust-client only; preparing server sources does not deploy a server fix.
$ErrorActionPreference='Stop'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
$pristine='D:/MCRF/Projects/MCRF/work/android-upstream/csqtt-current-d02da9b'
$revision='d02da9bd9d8926969b766e197c434d830a83dbd8'
$patch='D:/MCRF/Projects/MCRF/work/android-upstream/csqtt-swg-2.5.1.patch'
$patchSha='b61ad9dd4610b3ed200e9f2fb69567492987a4fb2d89c70255f0770148b2ff77'
if((& git -C $pristine rev-parse HEAD) -cne $revision -or $LASTEXITCODE){throw 'Pinned CSQTT25 revision missing'}
$dirty=& git -C $pristine status --porcelain
if($LASTEXITCODE -or $dirty){throw 'Pristine CSQTT25 has user changes'}
if((Hash $patch) -cne $patchSha){throw 'Pinned SWG 2.5.1 dispatcher patch changed'}
$stage="D:/MCRF/Builds/Android/csqtt25-negotiated-$([guid]::NewGuid().ToString('N'))"
$null=New-Item -ItemType Directory -Path $stage
$source=Join-Path $stage 'source'
$receipt=[ordered]@{state='PREPARING_SOURCE';kind='csqtt25';source=[IO.Path]::GetFullPath($source);revision=$revision;swgPatchSha256=$patchSha;workerNegotiationContract=1;serverTunTxQueueContract=1;sourceStable=$false;nativeExecuted=$false;apkBuilt=$false;artifacts=@()}
function Save{[IO.File]::WriteAllText("$stage/receipt.json",($receipt|ConvertTo-Json -Depth 9),[Text.UTF8Encoding]::new($false))}
Save
try{
 & git clone --no-hardlinks --no-checkout -c core.autocrlf=false $pristine $source
 if($LASTEXITCODE){throw 'Owned Rust source clone failed'}
 & git -C $source sparse-checkout init --cone
 if($LASTEXITCODE){throw 'Owned Rust sparse initialization failed'}
 & git -C $source sparse-checkout set rust-client rust-server shared
 if($LASTEXITCODE){throw 'Owned Rust sparse selection failed'}
 & git -C $source checkout --detach $revision
 if($LASTEXITCODE){throw 'Owned Rust revision checkout failed'}
 Copy-Item -LiteralPath $patch -Destination "$stage/swg-2.5.1.patch"
 & git -C $source apply --include=rust-client/dispatcher.rs "$stage/swg-2.5.1.patch"
 if($LASTEXITCODE){throw 'SWG 2.5.1 dispatcher patch failed'}
 Copy-Item -LiteralPath "$PSScriptRoot/prepare-negotiated-workers.py" -Destination "$stage/prepare-negotiated-workers.py"
 # Exactly one application, after owned copy creation and before freezing hashes.
 & python "$stage/prepare-negotiated-workers.py" $source
 if($LASTEXITCODE){throw 'Worker negotiation/server TUN queue transform failed'}
 & python "$PSScriptRoot/prepare-negotiated-workers.py" --verify $source
 if($LASTEXITCODE){throw 'Transformed Rust source freeze failed'}
 $receipt.sourceManifestSha256=Hash "$stage/source-manifest.json"
 $receipt.negotiationSha256=Hash "$stage/negotiation.json"
 $receipt.transformSha256=Hash "$stage/prepare-negotiated-workers.py"
 $receipt.preparerSha256=Hash $PSCommandPath
 $receipt.changedInputs=(Get-Content "$stage/negotiation.json" -Raw|ConvertFrom-Json).changedInputs
 $receipt.state='SOURCE_PREPARED';$receipt.sourceStable=$true;Save
}catch{$receipt.state='FAILED_SOURCE_PREPARATION';Save;throw}
Write-Output "CSQTT25_NEGOTIATED_SOURCE=$stage"
