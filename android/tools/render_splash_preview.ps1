param(
    [Parameter(Mandatory=$true)][string]$Ffmpeg,
    [Parameter(Mandatory=$true)][string]$Output
)

Add-Type -AssemblyName System.Drawing
$width = 720
$height = 1600
$fps = 30
$seconds = 4
$framesDir = Join-Path ([System.IO.Path]::GetDirectoryName($Output)) 'readme_splash_frames'
New-Item -ItemType Directory -Force -Path $framesDir | Out-Null

function Smooth([double]$x) {
    $v = [Math]::Max(0, [Math]::Min(1, $x))
    return $v * $v * (3 - 2 * $v)
}

function CenterText($graphics, $text, $font, $brush, [float]$y) {
    $size = $graphics.MeasureString($text, $font)
    $graphics.DrawString($text, $font, $brush, ($width - $size.Width) / 2, $y)
}

$basePoints = @(
    @(178, 660), @(246, 660), @(266, 638), @(286, 700), @(315, 565),
    @(348, 758), @(380, 620), @(405, 660), @(542, 660)
)

for ($frame = 0; $frame -lt ($fps * $seconds); $frame++) {
    $t = $frame / [double]$fps
    $bitmap = New-Object System.Drawing.Bitmap($width, $height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $graphics.Clear([System.Drawing.Color]::FromArgb(250, 249, 245))

    $lineProgress = Smooth (($t - 0.25) / 1.25)
    $pulse = if ($t -gt 1.1) { 1 + 0.018 * [Math]::Sin(($t - 1.1) * 8.0) * [Math]::Exp(-0.45 * ($t - 1.1)) } else { 1 }
    $points = New-Object System.Collections.Generic.List[System.Drawing.PointF]
    foreach ($p in $basePoints) {
        $scaledY = 660 + (($p[1] - 660) * $pulse)
        $points.Add([System.Drawing.PointF]::new([float]$p[0], [float]$scaledY))
    }

    $segmentLengths = @()
    $totalLength = 0.0
    for ($i = 0; $i -lt $points.Count - 1; $i++) {
        $dx = $points[$i+1].X - $points[$i].X
        $dy = $points[$i+1].Y - $points[$i].Y
        $length = [Math]::Sqrt($dx*$dx + $dy*$dy)
        $segmentLengths += $length
        $totalLength += $length
    }
    $remaining = $totalLength * $lineProgress
    $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(201,100,66), 10)
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    for ($i = 0; $i -lt $segmentLengths.Count -and $remaining -gt 0; $i++) {
        $take = [Math]::Min($remaining, $segmentLengths[$i])
        $ratio = $take / $segmentLengths[$i]
        $endX = $points[$i].X + ($points[$i+1].X - $points[$i].X) * $ratio
        $endY = $points[$i].Y + ($points[$i+1].Y - $points[$i].Y) * $ratio
        $graphics.DrawLine($pen, $points[$i].X, $points[$i].Y, [float]$endX, [float]$endY)
        $remaining -= $take
    }
    $pen.Dispose()

    $brandAlpha = [int](255 * (Smooth (($t - 1.15) / 0.75)))
    $tagAlpha = [int](255 * (Smooth (($t - 1.85) / 0.9)))
    $brandFont = New-Object System.Drawing.Font('Segoe UI', 58, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
    $tagFont = New-Object System.Drawing.Font('Microsoft YaHei UI', 30, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
    $brandBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb($brandAlpha,43,42,39))
    $tagBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb($tagAlpha,138,134,124))
    CenterText $graphics 'ReadMe' $brandFont $brandBrush 790
    CenterText $graphics '读懂身体，也读懂我' $tagFont $tagBrush 884
    $brandFont.Dispose(); $tagFont.Dispose(); $brandBrush.Dispose(); $tagBrush.Dispose()

    $path = Join-Path $framesDir ('frame_{0:D4}.png' -f $frame)
    $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $graphics.Dispose(); $bitmap.Dispose()
}

& $Ffmpeg -y -framerate $fps -i (Join-Path $framesDir 'frame_%04d.png') -c:v libx264 -pix_fmt yuv420p -movflags +faststart -r $fps $Output
if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed with exit code $LASTEXITCODE" }
Remove-Item -LiteralPath $framesDir -Recurse -Force
