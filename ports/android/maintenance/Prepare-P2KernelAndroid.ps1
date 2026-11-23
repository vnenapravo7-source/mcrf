#requires -Version 7.0
param([Parameter(Mandatory=$true)][string]$DispatcherSourceReceipt,[string]$RetainedKernel='')
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation native-build
if($args.Count){throw 'Unknown kernel package scope'}
$taskRoot='D:/MCRF/Projects/MCRF';$taskCoord="$taskRoot/ports/android/coordination"
function Hash([string]$path){(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}
$taskCurrent=Get-Content -LiteralPath "$taskCoord/current.json" -Raw|ConvertFrom-Json
if($taskCurrent.activeBatch -cne 'p2-progress-2026-10-20.json' -or @($taskCurrent.jobs.PSObject.Properties|Where-Object Value).Count){throw 'Idle exact common P2 batch required'}
$taskGate="$taskCoord/p2-progress-2026-10-20.json";$taskGateSha=Hash $taskGate
$taskSourceReceipt=Get-Content -LiteralPath $DispatcherSourceReceipt -Raw|ConvertFrom-Json
if($taskSourceReceipt.state -cne 'SOURCE_FROZEN_UNCOMPILED_UNTESTED' -or !$taskSourceReceipt.producerComplete -or $taskSourceReceipt.readinessSha256 -cne $taskGateSha){throw 'Exact frozen dispatcher source required'}
$taskDispatcher=Join-Path $taskSourceReceipt.stage 'source'
$taskGo="$taskRoot/work/toolchains/go1.26.8/go/bin/go.exe"
$taskOwnerTemplate=Join-Path $PSScriptRoot 'p2-kernel-owner.go.in'
$taskStage='D:/MCRF/Builds/Android/p2-kernel-'+[guid]::NewGuid().ToString('N')
New-Item -ItemType Directory -Path $taskStage|Out-Null
$taskOwner='P2_KERNEL_BUILD:'+$taskStage
$taskRetained=$null;$taskRetainedPackage=$null
if($RetainedKernel){
    $taskRetained=Get-Content -LiteralPath $RetainedKernel -Raw|ConvertFrom-Json
    $taskRetainedPackage=Get-Content -LiteralPath $taskRetained.package -Raw|ConvertFrom-Json
    if(!$taskRetained.cleanupComplete -or $taskRetained.cleanupPending -or @($taskRetained.failures).Count -ne 1 -or $taskRetained.failures[0] -cne 'group' -or
       (Hash $taskRetained.package) -cne $taskRetained.packageSha256 -or $taskRetainedPackage.dispatcherSourceReceiptSha256 -cne (Hash $DispatcherSourceReceipt)){
        throw 'Exact retired prior package with only the Android children-file fixture failure required'
    }
}
$taskState=[ordered]@{priority=2;state='COMPILING';sourceReadySha256=$taskGateSha;sourceStable=$false;compiled=$false;tested=$false;phoneTouched=$false;producerSha256=(Hash $PSCommandPath);ownerTemplateSha256=(Hash $taskOwnerTemplate);goSha256=(Hash $taskGo);dispatcherSourceReceipt=$DispatcherSourceReceipt;dispatcherSourceReceiptSha256=(Hash $DispatcherSourceReceipt);programs=@();failures=@()}
function Save{[IO.File]::WriteAllText("$taskStage/receipt.json",($taskState|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))}
function SaveCurrent($value){[IO.File]::WriteAllText("$taskCoord/current.json",($value|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))}
function Run([string]$exe,[string[]]$arguments,[string]$cwd,[string]$log){
    $info=[Diagnostics.ProcessStartInfo]::new($exe);$info.WorkingDirectory=$cwd;$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($a in $arguments){$info.ArgumentList.Add($a)}
    $child=[Diagnostics.Process]::Start($info);$out=$child.StandardOutput.ReadToEndAsync();$err=$child.StandardError.ReadToEndAsync()
    try{while(!$child.WaitForExit(1000)){};$code=$child.ExitCode;[IO.File]::WriteAllText($log,($out.GetAwaiter().GetResult()+$err.GetAwaiter().GetResult()),[Text.UTF8Encoding]::new($false))}finally{$child.Dispose()}
    if($code){throw 'Common kernel compilation/preparation failed; retain log'}
}
function Owner([string]$directory,[string]$package){
    $path=Join-Path $directory 'p2_kernel_owner_test.go'
    [IO.File]::WriteAllText($path,[IO.File]::ReadAllText($taskOwnerTemplate).Replace('package PACKAGE',"package $package"),[Text.UTF8Encoding]::new($false));return $path
}
$taskEnv=@{};foreach($name in @('GOOS','GOARCH','CGO_ENABLED','GO111MODULE','GOPATH','GOCACHE','GOTOOLCHAIN','GOFLAGS','GOWORK','GOENV','GOPROXY','GOSUMDB')){$taskEnv[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$taskCurrent.jobs.build=$taskOwner;SaveCurrent $taskCurrent
try{
    Save
    $group="$taskRoot/ports/android/transports/swg";$wg="$taskRoot/ports/android/transports/wg-backend"
    $groupNames=@('mcrf_process_group.go','mcrf_process_group_linux.go','mcrf_process_group_test.go','mcrf_process_group_linux_test.go','mcrf_child_input.go','mcrf_child_input_test.go')
    $groupDir="$taskStage/group";$wgDir="$taskStage/wg";New-Item -ItemType Directory -Path $groupDir,$wgDir|Out-Null
    $groupInputs=[ordered]@{};foreach($name in $groupNames){$groupInputs[(Join-Path $group $name)]=Hash (Join-Path $group $name);[IO.File]::Copy((Join-Path $group $name),(Join-Path $groupDir $name),$false)}
    $wgInputs=[ordered]@{};foreach($file in Get-ChildItem -LiteralPath $wg -File|Where-Object {$_.Extension -ceq '.go' -or $_.Name -in @('go.mod','go.sum')}){$wgInputs[$file.FullName]=Hash $file.FullName;[IO.File]::Copy($file.FullName,(Join-Path $wgDir $file.Name),$false)}
    $upstream="$taskRoot/work/android-upstream/sing-box-lx-mcrf-clash-20261014/submodules/wireguard-go"
    $head=(& git -C $upstream rev-parse HEAD|Out-String).Trim();if($LASTEXITCODE -or $head -cne '64065e6826ad82aa28429dd829c1c65f29b7f6c9' -or ((& git -C $upstream status --porcelain|Out-String).Trim())){throw 'WG dependency must match its pristine pinned commit'}
    Run 'git.exe' @('-C',$upstream,'archive','--format=tar','-o',"$taskStage/wg-upstream.tar",$head) $taskStage "$taskStage/archive.log"
    $members=@(& tar -tf "$taskStage/wg-upstream.tar");if($LASTEXITCODE -or @($members|Where-Object {$_ -match '(^/|^[A-Za-z]:|(^|/)\.\.(/|$)|\\)'}).Count){throw 'Unsafe frozen upstream archive'}
    New-Item -ItemType Directory -Path "$taskStage/wg-upstream"|Out-Null
    Run 'tar.exe' @('-xf',"$taskStage/wg-upstream.tar",'-C',"$taskStage/wg-upstream") $taskStage "$taskStage/extract.log"
    $taskState.wgUpstreamSha256=Hash "$taskStage/wg-upstream.tar"
    $env:GOOS='linux';$env:GOARCH='arm64';$env:CGO_ENABLED='0';$env:GOTOOLCHAIN='local';$env:GOWORK='off';$env:GOENV='off';$env:GOPROXY='off';$env:GOSUMDB='off';$env:GOPATH="$taskRoot/work/toolchains/go-home";$env:GOCACHE="$taskRoot/work/toolchains/go-cache-wgbackend";$env:GOFLAGS='-mod=readonly -p=4'
    Run $taskGo @('mod','edit',"-replace=github.com/sagernet/wireguard-go=$taskStage/wg-upstream") $wgDir "$taskStage/wg-mod.log"
    $groupOwner=Owner $groupDir 'main';$wgOwner=Owner $wgDir 'wgbackend'
    $dispatcherOwner=Owner $taskStage 'libbox'
    $overlay=[ordered]@{Replace=@{(Join-Path $taskDispatcher 'experimental/libbox/p2_kernel_owner_test.go')=$dispatcherOwner}}
    [IO.File]::WriteAllText("$taskStage/overlay.json",($overlay|ConvertTo-Json -Depth 3),[Text.UTF8Encoding]::new($false))
    $groupCases=@($groupNames|Where-Object {$_.EndsWith('_test.go')}|ForEach-Object {[regex]::Matches([IO.File]::ReadAllText((Join-Path $groupDir $_)),'(?m)^func (TestMcrf[A-Za-z0-9_]+)\(')}|ForEach-Object {$_.Groups[1].Value}|Where-Object {$_ -cne 'TestMcrfProcessGroupLinuxChild'})
    $wgCases=@([regex]::Matches([IO.File]::ReadAllText("$wgDir/route_pipe_test.go"),'(?m)^func (TestPacketPipe[A-Za-z0-9_]+)\(')|ForEach-Object {$_.Groups[1].Value})
    $dispatcherCases=@(Get-ChildItem -LiteralPath "$taskDispatcher/experimental/libbox" -File -Filter 'p2_*_test.go'|ForEach-Object {[regex]::Matches([IO.File]::ReadAllText($_.FullName),'(?m)^func (TestMcrfPipe[A-Za-z0-9_]+)\(')}|ForEach-Object {$_.Groups[1].Value})
    if($groupCases.Count -ne 8 -or $wgCases.Count -ne 6 -or $dispatcherCases.Count -ne 14){throw 'Review complete kernel case inventory before execution'}
    $plans=@(
        @{name='group';cwd=$groupDir;package='main';cases=$groupCases;inputs=$groupInputs;arguments=@('test','-c','-trimpath','-o',"$taskStage/group-tests")+$groupNames+@('p2_kernel_owner_test.go')},
        @{name='wg';cwd=$wgDir;package='wgbackend';cases=$wgCases;inputs=$wgInputs;arguments=@('test','-c','-trimpath','-ldflags=-checklinkname=0','-o',"$taskStage/wg-tests",'.')},
        @{name='dispatcher';cwd=$taskDispatcher;package='libbox';cases=$dispatcherCases;inputs=@{};arguments=@('test','-c','-trimpath','-ldflags=-checklinkname=0','-overlay',"$taskStage/overlay.json",'-tags=with_gvisor,with_mcrf_direct_family','-o',"$taskStage/dispatcher-tests",'./experimental/libbox')})
    foreach($plan in $plans){
        try{
            $retained=$null;$retainedCases=@();$runCases=@($plan.cases)
            if($taskRetained){
                $old=@($taskRetainedPackage.programs|Where-Object name -CEQ $plan.name)[0]
                $proof=@($taskRetained.programs|Where-Object name -CEQ $plan.name)[0]
                if(!$old -or !$proof.actualRetirement -or $proof.binarySha256 -cne $old.sha256 -or (Hash $old.binary) -cne $old.sha256 -or
                   (Hash (Join-Path ([IO.Path]::GetDirectoryName($RetainedKernel)) ($plan.name+'-android.log'))) -cne $proof.logSha256 -or
                   $taskRetainedPackage.ownerTemplateSha256 -cne $taskState.ownerTemplateSha256 -or $taskRetainedPackage.goSha256 -cne $taskState.goSha256){throw 'Retained fixture origin changed'}
                foreach($row in $plan.inputs.GetEnumerator()){
                    $previous=$old.inputs.PSObject.Properties[$row.Key]
                    if(!$previous){throw 'Retained dependency missing'}
                    if($plan.name -ceq 'group' -and [IO.Path]::GetFileName($row.Key) -ceq 'mcrf_process_group_linux_test.go'){
                        $before=[IO.File]::ReadAllText((Join-Path ([IO.Path]::GetDirectoryName($taskRetained.package)) 'group/mcrf_process_group_linux_test.go'))
                        $after=[IO.File]::ReadAllText($row.Key)
                        $marker='func TestMcrfProcessGroupLinuxChild'
                        if($before.IndexOf($marker,[StringComparison]::Ordinal) -lt 0 -or $after.IndexOf($marker,[StringComparison]::Ordinal) -lt 0 -or
                           $before.Substring(0,$before.IndexOf($marker,[StringComparison]::Ordinal)) -cne $after.Substring(0,$after.IndexOf($marker,[StringComparison]::Ordinal)) -or
                           @([regex]::Matches($after,'(?m)^func ')).Count -ne 2){throw 'Only the two real-child fixture functions may differ'}
                    }elseif($previous.Value -cne $row.Value){throw 'Unchanged retained production/model source differs'}
                }
                $retained=@{receipt=$RetainedKernel;sha256=(Hash $RetainedKernel);package=$taskRetained.package;packageSha256=$taskRetained.packageSha256;name=$plan.name}
                if($plan.name -cne 'group'){
                    if(!$proof.passed -or ((@($proof.cases)|Sort-Object)-join ',') -cne ((@($plan.cases)|Sort-Object)-join ',')){throw 'Full unchanged fixture group was not PASS'}
                    [IO.File]::Copy($old.binary,"$taskStage/$($plan.name)-tests",$false)
                    $taskState.programs+=@{name=$plan.name;binary="$taskStage/$($plan.name)-tests";sha256=$old.sha256;target='linux/arm64';expectedCases=$plan.cases;inputs=$plan.inputs;retained=$retained;fixtureOnly=$true;androidAppAccepted=$false}
                    Save;continue
                }
                $runCases=@('TestMcrfProcessGroupLinuxRealAckAndWholeTreeRetirement')
                $retainedCases=@($plan.cases|Where-Object {$_ -cnotin $runCases})
                if(((@($proof.cases)|Sort-Object)-join ',') -cne (($retainedCases|Sort-Object)-join ',')){throw 'Exactly seven unaffected model cases must be retained'}
            }
            $env:GO111MODULE=if($plan.name -ceq 'group'){'off'}else{'on'}
            Run $taskGo $plan.arguments $plan.cwd "$taskStage/$($plan.name)-compile.log"
            $binary="$taskStage/$($plan.name)-tests"
            $taskState.programs+=@{name=$plan.name;binary=$binary;sha256=(Hash $binary);target='linux/arm64';expectedCases=$plan.cases;runCases=$runCases;retainedCases=$retainedCases;retained=$retained;inputs=$plan.inputs;fixtureOnly=$true;androidAppAccepted=$false}
        }catch{$taskState.failures+=@{name=$plan.name;message=$_.Exception.Message} }
        Save
    }
    foreach($inputs in @($groupInputs,$wgInputs)){foreach($row in $inputs.GetEnumerator()){if((Hash $row.Key) -cne $row.Value){throw 'Canonical kernel source changed'}}}
    if((Hash $taskGate) -cne $taskGateSha -or (Hash $DispatcherSourceReceipt) -cne $taskState.dispatcherSourceReceiptSha256 -or (Hash $taskOwnerTemplate) -cne $taskState.ownerTemplateSha256){throw 'Common input changed'}
    $taskState.sourceStable=$true;$taskState.compiled=$taskState.failures.Count -eq 0;$taskState.state=if($taskState.compiled){'COMPILED_UNRUN'}else{'COMPILE_FAILED'};Save
    Write-Output "P2_KERNEL_PACKAGE=$taskStage/receipt.json"
    if(!$taskState.compiled){throw 'Common kernel package has compile failures; no phone execution'}
}finally{
    foreach($name in $taskEnv.Keys){[Environment]::SetEnvironmentVariable($name,$taskEnv[$name],'Process')}
    Save
    $current=Get-Content -LiteralPath "$taskCoord/current.json" -Raw|ConvertFrom-Json
    if($current.jobs.build -ceq $taskOwner){$current.jobs.build=$null;SaveCurrent $current}
}
