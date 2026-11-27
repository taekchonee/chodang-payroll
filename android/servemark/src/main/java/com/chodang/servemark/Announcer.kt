package com.chodang.servemark

import android.content.Context
import android.os.Handler
import android.os.Looper
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

    /** 테이블별 한 조각(예: "짬뽕순두부 1개 추가")을 모아 1.5초 뒤 한 번에 방송 */
    fun table(table: String, part: String) = main.post {
        val list = pending.getOrPut(table) { mutableListOf() }
        if (list.isEmpty()) main.postDelayed({ flush(table) }, MERGE_MS)
        list += part
    }

    private fun flush(table: String) {
        val parts = pending.remove(table) ?: return
        say("${table}번 테이블, " + parts.joinToString(", "))
    }

    /** 문장 그대로 방송 */
    fun say(text: String) {
        if (!enabled) return
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
