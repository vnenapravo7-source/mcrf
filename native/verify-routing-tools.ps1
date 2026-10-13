param([string]$FixtureDirectory)
$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
if (!$FixtureDirectory) { $FixtureDirectory=Join-Path $project ('work/routing-tools-test-'+(Get-Date -Format 'yyyy-MM-dd')) }
$fixture=[IO.Path]::GetFullPath($FixtureDirectory)
New-Item -ItemType Directory -Path $fixture -Force | Out-Null
$cases=@('AdaptiveConstructorCheck','RoutingToolsCheck','RouteEditorLayoutCheck','UiPerformanceCheck','HomeColumnsCheck','BasicRenderingCheck')
foreach ($entry in $cases) {
    $source=Join-Path $PSScriptRoot ('tests/'+$entry+'.cs')
    $binary=Join-Path $fixture ($entry+'.exe')
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build.ps1') -OutputPath $binary -TestSources $source -TestEntryPoint $entry
    if ($LASTEXITCODE -ne 0) { throw ('Build failed: '+$entry) }
    $data=Join-Path $fixture ($entry+'-'+[Guid]::NewGuid().ToString('N'))
    & $binary $data
    if ($LASTEXITCODE -ne 0) { throw ('Check failed: '+$entry) }
}
Write-Output 'Native routing/editor/layout checks passed. No live network capture was started.'
