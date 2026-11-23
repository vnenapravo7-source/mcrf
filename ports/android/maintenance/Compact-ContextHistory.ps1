param([switch]$Apply)
$ErrorActionPreference='Stop'
$taskRoot='D:/MCRF/Projects/MCRF';$taskCoord="$taskRoot/ports/android/coordination"
$taskNames=@('coverage.json','solo-resume-2026-10-15.json')
$taskRetain=@('baseline','human_scope_overrides','new_human_changes_18','steer_alternative_core','original_and_followup_items','screenshots','later_ui_and_behavior','reference_analysis','final_acceptance')
$taskInputs=@{}
foreach($name in $taskNames){
    $path="$taskCoord/$name";if((Get-Item -LiteralPath $path).LinkType){throw 'Linked context input rejected'}
    $bytes=[IO.File]::ReadAllBytes($path);$data=[Text.Encoding]::UTF8.GetString($bytes)|ConvertFrom-Json
    if($data.contextSchema -eq 2){throw 'Already compacted; use existing archive/receipt instead of repeating'}
    $taskInputs[$name]=@{bytes=$bytes;data=$data;sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()}
}
if(@($taskInputs['coverage.json'].data.original_and_followup_items).Count -ne 32 -or @($taskInputs['coverage.json'].data.screenshots).Count -ne 23){throw 'Original contract counts changed'}
if(!$Apply){[pscustomobject]@{apply=$false;files=$taskNames;originalBytes=($taskInputs.Values.bytes.Length|Measure-Object -Sum).Sum;retainOriginalRequirements=32;retainScreenshots=23;history='Lossless gzip, byte-for-byte SHA256 roundtrip before replacement';sourceOrLicensesRemoved=$false}|ConvertTo-Json;return}
$taskPolicy=Get-Content -LiteralPath "$taskCoord/build-retention.json" -Raw|ConvertFrom-Json
if(@($taskPolicy.activeSnapshots).Count -or @(Get-CimInstance Win32_Process|Where-Object {$_.Name -match '^(java|javaw|go|cmake|ninja|cargo|rustc|gradle|robocopy)\.exe$'}).Count){throw 'Active producer; no context rewrite'}
$taskArchive="$taskCoord/archive/context-"+[guid]::NewGuid().ToString('N')
New-Item -ItemType Directory -Path $taskArchive|Out-Null
$taskRows=@()
foreach($name in $taskNames){
    $taskEntry=$taskInputs[$name];$gzip="$taskArchive/$name.gz"
    $stream=[IO.File]::Open($gzip,[IO.FileMode]::CreateNew)
    try{$zip=[IO.Compression.GZipStream]::new($stream,[IO.Compression.CompressionLevel]::SmallestSize,$true);try{$zip.Write($taskEntry.bytes,0,$taskEntry.bytes.Length)}finally{$zip.Dispose()}}finally{$stream.Dispose()}
    $stream=[IO.File]::OpenRead($gzip);$restored=[IO.MemoryStream]::new()
    try{$zip=[IO.Compression.GZipStream]::new($stream,[IO.Compression.CompressionMode]::Decompress);try{$zip.CopyTo($restored)}finally{$zip.Dispose()}}finally{$stream.Dispose()}
    try{$restoredHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($restored.ToArray())).ToLowerInvariant()}finally{$restored.Dispose()}
    if($restoredHash -cne $taskEntry.sha256){throw 'History gzip roundtrip mismatch; originals untouched'}
    $archiveRelative=[IO.Path]::GetRelativePath($taskCoord,$gzip)-replace '\\','/'
    $compact=[ordered]@{contextSchema=2;readFirst='../../../HANDOFF.md';currentState='current.json';historyArchive=$archiveRelative;historyOriginalSha256=$taskEntry.sha256;historyOriginalBytes=$taskEntry.bytes.Length;historyIsNotCurrentAuthority=$true;release_acceptance=$false}
    if($name -eq 'coverage.json'){
        foreach($key in $taskRetain){$property=$taskEntry.data.PSObject.Properties[$key];if($null -ne $property){$compact[$key]=$property.Value}}
        $compact['latestEvidence']='current.json and its exact receipts; not whole-project acceptance'
    }else{
        $compact['priority']='Connection/sites/Direct; then real CAPTCHA for legacy CSQTT/CSQTT2.5/WDTT/QWDTT; then all remaining noncancelled requirements'
        $compact['remainingPool']='remaining-pool-2026-10-15.json'
        $compact['constraints']='Solo/main chat; DME forbidden; OpenFlux engine excluded; latest human authority and live leases/receipts prevail'
    }
    $output=$compact|ConvertTo-Json -Depth 64
    $parsed=$output|ConvertFrom-Json
    if($name -eq 'coverage.json'){foreach($key in $taskRetain){
        if(($parsed.$key|ConvertTo-Json -Depth 64 -Compress) -cne ($taskEntry.data.$key|ConvertTo-Json -Depth 64 -Compress)){throw 'Retained requirement/screenshot metadata changed'}
    }}
    $taskInputs[$name]['compact']=$output
    $taskRows+=@{path=$name;originalBytes=$taskEntry.bytes.Length;originalSha256=$taskEntry.sha256;gzip=$archiveRelative;gzipBytes=(Get-Item -LiteralPath $gzip).Length;compactBytes=[Text.Encoding]::UTF8.GetByteCount($output)}
}
foreach($name in $taskNames){if((Get-FileHash -LiteralPath "$taskCoord/$name").Hash.ToLowerInvariant() -cne $taskInputs[$name].sha256){throw 'Context changed during preparation; no originals replaced'}}
# Mechanical JSON compaction only. Both original byte streams are already
# archived and verified; preserve exact retained contract values, not old labels.
foreach($name in $taskNames){
    $temporary="$taskCoord/$name.compacting-"+[guid]::NewGuid().ToString('N')
    [IO.File]::WriteAllText($temporary,$taskInputs[$name].compact,[Text.UTF8Encoding]::new($false))
    [IO.File]::Move($temporary,"$taskCoord/$name",$true)
}
$receipt=@{passed=$true;timeUtc=[DateTime]::UtcNow.ToString('o');files=$taskRows;requirements=32;screenshots=23;sourceAndLicensesChanged=$false;restore='Decompress named gzip, verify originalSha256 before any explicit restoration; historical state is not live authority'}
[IO.File]::WriteAllText("$taskArchive/manifest.json",($receipt|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
Write-Output "CONTEXT_ARCHIVE=$taskArchive"
$taskRows|ForEach-Object {Write-Output "$($_.path): $($_.originalBytes) -> $($_.compactBytes) bytes; lossless history $($_.gzipBytes) bytes"}
