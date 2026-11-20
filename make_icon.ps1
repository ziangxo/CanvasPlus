$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

# CanvasPlus 应用图标生成器（2026-11-21 v3）
#
# 构图：画布的三分屏放大态——右下一个竖屏大窗铺满画布，左边和上边各露出被压住的窗口边条。
#
# 尺寸这块之前一直踩坑，这里记清楚：
#   自适应图标的分层画布是 108dp，系统只显示**中间 72dp**（四周各裁掉 18dp = 16.7%）。
#   所以"看起来顶到边"往往不是画得大，而是把内容画到了 108dp 画布的外圈、被裁掉了。
#   用户要求四周留约 10dp：72 - 20 = 52dp，即内容高度取 108dp 的 48%，
#   画在 432px 前景图上就是 207px 高、水平居中（宽按 0.72 的构图比例）。
#   常规 mipmap（桌面会整块显示）按"内容占 72%、四周各留 14%"画，观感与上面一致。
#
# 画完必须自己看一眼：脚本末尾会把「系统实际裁切后的样子」渲染成
# icon_src/preview_home.png（72/108 裁切 + 圆角），那才是桌面上的效果。

$root = 'c:\Users\ziang\CodeBuddy\20261007161444\canvasplus'
$res = "$root\app\src\main\res"
$iconSize = 1024

$bgTop = [System.Drawing.Color]::FromArgb(26, 20, 60)
$bgBottom = [System.Drawing.Color]::FromArgb(66, 27, 118)
$cardTop = [System.Drawing.Color]::FromArgb(253, 254, 255)
$cardBottom = [System.Drawing.Color]::FromArgb(226, 229, 246)
$backFill = [System.Drawing.Color]::FromArgb(110, 255, 255, 255)
$backStroke = [System.Drawing.Color]::FromArgb(140, 255, 255, 255)
$stroke = [System.Drawing.Color]::FromArgb(120, 255, 255, 255)

function New-RoundedPath([single]$x, [single]$y, [single]$w, [single]$h, [single]$r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    if ($d -gt $w) { $d = $w }
    if ($d -gt $h) { $d = $h }
    $r = $d / 2
    $p.AddArc($x, $y, $d, $d, 180, 90)
    $p.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $p.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $p.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    return $p
}

function Add-Shadow($g, [single]$x, [single]$y, [single]$w, [single]$h, [single]$r, [single]$unit) {
    for ($i = 9; $i -ge 1; $i--) {
        $grow = $i * $unit * 0.22
        $p = New-RoundedPath ($x - $grow) ($y - $grow + $unit * 0.5) ($w + $grow * 2) ($h + $grow * 2) ($r + $grow)
        $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(7, 8, 4, 24), [single]($unit * 0.5))
        $g.DrawPath($pen, $p)
        $pen.Dispose(); $p.Dispose()
    }
}

