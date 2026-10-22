param([Parameter(Mandatory)][string]$NativeStage,[Parameter(Mandatory)][string]$WrapperStage)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot '../../maintenance/Assert-HumanBatchReady.ps1') -Operation native-build
$base=[IO.Path]::GetFullPath('D:/MCRF/Builds/Android')
function Resolve([string]$path,[string]$pattern){
    $full=[IO.Path]::GetFullPath($path)
    if([IO.Path]::GetDirectoryName($full) -ine $base -or [IO.Path]::GetFileName($full) -cnotmatch $pattern -or (Get-Item -LiteralPath $full).LinkType){throw 'Exact ordinary completed stage required'}
    $full
}
function Hash([string]$path){(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}
$native=Resolve $NativeStage '^csqtt-historical-[0-9a-f]{32}$'
$wrapper=Resolve $WrapperStage '^csqtt-captcha-[0-9a-f]{32}$'
$n=Get-Content "$native/receipt.json" -Raw|ConvertFrom-Json
$w=Get-Content "$wrapper/receipt.json" -Raw|ConvertFrom-Json
if($n.state -cne 'COMPILED_NOT_EXECUTED' -or !$n.sourceStable -or $n.engine -cnotin @('csqtt20','csqtt215') -or @($n.artifacts).Count -ne 3){throw 'Completed historical native producer required'}
if($w.kind -cne 'csqtt' -or $w.state -cne 'COMPILED_NOT_EXECUTED' -or !$w.sourceStable -or $w.processGroupContract -ne 1 -or $w.turnHashPoolContract -ne 1 -or $w.captchaWaiterCapability -cne 'csqtt-id48-v1'){throw 'Exact retained safe Go bridge contract required'}
$stage="$base/csqtt-historical-package-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $stage,"$stage/licenses"|Out-Null
$result=[ordered]@{state='PREPARING';kind=$n.engine;generation=$n.generation;revision=$n.revision;sourceStable=$false;wrapperReceiptSha256=Hash "$wrapper/receipt.json";nativeReceiptSha256=Hash "$native/receipt.json";artifacts=@();releaseVerified=$false}
function Save{[IO.File]::WriteAllText("$stage/receipt.json",($result|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))}
try {
    Copy-Item "$wrapper/receipt.json" "$stage/wrapper-receipt.json";Copy-Item "$native/receipt.json" "$stage/native-receipt.json"
    foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')){
        New-Item -ItemType Directory -Path "$stage/$abi"|Out-Null
        $rust=@($n.artifacts|Where-Object {$_.abi -ceq $abi})
        $go=@($w.artifacts|Where-Object {$_.abi -ceq $abi -and [IO.Path]::GetFileName($_.file) -ceq 'libqwdtt.so'})
        if($rust.Count -ne 1 -or $go.Count -ne 1){throw 'One exact native and bridge per ABI required'}
        foreach($pair in @(@{row=$rust[0];name='libcsqtt_transport.so';expected="$native/candidates/$abi/libcsqtt_transport.so"},@{row=$go[0];name="lib$($n.engine).so";expected="$wrapper/$abi/libqwdtt.so"})){
            $input=Get-Item -LiteralPath $pair.row.file
            if($input.FullName -ine [IO.Path]::GetFullPath($pair.expected) -or $input.LinkType -or $input.PSIsContainer -or $input.Length -ne $pair.row.bytes -or (Hash $input.FullName) -cne $pair.row.sha256){throw 'Compiled input identity changed'}
            $target="$stage/$abi/$($pair.name)";Copy-Item -LiteralPath $input.FullName -Destination $target
            $result.artifacts+=@{abi=$abi;path="$abi/$($pair.name)";bytes=$input.Length;sha256=Hash $target}
        }
    }
    Copy-Item "$native/CSQTT-LICENSE" "$stage/licenses/CSQTT-LICENSE"
    Copy-Item "$wrapper/licenses/qwdtt-LICENSE" "$stage/licenses/qwdtt-LICENSE"
    $result.sourceStable=$true;$result.state='HISTORICAL_ENGINE_INPUTS_READY'
}catch{$result.state='FAILED_PRESERVED';throw}
finally{Save;Write-Output "HISTORICAL_PACKAGE_STAGE=$stage"}
