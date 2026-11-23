param([switch]$PublicDownload)
$ErrorActionPreference='Stop';$root='D:/MCRF/Projects/MCRF';$device='R5CX73QYN3D';$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$fixed='D:/MCRF/Builds/Android/singbox10-delivery-fix-db1e29c8858c43f5bc0702033b6ce73d';$runner='D:/MCRF/Builds/Android/delivery-fix-runner-21c5ba88c1ea45129c1a3d475eeff80a'
function Hash([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Adb([string[]]$a){$out=(& $adb -s $device @a 2>&1|Out-String).Trim();if($LASTEXITCODE){throw 'Owned device command failed'};$out}
function Save($v,[string]$p){[IO.File]::WriteAllText($p,(ConvertTo-Json -InputObject $v -Depth 9),[Text.UTF8Encoding]::new($false))}
$id=[guid]::NewGuid().ToString('N');$stage="D:/MCRF/Builds/Android/delivery-checks-$id";$remote="/data/local/tmp/mcrf-delivery-$id";New-Item -ItemType Directory -Path $stage|Out-Null
$result=[ordered]@{state='PREPARING';publicDownload=[bool]$PublicDownload;checks=0;helperRemoved=$false;protectedUnchanged=$false;mainUnchanged=$false;nativeRebuilt=$false;oldTestsReplayed=$false}
function Guard([string]$name){$out=Adb @('shell','am','instrument','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation');[IO.File]::WriteAllText("$stage/$name.log",$out,[Text.UTF8Encoding]::new($false));$m=[regex]::Match($out.Replace("`r",''),'(?s)INSTRUMENTATION_RESULT: protectedHashes=(.*?)\n\n');if(!$m.Success -or $out -notmatch 'passed=true' -or $out -notmatch 'stable=true'){throw 'Protected inventory failed'};$m.Groups[1].Value.Trim()}
$oldStay=$null;$remoteFiles=@();$installed=$false
try{
 if((Adb @('shell','getprop','ro.product.model')) -cne 'SM-F741B' -or (Adb @('shell','am','get-current-user')) -cne '0'){throw 'Owned Samsung only'}
 $vpn=Adb @('shell','dumpsys','vpn_management');if($vpn -notmatch 'Active vpn type:' -or $vpn -match 'Active vpn type: [01]\b'){throw 'Active user VPN; do not stop it'}
 if((Adb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Normal unlocked phone required'}
 $p=Adb @('shell','pm','path','org.mcrf.client');if($p -notmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Main path missing'};$mainPath=$Matches[1]
 if(!(Adb @('shell','sha256sum',$mainPath)).StartsWith('8c79308dab7cbac4d1616af1931a88939e95617ff24f4819231b059dd1105169')){throw 'Exact release0.6 R8 APK required'}
 $oldStay=Adb @('shell','settings','get','global','stay_on_while_plugged_in');$null=Adb @('shell','settings','put','global','stay_on_while_plugged_in','3')
 $before=Guard 'protected-before'
 $proof=Get-Content "$fixed/verification-receipt.json" -Raw|ConvertFrom-Json;if($proof.state -cne 'PASS_DELIVERY_METADATA_SIGNER_RUNTIME_IDENTITY'){throw 'Verified delivery fix required'}
 $rows=@();$local=@{}
 if($PublicDownload){
  $row=@($proof.artifacts|Where-Object abi -eq 'arm64-v8a')[0]
  $rows+=@{url='https://github.com/vnenapravo7-source/mcrf/releases/download/engines-2026-10-13-1/mcrf-engine-sing-box-arm64-v8a-release.apk';sha256=$row.sha256;bytes=$row.bytes}
 }else{
  $plan=Get-Content D:/MCRF/Builds/Android/release06-publication-20261023/plan.json -Raw|ConvertFrom-Json
  foreach($row in $plan.engines|Where-Object {$_.abi -eq 'arm64-v8a'}){
   $path="D:/MCRF/Builds/Android/release06-publication-20261023/assets/$($row.name)";$sha=$row.sha256
   if($row.kind -eq 'sing-box'){$n=@($proof.artifacts|Where-Object abi -eq 'arm64-v8a')[0];$path=$n.path;$sha=$n.sha256}
   $file="$($row.kind).apk";$rows+=@{file=$file;kind=$row.kind;sha256=$sha};$local[$file]=$path
  }
  $old='D:/MCRF/Builds/Android/p2-primary-release06-dfecc988d4a5433fbcd428501d77b36f/source/ports/android/p2-route-dispatcher/build/outputs/apk/release/p2-route-dispatcher-arm64-v8a-release.apk'
  $local['old-sing-box.apk']=$old;$rows+=@{file='old-sing-box.apk';kind='sing-box';sha256=Hash $old;reject=$true}
  $n=@($proof.artifacts|Where-Object abi -eq 'arm64-v8a')[0];$rows+=@{file='sing-box.apk';kind='steer';sha256=$n.sha256;reject=$true}
  if($rows.Count -ne 13){throw 'Exact11positive+oldmarkernegative+wrongkindnegative required'}
 }
 Save @($rows) "$stage/cases.json"
 $null=Adb @('shell','mkdir',$remote)
 foreach($file in $local.Keys){if((Hash $local[$file]) -cne @($rows|Where-Object file -eq $file)[0].sha256){throw 'Case source drift'};$null=Adb @('push',$local[$file],"$remote/$file");$remoteFiles+="$remote/$file"}
 $null=Adb @('push',"$stage/cases.json","$remote/cases.json");$remoteFiles+="$remote/cases.json"
 $r=Get-Content "$runner/receipt.json" -Raw|ConvertFrom-Json;if((Hash $r.apk) -cne $r.sha256){throw 'New fixture digest drift'}
 $null=Adb @('install','-t',$r.apk);$installed=$true
 $mode=if($PublicDownload){'download'}else{'verify'}
 $out=Adb @('shell','am','instrument','-w','-r','-e','cases',"$remote/cases.json",'-e','mode',$mode,'org.mcrf.client.deliverycheck/org.mcrf.delivery.DeliveryInstrumentation')
 [IO.File]::WriteAllText("$stage/result.log",$out,[Text.UTF8Encoding]::new($false))
 if($out -match 'INSTRUMENTATION_RESULT: checks=(\d+)'){$result.checks=[int]$Matches[1]}
 if($out -notmatch 'passed=true' -or $out -notmatch 'INSTRUMENTATION_CODE: -1' -or $result.checks -ne $(if($PublicDownload){1}else{13})){throw 'New delivery runtime check failed; preserve exact result'}
 $result.protectedUnchanged=(Guard 'protected-after') -ceq $before;if(!$result.protectedUnchanged){throw 'Protected state changed; no blind restore'}
 $result.mainUnchanged=(Adb @('shell','sha256sum',$mainPath)).StartsWith('8c79308dab7cbac4d1616af1931a88939e95617ff24f4819231b059dd1105169')
 if(!$result.mainUnchanged){throw 'Main APK changed'};$result.state='PASS_DELIVERY_RUNTIME'
}catch{$result.state='FAILED_PRESERVED';throw}finally{
 if($installed){$null=Adb @('uninstall','org.mcrf.client.deliverycheck');$result.helperRemoved=$true}
 if($remoteFiles.Count){$null=Adb (@('shell','rm')+$remoteFiles)}
 if($remote -match '^/data/local/tmp/mcrf-delivery-[a-f0-9]{32}$'){$null=Adb @('shell','rmdir',$remote)}
 if($null -ne $oldStay){$null=Adb @('shell','settings','put','global','stay_on_while_plugged_in',$oldStay)}
 Save $result "$stage/receipt.json";Write-Output "DELIVERY_CHECKS=$stage;STATE=$($result.state);COUNT=$($result.checks)"
}
