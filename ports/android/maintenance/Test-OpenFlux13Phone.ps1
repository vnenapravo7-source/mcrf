param([Parameter(Mandatory=$true)][string]$Stage,[string]$PreviousValidator='',[switch]$Production)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath($Stage)
if(!$taskRoot.StartsWith('D:\MCRF\Builds\Android\openflux13-validation-',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected validation stage'}
$taskReceipt=Get-Content "$taskRoot/receipt.json" -Raw|ConvertFrom-Json
if($taskReceipt.state -cne 'BUILT_NOT_INSTALLED' -or !$taskReceipt.sourceStable){throw 'Fresh complete isolated validator required'}
if((Get-FileHash -LiteralPath "$taskRoot/validation.apk").Hash.ToLowerInvariant() -cne $taskReceipt.apkSha256){throw 'Validation APK identity differs'}
if(Test-Path "$taskRoot/phone"){throw 'Phone attempt exists: inspect, do not blindly repeat'}
$null=New-Item -ItemType Directory "$taskRoot/phone"
$taskAdb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
function Adb([string[]]$Arguments){$taskOut=(& $taskAdb -s R5CX73QYN3D @Arguments 2>&1|Out-String).Trim();if($LASTEXITCODE){throw "ADB failed at $($Arguments[0]): $taskOut"};$taskOut}
function Save([string]$Name,[string]$Text){[IO.File]::WriteAllText("$taskRoot/phone/$Name",$Text,[Text.UTF8Encoding]::new($false))}
function Idle {
 if((Adb @('shell','am','get-current-user')) -cne '0'){throw 'User differs'}
 if((Adb @('shell','dumpsys','vpn_management')) -match 'Active vpn type: [01]\b'){throw 'VPN active: no interruption'}
 if((Adb @('shell','dumpsys','activity','services','org.mcrf.client')) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'MCRF service active: no interruption'}
}
function Guard([string]$Name){
 $taskText=Adb @('shell','am','instrument','--user','0','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation');Save $Name $taskText
 if($taskText -notmatch 'passed=true' -or $taskText -notmatch 'stable=true' -or $taskText -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Read-only preservation guard failed'}
 $taskRows=@{};foreach($taskMatch in [regex]::Matches($taskText,'(?m)(?:^|protectedHashes=)((?:files|shared_prefs)/[^\r\n=]+)=([a-f0-9]{64}|absent)\s*$')){$taskRows[$taskMatch.Groups[1].Value]=$taskMatch.Groups[2].Value}
 if($taskRows.Count -lt 15){throw 'Incomplete data inventory'};$taskRows
}
$taskResult=[ordered]@{state='PREFLIGHT';apkSha256=$taskReceipt.apkSha256;userDataPreserved=$false;mainAppUpdated=$false;productionEngineChanged=$false;usesSavedProfiles=$false;usesSystemVpn=$false}
try{
 Idle
 $taskGuardPath=Adb @('shell','pm','path','--user','0','org.mcrf.client.guard')
 if($taskGuardPath -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Guardian path differs'}
 if((Adb @('shell','sha256sum',$Matches[1])).Split()[0] -cne '8c32c7556f464aab8e8b261f109000c75615c21ca61b499b53d609fce4019b08'){throw 'Guardian identity differs'}
 $taskInstalled=Adb @('shell','pm','list','packages','--user','0','org.mcrf.openflux.validation')
 if($taskInstalled){
  if(!$PreviousValidator){throw 'Validator already installed: inspect ownership first'}
  $taskPrevious=[IO.Path]::GetFullPath($PreviousValidator)
  if(!$taskPrevious.StartsWith('D:\MCRF\Builds\Android\openflux13-validation-',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected previous validator root'}
  $taskOldReceipt=Get-Content "$taskPrevious/receipt.json" -Raw|ConvertFrom-Json
  $taskOldPath=Adb @('shell','pm','path','--user','0','org.mcrf.openflux.validation')
  if($taskOldPath -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Validator path differs'}
  if((Adb @('shell','sha256sum',$Matches[1])).Split()[0] -cne $taskOldReceipt.apkSha256){throw 'Installed validator not exact task-owned APK'}
 }
 $taskBefore=Guard 'protected-before.txt'
 $taskModulesBefore=Adb @('shell','pm','list','packages','--user','0','--show-versioncode','org.mcrf.engines');Save 'modules-before.txt' $taskModulesBefore
 Idle
 $taskArgs=@('install','--user','0','--no-streaming');if($taskInstalled){$taskArgs+='-r'};$taskArgs+="$taskRoot/validation.apk"
 $taskInstall=Adb $taskArgs;Save 'install.txt' $taskInstall
 if($taskInstall -notmatch '(?m)^Success\s*$'){throw 'Validator install unconfirmed; no retry or bypass'}
 $taskResult.state='VALIDATOR_INSTALLED'
 Save 'receipt.json' ($taskResult|ConvertTo-Json)
 Idle
 $taskRun=@('shell','am','instrument','--user','0','-w','-r');if($Production){$taskRun+=@('-e','production','true')};$taskRun+='org.mcrf.openflux.validation/org.mcrf.openflux.validation.Validation'
 $taskOutput=Adb $taskRun;Save 'validation.txt' $taskOutput
 $taskAfter=Guard 'protected-after.txt'
 if($taskBefore.Count -ne $taskAfter.Count){throw 'Protected inventory changed'}
 foreach($taskKey in $taskBefore.Keys){if($taskAfter[$taskKey] -cne $taskBefore[$taskKey]){throw 'Protected file changed: '+$taskKey}}
 $taskResult.userDataPreserved=$true;$taskResult.protectedEntries=$taskBefore.Count
 if((Adb @('shell','pm','list','packages','--user','0','--show-versioncode','org.mcrf.engines')) -cne $taskModulesBefore){throw 'Production module inventory changed'}
 if($taskOutput -notmatch 'passed=true' -or $taskOutput -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Fresh OpenFlux runtime checks FAILED; inspect validation.txt'}
 $taskResult.state='PASS_FRESH_OPENFLUX_LOCAL_ANDROID_RUNTIME'
 $taskResult.installedProductionModuleTested=[bool]$Production
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{Save 'receipt.json' ($taskResult|ConvertTo-Json -Depth 4);Write-Output "OPENFLUX_PHONE=$($taskResult.state);RECEIPT=$taskRoot/phone/receipt.json"}
