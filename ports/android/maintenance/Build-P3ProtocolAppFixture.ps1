param([Parameter(Mandatory)][string]$NativeStage,[Parameter(Mandatory)][string]$Snapshot,[string]$ResumeStage,[switch]$ServersOnly,[switch]$IsolatedPhone)
$ErrorActionPreference='Stop'
if($IsolatedPhone -and $ResumeStage){throw 'Phone ELF must be a fresh ARM64 build, not the x86 resume'}
$taskAbi=if($IsolatedPhone){'arm64-v8a'}else{'x86_64'}
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation build
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))}
$taskNative=(Resolve-Path $NativeStage).Path.Replace([char]92,[char]47)
if($taskNative -cnotmatch '^D:/MCRF/Builds/Android/p3-protocols-native-[a-f0-9]{32}$'){throw 'Exact accepted original protocol stage required'}
$taskNativeProof=Get-Content "$taskNative/receipt.json" -Raw|ConvertFrom-Json
if(!$taskNativeProof.producerComplete -or $taskNativeProof.state -cne 'COMPILED_OWNED_FIXTURE_VERIFIED' -or $taskNativeProof.compiledSourceManifestSha256 -cne (Hash "$taskNative/compiled-source-manifest.json")){throw 'Accepted source-bound original SDK stage required'}
if($ResumeStage){
 $taskRoot=(Resolve-Path $ResumeStage).Path.Replace([char]92,[char]47)
 if($taskRoot -cnotmatch '^D:/MCRF/Builds/Android/p3-protocol-app-fixture-[a-f0-9]{32}$'){throw 'Exact owned failed fixture stage required'}
 $taskPrior=Get-Content "$taskRoot/inputs.json" -Raw|ConvertFrom-Json
 if($taskPrior.originalSdkReceiptSha256 -cne (Hash "$taskNative/receipt.json") -or $taskPrior.fixtureGoSha256 -cne (Hash "$PSScriptRoot/protocol-app-fixture/main.go") -or $taskPrior.fixtureGoSha256 -cne (Hash "$taskRoot/module/cmd/mcrf-app-owned/main.go") -or !(Test-Path "$taskRoot/lib/x86_64/libp3_protocol_servers.so")){throw 'Retain only unchanged source-bound server ELF'}
 $taskArchive="$taskRoot/packaging-attempt-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $taskArchive|Out-Null
 foreach($taskName in @('servers.apk','receipt.json','P3OwnedProtocolProvider.java','AndroidManifest.xml','javac.log','aapt.log','signature.log')){if(Test-Path "$taskRoot/$taskName"){Copy-Item "$taskRoot/$taskName" "$taskArchive/$taskName"}}
 Copy-Item "$PSScriptRoot/protocol-app-fixture/P3OwnedProtocolProvider.java" "$taskRoot/P3OwnedProtocolProvider.java"
}else{
$taskRoot="D:/MCRF/Builds/Android/p3-protocol-app-fixture-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $taskRoot,"$taskRoot/module","$taskRoot/classes","$taskRoot/dex","$taskRoot/assets","$taskRoot/lib/$taskAbi"|Out-Null
$taskRows=@(Get-Content "$taskNative/compiled-source-manifest.json" -Raw|ConvertFrom-Json)
foreach($row in $taskRows){
 if([IO.Path]::IsPathRooted($row.path) -or '..' -in ($row.path -split '[/\\]')){throw 'Unsafe source manifest path'}
 $from=Get-Item -LiteralPath "$taskNative/module/$($row.path)"
 if($from.LinkType -or (Hash $from.FullName) -cne $row.sha256){throw 'Accepted SDK source changed'}
 $to="$taskRoot/module/$($row.path)";New-Item -ItemType Directory -Path (Split-Path $to) -Force|Out-Null;Copy-Item -LiteralPath $from.FullName -Destination $to
}
New-Item -ItemType Directory -Path "$taskRoot/module/cmd/mcrf-app-owned"|Out-Null
Copy-Item "$PSScriptRoot/protocol-app-fixture/main.go" "$taskRoot/module/cmd/mcrf-app-owned/main.go"
Copy-Item "$PSScriptRoot/protocol-app-fixture/P3OwnedProtocolProvider.java" "$taskRoot/P3OwnedProtocolProvider.java"
Copy-Item "$taskNative/licenses" "$taskRoot/assets/licenses" -Recurse
Save @{originalSdkReceiptSha256=Hash "$taskNative/receipt.json";originalSdkManifestSha256=Hash "$taskNative/compiled-source-manifest.json";fixtureGoSha256=Hash "$taskRoot/module/cmd/mcrf-app-owned/main.go";providerJavaSha256=Hash "$taskRoot/P3OwnedProtocolProvider.java";mainSnapshot=$Snapshot;productionModified=$false;originalClosedCasesExecuted=$false} "$taskRoot/inputs.json"
}
if($IsolatedPhone){
 $taskProvider=[IO.File]::ReadAllText("$taskRoot/P3OwnedProtocolProvider.java").Replace('if(!Build.HARDWARE.contains("ranchu")&&!Build.FINGERPRINT.contains("generic"))throw new SecurityException("Owned emulator only");','if(!"org.mcrf.p3test".equals(getContext().getPackageManager().getNameForUid(Binder.getCallingUid())))throw new SecurityException("Isolated test UID only");').Replace('"org.mcrf.client"','"org.mcrf.p3test"')
 [IO.File]::WriteAllText("$taskRoot/P3OwnedProtocolProvider.java",$taskProvider,[Text.UTF8Encoding]::new($false))
}
$taskSdk='C:/Users/dieonfire/AppData/Local/Android/Sdk';$taskJava='C:/Program Files/Java/jdk-17/bin';$taskTools="$taskSdk/build-tools/36.0.0";$taskAndroid="$taskSdk/platforms/android-34/android.jar"
function Run([string]$Name,[string]$Exe,[string[]]$Arguments){& $Exe @Arguments *> "$taskRoot/$Name.log";if($LASTEXITCODE){throw "$Name failed; preserve exact log at $taskRoot"}}
$taskSaved=@{};foreach($name in @('GOROOT','GOOS','GOARCH','CGO_ENABLED','GOENV','GOWORK','GOFLAGS','GOTOOLCHAIN','GOPROXY','GOPATH','GOMODCACHE','GOCACHE','GOTMPDIR','CC','CXX')){$taskSaved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
if(!$ResumeStage){try{
 [Environment]::SetEnvironmentVariable('GOROOT',$null,'Process');$env:GOOS='android';$env:GOARCH='amd64';$env:CGO_ENABLED='1';$env:GOENV='off';$env:GOWORK='off';$env:GOFLAGS='';$env:GOTOOLCHAIN='local';$env:GOPROXY='off'
 if($IsolatedPhone){$env:GOARCH='arm64'}
 $env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-home';$env:GOMODCACHE="$env:GOPATH/pkg/mod";$env:GOCACHE='D:/MCRF/Projects/MCRF/work/toolchains/go-build-cache';$env:GOTMPDIR='D:/MCRF/Projects/MCRF/work/toolchains/go-tmp'
 $taskClang="$taskSdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin";$env:CC="$taskClang/clang.exe --target=x86_64-linux-android29";$env:CXX="$taskClang/clang++.exe --target=x86_64-linux-android29"
 if($IsolatedPhone){$env:CC="$taskClang/clang.exe --target=aarch64-linux-android29";$env:CXX="$taskClang/clang++.exe --target=aarch64-linux-android29"}
 Push-Location "$taskRoot/module";try{Run 'native-server' 'D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/bin/go.exe' @('build','-mod=readonly','-trimpath','-buildvcs=false','-buildmode=pie','-tags=with_quic,with_utls,with_naive_outbound','-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384','-o',"$taskRoot/lib/$taskAbi/libp3_protocol_servers.so",'./cmd/mcrf-app-owned')}finally{Pop-Location}
}finally{foreach($name in $taskSaved.Keys){[Environment]::SetEnvironmentVariable($name,$taskSaved[$name],'Process')}}}
Run 'javac' "$taskJava/javac.exe" @('--release','8','-encoding','UTF-8','-classpath',$taskAndroid,'-d',"$taskRoot/classes","$taskRoot/P3OwnedProtocolProvider.java")
Run 'jar' "$taskJava/jar.exe" @('cf',"$taskRoot/classes.jar",'-C',"$taskRoot/classes",'.')
Run 'd8' "$taskTools/d8.bat" @('--release','--min-api','29','--lib',$taskAndroid,'--output',"$taskRoot/dex","$taskRoot/classes.jar")
[IO.File]::WriteAllText("$taskRoot/AndroidManifest.xml",'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.mcrf.ownedprotocols"><uses-sdk android:minSdkVersion="29" android:targetSdkVersion="34"/><uses-permission android:name="android.permission.INTERNET"/><uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/><queries><package android:name="org.mcrf.client"/><package android:name="org.mcrf.engines.steer"/></queries><application android:label="MCRF owned protocol test servers" android:allowBackup="false" android:testOnly="true" android:extractNativeLibs="true"><provider android:name="org.mcrf.ownedprotocols.P3OwnedProtocolProvider" android:authorities="org.mcrf.ownedprotocols" android:exported="true"/></application></manifest>',[Text.UTF8Encoding]::new($false))
if($IsolatedPhone){[IO.File]::WriteAllText("$taskRoot/AndroidManifest.xml",[IO.File]::ReadAllText("$taskRoot/AndroidManifest.xml").Replace('android:name="org.mcrf.client"','android:name="org.mcrf.p3test"'),[Text.UTF8Encoding]::new($false))}
Run 'aapt' "$taskTools/aapt.exe" @('package','-f','-M',"$taskRoot/AndroidManifest.xml",'-A',"$taskRoot/assets",'-I',$taskAndroid,'-F',"$taskRoot/unsigned.apk")
Push-Location "$taskRoot/dex";try{Run 'aapt-dex' "$taskTools/aapt.exe" @('add',"$taskRoot/unsigned.apk",'classes.dex')}finally{Pop-Location}
Push-Location $taskRoot;try{Run 'aapt-native' "$taskTools/aapt.exe" @('add',"$taskRoot/unsigned.apk","lib/$taskAbi/libp3_protocol_servers.so")}finally{Pop-Location}
Run 'zipalign' "$taskTools/zipalign.exe" @('-f','4',"$taskRoot/unsigned.apk","$taskRoot/aligned.apk")
Run 'sign' "$taskTools/apksigner.bat" @('sign','--ks','C:/Users/dieonfire/.android/debug.keystore','--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',"$taskRoot/servers.apk","$taskRoot/aligned.apk")
Run 'signature' "$taskTools/apksigner.bat" @('verify','--print-certs',"$taskRoot/servers.apk")
if([IO.File]::ReadAllText("$taskRoot/signature.log") -notmatch 'certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Same signer required'}
Save @{state='COMPOSED_SERVERS_COMPILED_NOT_EXECUTED';apk="$taskRoot/servers.apk";abi=$taskAbi;isolatedPhone=[bool]$IsolatedPhone;apkSha256=Hash "$taskRoot/servers.apk";nativeSha256=Hash "$taskRoot/lib/$taskAbi/libp3_protocol_servers.so";inputsSha256=Hash "$taskRoot/inputs.json";phoneTouched=$false;productionRebuilt=$false;closedTestsReplayed=$false} "$taskRoot/receipt.json"
Write-Output "P3_PROTOCOL_APP_SERVERS=$taskRoot"
if(!$ServersOnly){& "$PSScriptRoot/Build-P3SteerRuntimeProbe.ps1" -Snapshot $Snapshot -ProtocolMatrix}
