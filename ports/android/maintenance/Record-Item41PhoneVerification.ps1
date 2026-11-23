param([Parameter(Mandatory=$true)][string]$PhoneReceipt,[Parameter(Mandatory=$true)][string]$UiReceipt)
$ErrorActionPreference='Stop'
$stage='D:/MCRF/Builds/Android/item41-prerelease-282c76b04ef4429483bff3674649534c'
$repo='vnenapravo7-source/mcrf';$id=416790557;$tag='android-v0.1.0-preview.20261019.1';$gh='C:/Program Files/GitHub CLI/gh.exe'
function Gh([string[]]$arguments){$out=(& $gh @arguments 2>&1|Out-String).Trim();if($LASTEXITCODE){throw 'GitHub note operation failed; inspect current body before resuming'};$out}
function Api([string]$path){(Gh @('api',"repos/$repo/$path"))|ConvertFrom-Json}
function Shape($release){@($release.assets|Sort-Object name|ForEach-Object {@{id=$_.id;name=$_.name;digest=$_.digest;size=$_.size;state=$_.state}})|ConvertTo-Json -Depth 4 -Compress}
function Normal([string]$text){$text.Replace("`r`n","`n").Trim()}
$phone=Get-Content $PhoneReceipt -Raw|ConvertFrom-Json;$ui=Get-Content $UiReceipt -Raw|ConvertFrom-Json
if(!$phone.passed -or !$phone.appInstalled -or !$phone.protectedUnchanged -or !$phone.modulesUnchanged -or $phone.moduleCount -ne 15 -or $phone.sha256 -cne '35b1881362bc5555cf9bc97d9937578f22feedb6f674c8ca8023d8e0d8284eaa'){throw 'Verified guarded10011 main installation required'}
if($ui.versionCode -ne 10011 -or !$ui.data.protectedUnchangedAcrossUi -or $ui.data.guardedFiles -ne 20 -or $ui.recents.state -cne 'PARTIAL_NOT_ACCEPTED'){throw 'Accurate actual UI evidence required'}
$published=Get-Content "$stage/release-after.json" -Raw|ConvertFrom-Json
$before=Api "releases/$id"
if($before.tag_name -cne $tag -or $before.draft -or !$before.prerelease -or (Shape $published) -cne (Shape $before)){throw 'Published identity/assets changed; no overwrite'}
$original=[IO.File]::ReadAllText("$stage/NOTES.md")
$updated=$original.Replace('Обновление Samsung ожидает разблокировки; установка и сверка данных ещё не выполнены.','После публикации Samsung обновлён до10011: защищённые данные и15 внешних движков сохранены. Подробности дополнительной проверки — ниже.')+@'


### Проверка Samsung после публикации

Версия10011 установлена на Samsung SM-F741B. До/после установки защищённые данные совпали; после обычного просмотра настроек все20 защищённых файлов также остались неизменными. Все15 внешних движков сохранены. Проверены новое название/пояснение DNS, точный переход к расширенным DNS-параметрам, новое меню, страница основного ядра, общая строка действий авторизации прокси и отмена сброса группы. VPN не запускался, профили и настройки не менялись.

Известное ограничение пункта41: при включённом «Скрывать из недавних» карточка MCRF скрыта после перехода через «Домой», но Samsung всё ещё показывает её при прямом переходе из приложения в обзор недавних. Этот сценарий НЕ принят как полностью исправленный.

APK, исходники и контрольные суммы этого релиза не заменялись. BUILD_EVIDENCE.json сохраняет состояние проверки на момент первоначальной публикации, когда телефон ещё был заблокирован; последующая проверка описана здесь. Полная приёмка остальных проектных функций не заявляется.
'@
if((Normal $before.body) -cne (Normal $original) -and (Normal $before.body) -cne (Normal $updated)){throw 'Release notes edited externally; preserve them and ask before reconciliation'}
$path="$stage/PHONE_VERIFICATION_NOTES.md";[IO.File]::WriteAllText($path,$updated,[Text.UTF8Encoding]::new($false))
if((Normal $before.body) -cne (Normal $updated)){Gh @('release','edit',$tag,'--repo',$repo,'--notes-file',$path)|Out-Null}
$after=Api "releases/$id"
if((Normal $after.body) -cne (Normal $updated) -or (Shape $before) -cne (Shape $after)){throw 'Post-note body/assets verification failed'}
if((Api 'releases/latest').tag_name -cne 'v1.0.11'){throw 'Stable latest unexpectedly changed'}
$receipt=@{state='PHONE_NOTES_UPDATED_VERIFIED';releaseId=$id;assetsUnchanged=$true;phoneVersionCode=10011;recents='PARTIAL_NOT_ACCEPTED';updatedUtc=[DateTime]::UtcNow.ToString('o');url=$after.html_url}
[IO.File]::WriteAllText("$stage/phone-notes-receipt.json",($receipt|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
Write-Output "PHONE_NOTES_UPDATED_VERIFIED=$($after.html_url)"
