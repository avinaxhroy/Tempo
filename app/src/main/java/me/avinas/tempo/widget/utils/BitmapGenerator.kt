package me.avinas.tempo.widget.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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

/**
 * Generates pixel-perfect bitmap renders for each Tempo widget,
 * matching the mockup designs exactly.
 */
object BitmapGenerator {

    // =========================================================================
    // Common Colors
    // =========================================================================
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

    // =========================================================================
    // Widget 1: Artist Spotlight ("Top Pick")
    // Dark teal card, circular artist image, "TOP PICK" badge, hours listened
    // =========================================================================
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
        
        // Use adaptive scaling
        drawScaledText(
            canvas, 
            artistName, 
            width / 2f, 
            nameY, 
            width * 0.9f, // Max width with padding
            namePaint
        )

        // 8. Clock icon + "Xh listened" (green, centered)
        val hoursTextPaint = Paint().apply {
            color = ColorUtils.setAlphaComponent(TEAL_PRIMARY, 230)
            textSize = width * 0.058f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val clockText = "⏱ ${hoursListened} listened"
        canvas.drawText(clockText, width / 2f, nameY + width * 0.1f, hoursTextPaint)

        return bitmap
    }

    // =========================================================================
    // Widget 2: Weekly Mix
    // Dark card, colorful album art icon, "Weekly Mix / Made for you", audio bars
    // =========================================================================
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
                val artBitmap = BitmapFactory.decodeFile(imagePath)
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
        // Use adaptive scaling
        drawScaledText(canvas, mixTitle, textX, titleY, width - textX - pad, titlePaint)
        drawScaledText(canvas, artistName, textX, titleY + width * 0.05f, width - textX - pad, subtitlePaint)

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

    // =========================================================================
    // Widget 3: Genre Heatmap (Treemap layout)
    // Blocks sized by percentage with gradient colors
    // =========================================================================
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
                // Dynamic scaling for long words
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

    // =========================================================================
    // Widget 4: Discovery Card
    // Purple glassmorphism, insight sentence, 3-column stats footer
    // =========================================================================
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
        val pillLabel = "✨ DIscovery"
        val ptw = pillTextPaint.measureText(pillLabel)
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
        
        canvas.drawText(
            pillLabel,
            pillRect.left + width * 0.03f,
            pillRect.centerY() - (pillTextPaint.descent() + pillTextPaint.ascent()) / 2,
            pillTextPaint
        )

