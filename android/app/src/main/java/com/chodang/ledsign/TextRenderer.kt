package com.chodang.ledsign

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil

/** 패널은 R/G/B 각각 켜짐·꺼짐만 있어서 7가지 색만 낼 수 있습니다. */
enum class LedColor(val label: String, val r: Boolean, val g: Boolean, val b: Boolean) {
    RED("빨강", true, false, false),
    GREEN("초록", false, true, false),
    BLUE("파랑", false, false, true),
    YELLOW("노랑", true, true, false),
    CYAN("하늘", false, true, true),
    PURPLE("보라", true, false, true),
    WHITE("흰색", true, true, true);

    val argb: Int get() = Color.rgb(if (r) 255 else 0, if (g) 255 else 0, if (b) 255 else 0)

    companion object {
        fun parse(value: String?): LedColor {
            if (value.isNullOrBlank()) return WHITE
            val v = value.trim()
            return entries.firstOrNull { it.name.equals(v, true) || it.label == v } ?: WHITE
        }
    }
}

/**
 * 패널을 단 방향.
 * 패널은 내부적으로 항상 가로(폭 96 × 높이 16)로 그림을 받습니다.
 * 세로로 달았으면 글자를 위→아래로 쌓아 그린 뒤 90° 돌려서 보냅니다.
 */
enum class Orientation(val label: String) {
    HORIZONTAL("가로"),
    VERTICAL_A("세로 A (270°)"),
    VERTICAL_B("세로 B (90°)");

    val vertical: Boolean get() = this != HORIZONTAL

    companion object {
        fun parse(v: String?) = entries.firstOrNull { it.name == v } ?: VERTICAL_A
    }
}

class RenderResult(
    /** 패널로 보낼 R/G/B 비트 묶음 */
    val pixelBits: ByteArray,
    /** 앱 화면 미리보기용 (실제로 보이는 방향) */
    val preview: Bitmap,
    /** 글자가 한 화면 안에 다 들어가는지 */
    val fits: Boolean,
    /** 손님에게 실제로 보이는 모습 (세로 패널이면 세로 화면들을 나란히) */
    val signPreview: Bitmap = preview,
)

object TextRenderer {

    private val OFF_COLOR = Color.rgb(25, 25, 25)

    /**
     * Neo둥근모: 16×16 칸에 맞춘 한글 도트 글꼴 (OFL-1.1, assets/fonts/neodgm-LICENSE.txt).
     * CoolLED 앱처럼 글자가 칸을 꽉 채우고 획이 1칸으로 반듯하게 찍힙니다.
     * 16px(또는 32px 등 16의 배수)에서 가장 깨끗합니다.
     */
    @Volatile private var pixelFont: Typeface = Typeface.DEFAULT

    fun loadFont(context: Context) {
        if (pixelFont != Typeface.DEFAULT) return
        pixelFont = try {
            Typeface.createFromAsset(context.assets, "fonts/neodgm.ttf")
        } catch (_: Exception) {
            Typeface.DEFAULT
        }
    }

    private fun paint(fontPx: Float) = Paint().apply {
        isAntiAlias = false
        textSize = fontPx
        typeface = pixelFont
        color = Color.WHITE
    }

    /** 굵게: 같은 글자를 오른쪽으로 1칸 밀어 한 번 더 찍습니다(가로획은 그대로, 세로획이 2칸). */
    private fun Canvas.drawGlyphs(text: String, x: Float, baseline: Float, paint: Paint, bold: Boolean) {
        drawText(text, x, baseline, paint)
        if (bold) drawText(text, x + 1f, baseline, paint)
    }

    /** 글꼴 높이(어센트+디센트)를 칸 높이 안에 가운데 맞춘 기준선 */
    private fun baselineIn(paint: Paint, top: Float, height: Int): Float {
        val fm = paint.fontMetrics
        return top + (height - (fm.descent - fm.ascent)) / 2f - fm.ascent
    }

    private fun lit(px: Int) = (Color.red(px) + Color.green(px) + Color.blue(px)) / 3 > 127

