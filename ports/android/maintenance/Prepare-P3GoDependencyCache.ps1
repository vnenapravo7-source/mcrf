param([Parameter(Mandatory)][string]$SourceStage)
$ErrorActionPreference='Stop'
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation native-build
$taskStage=(Resolve-Path -LiteralPath $SourceStage).Path.Replace([char]92,[char]47)
if($taskStage -cnotmatch '^D:/MCRF/Builds/Android/[A-Za-z0-9_.-]+$'){throw 'Exact owned source stage required'}
$taskSource=Get-Content "$taskStage/receipt.json" -Raw|ConvertFrom-Json
$taskModule=$taskSource.module
if(!$taskModule -or !(Test-Path -LiteralPath "$taskModule/go.mod")){return}
$taskModule=[IO.Path]::GetFullPath($taskModule).Replace([char]92,[char]47)
if(!$taskModule.StartsWith($taskStage+'/',[StringComparison]::OrdinalIgnoreCase)){throw 'Dependency intake must belong to its frozen source stage'}
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
$taskOriginal=Hash "$taskModule/go.mod"
$taskSum=if(Test-Path "$taskModule/go.sum"){Hash "$taskModule/go.sum"}else{$null}
$taskIntake="D:/MCRF/Builds/Android/p3-go-dependency-intake-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $taskIntake|Out-Null
Copy-Item -LiteralPath "$taskModule/go.mod" -Destination "$taskIntake/go.mod"
if($taskSum){Copy-Item -LiteralPath "$taskModule/go.sum" -Destination "$taskIntake/go.sum"}
foreach($name in @('go.mod','go.sum')){if(Test-Path "$taskIntake/$name"){Set-ItemProperty -LiteralPath "$taskIntake/$name" -Name IsReadOnly -Value $false}}
$taskVars=@('GOPATH','GOMODCACHE','GOPROXY','GOTOOLCHAIN','GOENV','GOWORK','GOFLAGS');$taskOld=@{}
foreach($name in $taskVars){$taskOld[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$proof=@{state='DEPENDENCY_INTAKE_RUNNING';source=$taskStage;originalGoModSha256=$taskOriginal;originalGoSumSha256=$taskSum;compiled=$false;testsExecuted=$false}
try {
 $env:GOPATH='D:/MCRF/Projects/MCRF/work/toolchains/go-home';$env:GOMODCACHE="$env:GOPATH/pkg/mod";$env:GOPROXY='https://proxy.golang.org';$env:GOTOOLCHAIN='local';$env:GOENV='off';$env:GOWORK='off';$env:GOFLAGS=''
 Push-Location $taskModule
 try{& 'D:/MCRF/Projects/MCRF/work/toolchains/go1.26.8/go/bin/go.exe' mod download "-modfile=$taskIntake/go.mod" all *> "$taskIntake/download.log";if($LASTEXITCODE){throw 'Full dependency graph intake failed; preserve download log'}}finally{Pop-Location}
 if((Hash "$taskModule/go.mod") -cne $taskOriginal -or $taskSum -and (Hash "$taskModule/go.sum") -cne $taskSum){throw 'Frozen module changed during dependency intake'}
 $proof.state='DEPENDENCIES_CACHED_NO_COMPILATION';$proof.downloadLogSha256=Hash "$taskIntake/download.log"
}catch{$proof.state='DEPENDENCY_INTAKE_FAILED_PRESERVED';$proof.failure=$_.Exception.Message;throw}
finally{foreach($name in $taskVars){[Environment]::SetEnvironmentVariable($name,$taskOld[$name],'Process')};[IO.File]::WriteAllText("$taskIntake/receipt.json",($proof|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false));Write-Output "P3_GO_DEPENDENCY_INTAKE=$taskIntake"}
