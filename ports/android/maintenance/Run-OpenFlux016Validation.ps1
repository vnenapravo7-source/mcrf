param([Parameter(Mandatory=$true)][string]$Stage,[switch]$Production,[string]$PreviousValidatorStage='')
$ErrorActionPreference='Stop';$taskRoot=[IO.Path]::GetFullPath($Stage)
if(!$taskRoot.StartsWith('D:\MCRF\Builds\Android\openflux016-validation-',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected owned validator stage'}
$taskProof=Get-Content "$taskRoot/receipt.json" -Raw|ConvertFrom-Json
if($taskProof.state -cne 'BUILT_NOT_INSTALLED' -or !$taskProof.sourceStable -or (Get-FileHash "$taskRoot/validation.apk").Hash.ToLowerInvariant() -cne $taskProof.apkSha256){throw 'Verified validator APK required'}
$taskTools='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0';$taskAdb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe';$taskPackage='org.mcrf.openflux.team016.validation'
function Adb([string[]]$Arguments){$taskValue=(& $taskAdb -s R5CX73QYN3D @Arguments 2>&1|Out-String).Trim();if($LASTEXITCODE){throw "ADB failed: $($Arguments[0])"};$taskValue}
function Idle{if((Adb @('shell','am','get-current-user')) -cne '0' -or (Adb @('shell','dumpsys','vpn_management')) -match 'Active vpn type: [01]\b' -or (Adb @('shell','dumpsys','activity','services','org.mcrf.client')) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'Phone not idle: no VPN/session interruption'}}
$taskFolder=if($Production){'production-phone'}else{'phone'}
if(Test-Path "$taskRoot/$taskFolder"){throw 'Validation attempt exists; inspect exact outcome before retry'}
$null=New-Item -ItemType Directory "$taskRoot/$taskFolder"
function Save([string]$Name,[string]$Value){[IO.File]::WriteAllText("$taskRoot/$taskFolder/$Name",$Value,[Text.UTF8Encoding]::new($false))}
$taskResult=[ordered]@{state='PREFLIGHT';passed=$false;production=[bool]$Production;cases=@();vpnInterrupted=$false;mainStorageTouched=$false;nativeSha256=$taskProof.nativeSha256}
try{
 Idle
 $taskSignature=(& "$taskTools/apksigner.bat" verify --print-certs "$taskRoot/validation.apk" 2>&1|Out-String)
 if($LASTEXITCODE -or $taskSignature -notmatch 'certificate SHA-256 digest:\s*fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Validator signer differs'}
 $taskExisting=if((Adb @('shell','pm','list','packages','--user','0',$taskPackage)) -ceq "package:$taskPackage"){Adb @('shell','pm','path','--user','0',$taskPackage)}else{''}
 if($taskExisting){
  if($taskExisting -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Unknown existing validator path'}
  $taskInstalledHash=(Adb @('shell','sha256sum',$Matches[1])).Split()[0]
  if($taskInstalledHash -cne $taskProof.apkSha256){
   if(!$PreviousValidatorStage){throw 'Unknown existing validator: no overwrite/uninstall'}
   $taskPreviousRoot=[IO.Path]::GetFullPath($PreviousValidatorStage)
   if(!$taskPreviousRoot.StartsWith('D:\MCRF\Builds\Android\openflux016-validation-',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected previous owned validator stage'}
   $taskPrevious=Get-Content "$taskPreviousRoot/receipt.json" -Raw|ConvertFrom-Json
   if(!$taskPrevious.sourceStable -or $taskInstalledHash -cne $taskPrevious.apkSha256 -or (Get-FileHash "$taskPreviousRoot/validation.apk").Hash.ToLowerInvariant() -cne $taskInstalledHash){throw 'Previous owned validator differs'}
   Idle;$taskInstall=Adb @('install','--user','0','--no-streaming','-r',"$taskRoot/validation.apk");Save 'update-owned-validator.txt' $taskInstall;if($taskInstall -notmatch '(?m)^Success\s*$'){throw 'Owned validator update not confirmed'}
  }
 }
 else{$taskInstall=Adb @('install','--user','0','--no-streaming',"$taskRoot/validation.apk");Save 'install.txt' $taskInstall;if($taskInstall -notmatch '(?m)^Success\s*$'){throw 'Validator install not confirmed; no bypass'}}
 $taskScopes=if($Production){@(@('direct','same'),@('fluxfast','same'))}else{@(@('direct','same'),@('fluxfast','same'),@('direct','16to13'),@('direct','13to16'))}
 foreach($taskScope in $taskScopes){
  Idle;$taskLabel=$taskScope[0]+'-'+$taskScope[1]
  $taskOut=Adb @('shell','am','instrument','--user','0','-w','-r','-e','transport',$taskScope[0],'-e','pair',$taskScope[1],'-e','production',([string][bool]$Production).ToLowerInvariant(),"$taskPackage/$taskPackage.Validation")
  Save "$taskLabel.txt" $taskOut
  if($taskOut -notmatch 'passed=true' -or $taskOut -notmatch 'INSTRUMENTATION_CODE: -1' -or $taskOut -match 'CLEANUP required forced termination' -or $taskOut -notmatch 'exact8192byte HTTP response' -or $taskOut -notmatch 'wrong encryption key never becomes ready'){throw "Owned runtime failed: $taskLabel; inspect saved log"}
  $taskResult.cases+=@{transport=$taskScope[0];pair=$taskScope[1];passed=$true;sha256=(Get-FileHash "$taskRoot/$taskFolder/$taskLabel.txt").Hash.ToLowerInvariant()};Save 'receipt.json' ($taskResult|ConvertTo-Json -Depth 6)
 }
 Idle;$taskResult.passed=$true;$taskResult.state=if($Production){'PASS_INSTALLED_016_DIRECT_FLUXFAST'}else{'PASS_ISOLATED_016_DIRECT_FLUXFAST_13_INTEROP'}
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{Save 'receipt.json' ($taskResult|ConvertTo-Json -Depth 7);Write-Output "OPENFLUX016_RUNTIME=$($taskResult.state);STAGE=$taskRoot/$taskFolder"}
