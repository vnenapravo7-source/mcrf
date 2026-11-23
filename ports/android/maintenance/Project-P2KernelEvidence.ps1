#requires -Version 7.0
param([Parameter(Mandatory=$true)][string]$Path,[Parameter(Mandatory=$true)][ValidateSet('group','wg')][string]$Component)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation native-build
$taskRoot='D:/MCRF/Projects/MCRF'
function Hash([string]$path){(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}
$kernel=& "$PSScriptRoot/Read-P2KernelEvidence.ps1" -Path $Path -Component $Component
$stage=if($Component -ceq 'group'){'D:/MCRF/Builds/Android/p2-process-group-'+[guid]::NewGuid().ToString('N')}else{"$taskRoot/work/wg-host-"+[guid]::NewGuid().ToString('N')}
New-Item -ItemType Directory -Path $stage|Out-Null
$proof=[ordered]@{priority=2;state='OWNED_ANDROID_KERNEL_PASS_APP_UNVERIFIED';passed=$true;sourceStable=$true;sourceReadySha256=$kernel.sourceReadySha256;
    kernelEvidence=$kernel.path;kernelEvidenceSha256=$kernel.sha256;producerSha256=(Hash $PSCommandPath);component=$Component;
    cases=@($kernel.cases);newExecution=$false;androidAppAccepted=$false;goSha256=$kernel.package.goSha256}
if($Component -ceq 'group'){
    $inputs=[ordered]@{}
    foreach($row in $kernel.program.inputs.PSObject.Properties){$name=[IO.Path]::GetFileName($row.Name);$inputs[$name]=$row.Value
        $source=Join-Path "$($kernel.sourceStage)/group" $name
        if((Hash $source) -cne $row.Value){throw 'Exact executed group source changed'}
        [IO.File]::Copy($source,(Join-Path $stage $name),$false)
    }
    if($inputs.Count -ne 6){throw 'Complete six-file group scope required'}
    $proof.inputs=$inputs;$proof.logSha256=$kernel.logSha256;$proof.testBinarySha256=$kernel.program.sha256
    [IO.File]::Copy($kernel.program.binary,"$stage/group-tests-linux",$false)
    [IO.File]::Copy($kernel.log,"$stage/tests.log",$false)
    [IO.File]::Copy($PSCommandPath,"$stage/producer-control.ps1",$false)
    [IO.File]::Copy("$taskRoot/ports/android/coordination/p2-progress-2026-10-20.json","$stage/p2-progress-2026-10-20.json",$false)
}else{
    $priorPath="$taskRoot/work/wg-host-dffe23f456ba4e38ab9300dbdb244d69/receipt.json"
    $prior=Get-Content -LiteralPath $priorPath -Raw|ConvertFrom-Json
    $common=Get-Content -LiteralPath 'D:/MCRF/Builds/Android/p1-whole-17fb34f9d2324793918321b1fe266a7e/receipt.json' -Raw|ConvertFrom-Json
    if(!$prior.passed -or !$prior.sourceStable -or $prior.upstreamCommit -cne '64065e6826ad82aa28429dd829c1c65f29b7f6c9' -or
       (Hash $priorPath) -cne $common.phases.wg_awg_and_managed_kernel_dns_host.sha256){throw 'Retained closed WG evidence required'}
    $canonical="$taskRoot/ports/android/transports/wg-backend"
    $files=@(Get-ChildItem -LiteralPath $canonical -File|Where-Object {$_.Extension -ceq '.go' -or $_.Name -in @('go.mod','go.sum')})
    if($files.Count -ne @($prior.inputs).Count+2){throw 'Only the reviewed private-pipe extension may be new'}
    foreach($row in $prior.inputs){
        $old=Join-Path ([IO.Path]::GetDirectoryName($priorPath)) ('source/'+$row.name);$now=Join-Path $canonical $row.name
        $expected=$row.sha256
        if($row.name -ceq 'go.mod'){
            $bound=@($prior.frozenInputs|Where-Object name -CEQ 'go.mod')
            if($bound.Count -ne 1 -or (Hash $now) -cne $row.sha256){throw 'Original WG module intent changed'}
            $expected=$bound[0].sha256 # Recorded post-freeze private replacement, not raw go.mod.
        }
        if((Hash $old) -cne $expected){throw 'Retained source changed'}
        if($row.name -ceq 'start_android.go'){
            $text=[IO.File]::ReadAllText($now).Replace("`r`n","`n")
            $begin=$text.IndexOf('// Separate INTERNAL per-server packet role.',[StringComparison]::Ordinal);$end=$text.IndexOf('// Separate opt-in extension:',[StringComparison]::Ordinal)
            if($begin -lt 0 -or $end -le $begin -or $text.Remove($begin,$end-$begin) -cne [IO.File]::ReadAllText($old).Replace("`r`n","`n")){throw 'Unreviewed old Android WG path change'}
        }elseif((Hash $now) -cne $row.sha256){throw 'Unchanged WG source differs'}
    }
    $upstream="$taskRoot/work/android-upstream/sing-box-lx-mcrf-clash-20261014/submodules/wireguard-go"
    $frozen="$($kernel.executedSourceStage)/wg-upstream"
    $tracked=@(& git -C $upstream ls-files);if($LASTEXITCODE -or ((& git -C $upstream status --porcelain|Out-String).Trim())){throw 'Pristine WG dependency required'}
    if(@(Get-ChildItem -LiteralPath $frozen -Recurse -File).Count -ne $tracked.Count -or @(Get-ChildItem -LiteralPath $frozen -Recurse -Attributes ReparsePoint).Count){throw 'Frozen WG dependency inventory differs'}
    foreach($name in $tracked){if([IO.Path]::IsPathRooted($name) -or '..' -in ($name -split '/') -or (Hash (Join-Path $frozen $name)) -cne (Hash (Join-Path $upstream $name))){throw 'Actual tested dependency bytes differ'}}
    $proof.upstreamCommit=$prior.upstreamCommit;$proof.kernelFrozenUpstream=$frozen
    $proof.inputs=@($files|ForEach-Object {[pscustomobject]@{name=$_.Name;sha256=(Hash $_.FullName)}})
    $proof.frozenInputs=@($files|ForEach-Object {[pscustomobject]@{name=$_.Name;sha256=(Hash (Join-Path "$($kernel.executedSourceStage)/wg" $_.Name))}})
    $proof.executedTests=@($kernel.cases);$proof.retainedTests=@($prior.tests);$proof.tests=@($prior.tests)+@($kernel.cases)
    $protocols=@([regex]::Matches([IO.File]::ReadAllText($kernel.log),'--- PASS: TestPacketPipeEncryptedDualStack/(WG|AWG3_1|AWG3) \(')|ForEach-Object {$_.Groups[1].Value})
    if((($protocols|Sort-Object)-join ',') -cne 'AWG3,AWG3_1,WG'){throw 'Actual encrypted WG/AWG protocol subcases required'}
    $proof.encryptedPipeProtocols=$protocols;$proof.target='linux/arm64';$proof.testLogSha256=$kernel.logSha256
    [IO.File]::Copy($kernel.log,"$stage/pipe-tests.log",$false)
}
[IO.File]::WriteAllText("$stage/receipt.json",($proof|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
Write-Output "P2_PROJECTED_KERNEL=$stage/receipt.json"
