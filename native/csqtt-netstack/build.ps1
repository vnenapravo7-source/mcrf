param([Parameter(Mandatory=$true)][string]$Go,[Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference='Stop'
$env:CGO_ENABLED='0';$env:GOOS='windows';$env:GOARCH='amd64'
Push-Location $PSScriptRoot
try{& $Go build -mod=readonly -trimpath -ldflags '-s -w' -o $Output .;if($LASTEXITCODE -ne 0){throw 'IP adapter build failed'}}finally{Pop-Location}