# 在给定矩形里画三块窗口。artW/artH 就是"构图区域"，内部全部按它的比例算，
# 这样同一套坐标能同时用于 432px 前景图、1024px 母版和预览图。
function Draw-Composition($g, [single]$ax, [single]$ay, [single]$artW, [single]$artH) {
    $unit = [Math]::Min($artW, $artH)
    # 大窗从 artW/artH 的 (0.13,0.08) 收到 (0.20,0.14)，宽高随之缩到 0.80/0.86；
    # 后面两个窗的露出量因此从 13%/8% 增到 20%/14%（用户 2026-11-21 要求
    # "大窗口按比例缩小一点、另外两个屏幕按比例放大一点"）。
    $topX = $ax + $artW * 0.12
    $topY = $ay
    $topW = $artW * 0.88
    $topH = $artH * 0.34
    $topR = $unit * 0.09

    $leftX = $ax
    $leftY = $ay + $artH * 0.04
    $leftW = $artW * 0.50
    $leftH = $artH * 0.92
    $leftR = $unit * 0.09

    $frontX = $ax + $artW * 0.20
    $frontY = $ay + $artH * 0.14
    $frontW = $artW * 0.80
    $frontH = $artH * 0.86
    $frontR = $unit * 0.10

    # 后面的两层窗
    foreach ($card in @(
            @($topX, $topY, $topW, $topH, $topR),
            @($leftX, $leftY, $leftW, $leftH, $leftR))) {
        $p = New-RoundedPath $card[0] $card[1] $card[2] $card[3] $card[4]
        $brush = New-Object System.Drawing.SolidBrush($backFill)
        $g.FillPath($brush, $p)
        $brush.Dispose()
        $pen = New-Object System.Drawing.Pen($backStroke, [single]($unit * 0.01))
        $g.DrawPath($pen, $p)
        $pen.Dispose(); $p.Dispose()
    }

    # 大窗
    Add-Shadow $g $frontX $frontY $frontW $frontH $frontR $unit
    $fPath = New-RoundedPath $frontX $frontY $frontW $frontH $frontR
    $fRect = New-Object System.Drawing.RectangleF($frontX, $frontY, $frontW, $frontH)
    $fBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush($fRect, $cardTop, $cardBottom, 90.0)
    $g.FillPath($fBrush, $fPath)
    $fBrush.Dispose()

    # 屏幕内容：顶部状态条 + 3×4 键位（最后一颗橙色）
    $inset = $frontW * 0.09
    $barW = $frontW * 0.30
    $barH = [single]($frontH * 0.016)
    $barP = New-RoundedPath ($frontX + ($frontW - $barW) / 2) ($frontY + $inset) $barW $barH ($barH / 2)
    $barBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(55, 92, 98, 132))
    $g.FillPath($barBrush, $barP)
    $barBrush.Dispose(); $barP.Dispose()

    $cols = 3
    $rows = 4
    $gridTop = $frontY + $frontH * 0.44
    $gridBottom = $frontY + $frontH * 0.90
    $cellW = ($frontW - $inset * 2) / $cols
    $cellH = ($gridBottom - $gridTop) / $rows
    $dotR = [single]([Math]::Min($cellW, $cellH) * 0.30)
    for ($r = 0; $r -lt $rows; $r++) {
        for ($c = 0; $c -lt $cols; $c++) {
            $cx = $frontX + $inset + $cellW * ($c + 0.5)
            $cy = $gridTop + $cellH * ($r + 0.5)
            $accent = ($r -eq $rows - 1) -and ($c -eq $cols - 1)
            $color = if ($accent) {
                [System.Drawing.Color]::FromArgb(235, 240, 112, 60)
            } else {
                [System.Drawing.Color]::FromArgb(26, 40, 48, 92)
            }
            $dot = New-Object System.Drawing.SolidBrush($color)
            $g.FillEllipse($dot, [single]($cx - $dotR), [single]($cy - $dotR),
                [single]($dotR * 2), [single]($dotR * 2))
            $dot.Dispose()
        }
    }

    $penSt = New-Object System.Drawing.Pen($stroke, [single]($unit * 0.008))
    $g.DrawPath($penSt, $fPath)
    $penSt.Dispose()
    $fPath.Dispose()
}

function New-Background([int]$px) {
    $bmp = New-Object System.Drawing.Bitmap($px, $px, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $rect = New-Object System.Drawing.RectangleF(0, 0, $px, $px)
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, $bgTop, $bgBottom, 60.0)
    $g.FillRectangle($brush, 0, 0, $px, $px)
    $brush.Dispose()
    $glow = New-Object System.Drawing.Drawing2D.GraphicsPath
    $glow.AddEllipse([single](-$px * 0.3), [single](-$px * 0.4), [single]($px * 1.2), [single]($px * 1.2))
    $gb = New-Object System.Drawing.Drawing2D.PathGradientBrush($glow)
    $gb.CenterColor = [System.Drawing.Color]::FromArgb(30, 255, 255, 255)
    $gb.SurroundColors = @([System.Drawing.Color]::FromArgb(0, 255, 255, 255))
    $g.FillPath($gb, $glow)
    $gb.Dispose(); $glow.Dispose()
    $g.Dispose()
    return $bmp
}

# ---------------- 1. 常规图标母版 ----------------
# 桌面会整块显示这张图，内容占 72%（四周各 14%）
$legacyH = [single]($iconSize * 0.72)
$legacyW = [single]($legacyH * 0.72)
$legacy = New-Background $iconSize
$lg = [System.Drawing.Graphics]::FromImage($legacy)
$lg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$lg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
Draw-Composition $lg ([single](($iconSize - $legacyW) / 2)) ([single](($iconSize - $legacyH) / 2)) $legacyW $legacyH
$lg.Dispose()

$masterDir = "$root\icon_src"
New-Item -ItemType Directory -Force -Path $masterDir | Out-Null
$legacy.Save("$masterDir\master_legacy.png", [System.Drawing.Imaging.ImageFormat]::Png)

