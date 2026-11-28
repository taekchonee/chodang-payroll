package com.chodang.kds

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 주방 태블릿 전용 앱: 포스기의 주방 화면을 전체 화면으로 계속 켜 둡니다.
 * - 화면 꺼짐 방지, 알림음은 누르지 않아도 남
 * - 포스기 주소를 기억하고, 끊기면 같은 와이파이에서 포스기(8080 포트)를 다시 찾음
 */
class MainActivity : Activity() {

    private val port = 8080
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var msg: TextView
    private lateinit var upd: TextView
    private val updater by lazy { Updater(this, "kitchen.apk") }
    private val updateCheck = object : Runnable {
        override fun run() {
            updater.check { latest, cur -> upd.visibility = if (latest != null && latest > cur) View.VISIBLE else View.GONE }
            handler.postDelayed(this, 30 * 60_000L)
        }
    }
    private var host: String? = null
    @Volatile private var searching = false
    private val prefs get() = getSharedPreferences("kds", Context.MODE_PRIVATE)

    private val watchdog = object : Runnable {
        override fun run() {
            val h = host
            thread {
                val ok = h != null && alive(h)
                handler.post { if (!ok) find() }
            }
            handler.postDelayed(this, 20_000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0F1117"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = settings.userAgentString + " ChodangKDS"
            webViewClient = object : WebViewClient() {
                @Deprecated("Deprecated in Java")
                override fun onReceivedError(v: WebView?, code: Int, d: String?, url: String?) {
                    sendLog("화면 오류($code): ${d ?: ""}")
                    find()
                }
            }
        }
        msg = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F1117"))
        }
        upd = TextView(this).apply {
            text = "새 버전 있음 · 눌러서 업데이트"; textSize = 16f; setTextColor(Color.parseColor("#1E1200"))
            setBackgroundColor(Color.parseColor("#EF9F27")); setPadding(28, 18, 28, 18); visibility = View.GONE
            setOnClickListener { updater.install { m -> text = m } }
        }
        setContentView(FrameLayout(this).apply {
            addView(web); addView(msg)
            addView(upd, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 24, 70) })
        })
        handler.post(updateCheck)
        host = prefs.getString("host", null)
        if (host != null) open(host!!) else find()
        handler.postDelayed(watchdog, 20_000)
    }

    override fun onResume() {
        super.onResume()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun open(h: String) {
        msg.visibility = View.GONE
        web.loadUrl("http://$h:$port/")
    }

    /** 같은 와이파이(앞 3자리 같은 주소)에서 주방 화면 서버를 찾습니다. */
    private fun find() {
        if (searching) return
        searching = true
        msg.visibility = View.VISIBLE
        msg.text = "포스기를 찾는 중…\n(포스기와 같은 와이파이인지 확인해 주세요)"
        thread {
            val prefix = myPrefix()
            val found = AtomicReference<String?>(null)
            if (prefix != null) {
                val pool = Executors.newFixedThreadPool(32)
                (1..254).forEach { i ->
                    pool.execute { if (found.get() == null && alive("$prefix.$i")) found.compareAndSet(null, "$prefix.$i") }
                }
                pool.shutdown()
                pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
            }
            handler.post {
                searching = false
                val h = found.get()
                if (h != null) {
                    host = h; prefs.edit().putString("host", h).apply(); open(h)
                    sendLog("포스기 연결됨 ($h)")
                } else {
                    msg.text = "포스기를 찾지 못했습니다\n포스기의 '서빙 표시' 앱이 켜져 있는지, 같은 와이파이인지 확인해 주세요\n(10초 뒤 다시 찾습니다)"
                    handler.postDelayed({ find() }, 10_000)
                }
            }
        }
    }

    private fun alive(h: String): Boolean = try {
        (URL("http://$h:$port/api/state").openConnection() as HttpURLConnection).run {
            connectTimeout = 700; readTimeout = 1500
            val ok = responseCode == 200 && inputStream.bufferedReader().readText().contains("\"tickets\"")
            disconnect(); ok
        }
    } catch (_: Exception) { false }

    private fun sendLog(text: String) {
        val h = host ?: return
        thread(isDaemon = true) {
            runCatching {
                val q = java.net.URLEncoder.encode(text, "UTF-8")
                (URL("http://$h:$port/api/log?app=kds&msg=$q").openConnection() as HttpURLConnection).run {
                    requestMethod = "POST"; connectTimeout = 1000; readTimeout = 1500
                    responseCode; disconnect()
                }
            }
        }
    }

    private fun myPrefix(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress?.substringBeforeLast('.')
    }.getOrNull()
}
