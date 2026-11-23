param([string]$BuildScratchRoot='')
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskGo=Join-Path $taskRoot 'work/toolchains/go1.26.8/go/bin/go.exe'
if(!$BuildScratchRoot){$BuildScratchRoot=(Get-Content -LiteralPath (Join-Path $taskRoot 'ports/android/coordination/build-retention.json') -Raw|ConvertFrom-Json).generatedScratchRoot}
if(![IO.Path]::IsPathRooted($BuildScratchRoot)){throw 'An absolute generated build scratch directory is required.'}
$taskScratch=[IO.Path]::GetFullPath($BuildScratchRoot)
New-Item -ItemType Directory -Path $taskScratch -Force|Out-Null
$taskOutput=Join-Path $taskScratch ('WireGuard-native-output-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskOutput | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'go.mod'),(Join-Path $PSScriptRoot 'go.sum'),(Join-Path $PSScriptRoot 'WireGuard_native_test.go') -Destination $taskOutput
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'cmd') -Destination $taskOutput -Recurse
$taskEnvironment=@{};foreach($taskKey in @('GOPATH','GOCACHE','GOTOOLCHAIN','GOOS','GOARCH','CGO_ENABLED')){$taskEnvironment[$taskKey]=[Environment]::GetEnvironmentVariable($taskKey,'Process')}
Push-Location $taskOutput
try {
    $env:GOPATH=Join-Path $taskRoot 'work/toolchains/go-home';$env:GOCACHE=Join-Path $taskOutput 'cache';$env:GOTOOLCHAIN='local';$env:CGO_ENABLED='0'
    Remove-Item Env:GOOS,Env:GOARCH -ErrorAction SilentlyContinue
    & $taskGo mod verify
    if($LASTEXITCODE -ne 0){throw 'Fixture dependency verification failed'}
    & $taskGo test '-mod=readonly' '-ldflags=-checklinkname=0' -count=1 -timeout 90s -v ./...
    if($LASTEXITCODE -ne 0){throw 'Isolated native WG/AWG fixture failed'}
    Write-Output "Native fixture output: $taskOutput. Loopback-only; no account/VPS/phone/Gradle. AWG3/3.1 requires coordinator's exact libbox fixture."
} finally {
    Pop-Location
    foreach($taskKey in $taskEnvironment.Keys){[Environment]::SetEnvironmentVariable($taskKey,$taskEnvironment[$taskKey],'Process')}
}
