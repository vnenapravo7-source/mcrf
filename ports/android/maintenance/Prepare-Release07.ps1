param([Parameter(Mandatory=$true)][string]$Stage)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath($Stage)
if(!$taskRoot.StartsWith('D:\MCRF\Builds\Android\release07-',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected release stage'}
$taskProof=Get-Content "$taskRoot/receipt.json" -Raw|ConvertFrom-Json
if($taskProof.state -cne 'BUILT_NOT_INSTALLED_OR_PUBLISHED' -or !$taskProof.sourceStable){throw 'Complete frozen release build required'}
$taskTools='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Json([string]$Path,$Value){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))}
$taskAssets="$taskRoot/assets"
if(Test-Path $taskAssets){throw 'Asset preparation already exists; inspect exact receipts before resuming'}
$null=New-Item -ItemType Directory $taskAssets
$taskRows=[Collections.Generic.List[object]]::new()
function Package([string]$From,[string]$Name,[string]$Kind,[string]$ExpectedHash=''){
 $taskHash=Hash $From;if($ExpectedHash -and $taskHash -cne $ExpectedHash){throw "Retained artifact differs: $Name"}
 $taskIdentity=(& "$taskTools/aapt.exe" dump badging $From|Select-Object -First 1)
 if($LASTEXITCODE -or $taskIdentity -notmatch "package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'"){throw "APK identity missing: $Name"}
 $taskPackage=$Matches[1];$taskCode=[int]$Matches[2];$taskVersion=$Matches[3]
 $taskExpectedPackage=if($Kind -eq 'app'){'org.mcrf.client'}else{"org.mcrf.engines.$Kind"}
 if($taskPackage -cne $taskExpectedPackage -or $taskVersion -match 'candidate|diagnostic|debug'){throw "Not an ordinary release package: $Name"}
 if($Kind -eq 'app' -and ($taskCode -ne 10059 -or $taskVersion -cne '0.7')){throw 'Main0.7 identity differs'}
 if($Kind -eq 'openflux' -and ($taskCode -ne 14 -or $taskVersion -cne '0.13.0-mcrf.14')){throw 'OpenFlux production identity differs'}
 $taskSignature=(& "$taskTools/apksigner.bat" verify --print-certs $From 2>&1|Out-String)
 if($LASTEXITCODE -or $taskSignature -notmatch 'certificate SHA-256 digest:\s*fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw "APK signer differs: $Name"}
 Copy-Item -LiteralPath $From -Destination "$taskAssets/$Name"
 $taskRows.Add(@{name=$Name;kind=$Kind;package=$taskPackage;versionCode=$taskCode;versionName=$taskVersion;bytes=(Get-Item $From).Length;sha256=$taskHash;source=$From})
}
Package "$taskRoot/app/outputs/apk/release/app-universal-release.apk" 'mcrf-android-universal-release.apk' 'app'
Package "$taskRoot/app/outputs/apk/release/app-universal-release.apk" 'arm64-v8a-release.apk' 'app'
foreach($taskArtifactRoot in @('D:/MCRF/Projects/MCRF/work/android-module-artifacts-3db1f7c2c5a14fa58a2ed3fa01feb7b3','D:/MCRF/Projects/MCRF/work/android-module-artifacts-5d29908c92e1452bae123824b8ff73ef')){
 $taskReceipt=Get-Content "$taskArtifactRoot/artifact-receipt.json" -Raw|ConvertFrom-Json
 $taskBuild=Get-Content "$($taskReceipt.snapshot)/build-result.json" -Raw|ConvertFrom-Json
 if(!$taskBuild.sourceStable -or $taskBuild.exitCode -ne 0){throw 'Retained module build not accepted for packaging'}
 foreach($taskFile in $taskReceipt.files){
  $taskParts=$taskFile.path -split '[/\\]';$taskKind=$taskParts[0]
  if($taskArtifactRoot -match '3db1' -and $taskKind -eq 'steer'){continue}
  if($taskParts[-1] -notmatch '^engines-[a-z0-9]+-(arm64-v8a|armeabi-v7a|x86_64|universal)-release\.apk$'){throw 'Unexpected retained APK filename'}
  $taskAbi=$Matches[1]
  Package (Join-Path $taskArtifactRoot $taskFile.path) "mcrf-engine-$taskKind-$taskAbi-release.apk" $taskKind $taskFile.sha256
 }
}
foreach($taskFile in Get-ChildItem "$taskRoot/engines/outputs/apk/openflux/release" -File -Filter '*.apk'){
 if($taskFile.Name -notmatch '^engines-openflux-(arm64-v8a|armeabi-v7a|x86_64|universal)-release\.apk$'){throw 'Unexpected fresh module APK filename'}
 Package $taskFile.FullName "mcrf-engine-openflux-$($Matches[1])-release.apk" 'openflux'
}
# Publication contract: list limitations instead of treating host models as a
# full phone/provider acceptance matrix. Experimental verification stays off.
$taskBody=@'
## Движки и протоколы

- OpenFlux обновлён до 0.13.0. Исправлена остановка во время незавершённого соединения. На Android проверены зашифрованная передача данных, локальная авторизация, отказ с неверным ключом, STOP и EOF.
- Обновлены модули CSQTT, CSQTT 2.0/2.1.5/2.5, QWDTT, WDTT, Steer, DNSTT и OLC RTC: исправления жизненного цикла, CAPTCHA, маршрутизации и диагностики.
- Добавлен отдельный модуль протоколов TUIC, AnyTLS, Naive и SSH для работы с основным движком Steer.
- Добавлены VayDNS и Slipstream; SSTP и MasterDnsVPN доступны как экспериментальные модули.
- Улучшены обнаружение обновлений движков и проверка совместимости пакетов. Номер версии CSQTT определяется автоматически только при однозначных данных ссылки; неоднозначные старые ссылки не угадываются.

## Интерфейс и настройки

- Добавленный сервер сразу появляется в списке без перехода в другой раздел.
- Упрощено окно добавления движка.
- В разделе «VPN и прокси» добавлены три настраиваемых сайта проверки **наличия БС**: готовые варианты выбираются из списка, собственный домен вводится через карандаш.
- Для уже привязанного VK отображается только кнопка «Отвязать».
- Улучшены оформление диалогов и отображение состояний фоновых операций.

## Подключение и маршрутизация

- Исправления восстановления после сна и смены сети, обновления активных групп маршрутов и обработки остановки вспомогательных процессов.
- Исправлены отдельные ошибки DNS, GeoData и сохранения связанных настроек маршрутизации.

## Импорт, хранение и диагностика

- Усилены проверки импорта, UTF-8 и ограничений размера, обработка отмены и защита сохранённых данных при ошибке.
- Улучшены сохранение черновиков, резервное копирование и сообщения о неопределённом результате записи.
- Исправлены отдельные сценарии подписок, автодеплоя и повторного выполнения операций; улучшено скрытие секретов и объяснение сетевых ошибок.
- Обновлена совместимость окна авторизации VK; успешный вход зависит также от доступности и поведения VK.

Обновление устанавливается поверх приложения с сохранением настроек и подключений. Движки обновляются отдельно. Проверка OpenFlux выполнена на изолированном прямом зашифрованном транспорте; это не подтверждение работы каждого внешнего провайдера или CAPTCHA. Экспериментальные ownHTTP/inline-интерфейсы и установка скриптов OpenFlux в этом модуле не включены. Для CSQTT 2.5 публикуется проверенный пакет arm64; наличие native-сборки другой архитектуры не означает готовый Android-пакет.
'@
[IO.File]::WriteAllText("$taskRoot/release-notes.txt",$taskBody,[Text.UTF8Encoding]::new($false))
Json "$taskRoot/assets-plan.json" @{state='APK_ASSETS_VERIFIED_NOT_PUBLISHED';mainVersion='0.7';moduleKinds=@($taskRows|Where-Object {$_.kind -ne 'app'}|ForEach-Object {$_.kind}|Sort-Object -Unique);files=$taskRows;signer='fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0';sourceArchivesPrepared=$false;published=$false}
Write-Output "RELEASE07_ASSETS=$($taskRows.Count);PLAN=$taskRoot/assets-plan.json"
