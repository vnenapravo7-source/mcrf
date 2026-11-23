param([ValidateSet(10057,10058)][int]$Version=10057,[ValidateSet(10051,10057)][int]$PreviousVersion=10051)
$ErrorActionPreference='Stop'
$taskAdb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$taskSigner='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0/apksigner.bat'
$taskSerial='R5CX73QYN3D'
$taskRoot=if($Version -eq 10058){'D:/MCRF/Builds/Android/bs-sites-ui-20261026-001'}else{'D:/MCRF/Builds/Android/human-fixes-20261026-001'}
if(($Version -eq 10058) -ne ($PreviousVersion -eq 10057)){throw 'Only the exact human-authorized upgrade pairs are permitted'}
$taskEvidence="$taskRoot/phone-install"
if(Test-Path -LiteralPath $taskEvidence){throw 'Existing phone attempt; inspect evidence, do not repeat'}
$null=New-Item -ItemType Directory -Path $taskEvidence
function Adb([string[]]$Arguments){
 $taskText=(& $taskAdb -s $taskSerial @Arguments 2>&1|Out-String).Trim()
 if($LASTEXITCODE){throw "ADB failed: $($Arguments[0]); $taskText"};$taskText
}
function Save([string]$Name,[string]$Value){[IO.File]::WriteAllText("$taskEvidence/$Name",$Value,[Text.UTF8Encoding]::new($false))}
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function PackagePath([string]$Package){$taskPath=Adb @('shell','pm','path','--user','0',$Package);if($taskPath -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Unexpected package path'};$Matches[1]}
function Idle {
 if((Adb @('shell','am','get-current-user')) -cne '0'){throw 'Unexpected active Android user'}
 if((Adb @('shell','dumpsys','vpn_management')) -match 'Active vpn type: [01]\b'){throw 'Active VPN: do not interrupt'}
 if((Adb @('shell','dumpsys','activity','services','org.mcrf.client')) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'Active app service: do not interrupt'}
}
function Guard([string]$Name){
 $taskOut=Adb @('shell','am','instrument','--user','0','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation')
 Save $Name $taskOut
 if($taskOut -notmatch 'passed=true' -or $taskOut -notmatch 'stable=true' -or $taskOut -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Read-only guard failed'}
 $taskRows=[ordered]@{}
 foreach($taskMatch in [regex]::Matches($taskOut,'(?m)(?:^|protectedHashes=)((?:files|shared_prefs)/[^\r\n=]+)=([a-f0-9]{64}|absent)\s*$')){$taskRows[$taskMatch.Groups[1].Value]=$taskMatch.Groups[2].Value}
 if($taskRows.Count -lt 15 -or @($taskRows.Keys|Where-Object {$_ -like '*connections.enc' -and $taskRows[$_] -ne 'absent'}).Count -lt 1 -or @($taskRows.Keys|Where-Object {$_ -like 'shared_prefs/*' -and $taskRows[$_] -ne 'absent'}).Count -lt 1){throw 'Incomplete protected inventory'}
 $taskRows
}
$taskResult=[ordered]@{authority='human-20261026-update-phone-preserve-settings-connections';state='PREFLIGHT';installed=$false;protectedUnchanged=$false;published=$false;nativeModulesChanged=$false;dataCleared=$false;startedUtc=[DateTime]::UtcNow.ToString('o')}
try {
 $taskReceipt=Get-Content "$taskRoot/receipt.json" -Raw|ConvertFrom-Json
 $taskApk="$taskRoot/$($taskReceipt.apk)"
 if($taskReceipt.versionCode -ne $Version -or $taskReceipt.sha256 -cne (Hash $taskApk)){throw 'Exact requested APK hash required'}
 Idle
 $taskPackageBefore=Adb @('shell','dumpsys','package','org.mcrf.client')
 if($taskPackageBefore -notmatch ("versionCode=$PreviousVersion\b")){throw 'Installed version changed; inspect before update'}
 $taskGuardianPath=PackagePath 'org.mcrf.client.guard'
 $taskGuardianHash=(Adb @('shell','sha256sum',$taskGuardianPath)).Split()[0]
 if($taskGuardianHash -cne '8c32c7556f464aab8e8b261f109000c75615c21ca61b499b53d609fce4019b08'){throw 'Read-only guard identity mismatch'}
 $taskBeforePath=PackagePath 'org.mcrf.client'
 $null=Adb @('pull',$taskBeforePath,"$taskEvidence/installed-before.apk")
 $taskOldSignature=(& $taskSigner verify --print-certs "$taskEvidence/installed-before.apk" 2>&1|Out-String);if($LASTEXITCODE){throw 'Installed APK signature verification failed'}
 $taskNewSignature=(& $taskSigner verify --print-certs $taskApk 2>&1|Out-String);if($LASTEXITCODE){throw 'New APK signature verification failed'}
 $taskExpected='Signer #1 certificate SHA-256 digest: '+$taskReceipt.certificateSha256
 if(!$taskOldSignature.Contains($taskExpected) -or !$taskNewSignature.Contains($taskExpected)){throw 'Signer mismatch; NEVER uninstall to work around it'}
 Save 'signatures.txt' ($taskOldSignature+"`n"+$taskNewSignature)
 $taskModulesBefore=Adb @('shell','pm','list','packages','--user','0','--show-versioncode','org.mcrf.engines')
 $taskBefore=Guard 'protected-before.txt'
 $taskResult.state='INSTALLING';$taskResult.versionBefore=$PreviousVersion;$taskResult.versionAfter=$Version;$taskResult.apkSha256=$taskReceipt.sha256
 Save 'receipt.json' ($taskResult|ConvertTo-Json -Depth 6)
 Idle
 $taskInstall=Adb @('install','--user','0','--no-streaming','-r',$taskApk)
 Save 'install.txt' $taskInstall
 if($taskInstall -notmatch '(?m)^Success\s*$'){throw 'Install not confirmed; no retry or consent bypass'}
 $taskResult.installed=$true
 Save 'receipt.json' ($taskResult|ConvertTo-Json -Depth 6)
 $taskPackageAfter=Adb @('shell','dumpsys','package','org.mcrf.client')
 if($taskPackageAfter -notmatch ("versionCode=$Version\b") -or $taskPackageAfter -notmatch ('versionName='+[regex]::Escape($taskReceipt.versionName)+'\b')){throw 'Wrong installed version'}
 $taskInstalledPath=PackagePath 'org.mcrf.client'
 $taskInstalledHash=(Adb @('shell','sha256sum',$taskInstalledPath)).Split()[0]
 if($taskInstalledHash -cne $taskReceipt.sha256){throw 'Installed APK hash mismatch'}
 $taskAfter=Guard 'protected-after.txt'
 if(($taskBefore|ConvertTo-Json -Compress) -cne ($taskAfter|ConvertTo-Json -Compress)){throw 'Protected data differs; do not restore old data blindly'}
 if((Adb @('shell','pm','list','packages','--user','0','--show-versioncode','org.mcrf.engines')) -cne $taskModulesBefore){throw 'Unexpected engine inventory change'}
 $taskResult.protectedUnchanged=$true;$taskResult.protectedEntries=$taskBefore.Count
 $taskResult.presentProtectedFiles=@($taskBefore.Values|Where-Object {$_ -ne 'absent'}).Count
 $taskResult.installedSha256=$taskInstalledHash;$taskResult.state="UPDATED_${Version}_PROTECTED_DATA_IDENTICAL";$taskResult.completedUtc=[DateTime]::UtcNow.ToString('o')
 Save 'receipt.json' ($taskResult|ConvertTo-Json -Depth 6)
 Write-Output ($taskResult|ConvertTo-Json -Compress)
}catch {
 $taskResult.state='STOPPED_WITH_ERROR';$taskResult.error=$_.Exception.Message
 Save 'receipt.json' ($taskResult|ConvertTo-Json -Depth 6)
 throw
}
