param([string]$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018')
$ErrorActionPreference='Stop'
$taskProject=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskGo=Join-Path $taskProject 'work/toolchains/go1.26.8/go/bin/go.exe'
$taskCompiler=Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
if(!(Test-Path -LiteralPath "$taskCompiler/clang.exe")){throw 'Existing Android NDK compiler required; no automatic SDK installation'}
$taskLock=(Get-Content -LiteralPath "$PSScriptRoot/transports/transport-lock.json" -Raw|ConvertFrom-Json).dnstt
$taskStage=Join-Path $PSScriptRoot ('dnstt-fixtures/build/peer-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$taskStage/tmp" -Force|Out-Null
$taskSaved=@{}
foreach($taskName in @('GOPATH','GOCACHE','GOTOOLCHAIN','GOTMPDIR','GOOS','GOARCH','GOARM','CGO_ENABLED','CC','PATH')){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
try{
    $env:GOPATH=Join-Path $taskProject 'work/toolchains/go-home'
    $env:GOCACHE=Join-Path $PSScriptRoot 'optional-cores/build/go-cache'
    $env:GOTOOLCHAIN='local';$env:GOTMPDIR="$taskStage/tmp"
    $taskModule=(& $taskGo mod download -json "$($taskLock.module)@$($taskLock.version)"|Out-String|ConvertFrom-Json)
    if($LASTEXITCODE -ne 0 -or $taskModule.Sum -cne $taskLock.sum){throw 'DNSTT module checksum does not match project lock'}
    $taskFiles=@()
    Push-Location $taskModule.Dir
    try{
        & $taskGo mod verify
        if($LASTEXITCODE -ne 0){throw 'DNSTT source/dependency verification failed'}
        $env:PATH=$taskCompiler+';'+$taskSaved['PATH'];$env:GOOS='android';$env:CGO_ENABLED='1'
        foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')){
            [Environment]::SetEnvironmentVariable('GOARM',$null,'Process')
            switch($taskAbi){
                'arm64-v8a'{$env:GOARCH='arm64';$taskTriple='aarch64-linux-android29'}
                'armeabi-v7a'{$env:GOARCH='arm';$env:GOARM='7';$taskTriple='armv7a-linux-androideabi29'}
                'x86_64'{$env:GOARCH='amd64';$taskTriple='x86_64-linux-android29'}
            }
            $env:CC="clang --target=$taskTriple"
            $taskOutput="$taskStage/fixtures/$taskAbi/libdnstt_testserver.so"
            New-Item -ItemType Directory -Path (Split-Path $taskOutput) -Force|Out-Null
            & $taskGo build -mod=readonly -trimpath -buildmode=pie '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o $taskOutput ./dnstt-server
            if($LASTEXITCODE -ne 0){throw "DNSTT Android test peer build failed: $taskAbi"}
            $taskFiles+=@{abi=$taskAbi;kind='dnstt-test-peer';bytes=(Get-Item -LiteralPath $taskOutput).Length;sha256=(Get-FileHash -LiteralPath $taskOutput -Algorithm SHA256).Hash.ToLowerInvariant()}
        }
    }finally{Pop-Location}
    $taskReceipt=@{stage='debug-only-dnstt-peer';module=$taskLock.module;version=$taskLock.version;moduleSum=$taskLock.sum;files=$taskFiles;androidDeviceTested=$false;scope='Only androidTest JNI input, never base APK or engine release payload'}
    [IO.File]::WriteAllText("$taskStage/receipt.json",($taskReceipt|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
    Write-Output "DNSTT_FIXTURE_STAGE=$taskStage"
}finally{
    foreach($taskName in $taskSaved.Keys){[Environment]::SetEnvironmentVariable($taskName,$taskSaved[$taskName],'Process')}
}
