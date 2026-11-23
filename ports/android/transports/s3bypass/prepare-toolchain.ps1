param()
$ErrorActionPreference='Stop'
# Prerequisite download only: never execute Go, build an APK or run tests here.
$taskRoot=[IO.Path]::GetFullPath('D:/MCRF/Projects/MCRF/work/toolchains')
$taskMutex=[Threading.Mutex]::new($false,'Local\MCRF_Go1272Prerequisite')
if(!$taskMutex.WaitOne(0)){$taskMutex.Dispose();throw 'Go prerequisite download already active; continue its existing session'}
try{
$taskTarget=Join-Path $taskRoot 'go1.27.2'
if(Test-Path -LiteralPath $taskTarget){throw 'Pinned toolchain target already exists; inspect its receipt instead of replacing it'}
$taskStage=Join-Path $taskRoot ('go1.27.2-download-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskStage|Out-Null
$taskReceipt=[ordered]@{version='go1.27.2';state='DOWNLOADING';url='https://go.dev/dl/go1.27.2.windows-amd64.zip';sha256='1314008898bd40df77af4b014f777f08873dbdfbcd3d92308728ee03304fe04f';producerPid=$PID;producerStartTicks=[Diagnostics.Process]::GetCurrentProcess().StartTime.ToUniversalTime().Ticks;executed=$false}
function Save-ToolchainReceipt {[IO.File]::WriteAllText((Join-Path $taskStage 'receipt.json'),($taskReceipt|ConvertTo-Json),[Text.UTF8Encoding]::new($false))}
Save-ToolchainReceipt
Write-Output "TOOLCHAIN_STAGE=$taskStage"
try{
    $taskArchive=Join-Path $taskStage 'go.zip'
    Invoke-WebRequest -Uri $taskReceipt.url -OutFile $taskArchive -TimeoutSec 300
    if((Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskReceipt.sha256){throw 'Official Go archive hash mismatch; archive retained, not extracted or executed'}
    $taskReceipt.state='VERIFIED_ARCHIVE';Save-ToolchainReceipt
    $taskZip=[IO.Compression.ZipFile]::OpenRead($taskArchive)
    try{foreach($taskEntry in $taskZip.Entries){
        if(!$taskEntry.FullName.StartsWith('go/',[StringComparison]::Ordinal) -or $taskEntry.FullName.Contains('\') -or
            @($taskEntry.FullName.Split('/')|Where-Object {$_ -in @('.','..')}).Count -or
            (($taskEntry.ExternalAttributes -shr 16) -band 0xf000) -eq 0xa000){throw 'Unsafe archive entry; preserve the verified archive for review'}
    }}finally{$taskZip.Dispose()}
    $taskUnpacked=Join-Path $taskStage 'unpacked'
    [IO.Compression.ZipFile]::ExtractToDirectory($taskArchive,$taskUnpacked)
    $taskCompiler=Join-Path $taskUnpacked 'go/bin/go.exe'
    if(!(Test-Path -LiteralPath $taskCompiler -PathType Leaf)){throw 'Verified archive lacks the expected Windows compiler'}
    $taskReceipt.compilerSha256=(Get-FileHash -LiteralPath $taskCompiler -Algorithm SHA256).Hash.ToLowerInvariant()
    # Validate both exact directory-move endpoints. Never merge/overwrite an existing SDK.
    if([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($taskTarget)) -ine $taskRoot -or
        ![IO.Path]::GetFullPath($taskUnpacked).StartsWith($taskRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Toolchain move escaped its owned root'}
    [IO.Directory]::Move($taskUnpacked,$taskTarget)
    $taskReceipt.state='READY_NOT_EXECUTED';$taskReceipt.target=$taskTarget;Save-ToolchainReceipt
    [IO.File]::WriteAllText((Join-Path $taskTarget 'toolchain.json'),($taskReceipt|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    Write-Output "TOOLCHAIN_READY=$taskTarget"
}catch{
    $taskReceipt.state='FAILED_RETAINED';$taskReceipt.failure=$_.Exception.GetType().Name;Save-ToolchainReceipt
    throw
}
}finally{$taskMutex.ReleaseMutex();$taskMutex.Dispose()}
