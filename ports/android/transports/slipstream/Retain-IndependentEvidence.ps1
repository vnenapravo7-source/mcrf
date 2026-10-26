param([Parameter(Mandatory)][string]$NativeStage,[Parameter(Mandatory)][string]$RetainedStage,[Parameter(Mandatory)][string]$PreparedSource)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-build
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 16),[Text.UTF8Encoding]::new($false))}
foreach($taskRoot in @($NativeStage,$RetainedStage)){if((Resolve-Path $taskRoot).Path.Replace([char]92,[char]47) -cnotmatch '^D:/MCRF/Builds/Android/p3-slipstream-native-[a-f0-9]{32}$'){throw 'Exact owned native stages required'}}
$taskNew=Get-Content "$NativeStage/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
$taskOld=Get-Content "$RetainedStage/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
$taskSource=Get-Content "$PreparedSource/receipt.json" -Raw|ConvertFrom-Json
if($taskNew.state -cne 'COMPILED_LEGAL_AND_FIXTURE_PENDING' -or $taskSource.revision -cne 'EdDSA-one-shot-only' -or $taskNew.sourceManifestSha256 -cne $taskSource.manifestSha256 -or $taskOld.sourceManifestSha256 -cne (Hash "$($taskSource.parentPreparedSource)/SOURCE_MANIFEST.json") -or $taskOld.dependencyReceiptSha256 -cne $taskNew.dependencyReceiptSha256){throw 'Exact signature-only revision with unchanged C dependencies required'}
$taskRows=@(Get-Content "$PreparedSource/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
$taskPrior=@(Get-Content "$($taskSource.parentPreparedSource)/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
$taskChanged=@($taskRows|Where-Object {$r=$_;($taskPrior|Where-Object path -CEQ $r.path).sha256 -cne $r.sha256})
if($taskRows.Count -ne $taskPrior.Count -or $taskChanged.Count -ne 1 -or $taskChanged[0].path -cne 'rust/crates/slipstream-client/src/pinning.rs'){throw 'Independent tests require unchanged Go/C/leaf/private-ingress sources'}
foreach($row in @($taskOld.artifacts)+@($taskOld.fixtureArtifacts)){if((Hash "$RetainedStage/$($row.file)") -cne $row.sha256){throw 'Retained original artifacts changed'}}
$taskC=@($taskOld.fixtureArtifacts|Where-Object file -CEQ 'fixtures/libslipstream_cserver.so');if($taskC.Count -ne 1 -or !$taskOld.originalCFixture.loopbackOnly){throw 'Exact original-C interoperability fixture required'}
foreach($file in @('cfixture-source-manifest.json','cfixture-source.json','fixtures/C-SERVER-LICENSE','fixtures/libslipstream_cserver.so')){Copy-Item -LiteralPath "$RetainedStage/$file" -Destination "$NativeStage/$file"}
$taskNew.fixtureArtifacts+=@($taskC[0]);$taskNew.originalCFixture=$taskOld.originalCFixture
$taskEvidence=Get-Content "$RetainedStage/fixture-evidence.json" -Raw|ConvertFrom-Json -AsHashtable
$taskIndependent=@('TestMcrfSlipstreamStrictConfigAndMandatoryLeaf','TestMcrfSlipstreamRejectDuplicateAndTrailingConfig','TestMcrfSlipstreamEndpointAndKeepAliveBounds','TestMcrfSlipstreamUnauthenticatedClientCannotOpenRemoteStream','TestMcrfSlipstreamPrivateRelayPreservesHalfClose','TestMcrfSlipstreamCancellationClosesPendingRemoteAuth','TestMcrfSlipstreamWrongLeafStopsBeforeRemoteSOCKS')
if(@($taskEvidence.cases|Where-Object {$_ -cnotin $taskIndependent}).Count -or $taskEvidence.cases.Count -ne 7){throw 'Only seven unaffected cases may be retained'}
foreach($attempt in $taskEvidence.attempts){
 if((Hash "$RetainedStage/$($attempt.log)") -cne $attempt.sha256){throw 'Retained raw case log changed'}
 $taskDir=Split-Path $attempt.log
 if($taskDir -cnotmatch '^attempt-[a-f0-9]{32}$'){throw 'Unsafe retained log directory'}
 New-Item -ItemType Directory -Path "$NativeStage/$taskDir"|Out-Null
 Get-ChildItem "$RetainedStage/$taskDir" -File|ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination "$NativeStage/$taskDir/$($_.Name)"}
}
$taskEvidence.retainedOriginalStage=$RetainedStage;$taskEvidence.retainedSourceManifestSha256=$taskEvidence.sourceManifestSha256;$taskEvidence.sourceManifestSha256=$taskNew.sourceManifestSha256;$taskEvidence.signatureOnlyRevision=$true
Save $taskEvidence "$NativeStage/fixture-evidence.json"
$taskNew.retainedEvidence=@{source=$RetainedStage;sourceReceiptSha256=Hash "$RetainedStage/receipt.json";cases=@($taskEvidence.cases);scope='Go tests/bridge/C unchanged; leaf rejection occurs before corrected EdDSA signature function';testsExecuted=$false}
Save $taskNew "$NativeStage/receipt.json";Write-Output "P3_SLIPSTREAM_RETAINED=$NativeStage"
