param()
$ErrorActionPreference='Stop'
$gh='C:/Program Files/GitHub CLI/gh.exe';$repo='vnenapravo7-source/mcrf';$tag='engines-2026-10-13-1'
$stage='D:/MCRF/Builds/Android/engine-version-labels-'+[guid]::NewGuid().ToString('N')
New-Item -ItemType Directory -Path $stage|Out-Null
$json=(& $gh api "repos/$repo/releases/tags/$tag"|Out-String)
if($LASTEXITCODE -ne 0){throw 'Release read failed'}
$release=$json|ConvertFrom-Json
$rows=@($release.assets|Where-Object {$_.name -match '^mcrf-engine-[a-z0-9-]+-(arm64-v8a|armeabi-v7a|x86_64|universal)-release\.apk$'})
if($rows.Count -lt 30 -or $release.id -ne 411713792){throw 'Unexpected catalogue'}
[IO.File]::WriteAllText("$stage/release-before.json",$json,[Text.UTF8Encoding]::new($false))
Write-Output "ENGINE_VERSION_STAGE=$stage"
& $gh release download $tag --repo $repo --pattern 'mcrf-engine-*-release.apk' --dir $stage
if($LASTEXITCODE -ne 0){throw 'Download incomplete; no remote writes'}
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
$sdk='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
$result=@()
foreach($row in $rows){
    $file=Join-Path $stage $row.name
    if((Get-Item $file).Length -ne $row.size -or ('sha256:'+(Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant()) -cne $row.digest){throw 'Published download integrity failed'}
    $signer=(& "$sdk/apksigner.bat" verify --print-certs $file 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $signer -notmatch 'fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Published package signer mismatch'}
    $badging=(& "$sdk/aapt2.exe" dump badging $file 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $badging -notmatch "(?m)^package: name='(org\.mcrf\.engines\.[a-z0-9]+)' versionCode='([1-9][0-9]*)'"){throw 'Version identity unavailable'}
    $result+=@{id=$row.id;name=$row.name;bytes=$row.size;sha256=($row.digest -replace '^sha256:','');package=$Matches[1];versionCode=[long]$Matches[2]}
}
[IO.File]::WriteAllText("$stage/versions.json",($result|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
Write-Output "VERIFIED_VERSION_LABELS=$stage/versions.json"
