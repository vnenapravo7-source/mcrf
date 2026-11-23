$ErrorActionPreference='Stop'
$taskRoot='D:/MCRF/Projects/MCRF'
$taskAvdRoot='D:/MCRF/Builds/Android/prerelease-clean-avd'
$taskEmulator='C:/Users/dieonfire/AppData/Local/Android/Sdk/emulator/emulator.exe'
if(@(Get-CimInstance Win32_Process|Where-Object {$_.Name -match '^(emulator|qemu-system.*)\.exe$'}).Count){throw 'Existing emulator process; inspect it instead of starting another'}
if((Get-Content -LiteralPath "$taskAvdRoot/mcrf-prerelease-clean.ini" -Raw) -notmatch 'path=D:\\MCRF\\Builds\\Android\\prerelease-clean-avd\\mcrf-prerelease-clean.avd'){throw 'Owned AVD path changed'}
$taskEvidence=Join-Path 'D:/MCRF/Builds/Android' ('direct-tun-emulator-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskEvidence|Out-Null
$taskPrevious=$env:ANDROID_AVD_HOME
try{
    $env:ANDROID_AVD_HOME=$taskAvdRoot
    $taskProcess=Start-Process -FilePath $taskEmulator -ArgumentList @('-avd','mcrf-prerelease-clean','-port','5586','-read-only','-no-snapshot-load','-no-snapshot-save','-no-window','-no-audio','-no-boot-anim') -WindowStyle Hidden -PassThru -RedirectStandardOutput "$taskEvidence/stdout.log" -RedirectStandardError "$taskEvidence/stderr.log"
    [IO.File]::WriteAllText("$taskEvidence/launch.json",(@{pid=$taskProcess.Id;device='emulator-5586';avd='mcrf-prerelease-clean';readOnly=$true;snapshotLoadSave=$false;timeUtc=[DateTime]::UtcNow.ToString('o');authority='ONE explicit human emulator-only test APK; no physical device actions, no consent bypass'}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    Write-Output "EMULATOR_PID=$($taskProcess.Id) EVIDENCE=$taskEvidence"
}finally{$env:ANDROID_AVD_HOME=$taskPrevious}
