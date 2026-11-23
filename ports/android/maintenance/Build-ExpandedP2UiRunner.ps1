param([string]$Common='D:/MCRF/Projects/MCRF/work/android-integration-7eb12edebcfe481eb1ceaff2b872eae7',[switch]$AcceptedBinaryDiagnostic)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation build
$android=[IO.Path]::GetFullPath("$PSScriptRoot/..")
$root=[IO.Path]::GetFullPath("$android/../..")
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
$build=Get-Content "$Common/build-result.json" -Raw|ConvertFrom-Json
$evidence=Get-Content "$Common/p2-expansion-artifacts.json" -Raw|ConvertFrom-Json
$jvm=Get-Content "$Common/p2-expansion-jvm-evidence.json" -Raw|ConvertFrom-Json
$generationProof=($jvm.retained.mode -ceq 'csqtt-generations-affected-only' -and @($jvm.retained.cases).Count -eq 14 -and $jvm.newExecutionCount -ge 11) -or ($jvm.retained.mode -ceq 'csqtt-generations-fixture-resume' -and @($jvm.retained.cases).Count -eq 60 -and $jvm.retained.resumedCaseCount -eq 46 -and $jvm.retained.baseRetainedCaseCount -eq 14 -and $jvm.newExecutionCount -eq 1 -and $jvm.retained.partial.parentExitCode -eq 1 -and $jvm.retained.partial.fixtureOnlyCorrection)
$generationProof=$generationProof -or ($jvm.retained.mode -ceq 'csqtt-generations-packaging-visual-only' -and $jvm.retained.allRetained -and @($jvm.retained.cases).Count -eq 61 -and $jvm.newExecutionCount -eq 0)
$generationProof=$generationProof -or ($jvm.retained.mode -ceq 'csqtt-generations-autodeploy-ux' -and !$jvm.retained.allRetained -and @($jvm.retained.cases).Count -eq 61 -and $jvm.newExecutionCount -eq 7 -and $jvm.count -eq 68)
$generationProof=$generationProof -or ($jvm.retained.mode -ceq 'csqtt-generations-historical-ipc-only' -and $jvm.retained.allRetained -and @($jvm.retained.cases).Count -eq 68 -and $jvm.newExecutionCount -eq 0 -and $jvm.count -eq 68)
$generationProof=$generationProof -or ($jvm.retained.mode -ceq 'csqtt-generations-swg25-accordion' -and !$jvm.retained.allRetained -and @($jvm.retained.cases).Count -eq 63 -and $jvm.newExecutionCount -eq 12 -and $jvm.count -eq 75)
$generationProof=$generationProof -or ($jvm.retained.mode -ceq 'csqtt-generations-swg-subnet-only' -and $jvm.retained.allRetained -and @($jvm.retained.cases).Count -eq 75 -and $jvm.newExecutionCount -eq 0 -and $jvm.count -eq 75)
if($build.exitCode -ne 0 -or !$build.sourceStable -or !$jvm.passed -or ($jvm.count -notin @(19,22,23,29,31,34,52) -and !$generationProof) -or @($jvm.cases).Count -ne $jvm.count -or
   $jvm.selectionSha256 -cne (Hash "$Common/ports/android/coordination/p2-expansion-jvm-affected-2026-10-21.txt") -or
   $evidence.sourceManifestSha256 -cne (Hash "$Common/source-manifest.json") -or
   $evidence.jvmEvidenceSha256 -cne (Hash "$Common/p2-expansion-jvm-evidence.json")){throw 'Accepted expanded common source/JVM/APK receipts required'}
