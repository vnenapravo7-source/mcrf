$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$taskPristine=Join-Path $taskRoot 'work/android-upstream/csqtt-legacy71712b'
$taskRevision='71712b07dbabe16fa43f5a248dc69e1ab9f3a270'
if((& git -C $taskPristine rev-parse HEAD) -ne $taskRevision -or ((& git -C $taskPristine status --porcelain | Out-String).Trim())){throw 'Pinned CSQTT pristine revision changed or contains user edits'}
$taskRequiredTree=@(& git -C $taskPristine ls-tree -r $taskRevision rust-client shared LICENSE)
if($LASTEXITCODE -ne 0 -or !$taskRequiredTree.Count){throw 'Required pinned CSQTT source tree is unavailable'}
$taskRequiredObjects=@($taskRequiredTree|ForEach-Object {($_ -split '\s+')[2]})
$taskObjectChecks=@($taskRequiredObjects | & git -C $taskPristine cat-file --batch-check)
if($LASTEXITCODE -ne 0 -or $taskObjectChecks.Count -ne $taskRequiredObjects.Count -or
   @($taskObjectChecks|Where-Object {$_ -notmatch '^[0-9a-f]{40} blob [0-9]+$'}).Count){throw 'Required pinned CSQTT blob missing; do not build or repair the pristine checkout'}
$taskAdapter=Join-Path $taskRoot 'ports/android/optional-cores/upstream-throne/script/prepare_csqtt.py'
$taskAdapterSha='27ca4d970f34cedd8f44e6cf0a8dbbcc51010ee24a33f238a2850688371dbd00'
if((Get-FileHash -LiteralPath $taskAdapter -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskAdapterSha){throw 'Audited private-file/account adapter changed'}
$taskStage=Join-Path 'D:/MCRF/Builds/Android' ('csqtt-legacy-captcha-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskStage | Out-Null
$taskSource=Join-Path $taskStage 'source'
$taskReceipt=[ordered]@{state='PREPARING_SOURCE';stage=$taskStage;source=$taskSource;revision=$taskRevision;privateAdapterSha256=$taskAdapterSha;accountOptionsContract=1;sourceStable=$false;nativeExecuted=$false;apkBuilt=$false;artifacts=@()}
function Save-CsqttSourceReceipt {[IO.File]::WriteAllText((Join-Path $taskStage 'receipt.json'),($taskReceipt|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))}
Save-CsqttSourceReceipt
Copy-Item -LiteralPath $taskAdapter -Destination (Join-Path $taskStage 'account-options-adapter.py')
Write-Output "CSQTT_LEGACY_STAGE=$taskStage"
try{
    & git clone --no-hardlinks --no-checkout -c core.autocrlf=false $taskPristine $taskSource
    if($LASTEXITCODE -ne 0){throw 'Owned CSQTT source snapshot clone failed'}
    & git -C $taskSource sparse-checkout init --cone
    if($LASTEXITCODE -ne 0){throw 'Owned source sparse initialization failed'}
    & git -C $taskSource sparse-checkout set rust-client shared
    if($LASTEXITCODE -ne 0){throw 'Owned source sparse selection failed'}
    & git -C $taskSource checkout --detach $taskRevision
    if($LASTEXITCODE -ne 0){throw 'Owned source pinned checkout failed'}
    & python $taskAdapter $taskSource
    if($LASTEXITCODE -ne 0){throw 'Existing private-file/desktop I/O adaptation failed'}
    & git -C $taskSource apply --recount (Join-Path $PSScriptRoot 'csqtt-waiters.patch')
    if($LASTEXITCODE -ne 0){throw 'Exact native CAPTCHA waiter patch failed'}
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'mcrf_captcha_waiters.rs') -Destination (Join-Path $taskSource 'rust-client/mcrf_captcha_waiters.rs')
    $taskChanges=@(& git -C $taskSource diff --name-only)
    $taskAllowed=@('rust-client/main.rs','rust-client/captcha.rs','rust-client/tun.rs','rust-client/dispatcher.rs','rust-client/turn_integration_tests.rs','rust-client/udp_batch.rs')
    if(@($taskChanges|Where-Object {$_ -notin $taskAllowed}).Count){throw 'Unexpected native source changes'}
    $taskGitDir=Join-Path $taskSource '.git'
    $taskRows=@(Get-ChildItem -LiteralPath $taskSource -Recurse -File -Force | Where-Object {!$_.FullName.StartsWith($taskGitDir+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)} | Sort-Object FullName | ForEach-Object {
        if($_.LinkType){throw 'Linked native source input rejected'}
        [ordered]@{path=[IO.Path]::GetRelativePath($taskSource,$_.FullName) -replace '\\','/';bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
    [IO.File]::WriteAllText((Join-Path $taskStage 'source-manifest.json'),($taskRows|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
    $taskReceipt.helperSha256=(Get-FileHash -LiteralPath (Join-Path $taskSource 'rust-client/mcrf_captcha_waiters.rs') -Algorithm SHA256).Hash.ToLowerInvariant()
    $taskReceipt.cargoLockSha256=(Get-FileHash -LiteralPath (Join-Path $taskSource 'rust-client/Cargo.lock') -Algorithm SHA256).Hash.ToLowerInvariant()
    $taskReceipt.inputCount=$taskRows.Count;$taskReceipt.state='SOURCE_PREPARED';Save-CsqttSourceReceipt
    Write-Output "CSQTT_SOURCE_FROZEN=$taskSource"
}catch{$taskReceipt.state='FAILED_SOURCE_PREPARATION';Save-CsqttSourceReceipt;throw}
