param()
# Prepare frozen source only. No compiler/test/phone/module install/publication.
$ErrorActionPreference='Stop'
$taskRoot='D:/MCRF/Projects/MCRF'
$taskSource="$taskRoot/work/android-upstream/sing-box-lx-mcrf-clash-20261014"
$taskPatch="$taskRoot/ports/android/primary-cores/singbox/native-patches/p044-direct-family"
$taskCommit='3d3d3db3a2ccef3b1a8eb71b831ed2461c54b94d'
$taskPins=[ordered]@{
    'submodules/gvisor'='117243aa02fa2915cb53d1d4549bfca3a3ebf238'
    'submodules/sing-tun'='6f56eca9cb2e81d1c233b6104618ff30500e9f39'
    'submodules/utls'='59e89bb121d8cc7a0a1c87a930864122b5685d00'
    'submodules/wireguard-go'='64065e6826ad82aa28429dd829c1c65f29b7f6c9'
}
function NativeGit([string]$Path,[string[]]$Arguments){
    $result=(& git -C $Path @Arguments 2>&1|Out-String).Trim()
    if($LASTEXITCODE){throw 'Pinned source Git operation failed'}
    $result
}
if((NativeGit $taskSource @('rev-parse','HEAD')) -cne $taskCommit){throw 'Unexpected core pin'}
if(NativeGit $taskSource @('diff','--name-only','--ignore-submodules=all')){throw 'Modified core tracked inputs'}
foreach($row in $taskPins.GetEnumerator()){
    $path=Join-Path $taskSource $row.Key
    if((NativeGit $path @('rev-parse','HEAD')) -cne $row.Value -or (NativeGit $path @('diff','--name-only'))){throw 'Modified/pin-mismatched native submodule'}
}
$taskFiles=@('direct_family_lx.go','direct_family_stub_lx.go','direct_family_lx_test.go','direct_family_policy_lx_test.go','direct_family_stub_lx_test.go','router-seam.patch','README.md')
$taskBefore=@($taskFiles|ForEach-Object {[ordered]@{file=$_;sha256=(Get-FileHash -LiteralPath "$taskPatch/$_" -Algorithm SHA256).Hash.ToLowerInvariant()}})
$taskStage='D:/MCRF/Builds/Android/p044-native-source-'+[guid]::NewGuid().ToString('N')
[IO.Directory]::CreateDirectory("$taskStage/source")|Out-Null
$taskReceipt=[ordered]@{schema=1;state='PREPARING_SOURCE_ONLY';stage=$taskStage;commit=$taskCommit;submodules=$taskPins;patches=$taskBefore;archives=@();compiled=$false;tested=$false;installed=$false;published=$false;producerComplete=$false;authority='Latest direct human independently open/close/check until fixed; P0/44 source fix only. External native module, no P1/capabilities/router changes/publication. Preparation is not build readiness.'}
function SaveNativeReceipt {$taskReceipt|ConvertTo-Json -Depth 8|Set-Content -LiteralPath "$taskStage/receipt.json" -Encoding utf8}
SaveNativeReceipt
function ArchiveNative([string]$From,[string]$Commit,[string]$Destination,[string]$Name){
    $archive="$taskStage/$Name.tar"
    NativeGit $From @('archive','--format=tar','-o',$archive,$Commit)|Out-Null
    # Exact trusted Git trees only; reject traversal/Windows drive names before extraction.
    $entries=@(& tar -tf $archive)
    if($LASTEXITCODE -or @($entries|Where-Object {$_ -match '(^/|^[A-Za-z]:|(^|/)\.\.(/|$)|\\)'}).Count){throw 'Unsafe source archive'}
    [IO.Directory]::CreateDirectory($Destination)|Out-Null
    & tar -xf $archive -C $Destination
    if($LASTEXITCODE){throw 'Source extraction failed'}
    $taskReceipt.archives+=@{name=$Name;commit=$Commit;sha256=(Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant();bytes=(Get-Item -LiteralPath $archive).Length}
}
try{
    ArchiveNative $taskSource $taskCommit "$taskStage/source" 'core'
    foreach($row in $taskPins.GetEnumerator()){ArchiveNative (Join-Path $taskSource $row.Key) $row.Value (Join-Path "$taskStage/source" $row.Key) ($row.Key.Replace('/','-'))}
    $route="$taskStage/source/route/route.go"
    if((Get-FileHash -LiteralPath $route -Algorithm SHA256).Hash -cne 'E9DD4DD9588BD733A44299CCB6BDDE205F0A89920C844AD308D3F7C7DE3ADA40'){throw 'Upstream Git-archive route seam changed'}
    # The pinned Git blob itself is CRLF. Normalize this one generated copy
    # mechanically before applying the reviewed LF patch; retain both exact hashes.
    $taskReceipt.originalRouteSha256='e9dd4dd9588bd733a44299ccb6bdde205f0a89920c844ad308d3f7c7de3ada40'
    [IO.File]::WriteAllText($route,([IO.File]::ReadAllText($route).Replace("`r`n","`n")),[Text.UTF8Encoding]::new($false))
    if((Get-FileHash -LiteralPath $route -Algorithm SHA256).Hash -cne 'F48527789B1C1C2B4AB00F7FCFC2C5182AA20445A4694A51883981478916CD28'){throw 'Unexpected route normalization'}
    foreach($name in $taskFiles){
        $target=if($name.EndsWith('.go')){"$taskStage/source/route/$name"}else{"$taskStage/$name"}
        [IO.File]::Copy("$taskPatch/$name",$target,$false)
    }
    # Patch application, not a compilation/test. No live upstream file is edited.
    NativeGit "$taskStage/source" @('apply',"$taskStage/router-seam.patch")|Out-Null
    $taskAfter=@($taskFiles|ForEach-Object {[ordered]@{file=$_;sha256=(Get-FileHash -LiteralPath "$taskPatch/$_" -Algorithm SHA256).Hash.ToLowerInvariant()}})
    if(($taskBefore|ConvertTo-Json -Compress) -cne ($taskAfter|ConvertTo-Json -Compress)){throw 'Canonical patch inputs changed while freezing'}
    [IO.File]::Copy($PSCommandPath,"$taskStage/prepare-control.ps1",$false)
    $taskReceipt.routeSha256=(Get-FileHash -LiteralPath $route -Algorithm SHA256).Hash.ToLowerInvariant()
    $sourcePrefix=[IO.Path]::GetFullPath("$taskStage/source")+[IO.Path]::DirectorySeparatorChar
    $sourceManifest=@(Get-ChildItem -LiteralPath "$taskStage/source" -Recurse -File|Sort-Object FullName|ForEach-Object {
        [ordered]@{path=$_.FullName.Substring($sourcePrefix.Length).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
    $sourceManifest|ConvertTo-Json -Depth 4|Set-Content -LiteralPath "$taskStage/source-manifest.json" -Encoding utf8
    $taskReceipt.sourceManifestSha256=(Get-FileHash "$taskStage/source-manifest.json" -Algorithm SHA256).Hash.ToLowerInvariant()
    $taskReceipt.sourceFiles=$sourceManifest.Count
    $taskReceipt.state='SOURCE_FROZEN_UNCOMPILED_UNTESTED'
    $taskReceipt.producerComplete=$true
    SaveNativeReceipt
    Write-Output "P044_NATIVE_SOURCE=$taskStage"
}catch{
    $taskReceipt.state='PREPARATION_FAILED';$taskReceipt.error=$_.Exception.Message;$taskReceipt.producerComplete=$true;SaveNativeReceipt;throw
}
