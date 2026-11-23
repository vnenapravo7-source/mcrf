param([Parameter(Mandatory=$true)][string]$BuildReceipt,[string]$PhoneReceipt='',[switch]$Apply,[string]$ResumeStage='',[long]$ResumeReleaseId=0,[switch]$PrepareCorrectionRevision,[switch]$PrepareUiRound2Revision,[switch]$PrepareSelectionHotfixRevision,[string]$HumanApprovalReceipt='')
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF';$repo='vnenapravo7-source/mcrf';$tag='android-v0.1.0-preview.20261019.1';$gh='C:/Program Files/GitHub CLI/gh.exe';$utf8=[Text.UTF8Encoding]::new($false)
function Gh([string[]]$arguments){$out=(& $gh @arguments 2>&1|Out-String).Trim();if($LASTEXITCODE){throw "GitHub operation failed; inspect remote state before resuming: $($arguments[0])"};$out}
function Api([string]$path){(Gh @('api',"repos/$repo/$path"))|ConvertFrom-Json}
function Sum([string]$file){(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()}
function Save($value,[string]$file){[IO.File]::WriteAllText($file,($value|ConvertTo-Json -Depth 14),$utf8)}
function EngineShape($release){@($release.assets|Sort-Object name|ForEach-Object {@{id=$_.id;name=$_.name;digest=$_.digest;size=$_.size;label=$_.label}})|ConvertTo-Json -Depth 4 -Compress}
$build=Get-Content $BuildReceipt -Raw|ConvertFrom-Json;$phone=if($PhoneReceipt){Get-Content $PhoneReceipt -Raw|ConvertFrom-Json}else{$null}
$expectedCode=if($PrepareCorrectionRevision){10012}else{10011}
if($PrepareSelectionHotfixRevision){
    if($PrepareCorrectionRevision -or $PrepareUiRound2Revision -or $Apply -or $ResumeStage -or $ResumeReleaseId -or !$phone -or !$HumanApprovalReceipt){throw '10017 only prepares same-release replacement with guarded install and fresh human approval'}
    $approval=Get-Content $HumanApprovalReceipt -Raw|ConvertFrom-Json
    if(!$approval.humanApprovedSameReleaseRefresh -or !$approval.humanReportedAppChecked -or $approval.versionCode -ne 10017 -or $approval.releaseId -ne 416790557 -or $approval.tag -cne $tag -or $approval.mainSha256 -cne $build.artifacts.universal.sha256 -or $phone.versionCode -ne 10017){throw 'Fresh10017 human approval must bind exact installed artifact and existing release'}
    $expectedCode=10017
}
if($PrepareUiRound2Revision){
    if($PrepareCorrectionRevision -or $Apply -or $ResumeStage -or $ResumeReleaseId -or !$phone -or !$build.samePrereleaseAuthorized -or !$build.retainedUiEvidence){throw 'Complete approved UIround2 can ONLY prepare SAME-release replacement after guarded install'}
    $expectedCode=10015
}
if($PrepareCorrectionRevision -and ($Apply -or $ResumeStage -or $ResumeReleaseId -or !$phone -or !$build.retainedRecentsEvidence)){throw 'Correction mode prepares guarded revision only; use exact same-release refresh tool, never create'}
if($build.state -cne 'PASS' -or !$build.sourceStable -or !$build.excludedP1 -or $build.versionCode -ne $expectedCode){throw 'Verified frozen item41 build required'}
if($PhoneReceipt -and (!$phone.passed -or !$phone.appInstalled -or !$phone.protectedUnchanged -or !$phone.modulesUnchanged -or $phone.sha256 -cne $build.artifacts.universal.sha256)){throw 'Supplied phone evidence is not a guarded installation'}
# App-only prerelease publication is independently authorized. A locked phone
# blocks installation, not publication of this host-verified preview. Pending
# hardware acceptance must stay explicit in both immutable public evidence/notes.
if($ResumeStage){
    $stage=[IO.Path]::GetFullPath($ResumeStage)
    if($stage -cnotmatch '^D:\\MCRF\\Builds\\Android\\item41-prerelease-[a-f0-9]{32}$'){throw 'Unexpected prepared stage'}
    $plan=Get-Content "$stage/plan.json" -Raw|ConvertFrom-Json
    if($plan.tag -cne $tag -or $plan.mainSha256 -cne $build.artifacts.universal.sha256){throw 'Prepared plan mismatch'}
}else{
    $stage=Join-Path 'D:/MCRF/Builds/Android' ('item41-prerelease-'+[guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory("$stage/app")|Out-Null
    $engine=Api 'releases/tags/engines-2026-10-13-1';Save $engine "$stage/engine-before.json"
    if((Api 'releases/latest').tag_name -cne 'v1.0.11'){throw 'Unexpected stable latest; no publication'}
    $mapping=@{'universal'='mcrf-android-universal-release.apk';'arm64-v8a'='arm64-v8a-release.apk';'armeabi-v7a'='armeabi-v7a-release.apk';'x86_64'='androidApp-x86_64-release.apk'}
    foreach($kind in $mapping.Keys){$artifact=$build.artifacts.$kind;if((Sum $artifact.apk) -cne $artifact.sha256){throw 'Frozen artifact changed'};[IO.File]::Copy($artifact.apk,"$stage/app/$($mapping[$kind])",$false)}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::Open("$stage/app/mcrf-android-source.zip",[IO.Compression.ZipArchiveMode]::Create)
    try{
        $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach($row in @(Get-Content "$($build.stage)/source-manifest.json" -Raw|ConvertFrom-Json)){
            $path=$row.path -replace '\\','/'
            $ordinary=$path -match '^ports/(android|catalogue|fixtures)/' -and $path -notmatch '(^|/)(work|build|\.git|private|coordination)(/|$)'
            $spi=$path -in @('work/steer-android-port/android-vpn/integration-helper/src/main/java/org/mcrf/steerprep/SteerMcrfSnapshotAdapter.java','work/steer-android-port/android-vpn/module-contract/src/main/java/org/mcrf/engine/spi/v1/Engine.java','work/steer-android-port/android-vpn/module-contract/src/main/java/org/mcrf/engine/spi/v1/Platform.java')
            if((!$ordinary -and !$spi) -or $path -match '(^|/)(\.\.?|local\.properties)(/|$)|\.(jks|keystore|p12|dpapi)$' -or !$seen.Add($path)){throw 'Unexpected or private source archive input'}
            $file=[IO.Path]::GetFullPath((Join-Path $build.stage $path))
            if(!$file.StartsWith([IO.Path]::GetFullPath($build.stage)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Sum $file) -cne $row.sha256){throw 'Frozen public source changed'}
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file,$path,[IO.Compression.CompressionLevel]::Optimal)|Out-Null
        }
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$($build.stage)/source-manifest.json",'SOURCE_MANIFEST.json',[IO.Compression.CompressionLevel]::Optimal)|Out-Null
        if(Test-Path -LiteralPath "$root/LICENSE"){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$root/LICENSE",'LICENSE',[IO.Compression.CompressionLevel]::Optimal)|Out-Null}
    }finally{$zip.Dispose()}
    $evidence=[ordered]@{schema=1;versionCode=10011;versionName=$build.versionName;scope='Items40/41 UI, DNS explanation and settings. Not full-project acceptance.';sourceManifestSha256=(Sum "$($build.stage)/source-manifest.json");sourceStable=$true;excludedUnfinishedP1=$true;baseNativeLibraries=0;signing='Existing Android preview/test certificate lineage; not a production-key release';signerSha256=$build.signer;affectedTests=@($build.tests|ForEach-Object {@{suite=$_.suite;tests=$_.tests}});androidTestCompiled=$true;phone=@{model='SM-F741B';mainInstalled=$true;protectedDataExactAtInstall=$phone.protectedUnchanged;externalModulesUnchanged=$phone.moduleCount};notRepeated='Closed unchanged renderer/network/native suites';knownOpen='Full settings runtime/reset/narrow-layout acceptance, real providers and other mandatory project gates are not claimed by this scoped preview.'}
    if(!$phone){$evidence.phone=@{model='SM-F741B';mainInstalled=$false;pending='Phone locked; no install or data mutation performed'}}
    $evidence.versionCode=$build.versionCode
    if($PrepareUiRound2Revision){
        $evidence.scope='Complete item41 UIround2: dialog footer/keyboard viewport, system bars,70%opaque connection panel,neutral surfaces,Advanced exact return,spacing,no header underline; manual cross-pool CONNECT selects minimum displayed successful target-pool ping. Existing automatic policy unchanged.'
        $evidence.retainedAffectedUiChecks=22;$evidence.retainedKeyboardChecks=7
        $evidence.knownOpen='No renewed real VPN/provider connection acceptance; pure selection9 checks plus compile/R8. Scoped UI evidence is separate, not full-project acceptance.'
    }
    if($PrepareSelectionHotfixRevision){
        $evidence.scope='Item41 complete selection hotfix: in-place server selection, target-pool minimum-ping fallback, active explicit server handover after STOP acknowledgement and full worker retirement, full-width selected pencil, consumed stationary longpress and both swipe trays.'
        $evidence.retainedHotfixPolicyChecks=$build.retainedHotfixPolicyChecks
        $evidence.acceptance='Human reports personally checking installed10017 and explicitly approves this same-release refresh. Not an independently observed final17 network PASS.'
        $evidence.knownOpen='Earlier10016 runtime worker-busy failure retained; corrected10017 host checks/compile/R8 passed. Final17 guarded install exact20 protected files/15 modules. Agent17 real-network handover not executed; no full-project/provider acceptance claimed.'
        $evidence.priorUiEvidence='Previous10015 keyboard/theme/UI evidence retained; unchanged suites not repeated.'
        [IO.File]::Copy($HumanApprovalReceipt,"$stage/human-approval.json",$false)
    }
    if($PrepareCorrectionRevision){$evidence.scope='Full item41 corrections: UI-only Recents departure,90% opaque panel,VPN metrics removed, unified buttons, exact search anchor,5 DNS groups, proxy/core cleanup, no-op Apply,20 SVG pairs and background-prepared big rule. Scoped phone evidence separate; no new VPN/provider acceptance.';$evidence.recentsPolicyChecks=13;$evidence.retainedAffectedUiChecks=31;$evidence.knownOpen='No renewed VPN/provider/network gate, no full-project acceptance. Same existing release/tag, revision10012.'}
    Save $evidence "$stage/app/BUILD_EVIDENCE.json"
    $sums=@(Get-ChildItem "$stage/app" -File|Sort-Object Name|ForEach-Object {(Sum $_.FullName)+'  '+$_.Name})
    [IO.File]::WriteAllText("$stage/app/SHA256SUMS.txt",($sums -join "`n")+"`n",$utf8)
    $notes=@'
MCRF Android 0.1.0-preview.20261019.1 · versionCode 10011

Пункты 40/41: обновлены оформление подключения и взрыв литейной формы при отключении; упрощены вкладки VPN/БС и настройки. VPN, локальный прокси и доступ другим устройствам собраны вместе. Обновления приложения и движков находятся в одном разделе. Расширенные параметры сгруппированы, добавлены отдельные подтверждаемые сбросы групп без сброса серверов и паролей.

«DNS для подключения к серверам» получил понятное название и пояснение: определяет IP сервера до запуска VPN, не является DNS локальной сети. Восстановление подключения включено по умолчанию, ручное отключение сохраняется.

Проверены затронутые JVM-тесты, компиляция приложения/AndroidTest и R8 APK. Обновление Samsung выполнено с точной сверкой защищённых данных и 15 внешних движков до/после установки. Это ограниченная проверка пререлиза, не полная приёмка всех функций проекта.

Основной APK не содержит нативных движков; их существующий каталог не изменён. Незавершённая подготовка P1 исключена. Старые релизы не заменяются; стабильный Windows-релиз остаётся последним стабильным.

Важно: используется существующая тестовая подпись Android-пререлизов, не production-ключ. Перед установкой сохраняйте зашифрованный бэкап. APK для разных ABI имеют отдельные имена; для обычной установки подходит universal.

Контрольные суммы — SHA256SUMS.txt; исходники именно проверенного снимка — mcrf-android-source.zip; область проверки и ограничения — BUILD_EVIDENCE.json.
'@
    if(!$phone){$notes=$notes.Replace('Обновление Samsung выполнено с точной сверкой защищённых данных и 15 внешних движков до/после установки.','Обновление Samsung ожидает разблокировки; установка и сверка данных ещё не выполнены.')}
    if($PrepareCorrectionRevision){$notes="MCRF Android 0.1.0-preview.20261019.1 · revision10012 (тот же пререлиз)`n`nПункт41: исправления недавних Samsung, панель подключения90% непрозрачности, удалены показатели скорости/сеанса VPN, единый дизайн кнопок и DNS из5групп. Точный переход из поиска, упрощение прокси и меню, Apply без изменений не перерисовывает страницу.20сочетаний мелких SVG-узоров, карточки следуют палитре, сетка иконок и цветовые образцы без подписей. Большое правило «Всё кроме РФ» подготавливается в фоне, полный текст не сокращается при сохранении.`n`nВсе затронутые проверки и R8 сборка выполнены из неизменяемого P0 снимка. Установка Samsung main-only: защищённые данные и15движков сверены. Скрытие из недавних закрывает только окно при выходе пользователя; не останавливает VPN. Новые транспортные подключения не проверялись. Это scoped UI-пререлиз, не полная приёмка проекта.`n`nПодпись прежняя preview/test, не production. Исходники проверенного снимка, контрольные суммы и область проверки приложены. Старый10011 сохранён локально; тег/релиз те же. Windows stable и каталог движков не меняются.`n"}
    if($PrepareUiRound2Revision){$notes="MCRF Android 0.1.0-preview.20261019.1 · revision10015 (тот же пререлиз)`n`nПункт41: исправлены кнопки диалогов и высота выбора приложений при клавиатуре, смена системных панелей со светлой/тёмной темой, возврат из расширенных настроек в исходный раздел. Упорядочены фон карточек/нижней панели и отступы; панель подключения прозрачна на30%; убраны подчёркивания VPN/БС в заголовке.`n`nПри ручном подключении в VPN с выбранным БС-сервером (и наоборот) автоматически выбирается сервер нужного раздела с наименьшим успешным пингом из списка. При отсутствии успешных замеров сначала выбирается непроверенный сервер; при равенстве сохраняется порядок списка. Уже выбранный сервер своего раздела и политика автовыбора не меняются.`n`n9новых проверок выбора,7проверок клавиатурного viewport и22проверки UI, компиляция Main/AndroidTest и R8: неизменяемые P0-снимки. Samsung main-only обновлён с точной сверкой защищённых данных и15внешних движков. Реальное VPN/провайдерское подключение заново не проверялось; это ограниченная проверка, не полная приёмка проекта.`n`nТестовая подпись Android preview сохранена, production-ключ не используется. Исходники проверенной сборки, SHA256SUMS и BUILD_EVIDENCE приложены. Предыдущий10012 сохранён локально; релиз и тег те же. Windows stable и каталог движков не меняются.`n"}
    if($PrepareSelectionHotfixRevision){$notes="MCRF Android 0.1.0-preview.20261019.1 · revision10017 (тот же пререлиз)`n`nПункт41: выбор сервера обновляет строку без перезагрузки страницы. При подключении из другого раздела выбирается сервер нужного VPN/БС-пула с минимальным успешным пингом; устранены лишние требования выбрать сервер. Нажатие сервера при активном подключении переключает на выбранный сервер, включая VPN↔БС: сначала подтверждается остановка и освобождение занятого движка, затем выполняется обычная проверка и запуск.`n`nВыделение строки включает карандаш редактирования. Долгое удержание не вызывает свайп и не выбирает сервер при отпускании. Восстановлена отрисовка значков действий при свайпе в обе стороны. Ранее внесённые исправления оформления и настроек сохранены.`n`nПроверено: неизменяемый P0-снимок,21 затронутая проверка (20 сохранены по точным исходникам,1 уточнённая выполнена заново), компиляция Main/AndroidTest и R8.10017 уже установлен на Samsung,20 защищённых файлов и15 внешних движков при установке сохранены точно. Пользователь сообщил, что лично проверил приложение, и одобрил обновление файлов. Итоговое сетевое переключение10017 агентом независимо не подтверждалось; прежняя ошибка10016 сохранена в диагностике. Это ограниченный пререлиз, не полная приёмка проекта.`n`nПодпись прежняя preview/test, не production. Основной APK без встроенных нативных движков; незавершённый P1 исключён. Исходники проверенной сборки, SHA256SUMS и BUILD_EVIDENCE приложены. Предыдущий10015 сохранён локально; тег и релиз те же. Windows stable и каталог движков не меняются.`n"}
    [IO.File]::WriteAllText("$stage/NOTES.md",$notes,$utf8)
    $plan=[ordered]@{schema=1;tag=$tag;mainSha256=$build.artifacts.universal.sha256;phoneVerified=($null -ne $phone);target=(Api 'commits/main').sha;assets=@(Get-ChildItem "$stage/app" -File|Sort-Object Name|ForEach-Object {@{name=$_.Name;bytes=$_.Length;sha256=(Sum $_.FullName)}})}
    Save $plan "$stage/plan.json"
}
if($ResumeStage -and $phone -and !$plan.phoneVerified){
    if(@((Api 'releases?per_page=50')|Where-Object tag_name -CEQ $tag).Count){throw 'Do not change a prepared plan after external upload began'}
    $evidence=Get-Content "$stage/app/BUILD_EVIDENCE.json" -Raw|ConvertFrom-Json
    $evidence.phone=[pscustomobject]@{model='SM-F741B';mainInstalled=$true;protectedDataExactAtInstall=$phone.protectedUnchanged;externalModulesUnchanged=$phone.moduleCount}
    Save $evidence "$stage/app/BUILD_EVIDENCE.json"
    $notes=[IO.File]::ReadAllText("$stage/NOTES.md").Replace('Обновление Samsung ожидает разблокировки; установка и сверка данных ещё не выполнены.','Обновление Samsung выполнено с точной сверкой защищённых данных и 15 внешних движков до/после установки.')
    [IO.File]::WriteAllText("$stage/NOTES.md",$notes,$utf8)
    $sums=@(Get-ChildItem "$stage/app" -File|Where-Object Name -CNE 'SHA256SUMS.txt'|Sort-Object Name|ForEach-Object {(Sum $_.FullName)+'  '+$_.Name})
    [IO.File]::WriteAllText("$stage/app/SHA256SUMS.txt",($sums -join "`n")+"`n",$utf8)
    $plan.phoneVerified=$true;$plan.assets=@(Get-ChildItem "$stage/app" -File|Sort-Object Name|ForEach-Object {@{name=$_.Name;bytes=$_.Length;sha256=(Sum $_.FullName)}})
    Save $plan "$stage/plan.json"
}
foreach($asset in $plan.assets){if((Sum "$stage/app/$($asset.name)") -cne $asset.sha256 -or (Get-Item "$stage/app/$($asset.name)").Length -ne $asset.bytes){throw 'Prepared asset changed'}}
if(!$Apply){Write-Output "PREPARED=$stage";return}
$receipt=[ordered]@{state='STARTED';tag=$tag;published=$false;phoneVerified=$plan.phoneVerified;stage=$stage;startedUtc=[DateTime]::UtcNow.ToString('o')};Save $receipt "$stage/publication-receipt.json"
try {
    $engineBefore=Get-Content "$stage/engine-before.json" -Raw|ConvertFrom-Json
    if((EngineShape $engineBefore) -cne (EngineShape (Api 'releases/tags/engines-2026-10-13-1'))){throw 'Engine catalogue changed externally; inspect before publishing'}
    $existing=if($ResumeReleaseId){@((Api "releases/$ResumeReleaseId"))}else{@((Api 'releases?per_page=50')|Where-Object tag_name -CEQ $tag)}
    if(!$existing.Count){
        Gh @('release','create',$tag,'--repo',$repo,'--target',$plan.target,'--title','MCRF Android 0.1.0-preview.20261019.1','--notes-file',"$stage/NOTES.md",'--draft','--prerelease','--latest=false')|Out-Null
        $existing=@((Api 'releases?per_page=50')|Where-Object tag_name -CEQ $tag)
    }
    if($existing.Count -ne 1 -or $existing[0].tag_name -cne $tag -or !$existing[0].prerelease){throw 'Unexpected release identity'}
    $id=$existing[0].id;$receipt.releaseId=$id;Save $receipt "$stage/publication-receipt.json"
    foreach($local in $plan.assets){
        $current=Api "releases/$id";$remote=@($current.assets|Where-Object name -CEQ $local.name)
        if($remote.Count -eq 1 -and $remote[0].state -ceq 'uploaded' -and $remote[0].size -eq $local.bytes -and $remote[0].digest -ceq "sha256:$($local.sha256)"){continue}
        if($remote.Count -or !$current.draft){throw 'Refuse to overwrite published or mismatching asset'}
        Gh @('release','upload',$tag,"$stage/app/$($local.name)",'--repo',$repo)|Out-Null
        Write-Output "Uploaded=$($local.name)"
    }
    $current=Api "releases/$id"
    if(@($current.assets).Count -ne @($plan.assets).Count){throw 'Unexpected release assets'}
    foreach($local in $plan.assets){$remote=@($current.assets|Where-Object name -CEQ $local.name);if($remote.Count -ne 1 -or $remote[0].state -cne 'uploaded' -or $remote[0].size -ne $local.bytes -or $remote[0].digest -cne "sha256:$($local.sha256)"){throw 'Remote digest/size verification failed'}}
    if($current.draft){Gh @('api','--method','PATCH',"repos/$repo/releases/$id",'-F','draft=false','-F','prerelease=true','-f','make_latest=false')|Out-Null}
    $final=Api "releases/$id"
    if($final.draft -or !$final.prerelease -or (Api 'releases/latest').tag_name -cne 'v1.0.11'){throw 'Visibility/stable-latest verification failed'}
    if((EngineShape $engineBefore) -cne (EngineShape (Api 'releases/tags/engines-2026-10-13-1'))){throw 'Engine catalogue changed; retain evidence'}
    Save $final "$stage/release-after.json"
    $receipt.state='PUBLISHED_VERIFIED';$receipt.published=$true;$receipt.url=$final.html_url;$receipt.engineCatalogueUnchanged=$true;$receipt.stableWindowsLatestPreserved=$true
}catch{$receipt.state='FAILED_PRESERVED';$receipt.error=$_.Exception.Message;throw}
finally{$receipt.finishedUtc=[DateTime]::UtcNow.ToString('o');Save $receipt "$stage/publication-receipt.json";Write-Output "PUBLICATION=$($receipt.state);RECEIPT=$stage/publication-receipt.json"}
