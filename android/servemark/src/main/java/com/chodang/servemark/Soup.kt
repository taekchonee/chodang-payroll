package com.chodang.servemark

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 국(·공기밥) 남은 양. 새 냄비를 열면(+1회 양) 그때부터 판매만큼 빼고, 취소되면 다시 더합니다.
 * 날짜·재부팅과 무관하게 이어서 셉니다. 남은 양이 알림 기준 아래로 떨어지면 한 번 방송("황태해장국 끓이세요").
 * 설정(메뉴 이름·1회 양·알림 기준)은 settings.json 의 "soups".
 * 테이블마다 마지막으로 본 국 수량을 기억해 두고, 바뀐 만큼만 셉니다(포장 포함).
 */
class Soup(context: Context) {

    data class Pot(val key: String, val name: String, val menus: List<String>, val pot: Int, val alert: Int,
                   val unit: String, val say: String, val action: String) {
        val ready get() = pot > 0 && menus.isNotEmpty()
        fun count(items: Map<String, Int>) = items.entries.filter { e -> menus.any { e.key.contains(it) } }.sumOf { it.value }
    }

    private val prefs = context.applicationContext.getSharedPreferences("soup", Context.MODE_PRIVATE)
    /** 테이블별 마지막으로 본 국 수량 (key → 개수) */
    private val last = mutableMapOf<String, MutableMap<String, Int>>()
    var announce: ((String) -> Unit)? = null

    init {
        runCatching {
            val o = JSONObject(prefs.getString("last", "{}"))
            o.keys().forEach { t -> val m = o.getJSONObject(t); last[t] = m.keys().asSequence().associateWith { m.getInt(it) }.toMutableMap() }
        }
    }

    private fun pots() = RemoteConfig.soups

    private fun remain(k: String) = prefs.getInt("remain_$k", 0)
    private fun started(k: String) = prefs.getBoolean("started_$k", false)

    @Synchronized fun knows(table: String) = table in last

    /** 테이블에 보이는 메뉴를 반영 (늘면 판매, 줄면 취소) */
    @Synchronized fun observe(table: String, items: Map<String, Int>) {
        if (items.isEmpty()) return
        val before = last[table] ?: mutableMapOf()
        val now = mutableMapOf<String, Int>()
        var changed = table !in last
        pots().forEach { p ->
            val n = p.count(items)
            if (n > 0) now[p.key] = n
            val d = n - (before[p.key] ?: 0)
            if (d != 0) { changed = true; sold(p, d, table) }
        }
        last[table] = now
        if (changed) saveLast()
    }

    /** 테이블이 비었을 때: 취소면 되돌려 더하고, 결제면 그냥 잊음 */
    @Synchronized fun release(table: String, cancelled: Boolean) {
        val m = last.remove(table) ?: return
        if (cancelled) pots().forEach { p -> m[p.key]?.let { if (it > 0) sold(p, -it, "$table 취소") } }
        saveLast()
    }

    @Synchronized fun move(from: String, to: String) {
        last.remove(from)?.let { last[to] = it; saveLast() }
    }

    /** 새 냄비를 엶: 남은 양 + 1회 양 (처음이면 1회 양부터 시작) */
    @Synchronized fun open(key: String): Boolean {
        val p = pots().firstOrNull { it.key == key && it.ready } ?: return false
        val r = (if (started(key)) remain(key) else 0) + p.pot
        prefs.edit().putInt("remain_$key", r).putBoolean("started_$key", true).putBoolean("alerted_$key", false).apply()
        Diag.add("${p.name} ${p.action} → ${r}${p.unit} 남음")
        return true
    }

    private fun sold(p: Pot, n: Int, why: String) {
        if (!started(p.key)) return
        val r = remain(p.key) - n
        val e = prefs.edit().putInt("remain_${p.key}", r)
        Diag.add("${p.name} ${if (n > 0) "판매 $n" else "취소 ${-n}"} ($why) → ${r}${p.unit} 남음")
        val alerted = prefs.getBoolean("alerted_${p.key}", false)
        if (r < p.alert && !alerted) {
            e.putBoolean("alerted_${p.key}", true)
            if (p.say.isNotBlank()) announce?.invoke(p.say)
        } else if (r >= p.alert && alerted) e.putBoolean("alerted_${p.key}", false)
        e.apply()
    }

    /** 직원 폰 앱용 */
    @Synchronized fun json(): String {
        val arr = JSONArray()
        pots().forEach { p ->
            val r = remain(p.key)
            arr.put(JSONObject().put("key", p.key).put("name", p.name).put("ready", p.ready)
                .put("started", started(p.key)).put("remain", r).put("pot", p.pot).put("alert", p.alert)
                .put("unit", p.unit).put("action", p.action).put("say", p.say)
                .put("low", p.ready && started(p.key) && r < p.alert))
        }
        return JSONObject().put("items", arr).put("now", System.currentTimeMillis()).toString()
    }

    private fun saveLast() {
        val o = JSONObject()
        last.forEach { (t, m) -> o.put(t, JSONObject(m as Map<*, *>)) }
        prefs.edit().putString("last", o.toString()).apply()
    }
}
