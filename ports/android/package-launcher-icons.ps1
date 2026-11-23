$ErrorActionPreference='Stop'
# Deterministic resource packaging only; approved artwork and originals are unchanged.
Add-Type -AssemblyName System.Drawing
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source=Join-Path $root 'outputs/android-icon-concepts-20261015'
$selection=Get-Content (Join-Path $source 'selection.json') -Raw|ConvertFrom-Json
$target=Join-Path $PSScriptRoot 'app/src/main/res/drawable-nodpi'
New-Item -ItemType Directory -Path $target -Force|Out-Null
foreach($entry in $selection.keep){
    $inputFile=Join-Path $source $entry.file
    $original=[Drawing.Image]::FromFile($inputFile)
    try {
        if($original.Width -ne $original.Height){throw 'Launcher artwork must be square'}
        $bitmap=[Drawing.Bitmap]::new(256,256)
        try {
            $graphics=[Drawing.Graphics]::FromImage($bitmap)
            try {
                $graphics.InterpolationMode=[Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $graphics.DrawImage($original,0,0,256,256)
            }finally{$graphics.Dispose()}
            $destination=Join-Path $target ('launcher_choice_'+$entry.number+'.png')
            $bitmap.Save($destination,[Drawing.Imaging.ImageFormat]::Png)
            Get-Item -LiteralPath $destination|Select-Object Name,Length
        }finally{$bitmap.Dispose()}
    }finally{$original.Dispose()}
}
