Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Image]::FromFile('C:\Users\AD\Downloads\IMG_20260710_103115.jpg')
# 4096x3072 -> 2:1 crop (y 240..2288), downscale to 512x256 (ad box is ~333x165 px on screen)
$crop = New-Object System.Drawing.Rectangle(0, 240, 4096, 2048)
$bmp = New-Object System.Drawing.Bitmap(512, 256)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
$dest = New-Object System.Drawing.Rectangle(0, 0, 512, 256)
$g.DrawImage($src, $dest, $crop, [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()
$out = 'D:\MC-MTR-JS\mtr-headlight\v1.20.1\src\main\resources\assets\mtrlte\advertisements\xiamen_scenery.png'
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
$src.Dispose()
Write-Output ("saved " + $out)
