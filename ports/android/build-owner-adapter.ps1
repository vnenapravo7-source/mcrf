#requires -Version 7.0
[CmdletBinding(DefaultParameterSetName='Build')]
param(
    [Parameter(Mandatory,ParameterSetName='Build')][string]$Stage,
    [Parameter(Mandatory,ParameterSetName='Verify')][string]$VerifyReceipt,
    [Parameter(ParameterSetName='Build')][string]$JavaHome=$env:JAVA_HOME,
    [Parameter(ParameterSetName='Build')][string]$AndroidSdk="$env:LOCALAPPDATA/Android/Sdk",
    [Parameter(ParameterSetName='Build')][string]$GradleUserHome='',
    [Parameter(ParameterSetName='Build')][switch]$OnlineDependencies,
    [Parameter(ParameterSetName='Build')][string[]]$ExtraGradleArguments=@()
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$android=Join-Path $root 'ports/android'
$builds=[IO.Path]::GetFullPath('D:/MCRF/Builds/Android')
$adapterName='primary-adapter-release.aar'
$task=':primary-adapter:verifyReleaseAdapter'
function Hash([string]$path){(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}
function OrdinaryFile([string]$path) {
    $item=Get-Item -LiteralPath $path -Force
    if($item.PSIsContainer -or $item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw "Ordinary build input required: $path"}
    return $item
}
function StagePath([string]$path) {
    $absolute=[IO.Path]::GetFullPath($path)
    if([IO.Path]::GetDirectoryName($absolute) -ine $builds -or
       [IO.Path]::GetFileName($absolute) -cnotmatch '^owner-adapter-[A-Za-z0-9.-]+$'){throw 'Use a fresh D:/MCRF/Builds/Android/owner-adapter-* stage'}
    if((Test-Path -LiteralPath $absolute) -and (Get-Item -LiteralPath $absolute).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Redirected adapter stage'}
    return $absolute
}
function SourceManifest {
    $paths=@(
        'ports/android/build.gradle.kts','ports/android/settings.gradle.kts','ports/android/gradle.properties',
        'ports/android/gradlew.bat','ports/android/gradle/wrapper/gradle-wrapper.jar',
        'ports/android/gradle/wrapper/gradle-wrapper.properties','ports/android/primary-adapter/build.gradle.kts',
        'ports/android/primary-spi/build.gradle.kts','ports/android/primary-spi/contract-lock.json',
        'ports/android/primary-cores/singbox/consumer-rules.pro','ports/android/app/libs/libbox.aar',
        'ports/android/android-core-lock.json','ports/android/app/src/main/assets/licenses/sing-box-GPL-3.0.txt',
        'ports/android/app/src/main/assets/licenses/sing-box-NOTICE.txt',
        'work/steer-android-port/android-vpn/module-contract/src/main/java/org/mcrf/engine/spi/v1/Engine.java',
        'work/steer-android-port/android-vpn/module-contract/src/main/java/org/mcrf/engine/spi/v1/Platform.java'
    )
    if(Test-Path -LiteralPath (Join-Path $android 'local.properties')){$paths+='ports/android/local.properties'}
    foreach($base in @('ports/android/primary-adapter/src','ports/android/primary-spi/src','ports/android/primary-cores/singbox/src/main')){
        $directory=Join-Path $root $base
        if(Test-Path -LiteralPath $directory){
            $paths+=@(Get-ChildItem -LiteralPath $directory -File -Recurse|ForEach-Object {
                [IO.Path]::GetRelativePath($root,$_.FullName).Replace('\','/')
            })
        }
    }
    return @(foreach($path in ($paths|Sort-Object -Unique)){
        $file=OrdinaryFile (Join-Path $root $path)
        $digest=Hash $file.FullName
        if($path -ceq 'ports/android/app/libs/libbox.aar'){
            $lock=Get-Content -LiteralPath (Join-Path $android 'android-core-lock.json') -Raw|ConvertFrom-Json
            if($digest -cne $lock.sha256 -or $file.Length -ne $lock.bytes){throw 'Compile-only libbox AAR differs from canonical pin'}
        }
        [ordered]@{path=$path;bytes=$file.Length;sha256=$digest}
    })
}
function Write-Json([string]$path,$value,[int]$depth=8) {
    [IO.File]::WriteAllText($path,($value|ConvertTo-Json -Depth $depth -AsArray:($value -is [array])),[Text.UTF8Encoding]::new($false))
}
function Check-Receipt([string]$path) {
    $path=[IO.Path]::GetFullPath($path)
    $dir=StagePath ([IO.Path]::GetDirectoryName($path))
    if([IO.Path]::GetFileName($path) -cne 'receipt.json'){throw 'Exact adapter receipt required'}
    $null=OrdinaryFile $path
    $record=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json -AsHashtable
    if($record.schema -ne 1 -or $record.state -cne 'OWNER_ADAPTER_SOURCE_STABLE_PASS' -or
       $record.exitCode -ne 0 -or $record.sourceStable -ne $true -or $task -cnotin $record.gradleArguments -or
       $record.producerSha256 -cne (Hash $PSCommandPath)){throw 'Current source-bound owner adapter build receipt required'}
    $before=OrdinaryFile (Join-Path $dir 'inputs-before.json')
    $after=OrdinaryFile (Join-Path $dir 'inputs-after.json')
    $log=OrdinaryFile (Join-Path $dir 'gradle.log')
    $adapter=OrdinaryFile (Join-Path $dir $adapterName)
    if((Hash $before.FullName) -cne $record.sourceManifestBeforeSha256 -or
       (Hash $after.FullName) -cne $record.sourceManifestAfterSha256 -or
       $record.sourceManifestBeforeSha256 -cne $record.sourceManifestAfterSha256 -or
       (Hash $log.FullName) -cne $record.logSha256 -or
       (Hash (Join-Path $record.androidSdk 'platforms/android-34/android.jar')) -cne $record.adapterAndroidJarSha256 -or
       $record.adapter.file -cne $adapterName -or $record.adapter.bytes -ne $adapter.Length -or
       (Hash $adapter.FullName) -cne $record.adapter.sha256){throw 'Owner adapter receipt bytes changed'}
    $then=@(Get-Content -LiteralPath $before.FullName -Raw|ConvertFrom-Json -AsHashtable)
    $now=SourceManifest
    if((ConvertTo-Json -InputObject $then -Depth 8 -Compress) -cne
       (ConvertTo-Json -InputObject $now -Depth 8 -Compress)){throw 'Canonical owner adapter source closure changed since build'}
    return [ordered]@{adapter=$adapter.FullName;adapterSha256=$record.adapter.sha256;adapterBytes=$adapter.Length;sourceManifest=$before.FullName;receipt=$path}
}
if($PSCmdlet.ParameterSetName -ceq 'Verify'){
    Check-Receipt $VerifyReceipt|ConvertTo-Json -Compress
    exit 0
}
$Stage=StagePath $Stage
if(Test-Path -LiteralPath $Stage){throw 'Fresh owner adapter stage required'}
if(!$GradleUserHome){$GradleUserHome=Join-Path $root 'work/toolchains/gradle-home'}
$GradleUserHome=[IO.Path]::GetFullPath($GradleUserHome)
if($GradleUserHome -ine (Join-Path $root 'work/toolchains/gradle-home')){throw 'Use the canonical restored D:/MCRF Gradle cache'}
$JavaHome=(Resolve-Path -LiteralPath $JavaHome).Path
$AndroidSdk=(Resolve-Path -LiteralPath $AndroidSdk).Path
$java=OrdinaryFile (Join-Path $JavaHome 'bin/java.exe')
$androidJar=OrdinaryFile (Join-Path $AndroidSdk 'platforms/android-34/android.jar')
New-Item -ItemType Directory -Path $Stage|Out-Null
$temp=Join-Path 'D:/MCRF/t' ('g'+[guid]::NewGuid().ToString('N').Substring(0,10))
New-Item -ItemType Directory -Path $temp -Force|Out-Null
$record=[ordered]@{schema=1;state='BUILDING';sourceStable=$false;producerSha256=(Hash $PSCommandPath);javaSha256=(Hash $java.FullName);androidSdk=$AndroidSdk;adapterAndroidJarSha256=(Hash $androidJar.FullName);gradleUserHome=$GradleUserHome;temp=$temp}
$arguments=@($task)+$ExtraGradleArguments
if(!$OnlineDependencies){$arguments+='--offline'}
$arguments+=@('--console=plain','--no-daemon',"-Djava.io.tmpdir=$temp","-Dkotlin.daemon.runFilesPath=$temp")
$record.offlineDependencies=!$OnlineDependencies
$record.gradleArguments=$arguments
$before=Join-Path $Stage 'inputs-before.json'
$after=Join-Path $Stage 'inputs-after.json'
$log=Join-Path $Stage 'gradle.log'
$receiptPath=Join-Path $Stage 'receipt.json'
$names=@('JAVA_HOME','ANDROID_HOME','ANDROID_SDK_ROOT','GRADLE_USER_HOME','TMP','TEMP','JAVA_TOOL_OPTIONS')
$saved=@{}
foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
    Write-Json $before (SourceManifest)
    $record.sourceManifestBeforeSha256=Hash $before
    Write-Json $receiptPath $record
    $env:JAVA_HOME=$JavaHome
    $env:ANDROID_HOME=$AndroidSdk
    $env:ANDROID_SDK_ROOT=$AndroidSdk
    $env:GRADLE_USER_HOME=$GradleUserHome
    $env:TMP=$temp
    $env:TEMP=$temp
    $env:JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$temp"
    Push-Location $android
    try {
        & (Join-Path $android 'gradlew.bat') @arguments 2>&1|Tee-Object -FilePath $log
        $record.exitCode=$LASTEXITCODE
    }finally{Pop-Location}
    $record.logSha256=Hash $log
    Write-Json $after (SourceManifest)
    $record.sourceManifestAfterSha256=Hash $after
    if($record.exitCode -ne 0 -or $record.sourceManifestAfterSha256 -cne $record.sourceManifestBeforeSha256 -or
       $record.producerSha256 -cne (Hash $PSCommandPath) -or $record.javaSha256 -cne (Hash $java.FullName) -or
       $record.adapterAndroidJarSha256 -cne (Hash $androidJar.FullName) -or
       !(Select-String -LiteralPath $log -Pattern '^> Task :primary-adapter:verifyReleaseAdapter(?:\s|$)' -Quiet)){throw 'Fresh owner adapter build, task execution, or stable inputs failed'}
    $adapter=OrdinaryFile (Join-Path $android "primary-adapter/build/outputs/aar/$adapterName")
    $artifact=Join-Path $Stage $adapterName
    [IO.File]::Copy($adapter.FullName,$artifact,$false)
    $digest=Hash $artifact
    if((Hash $adapter.FullName) -cne $digest){throw 'Adapter changed while capturing artifact'}
    $record.adapter=[ordered]@{file=$adapterName;bytes=$adapter.Length;sha256=$digest}
    $record.sourceStable=$true
    $record.state='OWNER_ADAPTER_SOURCE_STABLE_PASS'
    Write-Json $receiptPath $record
    $null=Check-Receipt $receiptPath
    Write-Output "OWNER_ADAPTER_RECEIPT=$receiptPath"
}catch{
    $record.state='FAILED';$record.sourceStable=$false;$record.failure=$_.Exception.Message
    Write-Json $receiptPath $record
    throw
}finally{
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
}
