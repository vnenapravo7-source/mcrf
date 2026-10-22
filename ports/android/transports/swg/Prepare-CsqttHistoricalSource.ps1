param([Parameter(Mandatory)][ValidateSet('2.0','2.1.5')][string]$Generation,[string]$RetainedDependencyStage='')
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$sourceRepo='https://github.com/amurcanov/csqtt.git'
$revision=if($Generation -ceq '2.0'){'2ff8a1a4fbe031fb76b799e172dce25cfe1ca942'}else{'de7afc23cec7481f98845bf24fc8943af19b4cf8'}
$engine=if($Generation -ceq '2.0'){'csqtt20'}else{'csqtt215'}
$stage='D:/MCRF/Builds/Android/csqtt-historical-'+[guid]::NewGuid().ToString('N')
New-Item -ItemType Directory -Path $stage|Out-Null
$source=Join-Path $stage 'source'
$adapter=Join-Path $PSScriptRoot 'prepare-csqtt-historical.py'
$receipt=[ordered]@{state='PREPARING';generation=$Generation;engine=$engine;revision=$revision;source=$source;stage=$stage;sourceStable=$false;executed=$false;adapterSha256=(Get-FileHash $adapter).Hash.ToLowerInvariant();artifacts=@()}
function Save{[IO.File]::WriteAllText("$stage/receipt.json",($receipt|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))}
function Check{if($LASTEXITCODE){throw 'Historical source preparation failed; preserve the exact stage'}}
Save
try{
    # A shared clone of a partial donor does not inherit its promisor remote:
    # old-tag root blobs can be missing despite checkout returning success.
    & git clone --quiet --filter=blob:none --no-checkout $sourceRepo $source;Check
    & git -C $source sparse-checkout init --cone;Check
    & git -C $source sparse-checkout set rust-client shared;Check
    & git -C $source checkout --detach $revision;Check
    foreach($required in @('LICENSE','rust-client/Cargo.toml','rust-client/main.rs','rust-client/captcha.rs')){
        if(!(Test-Path -LiteralPath (Join-Path $source $required) -PathType Leaf)){throw "Required pinned historical source is absent: $required"}
    }
    & python $adapter $source $Generation;Check
    if($RetainedDependencyStage){
        $old=[IO.Path]::GetFullPath($RetainedDependencyStage)
        $expected=if($Generation -ceq '2.0'){'D:/MCRF/Builds/Android/csqtt-historical-e95bc9ddf2e04fbe9398914605d21886'}else{'D:/MCRF/Builds/Android/csqtt-historical-db036f84df62467685febedacc310516'}
        if($old -ine [IO.Path]::GetFullPath($expected) -or (Get-Item $old).LinkType){throw 'Exact previously compiled historical dependency origin required'}
        $prior=Get-Content "$old/receipt.json" -Raw|ConvertFrom-Json
        $rows=@(Get-Content "$old/source-manifest.json" -Raw|ConvertFrom-Json|Where-Object {$_.path -ceq 'rust-client/Cargo.lock'})
        $lock="$old/source/rust-client/Cargo.lock"
        if($prior.state -cne 'COMPILED_NOT_EXECUTED' -or !$prior.sourceStable -or $prior.generation -cne $Generation -or $rows.Count -ne 1 -or (Get-Item $lock).LinkType -or (Get-FileHash $lock).Hash.ToLowerInvariant() -cne $rows[0].sha256){throw 'Frozen historical dependency lock changed'}
        if(Test-Path "$source/rust-client/Cargo.lock"){throw 'Pinned historical checkout unexpectedly supplies a different lock; inspect before overwrite'}
        Copy-Item -LiteralPath $lock -Destination "$source/rust-client/Cargo.lock"
        $receipt.retainedDependencyLock=@{origin=$old;sha256=$rows[0].sha256;newResolution=$false}
    }
    if(!(Test-Path -LiteralPath "$source/rust-client/Cargo.lock")){
        # Historical tags did not publish a lockfile. Resolve it as source input
        # now; this command compiles/executes no upstream code. Freeze the exact
        # dependency solution before the later, separately gated common build.
        $rust='D:/MCRF/Builds/Tools/rust-1.97.1-gnu/toolchain'
        $savedCargoHome=$env:CARGO_HOME;$savedRustc=$env:RUSTC
        try {
            $env:CARGO_HOME=Join-Path $root 'work/toolchains/cargo-home';$env:RUSTC="$rust/bin/rustc.exe"
            & "$rust/bin/cargo.exe" generate-lockfile --manifest-path "$source/rust-client/Cargo.toml" *> "$stage/dependency-resolution.log";Check
        }finally{$env:CARGO_HOME=$savedCargoHome;$env:RUSTC=$savedRustc}
    }
    Copy-Item -LiteralPath $adapter -Destination "$stage/prepare-csqtt-historical.py"
    $gitDir=Join-Path $source '.git'
    $rows=@(Get-ChildItem -LiteralPath $source -Recurse -File -Force | Where-Object {!$_.FullName.StartsWith($gitDir+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)}|Sort-Object FullName|ForEach-Object {
        if($_.LinkType){throw 'Linked historical source input rejected'}
        @{path=[IO.Path]::GetRelativePath($source,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant();bytes=$_.Length}
    })
    [IO.File]::WriteAllText("$stage/source-manifest.json",($rows|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
    $receipt.state='SOURCE_PREPARED';$receipt.sourceStable=$true
}catch{$receipt.state='FAILED_SOURCE_PREPARATION';throw}
finally{Save;Write-Output "HISTORICAL_SOURCE_STAGE=$stage"}
