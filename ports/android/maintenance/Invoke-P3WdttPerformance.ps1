param([Parameter(Mandatory)][ValidateSet('Source','Native')][string]$Operation,[string]$PreparedSource='',[string]$HostEvidence='')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation native-build
# Keep the completed CSQTT/QWDTT producer and its evidence byte-identical.
# WDTT needs one producer-only compatibility fix: PowerShell single-quoted
# here-strings contain literal backslash-t, not tab characters in Go source.
$origin='D:/MCRF/Projects/MCRF/ports/android/transports/swg/performance'
$bundle="D:/MCRF/Builds/Android/p3-wdtt-producer-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $bundle|Out-Null
foreach($file in Get-ChildItem -LiteralPath $origin -File){
 if($file.LinkType){throw 'Regular reviewed producer inputs only'}
 Copy-Item -LiteralPath $file.FullName -Destination "$bundle/$($file.Name)"
}
$helper="$bundle/Apply-PerformanceSource.ps1"
$text=[IO.File]::ReadAllText($helper).Replace("`r`n","`n")
$anchor='function Replace-Exact([string]$Path,[string]$Old,[string]$New){'
if($text.Split(@($anchor),[StringSplitOptions]::None).Count -ne 2){throw 'Exact WDTT producer anchor required'}
$oldError=@'
Replace-Exact "$taskModule/socks5_server.go" "if err != nil {`n`t`t`tif ctx.Err() != nil || errors.Is(err, net.ErrClosed)" "if err != nil {`n`t`t`t<-clientSlots`n`t`t`tif ctx.Err() != nil || errors.Is(err, net.ErrClosed)"
'@
$newError=@'
Replace-Exact "$taskModule/socks5_server.go" "client, err := listener.Accept()`n`t`tif err != nil {`n`t`t`tif ctx.Err() != nil || errors.Is(err, net.ErrClosed)" "client, err := listener.Accept()`n`t`tif err != nil {`n`t`t`t<-clientSlots`n`t`t`tif ctx.Err() != nil || errors.Is(err, net.ErrClosed)"
'@
if($text.Split(@($oldError),[StringSplitOptions]::None).Count -ne 2){throw 'Exact WDTT Accept error directive required'}
# A similar error branch exists before slots are acquired. Anchor to Accept;
# never add a slot release to the listener-creation failure branch.
$text=$text.Replace($oldError,$newError)
$fix=@'
 if($Kind -ceq 'wdtt'){$Old=$Old.Replace('\t',"`t");$New=$New.Replace('\t',"`t")}
'@
Set-ItemProperty -LiteralPath $helper -Name IsReadOnly -Value $false
[IO.File]::WriteAllText($helper,$text.Replace($anchor,$anchor+"`n"+$fix),[Text.UTF8Encoding]::new($false))
$template=if($Operation -ceq 'Source'){'Prepare-TurnSource.ps1'}else{'Build-TurnNative.ps1'}
$producer="$bundle/$template"
$code=[IO.File]::ReadAllText($producer)
$gate='$PSScriptRoot/../../../maintenance/Assert-HumanBatchReady.ps1'
if(!$code.Contains($gate) -and $Operation -ceq 'Native'){throw 'Native producer safety gate anchor changed'}
$code=$code.Replace($gate,'D:/MCRF/Projects/MCRF/ports/android/maintenance/Assert-HumanBatchReady.ps1')
Set-ItemProperty -LiteralPath $producer -Name IsReadOnly -Value $false
[IO.File]::WriteAllText($producer,$code,[Text.UTF8Encoding]::new($false))
$rows=@(Get-ChildItem -LiteralPath $bundle -File|ForEach-Object {@{file=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}})
[IO.File]::WriteAllText("$bundle/SOURCE_MANIFEST.json",($rows|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
Write-Output "P3_WDTT_REVIEWED_PRODUCER=$bundle"
if($Operation -ceq 'Source'){
 $output=@(& $producer -Kind wdtt)
 $roots=@($output|Where-Object {$_ -is [string] -and $_ -cmatch '^P3_TURN_SOURCE='})
 if($roots.Count -ne 1){throw 'One exact prepared WDTT source required'}
 $root=$roots[0].Substring('P3_TURN_SOURCE='.Length)
 $source=Get-Content "$root/receipt.json" -Raw|ConvertFrom-Json
 $baseline=Get-Content $source.baselineReceipt -Raw|ConvertFrom-Json
 $original=Join-Path $baseline.source 'wdtt/pathprobe'
 $local="$($source.module)/mcrf-pathprobe"
 if(Test-Path -LiteralPath $local){throw 'Never overwrite an existing local module'}
 New-Item -ItemType Directory -Path $local|Out-Null
 $localRows=@()
 foreach($file in Get-ChildItem -LiteralPath $original -File){
  if($file.LinkType -or $file.Name -notmatch '^(go\.(mod|sum)|[A-Za-z0-9_]+\.go)$'){throw 'Unexpected original WDTT local-module input'}
  Copy-Item -LiteralPath $file.FullName -Destination "$local/$($file.Name)"
  $localRows+=@{file=$file.Name;sha256=(Get-FileHash -LiteralPath $file.FullName).Hash.ToLowerInvariant()}
 }
 $license=Join-Path $baseline.source 'wdtt/LICENSE'
 Copy-Item -LiteralPath $license -Destination "$local/LICENSE"
 $mod="$($source.module)/go.mod";$modText=[IO.File]::ReadAllText($mod)
 $old='replace wdtt.local/pathprobe => ../pathprobe'
 if($modText.Split(@($old),[StringSplitOptions]::None).Count -ne 2){throw 'Exact original WDTT local module binding required'}
 Set-ItemProperty -LiteralPath $mod -Name IsReadOnly -Value $false
 [IO.File]::WriteAllText($mod,$modText.Replace($old,'replace wdtt.local/pathprobe => ./mcrf-pathprobe'),[Text.UTF8Encoding]::new($false))
 $rows=@(Get-ChildItem -LiteralPath $source.module -Recurse -File|ForEach-Object {@{path=[IO.Path]::GetRelativePath($source.module,$_.FullName).Replace([char]92,[char]47);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}}|Sort-Object path)
 [IO.File]::WriteAllText("$root/SOURCE_MANIFEST.json",($rows|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
 $source.manifestSha256=(Get-FileHash "$root/SOURCE_MANIFEST.json").Hash.ToLowerInvariant()
 $source|Add-Member localPathprobeSource @{original=$original;inputs=$localRows;licenseSha256=(Get-FileHash $license).Hash.ToLowerInvariant();sameModuleIdentity=$true;copiedWithoutCodeChanges=$true}
 [IO.File]::WriteAllText("$root/receipt.json",($source|ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
 $output|Write-Output
}else{
 if(!$PreparedSource -or !$HostEvidence){throw 'Frozen WDTT source and actual four-case evidence required'}
 $source=Get-Content "$PreparedSource/receipt.json" -Raw|ConvertFrom-Json
 if($source.kind -cne 'wdtt'){throw 'This compatibility producer is WDTT only'}
 & $producer -PreparedSource $PreparedSource -HostEvidence $HostEvidence
}
