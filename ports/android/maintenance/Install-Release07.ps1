param([Parameter(Mandatory=$true)][string]$Stage)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath($Stage)
if(!$taskRoot.StartsWith('D:\MCRF\Builds\Android\release07-',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected release root'}
$taskPlan=Get-Content "$taskRoot/assets-plan.json" -Raw|ConvertFrom-Json
if($taskPlan.state -cne 'APK_ASSETS_VERIFIED_NOT_PUBLISHED'){throw 'Verified APK plan required'}
if(Test-Path "$taskRoot/phone"){throw 'Existing phone attempt; inspect exact outcome before resuming'}
$null=New-Item -ItemType Directory "$taskRoot/phone"
$taskAdb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
function Adb([string[]]$Arguments){$taskText=(& $taskAdb -s R5CX73QYN3D @Arguments 2>&1|Out-String).Trim();if($LASTEXITCODE){throw "ADB failed at $($Arguments[0]): $taskText"};$taskText}
function Save([string]$Name,[string]$Text){[IO.File]::WriteAllText("$taskRoot/phone/$Name",$Text,[Text.UTF8Encoding]::new($false))}
function Idle{
 if((Adb @('shell','am','get-current-user')) -cne '0'){throw 'User differs'}
 if((Adb @('shell','dumpsys','vpn_management')) -match 'Active vpn type: [01]\b'){throw 'Active VPN: no interruption'}
 if((Adb @('shell','dumpsys','activity','services','org.mcrf.client')) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'MCRF service active: no interruption'}
}
function Guard([string]$Name){
 $taskOut=Adb @('shell','am','instrument','--user','0','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation');Save $Name $taskOut
 if($taskOut -notmatch 'passed=true' -or $taskOut -notmatch 'stable=true' -or $taskOut -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Preservation guard failed'}
 $taskRows=@{};foreach($taskMatch in [regex]::Matches($taskOut,'(?m)(?:^|protectedHashes=)((?:files|shared_prefs)/[^\r\n=]+)=([a-f0-9]{64}|absent)\s*$')){$taskRows[$taskMatch.Groups[1].Value]=$taskMatch.Groups[2].Value}
 if($taskRows.Count -ne 34){throw 'Unexpected protected inventory'};$taskRows
}
$taskResult=[ordered]@{state='PREFLIGHT';installed=@();protectedDataIdentical=$false;userSettingsAndConnectionsPreserved=$false;vpnInterrupted=$false;clearedData=$false;uninstalled=$false;published=$false}
try{
 Idle
 $taskGuardian=Adb @('shell','pm','path','--user','0','org.mcrf.client.guard')
 if($taskGuardian -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Guardian path differs'}
 if((Adb @('shell','sha256sum',$Matches[1])).Split()[0] -cne '8c32c7556f464aab8e8b261f109000c75615c21ca61b499b53d609fce4019b08'){throw 'Guardian identity differs'}
 if((Adb @('shell','dumpsys','package','org.mcrf.client')) -notmatch 'versionCode=10058\b'){throw 'Main must be exact10058 before upgrade'}
 $taskBefore=Guard 'protected-before.txt'
 $taskInventory=Adb @('shell','pm','list','packages','--user','0','--show-versioncode','org.mcrf.engines');Save 'modules-before.txt' $taskInventory
 $taskSelected=@($taskPlan.files|Where-Object {$_.name -in @('mcrf-engine-openflux-arm64-v8a-release.apk','mcrf-engine-dnstt-arm64-v8a-release.apk','mcrf-android-universal-release.apk')})
 if($taskSelected.Count -ne 3){throw 'Exact upgrade trio required'}
 foreach($taskFile in ($taskSelected|Sort-Object @{e={if($_.kind -eq 'app'){1}else{0}}})){ 
  Idle
  $taskApk="$taskRoot/assets/$($taskFile.name)"
  if((Get-FileHash -LiteralPath $taskApk).Hash.ToLowerInvariant() -cne $taskFile.sha256){throw 'Release APK changed'}
  $taskExisting=Adb @('shell','dumpsys','package',$taskFile.package)
  if($taskExisting -notmatch 'versionCode=([0-9]+)\b'){throw 'Installed package version missing'}
  $taskOldVersion=[int]$Matches[1]
  $taskExpected=if($taskFile.kind -eq 'app'){10058}elseif($taskFile.kind -eq 'openflux'){7}else{4}
  if($taskOldVersion -ne $taskExpected -or $taskFile.versionCode -le $taskOldVersion){throw 'Exact forward upgrade pair required'}
  $taskPath=Adb @('shell','pm','path','--user','0',$taskFile.package)
  if($taskPath -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Installed package path differs'}
  $taskOldPath=$Matches[1];$taskOldHash=(Adb @('shell','sha256sum',$taskOldPath)).Split()[0]
  $null=Adb @('pull',$taskOldPath,"$taskRoot/phone/$($taskFile.kind)-before.apk")
  $taskSignature=(& 'C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0/apksigner.bat' verify --print-certs "$taskRoot/phone/$($taskFile.kind)-before.apk" 2>&1|Out-String)
  if($LASTEXITCODE -or !$taskSignature.Contains('certificate SHA-256 digest: '+$taskPlan.signer)){throw 'Installed signer differs: NEVER uninstall to work around'}
  $taskOut=Adb @('install','--user','0','--no-streaming','-r',$taskApk);Save "$($taskFile.kind)-install.txt" $taskOut
  if($taskOut -notmatch '(?m)^Success\s*$'){throw 'Update not confirmed; no consent bypass/retry'}
  $taskNewPath=Adb @('shell','pm','path','--user','0',$taskFile.package)
  if($taskNewPath -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Updated path differs'}
  if((Adb @('shell','sha256sum',$Matches[1])).Split()[0] -cne $taskFile.sha256){throw 'Installed APK digest differs'}
  $taskResult.installed+=@{package=$taskFile.package;from=$taskOldVersion;to=$taskFile.versionCode;sha256=$taskFile.sha256;previousSha256=$taskOldHash}
  Save 'receipt.json' ($taskResult|ConvertTo-Json -Depth 5)
 }
 $taskAfter=Guard 'protected-after.txt'
 $taskDifferences=@($taskBefore.Keys|Where-Object {$taskBefore[$_] -cne $taskAfter[$_]})
 # Only updater operational cache may naturally change on PACKAGE_REPLACED;
 # automatic-update preference is in MainActivity.xml and must remain exact.
 if(@($taskDifferences|Where-Object {$_ -cne 'shared_prefs/engine-updates.xml'}).Count){throw 'Protected settings/connections changed; stop and inspect, do not overwrite'}
 $taskResult.protectedDataIdentical=$taskDifferences.Count -eq 0;$taskResult.operationalCacheChanges=$taskDifferences
 $taskResult.userSettingsAndConnectionsPreserved=$true;$taskResult.protectedEntries=34;$taskResult.state='UPDATED_10059_OPENFLUX14_DNSTT5_PRESERVED'
 Save 'modules-after.txt' (Adb @('shell','pm','list','packages','--user','0','--show-versioncode','org.mcrf.engines'))
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{Save 'receipt.json' ($taskResult|ConvertTo-Json -Depth 6);Write-Output "RELEASE07_PHONE=$($taskResult.state);RECEIPT=$taskRoot/phone/receipt.json"}
