$ErrorActionPreference='Stop'
$taskRoot='D:/MCRF/Projects/MCRF';$taskCoord="$taskRoot/ports/android/coordination"
if((Get-Item -LiteralPath "$taskRoot/HANDOFF.md").Length -gt 8192 -or (Get-Item -LiteralPath "$taskCoord/current.json").Length -gt 4096){throw 'First-read context exceeded its size budget'}
$taskCurrent=Get-Content -LiteralPath "$taskCoord/current.json" -Raw|ConvertFrom-Json
foreach($name in @('coverage.json','solo-resume-2026-10-15.json')){
    $compact=Get-Content -LiteralPath "$taskCoord/$name" -Raw|ConvertFrom-Json
    if($compact.contextSchema -ne 2 -or !$compact.historyIsNotCurrentAuthority -or $compact.release_acceptance){throw 'Compacted context schema/authority mismatch'}
    $archive=[IO.Path]::GetFullPath((Join-Path $taskCoord $compact.historyArchive))
    if(!$archive.StartsWith([IO.Path]::GetFullPath("$taskCoord/archive")+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'History archive escaped coordination archive root'}
    $stream=[IO.File]::OpenRead($archive);$memory=[IO.MemoryStream]::new()
    try{$gzip=[IO.Compression.GZipStream]::new($stream,[IO.Compression.CompressionMode]::Decompress);try{$gzip.CopyTo($memory)}finally{$gzip.Dispose()}}finally{$stream.Dispose()}
    try{$bytes=$memory.ToArray();$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()}finally{$memory.Dispose()}
    if($hash -cne $compact.historyOriginalSha256 -or $bytes.Length -ne $compact.historyOriginalBytes){throw 'Historical bytes not recoverable exactly'}
    if($name -eq 'coverage.json'){
        $original=[Text.Encoding]::UTF8.GetString($bytes)|ConvertFrom-Json
        foreach($key in @('baseline','human_scope_overrides','new_human_changes_18','steer_alternative_core','original_and_followup_items','screenshots','later_ui_and_behavior','reference_analysis','final_acceptance')){
            if(($compact.$key|ConvertTo-Json -Depth 64 -Compress) -cne ($original.$key|ConvertTo-Json -Depth 64 -Compress)){throw 'Requirement/reference matrix changed'}
        }
        if(@($compact.original_and_followup_items).Count -ne 32 -or @($compact.screenshots).Count -ne 23){throw 'Required contract count changed'}
    }
}
foreach($relative in @($taskCurrent.phone.lastInstall,$taskCurrent.phone.lastHttps,$taskCurrent.directCidrTun.positive,$taskCurrent.directCidrTun.negative,$taskCurrent.contextCleanup.receipt)){
    if(!(Test-Path -LiteralPath (Join-Path $taskRoot $relative) -PathType Leaf)){throw 'Current evidence link missing'}
}
$taskCleanup=Get-Content -LiteralPath (Join-Path $taskRoot $taskCurrent.contextCleanup.receipt) -Raw|ConvertFrom-Json
if(!$taskCleanup.passed -or $taskCleanup.protectedBefore.sha256 -cne $taskCleanup.protectedAfter.sha256 -or $taskCleanup.protectedAfter.files -ne 480){throw 'APK/source-library/test-class preservation not proven'}
foreach($snapshot in @('2e768634ae6241e5b5caad22b80f6ba0','dcaf493b6b5e4e858b621928ac189491')){
    $receipt=Get-Content -LiteralPath "$taskCoord/build-history/android-integration-$snapshot/build-result.json" -Raw|ConvertFrom-Json
    if($receipt.exitCode -ne 0 -or !$receipt.sourceStable){throw 'Latest focused source validation not green/stable'}
}
$result=@{passed=$true;handoffBytes=(Get-Item -LiteralPath "$taskRoot/HANDOFF.md").Length;checkpointBytes=(Get-Item -LiteralPath "$taskCoord/current.json").Length;currentLegacyJsonBytes=((Get-Item -LiteralPath "$taskCoord/coverage.json").Length+(Get-Item -LiteralPath "$taskCoord/solo-resume-2026-10-15.json").Length);historyByteExact=$true;requirements=32;screenshots=23;protectedFilesUnchanged=480;removedBytes=$taskCleanup.removedBytes;sourceApkOrDeviceBuildRun=$false;timeUtc=[DateTime]::UtcNow.ToString('o')}
[IO.File]::WriteAllText((Join-Path ([IO.Path]::GetDirectoryName((Join-Path $taskRoot $taskCurrent.contextCleanup.receipt))) 'context-validation.json'),($result|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$result|ConvertTo-Json
