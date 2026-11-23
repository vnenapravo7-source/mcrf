$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$output=(& python "$root/ports/android/app/src/test/AndroidDeploySwg25ProvisioningTest.py" 2>&1|Out-String)
$code=$LASTEXITCODE
Write-Output $output.Trim()
if($code -ne 0 -or $output -notmatch 'Ran 4 tests' -or $output -notmatch '(?m)^OK\s*$'){throw 'Four NEW SWG25 provisioning checks required'}
Write-Output 'SWG25_PROVISIONING_NEW=4'
