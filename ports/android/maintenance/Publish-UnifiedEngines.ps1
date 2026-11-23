param()
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
if($taskRoot -ne 'D:\MCRF\Projects\MCRF'){throw 'Unexpected root'}
$taskStage=Join-Path $taskRoot 'outputs/unified-engines'
$taskRepo='vnenapravo7-source/mcrf'
$taskTag='engines-2026-10-13-1'
$taskExpected=@{}
foreach($taskLine in Get-Content -LiteralPath (Join-Path $taskStage 'SHA256SUMS.txt')){
    if($taskLine -notmatch '^([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9._-]+)$'){throw 'Malformed SHA manifest'}
    $taskName=$Matches[2];$taskHash=$Matches[1]
    $taskPath=Join-Path $taskStage $taskName
    if((Get-FileHash -LiteralPath $taskPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskHash){throw 'Stage changed before upload'}
    $taskExpected[$taskName]=$taskHash
}
$taskExpected['SHA256SUMS.txt']=(Get-FileHash -LiteralPath (Join-Path $taskStage 'SHA256SUMS.txt') -Algorithm SHA256).Hash.ToLowerInvariant()
$taskState=(& gh release view $taskTag --repo $taskRepo --json assets,isDraft|Out-String|ConvertFrom-Json)
if($LASTEXITCODE -ne 0 -or $taskState.isDraft){throw 'Expected existing public Windows catalogue release'}
$taskPending=@()
foreach($taskName in @($taskExpected.Keys|Sort-Object)){
    $taskExisting=@($taskState.assets|Where-Object name -eq $taskName)
    if($taskExisting.Count -gt 1){throw 'Duplicate remote asset'}
    if($taskExisting.Count){
        if($taskExisting[0].digest -ne 'sha256:'+$taskExpected[$taskName]){throw 'Existing draft asset differs; no overwrite attempted'}
    }else{$taskPending+=Join-Path $taskStage $taskName}
}
if($taskPending.Count){
    & gh release upload $taskTag --repo $taskRepo @taskPending
    if($LASTEXITCODE -ne 0){throw 'Upload incomplete; retain draft and resume after inspecting current process/assets'}
}
$taskVerified=(& gh release view $taskTag --repo $taskRepo --json assets|Out-String|ConvertFrom-Json)
if($LASTEXITCODE -ne 0 -or $taskVerified.assets.Count -lt $taskExpected.Count){throw 'Remote asset count mismatch'}
foreach($taskName in $taskExpected.Keys){
    $taskMatches=@($taskVerified.assets|Where-Object name -eq $taskName)
    if($taskMatches.Count -ne 1){throw 'Missing or duplicate remote asset'}
    $taskAsset=$taskMatches[0]
    if($taskAsset.digest -ne 'sha256:'+$taskExpected[$taskAsset.name] -or $taskAsset.size -ne (Get-Item -LiteralPath (Join-Path $taskStage $taskAsset.name)).Length){throw 'Remote size/digest mismatch'}
}
Write-Output "UPDATED_EXISTING_RELEASE=$taskTag VERIFIED_ASSETS=$($taskExpected.Count)"
