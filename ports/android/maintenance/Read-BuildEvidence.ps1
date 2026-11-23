param(
    [Parameter(Mandatory)][ValidatePattern('^(android-integration-)?[0-9a-f]{32}$')][string]$Snapshot,
    [Parameter(Mandatory)][ValidateCount(1,32)][string[]]$Suite
)
$ErrorActionPreference='Stop'
# Read-only, exact evidence selection. Never infer process death or retry permission.
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskName='android-integration-'+($Snapshot -replace '^android-integration-','')
foreach($taskSuite in $Suite){
    if($taskSuite -cnotmatch '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$'){throw 'Exact qualified suite names required'}
}
if(@($Suite|Select-Object -Unique).Count -ne $Suite.Count){throw 'Duplicate suite names'}
$taskLocations=@((Join-Path $taskRoot "ports/android/coordination/build-history/$taskName"),(Join-Path $taskRoot "work/$taskName"))
$taskEvidence=$taskLocations|Where-Object {Test-Path -LiteralPath (Join-Path $_ 'build-result.json') -PathType Leaf}|Select-Object -First 1
if(!$taskEvidence){
    [ordered]@{snapshot=$taskName;status='NO_RECEIPT';scopePass=$false;hint='Inspect existing session/live registry; do not restart from this result.'}|ConvertTo-Json -Compress
    return
}
$taskReceipt=Get-Content -LiteralPath (Join-Path $taskEvidence 'build-result.json') -Raw|ConvertFrom-Json
if([IO.Path]::GetFileName(([string]$taskReceipt.snapshot).TrimEnd('/','\')) -cne $taskName){throw 'Receipt snapshot mismatch'}
$taskRows=foreach($taskSuite in $Suite){
    $taskXml=Join-Path $taskEvidence "ports/android/app/build/test-results/testDebugUnitTest/TEST-$taskSuite.xml"
    if(!(Test-Path -LiteralPath $taskXml -PathType Leaf)){throw "No XML for exact suite $taskSuite"}
    $taskSettings=[Xml.XmlReaderSettings]::new()
    $taskSettings.DtdProcessing=[Xml.DtdProcessing]::Prohibit
    $taskSettings.XmlResolver=$null
    $taskSettings.MaxCharactersInDocument=8388608
    $taskReader=[Xml.XmlReader]::Create($taskXml,$taskSettings)
    try {
        $taskDocument=[Xml.XmlDocument]::new();$taskDocument.XmlResolver=$null;$taskDocument.Load($taskReader)
        $taskNode=$taskDocument.DocumentElement
        if($taskNode.get_Name() -cne 'testsuite' -or $taskNode.GetAttribute('name') -cne $taskSuite){throw 'XML suite mismatch'}
        $taskRow=[ordered]@{suite=$taskSuite}
        foreach($taskKey in @('tests','failures','errors','skipped')){
            $taskValue=$taskNode.GetAttribute($taskKey)
            if($taskValue -cnotmatch '^[0-9]{1,7}$'){throw 'Invalid test counter'}
            $taskRow[$taskKey]=[int]$taskValue
        }
        [pscustomobject]$taskRow
    }finally{$taskReader.Dispose()}
}
$taskPass=($taskReceipt.exitCode -is [long] -or $taskReceipt.exitCode -is [int]) -and $taskReceipt.exitCode -eq 0 -and
    $taskReceipt.sourceStable -is [bool] -and $taskReceipt.sourceStable -eq $true -and
    @($taskRows|Where-Object {$_.tests -le 0 -or $_.failures -ne 0 -or $_.errors -ne 0 -or $_.skipped -ne 0}).Count -eq 0
[ordered]@{snapshot=$taskName;exit=$taskReceipt.exitCode;sourceStable=$taskReceipt.sourceStable;scopePass=$taskPass;suites=@($taskRows);limit='Selected JVM scope only; not APK/device/native/project acceptance.'}|ConvertTo-Json -Depth 4 -Compress
