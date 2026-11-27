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
        undoBar = buildUndoBar()
        root.addView(undoBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(14), 0, dp(14), dp(14)) })
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
        val sig = items.toString()
        if (sig == lastSoup) return
        lastSoup = sig
        soupBox.removeAllViews()
        for (i in 0 until items.length()) items.getJSONObject(i).let { o ->
            soupBox.addView(if (o.optString("kind") == "rice") riceCard(o) else soupCard(o))
        }
    }

    private fun cardBox(low: Boolean, grey: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = round(if (low) "#FEE2E2" else if (grey) "#F3F4F6" else "#FFFFFF", if (low) "#DC2626" else "#E5E7EB", if (low) 2 else 1)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    private fun cardTop(name: String, right: String, rightColor: String = "#111827") = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(text(name, 18f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text(right, 18f, bold = true).apply { setTextColor(Color.parseColor(rightColor)) })
    }

    private fun vessels(list: List<VesselView.Item>) = VesselView(this).apply {
        items = list
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    /** 국 카드: 바트 그림 (1/4 먼저, 1/2 다음) */
    private fun soupCard(o: JSONObject): View {
        val name = o.optString("name"); val unit = o.optString("unit", "인분"); val key = o.optString("key")
        val ready = o.optBoolean("ready"); val started = o.optBoolean("started"); val low = o.optBoolean("low")
        val remain = o.optInt("remain"); val pot = o.optInt("pot"); val action = o.optString("action", "조리완료")
        val card = cardBox(low, !ready)
        val right = when {
            !ready -> "설정 필요"; !started -> "시작 전"
            low -> o.optString("say").removePrefix(name).trim().ifEmpty { "끓이세요" }
            else -> "약 $remain$unit"
        }
        card.addView(cardTop(name, right, if (low) "#DC2626" else if (!started || !ready) "#6B7280" else "#111827"))
        if (low) card.addView(text("약 $remain$unit 남음", 15f, bold = true).apply { setTextColor(Color.parseColor("#DC2626")) })
        if (ready) {
            val v = o.optJSONArray("vats")
            val caps = mutableMapOf<Int, Double>()
            val list = (0 until (v?.length() ?: 0)).map { i -> v!!.getJSONObject(i).let {
                caps[i] = it.optDouble("cap")
                VesselView.Item(it.optString("size"), it.optInt("pct"), it.optString("label"), it.optBoolean("now"), key, idx = if (started) i else -1)
            } }
            card.addView(vessels(list).apply {
                onLongPressItem = { item ->
                    val cap = caps[item.idx] ?: 0.0
                    val rest = list.sumOf { x -> (caps[x.idx] ?: 0.0) * x.pct / 100.0 } - cap * item.pct / 100.0
                    editAmount(key, "$name · ${item.type} 바트", item, cap, rest, unit)
                }
            })
            card.addView(text(if (started) "바트를 길게 누르면 남은 양을 고칠 수 있어요" else "조리완료를 누르면 그때부터 셉니다", 12f).apply { setTextColor(Color.parseColor("#6B7280")) })
            card.addView(button("$action (+$pot)") {
                confirm("$name $action",
                    if (started) "남은 ${remain}$unit + ${pot}$unit = ${remain + pot}${unit}으로 바꿉니다. 맞나요?\n(새 1/4 바트 · 새 1/2 바트 추가)"
                    else "${pot}${unit}(1/4 바트 + 1/2 바트)부터 셉니다. 맞나요?",
                    "네, $action") { post("/api/soup/open?key=$key", key, "$name $action") }
            })
        }
        return card
    }

    private var morningPots = 2.0

    /** 공기밥 카드: 아침엔 솥 수 입력, 영업 중엔 솥 그림 + 밥 짓기 판단 */
    private fun riceCard(o: JSONObject): View {
        val per = o.optInt("perPot", 17); val cookMin = o.optInt("cookMin", 40)
        if (!o.optBoolean("ready")) return cardBox(false, true).apply { addView(cardTop("공기밥", "설정 필요", "#6B7280")) }
        if (!o.optBoolean("started")) {
            val card = cardBox(false)
            card.addView(text("공기밥 · 오늘 시작 밥", 18f, bold = true))
            card.addView(text("영업 시작 전에 지금 있는 밥 양을 넣어 주세요", 13f).apply { setTextColor(Color.parseColor("#6B7280")) })
            val pv = vessels(emptyList())
            val value = text("", 24f, bold = true).apply { gravity = Gravity.CENTER }
            val sub = text("", 13f).apply { gravity = Gravity.CENTER; setTextColor(Color.parseColor("#6B7280")) }
            fun upd() {
                val full = morningPots.toInt(); val half = morningPots - full > 0.01
                pv.items = (List(full) { VesselView.Item("pot", 100, "${it + 1}솥", false, "rice") } +
                    (if (half) listOf(VesselView.Item("pot", 50, "반 솥", false, "rice")) else emptyList()))
                    .ifEmpty { listOf(VesselView.Item("pot", 0, "없음", false, "rice")) }
                value.text = (if (half) "%.1f" else "%.0f").format(morningPots) + "솥"
                sub.text = "약 ${(morningPots * per).toInt()}공기 · 0.5솥 단위"
            }
            card.addView(pv)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(button("−") { morningPots = (morningPots - 0.5).coerceAtLeast(0.0); upd() }, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("+") { morningPots = (morningPots + 0.5).coerceAtMost(8.0); upd() }, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(row); card.addView(sub)
            card.addView(button("영업 시작") {
                val p = morningPots
                post("/api/rice/start?pots=$p", "rice", "공기밥 영업 시작 · ${"%.1f".format(p)}솥")
            }.apply { setTextColor(Color.WHITE); background = round("#111827", "#111827") })
            upd()
            return card
        }
        val low = o.optBoolean("low"); val remain = o.optInt("remain")
        val card = cardBox(low)
        card.addView(cardTop("공기밥", if (low) "밥 지으세요" else "약 ${remain}공기", if (low) "#DC2626" else "#111827"))
        if (low) card.addView(text("약 ${remain}공기 남음", 15f, bold = true).apply { setTextColor(Color.parseColor("#DC2626")) })
        val pots = o.optJSONArray("pots"); val cooking = o.optJSONArray("cooking")
        val list = (0 until (pots?.length() ?: 0)).map { i ->
            val pct = pots!!.getInt(i)
            VesselView.Item("pot", pct, if (i == 0) "지금" else "${i + 1}솥", i == 0, "rice", idx = i)
        } + (0 until (cooking?.length() ?: 0)).map { i -> VesselView.Item("cook", 0, "짓는 중", false, "rice", "${cooking!!.getInt(i)}분") }
        card.addView(vessels(list).apply {
            onLongPressItem = { item ->
                val rest = list.filter { it.type == "pot" }.sumOf { x -> per * x.pct / 100.0 } - per * item.pct / 100.0
                editAmount("rice", "공기밥 · ${if (item.idx == 0) "지금 솥" else "${item.idx + 1}번째 솥"}", item, per.toDouble(), rest, "공기")
            }
        })
        card.addView(text("솥을 길게 누르면 남은 양을 고칠 수 있어요", 12f).apply { setTextColor(Color.parseColor("#6B7280")) })
        val runOut = o.optInt("runOut", -1)
        val status = when {
            o.optBoolean("closed") -> "영업 마감 시간"
            runOut >= 0 -> "약 ${runOut}분 뒤 부족 예상"
            else -> "마감까지 예상 ${o.optInt("toClose")}공기 · 괜찮음"
        }
        card.addView(text(status, 13f).apply { setTextColor(Color.parseColor(if (runOut in 0..cookMin) "#DC2626" else "#6B7280")); setPadding(0, dp(4), 0, 0) })
        card.addView(button("새로 지음 (+1솥, ${cookMin}분)") {
            confirm("공기밥 새로 지음", "${cookMin}분 뒤에 ${per}공기(1솥)가 더해집니다. 맞나요?", "네, 지음") {
                post("/api/soup/open?key=rice", "rice", "공기밥 새로 지음 · ${cookMin}분 뒤 +1솥")
            }
        })
        return card
    }

    /** 바트/솥 길게 누름 → 실제 남은 % 로 고치기 (그림이 같이 바뀜) */
    private fun editAmount(key: String, title: String, item: VesselView.Item, cap: Double, rest: Double, unit: String) {
        var pct = (item.pct / 5) * 5
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0) }
        col.addView(text("실제로 남은 만큼 맞춰 주세요", 13f).apply { setTextColor(Color.parseColor("#6B7280")) })
        val pic = VesselView(this).apply { big = true; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) } }
        col.addView(pic)
        val big = text("", 28f, bold = true).apply { gravity = Gravity.CENTER }
        val info = text("", 13f).apply { gravity = Gravity.CENTER; setTextColor(Color.parseColor("#6B7280")) }
        col.addView(big); col.addView(info)
        val bar = SeekBar(this).apply { max = 20 }
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        fun upd(fromBar: Boolean = false) {
            pic.items = listOf(item.copy(pct = pct, label = "", now = true))
            big.text = "$pct%"
            val mine = cap * pct / 100.0
            info.text = "이 그릇 약 ${"%.0f".format(mine)}$unit · 전체 약 ${"%.0f".format(rest + mine)}$unit"
            if (!fromBar) bar.progress = pct / 5
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { if (fromUser) { pct = v * 5; upd(true) } }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        col.addView(row(
            button("−10%") { pct = (pct - 10).coerceAtLeast(0); upd() },
            button("+10%") { pct = (pct + 10).coerceAtMost(100); upd() },
            button("비었음") { pct = 0; upd() },
        ))
        upd()
        AlertDialog.Builder(this).setTitle(title).setView(col)
            .setPositiveButton("저장") { _, _ -> post("/api/soup/set?key=$key&i=${item.idx}&pct=$pct", key, "${title.substringAfter("· ")} ${pct}%로 고침") }
            .setNegativeButton("취소", null).show()
    }

    private fun confirm(title: String, msg: String, yes: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton(yes) { _, _ -> onYes() }.setNegativeButton("취소", null).show()
    }

    /** 포스기에 보내고 화면 갱신 + 10초 되돌리기 띠 */
    private fun post(path: String, key: String, done: String) {
        if (!connected()) return toast("포스기와 연결된 뒤에 할 수 있습니다")
        val host = prefs.host
        thread {
            val s = runCatching { http("POST", "http://$host:${AlertService.PORT}$path") }.getOrNull()
            handler.post {
                if (s == null || !JSONObject(s).optBoolean("ok")) toast("실패했습니다. 다시 눌러 주세요")
                else { lastSoup = ""; showSoup(s); showUndo(done, key) }
            }
        }
    }

    private lateinit var undoBar: LinearLayout
    private lateinit var undoText: TextView
    private var undoKey = ""
    private val hideUndo = Runnable { undoBar.visibility = View.GONE }

    private fun showUndo(msg: String, key: String) {
        undoKey = key; undoText.text = msg
        undoBar.visibility = View.VISIBLE
        handler.removeCallbacks(hideUndo); handler.postDelayed(hideUndo, 10_000)
    }

    private fun buildUndoBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(12), dp(12))
        background = round("#1F2937", "#1F2937")
        visibility = View.GONE
        undoText = text("", 14f).apply { setTextColor(Color.WHITE) }
        addView(undoText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text("되돌리기", 15f, bold = true).apply {
            setTextColor(Color.parseColor("#93C5FD")); setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener {
                handler.removeCallbacks(hideUndo); undoBar.visibility = View.GONE
                val host = prefs.host; val k = undoKey
                thread {
                    val s = runCatching { http("POST", "http://$host:${AlertService.PORT}/api/soup/undo?key=" + URLEncoder.encode(k, "UTF-8")) }.getOrNull()
                    handler.post {
                        if (s == null || !JSONObject(s).optBoolean("ok")) toast("되돌리지 못했습니다 (시간이 지났거나 연결 끊김)")
                        else { toast("되돌렸습니다"); lastSoup = ""; showSoup(s) }
                    }
                }
            }
        })
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
