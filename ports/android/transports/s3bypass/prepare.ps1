param([string]$PinnedStage='')
$ErrorActionPreference='Stop'
$taskCommit='b54ed13e3d7473ad8c25b5c39dfe127b004db540'
$taskStage=Join-Path 'D:/MCRF/Builds/Android' ('s3bypass-native-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskStage | Out-Null
$taskSource=Join-Path $taskStage 'source'
$taskReceipt=[ordered]@{kind='s3bypass';state='PREPARING_SOURCE';source=$taskSource;commit=$taskCommit;repository='Fedarisha/Xray-core-fedarisha';sourceStable=$false;nativeExecuted=$false;apkBuilt=$false;testsExecuted=$false;artifacts=@()}
function Save-S3Receipt {[IO.File]::WriteAllText((Join-Path $taskStage 'receipt.json'),($taskReceipt|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))}
Save-S3Receipt
Write-Output "S3_BYPASS_STAGE=$taskStage"
try {
    if($PinnedStage){
        $taskExisting=Get-Item -LiteralPath $PinnedStage
        if($taskExisting.Parent.FullName -ine 'D:\MCRF\Builds\Android' -or
           $taskExisting.Name -cnotmatch '^s3bypass-native-[a-f0-9]{32}$' -or $taskExisting.LinkType){throw 'Exact owned S3 base stage required'}
        $taskBase=Join-Path $taskExisting.FullName 'source'
        $taskBaseLicense=Join-Path $taskExisting.FullName 'licenses/S3-Bypass-app-LICENSE'
        if((Get-Item -LiteralPath $taskBase).LinkType -or (Get-Item -LiteralPath $taskBaseLicense).LinkType -or
           (& git -C $taskBase rev-parse HEAD) -cne $taskCommit){throw 'Pinned S3 base unavailable'}
    }
    & git init --quiet $taskSource
    if($LASTEXITCODE){throw 'Cannot initialize owned S3 source snapshot'}
    & git -C $taskSource remote add origin https://github.com/Fedarisha/Xray-core-fedarisha.git
    if($LASTEXITCODE){throw 'Cannot set pinned source origin'}
    $taskFetch=if($PinnedStage){$taskBase}else{'origin'}
    & git -C $taskSource -c core.autocrlf=false fetch --depth=1 $taskFetch $taskCommit
    if($LASTEXITCODE){throw 'Pinned S3 source unavailable; no alternative core substitution'}
    & git -C $taskSource -c core.autocrlf=false checkout --detach $taskCommit
    if($LASTEXITCODE -or (& git -C $taskSource rev-parse HEAD) -cne $taskCommit){throw 'Wrong S3 source revision'}
    function Set-S3SourceAnchor([string]$Relative,[string]$Old,[string]$New){
        $taskFile=Join-Path $taskSource $Relative
        $taskText=[IO.File]::ReadAllText($taskFile).Replace("`r`n","`n")
        $Old=$Old.Replace("`r`n","`n");$New=$New.Replace("`r`n","`n")
        if(($taskText.Split([string[]]@($Old),[StringSplitOptions]::None).Count-1) -ne 1){throw "Pinned S3 source anchor changed: $Relative"}
        [IO.File]::WriteAllText($taskFile,$taskText.Replace($Old,$New),[Text.UTF8Encoding]::new($false))
    }
    $taskS3Package='proxy/fedarisha/storage/s3'
    foreach($taskName in @('underlay.go','underlay_android.go','underlay_test.go')){
        Copy-Item -LiteralPath "$PSScriptRoot/$taskName" -Destination "$taskSource/$taskS3Package/$taskName"
    }
    Set-S3SourceAnchor "$taskS3Package/s3.go" 'DialContext: (&net.Dialer{' 'DialContext: underlayDialer(net.Dialer{'
    Set-S3SourceAnchor 'main/main.go' 'import (' "import (`n`t`"fmt`"`n`tfedarishas3 `"github.com/xtls/xray-core/proxy/fedarisha/storage/s3`""
    $taskMain=@'
func main() {
	if err := fedarishas3.ConfigureMcrfUnderlay(); err != nil {
		fmt.Fprintln(os.Stderr, "MCRF_S3BYPASS_PHYSICAL_NETWORK_REJECTED")
		os.Exit(2)
	}
'@
    Set-S3SourceAnchor 'main/main.go' 'func main() {' $taskMain
    $taskSocksOverlay=Join-Path $PSScriptRoot '../socks-owner/overlay.py'
    $taskSocks=(& python -B $taskSocksOverlay s3 $taskSource | ConvertFrom-Json)
    if($LASTEXITCODE -ne 0 -or $taskSocks.contract -ne 1){throw 'Pinned S3 SOCKS UDP source overlay failed'}
    $taskReceipt.socksUdpSource=@{s3bypass=1}
    $taskReceipt.socksUdpSourceOverlay=$taskSocks
    $taskRows=@(Get-ChildItem -LiteralPath $taskSource -Recurse -Force -File|Where-Object {!$_.FullName.StartsWith((Join-Path $taskSource '.git')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)}|Sort-Object FullName|ForEach-Object {
        if($_.LinkType){throw 'Linked source input rejected'}
        @{path=[IO.Path]::GetRelativePath($taskSource,$_.FullName);bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
    [IO.File]::WriteAllText((Join-Path $taskStage 'source-manifest.json'),($taskRows|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
    $taskLicenses=Join-Path $taskStage 'licenses';New-Item -ItemType Directory -Path $taskLicenses|Out-Null
    Copy-Item -LiteralPath (Join-Path $taskSource 'LICENSE') -Destination (Join-Path $taskLicenses 's3bypass-LICENSE')
    $taskAppLicense=Join-Path $taskLicenses 'S3-Bypass-app-LICENSE'
    if($PinnedStage){Copy-Item -LiteralPath $taskBaseLicense -Destination $taskAppLicense}
    else{Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/SpaceNeuroX/s3-bypass/815a9557b2a1739d75d1ea753a5d9d4a575a4f5d/LICENSE' -OutFile $taskAppLicense}
    $taskReceipt.state='SOURCE_PREPARED';$taskReceipt.inputCount=$taskRows.Count;$taskReceipt.s3UnderlayContract=1
    $taskReceipt.sourceManifestSha256=(Get-FileHash -LiteralPath (Join-Path $taskStage 'source-manifest.json') -Algorithm SHA256).Hash.ToLowerInvariant()
    Save-S3Receipt
}catch{$taskReceipt.state='FAILED_SOURCE_PREPARATION';Save-S3Receipt;throw}
