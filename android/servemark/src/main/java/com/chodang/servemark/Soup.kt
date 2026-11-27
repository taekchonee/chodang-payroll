package com.chodang.servemark

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 국 남은 양(바트별)과 공기밥 남은 양(솥별)·밥 짓기 판단.
 *
 * 국: 조리완료 = 1/4 바트 + 1/2 바트(용량 1 : 1.5)를 줄 끝에 추가. 판매는 줄 앞(지금 쓰는 바트)부터 빼고,
 *     취소는 지금 바트에 되돌림. 직원이 바트를 길게 눌러 % 를 고치면 그 바트만 바뀜.
 *     알림: 마지막 1/2 바트만 남았고 그게 alertPct(50%) 이하가 되면 "끓이세요" 한 번.
 * 밥: 아침에 넣은 솥 수로 시작(그날만). 판매는 지금 솥부터 빼고, '새로 지음'은 40분 뒤 줄 끝에 +1솥.
 *     지난 1달 같은 시간대(평일/주말) 평균 × 오늘 장사 빠르기로 예상해, 짓는 시간 안에 여유가 바닥나고
 *     마감까지 더 필요하면 "밥 지으세요" 한 번. 솥도 길게 눌러 % 를 고칠 수 있음.
 * 테이블마다 마지막으로 본 수량을 기억해 바뀐 만큼만 셉니다(포장 포함).
 */
class Soup(context: Context) {

    data class Pot(val key: String, val name: String, val menus: List<String>, val pot: Int, val alertPct: Int,
                   val unit: String, val say: String, val action: String) {
        val ready get() = pot > 0 && menus.isNotEmpty()
        fun count(items: Map<String, Int>) = items.entries.filter { e -> menus.any { e.key.contains(it) } }.sumOf { it.value }
    }

    data class Rice(val menus: List<String>, val perPot: Int, val cookMin: Int, val margin: Int, val close: String,
                    val say: String, val slotMin: Int, val weekday: Map<Int, Double>, val weekend: Map<Int, Double>) {
        fun count(items: Map<String, Int>) = items.entries.filter { e -> menus.any { e.key.contains(it) } }.sumOf { it.value }
        val closeMin get() = close.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
    }

    /** 바트 하나 (size "1/4"/"1/2", 용량, 남은 양, 몇 번째 배치) */
    data class Vat(val size: String, val cap: Double, var amt: Double, val batch: Int)

    companion object {
        const val RICE = "rice"
        const val UNDO_MS = 15_000L
        /** 1/4 바트 : 1/2 바트 = 1 : 1.5 → 1배치의 0.4 / 0.6 */
        val VATS = listOf("1/4" to 0.4, "1/2" to 0.6)
    }

    private val prefs = context.applicationContext.getSharedPreferences("soup", Context.MODE_PRIVATE)
    private val last = mutableMapOf<String, MutableMap<String, Int>>()
    private val undo = mutableMapOf<String, Pair<Long, Map<String, Any?>>>()
    var announce: ((String) -> Unit)? = null

    init {
        runCatching {
            val o = JSONObject(prefs.getString("last", "{}"))
            o.keys().forEach { t -> val m = o.getJSONObject(t); last[t] = m.keys().asSequence().associateWith { m.getInt(it) }.toMutableMap() }
        }
    }

