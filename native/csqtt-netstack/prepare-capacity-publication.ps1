param([Parameter(Mandatory=$true)][string]$OutputRoot,[Parameter(Mandatory=$true)][string]$BackupRoot,[Parameter(Mandatory=$true)][string]$BridgeZip)
$ErrorActionPreference='Stop';Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskOut=[IO.Path]::GetFullPath($OutputRoot);if(!$taskOut.StartsWith('D:\MCRF\Builds\Windows\',[StringComparison]::OrdinalIgnoreCase)){throw 'Windows artifact root required'}
New-Item -ItemType Directory -Path $taskOut -Force|Out-Null
$taskSourceZip=Join-Path $taskOut 'mcrf-csqtt-bridge-capacity-20261023-1-source.zip';if(Test-Path -LiteralPath $taskSourceZip){throw 'Existing source artifact retained'}
$taskArchive=[IO.Compression.ZipFile]::Open($taskSourceZip,[IO.Compression.ZipArchiveMode]::Create)
try{foreach($taskFile in Get-ChildItem -LiteralPath $PSScriptRoot -File){if($taskFile.Extension -notin @('.go','.mod','.sum','.md','.ps1') -and $taskFile.Name -notin @('LICENSE','NOTICE')){continue};[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskArchive,$taskFile.FullName,'native/csqtt-netstack/'+$taskFile.Name,[IO.Compression.CompressionLevel]::Optimal)|Out-Null}
 [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskArchive,(Join-Path $PSScriptRoot '../assets/optional-engines.json'),'native/assets/optional-engines.json',[IO.Compression.CompressionLevel]::Optimal)|Out-Null
}finally{$taskArchive.Dispose()}
Copy-Item -LiteralPath $BridgeZip -Destination (Join-Path $taskOut (Split-Path $BridgeZip -Leaf))
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../assets/optional-engines.json') -Destination (Join-Path $taskOut 'engine-catalog.json')
$taskChecksums=Get-Content -LiteralPath (Join-Path $BackupRoot 'SHA256SUMS.txt')
$taskNames=@((Split-Path $BridgeZip -Leaf),'mcrf-csqtt-bridge-capacity-20261023-1-source.zip','engine-catalog.json')
$taskLines=@($taskChecksums|Where-Object {$taskName=($_ -split '\s+',2)[1];$taskName -notin $taskNames})
foreach($taskName in $taskNames){$taskLines+=((Get-FileHash -LiteralPath (Join-Path $taskOut $taskName)).Hash.ToLowerInvariant()+'  '+$taskName)}
$taskLines|Set-Content -LiteralPath (Join-Path $taskOut 'SHA256SUMS.txt') -Encoding UTF8
Write-Output 'Prepared ONLY Windows bridge ZIP/source, Windows engine catalog and merged checksums. Nothing uploaded.'
