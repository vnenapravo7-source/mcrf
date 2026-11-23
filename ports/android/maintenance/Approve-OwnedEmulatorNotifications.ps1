# Explicit human YES: only this disposable emulator, through ordinary Android UI.
$ErrorActionPreference='Stop'
$taskAdb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$taskDevice='emulator-5586'
$taskEvidence='D:/MCRF/Builds/Android/direct-tun-emulator-d2759e63b4734a288a1ca58b40ef8ddd'
if(Test-Path -LiteralPath "$taskEvidence/notification-consent.json"){throw 'One-time consent already recorded; never toggle again'}
if(@(Get-CimInstance Win32_Process|Where-Object {$_.Name -eq 'emulator.exe' -and $_.ProcessId -eq 31120 -and $_.CommandLine -match '-read-only' -and $_.CommandLine -match '-port 5586'}).Count -ne 1){throw 'Original owned read-only emulator missing'}
& $taskAdb -s $taskDevice shell uiautomator dump /data/local/tmp/mcrf-notification-consent.xml | Out-Null
if($LASTEXITCODE -ne 0){throw 'Cannot observe consent UI'}
$taskText=(& $taskAdb -s $taskDevice shell cat /data/local/tmp/mcrf-notification-consent.xml|Out-String)
if($LASTEXITCODE -ne 0){throw 'Cannot read consent UI'}
$taskXml=[xml]$taskText
if(@($taskXml.SelectNodes('//node[@package="com.android.settings" and @text="All MCRF notifications"]')).Count -ne 1 -or @($taskXml.SelectNodes('//node[@resource-id="com.android.settings:id/entity_header_title" and @text="MCRF"]')).Count -ne 1){throw 'Not exact MCRF Android notification settings'}
$taskSwitch=@($taskXml.SelectNodes('//node[@resource-id="android:id/switch_widget" and @enabled="true" and @bounds="[839,1006][985,1132]"]'))
if($taskSwitch.Count -ne 1 -or $taskSwitch[0].bounds -ne '[839,1006][985,1132]'){throw 'Consent control changed; no blind tap'}
if($taskSwitch[0].checked -eq 'false'){
    & $taskAdb -s $taskDevice shell input tap 912 1069
    if($LASTEXITCODE -ne 0){throw 'Consent tap failed'}
}elseif($taskSwitch[0].checked -ne 'true'){throw 'Unknown consent state'}
& $taskAdb -s $taskDevice shell uiautomator dump /data/local/tmp/mcrf-notification-consent.xml | Out-Null
$taskAfter=(& $taskAdb -s $taskDevice shell cat /data/local/tmp/mcrf-notification-consent.xml|Out-String)
if($LASTEXITCODE -ne 0 -or ([xml]$taskAfter).SelectNodes('//node[@resource-id="android:id/switch_widget" and @checked="true" and @bounds="[839,1006][985,1132]"]').Count -ne 1){throw 'Ordinary UI consent not confirmed'}
$taskPermission=(& $taskAdb -s $taskDevice shell dumpsys package org.mcrf.client|Out-String)
if($LASTEXITCODE -ne 0 -or $taskPermission -notmatch 'android.permission.POST_NOTIFICATIONS: granted=true'){throw 'OS notification permission not confirmed'}
$taskPermission='granted'
[IO.File]::WriteAllText("$taskEvidence/notification-consent.json",(@{device=$taskDevice;passed=$true;permission=$taskPermission;method='Human explicitly approved; ordinary Android Settings MCRF notifications switch, false->true from fresh observed UI; no pm grant/appops mutation';timeUtc=[DateTime]::UtcNow.ToString('o')}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
Write-Output 'Owned emulator notification consent confirmed through ordinary Android UI'
