param([Parameter(Mandatory=$true)][string]$BuildReceipt,[string]$PhoneReceipt='',[switch]$Apply,[string]$ResumeStage='',[long]$ResumeReleaseId=0)
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF';$repo='vnenapravo7-source/mcrf';$tag='android-v0.1.0-preview.20261019.2';$gh='C:/Program Files/GitHub CLI/gh.exe';$utf8=[Text.UTF8Encoding]::new($false)
$moduleStage='D:/MCRF/Builds/Android/p044-module-apk-3a54d325f3f84aa1a0941a95eb5371f9'
$nativeStage='D:/MCRF/Builds/Android/p044-native-source-632c2b80b4b9433e85fb1163085a5ae4'
function Gh([string[]]$argv){$out=(& $gh @argv 2>&1|Out-String).Trim();if($LASTEXITCODE){throw 'GitHub operation failed; inspect concrete remote identity before resume'};$out}
function Api([string]$p){(Gh @('api',"repos/$repo/$p"))|ConvertFrom-Json}
function Sha([string]$p){(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant()}
function Save($v,[string]$p){[IO.File]::WriteAllText($p,($v|ConvertTo-Json -Depth 15),$utf8)}
function Shape($r){@{id=$r.id;tag=$r.tag_name;target=$r.target_commitish;draft=$r.draft;preview=$r.prerelease;name=$r.name;body=$r.body;assets=@($r.assets|Sort-Object name|ForEach-Object {@{id=$_.id;name=$_.name;bytes=$_.size;sha=$_.digest;label=$_.label}})}|ConvertTo-Json -Depth 5 -Compress}
function Safe([string]$base,[string]$p){$path=[IO.Path]::GetFullPath((Join-Path $base $p));if(!$path.StartsWith([IO.Path]::GetFullPath($base)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or $p -match '(^|/)(\.\.?|\.git|private|local\.properties)(/|$)|\.(jks|keystore|p12|dpapi)$'){throw 'Unsafe public path'};if((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Public source reparse point'};$path}
function AddSource($zip,[string]$base,$row,[string]$prefix=''){
    $p=$row.path -replace '\\','/';$file=Safe $base $p;if((Sha $file) -cne $row.sha256){throw 'Frozen public source drift'}
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file,$prefix+$p,[IO.Compression.CompressionLevel]::Optimal)|Out-Null
}
$build=Get-Content $BuildReceipt -Raw|ConvertFrom-Json;$module=Get-Content "$moduleStage/receipt.json" -Raw|ConvertFrom-Json
if($build.state -cne 'PASS' -or !$build.sourceStable -or !$build.excludedP1 -or $build.versionCode -ne 10020 -or $build.versionName -cne '0.1.0-preview.20261019.2' -or $build.stage -cnotmatch '^D:/MCRF/Builds/Android/p0-preview20-[a-f0-9]{32}$'){throw 'Exact20 passed immutable build required'}
if($module.state -cne 'PASS_EXTERNAL_MODULE7_STATIC_ONLY' -or !$module.sourceStable -or !$module.excludedP1 -or $module.moduleVersionCode -ne 7){throw 'Exact7 external module proof required'}
$phone=if($PhoneReceipt){Get-Content $PhoneReceipt -Raw|ConvertFrom-Json}else{$null}
if($phone -and (!$phone.passed -or !$phone.appInstalled -or !$phone.protectedUnchanged -or !$phone.modulesUnchanged -or $phone.versionCode -ne 10020 -or $phone.sha256 -cne $build.artifacts.universal.sha256)){throw 'Exact phone20 install proof required'}
if($ResumeStage){
    $stage=[IO.Path]::GetFullPath($ResumeStage)
    if($stage -cnotmatch '^D:\\MCRF\\Builds\\Android\\p0-preview20-release-[a-f0-9]{32}$'){throw 'Exact prepared stage required'}
    $plan=Get-Content "$stage/plan.json" -Raw|ConvertFrom-Json
    if($plan.tag -cne $tag -or $plan.mainSha -cne $build.artifacts.universal.sha256){throw 'Plan identity drift'}
}else{
    if(@((Api 'releases?per_page=30')|Where-Object tag_name -CEQ $tag).Count){throw 'Release exists: resume its prepared stage, do not duplicate'}
    $stage='D:/MCRF/Builds/Android/p0-preview20-release-'+[guid]::NewGuid().ToString('N');[IO.Directory]::CreateDirectory("$stage/assets")|Out-Null
    $old=Api 'releases/416790557';$engines=Api 'releases/tags/engines-2026-10-13-1';$latest=Api 'releases/latest'
    if($old.tag_name -cne 'android-v0.1.0-preview.20261019.1' -or $old.draft -or !$old.prerelease -or $latest.tag_name -cne 'v1.0.11'){throw 'Existing release/latest identity drift'}
    Save $old "$stage/old-before.json";Save $engines "$stage/engines-before.json";Save $latest "$stage/stable-before.json"
    $mapping=@{universal='mcrf-android-universal-release.apk';'arm64-v8a'='arm64-v8a-release.apk';'armeabi-v7a'='armeabi-v7a-release.apk';'x86_64'='androidApp-x86_64-release.apk'}
    foreach($abi in $mapping.Keys){$a=$build.artifacts.$abi;if((Sha $a.apk) -cne $a.sha256){throw 'Main artifact drift'};[IO.File]::Copy($a.apk,"$stage/assets/$($mapping[$abi])",$false)}
    foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64','universal')){$a=$module.artifacts.$abi;if((Sha $a.apk) -cne $a.sha256){throw 'Module artifact drift'};[IO.File]::Copy($a.apk,"$stage/assets/mcrf-engine-sing-box-$abi-release.apk",$false)}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $rows=@(Get-Content "$($build.stage)/source-manifest.json" -Raw|ConvertFrom-Json)
    $zip=[IO.Compression.ZipFile]::Open("$stage/assets/mcrf-android-source.zip",[IO.Compression.ZipArchiveMode]::Create)
    try{
        foreach($row in $rows){$p=$row.path -replace '\\','/';$ordinary=$p -match '^ports/(android|catalogue|fixtures)/' -and $p -notmatch '(^|/)(work|build|coordination)(/|$)';$spi=$p -in @('work/steer-android-port/android-vpn/integration-helper/src/main/java/org/mcrf/steerprep/SteerMcrfSnapshotAdapter.java','work/steer-android-port/android-vpn/module-contract/src/main/java/org/mcrf/engine/spi/v1/Engine.java','work/steer-android-port/android-vpn/module-contract/src/main/java/org/mcrf/engine/spi/v1/Platform.java');if(!$ordinary -and !$spi){throw 'Unapproved public main source'};AddSource $zip $build.stage $row}
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$($build.stage)/source-manifest.json",'SOURCE_MANIFEST.json',[IO.Compression.CompressionLevel]::Optimal)|Out-Null
    }finally{$zip.Dispose()}
    # Native corresponding source is the EXACT patched immutable producer tree, not live upstream.
    $nativeRows=@(Get-Content "$nativeStage/source-manifest.json" -Raw|ConvertFrom-Json)
    $moduleRows=@(Get-Content "$moduleStage/source-manifest.json" -Raw|ConvertFrom-Json|Where-Object {$_.path -match '^ports/android/(engines|primary-cores/singbox)/'})
    $zip=[IO.Compression.ZipFile]::Open("$stage/assets/mcrf-sing-box-7-source.zip",[IO.Compression.ZipArchiveMode]::Create)
    try{
        foreach($row in $nativeRows){AddSource $zip "$nativeStage/source" $row 'native/'}
        foreach($row in $moduleRows){AddSource $zip $moduleStage $row 'adapter/'}
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$nativeStage/source-manifest.json",'NATIVE_SOURCE_MANIFEST.json',[IO.Compression.CompressionLevel]::Optimal)|Out-Null
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$moduleStage/source-manifest.json",'MODULE_BUILD_MANIFEST.json',[IO.Compression.CompressionLevel]::Optimal)|Out-Null
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$nativeStage/runtime-sources.jar",'runtime-sources.jar',[IO.Compression.CompressionLevel]::Optimal)|Out-Null
    }finally{$zip.Dispose()}
    $evidence=@{schema=1;versionCode=10020;versionName=$build.versionName;baseNativeLibraries=0;signer=$build.signer;sourceManifestSha=(Sha "$($build.stage)/source-manifest.json");excludedUnfinishedP1=$true;checks=@($build.tests|ForEach-Object {@{suite=$_.suite;tests=$_.tests}});retainedPriorHostChecks=40;scope='P042-45/sidebar fixes inherited verified19; exact-app-preview singbox7 update offer added, consent/non-downgrade retained. Not full-project acceptance.';native=@{moduleVersionCode=7;version='1.14.2-lx.11-mcrf-direct-family.1';upstreamCommit='3d3d3db3a2ccef3b1a8eb71b831ed2461c54b94d';nativeSourceManifestSha=(Sha "$nativeStage/source-manifest.json");hostTaggedPass=27;hostStubPass=1;threeAbiApiElfAndApkGates=$true;changedJavaClasses=0};phone=@{new20Installed=($null -ne $phone);protectedExactAtInstall=($phone -and $phone.protectedUnchanged);module7AlreadyInstalled=$true};acceptance='Samsung19 actual automaticVPN-to-BS and return plus health and Androidnotices PASS; Chrome Google/YouTube/Instagram through two profiles PASS; Direct/appDirect/appVPN/BLOCK and marked failed/stale pool checks PASS. Module7 unchanged.';open='Vivo human verification; complete manual sidebar-fastest matrix and full IPv4/IPv6 Direct/VPN/BLOCK route matrix; no all-provider/ABI/wholeproject acceptance claim.'}
    Save $evidence "$stage/assets/BUILD_EVIDENCE.json"
    $notes=@"
MCRF Android 0.1.0-preview.20261019.2 · build 10020

- Исправлены автоматические переходы VPN → БС и обратно, с уведомлением после подтверждённого подключения.
- Исправлены автовыбор отмеченных серверов и прямой выход; IPv6 по умолчанию разрешён. Совместимость IPv4 — отдельная выключенная по умолчанию опция. Струя «Чертежа» постоянная: 44.
- После обновления приложение предлагает sing-box 7, если установлен старый модуль. Новый модуль находится в этом же пререлизе; подтверждение установки Android обязательно. Актуальный 7 повторно не скачивается. Старый общий каталог движков не изменён.

Основное приложение: mcrf-android-universal-release.apk либо APK своей архитектуры. Исправленный sing-box — отдельный mcrf-engine-sing-box-<ABI>-release.apk (для Samsung/Vivo обычно arm64-v8a). Если sing-box 7 уже установлен, менять его не нужно. Профили и маршруты сохраняются.

На Samsung проверены оба автоматических перехода с передачей данных и уведомлениями, Direct и правило приложения, отрицательный BLOCK-контроль, автовыбор с неудачным/устаревшим замером, Google/YouTube/Instagram через два профиля. Номер версии нового приложения .2 отличается от .1, поэтому обновление обнаруживается штатно.

Ограничения: Vivo проверяет пользователь; полная матрица ручных боковых переключателей с минимальным пингом и всех IPv4/IPv6 Direct/VPN/BLOCK сценариев ещё не пройдена. Это тестовый выпуск, не полная приёмка проекта. Незавершённый P1 исключён. Старый Android-пререлиз, каталог движков и Windows latest не изменяются.

Точные исходники приложены отдельно: mcrf-android-source.zip и mcrf-sing-box-7-source.zip с манифестами. Native build tags: with_gvisor, with_quic, with_wireguard, with_utls, with_clash_api, with_mcrf_direct_family. LICENSE upstream и зависимости входят в соответствующий исходный снимок. Контрольные суммы — SHA256SUMS.txt, область проверки — BUILD_EVIDENCE.json. Существующая тестовая подпись Android сохранена.
"@
    [IO.File]::WriteAllText("$stage/NOTES.md",$notes,$utf8)
    $sums=@(Get-ChildItem "$stage/assets" -File|Sort-Object Name|ForEach-Object {(Sha $_.FullName)+'  '+$_.Name});[IO.File]::WriteAllText("$stage/assets/SHA256SUMS.txt",($sums -join "`n")+"`n",$utf8)
    $publicAssets=@(Get-ChildItem "$stage/assets" -File|Sort-Object Name|ForEach-Object {
        $label=if($_.Name -match '^mcrf-engine-sing-box-'){$_.Name+'; mcrf-version-code=7'}else{''}
        @{name=$_.Name;bytes=$_.Length;sha256=(Sha $_.FullName);label=$label}
    })
    $plan=@{tag=$tag;target=(Api 'commits/main').sha;mainSha=$build.artifacts.universal.sha256;buildReceipt=$BuildReceipt;assets=$publicAssets}
    Save $plan "$stage/plan.json"
}
foreach($a in $plan.assets){if((Sha "$stage/assets/$($a.name)") -cne $a.sha256 -or (Get-Item "$stage/assets/$($a.name)").Length -ne $a.bytes){throw 'Prepared asset drift'}}
if(!$Apply){"PREPARED=$stage";return}
$receipt=@{state='PUBLISHING';tag=$tag;published=$false;startedUtc=[DateTime]::UtcNow.ToString('o')}
try {
    if((Shape (Api 'releases/416790557')) -cne (Shape (Get-Content "$stage/old-before.json" -Raw|ConvertFrom-Json)) -or (Shape (Api 'releases/tags/engines-2026-10-13-1')) -cne (Shape (Get-Content "$stage/engines-before.json" -Raw|ConvertFrom-Json))){throw 'Protected remote release changed'}
    $found=if($ResumeReleaseId){@((Api "releases/$ResumeReleaseId"))}else{@((Api 'releases?per_page=50')|Where-Object tag_name -CEQ $tag)}
    if(!$found.Count){Gh @('release','create',$tag,'--repo',$repo,'--target',$plan.target,'--title','MCRF Android 0.1.0-preview.20261019.2','--notes-file',"$stage/NOTES.md",'--draft','--prerelease','--latest=false')|Out-Null;$found=@((Api 'releases?per_page=50')|Where-Object tag_name -CEQ $tag)}
    if($found.Count -ne 1 -or !$found[0].prerelease -or $found[0].tag_name -cne $tag){throw 'Concrete new release identity unavailable'}
    $id=$found[0].id;$receipt.releaseId=$id;Save $receipt "$stage/publication-receipt.json"
    foreach($local in $plan.assets){
        $current=Api "releases/$id";$remote=@($current.assets|Where-Object name -CEQ $local.name)
        if(!$remote.Count){Gh @('release','upload',$tag,"$stage/assets/$($local.name)",'--repo',$repo)|Out-Null;$remote=@((Api "releases/$id").assets|Where-Object name -CEQ $local.name)}
        if($remote.Count -ne 1 -or $remote[0].size -ne $local.bytes -or $remote[0].digest -cne "sha256:$($local.sha256)"){throw 'Remote asset digest/size mismatch'}
        if($local.label -and $remote[0].label -cne $local.label){Gh @('api','--method','PATCH',"repos/$repo/releases/assets/$($remote[0].id)",'-f',"label=$($local.label)")|Out-Null}
        Write-Output "VERIFIED_ASSET=$($local.name)"
    }
    $current=Api "releases/$id";if(@($current.assets).Count -ne @($plan.assets).Count){throw 'Unexpected release assets'}
    foreach($local in $plan.assets){$remote=@($current.assets|Where-Object name -CEQ $local.name);if($remote.Count -ne 1 -or $remote[0].state -cne 'uploaded' -or $remote[0].digest -cne "sha256:$($local.sha256)" -or $remote[0].size -ne $local.bytes -or $local.label -and $remote[0].label -cne $local.label){throw 'Final asset identity mismatch'}}
    if($current.draft){Gh @('api','--method','PATCH',"repos/$repo/releases/$id",'-F','draft=false','-F','prerelease=true','-f','make_latest=false')|Out-Null}
    $final=Api "releases/$id"
    if($final.draft -or !$final.prerelease -or (Api 'releases/latest').tag_name -cne 'v1.0.11' -or (Shape (Api 'releases/416790557')) -cne (Shape (Get-Content "$stage/old-before.json" -Raw|ConvertFrom-Json)) -or (Shape (Api 'releases/tags/engines-2026-10-13-1')) -cne (Shape (Get-Content "$stage/engines-before.json" -Raw|ConvertFrom-Json))){throw 'Final release/protected remote identity mismatch'}
    Save $final "$stage/release-after.json";$receipt.state='PUBLISHED_VERIFIED';$receipt.published=$true;$receipt.url=$final.html_url;$receipt.oldReleaseUnchanged=$true;$receipt.engineCatalogueUnchanged=$true;$receipt.windowsLatestUnchanged=$true
}catch{$receipt.state='FAILED_PRESERVED';$receipt.error=$_.Exception.Message;throw}
finally {$receipt.finishedUtc=[DateTime]::UtcNow.ToString('o');Save $receipt "$stage/publication-receipt.json";"PUBLICATION=$($receipt.state);RECEIPT=$stage/publication-receipt.json"}
