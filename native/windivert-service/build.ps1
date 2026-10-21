param([Parameter(Mandatory=$true)][string]$SourceRoot,[Parameter(Mandatory=$true)][string]$ToolchainBin,[Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
$pin='1789526ecfb9ff5397c94f9f54c1a3dc2fb60440'
if((& git -C $SourceRoot rev-parse HEAD) -ne $pin -or (& git -C $SourceRoot status --porcelain)){throw 'Expected pristine WinDivert v2.2.2 source'}
$OutputDirectory=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $OutputDirectory){throw 'Use a fresh output directory'}
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$stage=Join-Path $OutputDirectory 'source'
New-Item -ItemType Directory -Path $stage | Out-Null
foreach($name in @('dll','include','LICENSE','VERSION','mingw-build.sh')){Copy-Item -LiteralPath (Join-Path $SourceRoot $name) -Destination $stage -Recurse}
& git -C $stage apply --no-index --ignore-space-change (Join-Path $PSScriptRoot 'service-name.patch')
if($LASTEXITCODE -ne 0){throw 'WinDivert service patch failed'}
$compiler=Join-Path $ToolchainBin 'x86_64-w64-mingw32-clang.exe'
$object=Join-Path $OutputDirectory 'windivert.o'
$dll=Join-Path $OutputDirectory 'WinDivert.dll'
& $compiler '-fno-ident' '-Wall' '-Wno-pointer-to-int-cast' '-Os' '-include' 'stddef.h' ('-I'+(Join-Path $stage 'include')) '-c' (Join-Path $stage 'dll/windivert.c') '-o' $object
if($LASTEXITCODE -ne 0){throw 'WinDivert compilation failed'}
& $compiler '-shared' '-Wl,--entry=WinDivertDllEntry' '-Wl,--no-insert-timestamp' '-o' $dll $object (Join-Path $stage 'dll/windivert.def') '-nostdlib' '-lkernel32' '-ladvapi32' '-lmsvcrt'
if($LASTEXITCODE -ne 0){throw 'WinDivert DLL link failed'}
& (Join-Path $ToolchainBin 'llvm-strip.exe') $dll
if($LASTEXITCODE -ne 0){throw 'WinDivert DLL strip failed'}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'service-name.patch'),(Join-Path $PSScriptRoot 'README.md') -Destination $OutputDirectory
Get-FileHash -LiteralPath $dll | Format-List
