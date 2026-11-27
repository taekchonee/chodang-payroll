package com.chodang.servemark

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 설정·진단 화면. 평소에는 열 필요 없음(접근성 서비스가 알아서 동작). */
class MainActivity : Activity() {

    private lateinit var marks: Marks
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var log: TextView
    private lateinit var diagButton: TextView
    private lateinit var announceButton: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1500) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        marks = Marks(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            setBackgroundColor(Color.parseColor("#F4F5F7"))
        }
        col.addView(TextView(this).apply {
            text = "서빙 표시"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#111827"))
        })
        status = TextView(this).apply { textSize = 16f; setPadding(0, dp(8), 0, dp(8)); typeface = Typeface.DEFAULT_BOLD }
        col.addView(status)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("접근성 설정 열기") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        row1.addView(button("5초 뒤 화면 구조 기록") {
            val s = ServeService.instance ?: return@button toast("먼저 접근성에서 '서빙 표시'를 켜 주세요")
            toast("5초 안에 이지포스 화면으로 가 주세요")
            handler.postDelayed({ s.dumpTree() }, 5000)
        })
        diagButton = button("") {
            marks.diagOverlay = !marks.diagOverlay
            ServeService.instance?.refresh()
        }
        row1.addView(diagButton)
        row1.addView(button("주방 방송 시험") {
            val s = ServeService.instance ?: return@button toast("먼저 접근성에서 '서빙 표시'를 켜 주세요")
            s.announcer.say("주방 방송 시험입니다")
            toast("매장주방홈미니에서 소리가 나는지 확인해 주세요")
        })
        announceButton = button("") {
            val s = ServeService.instance ?: return@button
            s.announcer.enabled = !s.announcer.enabled
        }
        row1.addView(announceButton)
        row1.addView(button("앱 업데이트") { runUpdate() })
        row1.addView(button("표시 모두 지우기") { marks.clear(); ServeService.instance?.refresh() })
        col.addView(row1)


        info = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor("#374151")); setPadding(0, dp(12), 0, dp(4)) }
        col.addView(info)
        log = TextView(this).apply {
            textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#111827"))
            setTextIsSelectable(true)
        }
        col.addView(log)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    private fun refresh() {
        val on = ServeService.instance != null
        status.text = if (on) "● 켜짐 — 이지포스에서 주문 있는 테이블을 길게 누르면 '서빙완료'가 표시됩니다"
            else "● 꺼짐 — '접근성 설정 열기'에서 '서빙 표시'를 켜 주세요"
        status.setTextColor(Color.parseColor(if (on) "#15803D" else "#C62828"))
        announceButton.text = if (ServeService.instance?.announcer?.enabled != false) "주방 방송 끄기" else "주방 방송 켜기"
        diagButton.text = if (marks.diagOverlay) "진단 표시 끄기" else "진단 표시 켜기"
        info.text = buildString {
            append("앱 버전: ").append(Updater(this@MainActivity, "serve-mark.apk").currentCode()).append("  ·  설정: ").append(RemoteConfig.status).append('\n')
            append("직원 폰 연결: ").append(ServeService.instance?.announcer?.phonesOnline() ?: 0).append("대").append('\n')
            append("주방 화면 주소: ").append(kdsAddress()).append('\n')
            append("이지포스 앱: ").append(marks.targetPackage.ifEmpty { "(아직 못 찾음 — 이지포스를 한 번 눌러 주세요)" }).append('\n')
            append("서빙완료 표시 중: ").append(marks.all().sorted().joinToString(", ").ifEmpty { "없음" }).append('\n')
            append("서빙 순서: ").append(ServeService.instance?.queue?.joinToString(" → ")?.ifEmpty { "남은 서빙 없음" } ?: "-").append('\n')
            append("마지막 화면 읽기: ").append(Diag.lastScan).append('\n')
            append("신호가 온 앱: ").append(Diag.packages.entries.joinToString(", ") { "${it.key}(${it.value})" })
        }
        log.text = "── 최근 신호 ──\n" + Diag.recent(60).reversed().joinToString("\n") +
            "\n\n── 마지막 화면 구조 ──\n" + Diag.lastTree
    }

    private fun button(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#111827"))
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(10).toFloat(); setStroke(dp(1), Color.parseColor("#D1D5DB"))
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
    }

    /** 이 포스기의 매장 내 주소 (주방 태블릿 크롬에 입력) */
    private fun kdsAddress(): String = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            ?.let { "http://${it.hostAddress}:${KdsServer.PORT}" }
    }.getOrNull() ?: "(네트워크 없음)"


    /** 앱 업데이트 버튼 */
    private fun runUpdate() {
        val u = Updater(this, "serve-mark.apk")
        toast("새 버전 확인 중…")
        u.check { latest, cur ->
            if (latest == null) toast("업데이트 확인 실패 (인터넷 연결을 확인해 주세요)")
            else if (latest <= cur) toast("최신 버전입니다 (버전 $cur)")
            else u.install { toast(it) }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