$current=Get-Content "$android/coordination/current.json" -Raw|ConvertFrom-Json
if($current.activeBatch -cne 'p2-expansion-2026-10-21.json' -or @($current.jobs.PSObject.Properties|Where-Object {$_.Value}).Count){throw 'Exclusive idle expanded-P2 coordinator required'}
# The test uses installed classes reflectively; it must match accepted production code.
$production=@(Get-Content "$Common/source-manifest.json" -Raw|ConvertFrom-Json|Where-Object {$_.path.Replace('\','/').StartsWith('ports/android/app/src/main/')})
if(!$production.Count){throw 'No accepted main source inventory'}
$targetRoot=if($AcceptedBinaryDiagnostic){$Common}else{$root}
foreach($row in $production){if((Hash (Join-Path $targetRoot $row.path)) -cne $row.sha256){throw "Target source changed: $($row.path)"}}
foreach($row in $evidence.artifacts){if((Hash (Join-Path $Common $row.path)) -cne $row.sha256){throw 'Accepted artifact changed'}}
$stage="D:/MCRF/Builds/Android/p2-expanded-ui-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $stage,"$stage/classes","$stage/dex"|Out-Null
$result=[ordered]@{state='PREPARING';common=$Common;scope='Only remaining new P2 UI/live scenarios; no app/engine/JVM/native rebuild or old-suite replay';sourceStable=$false;mainAndModulesUnchanged=$false;deviceRun=$false;published=$false}
$result.acceptedBinaryDiagnostic=[bool]$AcceptedBinaryDiagnostic
if($AcceptedBinaryDiagnostic){$result.scope='Diagnosis against exact INSTALLED accepted snapshot only; unfinished current source excluded; not regression acceptance'}
$sdk='C:/Users/dieonfire/AppData/Local/Android/Sdk';$bt="$sdk/build-tools/36.0.0";$jdk='C:/Program Files/Java/jdk-17/bin'
function ExitCheck{if($LASTEXITCODE){throw "Tool failed: $LASTEXITCODE"}}
Copy-Item -LiteralPath "$PSScriptRoot/p2-expanded-ui/ExpandedUiInstrumentation.java" -Destination "$stage/ExpandedUiInstrumentation.java"
Copy-Item -LiteralPath "$PSScriptRoot/p2-expanded-ui/AndroidManifest.xml" -Destination "$stage/AndroidManifest.xml"
Copy-Item -LiteralPath "$PSScriptRoot/p2-expanded-ui/P2ExpandedLive.java" -Destination "$stage/P2ExpandedLive.java"
$support="$android/verification/p2/P2ServiceScenarios.java"
Copy-Item -LiteralPath $support -Destination "$stage/P2ServiceScenarios.java"
$supportHash=Hash $support
$inputs=@('ExpandedUiInstrumentation.java','AndroidManifest.xml','P2ExpandedLive.java')|ForEach-Object {@{path=$_;sha256=Hash "$stage/$_"}}
$appJar="$Common/ports/android/app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar"
$retained="$root/work/android-integration-0dcead46dd0e473fa46510ffe38f6dea/work/p2-primary-p2-steer-candidate-d3992f56f32d41438c654d3d785162a1"
$spi="$retained/spi.jar";$kotlin="$retained/steer/bundled/org.jetbrains.kotlin_kotlin-stdlib_2.2.10.jar"
$dependencyHashes=@{};foreach($file in @($appJar,$spi,$kotlin)){$dependencyHashes[$file]=Hash $file}
Write-Output "P2_EXPANDED_UI=$stage"
try{
    & "$jdk/javac.exe" --release 8 -encoding UTF-8 -classpath "$sdk/platforms/android-34/android.jar;$appJar;$spi;$kotlin" -d "$stage/classes" "$stage/ExpandedUiInstrumentation.java" "$stage/P2ExpandedLive.java" "$stage/P2ServiceScenarios.java" *> "$stage/javac.log";ExitCheck
    & "$jdk/jar.exe" cf "$stage/classes.jar" -C "$stage/classes" .;ExitCheck
    & "$bt/d8.bat" --min-api 29 --lib "$sdk/platforms/android-34/android.jar" --output "$stage/dex" "$stage/classes.jar" *> "$stage/d8.log";ExitCheck
    & "$bt/aapt2.exe" link -I "$sdk/platforms/android-34/android.jar" --manifest "$stage/AndroidManifest.xml" -o "$stage/unsigned.apk" *> "$stage/aapt.log";ExitCheck
    & "$jdk/jar.exe" uf "$stage/unsigned.apk" -C "$stage/dex" classes.dex;ExitCheck
    & "$bt/zipalign.exe" -f 4 "$stage/unsigned.apk" "$stage/aligned.apk";ExitCheck
    & "$bt/apksigner.bat" sign --ks C:/Users/dieonfire/.android/debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$stage/runner.apk" "$stage/aligned.apk";ExitCheck
    $certificate=(& "$bt/apksigner.bat" verify --print-certs "$stage/runner.apk" 2>&1|Out-String);ExitCheck
    if($certificate -notmatch 'certificate SHA-256 digest:\s*([0-9a-f]{64})'){throw 'No runner signer'};$signer=$Matches[1]
    $main=@($evidence.artifacts|Where-Object {$_.path -match 'app-universal-debug.apk$'})
    if($main.Count -ne 1){throw 'Exact common main APK missing'}
    $mainCert=(& "$bt/apksigner.bat" verify --print-certs (Join-Path $Common $main[0].path) 2>&1|Out-String);ExitCheck
    if($mainCert -notmatch 'certificate SHA-256 digest:\s*([0-9a-f]{64})' -or $Matches[1] -cne $signer){throw 'Instrumentation signer must match exact installed common'}
    & "$bt/zipalign.exe" -c 4 "$stage/runner.apk";ExitCheck
    foreach($row in $inputs){if((Hash "$stage/$($row.path)") -cne $row.sha256 -or (Hash "$PSScriptRoot/p2-expanded-ui/$($row.path)") -cne $row.sha256){throw 'Runner source changed'}}
    if((Hash $support) -cne $supportHash -or (Hash "$stage/P2ServiceScenarios.java") -cne $supportHash){throw 'Retained peer/ownership support changed'}
    foreach($file in $dependencyHashes.Keys){if((Hash $file) -cne $dependencyHashes[$file]){throw 'Compile dependency changed'}}
    $result.supportSha256=$supportHash;$result.compileDependencies=$dependencyHashes
    foreach($row in $production){if((Hash (Join-Path $targetRoot $row.path)) -cne $row.sha256){throw 'Target production source changed'}}
    foreach($row in $evidence.artifacts){if((Hash (Join-Path $Common $row.path)) -cne $row.sha256){throw 'Accepted artifacts changed'}}
    $result.mainAndModulesUnchanged=$true;$result.sourceStable=$true;$result.apk="$stage/runner.apk";$result.sha256=Hash $result.apk;$result.signer=$signer;$result.state='COMPILED_DEVICE_PENDING'
}catch{$result.state='FAILED_PRESERVED';$result.error=$_.Exception.Message;throw}
finally{
    [IO.File]::WriteAllText("$stage/source-manifest.json",($inputs|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText("$stage/receipt.json",($result|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
    Write-Output "P2_UI_STATE=$($result.state)"
}
