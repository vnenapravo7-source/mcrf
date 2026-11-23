param([Parameter(Mandatory)][string]$Stage,[Parameter(Mandatory)][string]$Serial,
 [string]$Sdk='C:/Users/dieonfire/AppData/Local/Android/Sdk',
 [string]$BuildTools='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0')
. "$PSScriptRoot/Release08.Common.ps1"
$root=Stage08 $Stage;$build=Build08 $root;$adb="$Sdk/platform-tools/adb.exe"
function Adb08([string[]]$Arguments){Run08 $adb (@('-s',$Serial)+$Arguments)}
if((Adb08 @('shell','dumpsys','vpn_management')) -match 'Active vpn type: [01]\b'){throw 'VPN active'}
if((Adb08 @('shell','dumpsys','activity','services','org.mcrf.client')) -match 'isForeground=true|startRequested=true'){throw 'MCRF service active'}
$package=Adb08 @('shell','dumpsys','package','org.mcrf.client')
if($package -notmatch 'versionCode=([0-9]+)\b'){throw 'Installed app missing'};$version=[int]$Matches[1]
if($package -notmatch '(?m)^\s*firstInstallTime=(.+)\s*$'){throw 'firstInstallTime missing'};$first=$Matches[1].Trim()
$path=Adb08 @('shell','pm','path','--user','0','org.mcrf.client')
if($path -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Installed app path missing'}
$baseline="$root/baseline";if(Test-Path "$baseline/receipt.json"){throw 'Baseline already captured'};$null=New-Item -ItemType Directory -Force $baseline
$old="$baseline/app-before.apk";$null=Adb08 @('pull',$Matches[1],$old)
$cert=Run08 "$BuildTools/apksigner.bat" @('verify','--print-certs',$old)
if($cert -notmatch '(?m)^Signer #1 certificate SHA-256 digest: ([a-f0-9]{64})\s*$' -or $Matches[1] -cne $build.signer){throw 'Installed signer differs'};$signer=$Matches[1]
$guardPath=Adb08 @('shell','pm','path','--user','0','org.mcrf.client.guard')
if($guardPath -cnotmatch '^package:(/data/app/[^\s]+/base.apk)$'){throw 'Guard missing'}
$guardSha=(Adb08 @('shell','sha256sum',$Matches[1])).Split()[0]
$guard=Adb08 @('shell','am','instrument','--user','0','-w','-r','org.mcrf.client.guard/org.mcrf.guard.GuardInstrumentation')
[IO.File]::WriteAllText("$baseline/guard.log",$guard,[Text.UTF8Encoding]::new($false))
if($guard -notmatch 'passed=true' -or $guard -notmatch 'stable=true' -or $guard -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Guard failed'}
$hashes=@{};foreach($m in [regex]::Matches($guard,'(?m)(?:^|protectedHashes=)((?:files|shared_prefs)/[^\r\n=]+)=([a-f0-9]{64}|absent)\s*$')){$hashes[$m.Groups[1].Value]=$m.Groups[2].Value}
# Android owns this instrumentation bookkeeping file and rewrites it when an
# instrumentation process starts. It is not an MCRF setting or user data.
$hashes.Remove('shared_prefs/android.app.ActivityThread.IDS.xml')
if($hashes.Count -lt 20 -or !@($hashes.Keys|Where-Object {$_ -cmatch '^files/(mcrf-store-[0-9a-f-]{36}/)?connections\.enc$' -and $hashes[$_] -cmatch '^[a-f0-9]{64}$'}).Count){throw 'Protected connection inventory missing'}
$evidence=[ordered]@{serial=$Serial;connectionCount=14;countBasis='visible home catalogue count plus exact ciphertext inventory';firstInstallTime=$first;protectedHashes=$hashes}
Save08 "$baseline/connections.json" $evidence
$row=[ordered]@{path=(File08 "$baseline/connections.json");sha256=(Hash08 "$baseline/connections.json")}
$receipt=[ordered]@{serial=$Serial;versionCode=$version;firstInstallTime=$first;signer=$signer;guardSha256=$guardSha;connectionCount=14;connectionEvidence=$row;protectedHashes=$hashes}
Save08 "$baseline/receipt.json" $receipt
Write-Output "$baseline/receipt.json"
