param([string]$OutputPath,[string[]]$TestSources,[string]$TestEntryPoint,[switch]$Wpf,[string]$DependencyRoot,[string]$WinDivertDll,[string]$CoreExecutable)
$ErrorActionPreference = 'Stop'
if(!$CoreExecutable){throw 'Build native/singbox-host first, then pass -CoreExecutable. The old bundled core lacks private STOP support.'}
$project = Split-Path -Parent $PSScriptRoot
$taskDependencyPrefix=[IO.Path]::GetFullPath((Join-Path $project 'work')).TrimEnd('\')+'\'
if($DependencyRoot){$DependencyRoot=[IO.Path]::GetFullPath($DependencyRoot);if(!(Test-Path -LiteralPath $DependencyRoot -PathType Container)){throw 'Dependency directory is unavailable'}}
function Resolve-TaskDependency([string]$Path){$full=[IO.Path]::GetFullPath($Path);if($DependencyRoot -and $full.StartsWith($taskDependencyPrefix,[StringComparison]::OrdinalIgnoreCase)){return Join-Path $DependencyRoot $full.Substring($taskDependencyPrefix.Length)};return $Path}
if (-not $OutputPath) { $OutputPath = Join-Path $project 'outputs\native\mcrf.exe' }
New-Item -ItemType Directory -Path (Split-Path -Parent ([System.IO.Path]::GetFullPath($OutputPath))) -Force | Out-Null
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $compiler)) { throw 'Windows .NET Framework compiler was not found.' }
$iconTool = Join-Path $project 'work\brand\make-icon.exe'
$iconFile = Join-Path $project 'work\brand\mcrf.ico'
$logoFile = Join-Path $PSScriptRoot 'assets/mcrf-logo.png'
New-Item -ItemType Directory -Path (Split-Path -Parent $iconTool) -Force | Out-Null
& $compiler '/nologo' '/target:exe' "/out:$iconTool" '/r:System.Drawing.dll' "/resource:$logoFile,SplifyWin.UI.Logo.png" (Join-Path $PSScriptRoot 'BrandArt.cs') (Join-Path $PSScriptRoot 'IconGenerator.cs')
if ($LASTEXITCODE -ne 0) { throw 'Icon builder failed.' }
& $iconTool $iconFile
if ($LASTEXITCODE -ne 0) { throw 'Icon generation failed.' }
$core = Join-Path $project 'work\sing-box-lx\sing-box-1.14.2-lx.11-windows-amd64'
$resources = @(
  @($logoFile, 'SplifyWin.UI.Logo.png'),
  @($iconFile, 'SplifyWin.UI.AppIcon.ico'),
  @((Join-Path $PSScriptRoot 'assets/preset-bundle.json'), 'SplifyWin.Presets.bundle'),
  @((Join-Path $project 'work/byedpi/extracted/ciadpi.exe'), 'SplifyWin.ByeTube.ciadpi.exe'),
  @((Join-Path $project 'work/byedpi/LICENSE'), 'SplifyWin.ByeTube.LICENSE'),
  @((Join-Path $PSScriptRoot 'assets/byetube-strategies.txt'), 'SplifyWin.ByeTube.strategies.txt'),
  @((Join-Path $PSScriptRoot 'assets/ByeTube-NOTICES.txt'), 'SplifyWin.ByeTube.NOTICES'),
  @((Join-Path $core 'sing-box.exe'), 'SplifyWin.Core.sing-box.exe'),
  @((Join-Path $project 'work/webview-sdk/package/lib/net462/Microsoft.Web.WebView2.Core.dll'), 'SplifyWin.Video.Microsoft.Web.WebView2.Core.dll'),
  @((Join-Path $project 'work/webview-sdk/package/lib/net462/Microsoft.Web.WebView2.WinForms.dll'), 'SplifyWin.Video.Microsoft.Web.WebView2.WinForms.dll'),
  @((Join-Path $project 'work/webview-sdk/package/runtimes/win-x64/native/WebView2Loader.dll'), 'SplifyWin.Video.WebView2Loader.dll'),
  @((Join-Path $project 'work/webview-sdk/package/LICENSE.txt'), 'SplifyWin.Video.LICENSE.txt'),
  @((Join-Path $project 'work/webview-sdk/package/NOTICE.txt'), 'SplifyWin.Video.NOTICE.txt'),
  @((Join-Path $core 'libcronet.dll'), 'SplifyWin.Core.libcronet.dll'),
  @((Join-Path $core 'LICENSE'), 'SplifyWin.Core.LICENSE'),
  @((Join-Path $project 'work\openflux\LICENSE'), 'SplifyWin.Core.LICENSE.openflux'),
  @((Join-Path $PSScriptRoot 'turn-engines\NOTICES.txt'), 'SplifyWin.Core.LICENSE.turn-engines.txt'),
  @((Join-Path $PSScriptRoot 'assets/optional-engines.json'), 'SplifyWin.Core.engine-catalog.json'),
  @((Join-Path $project 'work\tgws\TgWsProxy_lite_windows_amd64.exe'), 'SplifyWin.Core.tgws.exe'),
  @((Join-Path $project 'work\tgws\LICENSE'), 'SplifyWin.Core.LICENSE.tgws'),
  @((Join-Path $project 'work\warpscout\extracted\warpscout.exe'), 'SplifyWin.Core.warpscout.exe'),
  @((Join-Path $project 'work\warpscout\LICENSE'), 'SplifyWin.Core.LICENSE.warpscout'),
  @((Join-Path $project 'work\curl\curl.exe'), 'SplifyWin.Core.curl.exe'),
  @((Join-Path $project 'work\curl\curl-ca-bundle.crt'), 'SplifyWin.Core.curl-ca-bundle.crt'),
  @((Join-Path $project 'work\curl\LICENSE.curl'), 'SplifyWin.Core.LICENSE.curl'),
  @((Join-Path $project 'work\curl\LICENSE.dependencies.zip'), 'SplifyWin.Core.LICENSE.dependencies.zip'),
  @((Join-Path $project 'work\zapret\flowseal-1.10.3.zip'), 'SplifyWin.Zapret.flowseal.zip'),
  @((Join-Path $project 'work\zapret\v-strategies.md'), 'SplifyWin.Zapret.v.md'),
  @((Join-Path $project 'work\zapret\youtube-strategies.md'), 'SplifyWin.Zapret.youtube.md'),
  @((Join-Path $project 'work\zapret\LICENSE.WinDivert'), 'SplifyWin.Zapret.LICENSE.WinDivert'),
  @((Join-Path $project 'work\zapret\LICENSE.zapret'), 'SplifyWin.Zapret.LICENSE.zapret'),
  @((Join-Path $project 'work\zapret\winws-mcrf.exe'), 'SplifyWin.Zapret.apps.winws.exe'),
  @((Join-Path $project 'work\zapret\cygwin-sdk\usr\bin\cygwin1.dll'), 'SplifyWin.Zapret.apps.cygwin1.dll'),
  @($(if($WinDivertDll){[IO.Path]::GetFullPath($WinDivertDll)}else{Join-Path $project 'work\windivert-mrcf-wd\WinDivert.dll'}), 'SplifyWin.Zapret.apps.WinDivert.dll'),
  @((Join-Path $PSScriptRoot 'windivert-service\README.md'), 'SplifyWin.Zapret.MRCF_WD.NOTICES.md'),
  @((Join-Path $project 'work\zapret\WinDivert64.sys'), 'SplifyWin.Zapret.apps.WinDivert64.sys'),
  @((Join-Path $PSScriptRoot 'zapret-engine\README.md'), 'SplifyWin.Zapret.apps.NOTICES.md'),
  @((Join-Path $PSScriptRoot 'zapret-engine\LICENSE.MCRF'), 'SplifyWin.Zapret.apps.LICENSE.MCRF'),
  @((Join-Path $project 'work\zapret\tls_clienthello_vk_com.bin'), 'SplifyWin.Zapret.tls_clienthello_vk_com.bin'),
  @((Join-Path $project 'work\zapret\tls_clienthello_gosuslugi_ru.bin'), 'SplifyWin.Zapret.tls_clienthello_gosuslugi_ru.bin'),
  @((Join-Path $PSScriptRoot 'assets\blue-glass-backdrop.png'), 'SplifyWin.UI.BlueGlassBackdrop.png')
)
if ($Wpf) {
  $qrLibrary=Resolve-TaskDependency (Join-Path $project 'work/qrcode/qrcoder-1.6.0/package/lib/net40/QRCoder.dll')
  if ((Get-FileHash -LiteralPath $qrLibrary -Algorithm SHA256).Hash -ne '5AE2792C76262943A4E34140BBD64B2AA7D9ED5C5822680C00D9EAA322412680') { throw 'QRCoder dependency checksum changed.' }
  $resources+=,@($qrLibrary,'Mcrf.Managed.QRCoder.dll')
  $resources+=,@((Join-Path $project 'work/qrcode/qrcoder-1.6.0/LICENSE.txt'),'Mcrf.Managed.LICENSE.QRCoder.txt')
  $sshRoot=Join-Path $project 'work/sshnet'
  $sshFiles=@('ssh.net-2025.1.0/lib/net462/Renci.SshNet.dll','microsoft.bcl.asyncinterfaces-8.0.0/lib/net462/Microsoft.Bcl.AsyncInterfaces.dll','bouncycastle.cryptography-2.6.2/lib/net461/BouncyCastle.Cryptography.dll','microsoft.extensions.logging.abstractions-8.0.3/lib/net462/Microsoft.Extensions.Logging.Abstractions.dll','microsoft.extensions.dependencyinjection.abstractions-8.0.2/lib/net462/Microsoft.Extensions.DependencyInjection.Abstractions.dll','system.formats.asn1-8.0.2/lib/net462/System.Formats.Asn1.dll','system.memory-4.5.5/lib/net461/System.Memory.dll','system.numerics.vectors-4.5.0/lib/net46/System.Numerics.Vectors.dll','system.buffers-4.5.1/lib/net461/System.Buffers.dll','system.runtime.compilerservices.unsafe-6.0.0/lib/net461/System.Runtime.CompilerServices.Unsafe.dll','system.threading.tasks.extensions-4.5.4/lib/net461/System.Threading.Tasks.Extensions.dll')
  foreach ($file in $sshFiles) { $resources+=,@((Join-Path $sshRoot $file),('Mcrf.Managed.'+[IO.Path]::GetFileName($file))) }
}
$arguments = @('/nologo', '/platform:x64', '/target:winexe', '/optimize+', "/out:$OutputPath", "/win32icon:$iconFile", "/win32manifest:$(Join-Path $PSScriptRoot 'assets\app.manifest')", '/r:System.Windows.Forms.dll', '/r:System.Web.Extensions.dll', '/r:System.Web.dll', '/r:System.IO.Compression.dll', '/r:System.IO.Compression.FileSystem.dll')
Add-Type -AssemblyName System.IO.Compression
$packedRoot=Join-Path $project 'work/packed-resources'
New-Item -ItemType Directory -Path $packedRoot -Force | Out-Null
foreach ($item in $resources) {
  if($CoreExecutable -and $item[1] -eq 'SplifyWin.Core.sing-box.exe'){$item[0]=[IO.Path]::GetFullPath($CoreExecutable);if(!$item[0].StartsWith('D:\MCRF\Work\Windows\',[StringComparison]::OrdinalIgnoreCase)){throw 'Private core must be Windows-task-owned'}}
  if($item[1] -ne 'SplifyWin.Zapret.apps.WinDivert.dll'){$item[0]=Resolve-TaskDependency $item[0]}
  # Optional transports are downloaded on demand, never embedded in mcrf.exe.
  if ([IO.Path]::GetFileName($item[0]) -match '^(openflux(?:-windows-amd64)?|csqtt(?:20|215|25)?-client|wdtt-client|qwdtt-client|libunwind)\.(exe|dll)$') {
    throw "Optional engine must not be bundled: $($item[0])"
  }
  if (-not (Test-Path $item[0])) { throw "Missing bundled resource: $($item[0])" }
  if ($item[0] -match '\.(exe|dll)$' -or $item[1] -eq 'SplifyWin.Presets.bundle') {
    $packed=Join-Path $packedRoot ($item[1]+'.deflate')
    $input=[IO.File]::OpenRead($item[0]);$output=[IO.File]::Create($packed)
    try{$deflate=[IO.Compression.DeflateStream]::new($output,[IO.Compression.CompressionLevel]::Optimal,$true);try{$input.CopyTo($deflate)}finally{$deflate.Dispose()}}finally{$input.Dispose();$output.Dispose()}
    $arguments += "/resource:$packed,$($item[1]).deflate"
  } else { $arguments += "/resource:$($item[0]),$($item[1])" }
}
$arguments += @((Join-Path $PSScriptRoot 'AssemblyInfo.cs'), (Join-Path $PSScriptRoot 'AppBrand.cs'), (Join-Path $PSScriptRoot 'ClientModel.cs'), (Join-Path $PSScriptRoot 'PresetStorage.cs'), (Join-Path $PSScriptRoot 'Qwdtt.cs'), (Join-Path $PSScriptRoot 'VkCalls.cs'), (Join-Path $PSScriptRoot 'TurnProfile.cs'), (Join-Path $PSScriptRoot 'TurnEngines.cs'), (Join-Path $PSScriptRoot 'NetworkCore.cs'), (Join-Path $PSScriptRoot 'RouteCompiler.cs'), (Join-Path $PSScriptRoot 'SecondPart.cs'), (Join-Path $PSScriptRoot 'Zapret.cs'), (Join-Path $PSScriptRoot 'OwnedJob.cs'), (Join-Path $PSScriptRoot 'WarpScout.cs'), (Join-Path $PSScriptRoot 'SystemProxy.cs'), (Join-Path $PSScriptRoot 'BrandArt.cs'), (Join-Path $PSScriptRoot 'BlueGlassDashboard.cs'), (Join-Path $PSScriptRoot 'NativeClient.cs'))
$arguments += (Join-Path $PSScriptRoot 'WinDivertService.cs')
$arguments += (Join-Path $PSScriptRoot 'VkTurnSettings.cs')
$arguments += (Join-Path $PSScriptRoot 'VkTurnOptions.cs')
$arguments += (Join-Path $PSScriptRoot 'VkTurnProfileImport.cs')
$arguments += (Join-Path $PSScriptRoot 'CsqttGenerations.cs')
$arguments += (Join-Path $PSScriptRoot 'VkMasqueRuntime.cs')
$arguments += (Join-Path $PSScriptRoot 'CoreProcessHost.cs')
$arguments += (Join-Path $PSScriptRoot 'TunIdentity.cs')
$arguments += (Join-Path $PSScriptRoot 'ClientJournal.cs')
$arguments += (Join-Path $PSScriptRoot 'ChangeConfirmation.cs')
$arguments += (Join-Path $PSScriptRoot 'HostsRepair.cs')
$arguments += (Join-Path $PSScriptRoot 'HostsCandidates.cs')
$arguments += (Join-Path $PSScriptRoot 'HostsEditor.cs')
$arguments += (Join-Path $PSScriptRoot 'BrowserServiceProbe.cs')
$arguments += (Join-Path $PSScriptRoot 'ResultAssignment.cs')
$arguments += (Join-Path $PSScriptRoot 'ZapretChecks.cs')
$arguments += (Join-Path $PSScriptRoot 'DiscordInterfaceProbe.cs')
$arguments += (Join-Path $PSScriptRoot 'DiscordVoiceCheck.cs')
$arguments += (Join-Path $PSScriptRoot 'DiscordVoiceScores.cs')
$arguments += (Join-Path $PSScriptRoot 'DiscordVoiceResults.cs')
$arguments += (Join-Path $PSScriptRoot 'ZapretProfileEditor.cs')
$arguments += (Join-Path $PSScriptRoot 'ZapretTablePage.cs')
$arguments += (Join-Path $PSScriptRoot 'ExitTablePage.cs')
$arguments += (Join-Path $PSScriptRoot 'TrayPopupNative.cs')
$arguments += (Join-Path $PSScriptRoot 'BestServiceProfiles.cs')
$arguments += (Join-Path $PSScriptRoot 'WarpOutputs.cs')
$arguments += (Join-Path $PSScriptRoot 'ByeTube.cs')
$arguments += (Join-Path $PSScriptRoot 'OutsideRussiaPreset.cs')
$arguments += (Join-Path $PSScriptRoot 'VpnPresetConstructor.cs')
$arguments += (Join-Path $PSScriptRoot 'NativeZapretResults.cs')
$arguments += (Join-Path $PSScriptRoot 'BundledResources.cs')
$arguments += (Join-Path $PSScriptRoot 'OptionalEngines.cs')
$arguments += (Join-Path $PSScriptRoot 'Csqtt25Link.cs')
$arguments += (Join-Path $PSScriptRoot 'NativeEngineDownloads.cs')
$arguments += (Join-Path $PSScriptRoot 'ZapretResultTable.cs')
$arguments += (Join-Path $PSScriptRoot 'NativeZapretProfiles.cs')
$arguments += (Join-Path $PSScriptRoot 'ZapretRoutes.cs')
$arguments += (Join-Path $PSScriptRoot 'AppUpdates.cs')
$arguments += (Join-Path $PSScriptRoot 'CoreEngineUpdates.cs')
$arguments += (Join-Path $PSScriptRoot 'NativeUpdates.cs')
$arguments += '/r:System.Security.dll'
$arguments += (Join-Path $PSScriptRoot 'NativeSetup.cs')
$arguments += (Join-Path $PSScriptRoot 'SetupDistribution.cs')
$arguments += (Join-Path $PSScriptRoot 'ServiceManualCheck.cs')
$arguments += (Join-Path $PSScriptRoot 'SetupHostsCheck.cs')
$arguments += (Join-Path $PSScriptRoot 'LiveChecks.cs')
$arguments += (Join-Path $PSScriptRoot 'BoundedProbe.cs')
$arguments += (Join-Path $PSScriptRoot 'VideoProbe.cs')
$arguments += (Join-Path $PSScriptRoot 'BrowserVideoProbe.cs')
$arguments += ('/r:'+(Join-Path $project 'work/webview-sdk/package/lib/net462/Microsoft.Web.WebView2.Core.dll'))
$arguments += ('/r:'+(Join-Path $project 'work/webview-sdk/package/lib/net462/Microsoft.Web.WebView2.WinForms.dll'))
$arguments += (Join-Path $PSScriptRoot 'ExitVerification.cs')
$arguments += (Join-Path $PSScriptRoot 'SimpleDashboard.cs')
$arguments += (Join-Path $PSScriptRoot 'ServiceConstructor.cs')
$arguments += (Join-Path $PSScriptRoot 'ProfileStorage.cs')
$arguments += (Join-Path $PSScriptRoot 'WindowsStartup.cs')
$arguments += '/r:Microsoft.CSharp.dll'
$arguments += (Join-Path $PSScriptRoot 'ByeTubeScan.cs')
$arguments += (Join-Path $PSScriptRoot 'ExitDns.cs')
$arguments += (Join-Path $PSScriptRoot 'NeonServerMap.cs')
$arguments += ('/resource:' + (Join-Path $PSScriptRoot 'assets\mcrf-dotted-atlas.png') + ',SplifyWin.UI.NeonGlobe.png')
$arguments += (Join-Path $PSScriptRoot 'GameProfiles.cs')
$arguments += (Join-Path $PSScriptRoot 'GameFlowObserver.cs')
$arguments += (Join-Path $PSScriptRoot 'WardogsAutopause.cs')
$arguments += (Join-Path $PSScriptRoot 'AdaptiveStrategySearch.cs')
$arguments += (Join-Path $PSScriptRoot 'StrategyEditorPage.cs')
$arguments += (Join-Path $PSScriptRoot 'StrategyParameterEditor.cs')
$arguments += (Join-Path $PSScriptRoot 'BatStrategyConfig.cs')
$arguments += (Join-Path $PSScriptRoot 'RouteInlineEditor.cs')
$arguments += (Join-Path $PSScriptRoot 'ProfileHotkeys.cs')
$arguments += (Join-Path $PSScriptRoot 'SingleInstance.cs')
$arguments += (Join-Path $PSScriptRoot 'NetworkWallpaper.cs')
$arguments += (Join-Path $PSScriptRoot 'DiscordCaptureFilter.cs')
$arguments += @('ApplicationExclusions.cs','SteamGameTemplates.cs','RoutingToolsPage.cs','CustomStrategies.cs','StrategyWorkshopPage.cs','RouteDnsPage.cs') | ForEach-Object { Join-Path $PSScriptRoot $_ }
if ($Wpf) {
  $wpfRoot=Join-Path $project 'wpf'
  $arguments=@($arguments | Where-Object { $_ -notlike '/win32manifest:*' })
  $arguments+=('/win32manifest:'+(Join-Path $wpfRoot 'app.manifest'))
  $framework=Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319'
  $arguments+=('/r:'+$qrLibrary)
  $arguments+=@('/r:System.Xaml.dll',('/r:'+(Join-Path $project 'work/sshnet/ssh.net-2025.1.0/lib/net462/Renci.SshNet.dll')))
  $arguments+=@('PresentationFramework.dll','PresentationCore.dll','WindowsBase.dll','WindowsFormsIntegration.dll') | ForEach-Object { '/r:'+(Join-Path $framework ('WPF/'+$_)) }
  $arguments+=Get-ChildItem -LiteralPath $wpfRoot -Filter '*.cs' | ForEach-Object FullName
  $arguments+=('/resource:'+(Join-Path $wpfRoot 'Theme.xaml')+',Mcrf.Wpf.Theme.xaml')
  $arguments+=('/resource:'+(Join-Path $wpfRoot 'Provisioning.py')+',Mcrf.Deployment.Provisioning.py')
  if (!$TestSources) { $arguments+='/main:Mcrf.Wpf.Program' }
}
if ($TestSources) {
  if (-not $TestEntryPoint) { throw 'Tests require an explicit entry point.' }
  $arguments = @('/target:exe', "/main:$TestEntryPoint") + @($arguments | Where-Object { $_ -ne '/target:winexe' -and $_ -notlike '/win32manifest:*' })
  $arguments += $TestSources
}
$arguments=@($arguments | ForEach-Object {if($_ -like '/r:*'){'/r:'+(Resolve-TaskDependency $_.Substring(3))}else{$_}})
& $compiler @arguments
if ($LASTEXITCODE -ne 0) { throw "C# build failed: $LASTEXITCODE" }
$built = Get-Item -LiteralPath $OutputPath
Write-Output "Built $($built.FullName) ($($built.Length) bytes)"
