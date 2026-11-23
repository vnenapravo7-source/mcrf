param([Parameter(Mandatory=$true)][string]$WholeReceipt,[Parameter(Mandatory=$true)][string]$Snapshot)
$ErrorActionPreference='Stop';$root='D:/MCRF/Projects/MCRF'
function Sha([string]$p){(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}
$whole=Get-Content -LiteralPath $WholeReceipt -Raw|ConvertFrom-Json
if($whole.state -cne 'COMMON_HOST_BUILD_PASS_ANDROID_NATIVE_TRAFFIC_ACCEPTANCE_PENDING' -or !$whole.sourceStable){throw 'Completed common evidence required'}
$parent=$whole.phases.common_main_r8_debug_runner_helpers_and_external_modules.snapshot
$view=[IO.Path]::GetFullPath((Join-Path $root $Snapshot));$build=Get-Content -LiteralPath (Join-Path $view 'build-result.json') -Raw|ConvertFrom-Json
if($build.retainedCommonBuild.parentSnapshot -cne $parent -or !$build.retainedCommonBuild.compiledInputsAndApksUnchanged -or $build.retainedCommonBuild.newCompilation -ne $false){throw 'Exact source-bound control-only view required'}
$python='C:/Users/dieonfire/AppData/Local/Programs/Python/Python314/python.exe'
$relative='work/steer-android-port/android-vpn/tests'
$priorLog=$whole.phases.independent_steer_peer_new_cases.receipt
if((Sha $priorLog) -cne $whole.phases.independent_steer_peer_new_cases.sha256 -or [IO.File]::ReadAllText($priorLog) -cnotmatch '(?s)Ran 8 tests in .+\r?\n\r?\nOK\s*$'){throw 'Original eight peer cases must be complete and unchanged'}
$out=Join-Path $root ('work/ports-test-2026-10-14/p1-peer-capacity-'+[guid]::NewGuid().ToString('N'));[IO.Directory]::CreateDirectory($out)|Out-Null
# AST comparison is deliberately BEFORE running the sole new regression. Old
# test bodies and all their production callables/constants must remain exact.
$audit=@'
import ast,pathlib,sys
old,new=map(pathlib.Path,sys.argv[1:])
for name in ('p1_owned_peer.py','test_p1_owned_peer.py'):
    before=ast.parse((old/name).read_text(encoding='utf-8-sig'))
    after=ast.parse((new/name).read_text(encoding='utf-8-sig'))
    defs={node.name:node for node in after.body if isinstance(node,(ast.FunctionDef,ast.ClassDef))}
    for node in before.body:
        if isinstance(node,(ast.FunctionDef,ast.ClassDef)):
            # The old eight invoke exchange/Reader/dns_reply, not the listener
            # entrypoint serve. Its admission change is the NEW regression.
            if name=='p1_owned_peer.py' and node.name=='serve': continue
            actual=defs[node.name]
            if node.name=='OwnedPeerTests':
                prior_methods={x.name:ast.dump(x) for x in node.body if isinstance(x,ast.FunctionDef)}
                fresh_methods={x.name:ast.dump(x) for x in actual.body if isinstance(x,ast.FunctionDef)}
                assert all(fresh_methods[k]==v for k,v in prior_methods.items()), name+':old test body changed'
                assert len(fresh_methods)==len(prior_methods)+1
            else: assert ast.dump(node)==ast.dump(actual), name+':'+node.name
        if isinstance(node,(ast.Assign,ast.Import,ast.ImportFrom)):
            assert any(ast.dump(node)==ast.dump(x) for x in after.body)
print('UNCHANGED_EIGHT_CASE_BODIES_AND_PRODUCTION_DEPENDENCIES')
'@
$auditOutput=@($audit|& $python - (Join-Path $parent $relative) (Join-Path $view $relative) 2>&1)
[IO.File]::WriteAllLines((Join-Path $out 'ast-retention.log'),[string[]]$auditOutput,[Text.UTF8Encoding]::new($false))
if($LASTEXITCODE -ne 0 -or $auditOutput -cnotcontains 'UNCHANGED_EIGHT_CASE_BODIES_AND_PRODUCTION_DEPENDENCIES'){throw 'Prior peer cases affected: cannot retain them'}
Push-Location (Join-Path $view $relative)
try{$log=@(& $python -m unittest test_p1_owned_peer.OwnedPeerTests.test_persistent_dns_capacity_leaves_bounded_http_room 2>&1);$code=$LASTEXITCODE}finally{Pop-Location}
[IO.File]::WriteAllLines((Join-Path $out 'regression.log'),[string[]]$log,[Text.UTF8Encoding]::new($false))
if($code -ne 0 -or ($log -join "`n") -cnotmatch '(?s)Ran 1 test in .+\n\nOK\s*$'){throw 'New capacity regression failed; original output retained'}
$inputs=@();foreach($name in @('p1_owned_peer.py','test_p1_owned_peer.py')){$inputs+=@{file=$name;sha256=Sha (Join-Path (Join-Path $view $relative) $name)}}
$result=@{passed=$true;retainedCases=8;executedCases=1;wholeReceiptSha256=Sha $WholeReceipt;parentSnapshot=$parent;snapshot=$view;pythonSha256=Sha $python;priorEightLogSha256=Sha $priorLog;inputs=$inputs;phoneTouched=$false;actualNativeAcceptance=$false}
[IO.File]::WriteAllText((Join-Path $out 'receipt.json'),($result|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
Write-Output "P1_PEER_CAPACITY_RECEIPT=$out/receipt.json;PASS1_RETAINED8_NO_RERUN"
