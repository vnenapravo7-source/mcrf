param([string]$RunName='release-1.0.11',[switch]$OfflineStartupOnly)
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
if ($RunName -notmatch '^release-1\.0\.11(?:-[0-9]+)?$') { throw 'Unexpected release fixture name.' }
$releaseDirectory=Join-Path $project 'outputs/native/release-1.0.11'
$production=Join-Path $releaseDirectory 'mcrf.exe'
$version=(Get-Item -LiteralPath $production).VersionInfo
if ($version.FileVersion -ne '1.0.11.0' -or $version.ProductVersion -ne '1.0.11') { throw 'Wrong release binary version.' }
$fixtures=Join-Path $project ('work/routing-tools-test-2026-10-13/'+$RunName)
if (Test-Path -LiteralPath $fixtures) { throw 'Fresh release verification fixture required.' }
New-Item -ItemType Directory -Path $fixtures | Out-Null
$evidence=Join-Path $releaseDirectory 'verification'
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
$startupDirectory=Join-Path $fixtures 'startup'
New-Item -ItemType Directory -Path $startupDirectory -Force | Out-Null
$probe=Join-Path $startupDirectory 'startup-probe.exe'
$framework=Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319'
& (Join-Path $framework 'csc.exe') /nologo /target:exe ("/out:"+$probe) /r:System.Xaml.dll ("/r:"+(Join-Path $framework 'WPF/WindowsBase.dll')) ("/r:"+(Join-Path $framework 'WPF/PresentationFramework.dll')) ("/r:"+(Join-Path $framework 'WPF/PresentationCore.dll')) (Join-Path $PSScriptRoot 'StartupThemeProbe20261014.cs')
if($LASTEXITCODE -ne 0){throw 'Startup probe compilation failed.'}
# Load the exact production EXE, not another build with a test Main(). Never show a window.
$index=0
$localizedName=(-join ([char[]]@(0x041c,0x043e,0x0439)))+' VPN.exe'
foreach($name in @('mcrf.exe','mcrf (1).exe',$localizedName)) {
 $copyDirectory=Join-Path $startupDirectory ('copy-'+$index)
 New-Item -ItemType Directory -Path $copyDirectory | Out-Null
 $copy=Join-Path $copyDirectory $name
 Copy-Item -LiteralPath $production -Destination $copy
 $lines=@(& $probe $copy --bootstrap 2>&1)
 $code=$LASTEXITCODE
 $lines | Out-File -LiteralPath (Join-Path $evidence ('startup-'+$index+'.txt')) -Encoding utf8
 if($code -ne 0 -or 'STARTUP_OFFLINE_OK 30' -notin $lines){throw ('Exact production startup probe failed: '+$name)}
 Write-Output ('STARTUP_OFFLINE_OK 30 / '+$name)
 $index++
}
if($OfflineStartupOnly){Write-Output ('PRODUCTION_STARTUP_VERIFIED '+(Get-FileHash -LiteralPath $production -Algorithm SHA256).Hash.ToLowerInvariant());return}
$suites=@(
 @{Name='outline';Entry='OutlineDeploymentCheck20261013';Summary='RESULT 3687 checks; 0 failures'},
 @{Name='routing-toggle';Entry='RoutingToggleCheck20261013';Summary='RESULT 160 checks; 0 failures'},
 @{Name='sharing';Entry='DeploymentSharing20261013';Summary='OK 79 deployment sharing and manual VK checks';Args=@('python',(Join-Path $PSScriptRoot 'ProvisioningChecks20261013.py'))},
 @{Name='integration';Entry='DeploymentIntegration20261013';Summary='OK 62 deployment integration checks; no remote server changed.';Args=@('python',(Join-Path $PSScriptRoot 'ProvisioningChecks20261013.py'),(Join-Path $project 'work/deployment-sources/openflux/deploy.sh'))},
 @{Name='functional';Entry='WpfFunctional20261013';Summary='OK 165 functional checks; no TUN adapter or packet capture started'},
 @{Name='branding';Entry='BrandingCheck20261013';Summary='OK 141 branding checks';Args=@($production)}
)
foreach($suite in $suites) {
 $build=Join-Path $fixtures ($suite.Name+'-build')
 $run=Join-Path $fixtures ($suite.Name+'-run')
 & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $project 'wpf/build.ps1') -OutputDirectory $build -TestSource (Join-Path $PSScriptRoot ($suite.Entry+'.cs')) -TestEntryPoint $suite.Entry
 if($LASTEXITCODE -ne 0) { throw ('Test compilation failed: '+$suite.Name) }
 $testArgs=@($run)+@($suite.Args)
 $lines=@(& (Join-Path $build 'MCRF.Wpf.exe') @testArgs 2>&1)
 $code=$LASTEXITCODE
 $lines | Out-File -LiteralPath (Join-Path $evidence ($suite.Name+'.txt')) -Encoding utf8
 if($code -ne 0 -or $suite.Summary -notin $lines) { throw ('Release verification failed: '+$suite.Name) }
 Write-Output $suite.Summary
}
$qr=@(& python -X utf8 (Join-Path $PSScriptRoot 'QRDecodeChecks20261013.py') (Join-Path $fixtures 'sharing-run') 2>&1)
if($LASTEXITCODE -ne 0) { throw 'Independent QR decoding failed.' }
$qr | Out-File -LiteralPath (Join-Path $evidence 'qr-decode.txt') -Encoding utf8
$signature=Get-AuthenticodeSignature -FilePath $production
$hash=(Get-FileHash -LiteralPath $production -Algorithm SHA256).Hash.ToLowerInvariant()
[pscustomobject]@{Version='1.0.11';FileVersion=$version.FileVersion;Bytes=(Get-Item -LiteralPath $production).Length;SHA256=$hash;Authenticode=[string]$signature.Status;Passed=$suites.Summary;QR='7 exact independent decodes';NetworkNote='Remote 3x-ui TCP/gRPC and encrypted OpenFlux Direct verified before this metadata-only version bump; SWG/external carriers not confirmed.'} | ConvertTo-Json -Depth 4 | Out-File -LiteralPath (Join-Path $evidence 'summary.json') -Encoding utf8
Write-Output ('RELEASE_VERIFICATION_OK '+$hash)
