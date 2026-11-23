$ErrorActionPreference='Stop'
$stage='D:/MCRF/Builds/Android/publish-android-v0.1.0-preview.20261017.5'
$receipt=Get-Content 'D:/MCRF/Projects/MCRF/work/ports-test-2026-10-14/engine-startup-ui-6112488314964eea87a2c76279604f6f/receipt.json' -Raw|ConvertFrom-Json
$plan=Get-Content "$stage/plan.json" -Raw|ConvertFrom-Json
$utf8=[Text.UTF8Encoding]::new($false)
if(!$receipt.passed -or !$receipt.modulesUnchanged -or $receipt.appSha256 -cne '1f4d38b0b5355f32244911b432cd9d6bdd864e0012ea909142b8a6962377373a' -or $plan.snapshot -cne $receipt.snapshot){throw 'Exact Android UI evidence required'}
foreach($row in $plan.appAssets){if((Get-Item "$stage/app/$($row.name)").Length -ne $row.size -or (Get-FileHash "$stage/app/$($row.name)").Hash.ToLowerInvariant() -cne $row.sha256){throw 'Prepared app asset changed before evidence update'}}
$evidence=Get-Content "$stage/app/BUILD_EVIDENCE.json" -Raw|ConvertFrom-Json
$evidence|Add-Member -Force -NotePropertyName startupUi -NotePropertyValue 'PASS exact minified10009 Android34 x86_64 owned read-only emulator, live enduring catalogue. Mixed installed versions: old CSQTT/QWDTT offered, current WDTT/DNSTT and absent CSQTT2.5 excluded. Update opens explicit batch-download consent; cancel leaves engine hashes unchanged. Not actual download/install/Vivo/provider acceptance.'
[IO.File]::WriteAllText("$stage/app/BUILD_EVIDENCE.json",($evidence|ConvertTo-Json -Depth 5),$utf8)
$sums=Get-ChildItem "$stage/app" -File|Where-Object Name -CNE 'SHA256SUMS.txt'|Sort-Object Name|ForEach-Object {(Get-FileHash $_.FullName).Hash.ToLowerInvariant()+'  '+$_.Name}
[IO.File]::WriteAllText("$stage/app/SHA256SUMS.txt",($sums -join "`n")+"`n",$utf8)
$plan.appAssets=@(Get-ChildItem "$stage/app" -File|ForEach-Object {@{name=$_.Name;size=$_.Length;sha256=(Get-FileHash $_.FullName).Hash.ToLowerInvariant()}})
[IO.File]::WriteAllText("$stage/plan.json",($plan|ConvertTo-Json -Depth 8),$utf8)
Write-Output 'Exact Android startup UI evidence recorded; APK and source archive bytes unchanged.'
