package me.avinas.tempo.widget.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import me.avinas.tempo.R
import java.util.Locale

/**
 * Generates pixel-perfect bitmap renders for each Tempo widget,
 * matching the mockup designs exactly.
 */
object BitmapGenerator {

    // Common Colors
    private val TEAL_PRIMARY = Color.parseColor("#13ECA4")
    private val DARK_BG_GREEN = Color.parseColor("#0F1815")
    private val DARK_BG_NEUTRAL = Color.parseColor("#151515")
    private val PURPLE_PRIMARY = Color.parseColor("#D0BCFF")
    private val PURPLE_ON_PRIMARY = Color.parseColor("#381E72")
    private val PURPLE_SURFACE = Color.parseColor("#2D2A37")
    private val ON_SURFACE = Color.parseColor("#E6E1E5")
    private val ON_SURFACE_VARIANT = Color.parseColor("#CAC4D0")
    private val TERTIARY_PINK = Color.parseColor("#EFB8C8")
    private val DYNAMIC_BG = Color.parseColor("#202826")
    private val DYNAMIC_PRIMARY = Color.parseColor("#80DAB0")
    private val DYNAMIC_CONTAINER = Color.parseColor("#005138")
    private val DYNAMIC_ON_CONTAINER = Color.parseColor("#9CF8CE")

    // Genre gradient colors
    private val GENRE_COLORS = listOf(
        intArrayOf(Color.parseColor("#13ECA4"), Color.parseColor("#0FB885")),  // Green (primary)
        intArrayOf(Color.parseColor("#2DD4BF"), Color.parseColor("#0EA5E9")),  // Teal→Blue
        intArrayOf(Color.parseColor("#818CF8"), Color.parseColor("#6366F1")),  // Indigo
        intArrayOf(Color.parseColor("#3B82F6"), Color.parseColor("#1E3A8A")),  // Blue
        intArrayOf(Color.parseColor("#A855F7"), Color.parseColor("#7C3AED")),  // Purple
    )

