param([Parameter(Mandatory)][string]$Inputs,[Parameter(Mandatory)][string]$PerlInputs,[Parameter(Mandatory)][string]$PerlMakeMakerInputs,[Parameter(Mandatory)][string]$PerlPodInputs,[Parameter(Mandatory)][string]$PkgconfInputs,[string]$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-build
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
$taskInputs=(Resolve-Path -LiteralPath $Inputs).Path
if($taskInputs.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-slipstream-inputs-[a-f0-9]{32}$' -or (Get-Item -LiteralPath $taskInputs).LinkType){throw 'Exact owned frozen Slipstream input stage required'}
$taskProof=Get-Content "$taskInputs/receipt.json" -Raw|ConvertFrom-Json
if($taskProof.state -cne 'INPUTS_PREPARED_NOT_COMPILED' -or $taskProof.lockSha256 -cne (Hash "$PSScriptRoot/dependencies.lock.json") -or $taskProof.manifestSha256 -cne (Hash "$taskInputs/SOURCE_MANIFEST.json")){throw 'Pinned Slipstream C inputs changed'}
$taskManifest=@(Get-Content "$taskInputs/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
function Assert-Inputs{
 if(@(Get-ChildItem -LiteralPath $taskInputs -Recurse -Force|Where-Object {$_.LinkType}).Count){throw 'Linked frozen C input'}
 $taskFiles=@(Get-ChildItem -LiteralPath $taskInputs -Recurse -File -Force|Where-Object {$_.FullName -notmatch '[/\\]\.git[/\\]' -and $_.Name -ne '.git' -and $_.FullName -ine [IO.Path]::GetFullPath("$taskInputs/SOURCE_MANIFEST.json") -and $_.FullName -ine [IO.Path]::GetFullPath("$taskInputs/receipt.json")})
 if($taskFiles.Count -ne $taskManifest.Count){throw 'Frozen C source/tool input set changed'}
 foreach($taskRow in $taskManifest){
  if([IO.Path]::IsPathRooted($taskRow.path) -or '..' -in ($taskRow.path -split '[/\\]')){throw 'Unsafe C input manifest path'}
  $taskFrom=Join-Path $taskInputs $taskRow.path
  if((Get-Item -LiteralPath $taskFrom).LinkType -or (Hash $taskFrom) -cne $taskRow.sha256){throw 'Frozen C source/tool input changed'}
 }
}
Assert-Inputs
$taskPerlInputs=(Resolve-Path $PerlInputs).Path.Replace([char]92,[char]47)
if($taskPerlInputs -cnotmatch '^D:/MCRF/Builds/Android/p3-slipstream-perl-inputs-[a-f0-9]{32}$'){throw 'Owned isolated Perl input required'}
$taskPerlProof=Get-Content "$taskPerlInputs/receipt.json" -Raw|ConvertFrom-Json
if($taskPerlProof.state -cne 'PERL_SOURCE_PREPARED_NOT_EXECUTED' -or $taskPerlProof.archiveSha256 -cne 'b009ff51f4fb108d19961a523e99b4373ccf958d37ca35bf1583215908dca9a9' -or $taskPerlProof.manifestSha256 -cne (Hash "$taskPerlInputs/SOURCE_MANIFEST.json") -or $taskPerlProof.lib -cne "$taskPerlInputs/source/Locale-Maketext-Simple-0.21/lib"){throw 'Exact official Perl source proof required'}
$taskPerlRows=@(Get-Content "$taskPerlInputs/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
if(@(Get-ChildItem "$taskPerlInputs/source" -Recurse -Force|Where-Object {$_.LinkType}).Count -or @(Get-ChildItem "$taskPerlInputs/source" -Recurse -File).Count -ne $taskPerlRows.Count){throw 'Frozen Perl source closure changed'}
foreach($row in $taskPerlRows){if([IO.Path]::IsPathRooted($row.path) -or '..' -in ($row.path -split '[/\\]') -or (Hash "$taskPerlInputs/source/$($row.path)") -cne $row.sha256){throw 'Frozen Perl source changed'}}
$taskMakeMakerInputs=(Resolve-Path $PerlMakeMakerInputs).Path.Replace([char]92,[char]47)
if($taskMakeMakerInputs -cnotmatch '^D:/MCRF/Builds/Android/p3-slipstream-perl-inputs-[a-f0-9]{32}$'){throw 'Owned isolated MakeMaker input required'}
$taskMakeMakerProof=Get-Content "$taskMakeMakerInputs/receipt.json" -Raw|ConvertFrom-Json
if($taskMakeMakerProof.state -cne 'PERL_SOURCE_PREPARED_NOT_EXECUTED' -or $taskMakeMakerProof.archiveSha256 -cne '43b33c20f8d82dba7cc48f8cd702f8fc9811e9d07880886dfd31b7077bd4a3a6' -or $taskMakeMakerProof.manifestSha256 -cne (Hash "$taskMakeMakerInputs/SOURCE_MANIFEST.json") -or $taskMakeMakerProof.lib -cne "$taskMakeMakerInputs/source/ExtUtils-MakeMaker-7.78/lib"){throw 'Exact official MakeMaker source proof required'}
$taskMakeMakerRows=@(Get-Content "$taskMakeMakerInputs/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
if(@(Get-ChildItem "$taskMakeMakerInputs/source" -Recurse -Force|Where-Object {$_.LinkType}).Count -or @(Get-ChildItem "$taskMakeMakerInputs/source" -Recurse -File).Count -ne $taskMakeMakerRows.Count){throw 'Frozen MakeMaker closure changed'}
foreach($row in $taskMakeMakerRows){if([IO.Path]::IsPathRooted($row.path) -or '..' -in ($row.path -split '[/\\]') -or (Hash "$taskMakeMakerInputs/source/$($row.path)") -cne $row.sha256){throw 'Frozen MakeMaker source changed'}}
$taskPodInputs=(Resolve-Path $PerlPodInputs).Path.Replace([char]92,[char]47)
if($taskPodInputs -cnotmatch '^D:/MCRF/Builds/Android/p3-slipstream-perl-pod-inputs-[a-f0-9]{32}$'){throw 'Owned isolated POD input required'}
$taskPodProof=Get-Content "$taskPodInputs/receipt.json" -Raw|ConvertFrom-Json
$taskPodPins=@{'Pod-Usage-2.05'='100c27908757c56ebfeca8b7bf15a9867e449df663ff013de3855d183dfbea30';'podlators-v6.1.1'='709a7dc4a2259683eb2f89e085ec70cf743710d950510e489ec6ddca3eb80c68';'Pod-Simple-3.48'='3297cf3c078de9d8297942423ec6ab59e85e30dfb38b782242699e386727c63a';'Pod-Escapes-1.07'='dbf7c827984951fb248907f940fd8f19f2696bc5545c0a15287e0fbe56a52308';'Pod-Perldoc-3.28'='cc41e605b8e13c40a8ee6504ff46347b5ba7fbd92203b3bb055422051befc64d'}
if($taskPodProof.state -cne 'PERL_POD_SOURCE_PREPARED_NOT_EXECUTED' -or $taskPodProof.manifestSha256 -cne (Hash "$taskPodInputs/SOURCE_MANIFEST.json") -or @($taskPodProof.packages).Count -ne 5 -or @($taskPodProof.packages.name|Select-Object -Unique).Count -ne 5){throw 'Exact complete official POD sources required'}
foreach($package in $taskPodProof.packages){if(!$taskPodPins.ContainsKey($package.name) -or $taskPodPins[$package.name] -cne $package.sha256){throw 'POD release pin changed'}}
$taskPodRows=@(Get-Content "$taskPodInputs/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
if(@(Get-ChildItem "$taskPodInputs/source" -Recurse -Force|Where-Object {$_.LinkType}).Count -or @(Get-ChildItem "$taskPodInputs/source" -Recurse -File).Count -ne $taskPodRows.Count){throw 'POD source closure changed'}
foreach($row in $taskPodRows){if([IO.Path]::IsPathRooted($row.path) -or '..' -in ($row.path -split '[/\\]') -or (Hash "$taskPodInputs/source/$($row.path)") -cne $row.sha256){throw 'Frozen POD source changed'}}
$taskPkgInputs=(Resolve-Path $PkgconfInputs).Path.Replace([char]92,[char]47)
if($taskPkgInputs -cnotmatch '^D:/MCRF/Builds/Android/p3-slipstream-pkgconf-inputs-[a-f0-9]{32}$'){throw 'Owned pkgconf input required'}
$taskPkgProof=Get-Content "$taskPkgInputs/receipt.json" -Raw|ConvertFrom-Json
if($taskPkgProof.state -cne 'PKGCONF_INPUT_PREPARED_NOT_EXECUTED' -or $taskPkgProof.archiveSha256 -cne '37b97f372409fa7cf54d709bab892f34e9c62d2658230a09f783a89a55ae590e' -or $taskPkgProof.manifestSha256 -cne (Hash "$taskPkgInputs/SOURCE_MANIFEST.json")){throw 'Exact official pkgconf proof required'}
$taskPkgRows=@(Get-Content "$taskPkgInputs/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
if(@(Get-ChildItem "$taskPkgInputs/tools" -Recurse -Force|Where-Object {$_.LinkType}).Count -or @(Get-ChildItem "$taskPkgInputs/tools" -Recurse -File -Force).Count -ne $taskPkgRows.Count){throw 'Pkgconf closure changed'}
foreach($row in $taskPkgRows){if([IO.Path]::IsPathRooted($row.path) -or '..' -in ($row.path -split '[/\\]') -or (Hash "$taskPkgInputs/tools/$($row.path)") -cne $row.sha256){throw 'Frozen pkgconf input changed'}}
$taskStage="D:/MCRF/Builds/Android/p3-slipstream-cdeps-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $taskStage|Out-Null
$taskNdkTools="$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin"
$taskCmake='C:/Users/dieonfire/AppData/Local/Android/Sdk/cmake/3.22.1/bin/cmake.exe'
$taskNinja='C:/Users/dieonfire/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe'
$taskMake="$taskInputs/tools/mingw64/bin/mingw32-make.exe";$taskPerl='C:/Program Files/Git/usr/bin/perl.exe'
$taskShell='C:/Program Files/Git/usr/bin/sh.exe'
$taskPkgconf="$taskPkgInputs/tools/mingw64/bin/pkgconf.exe"
foreach($taskTool in @($taskCmake,$taskNinja,$taskMake,$taskPerl,$taskShell,"$taskNdkTools/clang.exe")){if(!(Test-Path -LiteralPath $taskTool -PathType Leaf)){throw 'Existing pinned build prerequisite missing'}}
$taskNames=@('PATH','CC','AR','RANLIB','CROSS_COMPILE','ANDROID_NDK_ROOT','ANDROID_NDK_HOME','MSYS2_ARG_CONV_EXCL','MSYS2_ENV_CONV_EXCL','OPENSSL_CONF','PERL5LIB');$taskSaved=@{}
foreach($taskName in $taskNames){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
$taskReceipt=@{state='BUILDING';inputReceiptSha256=Hash "$taskInputs/receipt.json";inputManifestSha256=$taskProof.manifestSha256;lockSha256=$taskProof.lockSha256;artifacts=@();compiled=$false;executed=$false;systemInstalled=$false}
$taskReceipt.tools=@{};foreach($taskTool in @($taskCmake,$taskNinja,$taskMake,$taskPerl,$taskShell,"$taskNdkTools/clang.exe")){$taskReceipt.tools[[IO.Path]::GetFileName($taskTool)]=Hash $taskTool}
$taskReceipt.perlInputReceiptSha256=Hash "$taskPerlInputs/receipt.json"
$taskReceipt.makeMakerInputReceiptSha256=Hash "$taskMakeMakerInputs/receipt.json"
$taskReceipt.podInputReceiptSha256=Hash "$taskPodInputs/receipt.json"
$taskReceipt.pkgconfInputReceiptSha256=Hash "$taskPkgInputs/receipt.json";$taskReceipt.tools['pkgconf.exe']=Hash $taskPkgconf
try{
 $env:PATH="$taskPkgInputs/tools/mingw64/bin;$taskInputs/tools/mingw64/bin;$taskNdkTools;C:/Program Files/Git/usr/bin;"+$taskSaved.PATH
 $env:ANDROID_NDK_ROOT=$Ndk;$env:ANDROID_NDK_HOME=$Ndk;$env:MSYS2_ARG_CONV_EXCL='*';[Environment]::SetEnvironmentVariable('OPENSSL_CONF',$null,'Process')
 $env:PERL5LIB='/d'+$taskPerlProof.lib.Substring(2)+':/d'+$taskMakeMakerProof.lib.Substring(2)
 foreach($name in ($taskPodPins.Keys|Sort-Object)){$env:PERL5LIB+=':/d'+$taskPodInputs.Substring(2)+"/source/$name/lib"}
 # MSYS sh otherwise converts the colon-separated Unix module path to a
 # Windows semicolon list when spawning a native sub-make; MSYS Perl then
 # cannot resolve any of the same modules that Configure just loaded.
 $env:MSYS2_ENV_CONV_EXCL=(@($taskSaved.MSYS2_ENV_CONV_EXCL,'PERL5LIB')|Where-Object {$_}) -join ';'
 [Environment]::SetEnvironmentVariable('CROSS_COMPILE',$null,'Process')
 foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
  switch($taskAbi){
   # Same generic target mapping as pinned openssl-src/src/lib.rs:303ff.
   # Android builtin autodetection overrides the explicitly selected compiler
   # and falls back to obsolete GCC under Windows/MSYS. The real NDK target
   # triple/API remains explicit on CC; no host compiler is substituted.
   'arm64-v8a'{$taskOpenSSLTarget='linux-aarch64';$taskTriple='aarch64-linux-android29'}
   'armeabi-v7a'{$taskOpenSSLTarget='linux-armv4';$taskTriple='armv7a-linux-androideabi29'}
   'x86_64'{$taskOpenSSLTarget='linux-x86_64';$taskTriple='x86_64-linux-android29'}
  }
  $taskRoot="$taskStage/$taskAbi";$taskSSL="$taskRoot/openssl";$taskSSLSource="$taskRoot/openssl-source"
  New-Item -ItemType Directory -Path "$taskRoot/lib",$taskSSLSource|Out-Null
  $taskOriginal="$taskInputs/openssl-crate/openssl-src-300.6.0+3.6.2/openssl"
  foreach($taskChild in Get-ChildItem -LiteralPath $taskOriginal -Force){Copy-Item -LiteralPath $taskChild.FullName -Destination $taskSSLSource -Recurse}
  $env:CC="clang.exe --target=$taskTriple";$env:AR='llvm-ar.exe';$env:RANLIB='llvm-ranlib.exe'
  Push-Location $taskSSLSource
  try{
   & $taskPerl Configure $taskOpenSSLTarget '-D__ANDROID_API__=29' '-fPIC' "--prefix=$taskSSL" "--openssldir=$taskSSL/ssl" '--libdir=lib' no-shared no-tests no-module no-docs *> "$taskRoot/openssl-configure.log"
   if($LASTEXITCODE){throw "OpenSSL configure failed: $taskAbi"}
   & $taskMake -j4 "SHELL=$taskShell" build_libs *> "$taskRoot/openssl-build.log";if($LASTEXITCODE){throw "OpenSSL static build failed: $taskAbi"}
   & $taskMake "SHELL=$taskShell" install_dev *> "$taskRoot/openssl-install.log";if($LASTEXITCODE){throw "OpenSSL development staging failed: $taskAbi"}
  }finally{Pop-Location}
  $taskBase=@('-G','Ninja',"-DPKG_CONFIG_EXECUTABLE=$taskPkgconf","-DCMAKE_MAKE_PROGRAM=$taskNinja","-DCMAKE_TOOLCHAIN_FILE=$Ndk/build/cmake/android.toolchain.cmake",'-DANDROID_PLATFORM=android-29',"-DANDROID_ABI=$taskAbi",'-DCMAKE_BUILD_TYPE=Release','-DCMAKE_POSITION_INDEPENDENT_CODE=ON','-DCMAKE_POLICY_VERSION_MINIMUM=3.5',"-DOPENSSL_ROOT_DIR=$taskSSL", "-DOPENSSL_INCLUDE_DIR=$taskSSL/include","-DOPENSSL_CRYPTO_LIBRARY=$taskSSL/lib/libcrypto.a","-DOPENSSL_SSL_LIBRARY=$taskSSL/lib/libssl.a",'-DOPENSSL_USE_STATIC_LIBS=TRUE')
  & $taskCmake -S "$taskInputs/picotls" -B "$taskRoot/picotls-build" @taskBase '-DWITH_FUSION=OFF' '-DWITH_AEGIS=OFF' '-DWITH_MBEDTLS=OFF' '-DWITH_DTRACE=OFF' *> "$taskRoot/picotls-configure.log"
  if($LASTEXITCODE){throw "Picotls configure failed: $taskAbi"}
  & $taskCmake --build "$taskRoot/picotls-build" --target picotls-core picotls-openssl picotls-minicrypto --parallel 4 *> "$taskRoot/picotls-build.log";if($LASTEXITCODE){throw "Picotls static build failed: $taskAbi"}
  foreach($taskName in @('libpicotls-core.a','libpicotls-openssl.a','libpicotls-minicrypto.a')){Copy-Item -LiteralPath "$taskRoot/picotls-build/$taskName" -Destination "$taskRoot/lib/$taskName"}
  $taskPTLS=@("-DPTLS_INCLUDE_DIR=$taskInputs/picotls/include","-DPTLS_CORE_LIBRARY=$taskRoot/lib/libpicotls-core.a","-DPTLS_OPENSSL_LIBRARY=$taskRoot/lib/libpicotls-openssl.a","-DPTLS_MINICRYPTO_LIBRARY=$taskRoot/lib/libpicotls-minicrypto.a")
  & $taskCmake -S "$taskInputs/picoquic" -B "$taskRoot/picoquic-build" @taskBase @taskPTLS '-DPICOQUIC_FETCH_PTLS=OFF' '-DPTLS_WITH_FUSION=OFF' '-DWITH_FUSION=OFF' '-DBUILD_DEMO=OFF' '-DBUILD_HTTP=OFF' '-DBUILD_LOGLIB=OFF' '-DBUILD_LOGREADER=OFF' '-Dpicoquic_BUILD_TESTS=OFF' *> "$taskRoot/picoquic-configure.log"
  if($LASTEXITCODE){throw "Picoquic configure failed: $taskAbi"}
  & $taskCmake --build "$taskRoot/picoquic-build" --target picoquic-core --parallel 4 *> "$taskRoot/picoquic-build.log";if($LASTEXITCODE){throw "Picoquic static build failed: $taskAbi"}
  Copy-Item -LiteralPath "$taskRoot/picoquic-build/libpicoquic-core.a" -Destination "$taskRoot/lib/libpicoquic-core.a"
  foreach($taskFile in @('lib/libpicoquic-core.a','lib/libpicotls-core.a','lib/libpicotls-openssl.a','lib/libpicotls-minicrypto.a','openssl/lib/libcrypto.a','openssl/lib/libssl.a')){
   $taskArtifact="$taskRoot/$taskFile";if((Get-Item -LiteralPath $taskArtifact).Length -lt 8){throw 'Static dependency archive is empty'}
   $taskReceipt.artifacts+=@{abi=$taskAbi;file="$taskAbi/$taskFile";sha256=Hash $taskArtifact;bytes=(Get-Item -LiteralPath $taskArtifact).Length}
  }
  foreach($taskHeader in Get-ChildItem -LiteralPath "$taskSSL/include" -Recurse -File){
   if($taskHeader.LinkType){throw 'Regular generated OpenSSL header required'}
   $taskRelative=[IO.Path]::GetRelativePath($taskStage,$taskHeader.FullName).Replace([char]92,[char]47)
   $taskReceipt.artifacts+=@{abi=$taskAbi;file=$taskRelative;sha256=Hash $taskHeader.FullName;bytes=$taskHeader.Length;generatedHeader=$true}
  }
 }
 Assert-Inputs;$taskReceipt.compiled=$true;$taskReceipt.state='CDEPS_COMPILED_NOT_EXECUTED'
 foreach($row in $taskPerlRows){if((Hash "$taskPerlInputs/source/$($row.path)") -cne $row.sha256){throw 'Perl input changed during compilation'}}
 foreach($row in $taskMakeMakerRows){if((Hash "$taskMakeMakerInputs/source/$($row.path)") -cne $row.sha256){throw 'MakeMaker input changed during compilation'}}
 foreach($row in $taskPodRows){if((Hash "$taskPodInputs/source/$($row.path)") -cne $row.sha256){throw 'POD input changed during compilation'}}
 foreach($row in $taskPkgRows){if((Hash "$taskPkgInputs/tools/$($row.path)") -cne $row.sha256){throw 'Pkgconf input changed during compilation'}}
}catch{$taskReceipt.state='FAILED_PRESERVED';$taskReceipt.failure=$_.Exception.Message;throw}
finally{foreach($taskName in $taskNames){[Environment]::SetEnvironmentVariable($taskName,$taskSaved[$taskName],'Process')};[IO.File]::WriteAllText("$taskStage/receipt.json",($taskReceipt|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false));Write-Output "P3_SLIPSTREAM_CDEPS=$taskStage"}
