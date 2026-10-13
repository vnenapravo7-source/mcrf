param([Parameter(Mandatory=$true)][string]$TestExecutable,[Parameter(Mandatory=$true)][string]$FixtureDirectory,[string]$PriorFixture)
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$allowed=[IO.Path]::GetFullPath((Join-Path $project 'work/routing-tools-test-2026-10-13'))+[IO.Path]::DirectorySeparatorChar
$testPath=[IO.Path]::GetFullPath($TestExecutable)
$fixturePath=[IO.Path]::GetFullPath($FixtureDirectory)
if (!$testPath.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase) -or !$fixturePath.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)) { throw 'Test paths must remain inside the disposable fixture directory.' }
if ((Split-Path $fixturePath -Leaf) -notmatch '^pool16-elevated-run[0-9]+$' -or (Test-Path -LiteralPath $fixturePath)) { throw 'A new, explicitly named elevated fixture is required.' }
if ((Split-Path (Split-Path $testPath -Parent) -Leaf) -ne 'pool16-elevated-build' -or !(Test-Path -LiteralPath $testPath)) { throw 'Unexpected diagnostic executable.' }
trap { $_.Exception.Message | Set-Content -LiteralPath ($fixturePath+'-runner-error.txt'); exit 1 }
$principal=New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Confirm Windows elevation before running this test.' }
function Remove-OwnedDriver([string]$scope) {
 if (!$scope) { return }
 $absolute=[IO.Path]::GetFullPath($scope)
 if (!$absolute.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $absolute -Leaf) -notmatch '^pool16-elevated-run[0-9]+$') { throw 'Unexpected owned-driver fixture.' }
 $expected=Join-Path $absolute 'core/zapret-apps-v1/WinDivert64.sys'
 foreach ($driver in @(Get-CimInstance Win32_SystemDriver | Where-Object Name -eq 'WinDivert')) {
  $path=([string]$driver.PathName).Replace('\??\','').Trim('"')
  if (![string]::Equals($path,$expected,[StringComparison]::OrdinalIgnoreCase)) { continue }
  if (@(Get-Process -Name winws -ErrorAction SilentlyContinue).Count) { throw 'Another packet runtime is active; leave the driver intact.' }
  & sc.exe stop $driver.Name | Out-Null
  if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 1062) { throw 'Could not stop the test-owned driver.' }
  & sc.exe delete $driver.Name | Out-Null
  if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 1060) { throw 'Could not unregister the test-owned driver.' }
 }
}
Remove-OwnedDriver $PriorFixture
$before=@(Get-NetAdapter -IncludeHidden)
if (@($before | Where-Object Name -eq 'mcrf').Count -ne 0 -or @(Get-NetIPAddress -AddressFamily IPv4 | Where-Object IPAddress -eq '172.19.0.1').Count -ne 0) { throw 'Existing MCRF adapter detected; do not run against a user VPN.' }
$beforeIds=@($before | ForEach-Object { [string]$_.InterfaceGuid })
$resultPath=$fixturePath+'-result.json'
$ownedProcess=$null
$summary=[ordered]@{exitCode=$null;timedOut=$false;removedAdapters=@();cleanupErrors=@();existingAdaptersUnchanged=$false}
try {
 $ownedProcess=Start-Process -FilePath $testPath -ArgumentList ('"'+$fixturePath+'" --tun-zapret') -WindowStyle Hidden -PassThru -RedirectStandardOutput ($fixturePath+'-stdout.txt') -RedirectStandardError ($fixturePath+'-stderr.txt')
 $null=$ownedProcess.Handle
 if (!$ownedProcess.WaitForExit(90000)) { $summary.timedOut=$true; $ownedProcess.Kill(); $ownedProcess.WaitForExit(5000) | Out-Null }
 $ownedProcess.Refresh()
 $summary.exitCode=$ownedProcess.ExitCode
} finally {
 if ($ownedProcess -and !$ownedProcess.HasExited) { $ownedProcess.Kill(); $ownedProcess.WaitForExit(5000) | Out-Null }
 # Remove only our newly created exact adapter. Preserve other VPN devices.
 foreach ($adapter in @(Get-NetAdapter -IncludeHidden | Where-Object { $_.Name -eq 'mcrf' -and $_.InterfaceDescription -like '*Wintun*' -and [string]$_.InterfaceGuid -notin $beforeIds })) {
  try {
   $instance=[string]$adapter.PnPDeviceID
   if (!$instance -or $instance -notlike 'SWD\WINTUN\*') { throw 'Unexpected adapter device identity; automatic removal refused.' }
   $device=Get-PnpDevice -InstanceId $instance
   if ($device.Class -ne 'Net') { throw 'Unexpected device class.' }
   & pnputil.exe /remove-device $instance | Out-Null
   if ($LASTEXITCODE -ne 0) { throw 'Windows could not remove the test adapter.' }
   $summary.removedAdapters+=([string]$adapter.InterfaceGuid)
  } catch { $summary.cleanupErrors+=$_.Exception.Message }
 }
 try { Remove-OwnedDriver $fixturePath } catch { $summary.cleanupErrors+=$_.Exception.Message }
 $after=@(Get-NetAdapter -IncludeHidden)
 $summary.existingAdaptersUnchanged=@($before | Where-Object { $old=$_; !@($after | Where-Object { $_.InterfaceGuid -eq $old.InterfaceGuid -and $_.Status -eq $old.Status }).Count }).Count -eq 0
 if (@($after | Where-Object { $_.Name -eq 'mcrf' -and [string]$_.InterfaceGuid -notin $beforeIds }).Count) { $summary.cleanupErrors+='Test adapter still exists.' }
 $summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $resultPath -Encoding UTF8
}
if ($summary.exitCode -ne 0 -or $summary.timedOut -or $summary.cleanupErrors.Count -or !$summary.existingAdaptersUnchanged) { exit 1 }

