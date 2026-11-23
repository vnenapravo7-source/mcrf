param([string]$Ndk='', [switch]$TestServer)
$ErrorActionPreference='Stop'
$taskProject=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskLock=Get-Content (Join-Path $PSScriptRoot 'transport-lock.json') -Raw | ConvertFrom-Json
$taskCache=Join-Path $taskProject 'work/platform-dependencies/android-transports'
$taskGo=Join-Path $taskProject 'work/toolchains/go1.26.8/go/bin/go.exe'
$env:GOPATH=Join-Path $taskProject 'work/toolchains/go-home'
$taskLibs=Join-Path $PSScriptRoot '../engines/src/main/jniLibs'
$taskNotices=Join-Path $PSScriptRoot '../engines/src/main/assets/licenses'
New-Item -ItemType Directory -Path $taskCache,$taskNotices -Force | Out-Null
foreach($taskArchive in $taskLock.archives) {
    $taskFile=Join-Path $taskCache $taskArchive.name
    if(!(Test-Path -LiteralPath $taskFile)){Invoke-WebRequest "https://github.com/$($taskLock.repository)/releases/download/$($taskLock.tag)/$($taskArchive.name)" -OutFile $taskFile}
    if((Get-FileHash -LiteralPath $taskFile).Hash.ToLowerInvariant() -ne $taskArchive.sha256){throw 'Transport archive checksum mismatch'}
    $taskAllowed=@('CSQTT-LICENSE','OLCRTC-LICENSE','OpenFlux-LICENSE','qWDTT-LICENSE','SHA256SUMS','SOURCES.txt','libcsqtt_transport.so','libolcrtc.so','libopenflux.so','libqwdtt.so')
    $taskNames=@(& tar -tzf $taskFile);if($LASTEXITCODE -ne 0 -or $taskNames.Count -ne $taskAllowed.Count){throw 'Invalid transport archive'}
    foreach($taskName in $taskNames){if($taskName -notin $taskAllowed){throw 'Unexpected archive path'}}
    $taskExtract=Join-Path $taskCache $taskArchive.abi
    New-Item -ItemType Directory -Path $taskExtract -Force | Out-Null
    & tar -xzf $taskFile -C $taskExtract;if($LASTEXITCODE -ne 0){throw 'Transport extraction failed'}
    foreach($taskLine in Get-Content (Join-Path $taskExtract 'SHA256SUMS')){
        $taskPair=$taskLine -split '\s+',2
        if($taskPair[1] -notin $taskAllowed -or (Get-FileHash -LiteralPath (Join-Path $taskExtract $taskPair[1])).Hash.ToLowerInvariant() -ne $taskPair[0]){throw 'Native transport checksum mismatch'}
    }
    $taskDestination=Join-Path $taskLibs $taskArchive.abi;New-Item -ItemType Directory -Path $taskDestination -Force | Out-Null
    foreach($taskName in @('libolcrtc.so','libopenflux.so','libqwdtt.so','libcsqtt_transport.so')){Copy-Item -LiteralPath (Join-Path $taskExtract $taskName) -Destination $taskDestination -Force}
    foreach($taskName in @('OLCRTC-LICENSE','OpenFlux-LICENSE','qWDTT-LICENSE','CSQTT-LICENSE','SOURCES.txt')){Copy-Item -LiteralPath (Join-Path $taskExtract $taskName) -Destination $taskNotices -Force}
}
if(!$Ndk){$Ndk=Join-Path $taskProject 'work/toolchains/ndk-r27/android-ndk-r27'}
$taskCompiler=Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
if(!(Test-Path -LiteralPath (Join-Path $taskCompiler 'clang.exe'))){throw 'Android NDK r27 is required to build DNSTT'}
$taskModule=(& $taskGo mod download -json "$($taskLock.dnstt.module)@$($taskLock.dnstt.version)" | Out-String | ConvertFrom-Json)
if($LASTEXITCODE -ne 0 -or $taskModule.Sum -ne $taskLock.dnstt.sum){throw 'DNSTT source checksum mismatch'}
$taskSource=Join-Path $taskProject 'work/platform-dependencies/dnstt-mobile-v1.20260501.0'
New-Item -ItemType Directory -Path $taskSource -Force | Out-Null
Get-ChildItem -LiteralPath $taskModule.Dir | Copy-Item -Destination $taskSource -Recurse -Force
Get-ChildItem -LiteralPath $taskSource -Recurse -File | ForEach-Object { $_.IsReadOnly=$false }
& "$PSScriptRoot/dnstt/Apply-Adapter.ps1" -Module $taskSource
$taskOldPath=$env:PATH;$env:PATH=$taskCompiler+';'+$taskOldPath
$env:GOOS='android';$env:CGO_ENABLED='1'
try {
    Push-Location $taskSource
    foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
        switch($taskAbi){'arm64-v8a'{$env:GOARCH='arm64';$taskTriple='aarch64-linux-android29'}'armeabi-v7a'{$env:GOARCH='arm';$env:GOARM='7';$taskTriple='armv7a-linux-androideabi29'}'x86_64'{$env:GOARCH='amd64';$taskTriple='x86_64-linux-android29'}}
        $env:CC="clang --target=$taskTriple"
        & $taskGo build -trimpath -buildmode=pie '-ldflags=-s -w' -o (Join-Path $taskLibs "$taskAbi/libdnstt.so") ./dnstt-client
        if($LASTEXITCODE -ne 0){throw "DNSTT build failed: $taskAbi"}
        if($TestServer){$taskDebug=Join-Path $PSScriptRoot "../engines/src/debug/jniLibs/$taskAbi";New-Item -ItemType Directory -Path $taskDebug -Force | Out-Null;& $taskGo build -trimpath -buildmode=pie '-ldflags=-s -w' -o (Join-Path $taskDebug 'libdnstt_testserver.so') ./dnstt-server;if($LASTEXITCODE -ne 0){throw 'DNSTT fixture server build failed'}}
    }
}finally{Pop-Location;$env:PATH=$taskOldPath;Remove-Item Env:GOOS,Env:GOARCH,Env:CGO_ENABLED,Env:CC -ErrorAction SilentlyContinue}
Copy-Item -LiteralPath (Join-Path $taskSource 'COPYING') -Destination (Join-Path $taskNotices 'DNSTT-LICENSE') -Force
# The maintained bridge uses OpenFlux's mobile API, including strict negotiation,
# named transports, vyandex/boards and stream-mode links.
& (Join-Path $PSScriptRoot 'openflux/build.ps1') -Ndk $Ndk -TestServer:$TestServer
if($LASTEXITCODE -ne 0){throw 'OpenFlux bridge build failed'}
& (Join-Path $PSScriptRoot 'swg/build.ps1') -Ndk $Ndk
if($LASTEXITCODE -ne 0){throw 'SWG bridge build failed'}