    // Widget 1: Artist Spotlight ("Top Pick")
    // Dark teal card, circular artist image, "TOP PICK" badge, hours listened
    fun generateArtistSpotlight(
        context: Context,
        width: Int,
        height: Int,
        artistBitmap: Bitmap?,
        artistName: String,
        hoursListened: String
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pad = width * 0.09f

        // 1. Background: dark teal
        canvas.drawColor(DARK_BG_GREEN)

        // 2. Subtle green glow top-right
        val glowPaint = Paint().apply {
            shader = RadialGradient(
                width * 0.85f, height * 0.1f, width * 0.5f,
                ColorUtils.setAlphaComponent(TEAL_PRIMARY, 100),
                Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(width * 0.85f, height * 0.1f, width * 0.5f, glowPaint)

        // 3. Bottom gradient overlay for text readability
        val overlayPaint = Paint().apply {
            shader = LinearGradient(
                0f, height * 0.35f, 0f, height.toFloat(),
                Color.TRANSPARENT, ColorUtils.setAlphaComponent(Color.BLACK, 230),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, height * 0.35f, width.toFloat(), height.toFloat(), overlayPaint)

        // 4. Tempo icon (green square) top-left
        val iconSize = width * 0.12f
        val iconPaint = Paint().apply {
            color = TEAL_PRIMARY
            isAntiAlias = true
        }
        canvas.drawRoundRect(
            pad, pad, pad + iconSize, pad + iconSize,
            iconSize * 0.25f, iconSize * 0.25f, iconPaint
        )
        // Eq bars inside the icon
        val barPaint = Paint().apply {
            color = Color.parseColor("#0F1815")
            strokeWidth = iconSize * 0.1f
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        val icCx = pad + iconSize / 2
        val icCy = pad + iconSize / 2
        val barH = iconSize * 0.35f
        for (i in -1..1) {
            val bx = icCx + i * iconSize * 0.2f
            val h = barH * (if (i == 0) 1f else 0.6f)
            canvas.drawLine(bx, icCy - h / 2, bx, icCy + h / 2, barPaint)
        }

        // 5. "TOP PICK" badge top-right
        val badgePaint = Paint().apply {
            color = ColorUtils.setAlphaComponent(TEAL_PRIMARY, 50)
            isAntiAlias = true
        }
        val badgeBorderPaint = Paint().apply {
            color = ColorUtils.setAlphaComponent(TEAL_PRIMARY, 80)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        val badgeTextPaint = Paint().apply {
            color = TEAL_PRIMARY
            textSize = width * 0.05f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            letterSpacing = 0.15f
        }
        val badgeText = "TOP PICK"
        val btw = badgeTextPaint.measureText(badgeText)
        val bph = badgeTextPaint.textSize + width * 0.04f
        val badgeRight = width - pad
        val badgeLeft = badgeRight - btw - width * 0.06f
        val badgeTop = pad
        val badgeBottom = badgeTop + bph
        val badgeRect = RectF(badgeLeft, badgeTop, badgeRight, badgeBottom)
        canvas.drawRoundRect(badgeRect, bph, bph, badgePaint)
        canvas.drawRoundRect(badgeRect, bph, bph, badgeBorderPaint)
        canvas.drawText(
            badgeText,
            badgeRect.centerX() - btw / 2,
            badgeRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2,
            badgeTextPaint
        )

        // 6. Artist photo – circular, centered, with green glow ring
        val imgSize = (width * 0.47f).coerceAtMost(height * 0.38f)
        val imgCx = width / 2f
        val imgCy = height * 0.42f

        // Glow ring behind image
        val ringGlow = Paint().apply {
            shader = RadialGradient(
                imgCx, imgCy, imgSize * 0.6f,
                ColorUtils.setAlphaComponent(TEAL_PRIMARY, 80),
                Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(imgCx, imgCy, imgSize * 0.6f, ringGlow)

        // Image border
        val borderPaint = Paint().apply {
            color = ColorUtils.setAlphaComponent(TEAL_PRIMARY, 120)
            style = Paint.Style.STROKE
            strokeWidth = width * 0.012f
            isAntiAlias = true
        }
        canvas.drawCircle(imgCx, imgCy, imgSize / 2 + width * 0.008f, borderPaint)

        if (artistBitmap != null) {
            val scaled = cropBitmap(artistBitmap, imgSize.toInt(), imgSize.toInt())
            val layerId = canvas.save()
            val clipPath = Path().apply {
                addCircle(imgCx, imgCy, imgSize / 2, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            canvas.drawBitmap(scaled, imgCx - imgSize / 2, imgCy - imgSize / 2, null)
            canvas.restoreToCount(layerId)
        } else {
            // Placeholder circle
            val placePaint = Paint().apply {
                color = ColorUtils.setAlphaComponent(TEAL_PRIMARY, 60)
                isAntiAlias = true
            }
            canvas.drawCircle(imgCx, imgCy, imgSize / 2, placePaint)
        }

        // 7. Artist name (bold, white, centered)
        val namePaint = Paint().apply {
            color = Color.WHITE
            textSize = width * 0.1f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        val nameY = height * 0.73f
        canvas.drawText(artistName, width / 2f, nameY, namePaint)

        // 8. Clock icon + "Xh listened" (green, centered)
        val hoursTextPaint = Paint().apply {
            color = ColorUtils.setAlphaComponent(TEAL_PRIMARY, 230)
            textSize = width * 0.058f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val clockText = "${hoursListened} listened"
        val clockY = nameY + width * 0.1f
        val clockIconSize = width * 0.06f
        val clockTextW = hoursTextPaint.measureText(clockText)
        val clockStartX = width / 2f - (clockIconSize + width * 0.02f + clockTextW) / 2f
        drawIcon(
            canvas, context, R.drawable.ic_widget_timer,
            clockStartX, clockY - clockIconSize * 0.78f, clockIconSize,
            ColorUtils.setAlphaComponent(TEAL_PRIMARY, 230),
        )
        hoursTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(clockText, clockStartX + clockIconSize + width * 0.02f, clockY, hoursTextPaint)

        return bitmap
    }

    // Widget 2: Weekly Mix
    // Dark card, colorful album art icon, "Weekly Mix / Made for you", audio bars
    fun generateMixPortal(
        context: Context,
        width: Int,
        height: Int,
        mixTitle: String,
        artistName: String,
        imagePath: String?,
        chartData: List<Float> = emptyList()
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pad = width * 0.06f

        // 1. Background
        canvas.drawColor(DARK_BG_NEUTRAL)

        // 2. Subtle green glow top-right
        val glowPaint = Paint().apply {
            shader = RadialGradient(
                width * 0.9f, height * 0.1f, width * 0.35f,
                ColorUtils.setAlphaComponent(TEAL_PRIMARY, 50),
                Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(width * 0.9f, height * 0.1f, width * 0.35f, glowPaint)

        // Border
        val borderPaint = Paint().apply {
            color = ColorUtils.setAlphaComponent(Color.WHITE, 13)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), width * 0.08f, width * 0.08f, borderPaint)

        // 3. Album art thumbnail (rounded corners, top-left)
        val artSize = height * 0.4f
        val artX = pad
        val artY = pad
        if (imagePath != null) {
            try {
                val artBitmap = decodeBitmapSafe(imagePath, (artSize * 2).toInt().coerceAtLeast(256))
                if (artBitmap != null) {
                    val cropped = cropBitmap(artBitmap, artSize.toInt(), artSize.toInt())
                    val artRect = RectF(artX, artY, artX + artSize, artY + artSize)
                    val layerId = canvas.save()
                    val clipPath = Path().apply {
                        addRoundRect(artRect, artSize * 0.15f, artSize * 0.15f, Path.Direction.CW)
                    }
                    canvas.clipPath(clipPath)
                    canvas.drawBitmap(cropped, artX, artY, null)
                    canvas.restoreToCount(layerId)
                    
                    // Shadow under art
                    val shadowPaint = Paint().apply {
                        color = Color.BLACK
                        setShadowLayer(16f, 0f, 6f, Color.BLACK)
                    }
                }
            } catch (_: Exception) {}
        }
        // Fallback: colorful gradient square if no art
        if (imagePath == null) {
            val artPaint = Paint().apply {
                shader = LinearGradient(
                    artX, artY, artX + artSize, artY + artSize,
                    intArrayOf(Color.parseColor("#FF6B6B"), Color.parseColor("#4ECDC4"), Color.parseColor("#45B7D1")),
                    null, Shader.TileMode.CLAMP
                )
                isAntiAlias = true
            }
            canvas.drawRoundRect(
                artX, artY, artX + artSize, artY + artSize,
                artSize * 0.15f, artSize * 0.15f, artPaint
            )
        }

        // 4. Title text: "Weekly Mix" in white bold, "Made for you" in green
        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = width * 0.055f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val subtitlePaint = Paint().apply {
            color = TEAL_PRIMARY
            textSize = width * 0.038f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
            letterSpacing = 0.05f
        }
        val textX = artX + artSize + pad * 0.8f
        val titleY = artY + artSize * 0.45f
        canvas.drawText(mixTitle, textX, titleY, titlePaint)
        canvas.drawText(artistName, textX, titleY + width * 0.05f, subtitlePaint)

        // 5. Audio bars at bottom (Polished with gradient)
        val barsY = height * 0.95f
        val barWidth = width * 0.016f
        val maxBarH = height * 0.3f
        val barGap = width * 0.008f
        val numBars = 12
        val totalBarsWidth = numBars * (barWidth + barGap) - barGap
        val barsStartX = (width - totalBarsWidth) / 2f

        // Use chart data to drive bar heights, or generate procedural
        val barHeights = if (chartData.isNotEmpty()) {
            val maxVal = chartData.maxOrNull()?.coerceAtLeast(1f) ?: 1f
            // Interpolate to 12 bars
            (0 until numBars).map { i ->
                val idx = (i.toFloat() / numBars * chartData.size).toInt().coerceIn(0, chartData.lastIndex)
                (chartData[idx] / maxVal).coerceIn(0.15f, 1f)
            }
        } else {
            listOf(0.3f, 0.5f, 1f, 0.7f, 0.4f, 0.2f, 0.5f, 0.9f, 0.6f, 0.3f, 0.2f, 0.4f)
        }

        for (i in 0 until numBars) {
            val x = barsStartX + i * (barWidth + barGap)
            val h = maxBarH * barHeights[i]
            val alpha = (barHeights[i] * 255).toInt().coerceIn(80, 255)
            
            val barPaint = Paint().apply {
                shader = LinearGradient(0f, barsY - h, 0f, barsY,
                    TEAL_PRIMARY, ColorUtils.setAlphaComponent(TEAL_PRIMARY, 20),
                    Shader.TileMode.CLAMP)
                isAntiAlias = true
            }
            canvas.drawRoundRect(
                x, barsY - h, x + barWidth, barsY,
                barWidth / 2, barWidth / 2, barPaint
            )
        }

        return bitmap
    }

    // Widget 3: Genre Heatmap (Treemap layout)
    // Blocks sized by percentage with gradient colors
    fun generateHeatmapSynth(
        context: Context,
        width: Int,
        height: Int,
        topGenres: List<Pair<String, Int>>,
        genrePercents: List<Int> = emptyList()
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val gap = width * 0.006f

        canvas.drawColor(Color.parseColor("#10221C"))

        if (topGenres.isEmpty()) {
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = width * 0.06f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            canvas.drawText("No genre data yet", width / 2f, height / 2f, textPaint)
            return bitmap
        }

        // Calculate percentages if not provided
        val total = topGenres.sumOf { it.second }.coerceAtLeast(1)
        val percents = if (genrePercents.isNotEmpty()) genrePercents
        else topGenres.map { (it.second * 100) / total }

        // Treemap layout: largest genre takes left 7/12 of full height
        // Second genre takes top-right 5/12 width, top half
        // Third and fourth share bottom-right
        val genres = topGenres.take(4)
        val percs = percents.take(4)

        // Left block: genres[0] (dominant)
        val leftW = width * 7f / 12f
        drawGenreBlock(
            context, canvas, 0f, 0f, leftW - gap, height.toFloat(), 
            genres.getOrNull(0)?.first ?: "", percs.getOrNull(0) ?: 0,
            GENRE_COLORS[0], width, isLarge = true
        )

        // Top-right block: genres[1]
        val rightX = leftW + gap
        val rightW = width.toFloat() - rightX
        val topH = height * 3f / 6f
        drawGenreBlock(
            context, canvas, rightX, 0f, rightX + rightW, topH - gap,
            genres.getOrNull(1)?.first ?: "", percs.getOrNull(1) ?: 0,
            GENRE_COLORS[1], width, isLarge = false
        )

        // Bottom-right: split between genres[2] and genres[3]
        val botY = topH + gap
        val botH = height.toFloat()
        if (genres.size >= 4) {
            val splitX = rightX + rightW * 0.6f
            drawGenreBlock(
                context, canvas, rightX, botY, splitX - gap, botH,
                genres[2].first, percs[2],
                GENRE_COLORS[2], width, isLarge = false
            )
            drawGenreBlock(
                context, canvas, splitX + gap, botY, rightX + rightW, botH,
                genres[3].first, percs[3],
                GENRE_COLORS[3], width, isLarge = false
            )
        } else if (genres.size >= 3) {
            drawGenreBlock(
                context, canvas, rightX, botY, rightX + rightW, botH,
                genres[2].first, percs[2],
                GENRE_COLORS[2], width, isLarge = false
            )
        }

        // "TEMPO" branding in top-right
        val dotPaint = Paint().apply {
            color = TEAL_PRIMARY
            isAntiAlias = true
        }
        canvas.drawCircle(rightX + width * 0.02f, width * 0.04f, width * 0.012f, dotPaint)
        val brandPaint = Paint().apply {
            color = Color.WHITE
            textSize = width * 0.03f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            letterSpacing = 0.15f
        }
        canvas.drawText("TEMPO", rightX + width * 0.04f, width * 0.048f, brandPaint)

        return bitmap
    }

    private fun drawGenreBlock(
        context: Context, // Added context if needed, but not used here yet
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
        genreName: String, percent: Int, colors: IntArray, totalWidth: Int,
        isLarge: Boolean
    ) {
        val rect = RectF(left, top, right, bottom)
        val paint = Paint().apply {
            shader = LinearGradient(
                left, top, right, bottom,
                colors[0], colors[1], Shader.TileMode.CLAMP
            )
            isAntiAlias = true
        }
        canvas.drawRect(rect, paint)

        // Texture overlay (subtle noise)
        val texturePaint = Paint().apply {
            color = ColorUtils.setAlphaComponent(Color.WHITE, 8)
            style = Paint.Style.FILL
        }
        val step = totalWidth * 0.03f
        var tx = left
        while (tx < right) {
            var ty = top
            while (ty < bottom) {
                canvas.drawCircle(tx, ty, 0.5f, texturePaint)
                ty += step
            }
            tx += step
        }
        
        // Gradient overlay for text readability (bottom-up shadow)
        val overlayPaint = Paint().apply {
            shader = LinearGradient(
                0f, bottom - (bottom - top) * 0.6f, 0f, bottom,
                Color.TRANSPARENT, ColorUtils.setAlphaComponent(Color.BLACK, 100),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect, overlayPaint)

        // Typography
        val textColor = if (isLarge) Color.parseColor("#10221C") else Color.WHITE
        val baseTextSize = if (isLarge) totalWidth * 0.076f else totalWidth * 0.048f
        
        val namePaint = Paint().apply {
            color = textColor
            textSize = baseTextSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val percentPaint = Paint().apply {
            color = ColorUtils.setAlphaComponent(textColor, 200)
            textSize = if (isLarge) totalWidth * 0.045f else totalWidth * 0.03f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val padInner = totalWidth * 0.035f
        val blockW = right - left
        val maxTextW = blockW - padInner * 2

        if (isLarge) {
            val displayName = genreName.uppercase()
            val words = displayName.split(" ", "/")
            
            var textY = bottom - padInner - percentPaint.textSize - padInner * 0.5f
            
            words.reversed().forEach { word ->
                // Shrink font size for long words
                var currentTextSize = baseTextSize
                namePaint.textSize = currentTextSize
                while (namePaint.measureText(word) > maxTextW && currentTextSize > 10f) {
                    currentTextSize -= 2f
                    namePaint.textSize = currentTextSize
                }
                
                canvas.drawText(word, left + padInner, textY, namePaint)
                textY -= namePaint.textSize * 0.95f
                
                // Reset size for next word
                namePaint.textSize = baseTextSize 
            }
            canvas.drawText("${percent}%", left + padInner, bottom - padInner, percentPaint)
        } else {
            // Small blocks
            val blockH = bottom - top
            
            // Check if block is very narrow -> vertical text
            if (blockW < totalWidth * 0.12f) {
                canvas.save()
                canvas.rotate(-90f, (left + right) / 2, (top + bottom) / 2)
                canvas.drawText(genreName.uppercase(), (left + right) / 2 - namePaint.measureText(genreName.uppercase()) / 2, (top + bottom) / 2, namePaint)
                canvas.restore()
                canvas.drawText("${percent}%", left + padInner * 0.5f, bottom - padInner * 0.5f, percentPaint)
            } else {
                val displayName = genreName.uppercase()
                
                // Scale text to fit width
                var currentTextSize = baseTextSize
                namePaint.textSize = currentTextSize
                while (namePaint.measureText(displayName) > maxTextW && currentTextSize > 10f) {
                    currentTextSize -= 2f
                    namePaint.textSize = currentTextSize
                }
                
                canvas.drawText(displayName, left + padInner, bottom - padInner - percentPaint.textSize, namePaint)
                canvas.drawText("${percent}%", left + padInner, bottom - padInner, percentPaint)
            }
        }
    }

    // Widget 4: Discovery Card
    // Purple glassmorphism, insight sentence, 3-column stats footer
    fun generateDiscoveryCard(
        context: Context,
        width: Int,
        height: Int,
        discoveryText: String,
        artistName: String,
        tracksCount: String,
        hoursListened: String,
        percentile: String
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pad = width * 0.06f

        // 1. Background: Deep rich purple/dark theme
        val bgPaint = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
                Color.parseColor("#1E1B26"), Color.parseColor("#141218"), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Subtle noise texture
        drawNoiseTexture(canvas, width, height)

        // Purple glow top-right (stronger)
        val glowPaint = Paint().apply {
            shader = RadialGradient(
                width * 0.9f, height * 0.1f, width * 0.5f,
                ColorUtils.setAlphaComponent(Color.parseColor("#7C3AED"), 80),
                Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(width * 0.9f, height * 0.1f, width * 0.5f, glowPaint)

        // Blue glow bottom-left
        val glow2 = Paint().apply {
            shader = RadialGradient(
                width * 0.1f, height * 0.9f, width * 0.5f,
                ColorUtils.setAlphaComponent(Color.parseColor("#3B82F6"), 60),
                Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(width * 0.1f, height * 0.9f, width * 0.5f, glow2)

        // 2. Glass border
        val borderPaint = Paint().apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                ColorUtils.setAlphaComponent(Color.WHITE, 50),
                ColorUtils.setAlphaComponent(Color.WHITE, 10),
                Shader.TileMode.CLAMP)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), width * 0.08f, width * 0.08f, borderPaint)

        // 3. "Tempo Discovery" pill top-left
        val pillH = width * 0.07f
        val pillBg = Paint().apply {
            color = ColorUtils.setAlphaComponent(Color.WHITE, 20)
            isAntiAlias = true
        }
        val pillTextPaint = Paint().apply {
            color = Color.parseColor("#E6E1E5")
            textSize = width * 0.035f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAntiAlias = true
            letterSpacing = 0.05f
        }
        val pillLabel = "Discovery"
        val pillIconSize = pillH * 0.62f
        val pillIconGap = width * 0.015f
        val ptw = pillTextPaint.measureText(pillLabel) + pillIconSize + pillIconGap
        val pillRect = RectF(pad, pad, pad + ptw + width * 0.06f, pad + pillH)
        
        canvas.drawRoundRect(pillRect, pillH, pillH, pillBg)
        // Inner border for pill
        val pillBorder = Paint().apply {
            color = ColorUtils.setAlphaComponent(Color.WHITE, 30)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            isAntiAlias = true
        }
        canvas.drawRoundRect(pillRect, pillH, pillH, pillBorder)
        
        val pillTextX = pillRect.left + width * 0.03f
        drawIcon(
            canvas, context, R.drawable.ic_widget_sparkles,
            pillTextX, pillRect.centerY() - pillIconSize / 2f, pillIconSize,
            Color.parseColor("#E6E1E5"),
        )
        canvas.drawText(
            pillLabel,
            pillTextX + pillIconSize + pillIconGap,
            pillRect.centerY() - (pillTextPaint.descent() + pillTextPaint.ascent()) / 2,
            pillTextPaint
        )

        // 4. Arrow icon top-right
        val arrowSize = width * 0.06f
        drawIcon(
            canvas, context, R.drawable.ic_widget_trending_up,
            width - pad - arrowSize, pad + pillH / 2 - arrowSize / 2, arrowSize,
            Color.parseColor("#D0BCFF"),
        )

        // 5. Insight text centered
        val insightPaint = Paint().apply {
            color = Color.WHITE
            textSize = (width * 0.055f).coerceIn(24f, 60f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            letterSpacing = -0.01f
        }
        
        // Calculate center Y area
        val centerY = height * 0.45f
        
        // Text wrapping/handling logic could go here, but for now we trust the text is short-ish
        // We will "highlight" the artist name manually
        if (discoveryText.contains(artistName) && artistName.isNotEmpty()) {
            val parts = discoveryText.split(artistName)
            val fullWidth = insightPaint.measureText(discoveryText)
            val artistWidth = Paint(insightPaint).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }.measureText(artistName)
            // Re-measure full width with bold artist
             val totalW = insightPaint.measureText(parts[0]) + artistWidth + (if (parts.size > 1) insightPaint.measureText(parts[1]) else 0f)
             
            var currentX = width / 2f - totalW / 2f
            
            // Part 1
            if (parts.isNotEmpty()) {
                canvas.drawText(parts[0], currentX, centerY, insightPaint.apply { textAlign = Paint.Align.LEFT })
                currentX += insightPaint.measureText(parts[0])
            }
            
            // Artist (Gradient Text)
            val artistPaint = Paint().apply {
                textSize = insightPaint.textSize
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                isAntiAlias = true
                shader = LinearGradient(0f, 0f, width.toFloat(), 0f,
                   Color.parseColor("#D0BCFF"), Color.parseColor("#B39DDB"), Shader.TileMode.CLAMP)
            }
            canvas.drawText(artistName, currentX, centerY, artistPaint)
             currentX += artistPaint.measureText(artistName)
             
             // Part 2
             if (parts.size > 1) {
                canvas.drawText(parts[1], currentX, centerY, insightPaint.apply { shader = null; color = Color.WHITE })
             }
        } else {
            canvas.drawText(discoveryText, width / 2f, centerY, insightPaint)
        }

        // 6. Divider line 
        val dividerY = height * 0.65f
        val dividerPaint = Paint().apply {
            shader = LinearGradient(pad, 0f, width - pad, 0f,
                intArrayOf(Color.TRANSPARENT, ColorUtils.setAlphaComponent(Color.WHITE, 50), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            strokeWidth = 1f
        }
        canvas.drawLine(pad, dividerY, width - pad, dividerY, dividerPaint)

        // 7. Stats footer
        val statsY = height * 0.8f
        val labelY = height * 0.9f
        val colW = (width - pad * 2) / 3f
        
        val valuePaint = Paint().apply {
            color = Color.WHITE
            textSize = width * 0.055f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val labelPaint = Paint().apply {
            color = ColorUtils.setAlphaComponent(Color.parseColor("#CAC4D0"), 180)
            textSize = width * 0.026f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            letterSpacing = 0.05f
        }

        fun drawStat(index: Int, valText: String, lblText: String, color: Int = Color.WHITE) {
            val cx = pad + colW * index + colW / 2
            valuePaint.color = color
            canvas.drawText(valText, cx, statsY, valuePaint)
            canvas.drawText(lblText, cx, labelY, labelPaint)
        }

        drawStat(0, tracksCount, "TRACKS")
        drawStat(1, hoursListened, "HOURS")
        drawStat(2, percentile, "PERCENTILE", Color.parseColor("#EFB8C8")) // Pink for percentile

        return bitmap
    }

    // Keep old method name for compatibility (delegates to new)
    fun generateDiscoveryConstellation(
        context: Context, width: Int, height: Int, discoveryText: String
    ): Bitmap {
        return generateDiscoveryCard(context, width, height, discoveryText, "", "--", "--", "--")
    }

    // Widget 5: Milestone Card
    // Purple card, album art, track/artist, "#1 Most Played" badge
    fun generateMilestoneCard(
        context: Context,
        width: Int,
        height: Int,
        trackName: String,
        artistName: String,
        badge: String,
        imagePath: String?
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pad = width * 0.07f

        // 1. Generative purple background with header gradient
        val bgPaint = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
               Color.parseColor("#2D2A35"), Color.parseColor("#1C1A22"), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Noise
        drawNoiseTexture(canvas, width, height)

        // Radial glows
        val glow1 = Paint().apply {
            shader = RadialGradient(
                width * 0.9f, height * 0.1f, width * 0.5f,
                ColorUtils.setAlphaComponent(Color.parseColor("#7C3AED"), 60),
                Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(width * 0.9f, height * 0.1f, width * 0.5f, glow1)

        val glow2 = Paint().apply {
            shader = RadialGradient(
                width * 0.15f, height * 0.85f, width * 0.4f,
                ColorUtils.setAlphaComponent(Color.parseColor("#6D28D9"), 50),
                Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(width * 0.15f, height * 0.85f, width * 0.4f, glow2)

        // Wave Chart at bottom (replacing geometric pattern)
        // Simulate some data for visuals if we don't have real history passed here yet
        val dummyHistory = listOf(20f, 45f, 30f, 80f, 50f, 90f, 70f, 100f, 60f, 40f)
        val chartRect = RectF(0f, height * 0.65f, width.toFloat(), height.toFloat())
        drawWaveChart(
            canvas, 
            dummyHistory, 
            chartRect,
            ColorUtils.setAlphaComponent(TEAL_PRIMARY, 10), // Very subtle fill
            ColorUtils.setAlphaComponent(TEAL_PRIMARY, 80)
        )

        // Gradient overlay bottom
        val bottomGrad = Paint().apply {
            shader = LinearGradient(
                0f, height * 0.4f, 0f, height.toFloat(),
                Color.TRANSPARENT, ColorUtils.setAlphaComponent(Color.BLACK, 220),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, height * 0.4f, width.toFloat(), height.toFloat(), bottomGrad)

        // 2. Tempo branding pill top-left
        val brandBg = Paint().apply {
            color = ColorUtils.setAlphaComponent(Color.parseColor("#49454F"), 100)
            isAntiAlias = true
        }
        val brandText = Paint().apply {
            color = ON_SURFACE_VARIANT
            textSize = width * 0.035f
            isAntiAlias = true
            letterSpacing = 0.05f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        // Icon circle
        val icR = width * 0.035f
        val icCx = pad + icR
        val icCy = pad + icR
        val icPaint = Paint().apply { color = PURPLE_PRIMARY; isAntiAlias = true }
        canvas.drawCircle(icCx, icCy, icR, icPaint)

        // Eq bars in icon
        val eqPaint = Paint().apply {
            color = PURPLE_ON_PRIMARY
            strokeWidth = icR * 0.25f
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        for (i in -1..1) {
            val ex = icCx + i * icR * 0.45f
            val eh = icR * (if (i == 0) 0.8f else 0.5f)
            canvas.drawLine(ex, icCy - eh / 2, ex, icCy + eh / 2, eqPaint)
        }
        canvas.drawText("Tempo", icCx + icR + pad * 0.4f, icCy + brandText.textSize * 0.35f, brandText)

        // 3. Album art centered
        val artSize = (width * 0.45f).coerceAtMost(height * 0.38f)
        val artCx = width / 2f
        val artCy = height * 0.4f
        val artLeft = artCx - artSize / 2
        val artTop = artCy - artSize / 2
        val artRadius = artSize * 0.12f

        // Shadow/ring around art
        val ringPaint = Paint().apply {
            color = ColorUtils.setAlphaComponent(Color.WHITE, 40)
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        canvas.drawRoundRect(
            artLeft - 3, artTop - 3, artLeft + artSize + 3, artTop + artSize + 3,
            artRadius, artRadius, ringPaint
        )

        if (imagePath != null) {
            try {
                val artBitmap = decodeBitmapSafe(imagePath, (artSize * 2).toInt().coerceAtLeast(256))
                if (artBitmap != null) {
                    val cropped = cropBitmap(artBitmap, artSize.toInt(), artSize.toInt())
                    val artRect = RectF(artLeft, artTop, artLeft + artSize, artTop + artSize)
                    val layerId = canvas.save()
                    val clipPath = Path().apply {
                        addRoundRect(artRect, artRadius, artRadius, Path.Direction.CW)
                    }
                    canvas.clipPath(clipPath)
                    canvas.drawBitmap(cropped, artLeft, artTop, null)
                    canvas.restoreToCount(layerId)
                }
            } catch (_: Exception) {}
        } else {
            // Swirl placeholder (Improved)
            val swirlPaint = Paint().apply {
                shader = RadialGradient(
                    artCx, artCy, artSize / 2,
                    Color.parseColor("#F59E0B"), Color.parseColor("#7C3AED"),
                    Shader.TileMode.CLAMP
                )
                isAntiAlias = true
            }
            canvas.drawRoundRect(artLeft, artTop, artLeft + artSize, artTop + artSize, artRadius, artRadius, swirlPaint)
        }

        // 4. Track name + artist (centered below art)
        val trackPaint = Paint().apply {
            color = ON_SURFACE
            textSize = width * 0.065f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            letterSpacing = -0.02f
        }
        val artistPaint = Paint().apply {
            color = ON_SURFACE_VARIANT
            textSize = width * 0.042f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val trackY = artTop + artSize + width * 0.1f
        canvas.drawText(trackName, width / 2f, trackY, trackPaint)
        canvas.drawText(artistName, width / 2f, trackY + width * 0.06f, artistPaint)

        // 5. Badge pill at bottom
        val isSpecialMilestone = badge.contains("Stream!")
        val badgeColor = if (isSpecialMilestone) Color.parseColor("#FFF8E1") else Color.parseColor("#F3E5F5")
        
        val badgeTextPaint = Paint().apply {
            color = badgeColor
            textSize = width * 0.034f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            letterSpacing = 0.02f
        }

        // Special golden badge gradient
        val actualBadgeBg = if (isSpecialMilestone) {
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, width.toFloat(), 0f,
                    Color.parseColor("#FFECB3"),
                    Color.parseColor("#FFC107"), 
                    Shader.TileMode.CLAMP
                )
                isAntiAlias = true
            }
        } else {
             Paint().apply {
                color = ColorUtils.setAlphaComponent(Color.parseColor("#7C3AED"), 40) // More subtle purple
                isAntiAlias = true
            }
        }
        
        // For special milestone, we want the text to be dark since bg is bright gold
        if (isSpecialMilestone) badgeTextPaint.color = Color.parseColor("#3E2723")

        val actualBadgeBorder = Paint().apply {
            color = if (isSpecialMilestone) Color.parseColor("#FCD34D") else Color.parseColor("#D0BCFF")
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        val badgeIconSize = badgeTextPaint.textSize * 0.95f
        val badgeGap = width * 0.012f
        val badgeContentW = badgeIconSize + badgeGap + badgeTextPaint.measureText(badge)
        val badgePH = badgeTextPaint.textSize + width * 0.03f
        val badgeLeft = (width - badgeContentW) / 2 - width * 0.05f
        val badgeRight = (width + badgeContentW) / 2 + width * 0.05f
        val badgeTop = height - pad - badgePH
        val badgeBottom = height - pad
        val badgeRect = RectF(badgeLeft, badgeTop, badgeRight, badgeBottom)

        canvas.drawRoundRect(badgeRect, badgePH, badgePH, actualBadgeBg)
        canvas.drawRoundRect(badgeRect, badgePH, badgePH, actualBadgeBorder)
        val badgeContentLeft = (width - badgeContentW) / 2f
        drawIcon(
            canvas, context,
            if (isSpecialMilestone) R.drawable.ic_widget_trophy else R.drawable.ic_widget_trending_up,
            badgeContentLeft, badgeRect.centerY() - badgeIconSize / 2f, badgeIconSize,
            badgeTextPaint.color,
        )
        badgeTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            badge, badgeContentLeft + badgeIconSize + badgeGap,
            badgeRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2,
            badgeTextPaint
        )

        return bitmap
    }

    // Keep old method name for compatibility
    fun generateMilestoneArtifact(
        context: Context, width: Int, height: Int,
        title: String, subtitle: String, imagePath: String?
    ): Bitmap {
        return generateMilestoneCard(context, width, height, title, subtitle, "#1 Most Played This Week", imagePath)
    }

    // Widget 6: Dashboard Card
    // Split layout: hours+growth left, bar chart right, top artist pill bottom
    // Widget 6: Dashboard Card (Reimagined "Cinematic Daily" UI)
    // Layout: Big Typography Left, Floating Artist Right, Full Width Wave Bottom
    fun generateDashboardCard(
        context: Context,
        width: Int,
        height: Int,
        weeklyHours: String,
        weeklyGrowth: String, // e.g. "+12%"
        weeklyInsight: String,
        chartData: List<Float>,
        topArtistName: String,
        artistImagePath: String?
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pad = width * 0.06f

        // 1. Background: Deep rich gradient (Updated for better aesthetics)
        // 4-color gradient background
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(
                    Color.parseColor("#1A1C1E"), // Dark Gunmetal
                    Color.parseColor("#0F2027"), // Deep Blue
                    Color.parseColor("#203A43"), // Cinematic Teal
                    Color.parseColor("#2C5364")  // Lighter Teal accent
                ),
                floatArrayOf(0f, 0.4f, 0.8f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Noise texture
        drawNoiseTexture(canvas, width, height)

        // Subtle Glow top-left (behind the hours)
        val glowPaint = Paint().apply {
            shader = RadialGradient(
                width * 0.2f, height * 0.3f, width * 0.6f,
                ColorUtils.setAlphaComponent(TEAL_PRIMARY, 40),
                Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(width * 0.2f, height * 0.3f, width * 0.6f, glowPaint)

        // 2. Wave Chart (Bottom Hero)
        if (chartData.isNotEmpty()) {
            val chartH = height * 0.45f
            val chartTop = height - chartH
            val chartRect = RectF(0f, chartTop, width.toFloat(), height.toFloat()) // Full width, bottom aligned

            // Gradient fill for the chart
            val fillStart = ColorUtils.setAlphaComponent(TEAL_PRIMARY, 100)
            val fillEnd = ColorUtils.setAlphaComponent(TEAL_PRIMARY, 0)
            
            drawWaveChart(
                canvas,
                chartData,
                chartRect,
                fillStart,
                fillEnd
            )
        }

        // 3. Typography Group (Top Left)
        
        // "THIS WEEK" Label
        val labelPaint = Paint().apply {
            color = ColorUtils.setAlphaComponent(Color.WHITE, 180)
            textSize = width * 0.032f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAntiAlias = true
            letterSpacing = 0.2f
        }
        canvas.drawText("THIS WEEK", pad, pad + labelPaint.textSize, labelPaint)

        // Hours Value (Massive)
        val hoursPaint = Paint().apply {
            color = Color.WHITE
            textSize = width * 0.16f
            typeface = Typeface.create("sans-serif", Typeface.BOLD) // Standard bold often looks cleaner/premium than medium for headings
            isAntiAlias = true
            setShadowLayer(12f, 0f, 4f, ColorUtils.setAlphaComponent(Color.BLACK, 100))
        }
        val hoursY = pad + labelPaint.textSize + width * 0.02f + hoursPaint.textSize * 0.9f
        canvas.drawText(weeklyHours, pad, hoursY, hoursPaint)
        
        // Draw unit indicator
        val unitPaint = Paint().apply {
            color = ColorUtils.setAlphaComponent(Color.WHITE, 150)
            textSize = width * 0.06f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAntiAlias = true
        }
        val hw = hoursPaint.measureText(weeklyHours)
        canvas.drawText("h", pad + hw + width*0.01f, hoursY, unitPaint)

        // Growth Pill (Below Hours)
        val pillY = hoursY + width * 0.04f
        val pillH = width * 0.065f
        val growthParts = weeklyGrowth.split(" ")
        val mainGrowth = growthParts.getOrNull(0) ?: "+0%" // "+12%"
        
        // Use the smart insight if available, otherwise fallback to "vs last week"
        val contextText = if (weeklyInsight.isNotEmpty()) weeklyInsight else if (growthParts.size > 1) "vs last week" else ""

        val pillTextPaint = Paint().apply {
            color = TEAL_PRIMARY // Use primary color for text
            textSize = width * 0.032f
            typeface = Typeface.create("sans-serif-bold", Typeface.NORMAL)
            isAntiAlias = true
        }
        val ctxTextPaint = Paint().apply {
            color = ColorUtils.setAlphaComponent(Color.WHITE, 160) // Slightly brighter
            textSize = width * 0.032f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            isAntiAlias = true
        }
        
        val gpW = pillTextPaint.measureText(mainGrowth)
        // val cpW = ctxTextPaint.measureText(contextText) // Unused
        // val pillGap = width * 0.02f // Unused
        
        // Draw just the text, maybe a small dot indicator?
        // Let's do: [^] +12%  •  Most active on Fridays
        val arrowIconSize = width * 0.04f
        val aw = arrowIconSize
        
        drawIcon(
            canvas, context,
            if (mainGrowth.startsWith("+")) R.drawable.ic_widget_trending_up else R.drawable.ic_widget_trending_down,
            pad, pillY + pillH / 2 - arrowIconSize / 2, arrowIconSize, TEAL_PRIMARY,
        )
        canvas.drawText(mainGrowth, pad + aw + width*0.01f, pillY + pillH/2 + pillTextPaint.textSize/3, pillTextPaint)
        
        if (contextText.isNotEmpty()) {
             val startCtx = pad + aw + width*0.01f + gpW + width*0.03f
             
             // Draw bullet separator
             canvas.drawText("•", startCtx - width*0.02f, pillY + pillH/2 + ctxTextPaint.textSize/3, ctxTextPaint)
             
             canvas.drawText(contextText, startCtx, pillY + pillH/2 + ctxTextPaint.textSize/3, ctxTextPaint)
        }

        // 4. Top Artist Floating Pill (Top Right)
        val artistPillH = width * 0.09f // Slightly larger
        val artistPillPad = width * 0.015f
        val maxWidth = width * 0.45f
        
        // Measure text to size pill
        val artNamePaint = Paint().apply {
             color = Color.WHITE
             textSize = width * 0.032f
             typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
             isAntiAlias = true
        }
        val labelW = artNamePaint.measureText(topArtistName)
        val avatarSize = (artistPillH - artistPillPad*2)
        val pillW = (avatarSize + artistPillPad*3 + labelW).coerceAtMost(maxWidth)
        
        val pillRight = width - pad
        val pillTop = pad
        val pillRect = RectF(pillRight - pillW, pillTop, pillRight, pillTop + artistPillH)
        
        // Glassy Background
        val pillBg = Paint().apply {
            color = ColorUtils.setAlphaComponent(Color.WHITE, 20)
            isAntiAlias = true
        }
        val pillBorder = Paint().apply {
             color = ColorUtils.setAlphaComponent(Color.WHITE, 40)
             style = Paint.Style.STROKE
             strokeWidth = 1.5f
             isAntiAlias = true
        }
        canvas.drawRoundRect(pillRect, artistPillH, artistPillH, pillBg)
        canvas.drawRoundRect(pillRect, artistPillH, artistPillH, pillBorder)
        
        // Avatar
        val avCx = pillRect.left + artistPillPad + avatarSize/2
        val avCy = pillRect.centerY()
        val avRadius = avatarSize/2
        
         if (artistImagePath != null) {
            try {
                val artBitmap = decodeBitmapSafe(artistImagePath, (avatarSize * 2).toInt().coerceAtLeast(256))
                if (artBitmap != null) {
                    val cropped = cropBitmap(artBitmap, avatarSize.toInt(), avatarSize.toInt())
                     val layerId = canvas.save()
                    val clipPath = Path().apply {
                        addCircle(avCx, avCy, avRadius, Path.Direction.CW)
                    }
                    canvas.clipPath(clipPath)
                    canvas.drawBitmap(cropped, avCx - avRadius, avCy - avRadius, null)
                    canvas.restoreToCount(layerId)
                    
                    // Border
                    canvas.drawCircle(avCx, avCy, avRadius, pillBorder)
                }
            } catch (_: Exception) {}
        } else {
            val placePaint = Paint().apply { color = ColorUtils.setAlphaComponent(Color.WHITE, 50); isAntiAlias = true }
             canvas.drawCircle(avCx, avCy, avRadius, placePaint)
        }
        
        // Name (truncated with gradient fade if needed, but here just clip)
        val textLayoutX = avCx + avRadius + artistPillPad
        val maxTextW = pillRect.right - textLayoutX - artistPillPad
        
        // Manual truncation
        var displayName = topArtistName
        if (artNamePaint.measureText(displayName) > maxTextW) {
             // quick truncate logic
             while (displayName.length > 3 && artNamePaint.measureText(displayName + "...") > maxTextW) {
                 displayName = displayName.dropLast(1)
             }
             displayName += "..."
        }
        
        canvas.drawText(displayName, textLayoutX, pillRect.centerY() + artNamePaint.textSize*0.35f, artNamePaint)
        
        return bitmap
    }

    // Keep old method name for compatibility
    fun generateDashboardPulse(
        context: Context, width: Int, height: Int,
        weeklyHours: String, weeklyGrowth: String, chartData: List<Float>
    ): Bitmap {
        return generateDashboardCard(context, width, height, weeklyHours, weeklyGrowth, "", chartData, "No Data", null)
    }
    // ──────────────────────────────────────────────────────────────
    // Widgets 8 & 9 — the two Today-screen cards
    //
    //   8. Total listening time: hero numeral, three-stat strip, day-over-day bar.
    //   9. Listening rhythm: 24-hour bars, peak pill, quadrant totals.
    //
    // Both reflow for the shape a home screen actually hands them. The in-app
    // cards are tall columns; at 4x2 the same stack squeezes every number below
    // legibility, so the wide form moves the stat strip beside the numeral and
    // the square form keeps the column.
    // ──────────────────────────────────────────────────────────────

    /** Neutral near-black card surface, matching the in-app Obsidian variant. */
    private val CARD_SURFACE_TOP = Color.parseColor("#171B19")
    private val CARD_SURFACE_BOTTOM = Color.parseColor("#090B0A")

    /** "10 PM" from a 24-hour clock value — the peak pill's time label. */
    private fun formatWidgetHourLabel(hour: Int): String {
        val hourOfClock = when (val twelveHour = hour % 12) {
            0 -> 12
            else -> twelveHour
        }
        return "$hourOfClock ${if (hour >= 12) "PM" else "AM"}"
    }

    private fun cardTextPaint(
        textColor: Int,
        size: Float,
        family: String = "sans-serif",
        style: Int = Typeface.NORMAL,
        spacing: Float = 0f,
        align: Paint.Align = Paint.Align.LEFT,
    ): Paint = Paint().apply {
        color = textColor
        textSize = size
        typeface = Typeface.create(family, style)
        isAntiAlias = true
        letterSpacing = spacing
        textAlign = align
    }

    /** Card backdrop: vertical near-black wash, one accent bloom, film grain. */
    private fun drawCardBackdrop(
        canvas: Canvas,
        width: Int,
        height: Int,
        bloomX: Float,
        bloomY: Float,
    ) {
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                CARD_SURFACE_TOP, CARD_SURFACE_BOTTOM, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val bloomRadius = width * 0.7f
        val bloomPaint = Paint().apply {
            shader = RadialGradient(
                bloomX, bloomY, bloomRadius,
                ColorUtils.setAlphaComponent(TEAL_PRIMARY, 26),
                Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(bloomX, bloomY, bloomRadius, bloomPaint)
        drawNoiseTexture(canvas, width, height)
    }

    /** Trims `text` with an ellipsis so it never runs past `maxWidth`. */
    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return text
        var trimmed = text
        while (trimmed.length > 1 && paint.measureText("$trimmed…") > maxWidth) {
            trimmed = trimmed.dropLast(1)
        }
        return "$trimmed…"
    }

    private fun drawDashedLine(
        canvas: Canvas,
        startX: Float,
        endX: Float,
        y: Float,
        color: Int,
        dashLength: Float,
        gapLength: Float,
        strokeWidth: Float,
    ) {
        val paint = Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            isAntiAlias = true
            pathEffect = DashPathEffect(floatArrayOf(dashLength, gapLength), 0f)
        }
        canvas.drawLine(startX, y, endX, y, paint)
    }

    /**
     * Small trend marker for the day-over-day chip, drawn as a path rather than a
     * glyph so it renders identically with every system font. [direction] is 1 for
     * up, -1 for down and 0 for flat.
     */
    private fun drawTrendMark(canvas: Canvas, cx: Float, cy: Float, size: Float, direction: Int, color: Int) {
        val half = size * 0.42f
        val paint = Paint().apply { this.color = color; isAntiAlias = true }
        if (direction == 0) {
            // Flat: a plain arrow shaft with a small head, like the in-app flat chip.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (size * 0.16f).coerceAtLeast(1.2f)
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(cx - half, cy, cx + half - size * 0.22f, cy, paint)
            paint.style = Paint.Style.FILL
            canvas.drawPath(
                Path().apply {
                    moveTo(cx + half, cy)
                    lineTo(cx + half - size * 0.34f, cy - half * 0.62f)
                    lineTo(cx + half - size * 0.34f, cy + half * 0.62f)
                    close()
                },
                paint
            )
            return
        }
        val path = Path().apply {
            if (direction > 0) {
                moveTo(cx, cy - half)
                lineTo(cx + half, cy + half)
                lineTo(cx - half, cy + half)
            } else {
                moveTo(cx, cy + half)
                lineTo(cx + half, cy - half)
                lineTo(cx - half, cy - half)
            }
            close()
        }
        canvas.drawPath(path, paint)
    }

    /** Sun or crescent, so the peak pill carries its day/night cue without an emoji. */
    private fun drawDayNightMark(canvas: Canvas, cx: Float, cy: Float, radius: Float, day: Boolean, color: Int) {
        val paint = Paint().apply { this.color = color; isAntiAlias = true }
        if (day) {
            canvas.drawCircle(cx, cy, radius * 0.55f, paint)
            val rayPaint = Paint().apply {
                this.color = color
                this.strokeWidth = radius * 0.16f
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
            }
            for (index in 0 until 8) {
                val angle = Math.toRadians(index * 45.0)
                val cos = kotlin.math.cos(angle).toFloat()
                val sin = kotlin.math.sin(angle).toFloat()
                canvas.drawLine(
                    cx + cos * radius * 0.72f, cy + sin * radius * 0.72f,
                    cx + cos * radius, cy + sin * radius,
                    rayPaint
                )
            }
        } else {
            val crescent = Path().apply { addCircle(cx, cy, radius * 0.92f, Path.Direction.CW) }
            val bite = Path().apply { addCircle(cx + radius * 0.55f, cy - radius * 0.42f, radius * 0.86f, Path.Direction.CW) }
            crescent.op(bite, Path.Op.DIFFERENCE)
            canvas.drawPath(crescent, paint)
        }
    }

    /**
     * Today's total listening time as a widget card.
     *
     * [todayShort] / [yesterdayShort] are display strings such as "1.1h" and "8m"; the raw
     * millisecond totals drive the proportional comparison bar, because rounding to minutes
     * first would flatten small days into a full-width bar.
     */
    fun generateTodayTotalCard(
        context: Context,
        width: Int,
        height: Int,
        heroTime: String,
        playCount: Int,
        trackCount: Int,
        albumCount: Int,
        artistCount: Int,
        avgSession: String,
        todayShort: String,
        yesterdayShort: String,
        growthPercent: Float,
        todayMs: Long,
        yesterdayMs: Long,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawCardBackdrop(canvas, width, height, width * 0.16f, height * 0.02f)

        val pad = (minOf(width, height) * 0.10f).coerceIn(12f, 52f)
        val contentW = (width - pad * 2f).coerceAtLeast(1f)
        val wide = width >= height * 1.7f

        val accent = TEAL_PRIMARY
        val mutedInk = ColorUtils.setAlphaComponent(ON_SURFACE, 150)
        val faintInk = ColorUtils.setAlphaComponent(ON_SURFACE, 105)
        val hairInk = ColorUtils.setAlphaComponent(ON_SURFACE, 30)

        // Type scale — derived from the widget's own width so a 2x2 and a 6x6
        // both look deliberate instead of one being a scaled photograph of the other.
        val kickerSize = (width * 0.030f).coerceIn(9f, 26f)
        val chipSize = (width * 0.026f).coerceIn(8f, 22f)
        val subSize = (width * 0.030f).coerceIn(9f, 24f)
        val statLabelSize = (width * 0.023f).coerceIn(7.5f, 20f)
        val statValueSize = (width * 0.048f).coerceIn(13f, 38f)
        val axisSize = (width * 0.024f).coerceIn(7.5f, 20f)

        // ── Bottom band: today vs yesterday, anchored to the card's foot
        val barHeight = (height * 0.020f).coerceIn(5f, 12f)
        val axisBaseline = height - pad
        val barBottom = axisBaseline - axisSize * 1.25f
        val showsComparison = todayMs > 0L || yesterdayMs > 0L
        val bodyBottom = if (showsComparison) barBottom - barHeight - pad * 0.45f else height - pad

        // ── Top row: kicker on the left, day-over-day chip on the right
        val kickerPaint = cardTextPaint(mutedInk, kickerSize, "sans-serif-medium", Typeface.NORMAL, 0.16f)
        val topCenterY = pad + kickerSize * 0.55f
        val dotRadius = kickerSize * 0.20f
        val kickerTextX = pad + dotRadius * 2f + kickerSize * 0.40f
        val kickerLabel = "TOTAL LISTENING TIME"
        val kickerW = kickerTextX - pad + kickerPaint.measureText(kickerLabel)

        // Mirrors the in-app DayOverDayChip: a sub-half-percent move reads as flat
        // ("Same as yesterday"), falls are muted rather than accented.
        val growthDirection = when {
            growthPercent > 0.5f -> 1
            growthPercent < -0.5f -> -1
            else -> 0
        }
        val chipInk = if (growthDirection > 0) accent else ColorUtils.setAlphaComponent(ON_SURFACE, 185)
        val growthLabel = if (growthDirection == 0) {
            "Same as yesterday"
        } else {
            String.format(Locale.US, "%+.0f%%", growthPercent)
        }
        val chipPaint = cardTextPaint(chipInk, chipSize, "sans-serif-medium", Typeface.BOLD)
        val chipGlyphW = chipSize * 1.05f
        val chipPadH = chipSize * 0.85f
        val chipH = (chipSize * 2.05f).coerceAtMost(height * 0.22f)
        val chipRoom = contentW - kickerW - pad * 0.5f
        val chipFullLabel = if (growthDirection == 0) growthLabel else "$growthLabel vs yesterday"
        val chipLabel = when {
            chipPaint.measureText(chipFullLabel) + chipGlyphW + chipPadH * 2f <= chipRoom -> chipFullLabel
            growthDirection != 0 &&
                chipPaint.measureText(growthLabel) + chipGlyphW + chipPadH * 2f <= chipRoom -> growthLabel
            else -> ""
        }
        val showsChip = chipLabel.isNotEmpty()
        val chipW = chipPaint.measureText(chipLabel) + chipGlyphW + chipPadH * 2f
        val chipRect = RectF(width - pad - chipW, topCenterY - chipH / 2f, width - pad, topCenterY + chipH / 2f)

        if (showsChip) {
            canvas.drawRoundRect(
                chipRect, chipH / 2f, chipH / 2f,
                Paint().apply { color = ColorUtils.setAlphaComponent(chipInk, 30); isAntiAlias = true }
            )
            canvas.drawRoundRect(
                chipRect, chipH / 2f, chipH / 2f,
                Paint().apply {
                    this.color = ColorUtils.setAlphaComponent(chipInk, 95)
                    style = Paint.Style.STROKE
                    strokeWidth = 1.4f
                    isAntiAlias = true
                }
            )
            drawTrendMark(
                canvas,
                chipRect.left + chipPadH + chipGlyphW * 0.35f,
                chipRect.centerY(),
                chipSize * 0.62f,
                direction = growthDirection,
                color = chipInk
            )
            canvas.drawText(
                chipLabel,
                chipRect.left + chipPadH + chipGlyphW,
                chipRect.centerY() + chipSize * 0.35f,
                chipPaint
            )
        }

        canvas.drawCircle(pad + dotRadius, topCenterY - kickerSize * 0.05f, dotRadius, Paint().apply { color = accent; isAntiAlias = true })
        canvas.drawText(kickerLabel, kickerTextX, topCenterY + kickerSize * 0.35f, kickerPaint)

        // ── Hero numeral
        val topBlockBottom = pad + maxOf(kickerSize * 1.15f, chipH)
        val heroPreferred = if (wide) height * 0.30f else height * 0.28f
        val heroPaint = cardTextPaint(ON_SURFACE, heroPreferred.coerceAtLeast(16f), "serif", Typeface.BOLD, -0.01f)
        val heroMaxW = if (wide) contentW * 0.46f else contentW
        var heroGuard = 0
        while (heroPaint.measureText(heroTime) > heroMaxW && heroPaint.textSize > 12f && heroGuard < 90) {
            heroPaint.textSize *= 0.94f
            heroGuard++
        }
        val heroBaseline = topBlockBottom + heroPaint.textSize * 0.92f
        canvas.drawText(heroTime, pad, heroBaseline, heroPaint)

        val subPaint = cardTextPaint(faintInk, subSize, "sans-serif")
        val playsLine = when {
            playCount <= 0 -> "No plays recorded yet today"
            playCount == 1 -> "1 play recorded across today"
            else -> "$playCount plays recorded across today"
        }
        val subBaseline = heroBaseline + subSize * 1.45f
        canvas.drawText(ellipsize(playsLine, subPaint, contentW), pad, subBaseline, subPaint)

        // ── Stat strip
        val stats = listOf(
            Triple("TRACKS", "$trackCount", if (albumCount == 1) "1 album" else "$albumCount albums"),
            Triple("ARTISTS", "$artistCount", "unique"),
            Triple("AVG SESSION", avgSession, "per listen"),
        )
        val hairPaint = Paint().apply {
            color = hairInk
            strokeWidth = 1.2f
            isAntiAlias = true
        }

        if (wide) {
            // Numeral left, stats stacked right — a single-line list stays readable
            // where three narrow columns would clip "AVG SESSION" to "AVG…".
            val dividerX = pad + contentW * 0.52f
            canvas.drawLine(dividerX, topBlockBottom, dividerX, bodyBottom, hairPaint)

            val rightX = dividerX + pad * 0.55f
            val rightW = (width - pad - rightX).coerceAtLeast(1f)
            val rowsTop = topBlockBottom
            val rowH = ((bodyBottom - rowsTop) / 3f).coerceAtLeast(1f)
            stats.forEachIndexed { index, stat ->
                val rowTop = rowsTop + rowH * index
                val labelPaint = cardTextPaint(faintInk, statLabelSize, "sans-serif-medium", Typeface.NORMAL, 0.18f)
                val labelBaseline = rowTop + statLabelSize * 1.15f
                canvas.drawText(ellipsize(stat.first, labelPaint, rightW), rightX, labelBaseline, labelPaint)

                val valueSize = statValueSize.coerceAtMost(rowH * 0.42f).coerceAtLeast(9f)
                val valuePaint = cardTextPaint(
                    if (stat.first == "AVG SESSION") accent else ON_SURFACE,
                    valueSize, "serif", Typeface.BOLD
                )
                val valueBaseline = labelBaseline + valueSize * 0.95f + pad * 0.18f
                val valueText = ellipsize(stat.second, valuePaint, rightW * 0.6f)
                canvas.drawText(valueText, rightX, valueBaseline, valuePaint)

                val subPaintInline = cardTextPaint(faintInk, subSize * 0.82f, "sans-serif")
                val subX = rightX + valuePaint.measureText(valueText) + pad * 0.28f
                if (subX < width - pad) {
                    canvas.drawText(
                        ellipsize(stat.third, subPaintInline, width - pad - subX),
                        subX,
                        valueBaseline - valueSize * 0.06f,
                        subPaintInline
                    )
                }
            }
        } else {
            // Column layout: hairline, three columns split by vertical rules.
            val dividerY = subBaseline + subSize * 0.85f
            canvas.drawLine(pad, dividerY, width - pad, dividerY, hairPaint)

            val statsTop = dividerY + pad * 0.30f
            val bandH = bodyBottom - statsTop
            val needed = statLabelSize + statValueSize * 0.95f
            if (bandH > needed) {
                val colW = contentW / 3f
                stats.forEachIndexed { index, stat ->
                    val colX = pad + colW * index
                    if (index > 0) {
                        canvas.drawLine(colX, statsTop, colX, bodyBottom, hairPaint)
                    }
                    val cellX = colX + (if (index == 0) 0f else pad * 0.35f)
                    val cellW = colW - pad * 0.35f
                    val labelPaint = cardTextPaint(faintInk, statLabelSize, "sans-serif-medium", Typeface.NORMAL, 0.18f)
                    val labelBaseline = statsTop + statLabelSize * 1.15f
                    canvas.drawText(ellipsize(stat.first, labelPaint, cellW), cellX, labelBaseline, labelPaint)

                    val valueSize = statValueSize.coerceAtMost(bandH * 0.45f).coerceAtLeast(9f)
                    val valuePaint = cardTextPaint(
                        if (stat.first == "AVG SESSION") accent else ON_SURFACE,
                        valueSize, "serif", Typeface.BOLD
                    )
                    val valueBaseline = labelBaseline + valueSize * 0.95f + pad * 0.12f
                    val valueText = ellipsize(stat.second, valuePaint, cellW * 0.62f)
                    canvas.drawText(valueText, cellX, valueBaseline, valuePaint)

                    val subPaintInline = cardTextPaint(faintInk, subSize * 0.78f, "sans-serif")
                    val subX = cellX + valuePaint.measureText(valueText) + pad * 0.20f
                    if (subX < colX + colW - pad * 0.2f) {
                        canvas.drawText(
                            ellipsize(stat.third, subPaintInline, colX + colW - subX),
                            subX,
                            valueBaseline - valueSize * 0.04f,
                            subPaintInline
                        )
                    }
                }
            }
        }

        // ── Day-over-day bar: proportion comes from raw milliseconds so a light day
        //    still reads as a light day rather than rounding up to a full track.
        if (showsComparison) {
            val track = RectF(pad, barBottom - barHeight, width - pad, barBottom)
            canvas.drawRoundRect(
                track, barHeight / 2f, barHeight / 2f,
                Paint().apply { color = ColorUtils.setAlphaComponent(ON_SURFACE, 32); isAntiAlias = true }
            )

            val total = todayMs + yesterdayMs
            if (total > 0L) {
                val fraction = (todayMs.toFloat() / total).coerceIn(0.04f, 0.96f)
                val fillRight = track.left + track.width() * fraction
                val fillPaint = Paint().apply {
                    shader = LinearGradient(
                        track.left, 0f, fillRight, 0f,
                        ColorUtils.setAlphaComponent(accent, 185), accent, Shader.TileMode.CLAMP
                    )
                    isAntiAlias = true
                }
                canvas.drawRoundRect(
                    RectF(track.left, track.top, fillRight, track.bottom),
                    barHeight / 2f, barHeight / 2f, fillPaint
                )
            }

            val todayPaint = cardTextPaint(accent, axisSize, "sans-serif-medium", Typeface.BOLD, 0.10f)
            val yesterdayPaint = cardTextPaint(
                ColorUtils.setAlphaComponent(ON_SURFACE, 95),
                axisSize, "sans-serif-medium", Typeface.BOLD, 0.10f, Paint.Align.RIGHT
            )
            val axisDotRadius = axisSize * 0.20f
            canvas.drawCircle(
                pad + axisDotRadius, axisBaseline - axisSize * 0.32f, axisDotRadius,
                Paint().apply { color = accent; isAntiAlias = true }
            )
            canvas.drawText(
                "TODAY · $todayShort",
                pad + axisDotRadius * 2f + axisSize * 0.38f,
                axisBaseline,
                todayPaint
            )
            canvas.drawText("YESTERDAY · $yesterdayShort", width - pad, axisBaseline, yesterdayPaint)
        }

        return bitmap
    }

    /**
     * Listening rhythm as a widget card: 24 hourly bars over a dashed baseline, the peak
     * hour called out in the pill, and the four day-quadrant totals underneath.
     *
     * [hourlyPlays] is expected to hold 24 entries (hour 0..23); a short list is padded
     * with zeros rather than throwing, because a stale preference write from an older
     * build should never take the widget down.
     */
    fun generateListeningRhythmCard(
        context: Context,
        width: Int,
        height: Int,
        hourlyPlays: List<Int>,
        currentHour: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawCardBackdrop(canvas, width, height, width * 0.88f, height * 0.96f)

        val pad = (minOf(width, height) * 0.10f).coerceIn(12f, 52f)
        val contentW = (width - pad * 2f).coerceAtLeast(1f)

        val kickerSize = (width * 0.030f).coerceIn(9f, 26f)
        val pillSize = (width * 0.026f).coerceIn(8f, 22f)
        val axisSize = (width * 0.024f).coerceIn(7.5f, 19f)
        val quadLabelSize = (width * 0.021f).coerceIn(7f, 17f)
        val quadValueSize = (width * 0.040f).coerceIn(11f, 30f)

        val plays = (0..23).map { hourlyPlays.getOrElse(it) { 0 }.coerceAtLeast(0) }
        val maxPlays = (plays.maxOrNull() ?: 0).coerceAtLeast(1)
        val peakHour = (0..23).filter { plays[it] > 0 }.maxByOrNull { plays[it] }
        val totalPlays = plays.sum()

        val mutedInk = ColorUtils.setAlphaComponent(ON_SURFACE, 150)
        val faintInk = ColorUtils.setAlphaComponent(ON_SURFACE, 105)
        val ghostInk = ColorUtils.setAlphaComponent(ON_SURFACE, 80)
        val accent = TEAL_PRIMARY

        // ── Top row: section kicker left, peak pill right
        val topCenterY = pad + kickerSize * 0.55f
        val pillH = (pillSize * 2.1f).coerceAtMost(height * 0.24f)
        val topBlockBottom = pad + maxOf(kickerSize * 1.2f, pillH)

        val pillPaint = cardTextPaint(TERTIARY_PINK, pillSize, "sans-serif-medium", Typeface.BOLD)
        val peakLabel = peakHour?.let {
            "${formatWidgetHourLabel(it)} · ${plays[it]} ${if (plays[it] == 1) "play" else "plays"}"
        }
        val pillGlyphW = pillSize * 1.3f
        val pillPadH = pillSize * 0.85f
        val pillW = if (peakLabel != null) pillPaint.measureText(peakLabel) + pillGlyphW + pillPadH * 2f else 0f
        val pillMaxW = contentW * 0.58f

        if (peakLabel != null && pillW <= pillMaxW) {
            val pillRect = RectF(width - pad - pillW, topCenterY - pillH / 2f, width - pad, topCenterY + pillH / 2f)
            canvas.drawRoundRect(
                pillRect, pillH / 2f, pillH / 2f,
                Paint().apply { color = ColorUtils.setAlphaComponent(TERTIARY_PINK, 34); isAntiAlias = true }
            )
            canvas.drawRoundRect(
                pillRect, pillH / 2f, pillH / 2f,
                Paint().apply {
                    this.color = ColorUtils.setAlphaComponent(TERTIARY_PINK, 95)
                    style = Paint.Style.STROKE
                    strokeWidth = 1.4f
                    isAntiAlias = true
                }
            )
            drawDayNightMark(
                canvas,
                pillRect.left + pillPadH + pillGlyphW * 0.42f,
                pillRect.centerY(),
                pillSize * 0.52f,
                day = peakHour in 6..17,
                color = TERTIARY_PINK
            )
            canvas.drawText(
                ellipsize(peakLabel, pillPaint, pillMaxW - pillGlyphW - pillPadH * 2f),
                pillRect.left + pillPadH + pillGlyphW,
                pillRect.centerY() + pillSize * 0.35f,
                pillPaint
            )
        }

        // Section numeral + rule + label, dropping the numeral when the row is cramped.
        val kickerPaint = cardTextPaint(mutedInk, kickerSize, "sans-serif-medium", Typeface.NORMAL, 0.16f)
        val numeralW = kickerPaint.measureText("01")
        val labelW = kickerPaint.measureText("LISTENING RHYTHM")
        val showsNumeral = numeralW + labelW + kickerSize * 3.4f <= contentW - (if (pillW <= pillMaxW) pillW else 0f) - pad * 0.5f
        var kickerX = pad
        if (showsNumeral) {
            canvas.drawText(
                "01", kickerX, topCenterY + kickerSize * 0.35f,
                cardTextPaint(ColorUtils.setAlphaComponent(ON_SURFACE, 200), kickerSize, "sans-serif-medium", Typeface.BOLD, 0.16f)
            )
            kickerX += numeralW + kickerSize * 0.75f
            canvas.drawLine(
                kickerX, topCenterY, kickerX + kickerSize * 1.1f, topCenterY,
                Paint().apply {
                    this.color = ColorUtils.setAlphaComponent(ON_SURFACE, 60)
                    strokeWidth = 1.2f
                    isAntiAlias = true
                }
            )
            kickerX += kickerSize * 1.1f + kickerSize * 0.75f
        }
        canvas.drawText("LISTENING RHYTHM", kickerX, topCenterY + kickerSize * 0.35f, kickerPaint)

        // ── Vertical bands: quadrant strip and axis row are measured from the foot up
        val quadH = (height * 0.20f).coerceIn(24f, 62f)
        val roomForQuads = height - topBlockBottom - pad - axisSize * 2.2f - height * 0.24f
        val showsQuads = roomForQuads >= quadH
        val axisBaseline = height - pad - (if (showsQuads) quadH + pad * 0.45f else 0f)
        val chartBottom = axisBaseline - axisSize * 1.35f
        val chartTop = topBlockBottom + pad * 0.30f
        val chartH = (chartBottom - chartTop).coerceAtLeast(8f)

        val gap = (contentW * 0.006f).coerceIn(1.2f, 5f)
        val barW = ((contentW - gap * 23f) / 24f).coerceAtLeast(1.5f)
        val barCorner = minOf(barW * 0.45f, 3.5f)

        drawDashedLine(
            canvas, pad, width - pad, chartBottom,
            ColorUtils.setAlphaComponent(ON_SURFACE, 46),
            barW * 0.85f, barW * 0.85f, (height * 0.005f).coerceIn(1f, 2.2f)
        )

        for (hour in 0..23) {
            val barX = pad + hour * (barW + gap)
            val barRect = RectF(barX, chartBottom, barX + barW, chartBottom)
            val hourPlays = plays[hour]
            val stubPaint = Paint().apply {
                this.color = ColorUtils.setAlphaComponent(ON_SURFACE, 22)
                isAntiAlias = true
            }

            if (hourPlays <= 0) {
                barRect.top = chartBottom - 2.5f
                canvas.drawRoundRect(barRect, 1f, 1f, stubPaint)
                continue
            }

            val ratio = hourPlays.toFloat() / maxPlays
            barRect.top = chartBottom - (chartH * ratio).coerceAtLeast(4f)
            val barColor = when {
                hour == peakHour -> TERTIARY_PINK
                hour == currentHour -> DYNAMIC_ON_CONTAINER
                else -> ColorUtils.setAlphaComponent(TEAL_PRIMARY, (95 + 125 * ratio).toInt().coerceIn(0, 255))
            }
            val barPaint = Paint().apply {
                this.color = barColor
                isAntiAlias = true
            }
            canvas.drawRoundRect(barRect, barCorner, barCorner, barPaint)
            // Square the foot so bars sit on the baseline instead of floating on
            // rounded corners that visually lift them off it.
            canvas.drawRect(barRect.left, chartBottom - barCorner, barRect.right, chartBottom, barPaint)
        }

        if (totalPlays == 0) {
            val hintPaint = cardTextPaint(ghostInk, axisSize * 1.05f, "sans-serif", Typeface.NORMAL, 0f, Paint.Align.CENTER)
            canvas.drawText(
                "No listening recorded yet today",
                width / 2f,
                chartTop + chartH * 0.45f,
                hintPaint
            )
        }

        // ── 6-hour axis ticks
        val axisPaint = cardTextPaint(ColorUtils.setAlphaComponent(ON_SURFACE, 95), axisSize, "sans-serif-medium", Typeface.BOLD, 0.10f)
        fun hourCenterX(hour: Int): Float = pad + hour * (barW + gap) + barW / 2f
        canvas.drawText("12 AM", pad, axisBaseline, axisPaint)
        canvas.drawText(
            "6 AM", hourCenterX(6), axisBaseline,
            cardTextPaint(ColorUtils.setAlphaComponent(ON_SURFACE, 95), axisSize, "sans-serif-medium", Typeface.BOLD, 0.10f, Paint.Align.CENTER)
        )
        canvas.drawText(
            "12 PM", hourCenterX(12), axisBaseline,
            cardTextPaint(ColorUtils.setAlphaComponent(ON_SURFACE, 95), axisSize, "sans-serif-medium", Typeface.BOLD, 0.10f, Paint.Align.CENTER)
        )
        canvas.drawText(
            "6 PM", width - pad, axisBaseline,
            cardTextPaint(ColorUtils.setAlphaComponent(ON_SURFACE, 95), axisSize, "sans-serif-medium", Typeface.BOLD, 0.10f, Paint.Align.RIGHT)
        )

        // ── Quadrant totals
        if (showsQuads) {
            val bandTop = axisBaseline + pad * 0.45f
            val band = RectF(pad, bandTop, width - pad, bandTop + quadH)
            val bandRadius = minOf(quadH * 0.28f, contentW * 0.02f).coerceAtLeast(6f)
            canvas.drawRoundRect(
                band, bandRadius, bandRadius,
                Paint().apply { color = ColorUtils.setAlphaComponent(ON_SURFACE, 16); isAntiAlias = true }
            )
            canvas.drawRoundRect(
                band, bandRadius, bandRadius,
                Paint().apply {
                    this.color = ColorUtils.setAlphaComponent(ON_SURFACE, 26)
                    style = Paint.Style.STROKE
                    strokeWidth = 1.2f
                    isAntiAlias = true
                }
            )

            val quadrants = listOf(
                "NIGHT" to (0..5).sumOf { plays[it] },
                "MORNING" to (6..11).sumOf { plays[it] },
                "AFTERNOON" to (12..17).sumOf { plays[it] },
                "EVENING" to (18..23).sumOf { plays[it] },
            )
            val cellW = band.width() / 4f
            quadrants.forEachIndexed { index, (label, value) ->
                val centerX = band.left + cellW * index + cellW / 2f
                val labelPaint = cardTextPaint(
                    ghostInk, quadLabelSize, "sans-serif-medium", Typeface.BOLD, 0.16f, Paint.Align.CENTER
                )
                canvas.drawText(
                    ellipsize(label, labelPaint, cellW - pad * 0.2f),
                    centerX,
                    band.top + quadH * 0.36f,
                    labelPaint
                )
                val valuePaint = cardTextPaint(
                    if (value > 0) ON_SURFACE else ColorUtils.setAlphaComponent(ON_SURFACE, 70),
                    quadValueSize.coerceAtMost(quadH * 0.34f).coerceAtLeast(9f),
                    "serif", Typeface.BOLD, 0f, Paint.Align.CENTER
                )
                canvas.drawText("$value", centerX, band.top + quadH * 0.82f, valuePaint)
            }
        }

        return bitmap
    }

    // 48 px tile: divisible by both 4 (dot spacing) and 3 (the (x+y)%3 pattern), so tiling it
    // reproduces the old per-pixel loop on the same absolute coordinates.
    // ponytail: dots on a 48 px seam render as half-dots (sub-pixel at 0.8 px radius); fine for grain.
    private val noiseTile: Bitmap by lazy {
        val size = 48
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { tile ->
            val canvas = Canvas(tile)
            val paint = Paint().apply {
                color = Color.WHITE
                alpha = 15 // Very subtle
                style = Paint.Style.FILL
            }
            for (x in 0 until size step 4) {
                for (y in 0 until size step 4) {
                    if ((x + y) % 3 == 0) canvas.drawCircle(x.toFloat(), y.toFloat(), 0.8f, paint)
                }
            }
        }
    }

    // Draw a subtle noise/grain texture: one tiled draw instead of ~5k drawCircle per card.
    private fun drawNoiseTexture(canvas: Canvas, width: Int, height: Int) {
        val paint = Paint().apply {
            shader = BitmapShader(noiseTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    /**
     * Draws a tinted vector icon at [left],[top]. Emoji glyphs used to stand in for these; a
     * device whose emoji font lacks a glyph renders tofu, and the glyph colour ignores the
     * card's palette.
     */
    private fun drawIcon(
        canvas: Canvas,
        context: Context,
        resId: Int,
        left: Float,
        top: Float,
        size: Float,
        tint: Int,
    ) {
        val icon = context.getDrawable(resId) ?: return
        icon.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
        icon.setTint(tint)
        icon.draw(canvas)
    }

    /**
     * Center-crops a bitmap to fill the given width/height without distortion.
     */
    private fun cropBitmap(source: Bitmap, reqW: Int, reqH: Int): Bitmap {
        val srcW = source.width
        val srcH = source.height
        val scale = (reqW.toFloat() / srcW).coerceAtLeast(reqH.toFloat() / srcH)
        
        val scaledW = (srcW * scale).toInt()
        val scaledH = (srcH * scale).toInt()
        
        val scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)
        
        val x = (scaledW - reqW) / 2
        val y = (scaledH - reqH) / 2
        
        // Clamp crop bounds within source dimensions
        val safeX = x.coerceIn(0, scaledW - reqW)
        val safeY = y.coerceIn(0, scaledH - reqH)
        
        return Bitmap.createBitmap(scaled, safeX, safeY, reqW, reqH)
    }

    /**
     * Draws a smooth Bezier wave chart filled with a gradient.
     */
    private fun drawWaveChart(
        canvas: Canvas,
        data: List<Float>,
        rect: RectF,
        startColor: Int,
        endColor: Int
    ) {
        if (data.isEmpty()) return

        val path = Path()
        val width = rect.width()
        val height = rect.height()
        val stepX = width / (data.size - 1).coerceAtLeast(1)
        
        val maxVal = data.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val minVal = data.minOrNull() ?: 0f
        val range = (maxVal - minVal).coerceAtLeast(0.1f)

        // Start at bottom-left
        path.moveTo(rect.left, rect.bottom)
        
        // Calculate points
        val points = data.mapIndexed { i, value ->
            val x = rect.left + i * stepX
            val normalized = (value - minVal) / range
            val y = rect.bottom - (normalized * height)
            x to y
        }

        // Draw smooth curve to first point
        path.lineTo(points[0].first, points[0].second)

        // Cubic Bezier to subsequent points
        for (i in 0 until points.size - 1) {
            val (p0x, p0y) = points[i]
            val (p1x, p1y) = points[i + 1]
            
            val ck = 0.5f // tension
            val cp1x = p0x + (p1x - p0x) * ck
            val cp1y = p0y
            val cp2x = p1x - (p1x - p0x) * ck
            val cp2y = p1y
            
            path.cubicTo(cp1x, cp1y, cp2x, cp2y, p1x, p1y)
        }

        // Close path at bottom-right
        path.lineTo(rect.right, rect.bottom)
        path.close()

        val paint = Paint().apply {
            shader = LinearGradient(
                rect.left, rect.top, rect.left, rect.bottom,
                startColor, endColor, Shader.TileMode.CLAMP
            )
            isAntiAlias = true
        }

        canvas.drawPath(path, paint)
        
        // Stroke on top
        val strokePaint = Paint().apply {
            color = ColorUtils.setAlphaComponent(startColor, 200)
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        canvas.drawPath(path, strokePaint)
    }

    /**
     * Decodes with downsampling so no dimension exceeds `maxDimension` px, avoiding OOM on
     * extremely large images.
     */
    fun decodeBitmapSafe(path: String, maxDimension: Int = 1024): Bitmap? {
        return try {
            // Dimensions only: inJustDecodeBounds skips the pixel decode
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, options)

            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            var inSampleSize = 1
            val width = options.outWidth
            val height = options.outHeight
            if (width > maxDimension || height > maxDimension) {
                val halfWidth = width / 2
                val halfHeight = height / 2
                while (halfWidth / inSampleSize >= maxDimension &&
                    halfHeight / inSampleSize >= maxDimension) {
                    inSampleSize *= 2
                }
            }

            // Step 3: Decode with sample size
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            BitmapFactory.decodeFile(path, decodeOptions)
        } catch (_: Exception) {
            null
        }
    }
}
