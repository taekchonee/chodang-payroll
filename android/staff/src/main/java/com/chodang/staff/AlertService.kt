package com.chodang.staff

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 매장 와이파이에서 포스기(서빙 표시 앱)의 방송 문장을 기다렸다가 폰 자체 음성으로 읽습니다.
 * - 영업 시간이 아니거나 매장 밖이면 아무것도 하지 않고 기다림 (와이파이가 바뀔 때만 다시 확인)
 * - 포스기에 '기다리는 요청'을 걸어 두는 방식이라 평소엔 폰이 거의 쉼
 */
class AlertService : Service() {

    companion object {
        const val PORT = 8080
        private const val CH = "alert"
        @Volatile var status = "꺼짐"; private set
        @Volatile var instance: AlertService? = null; private set

        fun start(ctx: Context) {
            val i = Intent(ctx, AlertService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, AlertService::class.java)) }
    }

    private val lock = Object()
    @Volatile private var running = false
    private lateinit var prefs: Prefs
    private lateinit var cm: ConnectivityManager
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = wake()
        override fun onLost(network: Network) = wake()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        cm = getSystemService(ConnectivityManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "주방 알림 상태", NotificationManager.IMPORTANCE_LOW))
        val n = notification("시작하는 중…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        cm.registerDefaultNetworkCallback(netCallback)
        initTts()
        running = true
        thread(name = "alert-loop", isDaemon = true) { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { wake(); return START_STICKY }

    override fun onDestroy() {
        running = false
        wake()
        runCatching { cm.unregisterNetworkCallback(netCallback) }
        tts?.shutdown()
        instance = null
        status = "꺼짐"
        super.onDestroy()
    }

    fun wake() = synchronized(lock) { lock.notifyAll() }
    private fun sleep(ms: Long) = synchronized(lock) { if (running) lock.wait(ms) }

    // ---------------- 음성 ----------------

    fun initTts() {
        ttsReady = false
        tts?.shutdown()
        val engine = prefs.engine.ifEmpty { null }
        tts = TextToSpeech(this, { ok ->
            if (ok == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                t.language = Locale.KOREAN
                t.voices?.firstOrNull { it.name == prefs.voice }?.let { t.voice = it }
                t.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) = afterSpeak()
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) = afterSpeak()
                })
                ttsReady = true
            }
        }, engine)
    }

    private var focus: AudioFocusRequest? = null
    private var savedVolume = -1

    /** 유튜브 등 다른 소리를 잠깐 줄이고 읽기 */
    fun speak(text: String) {
        val t = tts ?: return
        if (!ttsReady) return
        val am = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
            am.requestAudioFocus(focus!!)
        }
        if (prefs.boost && savedVolume < 0) {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        }
        t.setSpeechRate(prefs.rate)
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "a" + System.nanoTime())
    }

    private fun afterSpeak() {
        val t = tts ?: return
        if (t.isSpeaking) return
        val am = getSystemService(AudioManager::class.java)
        if (savedVolume >= 0) { am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0); savedVolume = -1 }
        if (Build.VERSION.SDK_INT >= 26) focus?.let { am.abandonAudioFocusRequest(it) }
    }

    // ---------------- 포스기 기다리기 ----------------

    private fun loop() {
        var since = -1L
        var fails = 0
        while (running) {
            if (!prefs.inHours()) { set("영업 시간 아님 · 대기 중 (" + prefs.hoursText() + ")"); sleep(60_000); continue }
            val prefix = wifiPrefix()
            if (prefix == null) { set("매장 밖 · 대기 중 (배터리 사용 없음)"); sleep(10 * 60_000); continue }
            val host = prefs.host.takeIf { it.isNotEmpty() && it.startsWith("$prefix.") && alive(it) } ?: find(prefix)
            if (host == null) { set("매장 밖 · 대기 중 (배터리 사용 없음)"); sleep(10 * 60_000); continue }
            prefs.host = host
            set("주방 알림 받는 중")
            try {
                val j = get("http://$host:$PORT/api/announce?since=$since", 35_000)
                val seq = j.optLong("seq", since)
                val items = j.optJSONArray("items")
                if (since >= 0 && items != null) for (i in 0 until items.length()) speak(items.getJSONObject(i).getString("text"))
                since = seq
                fails = 0
            } catch (_: Exception) {
                fails++
                if (fails >= 3) { prefs.host = ""; fails = 0 }
                sleep(5_000)
            }
        }
    }

    /** 지금 와이파이에 붙어 있으면 그 주소 앞 3자리 (예: 192.168.0) */
    private fun wifiPrefix(): String? {
        val n = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(n) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        return cm.getLinkProperties(n)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress?.substringBeforeLast('.')
    }

    private fun find(prefix: String): String? {
        set("포스기 찾는 중…")
        val found = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(32)
        (1..254).forEach { i -> pool.execute { if (found.get() == null && alive("$prefix.$i")) found.compareAndSet(null, "$prefix.$i") } }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        return found.get()
    }

    private fun alive(h: String) = runCatching { get("http://$h:$PORT/api/announce?since=-1", 1500).has("seq") }.getOrDefault(false)

    private fun get(url: String, readMs: Int): JSONObject = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 800; readTimeout = readMs
        try { JSONObject(inputStream.bufferedReader().readText()) } finally { disconnect() }
    }

    // ---------------- 알림창 ----------------

    private fun set(s: String) {
        if (s == status) return
        status = s
        getSystemService(NotificationManager::class.java).notify(1, notification(s))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("주방 알림")
            .setContentText(text).setOngoing(true).setContentIntent(open).build()
    }
}

/** 설정 (이 폰 안에만 저장) */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("staff", Context.MODE_PRIVATE)
    var on: Boolean get() = p.getBoolean("on", true); set(v) = p.edit().putBoolean("on", v).apply()
    var host: String get() = p.getString("host", "") ?: ""; set(v) = p.edit().putString("host", v).apply()
    var engine: String get() = p.getString("engine", "") ?: ""; set(v) = p.edit().putString("engine", v).apply()
    var voice: String get() = p.getString("voice", "") ?: ""; set(v) = p.edit().putString("voice", v).apply()
    var rate: Float get() = p.getFloat("rate", 1.1f); set(v) = p.edit().putFloat("rate", v).apply()
    var boost: Boolean get() = p.getBoolean("boost", false); set(v) = p.edit().putBoolean("boost", v).apply()
    /** 받는 시간 (분 단위). 시작 = 끝이면 하루 종일 */
    var startMin: Int get() = p.getInt("start", 8 * 60 + 30); set(v) = p.edit().putInt("start", v).apply()
    var endMin: Int get() = p.getInt("end", 15 * 60 + 30); set(v) = p.edit().putInt("end", v).apply()

    fun inHours(): Boolean {
        if (startMin == endMin) return true
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (startMin < endMin) now in startMin until endMin else now >= startMin || now < endMin
    }
    fun hoursText() = if (startMin == endMin) "하루 종일" else fmt(startMin) + "~" + fmt(endMin)
    fun fmt(m: Int) = "%02d:%02d".format(m / 60, m % 60)
}
