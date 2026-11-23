param([Parameter(Mandatory=$true)][string]$WholeReceipt)
$ErrorActionPreference='Stop'
$root='D:/MCRF/Projects/MCRF'
function Sha([string]$p){(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant()}
function Json([string]$p){Get-Content -LiteralPath $p -Raw|ConvertFrom-Json}
function Save([string]$p,$v){[IO.File]::WriteAllText($p,($v|ConvertTo-Json -Depth 30),[Text.UTF8Encoding]::new($false))}
$whole=Json $WholeReceipt
if($whole.state -cne 'COMMON_HOST_BUILD_PASS_ANDROID_NATIVE_TRAFFIC_ACCEPTANCE_PENDING' -or !$whole.sourceStable){throw 'Completed common verification required'}
$parent=[IO.Path]::GetFullPath($whole.phases.common_main_r8_debug_runner_helpers_and_external_modules.snapshot)
if([IO.Path]::GetDirectoryName($parent) -ine (Join-Path $root 'work') -or [IO.Path]::GetFileName($parent) -cnotmatch '^android-integration-[0-9a-f]{32}$'){throw 'Exact parent snapshot required'}
$build=Json (Join-Path $parent 'build-result.json');$artifacts=Json (Join-Path $parent 'p1-owner-artifacts.json')
if($build.exitCode -ne 0 -or !$build.sourceStable -or !$build.p1OwnerFixture -or
 (Sha (Join-Path $parent 'source-manifest.json')) -cne $build.sourceManifestSha256 -or
 (Sha (Join-Path $parent 'p1-owner-artifacts.json')) -cne $whole.phases.common_main_r8_debug_runner_helpers_and_external_modules.artifactsSha256){throw 'Parent receipt mismatch'}
# A new immutable device-control view, NOT another compiled build. All compiled
# inputs and APKs remain exactly the parent's. Never edit its historical receipt.
$allowed=@('ports/android/integration-device-checks.ps1','ports/android/integration-p1-steer.ps1','ports/android/integration-packet-phone.ps1',
 'work/steer-android-port/android-vpn/tests/p1_owned_peer.py','work/steer-android-port/android-vpn/tests/test_p1_owned_peer.py')
$manifest=@(Json (Join-Path $parent 'source-manifest.json'))
$destination=Join-Path $root ('work/android-integration-'+[guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($destination)|Out-Null
$changes=@()
foreach($row in $manifest){
 $relative=$row.path.Replace('\','/')
 if($relative -cnotmatch '^[A-Za-z0-9_./-]+$' -or $relative.Split('/') -contains '..'){throw 'Invalid frozen relative path'}
 $old=Join-Path $parent $relative
 if((Get-Item -LiteralPath $old).LinkType -or (Sha $old) -cne $row.sha256){throw 'Parent source changed'}
 $input=$old
 if($relative -cin $allowed){
   $input=Join-Path $root $relative
   if($relative.EndsWith('.ps1')){
     $tokens=$null;$errors=$null;[void][Management.Automation.Language.Parser]::ParseFile($input,[ref]$tokens,[ref]$errors)
     if($errors.Count){throw 'Corrected coordinator syntax invalid'}
   }
   $hash=Sha $input
   if($hash -cne $row.sha256){$changes+=@{path=$relative;before=$row.sha256;after=$hash};$row.sha256=$hash;$row.bytes=(Get-Item -LiteralPath $input).Length}
 }
 $target=Join-Path $destination $relative;[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))|Out-Null
 Copy-Item -LiteralPath $input -Destination $target
 if((Sha $target) -cne $row.sha256){throw 'Frozen copy changed'}
}
if(!$changes.Count -or $changes.Count -gt $allowed.Count){throw 'Only reviewed noncompiled device coordinator/peer repair allowed'}
foreach($row in @($artifacts.artifacts)+@($artifacts.externalCandidates)){
 $relative=$row.path.Replace('\','/')
 if($relative -cnotmatch '^[A-Za-z0-9_./-]+\.apk$' -or $relative.Split('/') -contains '..'){throw 'Invalid APK relative path'}
 $input=Join-Path $parent $relative
 if((Get-Item -LiteralPath $input).LinkType -or (Get-Item -LiteralPath $input).Length -ne $row.bytes -or (Sha $input) -cne $row.sha256){throw 'Compiled parent APK changed'}
 $target=Join-Path $destination $relative;[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))|Out-Null
 Copy-Item -LiteralPath $input -Destination $target
 if((Sha $target) -cne $row.sha256){throw 'Copied APK changed'}
}
Save (Join-Path $destination 'source-manifest.json') $manifest
$manifestSha=Sha (Join-Path $destination 'source-manifest.json')
$build.snapshot=$destination;$build.sourceManifestSha256=$manifestSha
$proof=@{parentSnapshot=$parent;parentBuildResultSha256=Sha (Join-Path $parent 'build-result.json');parentSourceManifestSha256=Sha (Join-Path $parent 'source-manifest.json');wholeReceipt=$WholeReceipt;wholeReceiptSha256=Sha $WholeReceipt;compiledInputsAndApksUnchanged=$true;newCompilation=$false;changes=$changes;reason='Reviewed NONCOMPILED device coordinator/independent Python peer repairs only. Includes mode-specific markers, proxy versus DNS join, bounded persistent DNS capacity. No app/native/Gradle input or APK changed. Peer regression proof required separately before executing repaired peer.'}
$build|Add-Member -NotePropertyName retainedCommonBuild -NotePropertyValue $proof
$artifacts.sourceManifestSha256=$manifestSha
$artifacts|Add-Member -NotePropertyName retainedCommonBuild -NotePropertyValue $proof
Save (Join-Path $destination 'build-result.json') $build
Save (Join-Path $destination 'p1-owner-artifacts.json') $artifacts
Save (Join-Path $destination 'device-control-repair.json') $proof
Write-Output "P1_DEVICE_CONTROL_VIEW=$destination;COMPILED_INPUTS_APKS_BYTE_IDENTICAL;NO_REBUILD"
