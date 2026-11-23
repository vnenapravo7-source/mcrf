param([Parameter(Mandatory)][string]$ExistingSnapshot)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation test
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
$taskOld=(Resolve-Path $ExistingSnapshot).Path.Replace([char]92,[char]47)
if($taskOld -cnotmatch '^D:/MCRF/Projects/MCRF/work/android-integration-[a-f0-9]{32}$'){throw 'Exact failed common integration snapshot required'}
$taskBuild=Get-Content "$taskOld/build-result.json" -Raw|ConvertFrom-Json
if($taskBuild.exitCode -ne 1 -or !$taskBuild.sourceStable -or $taskBuild.sourceManifestSha256 -cne (Hash "$taskOld/source-manifest.json")){throw 'Original stable failed build proof required'}
$taskRoot='D:/MCRF/Projects/MCRF'
$taskTest='ports/android/app/src/test/kotlin/org/mcrf/client/NativeRouteDispatchPlanTest.kt'
$taskRows=@(Get-Content "$taskOld/source-manifest.json" -Raw|ConvertFrom-Json)
$taskChecked=0
foreach($row in $taskRows|Where-Object {$_.path -match '^ports/android/app/(src/main/|src/test/|libs/|build\.gradle\.kts$|proguard-rules\.pro$)' -or $_.path -match '^work/steer-android-port/android-vpn/(module-contract|integration-helper)/'}){
 if($row.path -ceq $taskTest){continue}
 if((Hash "$taskOld/$($row.path)") -cne $row.sha256 -or (Hash "$taskRoot/$($row.path)") -cne $row.sha256){throw "Passed JVM code/classpath changed: $($row.path)"}
 $taskChecked++
}
if($taskChecked -lt 100){throw 'Whole app/test source closure required'}
$taskBefore=[IO.File]::ReadAllText("$taskOld/$taskTest").Replace("`r`n","`n")
$taskAfter=[IO.File]::ReadAllText("$taskRoot/$taskTest").Replace("`r`n","`n")
$pattern='(?s)    @Test fun compactRouteProtocolsRequireOwnedHelpersInsteadOfPretendSteerUris\(\).*?(?=    @Test fun compactMainAndRouteCaptureKeepIndependentOriginalsAndRejectPostCaptureMutation\()'
if([regex]::Matches($taskBefore,$pattern).Count -ne 1 -or [regex]::Matches($taskAfter,$pattern).Count -ne 1 -or [regex]::Replace($taskBefore,$pattern,'') -cne [regex]::Replace($taskAfter,$pattern,'')){throw 'Only the failed method may change'}
$taskPassed=@();$taskFailed=@();$taskXmlRows=@()
foreach($file in Get-ChildItem "$taskOld/ports/android/app/build/test-results/testDebugUnitTest" -File -Filter 'TEST-*.xml'){
 [xml]$xml=Get-Content $file.FullName
 foreach($case in $xml.testsuite.testcase){
  $name="$($case.classname).$($case.name)"
  if($case.skipped){throw 'No skipped JVM cases can be retained'}
  if($case.failure -or $case.error){$taskFailed+=$name}else{$taskPassed+=$name}
 }
 $taskXmlRows+=@{file=$file.Name;sha256=Hash $file.FullName}
}
$taskMissing='org.mcrf.client.NativeRouteDispatchPlanTest.compactRouteProtocolsRequireOwnedHelpersInsteadOfPretendSteerUris'
if($taskPassed.Count -ne 57 -or @($taskPassed|Sort-Object -Unique).Count -ne 57 -or $taskFailed.Count -ne 1 -or $taskFailed[0] -cne $taskMissing){throw 'Exact 57 passed / one failed scoped common run required'}
$taskStage="D:/MCRF/Builds/Android/p3-jvm-retained-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path "$taskStage/xml"|Out-Null
foreach($row in $taskXmlRows){Copy-Item -LiteralPath "$taskOld/ports/android/app/build/test-results/testDebugUnitTest/$($row.file)" -Destination "$taskStage/xml/$($row.file)"}
$taskProof=@{state='RETAINED_57_NEW_JVM_CASES';originalSnapshot=$taskOld;originalBuildReceiptSha256=Hash "$taskOld/build-result.json";originalSourceManifestSha256=Hash "$taskOld/source-manifest.json";verifiedUnchangedInputs=$taskChecked;cases=$taskPassed;remaining=@($taskMissing);xml=$taskXmlRows;testsExecuted=$false;originalBuildRemainsFailed=$true;onlyFailedMethodChanged=$true}
[IO.File]::WriteAllText("$taskStage/receipt.json",($taskProof|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
Write-Output "P3_JVM_RETAINED=$taskStage"
