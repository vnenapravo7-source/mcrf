param([Parameter(Mandatory=$true)][string]$InstallReceipt)
$ErrorActionPreference='Stop'
if($args.Count){throw 'Unexpected TLS diagnostic arguments'}
$checker=Join-Path $PSScriptRoot '../test-installed-csqtt-https.ps1'
$source=Get-Content -Raw -LiteralPath $checker
$boundary=[regex]::Match($source,'(?m)^Idle\r?$')
if(!$boundary.Success){throw 'Preflight boundary changed'}
$checkerRoot=Split-Path ([IO.Path]::GetFullPath($checker))
. ([scriptblock]::Create($source.Substring(0,$boundary.Index).Replace('$PSScriptRoot','$checkerRoot'))) -InstallReceipt $InstallReceipt
$evidence=Join-Path $root ('work/ports-test-2026-10-14/tls-vpn-diagnostic-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $evidence|Out-Null
$remote='/data/local/tmp/mcrf-tls-diagnostic.xml'
$result=[ordered]@{stage=$evidence;started=$false;defaultChanged=$false;selectionChanged=$false;metrics=@();error=$null}
function One($doc,[string]$text,[string]$description='') {
    $nodes=@($doc.SelectNodes('//node')|Where-Object {($_.text -ceq $text -or ($description -and $_.'content-desc' -ceq $description)) -and $_.enabled -ceq 'true'})
    if($nodes.Count -ne 1){throw "Expected one observed control: $text"};return $nodes[0]
}
try{
    Idle;$before=Hashes;WriteEvidence 'protected-before.json' @{hashes=$before}
    Adb @('shell','am','start','-n','org.mcrf.client/.MainActivity')|Out-Null
    Start-Sleep -Seconds 2
    $doc=Ui
    Tap (One $doc 'Маршруты');$doc=Ui
    $direct=One $doc 'Напрямую';Tap $direct;$doc=Ui
    Tap (One $doc 'VPN / прокси');$result.defaultChanged=$true
    $doc=Ui;Tap (One $doc 'VPN' 'VPN');$doc=Ui
    for($i=0;$i -lt 7;$i++){
        $rows=@($doc.SelectNodes('//node')|Where-Object {$_.class -ceq 'android.widget.FrameLayout' -and $_.'content-desc' -match 'Sweden 21\.09 VLESS tcp REALITY, VLESS, outright7\.site,'})
        if($rows.Count -eq 1){break};$doc=ScrollUi $doc $false
    }
    if($rows.Count -ne 1){throw 'Existing exact Sweden server missing; no new profile'}
    if(@($doc.SelectNodes('//node')|Where-Object {$_.class -ceq 'android.widget.Switch' -and $_.text -ceq 'Автовыбор' -and $_.checked -ceq 'true'}).Count){throw 'Auto mode would edit pool; no tap'}
    Tap $rows[0];$result.selectionChanged=$true;$doc=Ui
    for($i=0;$i -lt 7;$i++){
        $connect=@($doc.SelectNodes('//node')|Where-Object {$_.'content-desc' -ceq 'Подключить' -and $_.enabled -ceq 'true'})
        if($connect.Count -eq 1){break};$doc=ScrollUi $doc $true
    }
    if($connect.Count -ne 1){throw 'Observed Connect missing'}
    Tap $connect[0];$result.started=$true
    $clock=[Diagnostics.Stopwatch]::StartNew()
    do{$v=VpnState;if($v.active -and $v.ownerPackage -ceq 'org.mcrf.client' -and $v.activeType -eq 1){break};Start-Sleep -Seconds 1}while($clock.Elapsed.TotalSeconds -lt 55)
    if(!$v.active -or $v.ownerPackage -cne 'org.mcrf.client'){throw 'No owned VPN established'}
    $result.connected=$true
    foreach($family in @('default','ipv4','ipv6')){
        Guard
        $familyArg=if($family -ceq 'default'){''}else{"--$family "}
        $command="/system/bin/curl ${familyArg}--head --proto =https --connect-timeout 10 --max-time 20 --silent --show-error --output /dev/null --write-out http=%{http_code},ip=%{remote_ip},tcp=%{time_connect},tls=%{time_appconnect},total=%{time_total} https://www.google.com/generate_204"
        $out=(& $adb -s $device shell $command 2>&1|Out-String).Trim();$code=$LASTEXITCODE
        $row=@{family=$family;exit=$code;metrics=$out};$result.metrics+=@($row)
        Write-Output ('TLS_FAMILY='+($row|ConvertTo-Json -Compress))
    }
}catch{$result.error=$_.Exception.Message}
finally{
    WriteEvidence 'receipt.json' $result
    Write-Output ('TLS_DIAGNOSTIC='+($result|ConvertTo-Json -Depth 4 -Compress))
}
if($result.error){throw 'Diagnostic preserved; restore only recorded owned UI changes, no automatic retry'}
