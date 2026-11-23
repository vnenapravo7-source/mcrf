$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$output=(& python "$root/ports/android/app/src/test/AndroidDeploySwg25ProvisioningTest.py" --subnet-followup 2>&1|Out-String)
$code=$LASTEXITCODE
Write-Output $output.Trim()
if($code -ne 0 -or $output -notmatch 'Ran 3 tests' -or $output -notmatch '(?m)^OK\s*$'){throw 'Three NEW SWG subnet/error checks required'}
Write-Output 'SWG_SUBNET_NEW=3'
