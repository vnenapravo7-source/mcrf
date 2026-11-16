param([string]$Core='')
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if(!$Core){$Core=Join-Path $taskRoot 'work/sing-box-lx/sing-box-1.14.2-lx.11-windows-amd64/sing-box.exe'}
if(!(Test-Path -LiteralPath $Core)){throw 'Pinned core is required to compile routing presets'}
if((Get-FileHash -LiteralPath $Core).Hash.ToLowerInvariant() -ne '1bf6c329963fbb6e4e80079f28e2079c0a6c3a860e8bdecf9a4dfc7f5bfa6b0f'){throw 'Preset compiler SHA-256 mismatch'}
$taskBundle=Get-Content -LiteralPath (Join-Path $taskRoot 'native/assets/preset-bundle.json') -Encoding UTF8 -Raw | ConvertFrom-Json
$taskCompiler=Get-Content -LiteralPath (Join-Path $taskRoot 'native/RouteCompiler.cs') -Encoding UTF8 -Raw
$taskStub='namespace SplifyWin { public sealed class RouteList { public string Text,Name,Target,MatchMode; public bool InvertAddresses; } }'
if(!('SplifyWin.RouteCompiler' -as [type])){Add-Type -TypeDefinition ($taskCompiler+[Environment]::NewLine+$taskStub)}
$taskBuild=Join-Path $taskRoot 'work/ports-test-2026-10-14/preset-build'
$taskOutput=Join-Path $taskRoot 'ports/android/app/src/main/assets/presets'
New-Item -ItemType Directory -Path $taskBuild,$taskOutput -Force | Out-Null
$taskUtf8=New-Object Text.UTF8Encoding($false)
$taskManifest=@();$taskIds=@{}
foreach($taskRow in $taskBundle.Catalog){
    $taskText=$taskBundle.Texts.PSObject.Properties[$taskRow.Url].Value
    if(!$taskText){throw ('Missing Windows preset: '+$taskRow.Name)}
    $taskHash=[Security.Cryptography.SHA256]::Create()
    try{$taskId=([BitConverter]::ToString($taskHash.ComputeHash([Text.Encoding]::UTF8.GetBytes($taskRow.Url)))).Replace('-','').ToLowerInvariant().Substring(0,16)}finally{$taskHash.Dispose()}
    if($taskIds.ContainsKey($taskId)){throw 'Duplicate preset id'};$taskIds[$taskId]=$true
    $taskFields=[SplifyWin.RouteCompiler]::Parse($taskText)
    if($taskFields['process_name'].Count -or $taskFields['process_path'].Count){throw 'A desktop process rule cannot be silently imported as an Android app rule'}
    $taskRules=@();$taskCount=0
    foreach($taskKey in @('domain','domain_suffix','ip_cidr')){
        if($taskFields[$taskKey].Count){$taskRules+=@{ $taskKey=@($taskFields[$taskKey]) };$taskCount+=$taskFields[$taskKey].Count}
    }
    if(!$taskRules.Count){throw ('Empty Windows preset: '+$taskRow.Name)}
    $taskJson=Join-Path $taskBuild ($taskId+'.json')
    $taskFile=Join-Path $taskOutput ($taskId+'.srs')
    # Deterministic generated build assets, not edits to source or user data.
    [IO.File]::WriteAllText($taskJson,(@{version=3;rules=$taskRules}|ConvertTo-Json -Depth 8 -Compress),$taskUtf8)
    & $Core rule-set compile --output $taskFile $taskJson
    if($LASTEXITCODE -ne 0){throw ('Preset compilation failed: '+$taskRow.Name)}
    $taskManifest+=@{id=$taskId;name=$taskRow.Name;kind=$taskRow.Kind;source=$taskRow.Source;repository=$taskRow.Repository;url=$taskRow.Url;records=$taskCount;domains=($taskFields['domain'].Count+$taskFields['domain_suffix'].Count);ips=$taskFields['ip_cidr'].Count;bytes=(Get-Item -LiteralPath $taskFile).Length;sha256=(Get-FileHash -LiteralPath $taskFile).Hash.ToLowerInvariant()}
}
[IO.File]::WriteAllText((Join-Path $taskOutput 'catalogue.json'),(@{version=1;asOf=$taskBundle.AsOf;compiler='sing-box 1.14.2-lx.11';presets=$taskManifest}|ConvertTo-Json -Depth 8 -Compress),$taskUtf8)
$taskManifest | ForEach-Object { [pscustomobject]$_ } | Measure-Object -Property bytes,records -Sum | Select-Object Property,Count,Sum | Format-Table
