param([switch]$OnlyOptional)
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF';$device='R5CX73QYN3D';$adb='C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe';$sdk='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
function Hash([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
function Adb([string[]]$a){$s=(& $adb -s $device @a 2>&1|Out-String).Trim();if($LASTEXITCODE){throw 'Owned Samsung command failed'};$s}
function Cert([string]$p){$s=(& "$sdk/apksigner.bat" verify --print-certs $p 2>&1|Out-String);if($LASTEXITCODE -or $s -notmatch 'certificate SHA-256 digest: ([0-9a-f]{64})'){throw 'Verified signer required'};$Matches[1]}
function Idle{
 if((Adb @('shell','getprop','ro.product.model')) -cne 'SM-F741B' -or (Adb @('shell','am','get-current-user')) -cne '0'){throw 'Exact owned Samsung required'}
 if((Adb @('shell','dumpsys','window','policy')) -notmatch '(?m)^\s+showing=false\s*$'){throw 'Normal unlocked keyguard required'}
 $v=Adb @('shell','dumpsys','vpn_management');if($v -notmatch 'Active vpn type:' -or $v -match 'Active vpn type: [01]\b'){throw 'Active or unknown user VPN; do not interrupt'}
 if((Adb @('shell','dumpsys','activity','services','org.mcrf.client')) -match 'isForeground=true|startRequested=true'){throw 'MCRF service active; do not interrupt'}
}
$main='D:/MCRF/Builds/Android/p2-release06-e7e81f3a12a14a48808710f07f92289e'
$primary='D:/MCRF/Builds/Android/p2-primary-release06-dfecc988d4a5433fbcd428501d77b36f'
$a=Get-Content "$main/verification-receipt.json" -Raw|ConvertFrom-Json;$b=Get-Content "$primary/verification-receipt.json" -Raw|ConvertFrom-Json
if($a.state -cne 'PASS_RELEASE_PACKAGING' -or $b.state -cne 'PASS_PRIMARY_RELEASE_PACKAGING'){throw 'Both exact release verifications required'}
$items=@($a.artifacts+$b.artifacts|Where-Object {$_.kind -eq 'main' -or $_.abi -eq 'arm64-v8a'})
if($items.Count -ne 10){throw 'Main and9 real optional release modules required'}
if($OnlyOptional){
 $o=Get-Content 'D:/MCRF/Builds/Android/p2-optional-release06-413efb66829146af8e0897522e648c33/verification-receipt.json' -Raw|ConvertFrom-Json
 if($o.state -cne 'PASS_OPTIONAL_RELEASE_PACKAGING'){throw 'Accepted optional release packaging required'}
 $items=@($o.artifacts|Where-Object abi -eq 'arm64-v8a');if($items.Count -ne 2){throw 'Exact two optional packages required'}
}
$stage="D:/MCRF/Builds/Android/release06-install-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $stage|Out-Null
$result=[ordered]@{state='PREPARING';installed=@();protectedUnchanged=$false;testsReplayed=$false;testVpnStarted=$false}
function Save{[IO.File]::WriteAllText("$stage/receipt.json",($result|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))}
function Guard([string]$name){$out=Adb @('shell','am','instrument','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation');[IO.File]::WriteAllText("$stage/$name.log",$out,[Text.UTF8Encoding]::new($false));$m=[regex]::Match($out.Replace("`r",''),'(?s)INSTRUMENTATION_RESULT: protectedHashes=(.*?)\n\n');if(!$m.Success -or $out -notmatch 'passed=true' -or $out -notmatch 'stable=true' -or $out -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Read-only protected guard failed'};$m.Groups[1].Value.Trim()}
try{
 Idle
 $guardian=Get-Content "$root/work/p0-data-guard-8509f0f4e30040888930e74758e270c2/receipt.json" -Raw|ConvertFrom-Json
 $gp=Adb @('shell','pm','path','org.mcrf.client.guard');if($gp -notmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Existing guardian missing'};$gh=Adb @('shell','sha256sum',$Matches[1]);if(!$gh.StartsWith($guardian.sha256)){throw 'Guardian digest mismatch'}
 $before=Guard 'protected-before'
 foreach($item in $items){
  if((Hash $item.path) -cne $item.sha256 -or (Cert $item.path) -cne 'fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Input drift or wrong signer'}
  $p=Adb @('shell','pm','path',$item.package);if($p -notmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Existing exact APK required'};$old=$Matches[1]
  $backup="$stage/previous-$($item.package).apk";$null=Adb @('pull',$old,$backup)
  $h=Adb @('shell','sha256sum',$old);if(!$h.StartsWith((Hash $backup)) -or (Cert $backup) -cne (Cert $item.path)){throw 'Recovery APK or signer mismatch'}
 }
 foreach($item in $items){
  Idle
  $out=Adb @('install','-r',$item.path);if($out -notmatch '(?m)^Success\s*$'){throw 'Install not confirmed'}
  $p=Adb @('shell','pm','path',$item.package);if($p -notmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Installed APK path missing'}
  if(!(Adb @('shell','sha256sum',$Matches[1])).StartsWith($item.sha256)){throw 'Installed APK digest mismatch'}
  $result.installed+=@{package=$item.package;versionCode=$item.versionCode;sha256=$item.sha256};Save
 }
 $after=Guard 'protected-after';$result.protectedUnchanged=$before -ceq $after;if(!$result.protectedUnchanged){throw 'Protected state changed; no blind rollback'}
 $null=Adb @('shell','am','start','-W','-n','org.mcrf.client/.MainActivity')
 $result.state='INSTALLED_RELEASE06_PROTECTED_EXACT_SMOKE_PENDING';Save
}catch{$result.state='FAILED_PRESERVED';Save;throw}finally{Write-Output "RELEASE_INSTALL=$stage;STATE=$($result.state)"}
