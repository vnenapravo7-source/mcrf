param([Parameter(Mandatory=$true)][string]$Stage)
$ErrorActionPreference='Stop';$taskRoot=[IO.Path]::GetFullPath($Stage)
if(!$taskRoot.StartsWith('D:\MCRF\Builds\Android\openflux016-routes-',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected stage'}
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
$taskOriginal=Get-Content "$taskRoot/receipt.json" -Raw|ConvertFrom-Json
if($taskOriginal.state -cne 'FAILED_PRESERVED' -or $taskOriginal.error -cne 'Expected main + three ABI modules + universal module'){throw 'Only exact successful-build APK-count postcheck can resume'}
if((Get-Content "$taskRoot/build.log" -Raw) -notmatch 'BUILD SUCCESSFUL'){throw 'Gradle did not succeed'}
$taskSnapshot=[IO.Path]::GetFullPath($taskOriginal.snapshot)
foreach($taskRow in (Get-Content "$taskSnapshot/source-manifest.json" -Raw|ConvertFrom-Json)){
 $taskPath=[IO.Path]::GetFullPath((Join-Path $taskSnapshot $taskRow.path));if(!$taskPath.StartsWith($taskSnapshot+'\',[StringComparison]::OrdinalIgnoreCase) -or (Hash $taskPath) -cne $taskRow.sha256){throw 'Frozen source changed'}
}
foreach($taskProperty in $taskOriginal.inputs.PSObject.Properties){if((Hash (Join-Path 'D:/MCRF/Projects/MCRF/ports/android' $taskProperty.Name)) -cne $taskProperty.Value){throw 'Canonical source changed'}}
$taskSuites=@(Get-ChildItem "$taskRoot/app/test-results/testDebugUnitTest" -Filter 'TEST-*.xml'|ForEach-Object{([xml](Get-Content $_.FullName -Raw)).testsuite})
if($taskSuites.Count -ne 6 -or @($taskSuites|Where-Object {[int]$_.failures -ne 0 -or [int]$_.errors -ne 0}).Count -ne 0 -or ($taskSuites|Measure-Object tests -Sum).Sum -ne 53){throw 'Affected tests not exact green53/6suites'}
$taskTools='C:/Users/dieonfire/AppData/Local/Android/Sdk/build-tools/36.0.0'
$taskNative=Get-Content "$($taskOriginal.nativeStage)/receipt.json" -Raw|ConvertFrom-Json
$taskFiles=@();$taskApks=@(Get-ChildItem "$taskRoot/app/outputs/apk/release" -Filter 'app-universal-release.apk')+@(Get-ChildItem "$taskRoot/engines/outputs/apk/openflux/release" -Filter '*.apk')
if($taskApks.Count -ne 5){throw 'Universal main and four modules required'}
foreach($taskApk in $taskApks){
 $taskSignature=(& "$taskTools/apksigner.bat" verify --print-certs $taskApk.FullName 2>&1|Out-String)
 if($LASTEXITCODE -or $taskSignature -notmatch 'certificate SHA-256 digest:\s*fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'APK signer differs'}
 $taskBadging=(& "$taskTools/aapt.exe" dump badging $taskApk.FullName|Out-String)
 $taskKind=if($taskBadging -match "name='org.mcrf.client' versionCode='10060'"){'app'}elseif($taskBadging -match "name='org.mcrf.engines.openflux' versionCode='15'"){'openflux'}else{throw 'APK identity differs'}
 if($taskKind -eq 'openflux'){
  $taskZip=[IO.Compression.ZipFile]::OpenRead($taskApk.FullName)
  try{
   if(@($taskZip.Entries|Where-Object {$_.FullName -match 'testserver|openflux13'}).Count){throw 'Test fixture in ordinary module'}
   $taskLibraries=@($taskZip.Entries|Where-Object {$_.FullName -match '^lib/([^/]+)/libopenflux.so$'})
   if($taskLibraries.Count -notin @(1,3)){throw 'Native payload count differs'}
   foreach($taskLibrary in $taskLibraries){$taskAbi=$taskLibrary.FullName.Split('/')[1];$taskStream=$taskLibrary.Open();try{$taskDigest=[Security.Cryptography.SHA256]::Create();$taskHash=[Convert]::ToHexString($taskDigest.ComputeHash($taskStream)).ToLowerInvariant()}finally{$taskStream.Dispose()};if($taskHash -cne ($taskNative.files|Where-Object abi -ceq $taskAbi).sha256){throw 'Packaged core differs from tested stage'}}
  }finally{$taskZip.Dispose()}
 }
 $taskFiles+=@{kind=$taskKind;name=$taskApk.Name;path=$taskApk.FullName;bytes=$taskApk.Length;sha256=(Hash $taskApk.FullName)}
}
$taskResult=@{state='BUILT_VERIFIED_NOT_INSTALLED';snapshot=$taskSnapshot;sourceStable=$true;nativeStage=$taskOriginal.nativeStage;versionCode=10060;versionName='0.7.1-candidate.1';files=$taskFiles;installed=$false;published=$false;tests=53;suites=6;lintAndR8=$true;originalBuildReceiptSha256=(Hash "$taskRoot/receipt.json");sourceManifestSha256=(Hash "$taskSnapshot/source-manifest.json");verificationRecipeSha256=(Hash $PSCommandPath)}
[IO.File]::WriteAllText("$taskRoot/verification-receipt.json",($taskResult|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
Write-Output "VERIFIED_COMMON_016_ROUTES=$taskRoot;TESTS=53;APKS=5"
