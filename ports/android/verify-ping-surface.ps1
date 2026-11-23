param(
    [string]$Serial='R5CX73QYN3D',
    [string]$Adb="$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe",
    [int]$Pages=4,
    [string]$OutputPrefix='ping-surface'
)
$ErrorActionPreference='Stop'
$taskConsoleEncoding=[Console]::OutputEncoding
[Console]::OutputEncoding=New-Object Text.UTF8Encoding($false)
if($Pages -lt 1 -or $Pages -gt 8){throw 'Pages must be 1–8'}
if($OutputPrefix -notmatch '^[a-z0-9-]{1,48}$'){throw 'Invalid output prefix'}
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskOutput=Join-Path $taskRoot 'work/ports-test-2026-10-14'
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
Add-Type -AssemblyName System.Drawing
function Task-Adb([string[]]$Arguments){
    $taskPrevious=$ErrorActionPreference
    try{$ErrorActionPreference='Continue';$taskValue=(& $Adb -s $Serial @Arguments 2>&1 | Out-String);$taskExit=$LASTEXITCODE}finally{$ErrorActionPreference=$taskPrevious}
    if($taskExit -ne 0){throw "ADB action failed: $($Arguments[0])"}
    return $taskValue
}
function Task-Focus {
    $taskFocus=Task-Adb @('shell','dumpsys','window')
    if($taskFocus -notmatch 'mCurrentFocus=Window\{[^\r\n]*org\.mcrf\.client/org\.mcrf\.client\.MainActivity'){throw 'Leave the unlocked phone on the actual MCRF window'}
}
function Task-Bounds([string]$Value){
    if($Value -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$'){throw 'Invalid accessibility bounds'}
    return @([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4])
}
function Task-Tree {
    Task-Focus
    $null=Task-Adb @('shell','uiautomator','dump','/data/local/tmp/mcrf-ping-surface.xml')
    return [xml](Task-Adb @('shell','cat','/data/local/tmp/mcrf-ping-surface.xml'))
}
function Task-Scroll($Tree){
    Task-Focus
    $taskScroll=$Tree.SelectSingleNode("//node[@scrollable='true']")
    if(!$taskScroll){throw 'Actual scroll container not found'}
    $taskRect=Task-Bounds $taskScroll.bounds
    $taskX=[string][int](($taskRect[0]+$taskRect[2])/2)
    $taskSwipeStart=$taskRect[3]-100
    $taskSwipeEnd=$taskSwipeStart-[int](($taskRect[3]-$taskRect[1])*.4)
    $null=Task-Adb @('shell','input','swipe',$taskX,[string]$taskSwipeStart,$taskX,[string]$taskSwipeEnd,'1200')
    Start-Sleep -Milliseconds 450
}
$taskSeen=@{};$taskSamples=0
try {
    $taskDensityText=Task-Adb @('shell','wm','density')
    $taskDensities=[regex]::Matches($taskDensityText,'(?:Physical|Override) density: (\d+)')
    if(!$taskDensities.Count){throw 'Device display density unavailable'}
    $taskMinimumPingHeight=[int][Math]::Ceiling(32*[int]$taskDensities[$taskDensities.Count-1].Groups[1].Value/160.0)
    $taskTree=Task-Tree
    $taskNav=$taskTree.SelectSingleNode("//node[@content-desc='Главная']")
    if(!$taskNav){throw 'MCRF home navigation not found'}
    $taskRect=Task-Bounds $taskNav.bounds
    $null=Task-Adb @('shell','input','tap',[string][int](($taskRect[0]+$taskRect[2])/2),[string][int](($taskRect[1]+$taskRect[3])/2))
    Start-Sleep -Milliseconds 450
    for($taskPage=1;$taskPage -le $Pages;$taskPage++){
        $taskTree=Task-Tree
        $taskPings=@($taskTree.SelectNodes("//node[starts-with(@content-desc,'Задержка ') or @content-desc='Сервер не отвечает']"))
        for($taskAttempt=0;!$taskPings.Count -and $taskAttempt -lt 9;$taskAttempt++){
            Task-Scroll $taskTree
            $taskTree=Task-Tree
            $taskPings=@($taskTree.SelectNodes("//node[starts-with(@content-desc,'Задержка ') or @content-desc='Сервер не отвечает']"))
        }
        if(!$taskPings.Count){throw 'No visible ping values after reaching the server list'}
        $taskImagePath=Join-Path $taskOutput ($OutputPrefix+("-{0:d2}.png" -f $taskPage))
        Task-Focus
        $null=Task-Adb @('shell','screencap','-p','/data/local/tmp/mcrf-ping-surface.png')
        $null=Task-Adb @('pull','/data/local/tmp/mcrf-ping-surface.png',$taskImagePath)
        $taskBitmap=[Drawing.Bitmap]::FromFile($taskImagePath)
        try {
            foreach($taskNode in $taskPings){
                $taskText=[string]$taskNode.text
                if($taskText -ne 'Нет связи' -and $taskText -notmatch '^\d+ мс$'){continue}
                $taskRect=Task-Bounds $taskNode.bounds
                # Accessibility clips bounds at the viewport edge. Do not mistake
                # a label whose actual baseline is outside the viewport for missing rendering.
                if($taskRect[2]-$taskRect[0] -lt 40 -or $taskRect[3]-$taskRect[1] -lt $taskMinimumPingHeight){continue}
                # Either theme is accepted, but only colours for this actual measured value.
                $taskColors=if($taskText -eq 'Нет связи'){@(0xff999f,0xa72e36)}elseif([string]$taskNode.'content-desc' -like '*хорошая'){@(0x80dcb1,0x156749)}else{@(0xe5be6c,0x84580a)}
                $taskPixels=0
                for($taskY=$taskRect[1];$taskY -lt [Math]::Min($taskRect[3],$taskBitmap.Height);$taskY++){
                    for($taskX=$taskRect[0];$taskX -lt [Math]::Min($taskRect[2],$taskBitmap.Width);$taskX++){
                        $taskPixel=$taskBitmap.GetPixel($taskX,$taskY)
                        foreach($taskColor in $taskColors){
                            if([Math]::Abs($taskPixel.R-(($taskColor -shr 16) -band 255)) -lt 12 -and [Math]::Abs($taskPixel.G-(($taskColor -shr 8) -band 255)) -lt 12 -and [Math]::Abs($taskPixel.B-($taskColor -band 255)) -lt 12){$taskPixels++;break}
                        }
                    }
                }
                if($taskPixels -lt 5){throw "Actual hardware frame has no visible ping text: $taskText, page $taskPage"}
                $taskParent=$taskNode.ParentNode
                while($taskParent -and (!$taskParent.'content-desc' -or $taskParent.'content-desc' -eq $taskNode.'content-desc')){$taskParent=$taskParent.ParentNode}
                $taskIdentity=[string]$taskParent.'content-desc'
                if(!$taskIdentity){$taskIdentity=$taskText}
                $taskHasher=[Security.Cryptography.SHA256]::Create()
                try{$taskKey=[BitConverter]::ToString($taskHasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($taskIdentity)))}finally{$taskHasher.Dispose()}
                $taskSeen[$taskKey]=$true;$taskSamples++
                Write-Output "PASS surface page=$taskPage value=$taskText colouredPixels=$taskPixels"
            }
        }finally{$taskBitmap.Dispose()}
        if($taskPage -lt $Pages){
            Task-Scroll $taskTree
        }
    }
    if(!$taskSamples){throw 'No real hardware ping labels were inspected'}
    Write-Output "HARDWARE PASS samples=$taskSamples distinctRows=$($taskSeen.Count)"
}finally{
    # Only exact disposable files created by this verifier, never application/profile data.
    $null=Task-Adb @('shell','rm','-f','/data/local/tmp/mcrf-ping-surface.xml','/data/local/tmp/mcrf-ping-surface.png')
    [Console]::OutputEncoding=$taskConsoleEncoding
}
