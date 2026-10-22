$ErrorActionPreference='Stop'
# Official1.97.1 release manifest SHA256:
# 03569b1886ceb5c05276b50c8431ab111de944cd6140fe1fa7d821dd8e0f29cf
# Extract verified component payloads only. No rustup/MSI/setup script, registry,
# system PATH, global default toolchain, WSL or Visual Studio changes.
$taskToolRoot='D:/MCRF/Builds/Tools/rust-1.97.1-gnu'
if(Test-Path -LiteralPath $taskToolRoot){throw 'Toolchain directory already exists; inspect its receipt/current producer, never overwrite or restart it'}
New-Item -ItemType Directory -Path $taskToolRoot | Out-Null
$taskPrefix=Join-Path $taskToolRoot 'toolchain'
$taskArchives=Join-Path $taskToolRoot 'archives'
$taskUnpack=Join-Path $taskToolRoot 'unpack'
foreach($taskDirectory in @($taskPrefix,$taskArchives,$taskUnpack)){New-Item -ItemType Directory -Path $taskDirectory | Out-Null}
$taskComponents=@(
    @('rustc-1.97.1-x86_64-pc-windows-gnu','11de14fa7ee410e9baaa070ce0cda44e155c0e9e32329db0b5f20efc0f2fa5e4'),
    @('cargo-1.97.1-x86_64-pc-windows-gnu','7809b05eabda3205167a8bde739e93a76ded00d65fe8984666130b1034c18b4c'),
    @('rust-std-1.97.1-x86_64-pc-windows-gnu','513ed4875d5a3726d02c5872034f0673593ae343ea62a70939213e894c274be3'),
    @('rust-mingw-1.97.1-x86_64-pc-windows-gnu','aa257e5f8354bd1bc1cacbcbcdede3ebb89a100bef504d72da56db8527807ad2'),
    @('rust-std-1.97.1-aarch64-linux-android','d664a49fb80d125d68f779112aa97d2a3f5def5f807a35540aa77fce0b350c4f'),
    @('rust-std-1.97.1-armv7-linux-androideabi','5e62a011675e0ba97b11bb0b8e5c8266273dcd0c8e450f6c07541690449d14a2'),
    @('rust-std-1.97.1-x86_64-linux-android','465ab02385c392e5399804ba81b32606e0bf279153f3746082297afad632a34b')
)
$taskReceipt=[ordered]@{state='ACTIVE';root=$taskToolRoot;prefix=$taskPrefix;current=$null;components=@();systemChanged=$false}
function Save-RustReceipt {[IO.File]::WriteAllText((Join-Path $taskToolRoot 'receipt.json'),($taskReceipt|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))}
Save-RustReceipt
try{
    foreach($taskComponent in $taskComponents){
        $taskName=$taskComponent[0];$taskHash=$taskComponent[1]
        $taskReceipt.current=$taskName;Save-RustReceipt
        $taskUrl="https://static.rust-lang.org/dist/2026-07-16/$taskName.tar.xz"
        $taskArchive=Join-Path $taskArchives "$taskName.tar.xz"
        Invoke-WebRequest -Uri $taskUrl -OutFile $taskArchive -TimeoutSec 60
        if((Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskHash){throw 'Official Rust component SHA mismatch; unverified data will not be extracted'}
        $taskEntries=@(& tar -tf $taskArchive)
        if($LASTEXITCODE -ne 0 -or !$taskEntries.Count -or @($taskEntries|Where-Object {$_ -match '(^|[/\\])\.\.([/\\]|$)|^[/\\]|^[A-Za-z]:' -or !($_ -eq $taskName -or $_.StartsWith($taskName+'/'))}).Count){throw 'Unexpected archive entry path'}
        $taskExtract=Join-Path $taskUnpack $taskName
        New-Item -ItemType Directory -Path $taskExtract | Out-Null
        & tar -xf $taskArchive -C $taskExtract
        if($LASTEXITCODE -ne 0){throw 'Rust component extraction failed'}
        $taskExtractRoot=Join-Path $taskExtract $taskName
        $taskPayloads=@(Get-Content -LiteralPath (Join-Path $taskExtractRoot 'components') | Where-Object {$_})
        foreach($taskPayloadName in $taskPayloads){
            if($taskPayloadName -notmatch '^[A-Za-z0-9_.-]+$'){throw 'Unexpected Rust component name'}
            $taskPayload=Join-Path $taskExtractRoot $taskPayloadName
            if(!(Test-Path -LiteralPath $taskPayload -PathType Container)){throw 'Rust payload is missing'}
            Get-ChildItem -LiteralPath $taskPayload -Force | Where-Object Name -ne 'manifest.in' | Copy-Item -Destination $taskPrefix -Recurse -Force
        }
        $taskReceipt.components+=@{name=$taskName;url=$taskUrl;sha256=$taskHash;bytes=(Get-Item -LiteralPath $taskArchive).Length;verified=$true}
        Save-RustReceipt
        Write-Output "RUST_COMPONENT_VERIFIED=$taskName"
    }
    $taskRustVersion=& (Join-Path $taskPrefix 'bin/rustc.exe') --version
    if($LASTEXITCODE -ne 0 -or $taskRustVersion -notmatch '^rustc 1\.97\.1 '){throw 'Portable compiler did not verify its version'}
    $taskCargoVersion=& (Join-Path $taskPrefix 'bin/cargo.exe') --version
    if($LASTEXITCODE -ne 0){throw 'Portable Cargo did not start'}
    $taskReceipt.state='READY_PORTABLE';$taskReceipt.current=$null
    $taskReceipt.rustc=$taskRustVersion;$taskReceipt.cargo=$taskCargoVersion
    Save-RustReceipt
    Write-Output "RUST_PORTABLE_READY=$taskPrefix"
}catch{
    $taskReceipt.state='FAILED';Save-RustReceipt
    throw
}
