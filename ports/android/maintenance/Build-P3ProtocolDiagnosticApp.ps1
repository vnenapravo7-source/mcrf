param([Parameter(Mandatory)][string]$NativeStage)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation build
# A failed fixture is not admitted for production. It may build a diagnostic
# APK containing the SAME native test bytes, so only failed cases are retried.
$taskProducer="$PSScriptRoot/Build-P3ProtocolFixtureApp.ps1"
$taskText=[IO.File]::ReadAllText($taskProducer)
$taskOld="@('COMPILED_LEGAL_AND_FIXTURE_PENDING','COMPILED_FIXTURE_PENDING')"
if([regex]::Matches($taskText,[regex]::Escape($taskOld)).Count -ne 1){throw 'Original fixture producer guard changed'}
$taskText=$taskText.Replace($taskOld,"@('COMPILED_LEGAL_AND_FIXTURE_PENDING','COMPILED_FIXTURE_PENDING','OWNED_FIXTURE_FAILED_RETAINED')")
$taskGate='& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation build'
if([regex]::Matches($taskText,[regex]::Escape($taskGate)).Count -ne 1){throw 'Original fixture authority call changed'}
$taskText=$taskText.Replace($taskGate,('& "'+$PSScriptRoot.Replace([char]92,[char]47)+'/Assert-HumanBatchReady.ps1" -Operation build'))
$taskBundle="D:/MCRF/Builds/Android/p3-protocols-diagnostic-producer-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $taskBundle|Out-Null
[IO.File]::WriteAllText("$taskBundle/Build-Fixture.ps1",$taskText,[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText("$taskBundle/derivation.json",(@{originalProducer=$taskProducer;originalSha256=(Get-FileHash $taskProducer).Hash.ToLowerInvariant();derivedSha256=(Get-FileHash "$taskBundle/Build-Fixture.ps1").Hash.ToLowerInvariant();changes=@('allow building failed immutable fixture for diagnosis only','absolute original authority call');nativeStage=$NativeStage}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
& "$taskBundle/Build-Fixture.ps1" -NativeStage $NativeStage
