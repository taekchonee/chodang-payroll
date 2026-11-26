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
            if (value.isNullOrBlank()) return GREEN
            val v = value.trim()
            return entries.firstOrNull { it.name.equals(v, true) || it.label == v } ?: GREEN
        }
    }
}

class RenderResult(
    /** 패널로 보낼 R/G/B 비트 묶음 */
    val pixelBits: ByteArray,
    /** 앱 화면 미리보기용 */
    val preview: Bitmap,
    /** 글자가 패널 폭 안에 다 들어가는지 */
    val fits: Boolean,
)

object TextRenderer {

    /**
     * 글자를 패널 높이에 맞춰 그린 뒤, 세로 8칸씩 묶어 비트로 바꿉니다.
     * (왼쪽 열부터, 위쪽 픽셀이 최상위 비트)
     *
     * @param center true 면 글자가 패널 폭보다 짧을 때 가운데 정렬합니다(고정 표시용).
     */
    fun render(
        text: String,
        color: LedColor,
        signWidth: Int,
        signHeight: Int,
        fontPx: Float,
        bold: Boolean,
        center: Boolean,
    ): RenderResult {
        require(signHeight % 8 == 0) { "패널 높이는 8의 배수여야 합니다: $signHeight" }

        val paint = Paint().apply {
            isAntiAlias = false
            textSize = fontPx
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            this.color = Color.WHITE
        }
        val textWidth = maxOf(1, ceil(paint.measureText(text)).toInt())
        val fits = textWidth <= signWidth
        val outWidth = if (center && fits) signWidth else textWidth
        val left = if (center && fits) (signWidth - textWidth) / 2 else 0

        val bounds = Rect()
        paint.getTextBounds(text.ifEmpty { " " }, 0, maxOf(1, text.length), bounds)
        val baseline = (signHeight - bounds.height()) / 2f - bounds.top

        val mask = Bitmap.createBitmap(outWidth, signHeight, Bitmap.Config.ARGB_8888)
        Canvas(mask).apply {
            drawColor(Color.BLACK)
            drawText(text, left.toFloat(), baseline, paint)
        }

        val preview = Bitmap.createBitmap(outWidth, signHeight, Bitmap.Config.ARGB_8888)
        val bytesPerColumn = signHeight / 8
        val r = ByteArray(outWidth * bytesPerColumn)
        val g = ByteArray(outWidth * bytesPerColumn)
        val b = ByteArray(outWidth * bytesPerColumn)
        var idx = 0
        for (x in 0 until outWidth) {
            var tr = 0; var tg = 0; var tb = 0
            for (y in 0 until signHeight) {
                val px = mask.getPixel(x, y)
                val lit = (Color.red(px) + Color.green(px) + Color.blue(px)) / 3 > 127
                tr = (tr shl 1) or if (lit && color.r) 1 else 0
                tg = (tg shl 1) or if (lit && color.g) 1 else 0
                tb = (tb shl 1) or if (lit && color.b) 1 else 0
                preview.setPixel(x, y, if (lit) color.argb else Color.rgb(25, 25, 25))
                if (y % 8 == 7) {
                    r[idx] = tr.toByte(); g[idx] = tg.toByte(); b[idx] = tb.toByte()
                    idx++
                    tr = 0; tg = 0; tb = 0
                }
            }
        }
        mask.recycle()
        return RenderResult(r + g + b, preview, fits)
    }
}
