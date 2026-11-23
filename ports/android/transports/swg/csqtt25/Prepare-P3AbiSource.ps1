param([Parameter(Mandatory)][string]$NegotiatedTransportStage,
 [string]$AcceptedStage='D:/MCRF/Builds/Android/p3-csqtt25-native-b72a5217a8754ac0823f9ab93870a43d')
$ErrorActionPreference='Stop'
function Hash([string]$path){(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}
function SafeRelative([string]$path){if([IO.Path]::IsPathRooted($path) -or '..' -in ($path -split '[/\\]')){throw 'Unsafe source manifest path'}}
$baseJson=& python "$PSScriptRoot/p3_abi_provenance.py" --baseline $AcceptedStage
if($LASTEXITCODE){throw 'Verified surviving accepted stage/source required'}
$base=$baseJson|ConvertFrom-Json
$module=$base.module
$baseline=@(Get-Content -LiteralPath $base.manifest -Raw|ConvertFrom-Json)
# Verify the rebuilt inputs BEFORE creating a source copy. No prebuilt fallback.
& python "$PSScriptRoot/prepare-negotiated-workers.py" --verify-built "$NegotiatedTransportStage/source"
if($LASTEXITCODE){throw 'Rebuilt negotiated transport required'}
$stage="D:/MCRF/Builds/Android/p3-csqtt25-source-$([guid]::NewGuid().ToString('N'))";$target="$stage/module"
New-Item -ItemType Directory -Path $target|Out-Null
foreach($row in $baseline){
 SafeRelative $row.path;$from=Join-Path $module $row.path
 if((Get-Item -LiteralPath $from).LinkType -or (Hash $from) -cne $row.sha256){throw 'Baseline source drift'}
 $to=Join-Path $target $row.path;New-Item -ItemType Directory -Force -Path (Split-Path $to)|Out-Null;Copy-Item -LiteralPath $from -Destination $to
}
$overlays=@('runtime.go','native_abi.go','native_abi_test.go','native_abi_inputs_test.go','profile.go','versions.go','versions_test.go')
$rows=@()
foreach($name in $overlays){$from=Join-Path $PSScriptRoot $name;Copy-Item -LiteralPath $from -Destination "$target/internal/csqtt25/$name" -Force;$rows+=@{name=$name;sha256=Hash $from}}
# Replacing the adapter must preserve the already-applied STATS ingress.
# The retained reporter selects STATS only when this bridge flag is true.
$bridge=[IO.File]::ReadAllText("$PSScriptRoot/../mcrf_csqtt25.go").Replace("`r`n","`n")
$statsAnchor='NormalizeTunnel: csqtt25.NormalizeTunnel,'
if($bridge.Contains('McrfSampleStreamStats') -or $bridge.Split(@($statsAnchor),[StringSplitOptions]::None).Count -ne 2){throw 'CS25 sampled stream bridge anchor changed'}
$bridge=$bridge.Replace($statsAnchor,"$statsAnchor`n`t`tMcrfSampleStreamStats: true,")
[IO.File]::WriteAllText("$target/mcrf_csqtt25.go",$bridge,[Text.UTF8Encoding]::new($false))
$old=[IO.File]::ReadAllText("$module/internal/csqtt25/runtime.go");$new=[IO.File]::ReadAllText("$target/internal/csqtt25/runtime.go")
$marker='// Construction only:'
if($old.IndexOf($marker) -lt 0 -or $new.IndexOf($marker) -lt 0 -or $old.Substring($old.IndexOf($marker)).Replace("`r`n","`n") -cne $new.Substring($new.IndexOf($marker)).Replace("`r`n","`n")){throw 'ABI change must not alter owned process launch/cleanup code'}
# The surviving manifest already includes the reviewed performance/stream overlay.
# Copy it unchanged; reapplying the single-application transform is an error.
& python "$PSScriptRoot/prepare-negotiated-workers.py" --pin-wrapper $target "$NegotiatedTransportStage/source"
if($LASTEXITCODE){throw 'Rebuilt negotiated transport identities required before wrapper freeze'}
$inputs=@(Get-ChildItem -LiteralPath $target -Recurse -File|ForEach-Object {@{path=[IO.Path]::GetRelativePath($target,$_.FullName).Replace([char]92,[char]47);sha256=Hash $_.FullName}}|Sort-Object path)
[IO.File]::WriteAllText("$stage/SOURCE_MANIFEST.json",($inputs|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$receipt=@{state='P3_ABI_SOURCE_PREPARED_NOT_COMPILED';sourceStable=$true;module=$target;baselineReceipt=$base.receipt;baselineReceiptSha256=$base.receiptSha256;baselinePreparedReceipt=$base.prepared;baselinePreparedSha256=$base.preparedSha256;baselineSourceManifestSha256=$base.manifestSha256;performanceAlreadyApplied=$true;overlays=$rows;manifestSha256=Hash "$stage/SOURCE_MANIFEST.json";ownerCommandUnchanged=$true;compiled=$false;executed=$false}
$receipt.provenanceVerifierSha256=Hash "$PSScriptRoot/p3_abi_provenance.py"
$receipt.performanceSourceSha256=Hash "$PSScriptRoot/../performance/Apply-PerformanceSource.ps1"
$receipt.negotiatedTransportStage=[IO.Path]::GetFullPath($NegotiatedTransportStage)
$receipt.negotiatedTransportReceiptSha256=Hash "$NegotiatedTransportStage/receipt.json"
$receipt.negotiatedSourceManifestSha256=Hash "$NegotiatedTransportStage/source-manifest.json"
$receipt.workerNegotiationContract=1
$receipt.serverTunTxQueueContract=1
[IO.File]::WriteAllText("$stage/receipt.json",($receipt|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
& python "$PSScriptRoot/p3_abi_provenance.py" --prepared $stage $AcceptedStage
if($LASTEXITCODE){
 $receipt.state='FAILED_PRESERVED';$receipt.sourceStable=$false
 [IO.File]::WriteAllText("$stage/receipt.json",($receipt|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
 throw 'Prepared wrapper provenance closure failed'
}
Write-Output "P3_CS25_SOURCE=$stage"
