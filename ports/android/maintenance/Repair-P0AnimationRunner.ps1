param([Parameter(Mandatory=$true)][string]$ReceiptPath)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Assert-HumanBatchReady.ps1') -Operation build
$taskBatch=Get-Content -Raw -LiteralPath $ReceiptPath|ConvertFrom-Json
if($taskBatch.state -cne 'PASS' -or !$taskBatch.sourceStable -or @(Get-Process -Name java,go -ErrorAction SilentlyContinue).Count){throw 'Verified batch and no producer required'}
$taskRoot='D:/MCRF/Projects/MCRF';$taskSdk='C:/Users/dieonfire/AppData/Local/Android/Sdk';$taskJava='C:/Program Files/Java/jdk-17/bin'
$taskStage=Join-Path 'D:/MCRF/Builds/Android' ('p0-animation-runner-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskStage,"$taskStage/classes","$taskStage/dex"|Out-Null
Copy-Item -LiteralPath "$taskRoot/ports/android/app/src/androidTest/java/org/mcrf/client/ConnectionAnimationInstrumentation.java" -Destination "$taskStage/ConnectionAnimationInstrumentation.java"
Copy-Item -LiteralPath "$PSScriptRoot/p0-animation-runner/AndroidManifest.xml" -Destination "$taskStage/AndroidManifest.xml"
$taskInputs=@('ConnectionAnimationInstrumentation.java','AndroidManifest.xml')|ForEach-Object {@{path=$_;sha256=(Get-FileHash -LiteralPath "$taskStage/$_" -Algorithm SHA256).Hash}}
$taskResult=[ordered]@{state='STARTED';stage=$taskStage;parent=$ReceiptPath;mainUnchanged=$false;fixtureUnchanged=$false;scope='Runner-only repair; no main/fixture app/JVM/native rebuild';sourceStable=$false}
try{
    foreach($taskKind in @('main','fixture','runner')){if((Get-FileHash -LiteralPath $taskBatch.artifacts.$taskKind.apk -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskBatch.artifacts.$taskKind.sha256){throw 'Original batch bytes changed'}}
    & "$taskJava/javac.exe" --release 8 -encoding UTF-8 -classpath "$taskSdk/platforms/android-34/android.jar" -d "$taskStage/classes" "$taskStage/ConnectionAnimationInstrumentation.java" *> "$taskStage/javac.log";if($LASTEXITCODE){throw 'Framework runner compile failed'}
    & "$taskJava/jar.exe" cf "$taskStage/classes.jar" -C "$taskStage/classes" .;if($LASTEXITCODE){throw 'Runner class archive failed'}
    & "$taskSdk/build-tools/36.0.0/d8.bat" --release --min-api 29 --lib "$taskSdk/platforms/android-34/android.jar" --output "$taskStage/dex" "$taskStage/classes.jar" *> "$taskStage/d8.log";if($LASTEXITCODE){throw 'Runner dex failed'}
    & "$taskSdk/build-tools/36.0.0/aapt.exe" package -f -M "$taskStage/AndroidManifest.xml" -I "$taskSdk/platforms/android-34/android.jar" -F "$taskStage/unsigned.apk" *> "$taskStage/aapt.log";if($LASTEXITCODE){throw 'Runner manifest failed'}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $taskOld=[IO.Compression.ZipFile]::OpenRead($taskBatch.artifacts.runner.apk)
    $taskNew=[IO.Compression.ZipFile]::Open("$taskStage/unsigned.apk",[IO.Compression.ZipArchiveMode]::Update)
    try{
        $taskDexCount=0
        foreach($taskEntry in $taskOld.Entries){
            if($taskEntry.FullName -eq 'AndroidManifest.xml' -or $taskEntry.FullName -match '^META-INF/[^/]+\.(MF|SF|RSA|DSA|EC)$'){continue}
            if($taskEntry.FullName -match '^classes(\d*)\.dex$'){$taskDexCount++}
            $taskLevel=if($taskEntry.FullName -ceq 'resources.arsc'){[IO.Compression.CompressionLevel]::NoCompression}else{[IO.Compression.CompressionLevel]::Optimal}
            $taskDest=$taskNew.CreateEntry($taskEntry.FullName,$taskLevel)
            $taskInput=$taskEntry.Open();$taskOutput=$taskDest.Open();try{$taskInput.CopyTo($taskOutput)}finally{$taskInput.Dispose();$taskOutput.Dispose()}
        }
        $taskEntry=$taskNew.CreateEntry("classes$($taskDexCount+1).dex")
        $taskInput=[IO.File]::OpenRead("$taskStage/dex/classes.dex");$taskOutput=$taskEntry.Open();try{$taskInput.CopyTo($taskOutput)}finally{$taskInput.Dispose();$taskOutput.Dispose()}
    }finally{$taskOld.Dispose();$taskNew.Dispose()}
    & "$taskSdk/build-tools/36.0.0/zipalign.exe" -f 4 "$taskStage/unsigned.apk" "$taskStage/aligned.apk";if($LASTEXITCODE){throw 'Runner alignment failed'}
    & "$taskSdk/build-tools/36.0.0/zipalign.exe" -c 4 "$taskStage/aligned.apk" *> "$taskStage/alignment.log";if($LASTEXITCODE){throw 'Runner alignment verification failed'}
    $taskCheck=[IO.Compression.ZipFile]::OpenRead("$taskStage/aligned.apk")
    try{$taskResources=$taskCheck.GetEntry('resources.arsc');if($taskResources -and $taskResources.CompressedLength -ne $taskResources.Length){throw 'Android R+ requires uncompressed resources.arsc'}}finally{$taskCheck.Dispose()}
    & "$taskSdk/build-tools/36.0.0/apksigner.bat" sign --ks 'C:/Users/dieonfire/.android/debug.keystore' --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$taskStage/runner.apk" "$taskStage/aligned.apk";if($LASTEXITCODE){throw 'Runner signing failed'}
    $taskCert=(& "$taskSdk/build-tools/36.0.0/apksigner.bat" verify --print-certs "$taskStage/runner.apk" 2>&1|Out-String)
    if($LASTEXITCODE -or $taskCert -notmatch 'certificate SHA-256 digest:\s*([0-9a-f]{64})' -or $Matches[1] -cne $taskBatch.signer){throw 'Runner signer mismatch'}
    foreach($taskRow in $taskInputs){if((Get-FileHash -LiteralPath "$taskStage/$($taskRow.path)" -Algorithm SHA256).Hash -cne $taskRow.sha256){throw 'Runner source changed'}}
    foreach($taskKind in @('main','fixture')){if((Get-FileHash -LiteralPath $taskBatch.artifacts.$taskKind.apk -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskBatch.artifacts.$taskKind.sha256){throw 'App bytes changed'}}
    $taskResult.mainUnchanged=$true;$taskResult.fixtureUnchanged=$true;$taskResult.sourceStable=$true
    $taskResult.apk="$taskStage/runner.apk";$taskResult.sha256=(Get-FileHash -LiteralPath $taskResult.apk -Algorithm SHA256).Hash.ToLowerInvariant();$taskResult.state='PASS'
}catch{$taskResult.state='FAILED_PRESERVED';$taskResult.error=$_.Exception.Message;throw}
finally{$taskInputs|ConvertTo-Json|Set-Content -LiteralPath "$taskStage/source-manifest.json" -Encoding utf8;$taskResult|ConvertTo-Json -Depth 5|Set-Content -LiteralPath "$taskStage/receipt.json" -Encoding utf8;Write-Output "ANIMATION_RUNNER=$($taskResult.state);RECEIPT=$taskStage/receipt.json"}
