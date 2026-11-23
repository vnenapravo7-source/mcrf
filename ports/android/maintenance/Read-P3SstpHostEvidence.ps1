param([Parameter(Mandatory)][string]$PreparedSource,[Parameter(Mandatory)][string]$ExistingEvidence)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation test
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
$old=(Resolve-Path -LiteralPath $ExistingEvidence).Path.Replace([char]92,[char]47)
if($old -cnotmatch '^D:/MCRF/Builds/Android/p4-sstp-host-[a-f0-9]{32}$'){throw 'Exact retained SSTP host run required'}
$source=Get-Content "$PreparedSource/receipt.json" -Raw|ConvertFrom-Json
$previous=Get-Content "$old/receipt.json" -Raw|ConvertFrom-Json
if($source.state -cne 'SOURCE_PREPARED_NOT_COMPILED' -or $source.manifestSha256 -cne $previous.sourceManifestSha256 -or
 (Hash "$PreparedSource/SOURCE_MANIFEST.json") -cne $source.manifestSha256){throw 'Retained tests must match the exact frozen source'}
foreach($row in (Get-Content "$PreparedSource/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)){
 if([IO.Path]::IsPathRooted($row.path) -or '..' -in ($row.path -split '[/\\]') -or (Hash (Join-Path $source.module $row.path)) -cne $row.sha256){throw 'Retained SSTP source changed'}
}
$log="$old/sstp-host-tests.jsonl";$events=@(Get-Content -LiteralPath $log|ForEach-Object {$_|ConvertFrom-Json})
if(@($events|Where-Object {$_.Action -ceq 'fail' -or $_.Action -ceq 'skip' -and $_.Test}).Count){throw 'Actual failing or skipped TEST cannot be rescored as a pass'}
foreach($group in ($events|Group-Object Package)){
 $terminal=@($group.Group|Where-Object {!$_.Test -and $_.Action -in @('pass','skip','fail')})
 if($terminal.Count -ne 1){throw 'Every package requires exactly one completed terminal record'}
 if($terminal[0].Action -ceq 'skip'){
  if(@($group.Group|Where-Object {$_.Test}).Count -or !@($group.Group|Where-Object {$_.Action -ceq 'output' -and $_.Output -match '\[no test files\]'}).Count){throw 'Only genuinely empty packages may have package-level skip'}
 }
}
$required=@('TestMcrfSstpStrictConfigAndSecureDefaults','TestMcrfSstpRejectAmbiguousConfigAndEndpoint','TestMcrfSstpTypedCertificateFailureIsPreserved','TestMcrfSstpCloseUnblocksStalledDisconnect','TestMcrfSstpCancelDuringPostTLSHTTPHandshake','TestMcrfSstpOwnedTLSMSCHAPIPData')
$passed=@($events|Where-Object {$_.Action -ceq 'pass' -and $_.Test}|ForEach-Object {$_.Test}|Sort-Object -Unique)
if(@($required|Where-Object {$_ -cnotin $passed}).Count){throw 'All six actual new cases must already have passed'}
$stage="D:/MCRF/Builds/Android/p4-sstp-host-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $stage|Out-Null
Copy-Item -LiteralPath $log -Destination "$stage/sstp-host-tests.jsonl"
$proof=@{state='PASS_P4_SSTP_HOST';sourceManifestSha256=$source.manifestSha256;failures=0;skipped=0;cases=$passed;logSha256=Hash $log;retainedTestsReplayed=$false;testsExecutedByThisRecovery=$false;androidRuntimeAccepted=$false;recoveredPackageOnlySkips=@($events|Where-Object {$_.Action -ceq 'skip' -and !$_.Test}).Count;originalEvidence=$old;originalEvidenceSha256=Hash "$old/receipt.json";originalLogSha256=Hash $log}
[IO.File]::WriteAllText("$stage/receipt.json",($proof|ConvertTo-Json -Depth 9),[Text.UTF8Encoding]::new($false))
Write-Output "P4_SSTP_HOST=$stage"
