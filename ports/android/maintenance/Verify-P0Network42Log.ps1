param()
$ErrorActionPreference='Stop'
if($args.Count){throw 'Unknown arguments'}
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation test
$root='D:/MCRF/Projects/MCRF'
$priorStage='D:/MCRF/Builds/Android/p0-network42-60e32072817a449cb83d574866caf84c'
$prior=Get-Content "$priorStage/receipt.json" -Raw|ConvertFrom-Json
if($prior.state -cne 'PASS_HOST_AND_ANDROID_COMPILE_ONLY' -or !$prior.sourceStable -or !$prior.excludedP1 -or $prior.apkBuilt){throw 'Exact passed P0 host/compile parent required'}
$currentPath="$root/ports/android/coordination/current.json"
$current=Get-Content $currentPath -Raw|ConvertFrom-Json
if($current.jobs.build -or $current.jobs.hostTest -or $current.jobs.device){throw 'Existing active job; no duplicate'}
$stage='D:/MCRF/Builds/Android/p0-network42-log-'+[guid]::NewGuid().ToString('N')
[IO.Directory]::CreateDirectory($stage)|Out-Null
$source='ports/android/app/src/main/kotlin/org/mcrf/client/McrfVpnService.kt'
$oldMessage='У физической сети нет IPv6-маршрута в интернет: DNS прямого выхода не выдаёт AAAA. DNS через VPN и IPv6-защита TUN сохранены.'
$newMessage='У физической сети нет IPv6-маршрута в интернет. Для однозначных прямых TCP-маршрутов включён быстрый отказ недоступного IPv6, позволяющий приложению попробовать IPv4. AAAA, IPv6 через VPN и защита TUN не отключены.'
$receipt=[ordered]@{state='PREPARING';stage=$stage;parent="$priorStage/receipt.json";excludedP1=$true;sourceStable=$false;apkBuilt=$false;installed=$false;published=$false;retainedHostTests=34;scope='Correct diagnostic string only. Verify every other immutable source unchanged, retain 34 passed host tests, main+AndroidTest compilation only.'}
function Sha([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Safe([string]$base,[string]$relative){
    $path=[IO.Path]::GetFullPath((Join-Path $base $relative))
    if(!$path.StartsWith([IO.Path]::GetFullPath($base)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Snapshot path escape'}
    $path
}
$names=@('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS');$previousEnv=@{}
foreach($name in $names){$previousEnv[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
    $current.jobs.hostTest="P0_NETWORK42_LOG:$stage";$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8
    $manifest=@(Get-Content "$priorStage/source-manifest.json" -Raw|ConvertFrom-Json)
    foreach($row in $manifest){
        $from=Safe $priorStage $row.path;$to=Safe $stage $row.path
        if((Sha $from) -cne $row.sha256){throw 'Parent source mismatch'}
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($to))|Out-Null;[IO.File]::Copy($from,$to,$false)
    }
    foreach($entry in $prior.canonicalInputs.PSObject.Properties){
        $hash=Sha (Safe $root $entry.Name)
        if($entry.Name -ne $source -and $hash -cne $entry.Value){throw 'Unrelated canonical source changed; cannot retain tests'}
    }
    $canonical=[IO.File]::ReadAllText((Safe $root $source)).Replace("`r`n","`n")
    $currentLine=@($canonical -split "`n"|Where-Object {$_.TrimStart().StartsWith('if(applyRoutes){val routes=RoutingStore(this);')})
    if($currentLine.Count -ne 1 -or !$currentLine[0].Contains($newMessage) -or $canonical.Contains($oldMessage)){throw 'New diagnostic string mismatch'}
    $path=Safe $stage $source;$text=[IO.File]::ReadAllText($path)
    if([regex]::Matches($text,[regex]::Escape($oldMessage)).Count -ne 1){throw 'Exact one diagnostic correction required'}
    $oldLine=@($text -split "`n"|Where-Object {$_.TrimStart().StartsWith('if(applyRoutes){val routes=RoutingStore(this);')})
    if($oldLine.Count -ne 1 -or $oldLine[0].Replace($oldMessage,$newMessage) -cne $currentLine[0]){throw 'Non-message production change; cannot retain tests'}
    [IO.File]::WriteAllText($path,$text.Replace($oldMessage,$newMessage),[Text.UTF8Encoding]::new($false))
    ($manifest|Where-Object {$_.path -ceq $source}).sha256=Sha $path
    $manifest|ConvertTo-Json -Depth 4|Set-Content "$stage/source-manifest.json" -Encoding utf8
    $receipt.sourceSha=Sha (Safe $root $source);$receipt.inputs=$manifest.Count
    $env:JAVA_HOME='C:/Program Files/Java/jdk-17';$env:ANDROID_HOME='C:/Users/dieonfire/AppData/Local/Android/Sdk';$env:GRADLE_USER_HOME="$root/work/toolchains/gradle-home"
    $env:JAVA_TOOL_OPTIONS=($previousEnv.JAVA_TOOL_OPTIONS+' "-Djdk.net.unixdomain.tmpdir=D:/MCRF/Builds/Android/java-tmp"').Trim()
    Push-Location "$stage/ports/android"
    try {& "$env:JAVA_HOME/bin/java.exe" -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain ':app:compileDebugKotlin' ':app:compileDebugAndroidTestKotlin' '--offline' '--console=plain' *> "$stage/verify.log";if($LASTEXITCODE){throw 'Diagnostic compile failed; log preserved'}}finally{Pop-Location}
    foreach($row in $manifest){if((Sha (Safe $stage $row.path)) -cne $row.sha256){throw 'Frozen source changed'}}
    if((Sha (Safe $root $source)) -cne $receipt.sourceSha){throw 'Canonical source changed during compilation'}
    $receipt.sourceStable=$true;$receipt.state='PASS_COMPILE_34_HOST_RETAINED'
}catch{$receipt.state='FAILED_PRESERVED';$receipt.error=$_.Exception.Message;throw}
finally {
    foreach($name in $previousEnv.Keys){[Environment]::SetEnvironmentVariable($name,$previousEnv[$name],'Process')}
    $receipt.finishedUtc=[DateTime]::UtcNow.ToString('o');$receipt|ConvertTo-Json -Depth 5|Set-Content "$stage/receipt.json" -Encoding utf8
    $current=Get-Content $currentPath -Raw|ConvertFrom-Json
    if($current.jobs.hostTest -ceq "P0_NETWORK42_LOG:$stage"){$current.jobs.hostTest=$null;$current|ConvertTo-Json -Depth 20|Set-Content $currentPath -Encoding utf8}
    Write-Output "P0_NETWORK42_LOG=$($receipt.state);RECEIPT=$stage/receipt.json"
}
