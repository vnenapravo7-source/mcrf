param([string]$ReviewedSource='')
$ErrorActionPreference='Stop'
$stage='D:/MCRF/Builds/Android/s3bypass-native-ecb18766cf934dc18458e0af616f3f37'
$source=Join-Path $stage 'source';$receipt=Get-Content -Raw (Join-Path $stage 'receipt.json')|ConvertFrom-Json
if(!$receipt.sourceStable -or !$receipt.producerComplete -or $receipt.commit -cne 'b54ed13e3d7473ad8c25b5c39dfe127b004db540'){throw 'Existing frozen pinned S3 source required'}
if(Get-Process go -ErrorAction SilentlyContinue){throw 'Existing Go producer; do not duplicate'}
$inputs=Get-Content -Raw (Join-Path $stage 'source-manifest.json')|ConvertFrom-Json
$lifecycleDisabled=$false;$reviewedHash=$null
if($ReviewedSource){
    $reviewedRoot=[IO.Path]::GetFullPath($ReviewedSource)
    if($reviewedRoot -cne 'D:\MCRF\Builds\Android\s3bypass-linux-source-nolifecycle-20261017' -or (Get-Item $reviewedRoot).LinkType){throw 'Exact reviewed private server source required'}
    $relative='proxy/fedarisha/inbound.go'
    $original=(Get-Content -Raw (Join-Path $source $relative)) -replace '\r\n',"`n"
    $call="`tregisterLifecycle(s.ctx, s.tag, s.config.GetStorage(), store)"
    if(([regex]::Matches($original,[regex]::Escape($call))).Count -ne 1){throw 'Pinned lifecycle call boundary changed'}
    $expected=$original.Replace($call,"`t// MCRF owned server: bucket lifecycle remains under its owner's control.")
    $reviewed=(Get-Content -Raw (Join-Path "$reviewedRoot/source" $relative)) -replace '\r\n',"`n"
    if($reviewed -cne $expected){throw 'Server source has an unreviewed change'}
    $reviewedHash=(Get-FileHash (Join-Path "$reviewedRoot/source" $relative)).Hash.ToLowerInvariant()
    $source=Join-Path $reviewedRoot 'source';$lifecycleDisabled=$true
}
function VerifySource {
    foreach($input in $inputs){
        $path=[IO.Path]::GetFullPath((Join-Path $source $input.path))
        $expectedHash=if($lifecycleDisabled -and $input.path.Replace('\','/') -ceq 'proxy/fedarisha/inbound.go'){$reviewedHash}else{$input.sha256}
        if(!$path.StartsWith($source+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Get-Item $path).LinkType -or (Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $expectedHash){throw 'Frozen source changed'}
    }
}
VerifySource
$output=Join-Path D:/MCRF/Builds/Android ('s3bypass-linux-server-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $output|Out-Null
$controlSha=(Get-FileHash $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant();Copy-Item $PSCommandPath (Join-Path $output 'producer-control.ps1')
$saved=@{};foreach($name in @('GOOS','GOARCH','CGO_ENABLED','GOTOOLCHAIN','GOENV','GOWORK','GOFLAGS','GOEXPERIMENT','GOROOT','GOCACHE','GOPATH','GOMODCACHE')){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$state='FAILED_PRESERVED'
try {
    $env:GOOS='linux';$env:GOARCH='amd64';$env:CGO_ENABLED='0';$env:GOTOOLCHAIN='local';$env:GOENV='off';$env:GOWORK='off';$env:GOFLAGS='';$env:GOEXPERIMENT='';Remove-Item Env:GOROOT -ErrorAction SilentlyContinue
    $env:GOCACHE='D:/MCRF/Projects/MCRF/work/toolchains/go-cache';$env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-path';$env:GOMODCACHE=Join-Path $env:GOPATH 'pkg/mod'
    Push-Location $source
    try{& D:/MCRF/Projects/MCRF/work/toolchains/go1.27.2/go/bin/go.exe build -mod=readonly -trimpath -buildvcs=false '-ldflags=-s -w' -o (Join-Path $output 's3bypass') ./main 2>&1|Tee-Object -FilePath (Join-Path $output 'build.log');if($LASTEXITCODE){throw 'Linux server build failed; preserve attempt'}}finally{Pop-Location}
    VerifySource
    if((Get-FileHash $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $controlSha){throw 'Producer changed'}
    $binary=Get-Item (Join-Path $output 's3bypass');$sha=(Get-FileHash $binary.FullName -Algorithm SHA256).Hash.ToLowerInvariant();$state='PASS'
}finally {
    foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
    [ordered]@{state=$state;source=$source;commit=$receipt.commit;sourceStable=($state -eq 'PASS');sourceInputs=$inputs.Count;target='linux/amd64';sha256=$sha;bytes=if($binary){$binary.Length}else{$null};bucketLifecycleMutationDisabled=$lifecycleDisabled;reviewedChange=if($lifecycleDisabled){'Remove exactly one inbound registerLifecycle call, no transport change'}else{$null};apkBuilt=$false;testsRepeated=$false;remoteInstalled=$false;autoDeployFeatureChanged=$false}|ConvertTo-Json|Set-Content (Join-Path $output 'receipt.json') -Encoding utf8
    Write-Output "S3_LINUX_SERVER=$state;STAGE=$output"
}
