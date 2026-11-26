package com.chodang.ledsign

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
)

object TextRenderer {

    private val OFF_COLOR = Color.rgb(25, 25, 25)

    private fun paint(fontPx: Float, bold: Boolean) = Paint().apply {
        isAntiAlias = false
        textSize = fontPx
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        color = Color.WHITE
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
        val paint = paint(fontPx, bold)
        val textWidth = maxOf(1, ceil(paint.measureText(text)).toInt())
        // 글자가 더 길면 글자 길이만큼 그립니다(자르는 건 호출하는 쪽에서).
        val outWidth = if (fixedWidth != null && textWidth <= fixedWidth) fixedWidth else textWidth
        val left = (outWidth - textWidth) / 2
        val bounds = Rect()
        paint.getTextBounds(text.ifEmpty { " " }, 0, maxOf(1, text.length), bounds)
        val baseline = (signHeight - bounds.height()) / 2f - bounds.top
        val mask = Bitmap.createBitmap(outWidth, signHeight, Bitmap.Config.ARGB_8888)
        Canvas(mask).apply {
            drawColor(Color.BLACK)
            drawText(text, left.toFloat(), baseline, paint)
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
        val result = RenderResult(encode(all, color), preview, pageMasks.size == 1)
        all.recycle(); pageMasks.forEach { it.recycle() }
        return result
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
    private fun verticalPage(
        cells: List<String>, signWidth: Int, signHeight: Int,
        fontPx: Float, bold: Boolean, orientation: Orientation,
    ): Bitmap {
        val paint = paint(fontPx, bold)
        val cell = signHeight
        val physW = signHeight      // 실제로 보이는 폭 (16)
        val physH = signWidth       // 실제로 보이는 높이 (96)
        val contentH = cells.sumOf { cellHeight(it, cell) }
        var y = (physH - contentH) / 2

        // 모든 글자를 같은 기준선에 맞추려고 한글 한 글자로 높이를 잽니다.
        val ref = Rect()
        paint.getTextBounds("한", 0, 1, ref)

        val phys = Bitmap.createBitmap(physW, physH, Bitmap.Config.ARGB_8888)
        Canvas(phys).apply {
            drawColor(Color.BLACK)
            for (ch in cells) {
                val h = cellHeight(ch, cell)
                if (!ch.isBlank()) {
                    val x = (physW - paint.measureText(ch)) / 2f
                    val baseline = y + (cell - ref.height()) / 2f - ref.top
                    drawText(ch, x, baseline, paint)
                }
                y += h
            }
        }

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