    private fun pots() = RemoteConfig.soups
    private fun rice() = RemoteConfig.rice
    private fun today(now: Long = System.currentTimeMillis()) = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date(now))
    private fun started(k: String) = if (k == RICE) prefs.getString("rice_day", "") == today() else prefs.getBoolean("started_$k", false) && prefs.contains("vats_$k")

    // ---------------- 바트 저장 ----------------

    private fun vats(key: String): MutableList<Vat> = runCatching {
        val a = JSONArray(prefs.getString("vats_$key", "[]"))
        (0 until a.length()).map { a.getJSONArray(it).let { v -> Vat(v.getString(0), v.getDouble(1), v.getDouble(2), v.getInt(3)) } }.toMutableList()
    }.getOrDefault(mutableListOf())

    private fun saveVats(key: String, list: List<Vat>) {
        // 다 쓴 바트는 지우되, 가장 새 배치(1/4·1/2)는 비어도 남겨 둠
        val newest = list.maxOfOrNull { it.batch } ?: 0
        val keep = list.filter { it.amt > 0.001 || it.batch == newest }
        prefs.edit().putString("vats_$key", JSONArray(keep.map { JSONArray().put(it.size).put(it.cap).put(it.amt).put(it.batch) }).toString()).apply()
    }

    private fun riceAmts(): MutableList<Double> = runCatching {
        val a = JSONArray(prefs.getString("rice_pots", "[]")); (0 until a.length()).map { a.getDouble(it) }.toMutableList()
    }.getOrDefault(mutableListOf())

    private fun saveRice(list: List<Double>) {
        val keep = list.filter { it > 0.001 }.ifEmpty { listOf(0.0) }
        prefs.edit().putString("rice_pots", JSONArray(keep).toString()).apply()
    }

    private fun cooking(): MutableList<Long> = runCatching {
        val a = JSONArray(prefs.getString("rice_cook", "[]")); (0 until a.length()).map { a.getLong(it) }.toMutableList()
    }.getOrDefault(mutableListOf())

    /** 판매(n>0)는 앞에서부터 빼고, 취소(n<0)는 지금 그릇에 되돌림(넘치면 앞 그릇으로) */
    private fun take(amts: MutableList<Double>, caps: List<Double>, n: Double) {
        if (amts.isEmpty()) return
        if (n > 0) {
            var left = n
            for (i in amts.indices) { val d = minOf(amts[i], left); amts[i] -= d; left -= d; if (left <= 0) break }
        } else {
            var left = -n
            val cur = amts.indexOfFirst { it > 0.001 }.let { if (it < 0) amts.size - 1 else it }
            var i = cur
            while (left > 0 && i >= 0) { val d = minOf(caps[i] - amts[i], left); amts[i] += d; left -= d; i-- }
            if (left > 0) amts[cur] += left
        }
    }

    // ---------------- 화면 읽기 반영 ----------------

    @Synchronized fun knows(table: String) = table in last

    @Synchronized fun observe(table: String, items: Map<String, Int>) {
        if (items.isEmpty()) return
        val before = last[table] ?: mutableMapOf()
        val now = mutableMapOf<String, Int>()
        var changed = table !in last
        fun one(key: String, n: Int) {
            if (n > 0) now[key] = n
            val d = n - (before[key] ?: 0)
            if (d != 0) { changed = true; sold(key, d, table) }
        }
        pots().forEach { one(it.key, it.count(items)) }
        one(RICE, rice().count(items))
        last[table] = now
        if (changed) saveLast()
    }

    @Synchronized fun release(table: String, cancelled: Boolean) {
        val m = last.remove(table) ?: return
        if (cancelled) m.forEach { (k, n) -> if (n > 0) sold(k, -n, "$table 취소") }
        saveLast()
    }

    @Synchronized fun move(from: String, to: String) {
        last.remove(from)?.let { last[to] = it; saveLast() }
    }

    private fun sold(key: String, n: Int, why: String) {
        if (!started(key)) return
        if (key == RICE) {
            val a = riceAmts(); take(a, a.map { maxOf(it, rice().perPot.toDouble()) }, n.toDouble()); saveRice(a)
            prefs.edit().putFloat("rice_used", prefs.getFloat("rice_used", 0f) + n).apply()
            Diag.add("공기밥 ${if (n > 0) "$n" else "취소 ${-n}"} ($why) → ${a.sum().roundToInt()}공기 남음")
            checkRice(System.currentTimeMillis())
            return
        }
        val p = pots().firstOrNull { it.key == key } ?: return
        val v = vats(key)
        val a = v.map { it.amt }.toMutableList()
        take(a, v.map { it.cap }, n.toDouble())
        v.forEachIndexed { i, x -> x.amt = a[i] }
        saveVats(key, v)
        Diag.add("${p.name} ${if (n > 0) "판매 $n" else "취소 ${-n}"} ($why) → ${"%.1f".format(a.sum())}${p.unit} 남음")
        checkSoup(p)
    }

    /** 마지막 1/2 바트만 남았고 그게 alertPct 이하 → 한 번 방송 */
    private fun soupLow(p: Pot): Boolean {
        if (!p.ready || !started(p.key)) return false
        val v = vats(p.key)
        val lastVat = v.lastOrNull() ?: return true
        val others = v.dropLast(1).sumOf { it.amt }
        return others <= 0.001 && lastVat.amt / lastVat.cap * 100 <= p.alertPct + 0.001
    }

    private fun checkSoup(p: Pot) {
        val low = soupLow(p)
        val alerted = prefs.getBoolean("alerted_${p.key}", false)
        if (low && !alerted) {
            prefs.edit().putBoolean("alerted_${p.key}", true).apply()
            Diag.add("${p.name} 1/2 바트 ${p.alertPct}% 이하 → 방송")
            if (p.say.isNotBlank()) announce?.invoke(p.say)
        } else if (!low && alerted) prefs.edit().putBoolean("alerted_${p.key}", false).apply()
    }

    // ---------------- 버튼 ----------------

    private fun snapshot(key: String) {
        val names = if (key == RICE) listOf("rice_day", "rice_used", "rice_cook", "rice_start", "rice_pots", "alerted_rice")
            else listOf("vats_$key", "started_$key", "alerted_$key")
        undo[key] = System.currentTimeMillis() to names.associateWith { prefs.all[it] }
    }

    /** 국: 조리완료(+1배치) / 밥: 새로 지음(40분 뒤 +1솥) */
    @Synchronized fun open(key: String): Boolean {
        val now = System.currentTimeMillis()
        if (key == RICE) {
            if (!started(RICE)) return false
            snapshot(key)
            val c = cooking().apply { add(now + rice().cookMin * 60_000L) }
            prefs.edit().putString("rice_cook", JSONArray(c).toString()).putBoolean("alerted_rice", false).apply()
            Diag.add("공기밥 새로 지음 (${rice().cookMin}분 뒤 +${rice().perPot}공기)")
            return true
        }
        val p = pots().firstOrNull { it.key == key && it.ready } ?: return false
        snapshot(key)
        val v = if (started(key)) vats(key) else mutableListOf()
        val b = (v.maxOfOrNull { it.batch } ?: -1) + 1
        VATS.forEach { (size, frac) -> v += Vat(size, p.pot * frac, p.pot * frac, b) }
        prefs.edit().putBoolean("started_$key", true).putBoolean("alerted_$key", false).apply()
        saveVats(key, v)
        Diag.add("${p.name} 조리완료 → ${"%.1f".format(v.sumOf { it.amt })}${p.unit} 남음")
        return true
    }

    /** 직원이 실제로 보고 고침: i 번째 바트/솥을 pct% 로 */
    @Synchronized fun set(key: String, i: Int, pct: Int): Boolean {
        if (!started(key) || pct !in 0..100) return false
        if (key == RICE) {
            val a = riceAmts(); if (i !in a.indices) return false
            snapshot(key)
            a[i] = rice().perPot * pct / 100.0
            if (pct > 0 || a.size == 1) prefs.edit().putString("rice_pots", JSONArray(a).toString()).apply() else saveRice(a)
            Diag.add("공기밥 ${i + 1}번째 솥 ${pct}% 로 고침 → ${riceAmts().sum().roundToInt()}공기")
            checkRice(System.currentTimeMillis())
            return true
        }
        val p = pots().firstOrNull { it.key == key } ?: return false
        val v = vats(key); if (i !in v.indices) return false
        snapshot(key)
        v[i].amt = v[i].cap * pct / 100.0
        saveVats(key, v)
        Diag.add("${p.name} ${v[i].size} 바트 ${pct}% 로 고침 → ${"%.1f".format(vats(key).sumOf { it.amt })}${p.unit}")
        checkSoup(p)
        return true
    }

    /**
     * 솥 수 맞추기 (0.5 단위, 하루 중 언제든). 반 솥이 있으면 그게 '지금' 솥.
     * 그날 처음이면 그때부터 셈 시작, 이미 세는 중이면 남은 밥만 맞춤(짓는 중인 밥·오늘 판매 기록은 그대로).
     */
    @Synchronized fun riceStart(pots: Double): Boolean {
        if (pots < 0 || pots > 20) return false
        snapshot(RICE)
        val now = System.currentTimeMillis(); val per = rice().perPot.toDouble()
        val full = pots.toInt(); val part = pots - full
        val a = (if (part > 0.01) listOf(part * per) else emptyList()) + List(full) { per }
        if (!started(RICE)) prefs.edit().putString("rice_day", today(now)).putFloat("rice_used", 0f).putLong("rice_start", now)
            .putString("rice_cook", "[]").putBoolean("alerted_rice", false).apply()
        saveRice(a)
        Diag.add("공기밥 솥 수 맞춤: ${pots}솥")
        checkRice(now)
        return true
    }

    /** 방금 누른 것 되돌리기 (15초 안) */
    @Synchronized fun undo(key: String): Boolean {
        val (at, m) = undo.remove(key) ?: return false
        if (System.currentTimeMillis() - at > UNDO_MS) return false
        val e = prefs.edit()
        m.forEach { (k, v) ->
            when (v) {
                null -> e.remove(k); is Float -> e.putFloat(k, v); is Int -> e.putInt(k, v); is Long -> e.putLong(k, v)
                is Boolean -> e.putBoolean(k, v); is String -> e.putString(k, v)
            }
        }
        e.apply()
        Diag.add("$key 되돌리기")
        return true
    }

    // ---------------- 밥 예측 ----------------

    private fun minuteOfDay(now: Long) = Calendar.getInstance().apply { timeInMillis = now }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) + it.get(Calendar.SECOND) / 60.0 }
    private fun weekend(now: Long) = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }

    /** 지난 1달 평균으로 [from, to) 분 사이에 쓰는 공기 수 */
    private fun usage(from: Double, to: Double, wk: Boolean): Double {
        val r = rice(); val prof = if (wk) r.weekend else r.weekday
        var sum = 0.0
        prof.forEach { (s, v) ->
            val a = maxOf(from, s.toDouble()); val b = minOf(to, (s + r.slotMin).toDouble())
            if (b > a) sum += v * (b - a) / r.slotMin
        }
        return sum
    }

    private data class Plan(val toClose: Double, val runOut: Int, val low: Boolean)

    private fun plan(now: Long): Plan {
        val r = rice(); val wk = weekend(now); val m = minuteOfDay(now)
        val start = minuteOfDay(prefs.getLong("rice_start", now)).coerceAtMost(m)
        val exp = usage(start, m, wk)
        val used = prefs.getFloat("rice_used", 0f).toDouble()
        val f = if (exp >= 5) (used / exp).coerceIn(0.6, 1.6) else 1.0
        val close = r.closeMin.toDouble()
        val remain = riceAmts().sum()
        val cook = cooking()
        val toClose = usage(m, close, wk) * f
        var cum = 0.0; var runOut = -1; var t = m
        while (t < close) {
            cum += usage(t, t + 1, wk) * f
            val ready = cook.count { it <= now + ((t + 1 - m) * 60_000).toLong() } * r.perPot
            if (cum > remain + ready) { runOut = (t + 1 - m).roundToInt(); break }
            t += 1.0
        }
        val soon = usage(m, m + r.cookMin, wk) * f
        val readySoon = cook.count { it <= now + r.cookMin * 60_000L } * r.perPot
        val low = remain + readySoon - soon < r.margin && toClose > remain + cook.size * r.perPot
        return Plan(toClose, runOut, low)
    }

    /** 다 지어진 밥 더하기 + 밥 지을지 판단 (몇 초마다 불러도 됨) */
    @Synchronized fun tick(now: Long) {
        if (!started(RICE)) return
        val c = cooking()
        val done = c.filter { it <= now }
        if (done.isNotEmpty()) {
            c.removeAll(done)
            val a = riceAmts().filter { it > 0.001 }.toMutableList()
            repeat(done.size) { a += rice().perPot.toDouble() }
            prefs.edit().putString("rice_cook", JSONArray(c).toString()).apply()
            saveRice(a)
            Diag.add("공기밥 다 지음 → ${a.sum().roundToInt()}공기 남음")
        }
        checkRice(now)
    }

    private fun checkRice(now: Long) {
        if (!started(RICE) || rice().weekday.isEmpty()) return
        if (minuteOfDay(now) >= rice().closeMin) return
        val low = plan(now).low
        val alerted = prefs.getBoolean("alerted_rice", false)
        if (low && !alerted) {
            prefs.edit().putBoolean("alerted_rice", true).apply()
            Diag.add("공기밥 부족 예상 → 방송")
            if (rice().say.isNotBlank()) announce?.invoke(rice().say)
        } else if (!low && alerted) prefs.edit().putBoolean("alerted_rice", false).apply()
    }

    // ---------------- 직원 폰 앱용 ----------------

    @Synchronized fun json(): String {
        val now = System.currentTimeMillis()
        val arr = JSONArray()
        pots().forEach { p ->
            val st = started(p.key)
            val v = if (st) vats(p.key) else VATS.map { (s, f) -> Vat(s, p.pot * f, 0.0, 0) }
            val multi = v.map { it.batch }.distinct().size > 1
            val newest = v.maxOfOrNull { it.batch } ?: 0
            val nowIdx = v.indexOfFirst { it.amt > 0.001 }
            val va = JSONArray()
            v.forEachIndexed { i, x ->
                val pre = if (!multi) "" else if (x.batch == newest) "새 " else "남은 "
                va.put(JSONObject().put("i", i).put("size", x.size).put("pct", (x.amt / x.cap * 100).roundToInt().coerceIn(0, 100))
                    .put("serv", x.amt).put("cap", x.cap).put("now", st && i == nowIdx)
                    .put("label", pre + x.size + if (!st) "" else if (i == nowIdx) " · 지금" else if (x.amt <= 0.001) " · 다 씀" else ""))
            }
            arr.put(JSONObject().put("key", p.key).put("kind", "soup").put("name", p.name).put("ready", p.ready)
                .put("started", st).put("remain", v.sumOf { it.amt }.roundToInt()).put("pot", p.pot).put("alertPct", p.alertPct)
                .put("unit", p.unit).put("action", p.action).put("say", p.say).put("low", soupLow(p)).put("vats", va))
        }
        val rc = rice()
        val o = JSONObject().put("key", RICE).put("kind", "rice").put("name", "공기밥").put("ready", rc.perPot > 0 && rc.menus.isNotEmpty())
            .put("started", started(RICE)).put("perPot", rc.perPot).put("cookMin", rc.cookMin).put("unit", "공기").put("say", rc.say)
        // 아침 준비용: 최근 평일/주말 하루 평균 사용량
        val wkToday = weekend(now)
        val avgBowls = usage(0.0, 1440.0, wkToday)
        o.put("weekend", wkToday).put("avgBowls", avgBowls.roundToInt())
            .put("avgPots", if (rc.perPot > 0) (avgBowls / rc.perPot * 10).roundToInt() / 10.0 else 0.0)
        if (started(RICE)) {
            tick(now)
            val a = riceAmts()
            val pl = plan(now)
            o.put("remain", a.sum().roundToInt()).put("potsNow", (a.sum() / rc.perPot * 10).roundToInt() / 10.0)
                .put("pots", JSONArray(a.map { (it / rc.perPot * 100).roundToInt().coerceIn(0, 100) }))
                .put("cooking", JSONArray(cooking().map { kotlin.math.ceil(((it - now) / 60_000.0).coerceAtLeast(0.0)).toInt() }))
                .put("toClose", pl.toClose.roundToInt()).put("runOut", pl.runOut).put("low", pl.low)
                .put("closed", minuteOfDay(now) >= rc.closeMin)
        }
        arr.put(o)
        return JSONObject().put("items", arr).put("now", now).toString()
    }

    private fun saveLast() {
        val o = JSONObject()
        last.forEach { (t, m) -> o.put(t, JSONObject(m as Map<*, *>)) }
        prefs.edit().putString("last", o.toString()).apply()
    }
}
