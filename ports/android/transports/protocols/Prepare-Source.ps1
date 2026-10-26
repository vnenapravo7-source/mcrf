param([Parameter(Mandatory)][string]$Upstream)
$ErrorActionPreference='Stop'
$taskCommit='3d3d3db3a2ccef3b1a8eb71b831ed2461c54b94d'
$taskRoot=(Resolve-Path -LiteralPath $Upstream).Path
$taskPins=[ordered]@{'submodules/gvisor'='117243aa02fa2915cb53d1d4549bfca3a3ebf238';'submodules/sing-tun'='6f56eca9cb2e81d1c233b6104618ff30500e9f39';'submodules/utls'='59e89bb121d8cc7a0a1c87a930864122b5685d00';'submodules/wireguard-go'='64065e6826ad82aa28429dd829c1c65f29b7f6c9'}
if((& git -C $taskRoot rev-parse HEAD).Trim() -cne $taskCommit){throw 'Exact protocol SDK revision required'}
$taskStage="D:/MCRF/Builds/Android/p3-protocols-source-$([guid]::NewGuid().ToString('N'))"
$taskModule="$taskStage/module";New-Item -ItemType Directory -Path $taskModule|Out-Null
function Copy-Tracked([string]$From,[string]$To){
 foreach($taskRelative in (& git -C $From ls-files)){
  if($LASTEXITCODE){throw 'SDK source inventory failed'}
  $taskFrom=Join-Path $From $taskRelative
  if(Test-Path -LiteralPath $taskFrom -PathType Container){continue}
  if(!(Test-Path -LiteralPath $taskFrom -PathType Leaf) -or (Get-Item -LiteralPath $taskFrom).LinkType){throw 'Complete regular SDK source required'}
  if((& git -C $From hash-object -- $taskRelative).Trim() -cne (& git -C $From rev-parse "HEAD:$taskRelative").Trim()){throw 'SDK Git object changed'}
  $taskTo=Join-Path $To $taskRelative;New-Item -ItemType Directory -Path (Split-Path $taskTo) -Force|Out-Null;Copy-Item -LiteralPath $taskFrom -Destination $taskTo
 }
}
Copy-Tracked $taskRoot $taskModule
foreach($taskPair in $taskPins.GetEnumerator()){
 $taskFrom=Join-Path $taskRoot $taskPair.Key
 if((& git -C $taskRoot rev-parse "HEAD:$($taskPair.Key)").Trim() -cne $taskPair.Value -or (& git -C $taskFrom rev-parse HEAD).Trim() -cne $taskPair.Value){throw 'Exact original SDK Gitlinks required'}
 Copy-Tracked $taskFrom (Join-Path $taskModule $taskPair.Key)
}
$taskCommand="$taskModule/cmd/mcrf-protocols";New-Item -ItemType Directory -Path $taskCommand|Out-Null
Get-ChildItem -LiteralPath $PSScriptRoot -File -Filter '*.go'|ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination "$taskCommand/$($_.Name)"}
Copy-Item -LiteralPath "$PSScriptRoot/../tls-core/system_server_dns.go" -Destination "$taskCommand/system_server_dns.go"
Copy-Item -LiteralPath "$PSScriptRoot/overlay/mcrf_physical.go" -Destination "$taskModule/common/dialer/mcrf_physical.go"
function Replace-Exact([string]$Path,[string]$Old,[string]$New){
 $taskText=[IO.File]::ReadAllText($Path).Replace("`r`n","`n")
 if(($taskText.Split([string[]]@($Old),[StringSplitOptions]::None).Count-1) -ne 1){throw 'Pinned protocol physical-network anchor changed'}
 [IO.File]::WriteAllText($Path,$taskText.Replace($Old,$New),[Text.UTF8Encoding]::new($false))
}
$taskAnchor="`tif networkManager != nil {`n`t`tinterfaceFinder = networkManager.InterfaceFinder()"
$taskPhysical="`tif physical := mcrfPhysicalControl(ctx); physical != nil {`n`t`tdialer.Control = control.Append(dialer.Control, physical)`n`t`tlistener.Control = control.Append(listener.Control, physical)`n`t}`n"+$taskAnchor
Replace-Exact "$taskModule/common/dialer/default.go" $taskAnchor $taskPhysical
Replace-Exact "$taskCommand/system_server_dns.go" 'lookup: net.DefaultResolver.LookupIP' 'lookup: physicalLookup(ctx)'
New-Item -ItemType Directory -Path "$taskModule/mcrfbridge"|Out-Null
Get-ChildItem -LiteralPath "$PSScriptRoot/../../optional-cores/bridge" -File|Where-Object {$_.Name -eq 'go.mod' -or ($_.Extension -eq '.go' -and $_.Name -notlike '*_test.go')}|ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination "$taskModule/mcrfbridge/$($_.Name)"}
$taskGoMod=[IO.File]::ReadAllText("$taskModule/go.mod")
if($taskGoMod.Contains('mcrf.local/optionalbridge')){throw 'Bridge already injected'}
[IO.File]::WriteAllText("$taskModule/go.mod",$taskGoMod+"`nrequire mcrf.local/optionalbridge v0.0.0`nreplace mcrf.local/optionalbridge => ./mcrfbridge`n",[Text.UTF8Encoding]::new($false))
$taskRows=@(Get-ChildItem -LiteralPath $taskModule -Recurse -File|ForEach-Object {@{path=[IO.Path]::GetRelativePath($taskModule,$_.FullName).Replace([char]92,[char]47);bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}}|Sort-Object path)
[IO.File]::WriteAllText("$taskStage/SOURCE_MANIFEST.json",($taskRows|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$taskReceipt=@{state='SOURCE_PREPARED_NOT_COMPILED';kind='protocols';module=$taskModule;commit=$taskCommit;repository='Leadaxe/sing-box-lx';pins=$taskPins;contract=1;naive='original-statically-linked-Cronet';tags='with_quic,with_utls,with_naive_outbound';manifestSha256=(Get-FileHash "$taskStage/SOURCE_MANIFEST.json").Hash.ToLowerInvariant();compiled=$false;executed=$false;androidIntegrated=$false}
[IO.File]::WriteAllText("$taskStage/receipt.json",($taskReceipt|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
Write-Output "P3_PROTOCOLS_SOURCE=$taskStage"
