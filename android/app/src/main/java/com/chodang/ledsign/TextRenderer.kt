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
    VERTICAL_A("세로 A"),
    VERTICAL_B("세로 B");

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
     * 글자를 그려 패널용 비트로 바꿉니다.
     *
     * @param signWidth 패널 내부 폭 (96)
     * @param signHeight 패널 내부 높이 (16)
     * @param center 한 화면에 들어가면 가운데 정렬(고정 표시용)
     * @param fixed 크기를 한 화면에 딱 맞춤(넘치면 잘림). 번갈아 표시 장면용
     */
    fun render(
        text: String,
        color: LedColor,
        signWidth: Int,
        signHeight: Int,
        fontPx: Float,
        bold: Boolean,
        center: Boolean,
        orientation: Orientation,
        fixed: Boolean = false,
    ): RenderResult =
        if (orientation.vertical) renderVertical(text, color, signWidth, signHeight, fontPx, bold, center, orientation, fixed)
        else renderHorizontal(text, color, signWidth, signHeight, fontPx, bold, center, fixed)

    private fun renderHorizontal(
        text: String, color: LedColor, signWidth: Int, signHeight: Int,
        fontPx: Float, bold: Boolean, center: Boolean, fixed: Boolean,
    ): RenderResult {
        val paint = paint(fontPx, bold)
        val textWidth = maxOf(1, ceil(paint.measureText(text)).toInt())
        val fits = textWidth <= signWidth
        val full = fixed || (center && fits)
        val outWidth = if (full) signWidth else textWidth
        val left = if (full) (signWidth - textWidth) / 2 else 0

        val bounds = Rect()
        paint.getTextBounds(text.ifEmpty { " " }, 0, maxOf(1, text.length), bounds)
        val baseline = (signHeight - bounds.height()) / 2f - bounds.top

        val mask = Bitmap.createBitmap(outWidth, signHeight, Bitmap.Config.ARGB_8888)
        Canvas(mask).apply {
            drawColor(Color.BLACK)
            drawText(text, left.toFloat(), baseline, paint)
        }
        val result = RenderResult(encode(mask, color), colorize(mask, color), fits)
        mask.recycle()
        return result
    }

    /**
     * 세로로 단 패널용: 한 글자씩 위→아래로 쌓아 그린 뒤 90° 돌립니다. (공백은 뺍니다)
     * 글자 칸은 16×16 이라 세로 96칸에 6글자가 들어갑니다. 글자 크기를 14px 정도로 두면 글자 사이가 뜹니다.
     */
    private fun renderVertical(
        text: String, color: LedColor, signWidth: Int, signHeight: Int,
        fontPx: Float, bold: Boolean, center: Boolean, orientation: Orientation, fixed: Boolean,
    ): RenderResult {
        val paint = paint(fontPx, bold)
        val chars = text.filterNot { it.isWhitespace() }.map { it.toString() }.ifEmpty { listOf(" ") }
        val cell = signHeight                  // 글자 칸은 패널 폭(16)과 같은 정사각형
        val contentH = chars.size * cell
        val fits = contentH <= signWidth
        val full = fixed || (center && fits)
        val physW = signHeight                 // 실제로 보이는 폭 (16)
        val physH = if (full) signWidth else contentH  // 실제로 보이는 높이
        val top = if (full) (signWidth - contentH) / 2 else 0

        // 모든 글자를 같은 기준선에 맞추려고 한글 한 글자로 높이를 잽니다.
        val ref = Rect()
        paint.getTextBounds("한", 0, 1, ref)

        val phys = Bitmap.createBitmap(physW, physH, Bitmap.Config.ARGB_8888)
        Canvas(phys).apply {
            drawColor(Color.BLACK)
            chars.forEachIndexed { i, ch ->
                val x = (physW - paint.measureText(ch)) / 2f
                val baseline = top + i * cell + (cell - ref.height()) / 2f - ref.top
                drawText(ch, x, baseline, paint)
            }
        }

        // 패널 방향(가로 physH × 16)으로 돌립니다.
        val mask = Bitmap.createBitmap(physH, physW, Bitmap.Config.ARGB_8888)
        for (lx in 0 until physH) for (ly in 0 until physW) {
            val px: Int
            val py: Int
            if (orientation == Orientation.VERTICAL_A) {
                // 패널 왼쪽 끝이 위로 오도록 시계 방향으로 돌려 단 경우
                px = physW - 1 - ly; py = lx
            } else {
                // 반시계 방향으로 돌려 단 경우
                px = ly; py = physH - 1 - lx
            }
            mask.setPixel(lx, ly, phys.getPixel(px, py))
        }
        val result = RenderResult(encode(mask, color), colorize(phys, color), fits)
        mask.recycle(); phys.recycle()
        return result
    }

    /**
     * 여러 장면을 한 화면 크기로 하나씩 그려 번갈아 표시(애니메이션)용으로 묶습니다.
     * pixelBits 는 [모든 장면의 R] + [모든 장면의 G] + [모든 장면의 B] 순서입니다.
     */
    fun renderPages(
        pages: List<String>,
        color: LedColor,
        signWidth: Int,
        signHeight: Int,
        fontPx: Float,
        bold: Boolean,
        orientation: Orientation,
    ): RenderResult {
        val rendered = pages.map { render(it, color, signWidth, signHeight, fontPx, bold, true, orientation, fixed = true) }
        val plane = signWidth * signHeight / 8
        val r = java.io.ByteArrayOutputStream()
        val g = java.io.ByteArrayOutputStream()
        val b = java.io.ByteArrayOutputStream()
        rendered.forEach {
            r.write(it.pixelBits, 0, plane)
            g.write(it.pixelBits, plane, plane)
            b.write(it.pixelBits, plane * 2, plane)
        }
        // 미리보기: 장면을 가로로 나란히 (사이 3칸)
        val gap = 3
        val pw = rendered.first().preview.width
        val ph = rendered.first().preview.height
        val preview = Bitmap.createBitmap(rendered.size * pw + (rendered.size - 1) * gap, ph, Bitmap.Config.ARGB_8888)
        Canvas(preview).apply {
            drawColor(Color.BLACK)
            rendered.forEachIndexed { i, it -> drawBitmap(it.preview, (i * (pw + gap)).toFloat(), 0f, null) }
        }
        return RenderResult(r.toByteArray() + g.toByteArray() + b.toByteArray(), preview, rendered.all { it.fits })
    }
}
