param()
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation test
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskAvdRoot='D:/MCRF/Builds/Android/prerelease-clean-avd'
$taskLease=Join-Path $taskRoot 'ports/android/coordination/emulator-lease.json'
$taskPrior=Get-Content -LiteralPath $taskLease -Raw|ConvertFrom-Json
if($taskPrior.state -notin @('idle_owned_overlay_closed','idle','closed_p0_common_readonly_no_snapshot') -or $taskPrior.owner_thread -cne '01a13a9b-6754-7b13-af52-422a6eb5579c'){throw 'An existing emulator owner/job must finish first'}
if(@(Get-CimInstance Win32_Process|Where-Object {$_.Name -match '^(emulator|qemu-system.*)\.exe$'}).Count){throw 'Existing emulator process; never launch a second instance'}
if(Get-NetTCPConnection -LocalPort 5586 -State Listen -ErrorAction SilentlyContinue){throw 'Emulator port owned by another process'}
if((Get-Content -LiteralPath "$taskAvdRoot/mcrf-prerelease-clean.ini" -Raw) -notmatch 'path=D:\\MCRF\\Builds\\Android\\prerelease-clean-avd\\mcrf-prerelease-clean.avd'){throw 'Owned AVD definition changed'}
$taskEvidence=Join-Path 'D:/MCRF/Builds/Android' ('p0-common-emulator-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskEvidence|Out-Null
[IO.File]::Copy($taskLease,(Join-Path $taskEvidence 'lease-before.json'),$false)
$taskPrevious=$env:ANDROID_AVD_HOME
try{
    $env:ANDROID_AVD_HOME=$taskAvdRoot
    $taskProcess=Start-Process -FilePath 'C:/Users/dieonfire/AppData/Local/Android/Sdk/emulator/emulator.exe' -ArgumentList @('-avd','mcrf-prerelease-clean','-port','5586','-read-only','-no-snapshot-load','-no-snapshot-save','-no-window','-no-audio','-no-boot-anim') -WindowStyle Hidden -PassThru -RedirectStandardOutput "$taskEvidence/stdout.log" -RedirectStandardError "$taskEvidence/stderr.log"
    $taskLaunch=[ordered]@{pid=$taskProcess.Id;device='emulator-5586';avd='mcrf-prerelease-clean';readOnly=$true;snapshotLoadSave=$false;timeUtc=[DateTime]::UtcNow.ToString('o');authority='Whole P0 common verification after source readiness, not reuse of historical one-off APK consent';notificationConsent='PENDING fresh human answer; no grant, appops, permission bypass or profile reset';physicalPhoneTouched=$false}
    [IO.File]::WriteAllText("$taskEvidence/launch.json",($taskLaunch|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    $taskRecord=[ordered]@{device='emulator-5586';state='booting_p0_common_readonly';owner_thread='01a13a9b-6754-7b13-af52-422a6eb5579c';scope='Whole ready P0 common batch only; preserve baseline/user data, no old closed suite repetition, no physical device actions';launchReceipt="$taskEvidence/launch.json";pid=$taskProcess.Id;notificationConsent='PENDING; no grant before fresh answer'}
    [IO.File]::WriteAllText($taskLease,($taskRecord|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    Write-Output "P0_EMULATOR_PID=$($taskProcess.Id);EVIDENCE=$taskEvidence"
}finally{$env:ANDROID_AVD_HOME=$taskPrevious}