# ---------------- 2. 自适应图标：背景层 + 前景层 ----------------
# 前景层是 108dp 画布，系统只显示中间 72dp，所以内容高度取 48%（= 72dp 里的 72%），
# 这样在桌面上看四周正好留 10dp 左右。
$fgFull = 432
$fgH = [single]($fgFull * 0.48)
$fgW = [single]($fgH * 0.72)

$fgLayer = New-Object System.Drawing.Bitmap($fgFull, $fgFull, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$fgG = [System.Drawing.Graphics]::FromImage($fgLayer)
$fgG.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
Draw-Composition $fgG ([single](($fgFull - $fgW) / 2)) ([single](($fgFull - $fgH) / 2)) $fgW $fgH
$fgG.Dispose()
$fgLayer.Save("$res\drawable-nodpi\ic_launcher_fg.png", [System.Drawing.Imaging.ImageFormat]::Png)
$fgLayer.Dispose()

$bgLayer = New-Background $fgFull
$bgLayer.Save("$res\drawable-nodpi\ic_launcher_bg.png", [System.Drawing.Imaging.ImageFormat]::Png)
$bgLayer.Dispose()

# ---------------- 3. 常规 mipmap ----------------
$sizes = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($key in @('mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi')) {
    $dir = "$res\mipmap-$key"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $px = $sizes[$key]
    $small = New-Object System.Drawing.Bitmap($px, $px, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $sg = [System.Drawing.Graphics]::FromImage($small)
    $sg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $sg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $sg.DrawImage($legacy, 0, 0, $px, $px)
    $sg.Dispose()
    $small.Save("$dir\ic_launcher.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $small.Dispose()
    Write-Host "wrote mipmap-$key ($px)"
}

# 背景改成 PNG 渐变层（drawable/ic_launcher_bg），原来那个纯色 color 资源删掉，
# 否则同名 color 和 drawable 会撞车（aapt2 报 duplicate resource）
$oldColors = "$res\values\colors.xml"
if (Test-Path $oldColors) { Remove-Item -Force $oldColors }

$anyDir = "$res\mipmap-anydpi-v26"
New-Item -ItemType Directory -Force -Path $anyDir | Out-Null
@'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_bg" />
    <foreground android:drawable="@drawable/ic_launcher_fg" />
</adaptive-icon>
'@ | Out-File -FilePath "$anyDir\ic_launcher.xml" -Encoding utf8

# ---------------- 4. 预览：模拟桌面（72/108 裁切 + 圆角蒙版） ----------------
# 这一步是给自己看的：把 bg + fg 叠起来，按系统实际可见区域裁掉外圈，
# 再套一个 squircle 蒙版，检查内容有没有贴边。
$previewPx = 256
$cropRatio = 72.0 / 108.0
$cropPx = [int]($fgFull * $cropRatio)
$offset = [int](($fgFull - $cropPx) / 2)

$composed = New-Object System.Drawing.Bitmap($fgFull, $fgFull, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$cg = [System.Drawing.Graphics]::FromImage($composed)
$cg.DrawImage([System.Drawing.Bitmap]::FromFile("$res\drawable-nodpi\ic_launcher_bg.png"), 0, 0, $fgFull, $fgFull)
$cg.DrawImage([System.Drawing.Bitmap]::FromFile("$res\drawable-nodpi\ic_launcher_fg.png"), 0, 0, $fgFull, $fgFull)
$cg.Dispose()

$cropped = New-Object System.Drawing.Bitmap($cropPx, $cropPx, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$cc = [System.Drawing.Graphics]::FromImage($cropped)
$cc.DrawImage($composed, (New-Object System.Drawing.Rectangle(0, 0, $cropPx, $cropPx)),
    $offset, $offset, $cropPx, $cropPx, [System.Drawing.GraphicsUnit]::Pixel)
$cc.Dispose()
$composed.Dispose()

$preview = New-Object System.Drawing.Bitmap($previewPx, $previewPx, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$pg = [System.Drawing.Graphics]::FromImage($preview)
$pg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$pg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$mask = New-RoundedPath 0 0 $previewPx $previewPx ($previewPx * 0.24)
$pg.SetClip($mask)
$pg.DrawImage($cropped, 0, 0, $previewPx, $previewPx)
$pg.ResetClip()
$pg.Dispose()
$mask.Dispose()
$cropped.Dispose()
$preview.Save("$masterDir\preview_home.png", [System.Drawing.Imaging.ImageFormat]::Png)
$preview.Dispose()

$legacy.Dispose()
Write-Host "icon done; preview: $masterDir\preview_home.png"
