$ErrorActionPreference='Stop'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation build
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$output=(& python -B "$root/ports/android/app/src/test/ServerManagementDiagnosticsTest.py" 2>&1|Out-String)
$code=$LASTEXITCODE
Write-Output $output.Trim()
if($code -ne 0 -or $output -notmatch 'Ran 3 tests' -or $output -notmatch '(?m)^OK\s*$'){throw 'Three fresh management boundary checks required'}
Write-Output 'MANAGEMENT_DIAGNOSTICS_NEW=3'
