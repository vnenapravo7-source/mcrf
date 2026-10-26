param([string]$OutputDirectory,[string]$TestSource,[string]$TestEntryPoint,[string]$BackendPath)
$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
if (!$OutputDirectory) { $OutputDirectory=Join-Path $project 'outputs/native/routing-tools-local-2026-10-13/pool14' }
$output=[IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $output -Force | Out-Null
$buildArgs=@{OutputPath=(Join-Path $output 'MCRF.Wpf.exe');Wpf=$true;CoreExecutable=$BackendPath}
if ($TestSource) { $buildArgs.TestSources=@([IO.Path]::GetFullPath($TestSource));$buildArgs.TestEntryPoint=$TestEntryPoint }
& (Join-Path $project 'native/build.ps1') @buildArgs
if ($LASTEXITCODE -ne 0) { throw 'WPF single-file build failed.' }
Write-Output ('Single EXE: '+$buildArgs.OutputPath)

