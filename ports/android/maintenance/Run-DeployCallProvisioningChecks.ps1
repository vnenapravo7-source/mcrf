$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$output=(& python "$root/ports/android/app/src/test/AndroidDeployProvisioningTest.py" --new-deploy-call-checks 2>&1|Out-String)
$code=$LASTEXITCODE
Write-Output $output.Trim()
if($code -ne 0 -or $output -notmatch 'Ran 2 tests' -or $output -notmatch '(?m)^OK\s*$'){throw 'Two fresh deploy-call provisioning checks required'}
Write-Output 'DEPLOY_CALL_PYTHON_NEW=2'
