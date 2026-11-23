param([string]$FixtureFrom='',[string]$NativeSource='D:/MCRF/Projects/MCRF/work/ports-test-2026-10-14/openflux-build-v0.13.0-949630f3b9eb45bca877a733756711cf')
$ErrorActionPreference='Stop'
# Human 26Oct: finish OpenFlux, test on phone, then publish0.7. This builds ONLY
# an isolated self-targeted test APK; never changes main-app/native production.
$taskProject='D:/MCRF/Projects/MCRF'
$taskOriginal=[IO.Path]::GetFullPath($NativeSource)
if(!$taskOriginal.StartsWith('D:\MCRF\Projects\MCRF\work\ports-test-2026-10-14\openflux-build-v0.13.0-',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected fresh native source root'}
$taskNative="$taskOriginal/verified-native/arm64-v8a/libopenflux.so"
$taskSdk='C:/Users/dieonfire/AppData/Local/Android/Sdk'
$taskJava='C:/Program Files/Java/jdk-17/bin'
$taskTools="$taskSdk/build-tools/36.0.0"
$taskGo="$taskProject/work/toolchains/go1.26.8/go/bin/go.exe"
$taskOutput="D:/MCRF/Builds/Android/openflux13-validation-$([guid]::NewGuid().ToString('N'))"
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
$taskReceipt=Get-Content "$taskOriginal/verified-native/fresh-core-candidate.json" -Raw|ConvertFrom-Json
if(!$taskReceipt.sourceStable -or $taskReceipt.commit -cne '4f9c786cdd1d9fcd0c133e2e0fec0c5a12522610'){throw 'Fresh source receipt differs'}
$taskCapability=Get-Content "$taskOriginal/verified-native/script-updater-capability.json" -Raw|ConvertFrom-Json
if((Hash $taskNative) -cne $taskCapability.sha256.'arm64-v8a'){throw 'Native payload differs from source-bound capability receipt'}
if((Hash "$taskOriginal/mobile/cmd/mcrf-test-exit/main.go") -cne (Hash "$PSScriptRoot/../transports/openflux/testserver/main.go")){throw 'Test exit source differs from reviewed fixture'}
$null=New-Item -ItemType Directory -Path "$taskOutput/classes","$taskOutput/dex","$taskOutput/lib/arm64-v8a"
$taskSources=@{}
foreach($taskName in @('Validation.java','AndroidManifest.xml')){
 $taskSources[$taskName]=Hash "$PSScriptRoot/openflux13-validation/$taskName"
 Copy-Item -LiteralPath "$PSScriptRoot/openflux13-validation/$taskName" -Destination "$taskOutput/$taskName"
}
$taskJavaSource=Get-Content "$taskOutput/Validation.java" -Raw
if([regex]::Matches($taskJavaSource,'EXPECTED_CORE_SHA256').Count -ne 1){throw 'Exact native hash substitution missing'}
[IO.File]::WriteAllText("$taskOutput/Validation.java",$taskJavaSource.Replace('EXPECTED_CORE_SHA256',(Hash $taskNative)),[Text.UTF8Encoding]::new($false))
$taskResult=[ordered]@{state='BUILDING';sourceStable=$false;mainAppChanged=$false;installed=$false;coreRecompiled=$false;upstreamCommit=$taskReceipt.commit;nativeSha256=(Hash $taskNative);sources=$taskSources}
$taskOldPath=$env:PATH
try{
 $env:PATH="$taskSdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin;"+$taskOldPath
 $env:GOPATH="$taskProject/work/toolchains/go-home";$env:GOOS='android';$env:GOARCH='arm64';$env:CGO_ENABLED='1';$env:CC='clang --target=aarch64-linux-android29'
 if($FixtureFrom){
  $taskPrevious=[IO.Path]::GetFullPath($FixtureFrom)
  if(!$taskPrevious.StartsWith('D:\MCRF\Builds\Android\openflux13-validation-',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected retained fixture root'}
  $taskPreviousReceipt=Get-Content "$taskPrevious/receipt.json" -Raw|ConvertFrom-Json
  if(!$taskPreviousReceipt.sourceStable -or (Hash "$taskPrevious/lib/arm64-v8a/libopenflux_testserver.so") -cne $taskPreviousReceipt.fixtureSha256){throw 'Retained fixture identity differs'}
  Copy-Item -LiteralPath "$taskPrevious/lib/arm64-v8a/libopenflux_testserver.so" -Destination "$taskOutput/lib/arm64-v8a/libopenflux_testserver.so"
 }else{Push-Location "$taskOriginal/mobile"
 try{
  & $taskGo build -mod=readonly -trimpath -buildmode=pie '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o "$taskOutput/lib/arm64-v8a/libopenflux_testserver.so" ./cmd/mcrf-test-exit *> "$taskOutput/exit-build.log"
  if($LASTEXITCODE){throw 'Fresh isolated test exit compile failed; inspect exit-build.log'}
 }finally{Pop-Location}}
 Copy-Item -LiteralPath $taskNative -Destination "$taskOutput/lib/arm64-v8a/libopenflux.so"
 & "$taskJava/javac.exe" --release 8 -encoding UTF-8 -classpath "$taskSdk/platforms/android-34/android.jar" -d "$taskOutput/classes" "$taskOutput/Validation.java" *> "$taskOutput/javac.log"
 if($LASTEXITCODE){throw 'Validation compile failed'}
 & "$taskJava/jar.exe" cf "$taskOutput/classes.jar" -C "$taskOutput/classes" .
 if($LASTEXITCODE){throw 'Validation class archive failed'}
 & "$taskTools/d8.bat" --release --min-api 29 --lib "$taskSdk/platforms/android-34/android.jar" --output "$taskOutput/dex" "$taskOutput/classes.jar" *> "$taskOutput/d8.log"
 if($LASTEXITCODE){throw 'Validation dex failed'}
 & "$taskTools/aapt.exe" package -f -M "$taskOutput/AndroidManifest.xml" -I "$taskSdk/platforms/android-34/android.jar" -F "$taskOutput/unsigned.apk" *> "$taskOutput/aapt.log"
 if($LASTEXITCODE){throw 'Validation manifest packaging failed'}
 Copy-Item "$taskOutput/dex/classes.dex" "$taskOutput/classes.dex"
 Push-Location $taskOutput
 try{& "$taskTools/aapt.exe" add unsigned.apk classes.dex lib/arm64-v8a/libopenflux.so lib/arm64-v8a/libopenflux_testserver.so *> aapt-add.log;if($LASTEXITCODE){throw 'Validation payload package failed'}}finally{Pop-Location}
 & "$taskTools/zipalign.exe" -f -p 4 "$taskOutput/unsigned.apk" "$taskOutput/aligned.apk"
 if($LASTEXITCODE){throw 'Validation alignment failed'}
 & "$taskTools/apksigner.bat" sign --ks 'C:/Users/dieonfire/.android/debug.keystore' --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$taskOutput/validation.apk" "$taskOutput/aligned.apk"
 if($LASTEXITCODE){throw 'Validation signing failed'}
 $taskSignature=(& "$taskTools/apksigner.bat" verify --print-certs "$taskOutput/validation.apk" 2>&1|Out-String)
 if($LASTEXITCODE -or $taskSignature -notmatch 'certificate SHA-256 digest:\s*fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Validation signature differs'}
 foreach($taskName in $taskSources.Keys){if((Hash "$PSScriptRoot/openflux13-validation/$taskName") -cne $taskSources[$taskName]){throw 'Validation source changed during build'}}
 $taskResult.sourceStable=$true;$taskResult.state='BUILT_NOT_INSTALLED';$taskResult.apk="$taskOutput/validation.apk";$taskResult.apkSha256=Hash $taskResult.apk;$taskResult.fixtureSha256=Hash "$taskOutput/lib/arm64-v8a/libopenflux_testserver.so"
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{
 $env:PATH=$taskOldPath;Remove-Item Env:GOOS,Env:GOARCH,Env:CGO_ENABLED,Env:CC -ErrorAction SilentlyContinue
 [IO.File]::WriteAllText("$taskOutput/receipt.json",($taskResult|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
 Write-Output "OPENFLUX_VALIDATION=$($taskResult.state);RECEIPT=$taskOutput/receipt.json"
}
