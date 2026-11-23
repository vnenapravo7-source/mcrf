param()
$ErrorActionPreference='Stop'
if($args.Count){throw 'Fixed final acceptance archive only'}
$root='D:/MCRF/Projects/MCRF'
$directory=Join-Path $root 'work/ports-test-2026-10-14/p1-final-11b3e3b2cbab492abf0cf4833a70b6ff'
$receipt=Get-Content -LiteralPath (Join-Path $directory 'receipt.json') -Raw|ConvertFrom-Json
function HashBytes([byte[]]$bytes){[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()}
$archive=Join-Path $directory 'audited-inputs';[IO.Directory]::CreateDirectory($archive)|Out-Null
$rows=@()
foreach($property in $receipt.inputs.PSObject.Properties){
 $path=[IO.Path]::GetFullPath($property.Name);$expected=[string]$property.Value
 if(!$path.StartsWith('D:\MCRF\',[StringComparison]::OrdinalIgnoreCase) -or $expected -cnotmatch '^[0-9a-f]{64}$' -or (Get-Item -LiteralPath $path).LinkType){throw 'Invalid audited input'}
 $bytes=[IO.File]::ReadAllBytes($path)
 if((HashBytes $bytes) -cne $expected){
  # Completion updates exactly these two mutable status records. Preserve a
  # HASH-IDENTICAL copy of their audited pre-completion text, never rewrite
  # their current status or an existing historical receipt.
  $text=[Text.Encoding]::UTF8.GetString($bytes)
  switch([IO.Path]::GetFileName($path)){
   'p1-device-authority-2026-10-19.json'{
    $text=$text.Replace('"state": "COMPLETED_OWNED_ACCEPTANCE_AND_RESTORATION_NO_REUSE"','"state": "ACTIVE_CONDITIONAL_ON_COMMON_PASS"').Replace('"install": false','"install": true').Replace('"verify": false','"verify": true')
   }
   'p1-device-acceptance-2026-10-19.json'{
    $text=$text.Replace('"state": "SUPPORTED_P1_PACKAGE_READY_SAMSUNG_OWNED_SCOPE"','"state": "SCOPED_ANDROID_CHECKS_IN_PROGRESS"')
    $text=[regex]::Replace($text,'(?m)^  "(?:supportedP1PackageReady|finalReceipt|finalReceiptSha256|mandatoryOwnedGatesPassed|protectedFilesUnchanged|temporaryHelpersRemovedApksRetained|acceptanceLimits)": [^\r\n]*\r?\n','')
    $text=$text.Replace('  "pending": [],',"  `"pending`": [`n    `"Final whole mandatory-gate audit and protected-data cleanup/restoration; remove only owned temporary helpers when no longer needed`"`n  ],")
    $current='Original failed receipts are preserved. Controller-only false negatives independently reviewed, never silently rewritten. Final source audit proves all actual production runtime inputs retained except3 private fixture classes; final framework correction changes only negative DEBUG expectations, not TLS trust or fallback. Owned matrix/cleanup complete, limitations explicit; scoped PASS is not universal full P1 acceptance.'
    $previous='Original failed receipts are preserved. Two controller-only false negatives were independently reviewed from complete original Android logs and peer evidence, not silently rewritten. Native/app sources and installed APKs did not change for peer capacity/join fixes. Scoped PASS is not full P1 acceptance.'
    $text=$text.Replace($current,$previous)
   }
   default{throw 'An immutable audited input changed'}
  }
  $bytes=[Text.UTF8Encoding]::new($false).GetBytes($text)
  if((HashBytes $bytes) -cne $expected){throw 'Exact historical status text could not be recovered; do not fabricate evidence'}
 }
 $leaf=$expected+[IO.Path]::GetExtension($path);$target=Join-Path $archive $leaf
 if(Test-Path -LiteralPath $target){if((Get-FileHash -LiteralPath $target).Hash.ToLowerInvariant() -cne $expected){throw 'Existing immutable archive differs'}}else{[IO.File]::WriteAllBytes($target,$bytes)}
 $rows+=@{original=$path;archive=$leaf;sha256=$expected}
}
$controller=Join-Path $root 'ports/android/maintenance/Finalize-P1OwnedAcceptance.ps1'
$controllerCopy=Join-Path $archive ($receipt.controllerSha256+'.ps1')
if(!(Test-Path -LiteralPath $controllerCopy)){
 if((Get-FileHash -LiteralPath $controller).Hash.ToLowerInvariant() -cne $receipt.controllerSha256){throw 'Archive the actually executed finalizer before updating its replay guard'}
 [IO.File]::Copy($controller,$controllerCopy,$false)
}
if((Get-FileHash -LiteralPath $controllerCopy).Hash.ToLowerInvariant() -cne $receipt.controllerSha256){throw 'Executed controller archive mismatch'}
[IO.File]::WriteAllText((Join-Path $directory 'audited-input-archive.json'),(@{source='Exact final acceptance inputs; only completion-status records recovered by already-recorded SHA256';inputs=$rows;controller=[IO.Path]::GetFileName($controllerCopy)}|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
Write-Output ('P1_FINAL_INPUT_ARCHIVE_PASS='+$rows.Count)
