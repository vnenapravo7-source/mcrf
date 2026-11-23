$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$taskDestination=Join-Path $taskRoot 'ports/android/wg-module/src/main/assets/licenses'
$taskCache=Join-Path $taskRoot 'work/wg-license-source'
New-Item -ItemType Directory -Path $taskDestination,$taskCache -Force|Out-Null
$taskGpl=Join-Path $taskCache 'GPL-3.0.txt'
if(!(Test-Path -LiteralPath $taskGpl)){Invoke-WebRequest -Uri 'https://www.gnu.org/licenses/gpl-3.0.txt' -OutFile $taskGpl}
$taskText=Get-Content -LiteralPath $taskGpl -Raw
if($taskText.Length -lt 30000 -or $taskText -notmatch 'GNU GENERAL PUBLIC LICENSE' -or $taskText -notmatch 'Version 3, 29 June 2007'){throw 'Full GPL3 notice verification failed'}
$taskSources=[ordered]@{
    'WireGuard-fork-MIT.txt'='work/android-upstream/sing-box-lx-mcrf-clash-20261014/submodules/wireguard-go/LICENSE'
    'sing-GPL-notice.txt'='work/toolchains/go-home/pkg/mod/github.com/sagernet/sing@v0.7.10/LICENSE'
    'Go-BSD.txt'='work/toolchains/go1.26.8/go/LICENSE'
    'gomobile-BSD.txt'='work/toolchains/go-home/pkg/mod/github.com/sagernet/gomobile@v0.1.13/LICENSE'
    'x-crypto-BSD.txt'='work/toolchains/go-home/pkg/mod/golang.org/x/crypto@v0.41.0/LICENSE'
    'x-sys-BSD.txt'='work/toolchains/go-home/pkg/mod/golang.org/x/sys@v0.35.0/LICENSE'
    'x-net-BSD.txt'='work/toolchains/go-home/pkg/mod/golang.org/x/net@v0.43.0/LICENSE'
    'GPL-3.0.txt'='work/wg-license-source/GPL-3.0.txt'
}
$taskRows=foreach($taskItem in $taskSources.GetEnumerator()) {
    $taskSource=Join-Path $taskRoot $taskItem.Value
    $taskTarget=Join-Path $taskDestination $taskItem.Key
    $taskHash=(Get-FileHash -LiteralPath $taskSource).Hash.ToLowerInvariant()
    if(Test-Path -LiteralPath $taskTarget){if((Get-FileHash -LiteralPath $taskTarget).Hash.ToLowerInvariant() -cne $taskHash){throw 'Existing notice differs; do not overwrite'}}
    else {Copy-Item -LiteralPath $taskSource -Destination $taskTarget}
    if((Get-FileHash -LiteralPath $taskTarget).Hash.ToLowerInvariant() -cne $taskHash){throw 'Notice copy hash mismatch'}
    [pscustomobject]@{name=$taskItem.Key;source=$taskItem.Value;sha256=$taskHash;bytes=(Get-Item -LiteralPath $taskTarget).Length}
}
[IO.File]::WriteAllText((Join-Path $taskDestination 'manifest.json'),(@{wireguardCommit='64065e6826ad82aa28429dd829c1c65f29b7f6c9';sing='v0.7.10';gomobile='v0.1.13';fullGplSource='https://www.gnu.org/licenses/gpl-3.0.txt';files=$taskRows;matchingSourceArchiveRequired=$true}|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
Write-Output 'WG/AWG third-party notices prepared; matching source archive remains required before publication.'