        // 4. Arrow icon top-right
        val arrowPaint = Paint().apply {
            color = Color.parseColor("#D0BCFF")
            textSize = width * 0.06f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        canvas.drawText("↗", width - pad - width * 0.02f, pad + pillH * 0.75f, arrowPaint)

        // 5. Insight text centered
        // 5. Insight text centered
        val insightPaint = Paint().apply {
            color = Color.WHITE
            textSize = (width * 0.055f).coerceIn(24f, 60f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            isAntiAlias = true
            letterSpacing = -0.01f
            // Base metrics needed for helper
            shader = null 
        }
        
        // Paint for the highlighted artist name (Gradient)
        val hlPaint = Paint().apply {
            textSize = insightPaint.textSize
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAntiAlias = true
            shader = LinearGradient(0f, 0f, width.toFloat(), 0f,
               Color.parseColor("#D0BCFF"), Color.parseColor("#B39DDB"), Shader.TileMode.CLAMP)
        }
        
        val centerY = height * 0.45f
        // Max width for text block allowing for padding
        val maxTextW = width - (pad * 2.5f)
        val maxTextH = height * 0.35f 

        // Use adaptive multiline drawing
        drawDiscoveryText(
            canvas,
            discoveryText,
            artistName,
            width / 2f,
            centerY,
            maxTextW,
            maxTextH,
            insightPaint,
            hlPaint
        )

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

        // Helper to draw stat
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

    // =========================================================================
    // Widget 5: Milestone Card
    // Purple card, album art, track/artist, "#1 Most Played" badge
    // =========================================================================
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
                val artBitmap = BitmapFactory.decodeFile(imagePath)
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
        // Adaptive scaling for milestone text
        drawScaledText(
            canvas, 
            trackName, 
            width / 2f, 
            trackY, 
            width * 0.85f, 
            trackPaint
        )
        drawScaledText(
            canvas, 
            artistName, 
            width / 2f, 
            trackY + width * 0.06f, 
            width * 0.85f, 
            artistPaint
        )

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

        val displayBadge = if (isSpecialMilestone) "🏆 $badge" else "📈 $badge"
        val badgeTW = badgeTextPaint.measureText(displayBadge)
        val badgePH = badgeTextPaint.textSize + width * 0.03f
        val badgeLeft = (width - badgeTW) / 2 - width * 0.05f
        val badgeRight = (width + badgeTW) / 2 + width * 0.05f
        val badgeTop = height - pad - badgePH
        val badgeBottom = height - pad
        val badgeRect = RectF(badgeLeft, badgeTop, badgeRight, badgeBottom)

        canvas.drawRoundRect(badgeRect, badgePH, badgePH, actualBadgeBg)
        canvas.drawRoundRect(badgeRect, badgePH, badgePH, actualBadgeBorder)
        canvas.drawText(
            displayBadge, width / 2f,
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

    // =========================================================================
    // Widget 6: Dashboard Card
    // Split layout: hours+growth left, bar chart right, top artist pill bottom
    // =========================================================================
    // =========================================================================
    // Widget 6: Dashboard Card (Reimagined "Cinematic Daily" UI)
    // Layout: Big Typography Left, Floating Artist Right, Full Width Wave Bottom
    // =========================================================================
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
        // Using a 4-color sweep for a more dynamic, less linear look
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
        val arrowPaint = Paint().apply {
             color = TEAL_PRIMARY
             textSize = width * 0.035f
             isAntiAlias = true
        }
        val arrow = if (mainGrowth.startsWith("+")) "↗" else "↘"
        val aw = arrowPaint.measureText(arrow)
        
        canvas.drawText(arrow, pad, pillY + pillH/2 + arrowPaint.textSize/3, arrowPaint)
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
                val artBitmap = BitmapFactory.decodeFile(artistImagePath)
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
        // Use adaptive scaling instead of truncation for better look
        drawScaledText(
            canvas, 
            topArtistName, 
            textLayoutX, 
            pillRect.centerY() + artNamePaint.textSize*0.35f, 
            maxTextW, 
            artNamePaint,
            minTextSize = width * 0.02f // Allow getting quite small for this pill
        )
        
        return bitmap
    }

    // Keep old method name for compatibility
    fun generateDashboardPulse(
        context: Context, width: Int, height: Int,
        weeklyHours: String, weeklyGrowth: String, chartData: List<Float>
    ): Bitmap {
        return generateDashboardCard(context, width, height, weeklyHours, weeklyGrowth, "", chartData, "No Data", null)
    }
    // Draw a subtle noise/grain texture
    private fun drawNoiseTexture(canvas: Canvas, width: Int, height: Int) {
        val paint = Paint().apply {
            color = Color.WHITE
            alpha = 15 // Very subtle
            style = Paint.Style.FILL
        }
        val step = 4f
        for (x in 0 until width step 4) {
            for (y in 0 until height step 4) {
                if ((x + y) % 3 == 0) { // Random-ish pattern
                     // Use drawPoint or small circle (circle is smoother)
                     canvas.drawCircle(x.toFloat(), y.toFloat(), 0.8f, paint)
                }
            }
        }
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
        
        // Safety check to ensure we don't crop outside bounds
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
        // Fix: Use 0 as baseline for accurate representation of magnitude
        val minVal = 0f 
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
        
        // Optional: Draw stroke on top
        val strokePaint = Paint().apply {
            color = ColorUtils.setAlphaComponent(startColor, 200)
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        canvas.drawPath(path, strokePaint)
    }

    /**
     * Draws text that scales down to fit the available width.
     */
    private fun drawScaledText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        paint: Paint,
        minTextSize: Float = 20f
    ) {
        val originalSize = paint.textSize
        var currentSize = originalSize
        paint.textSize = currentSize

        // Iteratively reduce text size until it fits
        while (paint.measureText(text) > maxWidth && currentSize > minTextSize) {
            currentSize -= 2f
            paint.textSize = currentSize
        }

        canvas.drawText(text, x, y, paint)
        paint.textSize = originalSize // Restore original size
    }

    /**
     * Draws multi-line text with adaptive sizing and highlighting.
     * specifically suitable for the Discovery widget.
     */
    private fun drawDiscoveryText(
        canvas: Canvas,
        fullText: String,
        highlightText: String,
        centerX: Float,
        centerY: Float,
        maxWidth: Float,
        maxHeight: Float,
        basePaint: Paint,
        highlightPaint: Paint
    ) {
        val words = fullText.split(" ")
        val originalSize = basePaint.textSize
        var currentSize = originalSize
        val minSize = originalSize * 0.5f
        
        var bestLines: List<String> = emptyList()
        var bestLineHeight = 0f
        
        // Iteratively find the best fit
        // Limit iterations for performance
        val maxIterations = 20
        var iter = 0
        
        while (currentSize >= minSize && iter < maxIterations) {
            iter++
            basePaint.textSize = currentSize
            highlightPaint.textSize = currentSize
            val lineHeight = basePaint.descent() - basePaint.ascent()
            val lines = mutableListOf<String>()
            var currentLine = StringBuilder()
            
            for (word in words) {
                val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                val measurePaint = if (highlightText.contains(word, ignoreCase = true)) highlightPaint else basePaint
                // Approximate measurement using base paint for simplicity as font metrics are usually similar
                // or ideally use the widthest of the two.
                
                if (basePaint.measureText(testLine) <= maxWidth) {
                    currentLine.append(if (currentLine.isEmpty()) word else " $word")
                } else {
                    lines.add(currentLine.toString())
                    currentLine = StringBuilder(word)
                }
            }
            if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
            
            val totalHeight = lines.size * lineHeight
            // We want at least some text, max 4 lines
            if (totalHeight <= maxHeight && lines.size <= 5) {
                bestLines = lines
                bestLineHeight = lineHeight
                break
            }
            currentSize -= 2f
        }
        
        // If we failed to fit, stick to min size
        if (bestLines.isEmpty()) {
             basePaint.textSize = minSize
             highlightPaint.textSize = minSize
             // Recalculate one last time (simplification: just force wrap)
             // ... for now, just fallback to simpler drawing in the else block below
        }

        // Draw the lines
        if (bestLines.isNotEmpty()) {
            val totalBlockHeight = bestLines.size * bestLineHeight
            var drawY = centerY - totalBlockHeight / 2 + bestLineHeight * 0.7f // Approximate baseline adjustment
            
            for (line in bestLines) {
               val lineW = basePaint.measureText(line) // Approx width
               // Re-measure precisely if we want perfect centering... 
               // For mixed styles, centering is tricky without full text layout.
               // We will center the line as a whole block.
               
               var currentX = centerX - lineW / 2
               
               val lineWords = line.split(" ")
               lineWords.forEach { word ->
                   // Check containment widely to handle punctuation (e.g. "Artist,")
                   val cleanWord = word.filter { it.isLetterOrDigit() }
                   val cleanHighlight = highlightText.filter { it.isLetterOrDigit() }
                   
                   val isHighlight = cleanHighlight.isNotEmpty() && highlightText.contains(word, ignoreCase = true)
                   val paintToUse = if (isHighlight) highlightPaint else basePaint
                   
                   canvas.drawText(word, currentX, drawY, paintToUse)
                   currentX += paintToUse.measureText(word) + basePaint.measureText(" ")
               }
               
               drawY += bestLineHeight
            }
        } else {
            // Fallback: draw truncated single line
            basePaint.textSize = minSize
            val truncated = if (fullText.length > 25) fullText.take(25) + "..." else fullText
            canvas.drawText(truncated, centerX, centerY, basePaint)
        }
        
        basePaint.textSize = originalSize
    }
}
