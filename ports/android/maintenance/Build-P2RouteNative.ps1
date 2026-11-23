#requires -Version 7.0
param(
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    [Parameter(Mandatory=$true)][string]$RetainedDirectEvidence,
    [ValidatePattern('^[A-Za-z0-9._-]{1,64}$')][string]$Distribution='',
    [string]$KernelEvidence='',
    [switch]$ResumeNative
)
# Inactive whole-P2 recipe. This file is not evidence of compilation or acceptance.
$ErrorActionPreference='Stop'
if($args.Count){throw 'Unknown whole-P2 native arguments'}
if([bool]$Distribution -eq [bool]$KernelEvidence){throw 'Choose exact existing Linux environment OR completed Android kernel evidence'}
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation native-build
$taskRoot='D:/MCRF/Projects/MCRF'
$taskPath=[IO.Path]::GetFullPath($ReceiptPath)
if($taskPath -cnotmatch '^D:\\MCRF\\Builds\\Android\\p2-singbox-route-source-[a-f0-9]{32}\\receipt.json$'){throw 'Exact frozen P2 source receipt required'}
function Hash([string]$Path){return (Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
$taskSourceReceipt=Get-Content -LiteralPath $taskPath -Raw|ConvertFrom-Json
$taskReceiptSha=Hash $taskPath
$taskStage=[IO.Path]::GetDirectoryName($taskPath)
$taskSource=Join-Path $taskStage 'source'
$taskGate=Join-Path $taskRoot 'ports/android/coordination/p2-progress-2026-10-20.json'
$taskGateSha=Hash $taskGate
$taskCurrentPath=Join-Path $taskRoot 'ports/android/coordination/current.json'
$taskCurrent=Get-Content -LiteralPath $taskCurrentPath -Raw|ConvertFrom-Json
if($taskCurrent.activeBatch -cne 'p2-progress-2026-10-20.json' -or $taskSourceReceipt.priority -ne 2 -or
    $taskSourceReceipt.state -cne 'SOURCE_FROZEN_UNCOMPILED_UNTESTED' -or !$taskSourceReceipt.producerComplete -or
    $taskSourceReceipt.readinessSha256 -cne $taskGateSha -or [IO.Path]::GetFullPath($taskSourceReceipt.stage) -cne $taskStage -or
    $taskSourceReceipt.commit -cne '3d3d3db3a2ccef3b1a8eb71b831ed2461c54b94d'){throw 'Completed exact whole-P2 source freeze required'}
$previousBuild=$null
if(Test-Path -LiteralPath (Join-Path $taskStage 'build-receipt.json')){
    if(!$ResumeNative){throw 'Continue/review the existing native producer; never duplicate or overwrite it'}
    $previousBuild=Get-Content -LiteralPath (Join-Path $taskStage 'build-receipt.json') -Raw|ConvertFrom-Json -AsHashtable
    if($previousBuild.state -cne 'API_ABI_FAILED_NATIVE_RETAINED' -or !$previousBuild.nativeCompileCompleted -or !$previousBuild.sourceStable -or $previousBuild.sourceReceiptSha256 -cne $taskReceiptSha -or $previousBuild.sourceReadySha256 -cne $taskGateSha -or (Hash "$taskStage/runtime.aar") -cne $previousBuild.retainedNativeAarSha256){throw 'Exact completed immutable native compilation required for API-only resume'}
    if(Test-Path -LiteralPath "$taskStage/pre-api-resume.json"){throw 'API finalization already attempted; inspect existing evidence'}
    Copy-Item -LiteralPath "$taskStage/build-receipt.json" -Destination "$taskStage/pre-api-resume.json"
}elseif($ResumeNative){throw 'No completed native compiler to resume'}
if($taskCurrent.jobs.build -or $taskCurrent.jobs.hostTest -or $taskCurrent.jobs.device -or $taskCurrent.jobs.emulator){throw 'An owned job already exists'}
foreach($directory in @($taskStage,$taskSource)){
    if((Get-Item -LiteralPath $directory).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Ordinary immutable native stage required'}
}
$taskManifestPath=Join-Path $taskStage 'source-manifest.json'
if((Hash $taskManifestPath) -cne $taskSourceReceipt.sourceManifestSha256){throw 'Frozen source manifest changed'}
$taskManifest=@(Get-Content -LiteralPath $taskManifestPath -Raw|ConvertFrom-Json)
function AssertInputs {
    if((Hash $taskPath) -cne $taskReceiptSha -or (Hash $taskGate) -cne $taskGateSha -or (Hash $taskManifestPath) -cne $taskSourceReceipt.sourceManifestSha256){throw 'Source/readiness receipt changed'}
    $prefix=[IO.Path]::GetFullPath($taskSource)+[IO.Path]::DirectorySeparatorChar
    $actual=@(Get-ChildItem -LiteralPath $taskSource -Recurse -File)
    if($actual.Count -ne $taskSourceReceipt.sourceFiles -or $taskManifest.Count -ne $actual.Count -or
        @(Get-ChildItem -LiteralPath $taskSource -Recurse -Force -Attributes ReparsePoint).Count){throw 'Immutable source set/link changed'}
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach($row in $taskManifest){
        $file=[IO.Path]::GetFullPath((Join-Path $taskSource $row.path))
        if(!$file.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase) -or !$seen.Add($file) -or
            (Get-Item -LiteralPath $file).Length -ne $row.bytes -or (Hash $file) -cne $row.sha256){throw 'Frozen compiler input changed'}
    }
    foreach($row in $taskSourceReceipt.patches){
        $area=if($row.area -ceq 'direct-family'){'p044-direct-family'}elseif($row.area -ceq 'route-pipes'){'p2-route-pipes'}else{throw 'Unknown native patch area'}
        if((Hash (Join-Path "$taskRoot/ports/android/primary-cores/singbox/native-patches/$area" $row.file)) -cne $row.sha256){throw 'Canonical native patch changed'}
    }
}
AssertInputs
# Preserve unchanged Direct/IPv6 evidence only against its exact old source closure.
$taskOldPath=[IO.Path]::GetFullPath($RetainedDirectEvidence)
if($taskOldPath -cnotmatch '^D:\\MCRF\\Builds\\Android\\p044-native-source-[a-f0-9]{32}\\build-receipt.json$'){throw 'Exact retained Direct native evidence required'}
$taskOld=Get-Content -LiteralPath $taskOldPath -Raw|ConvertFrom-Json
if(!$taskOld.sourceStable -or !$taskOld.producerComplete -or $taskOld.state -cne 'COMPILED_UNINSTALLED_API_ABI_GATES_OPEN' -or
    @($taskOld.tests|Where-Object {$_.group -in @('HOST_TAGGED','HOST_STUB') -and $_.passed -gt 0}).Count -ne 2){throw 'Closed Direct native test evidence required'}
$taskOldSource=Get-Content -LiteralPath $taskOld.sourceReceipt -Raw|ConvertFrom-Json
$taskOldStage=[IO.Path]::GetDirectoryName($taskOldPath)
$taskOldManifest=Join-Path $taskOldStage 'source-manifest.json'
if((Hash $taskOldManifest) -cne $taskOldSource.sourceManifestSha256){throw 'Retained source manifest changed'}
foreach($row in @($taskOld.tests)){
    if((Hash (Join-Path $taskOldStage ($row.group+'.log'))) -cne $row.logSha256){throw 'Retained actual Direct test log changed'}
}
$taskOriginal=@(Get-Content -LiteralPath $taskOldManifest -Raw|ConvertFrom-Json)
$taskNewPaths=@($taskSourceReceipt.patches|Where-Object {$_.area -ceq 'route-pipes' -and $_.file.EndsWith('.go')}|ForEach-Object {'experimental/libbox/'+$_.file})
if($taskManifest.Count -ne $taskOriginal.Count+$taskNewPaths.Count){throw 'Unaccounted native source change; expand the affected verification plan'}
foreach($row in $taskOriginal){
    $same=@($taskManifest|Where-Object {$_.path -ceq $row.path})
    if($same.Count -ne 1){throw 'Retained input missing/duplicated'}
    if($row.path -ceq 'experimental/libbox/config.go'){
        $before=[IO.File]::ReadAllText((Join-Path "$taskOldStage/source" $row.path)).Replace("`r`n","`n")
        $after=[IO.File]::ReadAllText((Join-Path $taskSource $row.path)).Replace("`r`n","`n")
        if($after.Replace('mcrfOutboundRegistry()','include.OutboundRegistry()') -cne $before){throw 'Only the reviewed registry seam may differ'}
        if((Hash (Join-Path "$taskOldStage/source" $row.path)) -cne $row.sha256){throw 'Retained seam bytes changed'}
    }elseif($same[0].sha256 -cne $row.sha256){throw 'Unchanged native dependency differs; retained proof cannot be reused'}
}
foreach($path in $taskNewPaths){if(@($taskManifest|Where-Object {$_.path -ceq $path}).Count -ne 1){throw 'New native source closure incomplete'}}
$taskGo="$taskRoot/work/toolchains/go1.26.8/go/bin/go.exe"
$taskMobile="$taskRoot/work/toolchains/gomobile-v0.1.13/gomobile.exe"
$taskJava='C:/Program Files/Java/jdk-17'
$taskSdk='C:/Users/dieonfire/AppData/Local/Android/Sdk'
$taskNdk="$taskSdk/ndk/28.2.13676358"
$taskRequired=@('TestMcrfPipeSpecKeepsAssignedAddressesAndBothFamilies','TestMcrfPipeSpecRejectsImplicitDnsMappedAndDuplicateAddresses','TestMcrfPipeFrameIsWholeIpNotAnImplicitStream','TestMcrfPipeRegistryBindsExactGenerationTagAndNonce','TestMcrfPipeValidationCloseDoesNotConsumeRuntimeOwner','TestMcrfPipeFailedServerNeverFallsBackOrResurrects','TestMcrfPipeCloseFailureRetainsOwnerAndDoesNotBlockOtherRetirement','TestMcrfPipeFailedConstructorNeverLeavesATypedNilOwner','TestMcrfPipeStackUdpPrivateRoundtripBothFamilies','TestMcrfPipeStackCloseRetiresActualPendingTcpDial','TestMcrfPipeStackRefusesImplicitResolverAndWrongPeerOwner')
$taskRequired+=@('TestMcrfPipeNumericSocksNeverLooksUpNumericTargets','TestMcrfPipeNumericSocksUsesDispatcherDnsWithMetadata','TestMcrfPipeNumericSocksDnsFailureNeverFallsBack')
$taskMutex=[Threading.Mutex]::new($false,'Local\MCRF-Singbox-P2-Native')
if(!$taskMutex.WaitOne(0)){throw 'P2 native producer is already active'}
$taskNames=@('PATH','GOPATH','GOTOOLCHAIN','GOPROXY','GOSUMDB','JAVA_HOME','ANDROID_HOME','ANDROID_NDK_HOME','GOFLAGS','GOOS','GOARCH','CGO_ENABLED')
$taskSaved=@{};foreach($name in $taskNames){$taskSaved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$taskOwner="P2_ROUTE_NATIVE:$taskStage"
$taskBuild=[ordered]@{schema=1;priority=2;state='STARTING';sourceReceipt=$taskPath;sourceReceiptSha256=$taskReceiptSha;sourceReadySha256=$taskGateSha;sourceStable=$false;producerComplete=$false;compiled=$false;installed=$false;published=$false;runtimeVerified=$false;version='1.14.2-lx.11-mcrf-route-pipe.1';moduleVersionCode=8;packetRouteBridge='owned_pipe_stack_v1';retainedDirectEvidence=$taskOldPath;retainedDirectEvidenceSha256=(Hash $taskOldPath);tests=@();producerSha256=(Hash $PSCommandPath);goSha256=(Hash $taskGo);gomobileSha256=(Hash $taskMobile)}
if($previousBuild){$taskBuild=$previousBuild;$taskBuild.producerSha256=Hash $PSCommandPath;$taskBuild.finalizationOnly=$true;$taskBuild.originalReceiptSha256=Hash "$taskStage/pre-api-resume.json";$taskBuild.producerComplete=$false}
function Save{[IO.File]::WriteAllText((Join-Path $taskStage 'build-receipt.json'),($taskBuild|ConvertTo-Json -Depth 9),[Text.UTF8Encoding]::new($false))}
function SaveCurrent($value){
    if((Get-Item -LiteralPath $taskCurrentPath).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Redirected current-state record refused'}
    $temporary=Join-Path ([IO.Path]::GetDirectoryName($taskCurrentPath)) ('current-p2-'+[guid]::NewGuid().ToString('N')+'.tmp')
    $bytes=[Text.UTF8Encoding]::new($false).GetBytes(($value|ConvertTo-Json -Depth 20))
    $stream=[IO.File]::Open($temporary,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$stream.Write($bytes,0,$bytes.Length);$stream.Flush($true)}finally{$stream.Dispose()}
    [IO.File]::Move($temporary,$taskCurrentPath,$true)
}
function InvokeOwnedNative([string]$Executable,[string[]]$Arguments,[string]$Log){
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$Executable;$info.WorkingDirectory=$taskSource
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($argument in $Arguments){$info.ArgumentList.Add($argument)}
    $child=$null;$output=$null;$errors=$null;$code=-1;$changed=$false;$started=$false
    try {
        $child=[Diagnostics.Process]::new();$child.StartInfo=$info
        if(!$child.Start()){throw 'Native child did not start'};$started=$true
        $output=$child.StandardOutput.ReadToEndAsync();$errors=$child.StandardError.ReadToEndAsync()
        while(!$child.WaitForExit(1000)){
            try{if((Hash $taskGate) -cne $taskGateSha){$changed=$true}}catch{$changed=$true}
        }
    }finally{
        if($child -and $started){
            # A log/readiness IO failure is not permission to abandon a compiler
            # or guest test process. Retain ownership until actual launcher exit.
            while(!$child.WaitForExit(1000)){}
            $code=$child.ExitCode
            $text='';if($output){$text+=$output.GetAwaiter().GetResult()};if($errors){$text+=$errors.GetAwaiter().GetResult()}
            $child.Dispose()
            [IO.File]::WriteAllText($Log,$text,[Text.UTF8Encoding]::new($false))
        }elseif($child){$child.Dispose()}
    }
    if($changed -or $code -ne 0){throw 'Native child failed or whole-P2 readiness changed; retain its exact log'}
}
function ApiClasses([string]$Jar){
    $zip=[IO.Compression.ZipFile]::OpenRead($Jar)
    try{
        $classes=@($zip.Entries|Where-Object {$_.FullName.EndsWith('.class')}|ForEach-Object {
            if($_.FullName -cnotmatch '^[A-Za-z_$][A-Za-z0-9_$]*(/[A-Za-z_$][A-Za-z0-9_$]*)*\.class$'){throw 'Unsafe/unexpected generated API class name'}
            $_.FullName.Substring(0,$_.FullName.Length-6).Replace('/','.')
        })
        if(!$classes.Count -or $classes.Count -gt 4096 -or @($classes|Sort-Object -Unique).Count -ne $classes.Count){throw 'Bounded unique native API class inventory required'}
        return $classes
    }finally{$zip.Dispose()}
}
function ApiSurface([string]$Jar,[string[]]$Classes,[string]$Prefix){
    $surface=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    for($offset=0;$offset -lt $Classes.Count;$offset+=16){
        $batch=@($Classes[$offset..([Math]::Min($Classes.Count-1,$offset+15))])
        $log=Join-Path $taskStage ($Prefix+'-'+$offset+'.log')
        InvokeOwnedNative "$taskJava/bin/javap.exe" (@('-public','-classpath',$Jar)+$batch) $log
        $current=$null
        foreach($line in [IO.File]::ReadAllLines($log)){
            if($line -cmatch '\b(class|interface|enum) ([A-Za-z0-9_.$]+)'){
                $name=$Matches[2]
                if($batch -cnotcontains $name){throw 'Unexpected native API descriptor class'}
                $current=$name;[void]$seen.Add($name);[void]$surface.Add($name+'|'+$line.Trim())
            }elseif($current -and $line -cmatch '^\s*(public|protected) '){[void]$surface.Add($current+'|'+$line.Trim())}
        }
    }
    if($seen.Count -ne $Classes.Count){throw 'Generated API surface incomplete'}
    # Do not let PowerShell enumerate the set into unrelated output records.
    return ,$surface
}
try {
    Save;$taskCurrent.jobs.build=$taskOwner;SaveCurrent $taskCurrent
    [IO.File]::Copy($PSCommandPath,(Join-Path $taskStage $(if($ResumeNative){'build-resume-control.ps1'}else{'build-control.ps1'})),$false)
    $env:PATH=[IO.Path]::GetDirectoryName($taskGo)+';'+[IO.Path]::GetDirectoryName($taskMobile)+";$taskJava/bin;"+$env:PATH
    $env:GOPATH="$taskRoot/work/toolchains/go-home";$env:GOTOOLCHAIN='local';$env:GOPROXY='off';$env:GOSUMDB='off'
    $env:JAVA_HOME=$taskJava;$env:ANDROID_HOME=$taskSdk;$env:ANDROID_NDK_HOME=$taskNdk;$env:GOFLAGS='-mod=readonly -p=4'
    if((& $taskGo version) -notmatch '^go version go1\.26\.8 windows/amd64' -or $LASTEXITCODE){throw 'Pinned Go version required'}
    if((Get-Content -LiteralPath "$taskNdk/source.properties" -Raw) -notmatch 'Pkg.Revision\s*=\s*28\.2\.13676358'){throw 'Pinned NDK required'}
    if(!$ResumeNative){
    if($KernelEvidence){
        $kernel=& "$PSScriptRoot/Read-P2KernelEvidence.ps1" -Path $KernelEvidence -Component dispatcher
        if($kernel.package.dispatcherSourceReceiptSha256 -cne $taskReceiptSha -or $kernel.sourceReadySha256 -cne $taskGateSha){throw 'Actual dispatcher kernel proof belongs to another frozen source'}
        $taskArch='aarch64';$taskBuild.kernelEvidence=$KernelEvidence;$taskBuild.kernelEvidenceSha256=$kernel.sha256
    }else{$taskArch=(& wsl.exe --distribution $Distribution --exec uname -m|Out-String).Trim();if($LASTEXITCODE){throw 'Explicit existing WSL environment required; never install/change it'}}
    $env:GOOS='linux';$env:GOARCH=switch($taskArch){'x86_64'{'amd64'}'aarch64'{'arm64'}default{throw 'Unsupported owned Linux fixture target'}};$env:CGO_ENABLED='0'
    $taskBuild.testTarget='linux/'+$env:GOARCH;$taskBuild.state='AFFECTED_LINUX_COMPILE';Save
    Push-Location $taskSource
    try {
        if($KernelEvidence){
            [IO.File]::Copy($kernel.program.binary,"$taskStage/pipe-tests",$false)
            [IO.File]::Copy($kernel.log,"$taskStage/pipe-tests.log",$false)
        }else{
        InvokeOwnedNative $taskGo @('test','-c','-ldflags=-checklinkname=0','-tags=with_gvisor,with_mcrf_direct_family','-o',"$taskStage/pipe-tests",'./experimental/libbox') "$taskStage/pipe-compile.log"
        $taskGuest='/mnt/d/'+$taskStage.Substring(3).Replace('\','/')+'/pipe-tests'
        $taskBuild.testBinarySha256=Hash "$taskStage/pipe-tests";$taskBuild.state='AFFECTED_LINUX_RUN';Save
        InvokeOwnedNative 'wsl.exe' @('--distribution',$Distribution,'--exec',$taskGuest,'-test.v','-test.count=1','-test.timeout=60s','-test.run=^TestMcrfPipe') "$taskStage/pipe-tests.log"
        }
        $taskLog=[IO.File]::ReadAllText("$taskStage/pipe-tests.log")
        $taskPassed=@([regex]::Matches($taskLog,'(?m)^--- PASS: (TestMcrfPipe[A-Za-z0-9_]+) \(')|ForEach-Object {$_.Groups[1].Value})
        if($taskLog -match '(?m)^\s*(--- (FAIL|SKIP):|FAIL)' -or (($taskPassed|Sort-Object)-join ',') -cne (($taskRequired|Sort-Object)-join ',')){throw 'Affected Linux stack/registry cases failed/skipped/incomplete'}
        $taskBuild.tests=@{group=$(if($KernelEvidence){'P2_PIPE_ANDROID_KERNEL'}else{'P2_PIPE_LINUX'});passed=$taskPassed.Count;cases=$taskPassed;logSha256=(Hash "$taskStage/pipe-tests.log");newExecution=(!$KernelEvidence)}
        # The retained route implementation is unchanged; do not rerun its old suites.
        $env:GOOS=$taskSaved.GOOS;$env:GOARCH=$taskSaved.GOARCH;$env:CGO_ENABLED=$taskSaved.CGO_ENABLED
        $taskBuild.state='AAR_BUILD';Save
        $taskTags='with_gvisor,with_quic,with_wireguard,with_utls,with_naive_outbound,badlinkname,tfogo_checklinkname0,with_xhttp,with_awg,with_lx_command,with_lx_chain,with_lx_idle_suspend,with_openvpn,with_openconnect,with_tailscale,ts_omit_logtail,ts_omit_ssh,ts_omit_drive,ts_omit_taildrop,ts_omit_webclient,ts_omit_doctor,ts_omit_capture,ts_omit_kube,ts_omit_aws,ts_omit_synology,ts_omit_bird,with_clash_api,with_mcrf_direct_family'
        $taskBuild.tags=$taskTags
        InvokeOwnedNative $taskMobile @('bind','-o',"$taskStage/runtime.aar",'-target','android/arm,android/arm64,android/amd64','-androidapi','24','-javapkg','io.nekohasekai','-libname','box','-trimpath','-buildvcs=false','-ldflags','-X github.com/sagernet/sing-box/constant.Version=1.14.2-lx.11-mcrf-route-pipe.1 -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid=','-tags',$taskTags,'./experimental/libbox') "$taskStage/aar-build.log"
    }finally{Pop-Location}
    }elseif($KernelEvidence){
        $kernel=& "$PSScriptRoot/Read-P2KernelEvidence.ps1" -Path $KernelEvidence -Component dispatcher
        if($kernel.sha256 -cne $taskBuild.kernelEvidenceSha256 -or (Hash "$taskStage/pipe-tests.log") -cne $taskBuild.tests.logSha256){throw 'Retained kernel evidence changed'}
    }
    AssertInputs
    $taskBuild.nativeCompileCompleted=$true;$taskBuild.retainedNativeAarSha256=Hash "$taskStage/runtime.aar"
    $taskBuild.sourceStable=$true;$taskBuild.state='API_ABI_GATE';Save
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead("$taskStage/runtime.aar")
    try {
        $abis=@($zip.Entries|Where-Object {$_.FullName -cmatch '^jni/(arm64-v8a|armeabi-v7a|x86_64)/libbox.so$'}|ForEach-Object {$_.FullName})
        if(($abis|Sort-Object)-join ',' -cne 'jni/arm64-v8a/libbox.so,jni/armeabi-v7a/libbox.so,jni/x86_64/libbox.so'){throw 'Three exact external dispatcher ABIs required'}
        $classes=$zip.GetEntry('classes.jar');if(!$classes){throw 'Native API classes missing'}
        if($ResumeNative){
            $stream=$classes.Open()
            try{$entryHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()}finally{$stream.Dispose()}
            if((Hash "$taskStage/native-classes.jar") -cne $entryHash){throw 'Retained API classes differ from completed AAR'}
        }else{[IO.Compression.ZipFileExtensions]::ExtractToFile($classes,"$taskStage/native-classes.jar",$false)}
    }finally{$zip.Dispose()}
    $taskApi=(& "$taskJava/bin/javap.exe" -classpath "$taskStage/native-classes.jar" io.nekohasekai.libbox.Libbox 2>&1|Out-String)
    if($LASTEXITCODE){throw 'Generated native API could not be inspected'}
    foreach($signature in @('public static int mcrfPacketPipeSchema();','public static int mcrfNumericSocksSchema();','public static void mcrfAttachPacketPipe(long, java.lang.String, java.lang.String, int, int, int, java.lang.String) throws java.lang.Exception;','public static void mcrfFailPacketPipe(long, java.lang.String, java.lang.String) throws java.lang.Exception;','public static void mcrfClosePacketPipes(long) throws java.lang.Exception;')){
        if(!$taskApi.Contains($signature.Replace('public static ','public static native '))){throw 'Generated packet-route API differs from the reflected contract'}
    }
    [IO.File]::WriteAllText("$taskStage/api-signatures.txt",$taskApi,[Text.UTF8Encoding]::new($false))
    $oldAar=Join-Path $taskOldStage 'runtime.aar'
    if((Hash $oldAar) -cne $taskOld.aarSha256){throw 'Retained original native AAR changed'}
    $oldZip=[IO.Compression.ZipFile]::OpenRead($oldAar)
    try{
        $classes=$oldZip.GetEntry('classes.jar');if(!$classes){throw 'Retained native API classes missing'}
        [IO.Compression.ZipFileExtensions]::ExtractToFile($classes,"$taskStage/retained-native-classes.jar",$false)
    }finally{$oldZip.Dispose()}
    $oldClasses=@(ApiClasses "$taskStage/retained-native-classes.jar")
    $newClasses=@(ApiClasses "$taskStage/native-classes.jar")
    if(@($oldClasses|Where-Object {$newClasses -cnotcontains $_}).Count){throw 'Existing native API class removed'}
    $oldSurface=ApiSurface "$taskStage/retained-native-classes.jar" $oldClasses 'api-old'
    $newSurface=ApiSurface "$taskStage/native-classes.jar" $oldClasses 'api-new'
    if(@($oldSurface|Where-Object {!$newSurface.Contains($_)}).Count){throw 'Existing generated native API signature/class contract changed'}
    $surfaceReceipt=[ordered]@{scope='Static generated Java API only, not Android/runtime';retainedAarSha256=$taskOld.aarSha256;oldClasses=$oldClasses.Count;newClasses=$newClasses.Count;retainedSignatures=$oldSurface.Count;preserved=$true}
    [IO.File]::WriteAllText("$taskStage/api-compatibility.json",($surfaceReceipt|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
    $taskBuild.retainedApiPreserved=$true;$taskBuild.apiCompatibilitySha256=Hash "$taskStage/api-compatibility.json"
    AssertInputs
    if((Hash $PSCommandPath) -cne $taskBuild.producerSha256 -or (Hash $taskGo) -cne $taskBuild.goSha256 -or (Hash $taskMobile) -cne $taskBuild.gomobileSha256 -or (Hash $taskOldPath) -cne $taskBuild.retainedDirectEvidenceSha256){throw 'Producer/compiler/retained receipt changed'}
    $taskBuild.apiSha256=Hash "$taskStage/api-signatures.txt";$taskBuild.abis=$abis
    $taskBuild.aarSha256=Hash "$taskStage/runtime.aar";$taskBuild.aarBytes=(Get-Item -LiteralPath "$taskStage/runtime.aar").Length
    $taskBuild.sourceStable=$true;$taskBuild.compiled=$true;$taskBuild.producerComplete=$true;$taskBuild.state='COMPILED_API_ABI_PASS_RUNTIME_OPEN';Save
    Write-Output "P2_ROUTE_NATIVE=$taskStage/build-receipt.json"
}catch{$taskBuild.state=if($taskBuild.nativeCompileCompleted){'API_ABI_FAILED_NATIVE_RETAINED'}else{'FAILED'};$taskBuild.producerComplete=$true;Save;throw}
finally{
    foreach($name in $taskNames){[Environment]::SetEnvironmentVariable($name,$taskSaved[$name],'Process')}
    $latest=Get-Content -LiteralPath $taskCurrentPath -Raw|ConvertFrom-Json
    if($latest.jobs.build -ceq $taskOwner){$latest.jobs.build=$null;SaveCurrent $latest}
    $taskMutex.ReleaseMutex();$taskMutex.Dispose()
}
