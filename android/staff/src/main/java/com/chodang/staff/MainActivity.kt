package com.chodang.staff

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** 주방 알림 설정 화면 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var onBtn: TextView
    private lateinit var dot: android.view.View
    private lateinit var voiceText: TextView
    private lateinit var rateText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 1000) } }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
            setBackgroundColor(Color.parseColor("#F4F5F7"))
        }
        col.addView(text("주방 알림", 24f, bold = true).apply { setPadding(0, 0, 0, dp(8)) })
        onBtn = button("") {
            prefs.on = !prefs.on
            if (prefs.on) startAlerts() else AlertService.stop(this)
        }
        col.addView(onBtn)
        // 연결 상태 표시등: 초록 = 받는 중, 주황 = 준비 중, 회색 = 매장 밖·꺼짐
        val ind = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat(); setStroke(dp(1), Color.parseColor("#E5E7EB")) }
        }
        dot = android.view.View(this)
        ind.addView(dot, LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(12) })
        status = text("", 16f, bold = true)
        ind.addView(status)
        col.addView(ind, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        // 이어폰 착용 후 셀프 체크: 실제 안내와 같은 경로(띵동 + 음성, 다른 소리 잠깐 줄임)로 들려줌
        col.addView(button("🎧 이어폰 소리 확인") {
            val s = AlertService.instance ?: return@button toast("먼저 알림을 켜 주세요")
            s.speak("소리 확인입니다. 잘 들리면 준비 완료입니다.")
            if (!AlertService.status.contains("받는 중")) toast("소리는 확인됐지만 아직 포스기와 연결되지 않았습니다 (표시등 확인)")
        })

        // 직접 방송: 매장주방홈미니 + 주방 직원 폰으로 보냄 (매장 와이파이에서만)
        col.addView(section("직접 방송"))
        val toMini = android.widget.CheckBox(this).apply { text = "홈미니"; textSize = 16f; isChecked = true }
        val toPhones = android.widget.CheckBox(this).apply { text = "직원 폰"; textSize = 16f; isChecked = true }
        val sayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val sayInput = android.widget.EditText(this).apply { hint = "보낼 문구"; textSize = 16f; setSingleLine() }
        sayRow.addView(sayInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sendBtn = button("보내기") {
            val t = sayInput.text.toString().trim()
            val host = prefs.host
            if (t.isEmpty()) return@button
            val to = when { toMini.isChecked && toPhones.isChecked -> "both"; toMini.isChecked -> "mini"; toPhones.isChecked -> "phones"; else -> "" }
            if (to.isEmpty()) return@button toast("보낼 곳을 하나 이상 골라 주세요")
            if (host.isEmpty() || !AlertService.status.contains("받는 중")) return@button toast("포스기와 연결된 뒤에 보낼 수 있습니다 (표시등이 초록일 때)")
            kotlin.concurrent.thread {
                val ok = runCatching {
                    (java.net.URL("http://$host:${AlertService.PORT}/api/say?text=" + java.net.URLEncoder.encode(t, "UTF-8") + "&to=" + to)
                        .openConnection() as java.net.HttpURLConnection).run {
                        requestMethod = "POST"; connectTimeout = 2000; readTimeout = 4000
                        val c = responseCode; disconnect(); c == 200
                    }
                }.getOrDefault(false)
                handler.post { toast(if (ok) "보냄: $t" else "보내기 실패"); if (ok) sayInput.setText("") }
            }
        }
        sayRow.addView(sendBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        col.addView(sayRow)
        val targets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        targets.addView(toMini)
        targets.addView(toPhones, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(20) })
        col.addView(targets)

        col.addView(card(
            "이 앱이 하는 일",
            "• 매장 와이파이에 있을 때만, 포스기의 주방 안내(\"신규주문\" 등)를 받아 이 폰의 음성으로 읽어 줍니다.\n" +
            "• 유튜브 등을 보고 있어도 안내할 때만 그 소리를 잠깐 멈추고, 끝나면 원래대로 돌아갑니다.\n" +
            "• 매장 와이파이가 아니면(퇴근 후 등) 아무것도 하지 않고 기다립니다."))
        col.addView(card(
            "이 앱이 하지 않는 일",
            "• 연락처·사진·위치·마이크·카메라·문자 등 이 폰의 정보에 접근하지 않습니다. (권한 자체가 없음)\n" +
            "• 다른 앱의 내용이나 사용 기록을 보지 않습니다.\n" +
            "• 이 폰에서 어디로도 정보를 보내지 않습니다. 포스기에서 안내 문장을 받기만 합니다.\n" +
            "• 확인: 설정 → 애플리케이션 → 주방 알림 → 권한 (알림 말고는 없음)"))

        col.addView(section("목소리"))
        voiceText = text("", 15f)
        col.addView(voiceText)
        col.addView(row(
            button("음성 엔진") { pickEngine() },
            button("목소리") { pickVoice() },
            button("미리 듣기") { AlertService.instance?.speak("신규주문, 제육 2개 추가") ?: toast("먼저 켜 주세요") },
        ))
        rateText = text("", 15f).apply { setPadding(0, dp(10), 0, 0) }
        col.addView(rateText)
        col.addView(SeekBar(this).apply {
            max = 15; progress = ((prefs.rate - 0.5f) * 10).toInt().coerceIn(0, 15)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { if (fromUser) prefs.rate = 0.5f + v / 10f }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        col.addView(section("처음 한 번 설정"))
        col.addView(row(
            button("알림 허용") {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                else toast("이 폰은 따로 허용할 필요 없습니다")
            },
            button("배터리 '제한 없음'") {
                toast("배터리 → '제한 없음'을 골라 주세요")
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
        ))
        col.addView(text("삼성 폰: 배터리 → '제한 없음'으로 해야 화면이 꺼져도 계속 받습니다. '사용하지 않는 앱 절전' 목록에 있으면 빼 주세요.", 13f).apply { setPadding(0, dp(6), 0, 0) })

        col.addView(section("앱"))
        col.addView(row(
            button("포스기 다시 찾기") { prefs.host = ""; AlertService.instance?.wake(); toast("다시 찾습니다") },
            button("앱 업데이트") { runUpdate() },
        ))
        setContentView(ScrollView(this).apply { addView(col) })

        if (prefs.on) startAlerts()
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    private fun startAlerts() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        AlertService.start(this)
    }

    private fun refresh() {
        val s = AlertService.status
        val (label, color) = when {
            s.contains("받는 중") -> "연결됨 · 주방 알림 받는 중" to "#16A34A"
            s.contains("찾는 중") || s.contains("시작") -> "준비 중 · 포스기 찾는 중" to "#F59E0B"
            s.contains("매장 밖") -> "매장 와이파이 아님 · 대기 중" to "#9CA3AF"
            else -> "꺼짐" to "#9CA3AF"
        }
        status.text = label
        status.setTextColor(Color.parseColor(color))
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(color)) }
        onBtn.text = if (prefs.on) "알림 끄기" else "알림 켜기"
        voiceText.text = "엔진: " + prefs.engine.ifEmpty { "폰 기본값" } + "  ·  목소리: " + prefs.voice.ifEmpty { "기본 한국어" }
        rateText.text = "빠르기: " + "%.1f".format(prefs.rate) + "배"
    }

    private fun pickEngine() {
        var t: TextToSpeech? = null
        t = TextToSpeech(this) {
            val engines = t?.engines.orEmpty()
            t?.shutdown()
            val names = listOf("폰 기본값") + engines.map { it.label }
            AlertDialog.Builder(this).setTitle("음성 엔진").setItems(names.toTypedArray()) { _, i ->
                prefs.engine = if (i == 0) "" else engines[i - 1].name
                prefs.voice = ""
                AlertService.instance?.initTts()
            }.show()
        }
    }

    private fun pickVoice() {
        var t: TextToSpeech? = null
        t = TextToSpeech(this, {
            val voices = t?.voices.orEmpty().filter { it.locale.language == Locale.KOREAN.language }.sortedBy { it.name }
            t?.shutdown()
            if (voices.isEmpty()) { toast("한국어 목소리가 없습니다. 음성 엔진 설정에서 한국어 음성을 내려받아 주세요"); return@TextToSpeech }
            val names = listOf("기본 한국어") + voices.map { it.name + if (it.isNetworkConnectionRequired) " (인터넷 필요)" else "" }
            AlertDialog.Builder(this).setTitle("목소리").setItems(names.toTypedArray()) { _, i ->
                prefs.voice = if (i == 0) "" else voices[i - 1].name
                AlertService.instance?.initTts()
            }.show()
        }, prefs.engine.ifEmpty { null })
    }

    private fun runUpdate() {
        val u = Updater(this, "staff-alert.apk")
        toast("새 버전 확인 중…")
        u.check { latest, cur ->
            if (latest == null) toast("업데이트 확인 실패 (인터넷 연결 확인)")
            else if (latest <= cur) toast("최신 버전입니다 (버전 $cur)")
            else u.install { toast(it) }
        }
    }

    // ---------------- 작은 부품 ----------------

    private fun text(t: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.parseColor("#111827")); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun section(t: String) = text(t, 16f, bold = true).apply { setPadding(0, dp(22), 0, dp(8)) }
    private fun card(title: String, body: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.parseColor("#E5E7EB")) }
        addView(text(title, 15f, bold = true))
        addView(text(body, 14f).apply { setPadding(0, dp(6), 0, 0); setLineSpacing(0f, 1.3f) })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }
    private fun button(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#111827"))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat(); setStroke(dp(1), Color.parseColor("#D1D5DB")) }
        setOnClickListener { onClick(); refresh() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }
    private fun row(vararg views: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(8); topMargin = dp(8) })
        }
    }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}

/** 폰을 켜거나 앱이 업데이트되면 다시 시작 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { if (Prefs(c).on) AlertService.start(c) }
}
