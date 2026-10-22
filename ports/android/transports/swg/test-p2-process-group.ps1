#requires -Version 7.0
param([Parameter(Mandatory=$true)][string]$Distribution)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
& (Join-Path $PSScriptRoot '../../maintenance/Assert-HumanBatchReady.ps1') -Operation native-build
$taskGate=Join-Path $taskRoot 'ports/android/coordination/p2-progress-2026-10-20.json'
$taskReady=Get-Content -LiteralPath $taskGate -Raw|ConvertFrom-Json
$taskCurrent=Get-Content -LiteralPath (Join-Path $taskRoot 'ports/android/coordination/current.json') -Raw|ConvertFrom-Json
if($taskCurrent.activeBatch -cne 'p2-progress-2026-10-20.json' -or $taskReady.priority -ne 2){throw 'Exact ALL-five P2 source batch required'}
if($Distribution -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'){throw 'Explicit existing WSL distribution required'}
if($taskCurrent.jobs.build -or $taskCurrent.jobs.hostTest -or $taskCurrent.jobs.device -or $taskCurrent.jobs.emulator){throw 'Existing owned job must finish first'}
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Plain([string]$Path){
    $file=Get-Item -LiteralPath $Path
    if($file.PSIsContainer -or $file.LinkType){throw 'Ordinary source/tool file required'}
    for($parent=$file.Directory;$null -ne $parent;$parent=$parent.Parent){if($parent.LinkType){throw 'Redirected parent refused'}}
    $file.FullName
}
$taskGo=Plain (Join-Path $taskRoot 'work/toolchains/go1.26.8/go/bin/go.exe')
$taskWsl=Plain 'C:/Windows/System32/wsl.exe'
$taskGateSha=Hash $taskGate
$taskNames=@('mcrf_process_group.go','mcrf_process_group_linux.go','mcrf_process_group_test.go','mcrf_process_group_linux_test.go','mcrf_child_input.go','mcrf_child_input_test.go')
$taskSources=@{};foreach($name in $taskNames){$path=Plain (Join-Path $PSScriptRoot $name);$taskSources[$name]=Hash $path}
$taskProducerSha=Hash $PSCommandPath
$taskId=[guid]::NewGuid().ToString('N')
$taskStage="D:/MCRF/Builds/Android/p2-process-group-$taskId"
if(Test-Path -LiteralPath $taskStage){throw 'Fresh exact process-group stage required'}
[IO.Directory]::CreateDirectory($taskStage)|Out-Null
$taskMutex=[Threading.Mutex]::new($false,'Local\MCRF-P2-Process-Group')
if(!$taskMutex.WaitOne(0)){$taskMutex.Dispose();throw 'Exact P2 process-group owner already active'}
$taskOwner='P2ProcessGroup:'+$taskId
$taskState=[ordered]@{priority=2;state='STARTING';passed=$false;sourceStable=$false;sourceReadySha256=$taskGateSha;inputs=$taskSources;producerSha256=$taskProducerSha;goSha256=(Hash $taskGo);wslSha256=(Hash $taskWsl);distribution=$Distribution;cases=@();deviceAccess=$false;signalPolicy='Private stdin shutdown; signal0 observation only; no PID/group signal';scope='Real Linux owned helper group +bounded input only; NOT Android CSQTT transport/runtime acceptance'}
$taskState.cleanupPending=$false
$taskGuestLaunched=$false;$taskGuestProven=$false
$taskEnv=@{};foreach($name in @('GOOS','GOARCH','CGO_ENABLED','GO111MODULE','GOTOOLCHAIN','GOPROXY','GOSUMDB','GOWORK')){$taskEnv[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
function Save{[IO.File]::WriteAllText("$taskStage/receipt.json",($taskState|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))}
function Stable{
    if((Hash $taskGate) -cne $taskGateSha -or (Hash $PSCommandPath) -cne $taskProducerSha -or (Hash $taskGo) -cne $taskState.goSha256 -or (Hash $taskWsl) -cne $taskState.wslSha256){throw 'Frozen gate/producer/tool changed'}
    foreach($name in $taskNames){if((Hash (Join-Path $PSScriptRoot $name)) -cne $taskSources[$name] -or (Hash "$taskStage/$name") -cne $taskSources[$name]){throw 'Exact group source changed'}}
}
function InvokeOwned([string]$Executable,[string[]]$Arguments,[string]$Log){
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$Executable;$info.WorkingDirectory=$taskStage
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($argument in $Arguments){$info.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info;$started=$false;$output=$null;$errors=$null;$code=-1
    try{
        if(!$process.Start()){throw 'Owned process did not start'};$started=$true
        $output=$process.StandardOutput.ReadToEndAsync();$errors=$process.StandardError.ReadToEndAsync()
    }finally{
        if($started){
            # Retain actual process ownership even if source/log checks throw.
            while(!$process.WaitForExit(1000)){}
            $code=$process.ExitCode
            $text=$output.GetAwaiter().GetResult()+$errors.GetAwaiter().GetResult()
            [IO.File]::WriteAllText($Log,$text,[Text.UTF8Encoding]::new($false))
        }
        $process.Dispose()
    }
    if($code -ne 0){throw 'Owned compiler/guest test failed; retain exact log'}
}
try{
    Save
    $taskCurrent.jobs.hostTest=$taskOwner
    [IO.File]::WriteAllText((Join-Path $taskRoot 'ports/android/coordination/current.json'),($taskCurrent|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
    foreach($name in $taskNames){[IO.File]::Copy((Join-Path $PSScriptRoot $name),"$taskStage/$name",$false)}
    [IO.File]::Copy($PSCommandPath,"$taskStage/producer-control.ps1",$false)
    [IO.File]::Copy($taskGate,"$taskStage/p2-progress-2026-10-20.json",$false)
    Stable
    InvokeOwned $taskWsl @('--list','--quiet') "$taskStage/distributions.log"
    $available=@(([IO.File]::ReadAllText("$taskStage/distributions.log").Replace([string][char]0,'')) -split '\r?\n'|ForEach-Object {$_.Trim()})
    if($available -cnotcontains $Distribution){throw 'Existing exact WSL distribution not found; no install/download'}
    $env:GOOS='linux';$env:GOARCH='amd64';$env:CGO_ENABLED='0';$env:GO111MODULE='off';$env:GOTOOLCHAIN='local';$env:GOPROXY='off';$env:GOSUMDB='off';$env:GOWORK='off'
    InvokeOwned $taskGo (@('test','-c','-trimpath','-o',"$taskStage/group-tests-linux")+$taskNames) "$taskStage/compile.log"
    Stable
    $taskBinarySha=Hash "$taskStage/group-tests-linux"
    $taskGuest='/mnt/d/MCRF/Builds/Android/p2-process-group-'+$taskId+'/group-tests-linux'
    $taskGuestLaunched=$true
    InvokeOwned $taskWsl @('-d',$Distribution,'--exec',$taskGuest,'-test.v','-test.timeout=40s','-test.run=^(TestMcrfProcessGroupPrivate|TestMcrfProcessGroupCancel|TestMcrfProcessGroupMalformed|TestMcrfProcessGroupDesktop|TestMcrfProcessGroupChildDoes|TestMcrfProcessGroupLinuxReal|TestMcrfChildInput)') "$taskStage/tests.log"
    Stable
    if((Hash "$taskStage/group-tests-linux") -cne $taskBinarySha){throw 'Compiled guest test changed'}
    $log=[IO.File]::ReadAllText("$taskStage/tests.log")
    $cases=@([regex]::Matches($log,'(?m)^--- PASS: (TestMcrf[A-Za-z0-9_]+) \(')|ForEach-Object {$_.Groups[1].Value})
    $expected=@('TestMcrfProcessGroupPrivateAckIsExactAndOneShot','TestMcrfProcessGroupCancelNeverStartsUnacknowledgedChild','TestMcrfProcessGroupMalformedNonceRejectedBeforeOsOperation','TestMcrfProcessGroupDesktopFixtureDoesNotChangeItsProcessGroup','TestMcrfProcessGroupChildDoesNotInheritPrivateAckCapability','TestMcrfProcessGroupLinuxRealAckAndWholeTreeRetirement','TestMcrfChildInputBlockedPipeCannotBlockReservedStopOrReorderProofs','TestMcrfChildInputFailureWipesAndNeverRetriesOrReopens')
    if($log -match '(?m)^\s*(--- (FAIL|SKIP):|FAIL)' -or (($cases|Sort-Object)-join ',') -cne (($expected|Sort-Object)-join ',')){throw 'Exact eight new Linux cases failed/skipped/incomplete'}
    $taskGuestProven=$true
    $taskState.cases=$cases;$taskState.testBinarySha256=$taskBinarySha;$taskState.logSha256=Hash "$taskStage/tests.log"
    $taskState.sourceStable=$true;$taskState.passed=$true;$taskState.state='OWNED_LINUX_GROUP_AND_INPUT_PASS_NOT_ANDROID'
    Save;Write-Output "P2_PROCESS_GROUP=$taskStage/receipt.json"
}catch{$taskState.state='FAILED';$taskState.cleanupPending=$taskGuestLaunched -and !$taskGuestProven;Save;throw}
finally{
    foreach($name in $taskEnv.Keys){[Environment]::SetEnvironmentVariable($name,$taskEnv[$name],'Process')}
    $latest=Get-Content -LiteralPath (Join-Path $taskRoot 'ports/android/coordination/current.json') -Raw|ConvertFrom-Json
    if(!$taskState.cleanupPending -and $latest.jobs.hostTest -ceq $taskOwner){$latest.jobs.hostTest=$null;[IO.File]::WriteAllText((Join-Path $taskRoot 'ports/android/coordination/current.json'),($latest|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))}
    $taskMutex.ReleaseMutex();$taskMutex.Dispose()
}
