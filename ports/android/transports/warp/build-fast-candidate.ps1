param()
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
# This prepares and tests the owned overlay; never edits upstream or production payloads.
& (Join-Path $PSScriptRoot 'build.ps1') -HostTest
$source=Join-Path $root 'work/ports-test-2026-10-14/warp-mobile-build'
foreach($name in @('main.go','main_test.go')){
    if((Get-FileHash (Join-Path $PSScriptRoot $name)).Hash -cne (Get-FileHash (Join-Path $source "cmd/mcrf/$name")).Hash){throw 'CLI input changed during host acceptance'}
}
foreach($name in @('mcrf_fast.go','mcrf_fast_test.go')){
    if((Get-FileHash (Join-Path $PSScriptRoot $name)).Hash -cne (Get-FileHash (Join-Path $source "internal/warpscout/$name")).Hash){throw 'Budget input changed during host acceptance'}
}
$stage=Join-Path $PSScriptRoot ('build/candidate-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage|Out-Null
$ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin'
$go=Join-Path $root 'work/toolchains/go1.26.8/go/bin/go.exe'
$variables=@('PATH','GOOS','GOARCH','GOARM','CGO_ENABLED','CC','GOPATH','GOCACHE','GOTMPDIR')
$saved=@{};foreach($name in $variables){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$rows=@()
Push-Location $source
try {
    $env:PATH=$ndk+';'+$env:PATH;$env:GOOS='android';$env:CGO_ENABLED='1'
    $env:GOPATH=Join-Path $root 'work/toolchains/go-home';$env:GOCACHE=Join-Path $root 'work/toolchains/go-cache-warpscout'
    $env:GOTMPDIR=Join-Path $stage 'tmp';New-Item -ItemType Directory -Path $env:GOTMPDIR -Force|Out-Null
    foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){
        Remove-Item Env:GOARM -ErrorAction SilentlyContinue
        switch($abi){'arm64-v8a'{$env:GOARCH='arm64';$triple='aarch64-linux-android29'}'armeabi-v7a'{$env:GOARCH='arm';$env:GOARM='7';$triple='armv7a-linux-androideabi29'}'x86_64'{$env:GOARCH='amd64';$triple='x86_64-linux-android29'}}
        $env:CC="clang --target=$triple"
        $directory=Join-Path $stage "candidates/$abi";New-Item -ItemType Directory -Path $directory|Out-Null
        $binary=Join-Path $directory 'libwarpscout.so'
        & $go build -trimpath -buildmode=pie '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o $binary ./cmd/mcrf
        if($LASTEXITCODE -ne 0){throw "Android scanner candidate failed: $abi"}
        $elf=(& (Join-Path $ndk 'llvm-readelf.exe') -lW $binary|Out-String)
        if($LASTEXITCODE -ne 0 -or $elf -notmatch 'LOAD.+0x4000'){throw 'Candidate ELF alignment check failed'}
        $rows+=[ordered]@{abi=$abi;kind='warpscout';bytes=(Get-Item $binary).Length;sha256=(Get-FileHash $binary).Hash.ToLowerInvariant()}
    }
    $inputs=@(Get-ChildItem -LiteralPath $PSScriptRoot -File|Where-Object {$_.Extension -in @('.go','.patch')}|ForEach-Object {[ordered]@{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}})
    [IO.File]::WriteAllText((Join-Path $stage 'receipt.json'),([ordered]@{stage='android-warp-fast-candidate';upstream='d35fa797e702cc1e930701568c184aa99459f692';files=$rows;inputs=$inputs;hostTestsPassed=$true;androidDeviceTested=$false;productionPayloadChanged=$false;candidateBudgetQuick=2;candidateBudgetExtended=8;durabilityEchoes=10;jobs=6}|ConvertTo-Json -Depth 5))
    Write-Output "WARP_CANDIDATE=$stage"
}finally{Pop-Location;foreach($name in $variables){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}}
