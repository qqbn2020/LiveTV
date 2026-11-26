Add-Type -AssemblyName System.Drawing

$res = 'C:\dev\ai\tv-live\LiveTV\app\src\main\res'

function RoundedPath([single]$x, [single]$y, [single]$w, [single]$h, [single]$r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $p.AddArc($x, $y, $d, $d, 180, 90)
    $p.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $p.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $p.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    return $p
}

function NewIcon([string]$file, [int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $rect = New-Object System.Drawing.Rectangle(0, 0, $size, $size)
    $bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, [System.Drawing.Color]::FromArgb(9, 15, 26), [System.Drawing.Color]::FromArgb(12, 32, 56), 45)
    $g.FillRectangle($bg, $rect)

    $tv = RoundedPath ([single]($size * 0.15)) ([single]($size * 0.26)) ([single]($size * 0.70)) ([single]($size * 0.42)) ([single]($size * 0.06))
    $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(61, 165, 255), [single]($size * 0.042))
    $g.DrawPath($pen, $tv)

    $brush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(240, 245, 255))
    $pts = @(
        (New-Object System.Drawing.PointF([single]($size * 0.42), [single]($size * 0.36))),
        (New-Object System.Drawing.PointF([single]($size * 0.42), [single]($size * 0.58))),
        (New-Object System.Drawing.PointF([single]($size * 0.63), [single]($size * 0.47)))
    )
    $g.FillPolygon($brush, $pts)

    $stand = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(61, 165, 255))
    $g.FillRectangle($stand, [single]($size * 0.40), [single]($size * 0.74), [single]($size * 0.20), [single]($size * 0.035))
    $g.FillRectangle($stand, [single]($size * 0.30), [single]($size * 0.78), [single]($size * 0.40), [single]($size * 0.02))

    $bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose()
    $bmp.Dispose()
}

$sizes = @{
    'mipmap-mdpi'    = 48
    'mipmap-hdpi'    = 72
    'mipmap-xhdpi'   = 96
    'mipmap-xxhdpi'  = 144
    'mipmap-xxxhdpi' = 192
}
foreach ($key in $sizes.Keys) {
    $dir = Join-Path $res $key
    if (!(Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    NewIcon (Join-Path $dir 'ic_launcher.png') $sizes[$key]
}

# banner 640x360 (320x180dp @2x)
$w = 640; $h = 360
$bmp = New-Object System.Drawing.Bitmap($w, $h)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAlias
$rect = New-Object System.Drawing.Rectangle(0, 0, $w, $h)
$bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, [System.Drawing.Color]::FromArgb(6, 8, 12), [System.Drawing.Color]::FromArgb(13, 27, 46), 20)
$g.FillRectangle($bg, $rect)

# screen frame
$tv = RoundedPath ([single]($w * 0.07)) ([single]($h * 0.30)) ([single]($w * 0.22)) ([single]($h * 0.42)) ([single]14)
$pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(61, 165, 255), [single]3)
$g.DrawPath($pen, $tv)
$white = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(240, 245, 255))
$pts = @(
    (New-Object System.Drawing.PointF([single]($w * 0.16), [single]($h * 0.40))),
    (New-Object System.Drawing.PointF([single]($w * 0.16), [single]($h * 0.62))),
    (New-Object System.Drawing.PointF([single]($w * 0.25), [single]($h * 0.51)))
)
$g.FillPolygon($white, $pts)

$titleFont = New-Object System.Drawing.Font('Microsoft YaHei UI', [single]33, [System.Drawing.FontStyle]::Bold)
$subFont = New-Object System.Drawing.Font('Microsoft YaHei UI', [single]11)
$titleBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(240, 245, 255))
$subBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(160, 180, 200))
$g.DrawString('LiveTV 直播', $titleFont, $titleBrush, [single]($w * 0.36), [single]($h * 0.28))
$g.DrawString('电视直播 · m3u / txt / xml / json 直播源', $subFont, $subBrush, [single]($w * 0.37), [single]($h * 0.56))

$bmp.Save((Join-Path $res 'drawable\banner.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmp.Dispose()

Write-Output 'assets done'
