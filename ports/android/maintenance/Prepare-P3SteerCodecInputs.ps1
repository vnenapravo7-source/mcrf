param([string]$Cache='D:/MCRF/Projects/MCRF/work/steer-android-port/android-vpn/core/.cxx/RelWithDebInfo/2i1w144e/x86_64/_deps')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation native-build
$taskPins=@(
 @{name='v5.9.4-stable.tar.gz';sha='7256bfc89b183a75183806c7debfa203443873b0b4a562e1b80d68e01b45ac57';url='https://github.com/wolfSSL/wolfssl/archive/refs/tags/v5.9.4-stable.tar.gz'},
 @{name='ngtcp2-1.25.0.tar.xz';sha='2a34d2484ba17847a5d11965704e9dd0fac4c6d8efc75ffe1ec7de66d8c6b6fb';url='https://github.com/ngtcp2/ngtcp2/releases/download/v1.25.0/ngtcp2-1.25.0.tar.xz'},
 @{name='brotli-v1.2.0.tar.gz';sha='816c96e8e8f193b40151dad7e8ff37b1221d019dbcb9c35cd3fadbfe6477dfec';url='https://github.com/google/brotli/archive/refs/tags/v1.2.0.tar.gz'},
 @{name='zstd-1.5.7.tar.gz';sha='eb33e51f49a15e023950cd7825ca74a4a2b43db8354825ac24fc1b7ee09e6fa3';url='https://github.com/facebook/zstd/releases/download/v1.5.7/zstd-1.5.7.tar.gz'})
$taskStage="D:/MCRF/Builds/Android/p3-steer-codec-inputs-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $taskStage|Out-Null
$taskReceipt=@{state='INTAKE_RUNNING';compiled=$false;executed=$false;files=@()}
try {
 foreach($pin in $taskPins){
  $to="$taskStage/$($pin.name)"
  $cached=@(Get-ChildItem -LiteralPath $Cache -File -Recurse -Filter $pin.name -ErrorAction SilentlyContinue|Where-Object {!$_.LinkType -and (Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant() -ceq $pin.sha}|Select-Object -First 1)
  if($cached.Count){Copy-Item -LiteralPath $cached[0].FullName -Destination $to}else{Invoke-WebRequest -Uri $pin.url -OutFile $to -MaximumRedirection 5}
  if((Get-FileHash -LiteralPath $to).Hash.ToLowerInvariant() -cne $pin.sha){throw 'Original codec archive checksum mismatch; input retained, never extracted or executed'}
  $taskReceipt.files+=@{file=$pin.name;sha256=$pin.sha;bytes=(Get-Item $to).Length;url=$pin.url}
 }
 $taskReceipt.state='PINNED_CODEC_INPUTS_NOT_COMPILED'
}catch{$taskReceipt.state='FAILED_PRESERVED';throw}
finally{[IO.File]::WriteAllText("$taskStage/receipt.json",($taskReceipt|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false));Write-Output "P3_STEER_CODEC_INPUTS=$taskStage"}
