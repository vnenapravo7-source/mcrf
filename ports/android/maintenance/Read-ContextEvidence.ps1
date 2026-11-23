param([ValidateSet('coverage.json','solo-resume-2026-10-15.json')][string]$File='coverage.json',[string]$Key='',[int]$Index=-1,[string]$Property='',[switch]$History,[string]$Prefix='',[ValidateRange(500,8000)][int]$MaxChars=3500)
$ErrorActionPreference='Stop'
$taskCoord='D:/MCRF/Projects/MCRF/ports/android/coordination'
$taskData=Get-Content -LiteralPath "$taskCoord/$File" -Raw|ConvertFrom-Json
if($History){
    if(!$Key -and !$Prefix){throw 'Historical reads require an exact key or key prefix'}
    $taskArchive=[IO.Path]::GetFullPath((Join-Path $taskCoord $taskData.historyArchive))
    if(!$taskArchive.StartsWith([IO.Path]::GetFullPath("$taskCoord/archive")+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'History path outside archive root'}
    $taskStream=[IO.File]::OpenRead($taskArchive);$taskBuffer=[IO.MemoryStream]::new()
    try{$taskGzip=[IO.Compression.GZipStream]::new($taskStream,[IO.Compression.CompressionMode]::Decompress);try{$taskGzip.CopyTo($taskBuffer)}finally{$taskGzip.Dispose()}}finally{$taskStream.Dispose()}
    try{
        $taskBytes=$taskBuffer.ToArray()
        if([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($taskBytes)).ToLowerInvariant() -cne $taskData.historyOriginalSha256){throw 'Archive digest mismatch'}
        $taskData=[Text.Encoding]::UTF8.GetString($taskBytes)|ConvertFrom-Json
    }finally{$taskBuffer.Dispose()}
}
if(!$Key){
    $taskNames=@($taskData.PSObject.Properties.Name|Where-Object {!$Prefix -or $_.StartsWith($Prefix,[StringComparison]::OrdinalIgnoreCase)})
    [pscustomobject]@{matchingKeys=$taskNames.Count;keys=@($taskNames|Select-Object -First 25);hint='Choose -Key; refine -Prefix when more than25. Arrays support -Index and -Property.'}|ConvertTo-Json -Depth 3
    return
}
$taskEntry=$taskData.PSObject.Properties[$Key]
if($null -eq $taskEntry){throw 'Key absent; request selected historical key explicitly with -History if needed'}
$taskValue=$taskEntry.Value
if($Index -ge 0){if($taskValue -isnot [array] -or $Index -ge $taskValue.Count){throw 'Array index outside selected field'};$taskValue=$taskValue[$Index]}
if($Property){$taskEntry=$taskValue.PSObject.Properties[$Property];if($null -eq $taskEntry){throw 'Selected nested property absent'};$taskValue=$taskEntry.Value}
$taskOutput=$taskValue|ConvertTo-Json -Depth 64
if($taskOutput.Length -gt $MaxChars){throw "Selected evidence is $($taskOutput.Length) chars; refine -Index/-Property instead of dumping history"}
$taskOutput
