param([string]$JavaHome='C:/Program Files/Java/jdk-17')
$ErrorActionPreference='Stop'
$taskAndroid=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if($args.Count){throw 'Unknown core-policy argument'}
& (Join-Path $taskAndroid 'maintenance/Assert-HumanBatchReady.ps1') -Operation test
$taskOutput=Join-Path $PSScriptRoot 'audit/policy-classes'
New-Item -ItemType Directory -Path $taskOutput -Force|Out-Null
& (Join-Path $JavaHome 'bin/javac.exe') -d $taskOutput (Join-Path $taskAndroid 'app/src/main/java/org/mcrf/client/ClientPrimaryCorePolicy.java') (Join-Path $PSScriptRoot 'tests/ClientPrimaryCorePolicyChecks.java')
if($LASTEXITCODE -ne 0){throw 'Primary core policy compilation failed'}
& (Join-Path $JavaHome 'bin/java.exe') -classpath $taskOutput org.mcrf.client.ClientPrimaryCorePolicyChecks
if($LASTEXITCODE -ne 0){throw 'Primary core policy checks failed'}
