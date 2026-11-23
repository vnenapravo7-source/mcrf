$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskBundle=Get-Content -LiteralPath (Join-Path $taskRoot 'native/assets/preset-bundle.json') -Raw | ConvertFrom-Json
$taskUrls=@('https://raw.githubusercontent.com/vnenapravo7-source/iplist-domains/main/ru/ru.txt','https://raw.githubusercontent.com/ipverse/country-ip-blocks/master/country/ru/ipv4-aggregated.txt','https://raw.githubusercontent.com/ipverse/country-ip-blocks/master/country/ru/ipv6-aggregated.txt')
$taskParts=@('ru','su','xn--p1ai','10.0.0.0/8','172.16.0.0/12','192.168.0.0/16','169.254.0.0/16','100.64.0.0/10','fc00::/7','fe80::/10')
foreach($taskUrl in $taskUrls){$taskEntry=$taskBundle.Texts.PSObject.Properties[$taskUrl];if(!$taskEntry -or $taskEntry.Value.Length -lt 1000){throw 'Missing bundled Russia list'};$taskParts+=$taskEntry.Value}
$taskAssets=Join-Path $PSScriptRoot 'app/src/main/assets';New-Item -ItemType Directory -Path $taskAssets -Force | Out-Null
$taskBytes=[Text.Encoding]::UTF8.GetBytes(($taskParts -join "`n"))
# Derived compressed asset from the existing Windows snapshot, not a network fetch or handwritten source.
$taskFile=[IO.File]::Create((Join-Path $taskAssets 'russia-routes.bin'))
try{$taskGzip=New-Object IO.Compression.GZipStream($taskFile,[IO.Compression.CompressionLevel]::Optimal);try{$taskGzip.Write($taskBytes,0,$taskBytes.Length)}finally{$taskGzip.Dispose()}}finally{$taskFile.Dispose()}
