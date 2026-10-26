param([Parameter(Mandatory)][string]$PreparedSource)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-build
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 16),[Text.UTF8Encoding]::new($false))}
$taskParent=(Resolve-Path $PreparedSource).Path.Replace([char]92,[char]47)
if($taskParent -cnotmatch '^D:/MCRF/Builds/Android/p3-slipstream-source-[a-f0-9]{32}$'){throw 'Exact frozen source required'}
$taskProof=Get-Content "$taskParent/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
if($taskProof.manifestSha256 -cne (Hash "$taskParent/SOURCE_MANIFEST.json") -or $taskProof.commit -cne '7de506b222c83ae97a19a618e33fe26899af1bd5'){throw 'Frozen source proof changed'}
$taskRows=@(Get-Content "$taskParent/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
$taskStage="D:/MCRF/Builds/Android/p3-slipstream-source-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $taskStage|Out-Null
foreach($row in $taskRows){
 if([IO.Path]::IsPathRooted($row.path) -or '..' -in ($row.path -split '[/\\]')){throw 'Unsafe source manifest path'}
 $from=Join-Path $taskParent $row.path;$to=Join-Path $taskStage $row.path
 if((Get-Item $from).LinkType -or (Hash $from) -cne $row.sha256){throw 'Frozen source changed'}
 New-Item -ItemType Directory -Path (Split-Path $to) -Force|Out-Null;Copy-Item -LiteralPath $from -Destination $to
}
$taskPath="$taskStage/rust/crates/slipstream-client/src/pinning.rs"
$taskText=[IO.File]::ReadAllText($taskPath)
$taskOld="    let mut verifier = Verifier::new_without_digest(pkey).map_err(|err| err.to_string())?;`n    verifier.update(data).map_err(|err| err.to_string())?;`n    verifier.verify(sig).map_err(|err| err.to_string())"
if(($taskText.Split([string[]]@($taskOld),[StringSplitOptions]::None).Count-1) -ne 1){throw 'Exact EdDSA-only signature anchor required'}
# PureEdDSA cannot stream: rust-openssl Verifier::update docs explicitly require
# verify_oneshot. Leaf equality, signature input and all RSA/EC paths stay exact.
# https://docs.rs/openssl/latest/openssl/sign/struct.Verifier.html#method.update
(Get-Item $taskPath).IsReadOnly=$false
[IO.File]::WriteAllText($taskPath,$taskText.Replace($taskOld,"    let mut verifier = Verifier::new_without_digest(pkey).map_err(|err| err.to_string())?;`n    verifier.verify_oneshot(sig, data).map_err(|err| err.to_string())"),[Text.UTF8Encoding]::new($false))
$taskNew=@(foreach($row in $taskRows){@{path=$row.path;sha256=Hash (Join-Path $taskStage $row.path)}})
if(@($taskNew|Where-Object {$r=$_;($taskRows|Where-Object path -CEQ $r.path).sha256 -cne $r.sha256}).Count -ne 1){throw 'Signature correction changed more than one source'}
Save $taskNew "$taskStage/SOURCE_MANIFEST.json"
$taskProof.manifestSha256=Hash "$taskStage/SOURCE_MANIFEST.json";$taskProof.parentPreparedSource=$taskParent;$taskProof.parentReceiptSha256=Hash "$taskParent/receipt.json";$taskProof.revision='EdDSA-one-shot-only';$taskProof.revisionProducerSha256=Hash $PSCommandPath
Save $taskProof "$taskStage/receipt.json";Write-Output "P3_SLIPSTREAM_SOURCE=$taskStage"
