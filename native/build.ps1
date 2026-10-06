param([string]$OutputPath,[string[]]$TestSources,[string]$TestEntryPoint)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
if (-not $OutputPath) { $OutputPath = Join-Path $project 'outputs\native\mcrf.exe' }
New-Item -ItemType Directory -Path (Split-Path -Parent ([System.IO.Path]::GetFullPath($OutputPath))) -Force | Out-Null
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $compiler)) { throw 'Windows .NET Framework compiler was not found.' }
$iconTool = Join-Path $project 'work\brand\make-icon.exe'
$iconFile = Join-Path $project 'work\brand\7olet.ico'
New-Item -ItemType Directory -Path (Split-Path -Parent $iconTool) -Force | Out-Null
& $compiler '/nologo' '/target:exe' "/out:$iconTool" '/r:System.Drawing.dll' (Join-Path $PSScriptRoot 'BrandArt.cs') (Join-Path $PSScriptRoot 'IconGenerator.cs')
if ($LASTEXITCODE -ne 0) { throw 'Icon builder failed.' }
& $iconTool $iconFile
if ($LASTEXITCODE -ne 0) { throw 'Icon generation failed.' }
$core = Join-Path $project 'work\sing-box-lx\sing-box-1.14.2-lx.11-windows-amd64'
$resources = @(
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
  @((Join-Path $project 'outputs\engine-packages\engine-catalog.json'), 'SplifyWin.Core.engine-catalog.json'),
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
  @((Join-Path $project 'work\zapret\WinDivert.dll'), 'SplifyWin.Zapret.apps.WinDivert.dll'),
  @((Join-Path $project 'work\zapret\WinDivert64.sys'), 'SplifyWin.Zapret.apps.WinDivert64.sys'),
  @((Join-Path $PSScriptRoot 'zapret-engine\README.md'), 'SplifyWin.Zapret.apps.NOTICES.md'),
  @((Join-Path $PSScriptRoot 'zapret-engine\LICENSE.MCRF'), 'SplifyWin.Zapret.apps.LICENSE.MCRF'),
  @((Join-Path $project 'work\zapret\tls_clienthello_vk_com.bin'), 'SplifyWin.Zapret.tls_clienthello_vk_com.bin'),
  @((Join-Path $project 'work\zapret\tls_clienthello_gosuslugi_ru.bin'), 'SplifyWin.Zapret.tls_clienthello_gosuslugi_ru.bin'),
  @((Join-Path $PSScriptRoot 'assets\blue-glass-backdrop.png'), 'SplifyWin.UI.BlueGlassBackdrop.png')
)
$arguments = @('/nologo', '/platform:x64', '/target:winexe', '/optimize+', "/out:$OutputPath", "/win32icon:$iconFile", "/win32manifest:$(Join-Path $PSScriptRoot 'assets\app.manifest')", '/r:System.Windows.Forms.dll', '/r:System.Web.Extensions.dll', '/r:System.Web.dll', '/r:System.IO.Compression.dll', '/r:System.IO.Compression.FileSystem.dll')
Add-Type -AssemblyName System.IO.Compression
$packedRoot=Join-Path $project 'work/packed-resources'
New-Item -ItemType Directory -Path $packedRoot -Force | Out-Null
foreach ($item in $resources) {
  if (-not (Test-Path $item[0])) { throw "Missing bundled resource: $($item[0])" }
  if ($item[0] -match '\.(exe|dll)$') {
    $packed=Join-Path $packedRoot ($item[1]+'.deflate')
    $input=[IO.File]::OpenRead($item[0]);$output=[IO.File]::Create($packed)
    try{$deflate=[IO.Compression.DeflateStream]::new($output,[IO.Compression.CompressionLevel]::Optimal,$true);try{$input.CopyTo($deflate)}finally{$deflate.Dispose()}}finally{$input.Dispose();$output.Dispose()}
    $arguments += "/resource:$packed,$($item[1]).deflate"
  } else { $arguments += "/resource:$($item[0]),$($item[1])" }
}
$arguments += @((Join-Path $PSScriptRoot 'AssemblyInfo.cs'), (Join-Path $PSScriptRoot 'AppBrand.cs'), (Join-Path $PSScriptRoot 'ClientModel.cs'), (Join-Path $PSScriptRoot 'PresetStorage.cs'), (Join-Path $PSScriptRoot 'TurnProfile.cs'), (Join-Path $PSScriptRoot 'TurnEngines.cs'), (Join-Path $PSScriptRoot 'NetworkCore.cs'), (Join-Path $PSScriptRoot 'RouteCompiler.cs'), (Join-Path $PSScriptRoot 'SecondPart.cs'), (Join-Path $PSScriptRoot 'Zapret.cs'), (Join-Path $PSScriptRoot 'OwnedJob.cs'), (Join-Path $PSScriptRoot 'WarpScout.cs'), (Join-Path $PSScriptRoot 'SystemProxy.cs'), (Join-Path $PSScriptRoot 'BrandArt.cs'), (Join-Path $PSScriptRoot 'BlueGlassDashboard.cs'), (Join-Path $PSScriptRoot 'NativeClient.cs'))
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
$arguments += (Join-Path $PSScriptRoot 'NativeEngineDownloads.cs')
$arguments += (Join-Path $PSScriptRoot 'ZapretResultTable.cs')
$arguments += (Join-Path $PSScriptRoot 'NativeZapretProfiles.cs')
$arguments += (Join-Path $PSScriptRoot 'ZapretRoutes.cs')
$arguments += (Join-Path $PSScriptRoot 'AppUpdates.cs')
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
$arguments += (Join-Path $PSScriptRoot 'RouteInlineEditor.cs')
if ($TestSources) {
  if (-not $TestEntryPoint) { throw 'Tests require an explicit entry point.' }
  $arguments = @('/target:exe', "/main:$TestEntryPoint") + @($arguments | Where-Object { $_ -ne '/target:winexe' -and $_ -notlike '/win32manifest:*' })
  $arguments += $TestSources
}
& $compiler @arguments
if ($LASTEXITCODE -ne 0) { throw "C# build failed: $LASTEXITCODE" }
$built = Get-Item -LiteralPath $OutputPath
Write-Output "Built $($built.FullName) ($($built.Length) bytes)"

