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
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 주방 알림 앱 화면
 *  맨 위 줄: 주방 알림 · 🎧 소리 확인 · 🔔 종 칩(알림·연결 켜고 끄기) · ⚙️ 설정
 *  탭: 주방(국·공기밥 남은 양) / 방송(직접 방송)
 *  설정 화면은 처음 설치했을 때와 알림 허용·배터리 설정이 빠졌을 때만 자동으로 열림
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var bell: TextView
    private lateinit var tabKitchen: TextView
    private lateinit var tabSay: TextView
    private lateinit var tabs: LinearLayout
    private lateinit var kitchenView: View
    private lateinit var sayView: View
    private lateinit var settingsView: View
    private lateinit var soupMsg: TextView
    private lateinit var soupBox: LinearLayout
    private lateinit var voiceText: TextView
    private lateinit var rateText: TextView
    private var tab = 0            // 0 주방, 1 방송
    private var showingSettings = false
    private var lastSoup = ""
    private var ticks = 0
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            if (ticks++ % 5 == 0) loadSoup()
            handler.postDelayed(this, 1000)
        }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4F5F7"))
        }

        // ---- 맨 위 줄 ----
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(10), dp(8))
        }
        head.addView(text("주방 알림", 21f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(chip("🎧 소리 확인") { soundCheck() })
        bell = chip("🔔") { toggleBell() }
        head.addView(bell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        head.addView(TextView(this).apply {
            text = "⚙️"; textSize = 22f; setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener { showSettings(true) }
        })
        root.addView(head)

        // ---- 탭 ----
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), 0, dp(16), dp(6)) }
        tabKitchen = tabButton("주방") { tab = 0; showTab() }
        tabSay = tabButton("방송") { tab = 1; showTab() }
        tabs.addView(tabKitchen, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabs.addView(tabSay, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        root.addView(tabs)

        val body = FrameLayout(this)
        kitchenView = buildKitchen(); sayView = buildSay(); settingsView = buildSettings()
        listOf(kitchenView, sayView, settingsView).forEach { body.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        showTab()
        showSettings(needsSetup())
        if (prefs.on) startAlerts()
    }

    override fun onResume() { super.onResume(); ticks = 0; handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (showingSettings) showSettings(false) else super.onBackPressed() }

    // ---------------- 화면 전환 ----------------

    private fun showTab() {
        kitchenView.visibility = if (!showingSettings && tab == 0) View.VISIBLE else View.GONE
        sayView.visibility = if (!showingSettings && tab == 1) View.VISIBLE else View.GONE
        listOf(tabKitchen to (tab == 0), tabSay to (tab == 1)).forEach { (v, on) ->
            v.setTextColor(Color.parseColor(if (on) "#FFFFFF" else "#374151"))
            v.background = round(if (on) "#111827" else "#FFFFFF", if (on) "#111827" else "#D1D5DB")
        }
    }

    private fun showSettings(on: Boolean) {
        showingSettings = on
        settingsView.visibility = if (on) View.VISIBLE else View.GONE
        tabs.visibility = if (on) View.GONE else View.VISIBLE
        showTab()
    }

    /** 처음 설치했거나, 알림 허용·배터리 '제한 없음'이 빠졌을 때 */
    private fun needsSetup(): Boolean {
        if (!prefs.setupDone) return true
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return true
        return !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }

    // ---------------- 주방 탭: 국·공기밥 남은 양 ----------------

    private fun buildKitchen(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(24)) }
        soupMsg = text("", 14f).apply { setTextColor(Color.parseColor("#6B7280")); setPadding(0, dp(4), 0, dp(4)) }
        col.addView(soupMsg)
        soupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(soupBox)
        return ScrollView(this).apply { addView(col) }
    }

    private fun connected() = prefs.on && AlertService.status.contains("받는 중") && prefs.host.isNotEmpty()

    private fun loadSoup() {
        if (showingSettings || tab != 0) return
        if (!connected()) {
            soupMsg.text = if (!prefs.on) "알림이 꺼져 있습니다. 위의 종을 눌러 켜 주세요." else "포스기와 연결되면 남은 양이 보입니다. (매장 와이파이)"
            if (lastSoup.isNotEmpty()) { lastSoup = ""; soupBox.removeAllViews() }
            return
        }
        val host = prefs.host
        thread {
            val s = runCatching { http("GET", "http://$host:${AlertService.PORT}/api/soup") }.getOrNull()
            handler.post { if (s != null) showSoup(s) else soupMsg.text = "남은 양을 못 받았습니다. 잠시 뒤 다시 시도합니다." }
        }
    }

    private fun showSoup(s: String) {
        soupMsg.text = ""
        val items = runCatching { JSONObject(s).getJSONArray("items") }.getOrNull() ?: return
        val sig = (0 until items.length()).joinToString("|") { items.getJSONObject(it).let { o -> "${o.optString("key")}${o.optInt("remain")}${o.optBoolean("started")}${o.optBoolean("ready")}${o.optBoolean("low")}${o.optInt("pot")}" } }
        if (sig == lastSoup) return
        lastSoup = sig
        soupBox.removeAllViews()
        for (i in 0 until items.length()) soupBox.addView(soupCard(items.getJSONObject(i)))
    }

    private fun soupCard(o: JSONObject): View {
        val name = o.optString("name"); val unit = o.optString("unit", "인분")
        val ready = o.optBoolean("ready"); val started = o.optBoolean("started"); val low = o.optBoolean("low")
        val remain = o.optInt("remain"); val pot = o.optInt("pot"); val action = o.optString("action", "새로 엶")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = round(if (low) "#FEE2E2" else if (!ready) "#F3F4F6" else "#FFFFFF", if (low) "#DC2626" else "#E5E7EB", if (low) 2 else 1)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(name, 18f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (low) top.addView(text(o.optString("say").removePrefix(name).trim().ifEmpty { "끓이세요" }, 17f, bold = true).apply { setTextColor(Color.parseColor("#DC2626")) })
        card.addView(top)
        when {
            !ready -> card.addView(text("설정 필요", 16f).apply { setTextColor(Color.parseColor("#9CA3AF")); setPadding(0, dp(6), 0, 0) })
            !started -> card.addView(text("아직 시작 전 · 새로 열면 그때부터 셉니다", 15f).apply { setTextColor(Color.parseColor("#6B7280")); setPadding(0, dp(6), 0, 0) })
            else -> card.addView(text("$remain$unit 남음", 30f, bold = true).apply {
                setTextColor(Color.parseColor(if (low) "#DC2626" else "#111827")); setPadding(0, dp(4), 0, 0)
            })
        }
        if (ready) card.addView(button("$action (+$pot)") { confirmOpen(o.optString("key"), name, action, pot, unit) })
        return card
    }

    private fun confirmOpen(key: String, name: String, action: String, pot: Int, unit: String) {
        AlertDialog.Builder(this).setTitle("$name $action")
            .setMessage("남은 양에 ${pot}${unit}을 더합니다. 맞나요?")
            .setPositiveButton("네, $action") { _, _ ->
                val host = prefs.host
                if (!connected()) return@setPositiveButton toast("포스기와 연결된 뒤에 할 수 있습니다")
                thread {
                    val s = runCatching { http("POST", "http://$host:${AlertService.PORT}/api/soup/open?key=" + URLEncoder.encode(key, "UTF-8")) }.getOrNull()
                    handler.post {
                        if (s == null || !JSONObject(s).optBoolean("ok")) toast("실패했습니다. 다시 눌러 주세요")
                        else { toast("$name $action (+$pot$unit)"); lastSoup = ""; showSoup(s) }
                    }
                }
            }
            .setNegativeButton("취소", null).show()
    }

    // ---------------- 방송 탭: 직접 방송 ----------------

    private fun buildSay(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(24)) }
        col.addView(text("매장주방홈미니와 주방 직원 폰으로 보냅니다. 매장 와이파이에서만 됩니다.", 14f).apply { setTextColor(Color.parseColor("#6B7280")) })
        val input = EditText(this).apply { hint = "보낼 문구"; textSize = 18f; minLines = 2 }
        col.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        val toMini = CheckBox(this).apply { text = "홈미니"; textSize = 16f; isChecked = true }
        val toPhones = CheckBox(this).apply { text = "직원 폰"; textSize = 16f; isChecked = true }
        val targets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        targets.addView(toMini)
        targets.addView(toPhones, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(20) })
        col.addView(targets)
        col.addView(button("보내기") {
            val t = input.text.toString().trim()
            if (t.isEmpty()) return@button
            val to = when { toMini.isChecked && toPhones.isChecked -> "both"; toMini.isChecked -> "mini"; toPhones.isChecked -> "phones"; else -> "" }
            if (to.isEmpty()) return@button toast("보낼 곳을 하나 이상 골라 주세요")
            if (!connected()) return@button toast("포스기와 연결된 뒤에 보낼 수 있습니다 (종이 초록일 때)")
            val host = prefs.host
            thread {
                val ok = runCatching { http("POST", "http://$host:${AlertService.PORT}/api/say?text=" + URLEncoder.encode(t, "UTF-8") + "&to=" + to); true }.getOrDefault(false)
                handler.post { toast(if (ok) "보냄: $t" else "보내기 실패"); if (ok) input.setText("") }
            }
        }.apply { setTextColor(Color.WHITE); background = round("#1D9E75", "#1D9E75") })
        return ScrollView(this).apply { addView(col) }
    }

    // ---------------- 설정 화면 (⚙️) ----------------

    private fun buildSettings(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(28)) }
        col.addView(text("설정", 20f, bold = true))
        col.addView(text("이어폰을 끼고 위의 🎧 소리 확인을 눌러 들리는지 확인한 뒤 '확인 완료'를 눌러 주세요.", 14f).apply { setTextColor(Color.parseColor("#6B7280")); setPadding(0, dp(4), 0, 0) })

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

        col.addView(section("목소리"))
        voiceText = text("", 15f)
        col.addView(voiceText)
        col.addView(row(
            button("음성 엔진") { pickEngine() },
            button("목소리") { pickVoice() },
            button("미리 듣기") { AlertService.instance?.speak("신규주문, 제육 2개 추가") ?: toast("먼저 종을 눌러 켜 주세요") },
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

        col.addView(card(
            "이 앱이 하는 일",
            "• 매장 와이파이에 있을 때만, 포스기의 주방 안내(\"신규주문\" 등)를 받아 이 폰의 음성으로 읽어 줍니다.\n" +
            "• 국·공기밥 남은 양을 보여 주고, '새로 엶'을 누르면 포스기에 알립니다.\n" +
            "• 유튜브 등을 보고 있어도 안내할 때만 그 소리를 잠깐 멈추고, 끝나면 원래대로 돌아갑니다.\n" +
            "• 매장 와이파이가 아니면(퇴근 후 등) 아무것도 하지 않고 기다립니다."))
        col.addView(card(
            "이 앱이 하지 않는 일",
            "• 연락처·사진·위치·마이크·카메라·문자 등 이 폰의 정보에 접근하지 않습니다. (권한 자체가 없음)\n" +
            "• 다른 앱의 내용이나 사용 기록을 보지 않습니다.\n" +
            "• 매장 포스기 말고는 어디로도 정보를 보내지 않습니다.\n" +
            "• 확인: 설정 → 애플리케이션 → 주방 알림 → 권한 (알림 말고는 없음)"))

        col.addView(section("앱"))
        col.addView(row(
            button("포스기 다시 찾기") { prefs.host = ""; AlertService.instance?.wake(); toast("다시 찾습니다") },
            button("앱 업데이트") { runUpdate() },
        ))
        col.addView(text("버전 " + runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("?"), 13f).apply {
            setTextColor(Color.parseColor("#9CA3AF")); setPadding(0, dp(8), 0, 0)
        })
        col.addView(button("확인 완료") { prefs.setupDone = true; showSettings(false) }.apply {
            setTextColor(Color.WHITE); background = round("#111827", "#111827")
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)
        })
        return ScrollView(this).apply { addView(col) }
    }

    // ---------------- 종 칩·소리 확인 ----------------

    private fun toggleBell() {
        prefs.on = !prefs.on
        if (prefs.on) { startAlerts(); toast("알림 켬") } else { AlertService.stop(this); toast("알림 끔 (포스기 연결도 끊음)") }
        lastSoup = ""; loadSoup()
    }

    private fun soundCheck() {
        val s = AlertService.instance ?: return toast("먼저 종을 눌러 알림을 켜 주세요")
        s.speak("소리 확인입니다. 잘 들리면 준비 완료입니다.")
        if (!AlertService.status.contains("받는 중")) toast("소리는 확인됐지만 아직 포스기와 연결되지 않았습니다 (종이 초록이 되면 연결됨)")
    }

    private fun startAlerts() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        AlertService.start(this)
    }

    private fun refresh() {
        val s = AlertService.status
        val color = when {
            !prefs.on -> "#9CA3AF"
            s.contains("받는 중") -> "#16A34A"
            s.contains("매장 밖") || s == "꺼짐" -> "#9CA3AF"
            else -> "#F59E0B"   // 찾는 중·시작하는 중
        }
        bell.text = if (prefs.on) "🔔" else "🔕"
        bell.background = round(color, color)
        bell.contentDescription = if (!prefs.on) "알림 꺼짐" else s
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

    private fun http(method: String, url: String): String = (URL(url).openConnection() as HttpURLConnection).run {
        requestMethod = method; connectTimeout = 2000; readTimeout = 4000
        try {
            if (responseCode != 200) throw IllegalStateException("응답 $responseCode")
            inputStream.bufferedReader().readText()
        } finally { disconnect() }
    }

    // ---------------- 작은 부품 ----------------

    private fun round(fill: String, stroke: String, w: Int = 1) = GradientDrawable().apply {
        setColor(Color.parseColor(fill)); cornerRadius = dp(12).toFloat(); setStroke(dp(w), Color.parseColor(stroke))
    }
    private fun text(t: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.parseColor("#111827")); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun chip(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#111827"))
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = round("#FFFFFF", "#D1D5DB")
        setOnClickListener { onClick(); refresh() }
    }
    private fun tabButton(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener { onClick(); loadSoup() }
    }
    private fun section(t: String) = text(t, 16f, bold = true).apply { setPadding(0, dp(22), 0, dp(8)) }
    private fun card(title: String, body: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = round("#FFFFFF", "#E5E7EB")
        addView(text(title, 15f, bold = true))
        addView(text(body, 14f).apply { setPadding(0, dp(6), 0, 0); setLineSpacing(0f, 1.3f) })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }
    private fun button(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#111827"))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = round("#FFFFFF", "#D1D5DB")
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
