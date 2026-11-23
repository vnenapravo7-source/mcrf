param([string]$Ndk='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018')
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskProject=[IO.Path]::GetFullPath((Join-Path $taskRoot '../..'))
$taskGo=Join-Path $taskProject 'work/toolchains/go1.26.8/go/bin/go.exe'
$taskStage=Join-Path $PSScriptRoot ('build/fixture-'+[guid]::NewGuid().ToString('N'))
$taskWorker=(Get-Content -LiteralPath "$PSScriptRoot/current-candidate.json" -Raw | ConvertFrom-Json).attempt
New-Item -ItemType Directory -Force "$taskStage/transports","$taskStage/optional-cores" | Out-Null
Copy-Item -LiteralPath "$taskRoot/transports/xray" -Destination "$taskStage/transports/xray" -Recurse
Copy-Item -LiteralPath "$PSScriptRoot/bridge" -Destination "$taskStage/optional-cores/bridge" -Recurse
$taskOldPath=$env:PATH
$env:GOPATH=Join-Path $taskProject 'work/toolchains/go-home'
$env:GOCACHE=Join-Path $PSScriptRoot 'build/go-cache'
$taskFiles=@()
Push-Location "$taskStage/transports/xray"
try {
    $env:GOOS='windows';$env:GOARCH='amd64';$env:CGO_ENABLED='0'
    & $taskGo mod verify
    if($LASTEXITCODE -ne 0){throw 'Fixture pinned dependency check failed'}
    & $taskGo build -trimpath '-ldflags=-s -w -checklinkname=0' -o "$taskStage/optional-fixture.exe" ./testpeer
    if($LASTEXITCODE -ne 0){throw 'Host feature peer build failed'}
    & python "$PSScriptRoot/verify_feature_peer.py" $taskWorker $taskStage
    if($LASTEXITCODE -ne 0){throw 'Feature peer native TCP/UDP contract failed'}
    $taskCompiler=Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
    $env:PATH=$taskCompiler+';'+$taskOldPath;$env:GOOS='android';$env:CGO_ENABLED='1'
    foreach($taskAbi in @('arm64-v8a','armeabi-v7a','x86_64')) {
        Remove-Item Env:GOARM -ErrorAction SilentlyContinue
        switch($taskAbi){'arm64-v8a'{$env:GOARCH='arm64';$taskTriple='aarch64-linux-android29'}'armeabi-v7a'{$env:GOARCH='arm';$env:GOARM='7';$taskTriple='armv7a-linux-androideabi29'}'x86_64'{$env:GOARCH='amd64';$taskTriple='x86_64-linux-android29'}}
        $env:CC="clang --target=$taskTriple"
        $taskOutput="$taskStage/fixtures/$taskAbi/liboptional_fixture.so"
        New-Item -ItemType Directory -Force (Split-Path $taskOutput)|Out-Null
        & $taskGo build -trimpath -buildmode=pie '-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o $taskOutput ./testpeer
        if($LASTEXITCODE -ne 0){throw "Feature peer Android build failed: $taskAbi"}
        $taskFiles+=@{kind='debug-feature-peer';abi=$taskAbi;file=$taskOutput;bytes=(Get-Item $taskOutput).Length;sha256=(Get-FileHash -Algorithm SHA256 $taskOutput).Hash.ToLowerInvariant()}
    }
    @{stage='debug-only-native-peer';workerReceipt="$taskWorker/receipt.json";androidDeviceTested=$false;files=$taskFiles} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$taskStage/receipt.json" -Encoding utf8
    & python "$PSScriptRoot/verify_candidates.py" "$taskStage/receipt.json"
    if($LASTEXITCODE -ne 0){throw 'Feature peer ELF check failed'}
    @{attempt=$taskStage;receipt="$taskStage/receipt.json"}|ConvertTo-Json|Set-Content -LiteralPath "$PSScriptRoot/current-fixtures.json" -Encoding utf8
    Write-Output "Debug fixture receipt: $taskStage/receipt.json"
} finally {Pop-Location;$env:PATH=$taskOldPath;Remove-Item Env:GOOS,Env:GOARCH,Env:GOARM,Env:CGO_ENABLED,Env:CC -ErrorAction SilentlyContinue}
