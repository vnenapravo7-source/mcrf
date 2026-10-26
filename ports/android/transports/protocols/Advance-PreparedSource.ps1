param([Parameter(Mandatory)][string]$PreparedSource)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-build
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
$taskParent=(Resolve-Path -LiteralPath $PreparedSource).Path.Replace([char]92,[char]47)
if($taskParent -cnotmatch '^D:/MCRF/Builds/Android/p3-protocols-source-[a-f0-9]{32}$'){throw 'Exact retained original protocol source required'}
$taskProof=Get-Content "$taskParent/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
if($taskProof.state -cne 'SOURCE_PREPARED_NOT_COMPILED' -or $taskProof.commit -cne '3d3d3db3a2ccef3b1a8eb71b831ed2461c54b94d' -or $taskProof.manifestSha256 -cne (Hash "$taskParent/SOURCE_MANIFEST.json") -or $taskProof.module.Replace([char]92,[char]47) -cne "$taskParent/module"){throw 'Frozen original SDK/adapter proof required'}
$taskRows=@(Get-Content "$taskParent/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json)
if(@(Get-ChildItem "$taskParent/module" -Recurse -Force|Where-Object {$_.LinkType}).Count -or @(Get-ChildItem "$taskParent/module" -Recurse -File).Count -ne $taskRows.Count){throw 'Retained source closure changed'}
$taskStage="D:/MCRF/Builds/Android/p3-protocols-source-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path "$taskStage/module"|Out-Null
foreach($row in $taskRows){
 if([IO.Path]::IsPathRooted($row.path) -or '..' -in ($row.path -split '[/\\]')){throw 'Unsafe retained source path'}
 $from=Join-Path "$taskParent/module" $row.path;$to=Join-Path "$taskStage/module" $row.path
 if((Get-Item $from).Length -ne $row.bytes -or (Hash $from) -cne $row.sha256){throw 'Retained source bytes changed'}
 New-Item -ItemType Directory -Path (Split-Path $to) -Force|Out-Null
 Copy-Item -LiteralPath $from -Destination $to
 if((Hash $to) -cne $row.sha256){throw 'Copied source bytes changed'}
}
# The shared local adapter already requires Go1.26. No dependency version,
# checksum, SDK source, protocol implementation or test is changed here.
$taskMod="$taskStage/module/go.mod";$taskText=[IO.File]::ReadAllText($taskMod)
if([regex]::Matches($taskText,'(?m)^go 1\.25\.5\r?$').Count -ne 1 -or [IO.File]::ReadAllText("$taskStage/module/mcrfbridge/go.mod") -notmatch '(?m)^go 1\.26\.0\r?$'){throw 'Unexpected root/bridge Go directive'}
(Get-Item $taskMod).IsReadOnly=$false
[IO.File]::WriteAllText($taskMod,([regex]::Replace($taskText,'(?m)^go 1\.25\.5(?=\r?$)','go 1.26.0')),[Text.UTF8Encoding]::new($false))
$taskNewRows=@(Get-ChildItem "$taskStage/module" -Recurse -File|ForEach-Object {@{path=[IO.Path]::GetRelativePath("$taskStage/module",$_.FullName).Replace([char]92,[char]47);bytes=$_.Length;sha256=Hash $_.FullName}}|Sort-Object path)
Save $taskNewRows "$taskStage/SOURCE_MANIFEST.json"
$taskProof.module="$taskStage/module";$taskProof.manifestSha256=Hash "$taskStage/SOURCE_MANIFEST.json"
$taskProof.parentPreparedSource=$taskParent;$taskProof.parentReceiptSha256=Hash "$taskParent/receipt.json"
$taskProof.goDirective='1.26.0';$taskProof.revision='root-go-matches-existing-local-bridge';$taskProof.revisionProducerSha256=Hash $PSCommandPath
Save $taskProof "$taskStage/receipt.json"
Write-Output "P3_PROTOCOLS_SOURCE=$taskStage"
