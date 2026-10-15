$ErrorActionPreference='Stop'
$taskRoot='D:/MCRF/Projects/MCRF'
$taskArchive='D:/MCRF/Archive/Windows-original'
$taskStage='D:/MCRF/Builds/Windows/shared-catalogue-20261015'
if(Test-Path -LiteralPath $taskStage){throw 'Fresh snapshot required; do not duplicate existing build'}
New-Item -ItemType Directory -Path $taskStage -Force|Out-Null
foreach($taskName in @('native','wpf')){Copy-Item -LiteralPath "$taskRoot/$taskName" -Destination "$taskStage/$taskName" -Recurse}
New-Item -ItemType Directory -Path "$taskStage/work"|Out-Null
foreach($taskName in @('sing-box-lx','byedpi','webview-sdk','sshnet','qrcode','openflux','tgws','warpscout','curl','zapret')){
 $taskSource=Join-Path "$taskArchive/work" $taskName
 if(!(Test-Path -LiteralPath $taskSource -PathType Container)){throw "Missing archived dependency $taskName"}
 New-Item -ItemType Junction -Path "$taskStage/work/$taskName" -Target $taskSource|Out-Null
}
$taskInputs=Get-ChildItem "$taskStage/native","$taskStage/wpf" -Recurse -File|Get-FileHash -Algorithm SHA256
& "$taskStage/native/build.ps1" -Wpf -OutputPath "$taskStage/mcrf.exe"
if($LASTEXITCODE -ne 0){throw 'Production build failed'}
foreach($taskInput in $taskInputs){if((Get-FileHash -LiteralPath $taskInput.Path).Hash -ne $taskInput.Hash){throw 'Snapshot source changed'}}
$taskCompiler="$env:WINDIR/Microsoft.NET/Framework64/v4.0.30319/csc.exe"
& $taskCompiler /nologo /target:exe /platform:x64 ("/out:"+[IO.Path]::GetFullPath("$taskStage/catalogue-check.exe")) ("/r:"+[IO.Path]::GetFullPath("$taskStage/mcrf.exe")) /r:System.Web.Extensions.dll ([IO.Path]::GetFullPath("$taskStage/native/tests/SharedEngineCatalogCheck.cs"))
if($LASTEXITCODE -ne 0){throw 'Catalogue test compilation failed'}
& "$taskStage/catalogue-check.exe" "$taskStage/isolated-catalogue"
if($LASTEXITCODE -ne 0){throw 'Catalogue test failed'}
Write-Output 'WINDOWS_CATALOGUE_HOTFIX_BUILD_AND_TEST_PASS'
