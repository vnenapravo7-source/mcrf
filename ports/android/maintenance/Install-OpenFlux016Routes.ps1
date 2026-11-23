param([Parameter(Mandatory=$true)][string]$Stage,[Parameter(Mandatory=$true)][string]$ValidationStage)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath($Stage)
if(!$taskRoot.StartsWith('D:\MCRF\Builds\Android\openflux016-routes-',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected016 stage root'}
$taskProofPath=if(Test-Path "$taskRoot/verification-receipt.json"){"$taskRoot/verification-receipt.json"}else{"$taskRoot/receipt.json"}
$taskProof=Get-Content $taskProofPath -Raw|ConvertFrom-Json
if($taskProof.state -cne 'BUILT_VERIFIED_NOT_INSTALLED' -or !$taskProof.sourceStable){throw 'Common build verification required'}
$taskRuntime=Get-Content "$ValidationStage/phone/receipt.json" -Raw|ConvertFrom-Json
if($taskRuntime.state -cne 'PASS_ISOLATED_016_DIRECT_FLUXFAST_13_INTEROP' -or !$taskRuntime.passed){throw 'Actual isolated016 phone runtime required'}
$taskValidator=Get-Content "$ValidationStage/receipt.json" -Raw|ConvertFrom-Json
$taskNative=Get-Content "$($taskProof.nativeStage)/receipt.json" -Raw|ConvertFrom-Json
if($taskValidator.nativeSha256 -cne ($taskNative.files|Where-Object abi -eq 'arm64-v8a').sha256){throw 'Phone tested different native payload'}
if(Test-Path "$taskRoot/phone"){throw 'Phone attempt already exists; inspect before any retry'}
$null=New-Item -ItemType Directory "$taskRoot/phone"
$taskAdb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe';$taskTools='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
function Adb([string[]]$Arguments){$taskValue=(& $taskAdb -s R5CX73QYN3D @Arguments 2>&1|Out-String).Trim();if($LASTEXITCODE){throw "ADB failed: $($Arguments[0])"};$taskValue}
function Save([string]$Name,[string]$Value){[IO.File]::WriteAllText("$taskRoot/phone/$Name",$Value,[Text.UTF8Encoding]::new($false))}
function Idle{
 if((Adb @('shell','am','get-current-user')) -cne '0'){throw 'Android user differs'}
 if((Adb @('shell','dumpsys','vpn_management')) -match 'Active vpn type: [01]\b'){throw 'Active VPN: installation deferred, no interruption'}
 if((Adb @('shell','dumpsys','activity','services','org.mcrf.client')) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'MCRF active: installation deferred'}
}
function Guard([string]$Name){
 Idle;$taskOut=Adb @('shell','am','instrument','--user','0','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation');Save $Name $taskOut
 if($taskOut -notmatch 'passed=true' -or $taskOut -notmatch 'stable=true' -or $taskOut -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Read-only preservation guard failed'}
 $taskRows=@{};foreach($taskMatch in [regex]::Matches($taskOut,'(?m)(?:^|protectedHashes=)((?:files|shared_prefs)/[^\r\n=]+)=([a-f0-9]{64}|absent)\s*$')){$taskRows[$taskMatch.Groups[1].Value]=$taskMatch.Groups[2].Value}
 if($taskRows.Count -ne 34){throw 'Protected inventory differs'};return $taskRows
}
$taskResult=[ordered]@{state='PREFLIGHT';installed=@();userSettingsAndConnectionsPreserved=$false;vpnInterrupted=$false;clearedData=$false;uninstalled=$false;published=$false}
try{
 Idle
 $taskGuardian=Adb @('shell','pm','path','--user','0','org.mcrf.client.guard')
 if($taskGuardian -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Guardian missing'}
 if((Adb @('shell','sha256sum',$Matches[1])).Split()[0] -cne '8c32c7556f464aab8e8b261f109000c75615c21ca61b499b53d609fce4019b08'){throw 'Guardian differs'}
 $taskBefore=Guard 'protected-before.txt'
 $taskFiles=@($taskProof.files|Where-Object kind -eq 'app')+@($taskProof.files|Where-Object {$_.kind -eq 'openflux' -and $_.name -match 'arm64-v8a'})
 if($taskFiles.Count -ne 2){throw 'Exact main+arm64 module pair required'}
 foreach($taskFile in $taskFiles){
  Idle;$taskApp=$taskFile.kind -eq 'app';$taskPackage=if($taskApp){'org.mcrf.client'}else{'org.mcrf.engines.openflux'};$taskOldVersion=if($taskApp){10059}else{14};$taskNewVersion=if($taskApp){10060}else{15}
  if((Adb @('shell','dumpsys','package',$taskPackage)) -notmatch "versionCode=$taskOldVersion\b"){throw 'Installed version differs: no downgrade/clear/uninstall'}
  if((Get-FileHash -LiteralPath $taskFile.path).Hash.ToLowerInvariant() -cne $taskFile.sha256){throw 'Candidate APK changed'}
  $taskOldPath=Adb @('shell','pm','path','--user','0',$taskPackage)
  if($taskOldPath -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Installed APK path differs'}
  $null=Adb @('pull',$Matches[1],"$taskRoot/phone/$($taskFile.kind)-before.apk")
  foreach($taskApk in @("$taskRoot/phone/$($taskFile.kind)-before.apk",$taskFile.path)){
   $taskSignature=(& "$taskTools/apksigner.bat" verify --print-certs $taskApk 2>&1|Out-String)
   if($LASTEXITCODE -or $taskSignature -notmatch 'certificate SHA-256 digest:\s*fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'APK signer differs: no uninstall workaround'}
  }
  Idle;$taskOut=Adb @('install','--user','0','--no-streaming','-r',$taskFile.path);Save "$($taskFile.kind)-install.txt" $taskOut
  if($taskOut -notmatch '(?m)^Success\s*$'){throw 'Android install not confirmed; no bypass'}
  $taskNewPath=Adb @('shell','pm','path','--user','0',$taskPackage)
  if($taskNewPath -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'New APK path differs'}
  if((Adb @('shell','sha256sum',$Matches[1])).Split()[0] -cne $taskFile.sha256){throw 'Installed bytes differ'}
  if((Adb @('shell','dumpsys','package',$taskPackage)) -notmatch "versionCode=$taskNewVersion\b"){throw 'Installed version differs'}
  $taskResult.installed+=@{package=$taskPackage;from=$taskOldVersion;to=$taskNewVersion;sha256=$taskFile.sha256};Save 'receipt.json' ($taskResult|ConvertTo-Json -Depth 6)
 }
 $taskAfter=Guard 'protected-after.txt';$taskDifferences=@($taskBefore.Keys|Where-Object {$taskBefore[$_] -cne $taskAfter[$_]})
 if(@($taskDifferences|Where-Object {$_ -cne 'shared_prefs/engine-updates.xml'}).Count){throw 'Protected data changed: preserved receipts, no overwrite'}
 $taskResult.userSettingsAndConnectionsPreserved=$true;$taskResult.protectedDataIdentical=$taskDifferences.Count -eq 0;$taskResult.operationalCacheChanges=$taskDifferences;$taskResult.protectedEntries=34;$taskResult.state='UPDATED_10060_OPENFLUX15_PRESERVED'
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{Save 'receipt.json' ($taskResult|ConvertTo-Json -Depth 7);Write-Output "OPENFLUX016_PHONE=$($taskResult.state);STAGE=$taskRoot/phone"}