    /**
     * 패널 방향(가로) 기준의 흑백 그림을 R/G/B 비트로 바꿉니다.
     * 왼쪽 열부터, 세로 8칸씩 묶어 위쪽 픽셀이 최상위 비트.
     */
    private fun encode(mask: Bitmap, color: LedColor): ByteArray {
        val w = mask.width
        val h = mask.height
        require(h % 8 == 0) { "패널 높이는 8의 배수여야 합니다: $h" }
        val bytesPerColumn = h / 8
        val r = ByteArray(w * bytesPerColumn)
        val g = ByteArray(w * bytesPerColumn)
        val b = ByteArray(w * bytesPerColumn)
        var idx = 0
        for (x in 0 until w) {
            var tr = 0; var tg = 0; var tb = 0
            for (y in 0 until h) {
                val on = lit(mask.getPixel(x, y))
                tr = (tr shl 1) or if (on && color.r) 1 else 0
                tg = (tg shl 1) or if (on && color.g) 1 else 0
                tb = (tb shl 1) or if (on && color.b) 1 else 0
                if (y % 8 == 7) {
                    r[idx] = tr.toByte(); g[idx] = tg.toByte(); b[idx] = tb.toByte()
                    idx++
                    tr = 0; tg = 0; tb = 0
                }
            }
        }
        return r + g + b
    }

