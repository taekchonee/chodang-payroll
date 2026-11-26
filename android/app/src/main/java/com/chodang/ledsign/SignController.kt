package com.chodang.ledsign

import android.content.Context
import android.graphics.Bitmap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 패널에 보낼 요청 하나 */
data class ShowRequest(
    val text: String,
    val color: LedColor = LedColor.GREEN,
    /** auto: 한 화면에 들어가면 고정, 넘치면 왼쪽으로 흐름 */
    val mode: String = "auto",
    val speed: Int? = null,
    val brightness: Int? = null,
)

/**
 * 글자 그리기와 블루투스 전송을 묶어, 한 번에 하나씩 순서대로 처리합니다.
 */
class SignController(context: Context) {

    private val ble = CoolLedBle(context.applicationContext)
    private val prefs = context.applicationContext.getSharedPreferences("sign", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile var lastStatus: String = "아직 보낸 적 없음"
        private set
    @Volatile var lastText: String? = prefs.getString("last_text", null)
        private set
    @Volatile var lastPreview: Bitmap? = null
        private set

    val fontPx: Float get() = prefs.getFloat("font_px", 16f)
    val bold: Boolean get() = prefs.getBoolean("bold", true)

    /** 패널을 단 방향. 기본은 세로 */
    var orientation: Orientation
        get() = Orientation.parse(prefs.getString("orientation", null))
        set(v) = prefs.edit().putString("orientation", v.name).apply()

    /** 세로 패널이거나 | 로 나눈 문구는 96칸 화면 단위로 나눠 고정 모드로 보냅니다. */
    private fun paged(req: ShowRequest) = orientation.vertical || req.text.contains('|')

    fun setFont(px: Float, bold: Boolean) {
        prefs.edit().putFloat("font_px", px).putBoolean("bold", bold).apply()
    }

    val signSize: Pair<Int, Int>
        get() = ble.lastSign?.let { it.width to it.height }
            ?: (prefs.getInt("width", CoolLedBle.DEFAULT_WIDTH) to prefs.getInt("height", CoolLedBle.DEFAULT_HEIGHT))

    val signName: String? get() = ble.lastSign?.name

    fun locationBlocksScan(): Boolean = ble.locationBlocksScan()

    /** 미리보기만 만듭니다(전송 안 함). */
    fun preview(req: ShowRequest): RenderResult {
        val (w, h) = signSize
        return render(req, w, h).second
    }

    private fun parseMode(m: String): CoolLedProtocol.Mode? =
        CoolLedProtocol.Mode.entries.firstOrNull { it.name.equals(m, true) }
            ?: when (m) {
                "고정" -> CoolLedProtocol.Mode.STATIC
                "왼쪽", "흐름" -> CoolLedProtocol.Mode.LEFT
                "오른쪽" -> CoolLedProtocol.Mode.RIGHT
                else -> null
            }

    /** 보낼 방식과 그림을 정합니다. */
    private fun render(req: ShowRequest, w: Int, h: Int): Pair<CoolLedProtocol.Mode, RenderResult> {
        val chosen = parseMode(req.mode.lowercase().trim())
        if (paged(req)) {
            // 화면이 여러 개면 패널이 고정 모드에서 한 화면씩 넘겨 보여줍니다.
            val r = TextRenderer.renderPaged(req.text, req.color, w, h, fontPx, bold, orientation)
            return (chosen ?: CoolLedProtocol.Mode.STATIC) to r
        }
        if (chosen == null) {
            val r = TextRenderer.renderLine(req.text, req.color, w, h, fontPx, bold, center = true)
            if (r.fits) return CoolLedProtocol.Mode.STATIC to r
            return CoolLedProtocol.Mode.LEFT to TextRenderer.renderLine(req.text, req.color, w, h, fontPx, bold, center = false)
        }
        return chosen to TextRenderer.renderLine(req.text, req.color, w, h, fontPx, bold,
            center = chosen == CoolLedProtocol.Mode.STATIC)
    }

    /** 패널에 글자를 보냅니다. 끝날 때까지 기다리고 결과 문구를 돌려줍니다. */
    fun show(req: ShowRequest, timeoutSec: Long = 90): String = runJob("'${req.text}' 표시") {
        // 처음 한 번은 패널을 찾아 크기를 확인합니다.
        val sign = ble.signInfo()
        prefs.edit().putInt("width", sign.width).putInt("height", sign.height).apply()

        val (mode, render) = render(req, sign.width, sign.height)

        val frames = mutableListOf<ByteArray>()
        frames += CoolLedProtocol.chopUp(
            CoolLedProtocol.textPayload(req.text.length, render.pixelBits), CoolLedProtocol.CMD_TEXT)
        frames += CoolLedProtocol.setMode(mode)
        req.speed?.let { frames += CoolLedProtocol.setSpeed(it) }
        req.brightness?.let { frames += CoolLedProtocol.setBrightness(it) }
        frames += CoolLedProtocol.setOn(true)

        ble.send(frames)
        lastText = req.text
        lastPreview = render.signPreview
        prefs.edit().putString("last_text", req.text).apply()
    }.get(timeoutSec, TimeUnit.SECONDS)

    fun power(on: Boolean): String =
        runJob(if (on) "패널 켜기" else "패널 끄기") { ble.send(CoolLedProtocol.setOn(on)) }.get(60, TimeUnit.SECONDS)

    fun brightness(value: Int): String =
        runJob("밝기 $value") { ble.send(CoolLedProtocol.setBrightness(value)) }.get(60, TimeUnit.SECONDS)

    fun findSign(): String = runJob("패널 찾기") { ble.scan(onlyKnown = false) }.get(30, TimeUnit.SECONDS)

    private fun runJob(what: String, block: () -> Unit) = worker.submit(Callable {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date())
        lastStatus = try {
            block()
            "$time 성공: $what"
        } catch (e: Exception) {
            "$time 실패: $what (${e.message})"
        }
        lastStatus
    })

    fun shutdown() = worker.shutdownNow()
}
