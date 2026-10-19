$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$destination = Join-Path $PSScriptRoot '..\assets\auralis-icon.png'
$bitmap = [System.Drawing.Bitmap]::new(1024, 1024)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.Clear([System.Drawing.Color]::FromArgb(16, 17, 18))
$ring = [System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(52, 248, 211, 74), 4)
$stroke = [System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(248, 211, 74), 46)
$stroke.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$stroke.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
$graphics.DrawEllipse($ring, 160, 160, 704, 704)
$graphics.DrawEllipse($ring, 205, 205, 614, 614)
$graphics.DrawLine($stroke, 325, 720, 512, 310)
$graphics.DrawLine($stroke, 512, 310, 699, 720)
$graphics.DrawLine($stroke, 412, 545, 612, 545)
$bitmap.Save($destination, [System.Drawing.Imaging.ImageFormat]::Png)
$stroke.Dispose()
$ring.Dispose()
$graphics.Dispose()
$bitmap.Dispose()
Write-Output $destination