    private fun colorize(mask: Bitmap, color: LedColor): Bitmap {
        val out = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until mask.width) for (y in 0 until mask.height) {
            out.setPixel(x, y, if (lit(mask.getPixel(x, y))) color.argb else OFF_COLOR)
        }
        return out
    }

    /**
     * 가로로 단 패널용: 글자를 한 줄로 그립니다. (길면 흐르게 보낼 때 사용)
     * @param center 한 화면에 들어가면 가운데 정렬
     */
    fun renderLine(
        text: String, color: LedColor, signWidth: Int, signHeight: Int,
        fontPx: Float, bold: Boolean, center: Boolean,
    ): RenderResult {
        val mask = horizontalMask(text, signWidth, signHeight, fontPx, bold, if (center) signWidth else null)
        val fits = mask.width <= signWidth
        val result = RenderResult(encode(mask, color), colorize(mask, color), fits)
        mask.recycle()
        return result
    }

    /** fixedWidth 가 있으면 그 폭에 가운데 정렬(넘치면 잘림), 없으면 글자 길이만큼 */
    private fun horizontalMask(
        text: String, signWidth: Int, signHeight: Int, fontPx: Float, bold: Boolean, fixedWidth: Int?,
    ): Bitmap {
        val paint = paint(fontPx)
        val textWidth = maxOf(1, ceil(paint.measureText(text)).toInt() + if (bold) 1 else 0)
        // 글자가 더 길면 글자 길이만큼 그립니다(자르는 건 호출하는 쪽에서).
        val outWidth = if (fixedWidth != null && textWidth <= fixedWidth) fixedWidth else textWidth
        val left = (outWidth - textWidth) / 2
        val baseline = baselineIn(paint, 0f, signHeight)
        val mask = Bitmap.createBitmap(outWidth, signHeight, Bitmap.Config.ARGB_8888)
        Canvas(mask).apply {
            drawColor(Color.BLACK)
            drawGlyphs(text, left.toFloat(), baseline, paint, bold)
        }
        return mask
    }

    /**
     * 화면(96칸) 단위로 나눠 그립니다. 모든 화면은 가운데 정렬입니다.
     * - | 로 나눈 곳에서 화면을 나눕니다.
     * - 세로 패널은 한 화면에 안 들어가면 글자 단위로 다음 화면으로 넘깁니다.
     * 여러 화면을 이어 붙여 고정 모드로 보내면 패널이 한 화면씩 넘겨 보여줍니다.
     */
    fun renderPaged(
        text: String, color: LedColor, signWidth: Int, signHeight: Int,
        fontPx: Float, bold: Boolean, orientation: Orientation,
    ): RenderResult {
        val segments = text.split('|').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(" ") }
        val pageMasks = mutableListOf<Bitmap>()
        for (seg in segments) {
            if (orientation.vertical) {
                splitVertical(seg, signWidth, signHeight).forEach { cells ->
                    pageMasks += verticalPage(cells, signWidth, signHeight, fontPx, bold, orientation)
                }
            } else {
                pageMasks += horizontalMask(seg, signWidth, signHeight, fontPx, bold, signWidth)
                    .let { if (it.width == signWidth) it else Bitmap.createBitmap(it, (it.width - signWidth) / 2, 0, signWidth, signHeight) }
            }
        }
        val all = Bitmap.createBitmap(signWidth * pageMasks.size, signHeight, Bitmap.Config.ARGB_8888)
        Canvas(all).apply {
            drawColor(Color.BLACK)
            pageMasks.forEachIndexed { i, m -> drawBitmap(m, (i * signWidth).toFloat(), 0f, null) }
        }
        // 미리보기: CoolLED 앱처럼 패널 방향(가로 띠)으로, 화면 사이에 3칸 틈
        val gap = 3
        val preview = Bitmap.createBitmap(
            pageMasks.size * signWidth + (pageMasks.size - 1) * gap, signHeight, Bitmap.Config.ARGB_8888)
        Canvas(preview).apply {
            drawColor(Color.BLACK)
            pageMasks.forEachIndexed { i, m ->
                drawBitmap(colorize(m, color), (i * (signWidth + gap)).toFloat(), 0f, null)
            }
        }
        val signPreview = if (orientation.vertical) {
            val pw = signHeight; val ph = signWidth; val g = 4
            Bitmap.createBitmap(pageMasks.size * pw + (pageMasks.size - 1) * g, ph, Bitmap.Config.ARGB_8888).also { bmp ->
                Canvas(bmp).apply {
                    drawColor(Color.BLACK)
                    pageMasks.forEachIndexed { i, m ->
                        drawBitmap(colorize(unrotate(m, orientation), color), (i * (pw + g)).toFloat(), 0f, null)
                    }
                }
            }
        } else preview
        val result = RenderResult(encode(all, color), preview, pageMasks.size == 1, signPreview)
        all.recycle(); pageMasks.forEach { it.recycle() }
        return result
    }

    /** 패널 방향 그림을 실제로 보이는 세로 방향으로 되돌립니다(미리보기용). */
    private fun unrotate(mask: Bitmap, orientation: Orientation): Bitmap {
        val physW = mask.height
        val physH = mask.width
        val out = Bitmap.createBitmap(physW, physH, Bitmap.Config.ARGB_8888)
        for (lx in 0 until physH) for (ly in 0 until physW) {
            val px: Int
            val py: Int
            if (orientation == Orientation.VERTICAL_A) { px = physW - 1 - ly; py = lx }
            else { px = ly; py = physH - 1 - lx }
            out.setPixel(px, py, mask.getPixel(lx, ly))
        }
        return out
    }

    /** 세로 칸 하나: 글자(16칸) 또는 공백(반 칸) */
    private fun cellHeight(ch: String, cell: Int) = if (ch.isBlank()) cell / 2 else cell

    /** 글자 단위로 한 화면(signWidth 칸)에 들어가게 나눕니다. 화면 앞뒤 공백은 뺍니다. */
    private fun splitVertical(text: String, signWidth: Int, cell: Int): List<List<String>> {
        val chars = text.map { it.toString() }
        val pages = mutableListOf<MutableList<String>>()
        var cur = mutableListOf<String>()
        var used = 0
        for (ch in chars) {
            val h = cellHeight(ch, cell)
            if (used + h > signWidth && cur.isNotEmpty()) {
                pages += cur; cur = mutableListOf(); used = 0
            }
            if (cur.isEmpty() && ch.isBlank()) continue
            cur += ch; used += h
        }
        if (cur.isNotEmpty()) pages += cur
        return pages.map { p -> p.dropLastWhile { it.isBlank() } }.filter { it.isNotEmpty() }.ifEmpty { listOf(listOf(" ")) }
    }

    /**
     * 세로로 단 패널의 한 화면: 글자를 위→아래로 쌓아 가운데 정렬로 그린 뒤 90° 돌립니다.
     * 결과는 패널 방향(폭 signWidth × 높이 signHeight)입니다.
     */
    private const val CHAR_GAP = 2     // 글자 사이 틈 (칸)
    private const val SPACE_GAP = 6    // 공백 한 개의 틈 (칸)
    private const val MAX_STRETCH = 2f // 글자를 길이 방향으로 최대 몇 배까지 늘릴지

    /** 16×16 칸에 글자 하나를 그립니다(흑백). */
    private fun glyphBitmap(ch: String, paint: Paint, bold: Boolean, cell: Int): Bitmap {
        val g = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888)
        Canvas(g).apply {
            drawColor(Color.BLACK)
            val w = paint.measureText(ch) + if (bold) 1 else 0
            val x = Math.round((cell - w) / 2f).toFloat()
            drawGlyphs(ch, x, baselineIn(paint, 0f, cell), paint, bold)
        }
        return g
    }

    /**
     * 세로로 단 패널의 한 화면.
     * 글자를 위→아래로 쌓되, 패널 길이(96칸)를 꽉 채우도록 글자를 길이 방향으로 늘립니다.
     * (3글자는 2배, 4글자는 약 1.5배 …) 늘릴 때는 도트를 그대로 복제해 흐려지지 않습니다.
     * 결과는 패널 방향(폭 signWidth × 높이 signHeight)으로 돌려서 돌려줍니다.
     */
    private fun verticalPage(
        cells: List<String>, signWidth: Int, signHeight: Int,
        fontPx: Float, bold: Boolean, orientation: Orientation,
    ): Bitmap {
        val paint = paint(fontPx)
        val cell = signHeight
        val physW = signHeight      // 실제로 보이는 폭 (16)
        val physH = signWidth       // 실제로 보이는 높이 (96)

        val glyphs = cells.filter { it.isNotBlank() }.distinct().associateWith { glyphBitmap(it, paint, bold, cell) }

        // 모든 글자의 잉크가 있는 줄 범위(공통)를 구해 위아래 빈 줄을 잘라냅니다.
        var inkTop = cell
        var inkBottom = -1
        for (g in glyphs.values) for (y in 0 until cell) for (x in 0 until cell) {
            if (lit(g.getPixel(x, y))) { if (y < inkTop) inkTop = y; if (y > inkBottom) inkBottom = y }
        }
        if (inkBottom < inkTop) { inkTop = 0; inkBottom = cell - 1 }
        val inkH = inkBottom - inkTop + 1

        val n = cells.count { it.isNotBlank() }
        val spaces = cells.count { it.isBlank() }
        var gaps = 0
        for (i in 1 until cells.size) if (cells[i].isNotBlank() && cells[i - 1].isNotBlank()) gaps += CHAR_GAP
        val h = if (n == 0) inkH else minOf(
            (physH - spaces * SPACE_GAP - gaps) / n,
            (inkH * MAX_STRETCH).toInt(),
        ).coerceAtLeast(1)
        val total = n * h + spaces * SPACE_GAP + gaps

        val phys = Bitmap.createBitmap(physW, physH, Bitmap.Config.ARGB_8888)
        Canvas(phys).apply {
            drawColor(Color.BLACK)
            var y = (physH - total) / 2
            var prev: String? = null
            for (ch in cells) {
                if (ch.isBlank()) { y += SPACE_GAP; prev = ch; continue }
                if (prev != null && prev.isNotBlank()) y += CHAR_GAP
                val src = Bitmap.createBitmap(glyphs.getValue(ch), 0, inkTop, cell, inkH)
                val stretched = Bitmap.createScaledBitmap(src, physW, h, false)
                drawBitmap(stretched, 0f, y.toFloat(), null)
                stretched.recycle(); src.recycle()
                y += h
                prev = ch
            }
        }
        glyphs.values.forEach { it.recycle() }

        // 패널 방향으로 돌립니다.
        val mask = Bitmap.createBitmap(physH, physW, Bitmap.Config.ARGB_8888)
        for (lx in 0 until physH) for (ly in 0 until physW) {
            val px: Int
            val py: Int
            if (orientation == Orientation.VERTICAL_A) {
                // CoolLED 앱의 270° 와 같음: 패널 왼쪽 끝이 위로 오도록 단 경우
                px = physW - 1 - ly; py = lx
            } else {
                // CoolLED 앱의 90° 와 같음
                px = ly; py = physH - 1 - lx
            }
            mask.setPixel(lx, ly, phys.getPixel(px, py))
        }
        phys.recycle()
        return mask
    }
}
