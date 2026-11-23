# Shared validators only. Dot-sourcing never starts builds, adb or GitHub.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
function Kinds08 {@('app','csqtt','csqtt20','csqtt215','csqtt25','qwdtt','steer','wdtt')}
function Sets08($Actual,$Expected){
 if((@($Actual|Sort-Object) -join ',') -cne (@($Expected|Sort-Object) -join ',')){throw 'Exact release 2.0 package/ABI set required'}
}
function Hash08([string]$Path){(Get-FileHash -LiteralPath (File08 $Path) -Algorithm SHA256).Hash.ToLowerInvariant()}
function File08([string]$Path){
 $p=[IO.Path]::GetFullPath($Path)
 if(!$p.StartsWith('D:\MCRF\',[StringComparison]::OrdinalIgnoreCase)){throw 'Release08 inputs must stay under D:/MCRF'}
 $item=Get-Item -LiteralPath $p
 if($item.PSIsContainer){throw 'Regular file required'}
 for($node=$item;$null -ne $node;){
  if($node.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Redirected release input refused'}
  if($node.FullName -eq [IO.Path]::GetPathRoot($node.FullName)){break}
  $node=Get-Item -LiteralPath (Split-Path $node.FullName)
 }
 $p
}
function Read08([string]$Path){Get-Content -LiteralPath (File08 $Path) -Raw|ConvertFrom-Json -AsHashtable}
function Save08([string]$Path,$Value){[IO.File]::WriteAllText($Path,(ConvertTo-Json -InputObject $Value -Depth 25),[Text.UTF8Encoding]::new($false))}
function Stage08([string]$Stage){
 $p=[IO.Path]::GetFullPath($Stage).TrimEnd('\','/')
 if($p.Replace('\','/') -cnotmatch '^D:/MCRF/Builds/Android/release08-[a-f0-9]{32}$'){throw 'Exact owned release08 stage required'}
 $null=File08 "$p/receipt.json";$p
}
function Bound08($Row){
 if(!$Row.path -or $Row.sha256 -cnotmatch '^[a-f0-9]{64}$' -or (Hash08 $Row.path) -cne $Row.sha256){throw 'Release08 bound input changed'}
 $Row.path
}
function Source08($Build){
 $null=Bound08 $Build.sourceManifest
 $rows=@(Read08 $Build.sourceManifest.path)
 if(!$rows.Count -or @($rows.path|Sort-Object -Unique).Count -ne $rows.Count){throw 'Nonempty unique source closure required'}
 foreach($row in $rows){$null=Bound08 $row}
}
function Historical08([string]$Stage,[string]$Kind,[string]$WrapperReceiptSha256){
 $d=Read08 "$Stage/receipt.json"
 $generation=switch -CaseSensitive ($Kind){'csqtt20'{'2.0'}'csqtt215'{'2.1.5'}default{throw 'Unknown CSQTT historical package'}}
 $revision=if($Kind -ceq 'csqtt20'){'2ff8a1a4fbe031fb76b799e172dce25cfe1ca942'}else{'de7afc23cec7481f98845bf24fc8943af19b4cf8'}
 if($d.state -cne 'HISTORICAL_ENGINE_INPUTS_READY' -or $d.sourceStable -ne $true -or $d.kind -cne $Kind -or
    $d.generation -cne $generation -or $d.revision -cne $revision -or $d.packageVersionCode -ne 4 -or
    $d.packageVersionName -cne "$generation-mcrf.performance.4" -or
    $d.wrapperReceiptSha256 -cne $WrapperReceiptSha256){throw 'Historical package must use the current source-gated CSQTT wrapper'}
 $null=Bound08 @{path="$Stage/wrapper-receipt.json";sha256=$d.wrapperReceiptSha256}
 $null=Bound08 @{path="$Stage/native-receipt.json";sha256=$d.nativeReceiptSha256}
 $w=Read08 "$Stage/wrapper-receipt.json";$n=Read08 "$Stage/native-receipt.json"
 if($w.kind -cne 'csqtt' -or $w.state -cne 'COMPILED_NOT_EXECUTED' -or $w.sourceStable -ne $true -or
    $w.processGroupContract -ne 1 -or $w.turnHashPoolContract -ne 1 -or $w.captchaWaiterCapability -cne 'csqtt-id48-v1' -or
    $n.engine -cne $Kind -or $n.generation -cne $generation -or $n.revision -cne $revision -or
    $n.state -cne 'COMPILED_NOT_EXECUTED' -or $n.sourceStable -ne $true){throw 'Historical native/wrapper provenance differs'}
 # An adapter change invalidates retained Rust inputs; unchanged Rust is never rebuilt here.
 if((Hash08 "$PSScriptRoot/../transports/swg/prepare-csqtt-historical.py") -cne $n.adapterSha256){throw 'Historical adapter changed; source gate requires refreshed native provenance'}
 $expected=@();foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){$expected+="$abi/lib$Kind.so";$expected+="$abi/libcsqtt_transport.so"}
 Sets08 $d.artifacts.path $expected
 foreach($a in $d.artifacts){
  if($a.path -cnotmatch ('^'+[regex]::Escape($a.abi)+'/')){throw 'Historical ABI/path differs'}
  $path=File08 "$Stage/$($a.path)"
  if((Hash08 $path) -cne $a.sha256 -or (Get-Item -LiteralPath $path).Length -ne $a.bytes){throw 'Historical package bytes changed'}
  $rust=$a.path.EndsWith('/libcsqtt_transport.so')
  $origin=@(if($rust){$n.artifacts|Where-Object abi -CEQ $a.abi}else{$w.artifacts|Where-Object {$_.abi -ceq $a.abi -and [IO.Path]::GetFileName($_.file) -ceq 'libqwdtt.so'}})
  if($origin.Count -ne 1 -or $origin[0].sha256 -cne $a.sha256 -or $origin[0].bytes -ne $a.bytes){throw 'Historical package does not match pinned native/wrapper outputs'}
 }
 foreach($name in @('CSQTT-LICENSE','qwdtt-LICENSE')){$null=File08 "$Stage/licenses/$name"}
 $d
}
function Build08([string]$Stage){
 $root=Stage08 $Stage;$b=Read08 "$root/receipt.json"
 if($b.state -cne 'BUILT_NOT_INSTALLED_OR_PUBLISHED' -or $b.authority -cne 'human-20261029-local-test-then-release08' -or
    $b.versionName -cne '2.0' -or $b.versionCode -ne 10065 -or $b.sourceStable -ne $true -or $b.groupedAffectedBuild -ne $true){throw 'Complete source-bound 2.0 build receipt required'}
 Source08 $b
 $null=Bound08 $b.nativeReceipt;$native=Read08 $b.nativeReceipt.path
 if($native.state -cne 'AFFECTED_NATIVE_REBUILT' -or $native.sourceManifestSha256 -cne $b.sourceManifest.sha256 -or
    (@($native.engines.kind|Sort-Object) -join ',') -cne 'csqtt,csqtt25,qwdtt,steer,wdtt'){throw 'All five affected native producers required'}
 foreach($e in $native.engines){
  if($e.rebuilt -ne $true -or $e.sourceStable -ne $true -or !$e.outputs.Count){throw 'Retained binaries cannot substitute for changed native outputs'}
  $null=Bound08 $e.sourceManifest;foreach($row in @(Read08 $e.sourceManifest.path)){$null=Bound08 $row}
  foreach($row in $e.outputs){$null=Bound08 $row}
 }
 Sets08 $native.historicalPackages.kind @('csqtt20','csqtt215')
 $wrapper=@($native.engines|Where-Object kind -CEQ 'csqtt')[0]
 $wrapperRows=@(Read08 $wrapper.sourceManifest.path)
 if($wrapperRows.Count -ne 1){throw 'Exact CSQTT wrapper receipt binding required'}
 foreach($e in $native.historicalPackages){
  if($e.rebuilt -ne $false -or $e.sourceStable -ne $true){throw 'Retained historical provenance must be explicit'}
  $null=Bound08 $e.packageReceipt
  $package=Historical08 (Split-Path $e.packageReceipt.path) $e.kind $wrapperRows[0].sha256
  Sets08 $e.outputs.path @($package.artifacts|ForEach-Object {[IO.Path]::GetFullPath((Join-Path (Split-Path $e.packageReceipt.path) $_.path))})
  foreach($output in $e.outputs){
   $null=Bound08 $output
   $match=@($package.artifacts|Where-Object {$_.abi -ceq $output.abi -and $_.sha256 -ceq $output.sha256})
   if($match.Count -ne 1){throw 'Historical native output binding differs'}
  }
 }
 if($b.signer -cnotmatch '^[a-f0-9]{64}$' -or !$b.assets.Count){throw 'Signed assets required'}
 if(@($b.assets.name|Sort-Object -Unique).Count -ne $b.assets.Count){throw 'Duplicate assets refused'}
 foreach($a in $b.assets){
  if($a.kind -cnotin (Kinds08)){throw 'Unrelated engine'}
  $prefix=if($a.kind -ceq 'app'){'mcrf-android'}else{"mcrf-engine-$($a.kind)"}
  if($a.name -cne "$prefix-$($a.abi)-2.0.apk" -or $a.abi -cnotin @('arm64-v8a','armeabi-v7a','x86_64')){throw 'Wrong public asset version/ABI/name'}
  if($a.kind -ceq 'app'){
   if($a.package -cne 'org.mcrf.client' -or $a.versionName -cne '2.0' -or $a.versionCode -ne 10065){throw 'Wrong app release identity'}
  }elseif($a.package -cne "org.mcrf.engines.$($a.kind)"){throw 'Wrong engine package'}
  if($a.kind -cin @('csqtt20','csqtt215')){
   $generation=if($a.kind -ceq 'csqtt20'){'2.0'}else{'2.1.5'}
   if($a.versionCode -ne 4 -or $a.versionName -cne "$generation-mcrf.performance.4"){throw 'Historical APK must advance the catalogue package version'}
  }
  if((Hash08 "$root/assets/$($a.name)") -cne $a.sha256){throw 'APK changed'}
  if($a.kind -cne 'app'){
   $engine=@((@($native.engines)+@($native.historicalPackages))|Where-Object kind -CEQ $a.kind)[0]
   $outputs=@($engine.outputs|Where-Object abi -CEQ $a.abi)
   if(!$outputs.Count){throw 'APK ABI has no rebuilt native output'}
   $zip=[IO.Compression.ZipFile]::OpenRead("$root/assets/$($a.name)")
   try{foreach($output in $outputs){
    $entryName="lib/$($a.abi)/$([IO.Path]::GetFileName($output.path))"
    $entries=@($zip.Entries|Where-Object FullName -CEQ $entryName)
    if($entries.Count -ne 1){throw 'Rebuilt native output missing or duplicated in APK'}
    $stream=$entries[0].Open()
    try{$digest=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()}finally{$stream.Dispose()}
    if($digest -cne $output.sha256){throw 'APK contains different native bytes'}
   }}finally{$zip.Dispose()}
  }
 }
 Sets08 @($b.assets.kind|Sort-Object -Unique) (Kinds08)
 foreach($kind in (Kinds08)){
  $abis=if($kind -ceq 'steer'){@('arm64-v8a','x86_64')}else{@('arm64-v8a','armeabi-v7a','x86_64')}
  Sets08 @($b.assets|Where-Object kind -CEQ $kind|ForEach-Object abi) $abis
 }
 $null=Bound08 $b.sourceArchive;$null=Bound08 $b.releaseNotes
 if([IO.Path]::GetFileName($b.sourceArchive.path) -cne 'mcrf-android-2.0-source.zip'){throw 'Corresponding 2.0 source archive required'}
 $b
}
function Run08([string]$Tool,[string[]]$Arguments){
 $output=(& $Tool @Arguments 2>&1|Out-String).Trim()
 if($LASTEXITCODE -ne 0){throw "Command failed: $([IO.Path]::GetFileName($Tool)) / $($Arguments[0])"};$output
}
function Apk08([string]$Path,$Asset,[string]$Signer,[string]$BuildTools){
 $cert=Run08 "$BuildTools/apksigner.bat" @('verify','--verbose','--print-certs',$Path)
 $signers=@([regex]::Matches($cert,'(?m)^Signer #[0-9]+ certificate SHA-256 digest: ([a-f0-9]{64})\s*$'))
 if($signers.Count -ne 1 -or $signers[0].Groups[1].Value -cne $Signer){throw 'APK signer differs; never uninstall to work around'}
 $badging=Run08 "$BuildTools/aapt.exe" @('dump','badging',$Path)
 if($badging -cnotmatch "(?m)^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'"){throw 'APK identity missing'}
 if($Matches[1] -cne $Asset.package -or [int]$Matches[2] -ne $Asset.versionCode -or $Matches[3] -cne $Asset.versionName){throw 'APK identity differs from receipt'}
 if($Asset.kind -ceq 'app'){
  if($Asset.package -cne 'org.mcrf.client' -or $Asset.versionCode -ne 10065 -or $Asset.versionName -cne '2.0'){throw 'Wrong app release'}
 }elseif($Asset.package -cne "org.mcrf.engines.$($Asset.kind)"){throw 'Wrong engine package'}
}
function Acceptance08([string]$Root,$Build){
 $install=Read08 "$Root/phone/receipt.json";$accept=Read08 "$Root/device-acceptance.json"
 if($install.state -cne 'UPDATED_PRESERVED_ACCEPTANCE_PENDING' -or $install.dataPreserved -ne $true -or $install.connectionCount -ne 14 -or
    $install.buildReceiptSha256 -cne (Hash08 "$Root/receipt.json") -or $accept.state -cne 'DEVICE_ACCEPTED' -or
    $accept.buildReceiptSha256 -cne $install.buildReceiptSha256 -or $accept.installReceiptSha256 -cne (Hash08 "$Root/phone/receipt.json") -or
    $accept.serial -cne $install.serial -or $accept.connectionCount -ne 14 -or $accept.settingsPreserved -ne $true){throw 'Actual installed-device acceptance/preservation receipt required'}
 $required=@('wdtt-empty-routes','route-default-vpn','turn-live-streams','notification-server-ip','notification-proxy-hidden','random-foreground-theme','steer-post-pr')
 foreach($kind in @('qwdtt','wdtt','csqtt20','csqtt215','csqtt','csqtt25')){$required+="traffic-$kind";$required+="upload-$kind"}
 Sets08 $install.installed.kind (Kinds08)
 if($install.abi -cnotin @('arm64-v8a','x86_64')){throw 'Supported complete-install ABI required'}
 foreach($row in $install.installed){
  if(@($Build.assets|Where-Object {$_.abi -ceq $install.abi -and $_.kind -ceq $row.kind -and $_.package -ceq $row.package -and $_.sha256 -ceq $row.sha256}).Count -ne 1){throw 'Device receipt belongs to different APKs'}
 }
 foreach($name in $required){
  $cases=@($accept.cases|Where-Object name -CEQ $name)
  if($cases.Count -ne 1 -or $cases[0].result -cne 'PASS'){throw "Device case not accepted: $name"}
  $null=Bound08 $cases[0].evidence
  if($name.StartsWith('upload-') -and $cases[0].mbps -lt 12){throw 'TURN upload target not accepted'}
 }
 $accept
}
