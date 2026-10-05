param([string]$SdkPath,[string]$LlvmPath)
$ErrorActionPreference='Stop'
$project=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if(-not $SdkPath){$SdkPath=Join-Path $project 'work/zapret/cygwin-sdk'}
if(-not $LlvmPath){$LlvmPath=Join-Path $project 'work/toolchains/llvm-mingw/llvm-mingw-20260922-ucrt-x86_64'}
$taskZip=Join-Path $project 'work/zapret/upstream-source.zip'
if((Get-FileHash -LiteralPath $taskZip -Algorithm SHA256).Hash -ne 'BF2EFD098999A547D97288C00205BBC30A45418B27A447D4DC7729FB857CE5BF'){throw 'Unexpected upstream source archive'}
$taskStage=Join-Path $project ('work/zapret/build/'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskStage -Force | Out-Null
Expand-Archive -LiteralPath $taskZip -DestinationPath $taskStage
$taskSource=Join-Path $taskStage 'zapret-d437963452674faadfd45adcd62466272b5a2fcd'
Push-Location $taskSource
try {
  & git apply --check (Join-Path $PSScriptRoot 'upstream.patch')
  if($LASTEXITCODE -ne 0){throw 'Upstream patch check failed'}
  & git apply (Join-Path $PSScriptRoot 'upstream.patch')
  if($LASTEXITCODE -ne 0){throw 'Upstream patch failed'}
} finally {Pop-Location}
$taskNfq=Join-Path $taskSource 'nfq'
foreach($name in @('app_filter.c','app_filter.h')){Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $taskNfq}
$taskClang=Join-Path $LlvmPath 'bin/clang.exe'
$taskFiles=@(Get-ChildItem -LiteralPath $taskNfq -Filter '*.c')+@(Get-ChildItem (Join-Path $taskNfq 'crypto') -Filter '*.c')
$taskObjects=@()
foreach($taskFile in $taskFiles){
  $taskObject=$taskFile.FullName+'.o'
  & $taskClang -target x86_64-pc-cygwin -std=gnu99 -Os -Wno-address-of-packed-member -isystem (Join-Path $SdkPath 'usr/include') -isystem (Join-Path $SdkPath 'usr/include/w32api') -I (Join-Path $taskNfq 'windows') -c $taskFile.FullName -o $taskObject
  if($LASTEXITCODE -ne 0){throw ('C compilation failed: '+$taskFile.Name)}
  $taskObjects+=$taskObject
}
$taskOutput=Join-Path $project 'work/zapret/winws-mcrf.exe'
& $taskClang -target x86_64-pc-cygwin -nostdlib -fuse-ld=lld '-Wl,--gc-sections' (Join-Path $SdkPath 'usr/lib/crt0.o') @taskObjects (Join-Path $LlvmPath 'lib/clang/23/lib/windows/libclang_rt.builtins-x86_64.a') (Join-Path $SdkPath 'usr/lib/libz.a') -L (Join-Path $SdkPath 'usr/lib') -L (Join-Path $SdkPath 'usr/lib/w32api') -L (Join-Path $taskNfq 'windows/windivert') -lcygwin -lwlanapi -lole32 -loleaut32 -lwindivert64 -lkernel32 -ladvapi32 -luuid -lntdll -o $taskOutput
if($LASTEXITCODE -ne 0){throw 'Link failed'}
Write-Output ('Built process-aware Zapret: '+$taskOutput)
