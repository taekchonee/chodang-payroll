package com.chodang.servemark

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 포스 혼잡 알림: 이지포스 화면에서 최근 5분 / 10분 이내 주문의 그릇 수를 세어
 * 기준 이상이 유지되면 Home Assistant 웹훅(거실 홈미니)으로 알립니다.
 */
class BusyMonitor {

    data class Config(
        val enabled: Boolean = true,
        val dishLightMin: Int = 9,
        val dishRecentMin: Int = 13,
        val lightSec: Int = 300,
        val recentSec: Int = 600,
        val stableN: Int = 2,
        val clearSec: Int = 300,
        val cooldownSec: Int = 1200,
        val webhookUrl: String = "http://192.168.0.45/api/webhook/chodang_pos_busy",
        val notDish: List<String> = listOf(
            "공기밥", "면추가", "소주", "맥주", "음료", "지평막걸리", "막걸리",
            "반찬판매", "매실청", "모주", "젓갈", "고추장", "콜라", "사이다"
        ),
        val setMinus: List<String> = listOf("세트할인", "할인", "세트"),
    )

    private val net = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var lastEval = 0L
    private var hotN = 0
    @Volatile private var busy = false
    private var calmSince = 0L
    private var lastAlert = 0L

    @Volatile private var lastLight = 0
    @Volatile private var lastRecent = 0
    @Volatile private var lastDishes5 = 0
    @Volatile private var lastDishes10 = 0
    @Volatile private var lastTables: List<String> = emptyList()

    /** 한 테이블의 메뉴 맵에서 음식 그릇 수 계산 (세트할인 = -1그릇, 주류/공기밥 등 제외) */
    fun countDishes(items: Map<String, Int>, cfg: Config = RemoteConfig.busy): Int {
        var n = 0
        for ((rawName, qty) in items) {
            if (qty <= 0) continue
            val name = rawName.replace(" ", "")
            if (cfg.setMinus.any { name.contains(it) }) {
                n -= qty
                continue
            }
            if (name.contains("추가") || name.contains("공기") || name.contains("서빙") ||
                cfg.notDish.any { name.contains(it) }
            ) {
                continue
            }
            n += qty
        }
        return maxOf(n, 0)
    }

    /** 테이블 목록과 주문 시작 시각으로 혼잡 여부를 갱신합니다 (최소 3초 간격). */
    fun evaluate(
        tables: List<ServeService.Table>,
        orderSince: (String) -> Long,
        itemsOf: (ServeService.Table) -> Map<String, Int>,
        now: Long = System.currentTimeMillis(),
    ) {
        val cfg = RemoteConfig.busy
        if (!cfg.enabled) return
        if (now - lastEval < 3000L) return
        lastEval = now

        var light = 0
        var recent = 0
        var d5 = 0
        var d10 = 0
        val activeTables = mutableListOf<String>()

        for (t in tables) {
            if (!t.occupied || t.label.startsWith("포장")) continue
            val since = orderSince(t.label)
            if (since <= 0L) continue
            val ageSec = (now - since) / 1000.0
            if (ageSec > cfg.recentSec) continue
            val dishes = countDishes(itemsOf(t), cfg)
            recent++
            d10 += dishes
            activeTables += t.label
            if (ageSec <= cfg.lightSec) {
                light++
                d5 += dishes
            }
        }

        lastLight = light
        lastRecent = recent
        lastDishes5 = d5
        lastDishes10 = d10
        lastTables = activeTables

        val hot = (cfg.dishLightMin > 0 && d5 >= cfg.dishLightMin) ||
            (cfg.dishRecentMin > 0 && d10 >= cfg.dishRecentMin)

        if (hot) {
            hotN++
            if (hotN < cfg.stableN) return
            calmSince = 0L
            if (!busy) {
                busy = true
                if (lastAlert == 0L || now - lastAlert >= cfg.cooldownSec * 1000L) {
                    lastAlert = now
                    sendWebhook(cfg.webhookUrl, light, recent, d5, d10, activeTables, now)
                } else {
                    Diag.add("혼잡 다시 시작(${cfg.cooldownSec / 60}분 안이라 방송 안 함): 5분 ${d5}그릇, 10분 ${d10}그릇")
                }
            }
        } else {
            hotN = 0
            if (busy) {
                if (calmSince == 0L) calmSince = now
                if (now - calmSince >= cfg.clearSec * 1000L) {
                    busy = false
                    Diag.add("혼잡 해제 (5분 ${d5}그릇, 10분 ${d10}그릇)")
                }
            }
        }
    }

    private fun sendWebhook(
        url: String,
        light: Int,
        recent: Int,
        d5: Int,
        d10: Int,
        tables: List<String>,
        now: Long,
    ) {
        if (url.isBlank()) return
        val stamp = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(now))
        val body = JSONObject()
            .put("light", light)
            .put("recent", recent)
            .put("dishes_5", d5)
            .put("dishes", d10)
            .put("tables", JSONArray(tables))
            .put("time", stamp)
            .toString()

        net.execute {
            var result = ""
            for (attempt in 1..3) {
                result = try {
                    (URL(url).openConnection() as HttpURLConnection).run {
                        requestMethod = "POST"
                        connectTimeout = 3000
                        readTimeout = 5000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                        val c = responseCode
                        disconnect()
                        if (c in 200..299) "응답 $c" else "오류 $c"
                    }
                } catch (e: Exception) {
                    "실패 ${e.message}"
                }
                if (result.startsWith("응답")) break
                if (attempt < 3) runCatching { Thread.sleep(2000) }
            }
            main.post { Diag.add("혼잡 방송: 5분 ${d5}그릇, 10분 ${d10}그릇 ($result)") }
        }
    }

    fun json(): String = JSONObject()
        .put("enabled", RemoteConfig.busy.enabled)
        .put("busy", busy)
        .put("dishes_5", lastDishes5)
        .put("dishes", lastDishes10)
        .put("light", lastLight)
        .put("recent", lastRecent)
        .put("tables", JSONArray(lastTables))
        .put("dishLightMin", RemoteConfig.busy.dishLightMin)
        .put("dishRecentMin", RemoteConfig.busy.dishRecentMin)
        .put("lastAlert", lastAlert)
        .toString()
}
