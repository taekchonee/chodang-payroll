package com.chodang.servemark

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 설치 없이 바꾸는 설정: GitHub 릴리스의 settings.json 과 주방 화면(kds.html)을 몇 분마다 받아 씁니다.
 * 못 받으면 마지막으로 받은 것, 그것도 없으면 앱 안에 들어 있는 기본값(config/ 폴더)을 씁니다.
 */
object RemoteConfig {

    private const val REFRESH_MS = 5 * 60_000L

    @Volatile var serveCheckMs = 60_000L; private set
    @Volatile var autoServeMs = 180_000L; private set
    @Volatile var mergeMs = 1500L; private set
    @Volatile var amberSec = 300; private set
    @Volatile var redSec = 600; private set
    @Volatile var extra = listOf("소주", "맥주", "막걸리", "모주", "음료", "공기밥", "할인"); private set
    @Volatile var low = listOf("초당순두부"); private set
    @Volatile var batch = listOf("제육불고기"); private set
    /** 국·공기밥 남은 양 설정 */
    @Volatile var soups: List<Soup.Pot> = listOf(
        Soup.Pot("hwangtae", "황태해장국", listOf("황태해장국"), 23, 50, "인분", "황태해장국 끓이세요", "조리완료"),
        Soup.Pot("miyeok", "미역국", listOf("한우미역국", "전복미역국"), 23, 50, "인분", "미역국 끓이세요", "조리완료"),
    ); private set
    /** 공기밥 (시간대 자료는 settings.json 의 rice.weekday / rice.weekend) */
    @Volatile var rice = Soup.Rice(listOf("초당순두부", "짬뽕순두부", "한우미역국", "전복미역국", "황태해장국", "공기밥"),
        17, 40, 3, "14:30", "밥 지으세요", 15, emptyMap(), emptyMap()); private set
    /** 방송 문구 (빈 문구면 방송 안 함) */
    @Volatile private var phrases = mapOf("new" to "신규주문", "change" to "메뉴변경 발생", "void" to "", "move" to "")
    /** 묶음 조리 안내: "{name} {n}개 추가", name 은 줄임말 */
    @Volatile private var batchFormat = "{name} {n}개 추가"
    @Volatile private var batchNames = mapOf("제육불고기" to "제육")
    fun phrase(key: String) = phrases[key].orEmpty()
    fun batchPhrase(item: String, n: Int) = batchFormat.replace("{name}", batchNames[item] ?: item).replace("{n}", n.toString())
    @Volatile var status = "앱 기본값"; private set

    @Volatile private var html: ByteArray? = null
    @Volatile private var lastTry = 0L
    private lateinit var dir: File
    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        dir = File(app.filesDir, "remote").apply { mkdirs() }
        runCatching { apply(app.assets.open("settings.json").use { it.readBytes() }.toString(Charsets.UTF_8)) }
        File(dir, "settings.json").takeIf { it.exists() }?.let { f ->
            runCatching { apply(f.readText()); status = "저장된 설정 (" + stamp(f.lastModified()) + ")" }
        }
        File(dir, "kds.html").takeIf { it.exists() }?.let { html = it.readBytes() }
        refreshIfDue()
    }

    /** 주방 화면 HTML (받은 것 → 앱 기본값) */
    fun kdsHtml(): ByteArray = html ?: app.assets.open("kds.html").use { it.readBytes() }

    /** 주방 화면에 넘길 설정 */
    fun forKds(): JSONObject = JSONObject().put("amber", amberSec).put("red", redSec)
        .put("low", JSONArray(low)).put("extra", JSONArray(extra)).put("batch", JSONArray(batch))
        .put("html", kdsHtml().contentHashCode())

    /** 5분마다 한 번 새로 받기 (자주 불러도 됨) */
    fun refreshIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastTry < REFRESH_MS) return
        lastTry = now
        thread(isDaemon = true) {
            runCatching {
                val text = Updater.get(Updater.BASE + "settings.json").toString(Charsets.UTF_8)
                apply(text)
                File(dir, "settings.json").writeText(text)
                status = "GitHub (" + stamp(System.currentTimeMillis()) + ")"
            }.onFailure { Diag.add("설정 받기 실패: ${it.message}") }
            runCatching {
                val h = Updater.get(Updater.BASE + "kds.html")
                if (String(h, Charsets.UTF_8).contains("</html>")) { File(dir, "kds.html").writeBytes(h); html = h }
            }
        }
    }

    private fun apply(text: String) {
        val j = JSONObject(text)
        fun list(k: String, d: List<String>) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: d
        serveCheckMs = (j.optDouble("serveCheckMin", serveCheckMs / 60_000.0) * 60_000).toLong()
        autoServeMs = (j.optDouble("autoServeMin", autoServeMs / 60_000.0) * 60_000).toLong()
        mergeMs = (j.optDouble("announceMergeSec", mergeMs / 1000.0) * 1000).toLong()
        amberSec = (j.optDouble("amberMin", amberSec / 60.0) * 60).toInt()
        redSec = (j.optDouble("redMin", redSec / 60.0) * 60).toInt()
        extra = list("extra", extra); low = list("low", low); batch = list("batch", batch)
        j.optJSONArray("soups")?.let { a ->
            soups = (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val ms = o.optJSONArray("menus")
                Soup.Pot(o.getString("key"), o.optString("name", o.getString("key")),
                    if (ms == null) emptyList() else (0 until ms.length()).map { ms.getString(it) },
                    o.optInt("pot", 0), o.optInt("alertPct", 50), o.optString("unit", "인분"),
                    o.optString("say", ""), o.optString("action", "조리완료"))
            }.filter { it.key != Soup.RICE }
        }
        j.optJSONObject("rice")?.let { o ->
            fun prof(k: String) = o.optJSONObject(k)?.let { p -> p.keys().asSequence().associate { hm ->
                hm.split(":").let { it[0].toInt() * 60 + it[1].toInt() } to p.getDouble(hm) } } ?: emptyMap()
            val ms = o.optJSONArray("menus")
            rice = Soup.Rice(if (ms == null) rice.menus else (0 until ms.length()).map { ms.getString(it) },
                o.optInt("perPot", rice.perPot), o.optInt("cookMin", rice.cookMin), o.optInt("margin", rice.margin),
                o.optString("close", rice.close), o.optString("say", rice.say), o.optInt("slotMin", rice.slotMin),
                prof("weekday"), prof("weekend"))
        }
        fun map(k: String, d: Map<String, String>) = j.optJSONObject(k)?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } } ?: d
        phrases = map("phrases", phrases)
        batchNames = map("batchNames", batchNames)
        batchFormat = j.optString("batchFormat", batchFormat)
    }

    private fun stamp(t: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(Date(t))
}
