param([Parameter(Mandatory)][string]$Stage,[Parameter(Mandatory)][string]$Serial,
 [Parameter(Mandatory)][string]$BaselineReceipt,
 [string]$Sdk='C:/Users/dieonfire/AppData/Local/Android/Sdk',
 [string]$BuildTools='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0')
. "$PSScriptRoot/Release08.Common.ps1"
if($Serial -cnotmatch '^[A-Za-z0-9._:-]+$'){throw 'Explicit device identity required'}
$root=Stage08 $Stage;$build=Build08 $root;$baseline=Read08 $BaselineReceipt
if($baseline.serial -cne $Serial -or $baseline.connectionCount -ne 14 -or !$baseline.firstInstallTime -or
   $baseline.signer -cne $build.signer -or $baseline.guardSha256 -cnotmatch '^[a-f0-9]{64}$'){throw 'Verified device baseline with 14 connections and trusted signer/guard required'}
$null=Bound08 $baseline.connectionEvidence
$connections=Read08 $baseline.connectionEvidence.path
if($connections.serial -cne $Serial -or $connections.connectionCount -ne 14 -or
   $connections.firstInstallTime -cne $baseline.firstInstallTime -or !$connections.protectedHashes){throw 'Connection evidence must bind the same device/data baseline'}
$beforeExpected=$baseline.protectedHashes
$volatileAndroidFiles=@('shared_prefs/android.app.ActivityThread.IDS.xml')
foreach($key in $volatileAndroidFiles){$beforeExpected.Remove($key);$connections.protectedHashes.Remove($key)}
if(!$beforeExpected -or $beforeExpected.Count -lt 20 -or
   !@($beforeExpected.Keys|Where-Object {$_ -cmatch '^files/(mcrf-store-[0-9a-f-]{36}/)?connections\.enc$' -and $beforeExpected[$_] -cmatch '^[a-f0-9]{64}$'}).Count){throw 'Protected connection-store baseline missing'}
