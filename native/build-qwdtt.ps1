param([string]$GoPath)
$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
$qwdttRoot=Join-Path $project 'work/qwdtt-source'
$commit='a296c57eaba69bb9479a24f9157856490890e47d'
$patchPath=Join-Path $PSScriptRoot 'qwdtt-windows.patch'
if (!(Test-Path -LiteralPath $qwdttRoot)) {
  & git clone --no-checkout https://github.com/SpaceNeuroX/proxy-turn-vk-android.git $qwdttRoot
  if ($LASTEXITCODE -ne 0) { throw 'QWDTT source clone failed.' }
  & git -C $qwdttRoot checkout --detach $commit
  if ($LASTEXITCODE -ne 0) { throw 'QWDTT source checkout failed.' }
}
if ((& git -C $qwdttRoot rev-parse HEAD).Trim() -ne $commit) { throw 'Unexpected QWDTT source revision; existing checkout was preserved.' }
& git -C $qwdttRoot apply --reverse --check $patchPath 2>$null
if ($LASTEXITCODE -ne 0) {
  & git -C $qwdttRoot apply --check $patchPath
  if ($LASTEXITCODE -ne 0) { throw 'QWDTT adaptation overlaps local edits; checkout was preserved.' }
  & git -C $qwdttRoot apply $patchPath
  if ($LASTEXITCODE -ne 0) { throw 'QWDTT adaptation failed.' }
}
if (!$GoPath) { $GoPath=Join-Path $project 'work/toolchains/go1.26.8/go/bin/go.exe' }
if (!(Test-Path -LiteralPath $GoPath)) { throw 'Pass -GoPath for Go 1.26.8 or a compatible toolchain.' }
$env:GOPATH=Join-Path $project 'work/toolchains/go-cache'
$env:GOCACHE=Join-Path $project 'work/toolchains/go-build-cache'
$env:CGO_ENABLED='0'
Push-Location (Join-Path $qwdttRoot 'go_client')
try {
  & $GoPath build -mod=mod -trimpath -ldflags '-s -w' -o (Join-Path $project 'work/qwdtt-client.exe') .
  if ($LASTEXITCODE -ne 0) { throw 'QWDTT build failed.' }
} finally { Pop-Location }
Write-Output 'QWDTT Windows SOCKS sidecar built from pinned source; no network session started.'
