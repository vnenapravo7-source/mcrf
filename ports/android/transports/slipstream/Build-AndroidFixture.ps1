param([Parameter(Mandatory)][string]$NativeStage,[Parameter(Mandatory)][string]$PreparedSource)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/../../maintenance/Assert-HumanBatchReady.ps1" -Operation native-build
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 16),[Text.UTF8Encoding]::new($false))}
if((Resolve-Path $NativeStage).Path.Replace([char]92,[char]47) -cnotmatch '^D:/MCRF/Builds/Android/p3-slipstream-native-[a-f0-9]{32}$'){throw 'Exact native stage required'}
$taskProof=Get-Content "$NativeStage/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
if($taskProof.state -cne 'FIXTURE_FAILED_PRESERVED' -or $taskProof.androidFixture -or $taskProof.sourceManifestSha256 -cne (Hash "$PreparedSource/SOURCE_MANIFEST.json")){throw 'Exact failed original fixture required'}
$taskStage="$NativeStage/android-fixture";if(Test-Path $taskStage){throw 'Preserve previous fixture compiler attempt'}
New-Item -ItemType Directory -Path "$taskStage/bridge","$taskStage/tmp"|Out-Null
$taskRows=@()
foreach($file in Get-ChildItem "$PreparedSource/bridge" -File){Copy-Item -LiteralPath $file.FullName -Destination "$taskStage/bridge/$($file.Name)";$taskRows+=@{file=$file.Name;sha256=Hash $file.FullName}}
foreach($name in @('process_linux_test.go','wire_linux_test.go')){
 $taskBefore=[IO.File]::ReadAllText("$taskStage/bridge/$name").Replace("`r`n","`n")
 $taskAfter=[IO.File]::ReadAllText("$PSScriptRoot/bridge/$name").Replace("`r`n","`n")
 if($name -ceq 'process_linux_test.go'){
  $pattern='(?ms)^func ownedCore\(.*?(?=^func waitCoreStopped\()'
  if([regex]::Matches($taskBefore,$pattern).Count -ne 1 -or [regex]::Matches($taskAfter,$pattern).Count -ne 1 -or [regex]::Replace($taskBefore,$pattern,'') -cne [regex]::Replace($taskAfter,$pattern,'')){throw 'Only owned-PID discovery may change'}
 }elseif($taskBefore.Replace('t.Fatal("owned CONNECT")','t.Fatalf("owned CONNECT: error=%v reply=%x",err,connected)') -cne $taskAfter){throw 'Only CONNECT failure diagnostics may change'}
 Copy-Item -LiteralPath "$PSScriptRoot/bridge/$name" -Destination "$taskStage/bridge/$name" -Force
}
Save @{original=$taskRows;derived=@(Get-ChildItem "$taskStage/bridge" -File|ForEach-Object {@{file=$_.Name;sha256=Hash $_.FullName}});scope='owned child PID discovery Android kernel fallback; failure-only CONNECT details; assertions and transport unchanged'} "$taskStage/source-manifest.json"
$taskKeys=@('PATH','GOOS','GOARCH','CGO_ENABLED','CC','GOROOT','GOTOOLCHAIN','GOENV','GOWORK','GOFLAGS','GOPROXY','GOPATH','GOMODCACHE','GOCACHE','GOTMPDIR','TEMP','TMP');$taskOld=@{}
foreach($key in $taskKeys){$taskOld[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
try{
 $taskTools='C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin'
 $env:PATH="$taskTools;"+$taskOld.PATH;$env:GOOS='android';$env:GOARCH='amd64';$env:CGO_ENABLED='1';$env:CC="$taskTools/clang.exe --target=x86_64-linux-android29";$env:GOTOOLCHAIN='local';$env:GOENV='off';$env:GOWORK='off';$env:GOFLAGS='';$env:GOPROXY='off';[Environment]::SetEnvironmentVariable('GOROOT',$null,'Process')
 $env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-home';$env:GOMODCACHE="$env:GOPATH/pkg/mod";$env:GOCACHE='D:/MCRF/Projects/MCRF/work/toolchains/go-build-cache';$env:GOTMPDIR="$taskStage/tmp";$env:TEMP=$env:GOTMPDIR;$env:TMP=$env:GOTMPDIR
 $taskOutput="$NativeStage/fixtures/libslipstream_fixture_android.so"
 Push-Location "$taskStage/bridge"
 try{& D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/bin/go.exe test -c -mod=readonly -buildmode=pie '-ldflags=-linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o $taskOutput . *> "$taskStage/compile.log";if($LASTEXITCODE){throw "Android fixture compilation failed: $LASTEXITCODE"}}finally{Pop-Location}
 $taskLoads=@(& "$taskTools/llvm-readelf.exe" --program-headers --wide $taskOutput|Where-Object {$_ -match '^\s*LOAD\s'});if($LASTEXITCODE -or !$taskLoads.Count){throw 'ELF LOAD checks required'}
 foreach($load in $taskLoads){if($load -notmatch '\s0x([0-9a-fA-F]+)\s*$' -or [Convert]::ToUInt64($Matches[1],16) -lt 16384){throw 'Fixture ELF alignment failed'}}
 foreach($row in @($taskProof.artifacts)+@($taskProof.fixtureArtifacts)){if((Hash "$NativeStage/$($row.file)") -cne $row.sha256){throw 'Original native bytes changed'}}
 $taskProof.androidFixture=@{file='fixtures/libslipstream_fixture_android.so';sha256=Hash $taskOutput;bytes=(Get-Item $taskOutput).Length;elfChecked=$true;sourceManifestSha256=Hash "$taskStage/source-manifest.json"}
 $taskProof.fixtureArtifacts+=@($taskProof.androidFixture);$taskProof.state='COMPILED_FIXTURE_PENDING'
 Save $taskProof "$NativeStage/receipt.json"
}finally{foreach($key in $taskKeys){[Environment]::SetEnvironmentVariable($key,$taskOld[$key],'Process')}}
Write-Output "P3_SLIPSTREAM_ANDROID_FIXTURE=$NativeStage"