if($connections.protectedHashes.Count -ne $beforeExpected.Count){throw 'Connection evidence inventory differs'}
foreach($key in $beforeExpected.Keys){if($connections.protectedHashes[$key] -cne $beforeExpected[$key]){throw 'Connection evidence data hashes differ'}}
if(Test-Path "$root/phone"){throw 'Existing phone attempt: inspect receipt; no blind retry'}
$adb="$Sdk/platform-tools/adb.exe"
function Adb08([string[]]$Arguments){Run08 $adb (@('-s',$Serial)+$Arguments)}
function Idle08 {
 if((Adb08 @('shell','am','get-current-user')) -cne '0'){throw 'Primary Android user required'}
 if((Adb08 @('shell','dumpsys','vpn_management')) -match 'Active vpn type: [01]\b'){throw 'VPN active; stop before update, no forced interruption'}
 if((Adb08 @('shell','dumpsys','activity','services','org.mcrf.client')) -match 'isForeground=true|startRequested=true|startForegroundCount=[1-9]'){throw 'MCRF service active'}
}
function Guard08 {
 $out=Adb08 @('shell','am','instrument','--user','0','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation')
 if($out -notmatch 'passed=true' -or $out -notmatch 'stable=true' -or $out -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Protected data guard refused'}
 $rows=@{};foreach($m in [regex]::Matches($out,'(?m)(?:^|protectedHashes=)((?:files|shared_prefs)/[^\r\n=]+)=([a-f0-9]{64}|absent)\s*$')){$rows[$m.Groups[1].Value]=$m.Groups[2].Value}
 foreach($key in $volatileAndroidFiles){$rows.Remove($key)}
 if($rows.Count -ne $beforeExpected.Count){throw 'Protected inventory differs'}
 foreach($key in $beforeExpected.Keys){if(!$rows.ContainsKey($key) -or $rows[$key] -cne $beforeExpected[$key]){throw 'Protected settings/connections differ; do not restore old data'}}
 $rows
}
function Installed08([string]$Package){
 $text=Adb08 @('shell','dumpsys','package',$Package)
 if($text -notmatch 'versionCode=([0-9]+)\b'){return $null};$version=[int]$Matches[1]
 if($text -notmatch '(?m)^\s*firstInstallTime=(.+)\s*$'){throw 'firstInstallTime missing'}
 @{versionCode=$version;firstInstallTime=$Matches[1].Trim()}
}
Idle08
$guard=Adb08 @('shell','pm','path','--user','0','org.mcrf.client.guard')
if($guard -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Trusted guard not installed'}
if((Adb08 @('shell','sha256sum',$Matches[1])).Split()[0] -cne $baseline.guardSha256){throw 'Guard identity changed'}
$main=Installed08 'org.mcrf.client'
if(!$main -or $main.firstInstallTime -cne $baseline.firstInstallTime -or $main.versionCode -ne $baseline.versionCode -or $main.versionCode -gt 10065){throw 'Forward upgrade baseline differs'}
$null=Guard08
$abi=Adb08 @('shell','getprop','ro.product.cpu.abi')
$selected=@($build.assets|Where-Object {$_.abi -ceq $abi})
Sets08 $selected.kind (Kinds08)
$null=New-Item -ItemType Directory "$root/phone"
$result=@{state='INSTALLING';versionName='2.0';versionCode=10065;abi=$abi;serial=$Serial;buildReceiptSha256=Hash08 "$root/receipt.json";installed=@();dataPreserved=$false;published=$false}
try{
 # Verify ALL update pairs before the first install. No uninstall, clear or -d.
 foreach($asset in $selected){
  foreach($key in @('alreadyInstalled','firstInstallTimeBefore','sha256Before','versionCodeBefore')){$asset.Remove($key)}
  Apk08 "$root/assets/$($asset.name)" $asset $build.signer $BuildTools
  $old=Installed08 $asset.package
  if($old -and $old.versionCode -gt $asset.versionCode){throw 'Only forward installed-package updates allowed'}
  if($old){
   $path=Adb08 @('shell','pm','path','--user','0',$asset.package)
   if($path -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Single installed APK path required'}
   $oldApk="$root/phone/$($asset.kind)-before.apk";$null=Adb08 @('pull',$Matches[1],$oldApk)
   $oldHash=Hash08 $oldApk
   if((Adb08 @('shell','sha256sum',$Matches[1])).Split()[0] -cne $oldHash){throw 'Installed APK changed during preflight'}
   $badging=Run08 "$BuildTools/aapt.exe" @('dump','badging',$oldApk)
   if($badging -cnotmatch "(?m)^package: name='([^']+)' versionCode='([0-9]+)'" -or
      $Matches[1] -cne $asset.package -or [int]$Matches[2] -ne $old.versionCode){throw 'Installed APK package/version differs'}
   $cert=Run08 "$BuildTools/apksigner.bat" @('verify','--print-certs',$oldApk)
   $certs=@([regex]::Matches($cert,'(?m)^Signer #[0-9]+ certificate SHA-256 digest: ([a-f0-9]{64})\s*$'))
   if($certs.Count -ne 1 -or $certs[0].Groups[1].Value -cne $build.signer){throw 'Installed signer differs; no removal fallback'}
   $asset['firstInstallTimeBefore']=$old.firstInstallTime
   $asset['sha256Before']=$oldHash
   $asset['versionCodeBefore']=$old.versionCode
   if($old.versionCode -eq $asset.versionCode){
    # A locally tested release candidate can replace an earlier build with the
    # same public versionCode. The signer, package, protected data and exact
    # installed bytes are still verified before and after `adb install -r`.
    if((Hash08 $oldApk) -ceq $asset.sha256){$asset['alreadyInstalled']=$true}
   }
  }elseif($asset.kind -ceq 'app'){throw 'Main app must be an in-place update'}
 }
 foreach($asset in ($selected|Sort-Object @{Expression={if($_.kind -ceq 'app'){1}else{0}}})){
  Idle08;$null=Guard08
  if($asset.ContainsKey('sha256Before')){
   $current=Installed08 $asset.package
   if(!$current -or $current.versionCode -ne $asset.versionCodeBefore -or $current.firstInstallTime -cne $asset.firstInstallTimeBefore){throw 'Installed identity changed since preflight'}
   $currentPath=Adb08 @('shell','pm','path','--user','0',$asset.package)
   if($currentPath -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$' -or
      (Adb08 @('shell','sha256sum',$Matches[1])).Split()[0] -cne $asset.sha256Before){throw 'Installed bytes changed since preflight'}
  }
  if($asset.ContainsKey('alreadyInstalled') -and $asset.alreadyInstalled){
   $result.installed+=@{package=$asset.package;kind=$asset.kind;sha256=$asset.sha256;alreadyInstalled=$true;sha256Before=$asset.sha256Before;firstInstallTime=$asset.firstInstallTimeBefore};Save08 "$root/phone/receipt.json" $result;continue
  }
  if((Hash08 "$root/assets/$($asset.name)") -cne $asset.sha256){throw 'APK changed since preflight'}
  $out=Adb08 @('install','--user','0','--no-streaming','-r',"$root/assets/$($asset.name)")
  if($out -notmatch '(?m)^Success\s*$'){throw 'Update not confirmed; no consent bypass'}
  $now=Installed08 $asset.package
  $beforeTime=if($asset.ContainsKey('firstInstallTimeBefore')){$asset.firstInstallTimeBefore}else{$null}
  if(!$now -or $now.versionCode -ne $asset.versionCode -or ($beforeTime -and $now.firstInstallTime -cne $beforeTime)){throw 'Update identity/firstInstallTime changed'}
  $path=Adb08 @('shell','pm','path','--user','0',$asset.package)
  if($path -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$' -or (Adb08 @('shell','sha256sum',$Matches[1])).Split()[0] -cne $asset.sha256){throw 'Installed bytes differ'}
  $result.installed+=@{package=$asset.package;kind=$asset.kind;sha256=$asset.sha256};Save08 "$root/phone/receipt.json" $result
  $null=Guard08
 }
 $result.protectedHashes=Guard08;$result.firstInstallTime=(Installed08 'org.mcrf.client').firstInstallTime
 $result.baselineReceiptSha256=Hash08 $BaselineReceipt;$result.connectionCount=14;$result.dataPreserved=$true
 $result.state='UPDATED_PRESERVED_ACCEPTANCE_PENDING'
}catch{$result.state='FAILED_PRESERVED';$result.error=$_.Exception.Message;throw}
finally{Save08 "$root/phone/receipt.json" $result}
