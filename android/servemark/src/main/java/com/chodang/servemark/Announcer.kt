package com.chodang.servemark

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 주방 방송: 홈어시스턴트 웹훅으로 문장을 보내면, HA 자동화가 매장주방홈미니에서 읽어 줍니다.
 * 같은 테이블의 변화가 1.5초 안에 여러 번 오면 한 문장으로 묶습니다.
 */
class Announcer(context: Context) {

    companion object {
        const val DEFAULT_URL = "http://192.168.0.45/api/webhook/chodang_kds_announce"
        private val MERGE_MS get() = RemoteConfig.mergeMs
    }

    private val prefs = context.applicationContext.getSharedPreferences("announce", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private val pending = LinkedHashMap<String, MutableList<String>>()

    var enabled: Boolean
        get() = prefs.getBoolean("on", true)
        set(v) { prefs.edit().putBoolean("on", v).apply() }
    var url: String
        get() = prefs.getString("url", DEFAULT_URL) ?: DEFAULT_URL
        set(v) { prefs.edit().putString("url", v).apply() }

    /** 이벤트 문구를 잠깐 모아 한 문장으로 (같은 문구가 겹치면 한 번만): "신규주문, 제육 2개 추가" */
    fun event(part: String) = main.post {
        val list = pending.getOrPut("") { mutableListOf() }
        if (list.isEmpty()) main.postDelayed({ flush() }, MERGE_MS)
        if (part !in list) list += part
    }

    private fun flush() {
        val parts = pending.remove("") ?: return
        if (parts.isNotEmpty()) say(parts.joinToString(", "))
    }

    // ---------------- 직원 폰 (매장 와이파이로 문장만 받아 폰 자체 음성으로 읽음) ----------------

    private val lock = Object()
    private var seq = 0L
    private val log = ArrayDeque<Pair<Long, String>>()
    private val phones = mutableMapOf<String, Long>()

    /** 직원 폰이 기다리는 요청: since 이후 문장을 돌려주고, 없으면 최대 waitMs 동안 기다림 */
    fun poll(since: Long, ip: String, waitMs: Long): JSONObject = synchronized(lock) {
        phones[ip] = System.currentTimeMillis()
        // 처음 붙었거나 포스기 앱이 다시 시작돼 번호가 줄었으면 → 지금 번호만 알려 줌(지난 방송은 다시 안 읽음)
        if (since < 0 || since > seq) return JSONObject().put("seq", seq).put("items", JSONArray())
        val end = System.currentTimeMillis() + waitMs
        while (log.none { it.first > since }) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) break
            lock.wait(left)
        }
        phones[ip] = System.currentTimeMillis()
        val items = JSONArray()
        log.filter { it.first > since }.forEach { items.put(JSONObject().put("seq", it.first).put("text", it.second)) }
        JSONObject().put("seq", seq).put("items", items)
    }

    /** 최근 1분 30초 안에 붙어 있던 직원 폰 수 */
    fun phonesOnline(): Int = synchronized(lock) {
        val now = System.currentTimeMillis()
        phones.values.count { now - it < 90_000 }
    }

    /** 문장 그대로 방송: phones=직원 폰, mini=홈미니(홈어시스턴트, 켜져 있을 때) */
    fun say(text: String, mini: Boolean = true, phones: Boolean = true) {
        if (phones) synchronized(lock) {
            seq++
            log.addLast(seq to text)
            while (log.size > 50) log.removeFirst()
            lock.notifyAll()
        }
        if (!mini || !enabled) { Diag.add("주방 방송(폰만): \"$text\""); return }
        val target = url
        net.execute {
            val r = try {
                (URL(target).openConnection() as HttpURLConnection).run {
                    requestMethod = "POST"; connectTimeout = 3000; readTimeout = 5000; doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.use { it.write(JSONObject().put("message", text).toString().toByteArray()) }
                    val c = responseCode; disconnect(); "응답 $c"
                }
            } catch (e: Exception) { "실패 ${e.message}" }
            Diag.add("주방 방송: \"$text\" ($r)")
        }
    }
}
