param([Parameter(Mandatory=$true)][string]$Module,[ValidateSet('csqtt','csqtt25')][string]$Kind='csqtt')
$ErrorActionPreference='Stop'
# Read-only retention, NOT a new run or a relabelled acceptance receipt. The only
# changed shared-bridge function is the private config parser. Its new cases run
# in the expanded batch; the process/input helpers and complete execution tail
# must still match the accepted immutable package byte for byte.
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$currentBatch=Get-Content (Join-Path $root 'ports/android/coordination/p2-expansion-2026-10-21.json') -Raw|ConvertFrom-Json
if($currentBatch.sourceRevision -cin @('p2-turn-pool-20261022.2','p2-ui-notification-budget-20261022.3','p2-ui-notification-budget-20261022.4','p2-autodeploy-quick-navigation-20261022.5')){
    # Retain the already accepted diagnostic/tunnel execution, not the older
    # pre-diagnostics bridge. Only ingress changed in this package.
    $prior=if($Kind -ceq 'csqtt25'){'D:/MCRF/Builds/Android/csqtt25-captcha-3826488249184e138badc91ec899e8b8'}else{'D:/MCRF/Builds/Android/csqtt-captcha-d31a14c4338e4bbb96ef5def4e8dc80e'}
    function Hash([string]$path){(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}
    $receipt=Get-Content "$prior/receipt.json" -Raw|ConvertFrom-Json
    $closure=Get-Content "$prior/retained-owned-closure.json" -Raw|ConvertFrom-Json
    if(!$receipt.sourceStable -or $receipt.state -cne 'COMPILED_NOT_EXECUTED' -or $receipt.kind -cne $Kind -or
       (Hash "$prior/retained-owned-closure.json") -cne $receipt.retainedOwnerClosureSha256 -or
       (Hash "$prior/retained-owned-evidence.json") -cne $closure.retainedReceiptSha256 -or
       (Hash "$prior/expansion-affected-tests.jsonl") -cne $receipt.affectedLogSha256){throw 'Accepted native execution chain changed'}
    $events=@(Get-Content "$prior/expansion-affected-tests.jsonl"|ForEach-Object {$_|ConvertFrom-Json})
    if(@($events|Where-Object {$_.Action -cin @('fail','skip')}).Count){throw 'Failed accepted execution evidence'}
    if($Kind -ceq 'csqtt25'){
        foreach($name in @('Test25SharedBridgeWaitsForOwnedTransportAndGrandchildCleanupAfterStop','Test25SharedBridgeSendsPrivateBootstrapAndHonorsOwnedStopCleanup','Test25TunnelOptionalPolicyDoesNotBecomeRevisionOrRouting','Test25TunnelIPv6DnsAndMalformedFramesAreHandledWithoutSecrets')){
            if(@($events|Where-Object {$_.Action -ceq 'pass' -and $_.Test -ceq $name}).Count -ne 1){throw 'Accepted bridge/frame case missing'}
        }
    }
    $helpers=@()
    foreach($name in @('mcrf_process_group.go','mcrf_process_group_linux.go','mcrf_process_group_other.go','mcrf_child_input.go','csqtt_bridge.go')){
        $file=Get-Item -LiteralPath "$prior/process-group-inputs/$name"
        $row=@($receipt.processGroupInputs|Where-Object {$_.file -ceq $name})
        if($file.LinkType -or $row.Count -ne 1 -or (Hash $file.FullName) -cne $row[0].sha256){throw 'Accepted execution input changed'}
        if($name -cne 'csqtt_bridge.go'){
            if((Hash (Join-Path $Module $name)) -cne $row[0].sha256){throw 'Kernel helper changed'}
            $helpers+=@{file=$name;sha256=$row[0].sha256}
        }
    }
    function Execution([string]$path){
        $text=[IO.File]::ReadAllText($path).Replace("`r`n","`n")
        $begin=$text.IndexOf('func readCSQTTConfig(',[StringComparison]::Ordinal)
        $end=$text.IndexOf('func runCSQTTBridge(',[StringComparison]::Ordinal)
        if($begin -lt 0 -or $end -le $begin){throw 'Unexpected execution boundary'}
        $text.Substring(0,$begin)+$text.Substring($end)
    }
    $bridge="$prior/process-group-inputs/csqtt_bridge.go"
    if((Execution $bridge) -cne (Execution (Join-Path $Module 'csqtt_bridge.go'))){throw 'Execution changed; accepted cases cannot be retained'}
    $proof=Get-Content "$prior/retained-owned-evidence.json" -Raw|ConvertFrom-Json
    if(!$proof.passed -or !$proof.sourceStable -or @($proof.cases).Count -ne 8){throw 'Incomplete owned kernel chain'}
    return [ordered]@{newExecution=$false;scope='Exact accepted execution and kernel helpers; only changed ingress runs fresh';requiresAffectedBridgeRecheck=$false;
        retainedReceiptSha256=Hash "$prior/retained-owned-evidence.json";oldBridgeSha256=Hash $bridge;helpers=$helpers;
        oldBridge=$bridge;retainedReceipt="$prior/retained-owned-evidence.json";cases=@($proof.cases);
        acceptedExecution=@{stage=$prior;receiptSha256=Hash "$prior/receipt.json";closureSha256=Hash "$prior/retained-owned-closure.json";logSha256=$receipt.affectedLogSha256}}
}
$old=Join-Path $root ('work/android-integration-0dcead46dd0e473fa46510ffe38f6dea/ports/android/'+$(if($Kind -ceq 'csqtt25'){'native-stage-csqtt25'}else{'native-stage-csqtt-legacy'}))
$accepted=Get-Content -LiteralPath (Join-Path $root 'ports/android/coordination/p2-runtime-acceptance-2026-10-21.json') -Raw|ConvertFrom-Json
if(!$accepted.fullP2Accepted){throw 'Prior P2 is not accepted; no retention available'}
$rows=@()
foreach($name in @('mcrf_process_group.go','mcrf_process_group_linux.go','mcrf_process_group_other.go','mcrf_child_input.go')){
    $previous=Get-Item -LiteralPath (Join-Path $old "process-group-inputs/$name")
    $current=Get-Item -LiteralPath (Join-Path $Module $name)
    if($previous.LinkType -or $current.LinkType -or $previous.PSIsContainer -or $current.PSIsContainer){throw 'Ordinary retained helper required'}
    $sha=(Get-FileHash -LiteralPath $previous.FullName).Hash.ToLowerInvariant()
    if((Get-FileHash -LiteralPath $current.FullName).Hash.ToLowerInvariant() -cne $sha){throw 'Owned helper changed; its old acceptance cannot be retained'}
    $rows+=@{file=$name;sha256=$sha}
}
$bridgePath=Join-Path $old 'process-group-inputs/csqtt_bridge.go'
$previous=[IO.File]::ReadAllText($bridgePath)
$current=[IO.File]::ReadAllText((Join-Path $Module 'csqtt_bridge.go'))
$begin='func readCSQTTConfig('
$tail='func runCSQTTBridge('
foreach($body in @($previous,$current)){
    if($body.IndexOf($begin,[StringComparison]::Ordinal) -lt 0 -or $body.IndexOf($tail,[StringComparison]::Ordinal) -le $body.IndexOf($begin,[StringComparison]::Ordinal)){throw 'Unexpected bridge structure; retention refused'}
}
$oldClosure=$previous.Substring(0,$previous.IndexOf($begin,[StringComparison]::Ordinal))+$previous.Substring($previous.IndexOf($tail,[StringComparison]::Ordinal))
$newClosure=$current.Substring(0,$current.IndexOf($begin,[StringComparison]::Ordinal))+$current.Substring($current.IndexOf($tail,[StringComparison]::Ordinal))
$failureDelta=$false
if($oldClosure -cne $newClosure){
    # Retain only the unchanged kernel/input helpers. The exact three new
    # diagnostics changes need fresh shared-bridge cases in the SAME batch.
    $start='return fmt.Errorf("cannot start bundled CSQTT transport: %s", mcrfPublicChildFailure(c, err.Error()))'
    $wait=@'
	go func() {
		waitErr := command.Wait()
		if waitErr != nil && ctx.Err() == nil && command.ProcessState != nil { fmt.Printf("[CSQTT] child failure stage=transport exit=%d\n", command.ProcessState.ExitCode()) }
		writer.Close()
		select { case <-scanDone: case <-time.After(250 * time.Millisecond): }
		close(done)
	}()
'@
    $scan=@'
		// Keep only bounded, sanitized failures, never a config/challenge dump.
		if message := mcrfPublicChildFailure(c, line); message != "" {
			fmt.Printf("[CSQTT] child error: %s\n", message)
		}
'@
    $originalScan=@'
		// Raw transport logs may contain hashes and captcha session tokens.
		// Only explicit public lifecycle messages are emitted by this adapter.
'@
    $tunnel=@'
	if c.NormalizeTunnel != nil {
		config, err = c.NormalizeTunnel(config)
		if err != nil { return err }
	}
'@
    $normalized=$newClosure.Replace("`r`n","`n")
    foreach($part in @($start,$wait,$scan,$tunnel)){if(!$normalized.Contains($part)){throw 'Unknown bridge delta; old helper evidence alone cannot authorize it'}}
    $normalized=$normalized.Replace($tunnel+"`n",'')
    $normalized=$normalized.Replace($start,'return errors.New("cannot start bundled CSQTT transport")').Replace($wait,"`tgo func() { command.Wait(); writer.Close(); close(done) }()").Replace($scan,$originalScan)
    if($normalized -cne $oldClosure.Replace("`r`n","`n")){throw 'Additional execution/STOP/scan changes outside reviewed diagnostics; retention refused'}
    $failureDelta=$true
}
$proofPath=Join-Path $old 'process-group-evidence.json'
$proof=Get-Content -LiteralPath $proofPath -Raw|ConvertFrom-Json
if(!$proof.passed -or !$proof.sourceStable -or @($proof.cases).Count -ne 8){throw 'Retained owned group evidence incomplete'}
[ordered]@{newExecution=$false;scope='Unchanged kernel process/input helpers; changed diagnostics require fresh shared-bridge checks';requiresAffectedBridgeRecheck=$failureDelta;
    retainedReceiptSha256=(Get-FileHash -LiteralPath $proofPath).Hash.ToLowerInvariant();
    oldBridgeSha256=(Get-FileHash -LiteralPath $bridgePath).Hash.ToLowerInvariant();helpers=$rows;
    oldBridge=$bridgePath;retainedReceipt=$proofPath;cases=@($proof.cases)}
