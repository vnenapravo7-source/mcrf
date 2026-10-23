param([Parameter(Mandatory=$true)][string]$OutputRoot,[Parameter(Mandatory=$true)][string]$LicenseRoot)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskOut=[IO.Path]::GetFullPath($OutputRoot)
if(!$taskOut.StartsWith('D:\MCRF\Builds\Windows\',[StringComparison]::OrdinalIgnoreCase)){throw 'Windows build output required'}
foreach($taskLicense in @('LICENSE','THIRD-PARTY-NOTICES.txt')){Copy-Item -LiteralPath (Join-Path $LicenseRoot $taskLicense) -Destination (Join-Path $taskOut ('bridge/'+$taskLicense))}
$taskVersion='capacity-20261023-1'
$taskName='mcrf-csqtt-bridge-windows-amd64-'+$taskVersion+'.zip'
$taskZip=Join-Path $taskOut $taskName
if(Test-Path -LiteralPath $taskZip){throw 'Existing package retained'}
[IO.Compression.ZipFile]::CreateFromDirectory((Join-Path $taskOut 'bridge'),$taskZip,[IO.Compression.CompressionLevel]::Optimal,$false)
$taskCatalog=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../assets/optional-engines.json') -Raw | ConvertFrom-Json
$taskPackage=$taskCatalog.Engines|Where-Object Id -EQ 'csqtt-bridge'
$taskPackage.Version=$taskVersion
$taskPackage.Url='https://github.com/vnenapravo7-source/mcrf/releases/download/engines-2026-10-13-1/'+$taskName
$taskPackage.Size=(Get-Item -LiteralPath $taskZip).Length
$taskPackage.Sha256=(Get-FileHash -LiteralPath $taskZip).Hash.ToLowerInvariant()
$taskPackage.Files=@(Get-ChildItem -LiteralPath (Join-Path $taskOut 'bridge') -File | ForEach-Object {@{Name=$_.Name;Size=$_.Length;Sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}})
$taskCatalog|ConvertTo-Json -Depth 9 | Set-Content -LiteralPath (Join-Path $taskOut 'optional-engines.candidate.json') -Encoding UTF8
Write-Output ('Unpublished local bridge package: '+$taskPackage.Size+' bytes')
