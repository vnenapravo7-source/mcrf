param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
$outputRoot = Join-Path $projectRoot 'outputs\native'
$workRoot = Join-Path $projectRoot 'work'
$targets = [Collections.Generic.List[string]]::new()
$releases = @(Get-ChildItem -LiteralPath $outputRoot -Directory | Where-Object { $_.Name -match '^release-\d+\.\d+\.\d+$' } | Sort-Object { [version]($_.Name.Substring(8)) } -Descending)
$kept = @($releases | Select-Object -First 3)
foreach ($item in ($releases | Select-Object -Skip 3)) { $targets.Add($item.FullName) }
# Only agent-generated local builds with these known names are disposable.
foreach ($item in (Get-ChildItem -LiteralPath $outputRoot -Directory)) {
    if ($item.Name -match '^(routing-tools-local|discord-narrow-local|game-filter-local|game-filter-split-local|hotkeys-local|preset-autosave-local|tray-restore|zapret-restart-local)-\d{4}-\d{2}-\d{2}$') { $targets.Add($item.FullName) }
}
foreach ($item in (Get-ChildItem -LiteralPath $workRoot -Directory)) {
    if ($item.Name -match '^(routing-tools-test|game-split-regression|discord-capture-test|hotkey-test|autosave-regression|zapret-restart-test)-\d{4}-\d{2}-\d{2}([a-z]|-[a-z]+)?$') { $targets.Add($item.FullName) }
    if ($item.Name -match '^cleanup-\d{4}-\d{2}-\d{2}$') {
        $binary = Join-Path $item.FullName 'build-verification.exe'
        if (Test-Path -LiteralPath $binary -PathType Leaf) { $targets.Add($binary) }
    }
}
foreach ($name in @('discord-capture-check.exe','hotkey-check.exe','game-split-regression.exe','autosave-regression.exe')) {
    $binary = Join-Path $workRoot $name
    if (Test-Path -LiteralPath $binary -PathType Leaf) { $targets.Add($binary) }
}
$plan = @($targets | Select-Object -Unique | ForEach-Object {
    $resolved = (Get-Item -LiteralPath $_ -Force).FullName
    if (!$resolved.StartsWith($projectRoot+'\', [StringComparison]::OrdinalIgnoreCase) -or $resolved -eq $projectRoot) { throw "Unsafe cleanup target: $resolved" }
    $item = Get-Item -LiteralPath $resolved -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Linked cleanup target: $resolved" }
    $bytes = if ($item.PSIsContainer) {
        $children = @(Get-ChildItem -LiteralPath $resolved -Force -Recurse)
        if ($children | Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 }) { throw "Linked child in cleanup target: $resolved" }
        [long](($children | Where-Object { !$_.PSIsContainer } | Measure-Object Length -Sum).Sum)
    } else { [long]$item.Length }
    [pscustomobject]@{ Path=$resolved; Bytes=$bytes; Directory=$item.PSIsContainer }
})
Write-Output ('KEEP: '+(($kept | ForEach-Object {$_.Name}) -join ', '))
$plan | Format-Table Path,Bytes -AutoSize
$total = [long](($plan | Measure-Object Bytes -Sum).Sum)
Write-Output ('Disposable bytes: '+$total+' ('+[math]::Round($total/1GB,2)+' GiB)')
if (!$Apply) { Write-Output 'Dry run only. Add -Apply to remove the listed artifacts.'; return }
$reportRoot = Join-Path $workRoot ('cleanup-'+(Get-Date -Format 'yyyy-MM-dd'))
New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
$plan | Export-Csv -LiteralPath (Join-Path $reportRoot 'artifact-cleanup-plan.csv') -NoTypeInformation -Encoding UTF8
$failures = [Collections.Generic.List[string]]::new()
$removed = 0L
foreach ($entry in $plan) {
    try {
        if ($entry.Directory) { Remove-Item -LiteralPath $entry.Path -Recurse -Force } else { Remove-Item -LiteralPath $entry.Path -Force }
        $removed += $entry.Bytes
    } catch { $failures.Add($entry.Path+': '+$_.Exception.Message); Write-Warning $failures[$failures.Count-1] }
}
Write-Output ('Removed bytes: '+$removed+' ('+[math]::Round($removed/1GB,2)+' GiB). Source files remain; generated binaries/fixtures can be rebuilt.')
if ($failures.Count -gt 0) { throw 'Some artifacts could not be removed; inspect warnings. Do not broaden deletion scope.' }
