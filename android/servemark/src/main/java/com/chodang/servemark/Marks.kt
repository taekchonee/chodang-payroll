package com.chodang.servemark

import android.content.Context

/** 서빙완료로 표시된 테이블 이름들 (앱을 다시 켜도 유지). */
class Marks(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("marks", Context.MODE_PRIVATE)

    fun all(): Set<String> = prefs.getStringSet("tables", emptySet())!!.toSet()

    fun has(table: String) = table in all()

    fun add(table: String) { save(all() + table); prefs.edit().remove("prio:$table").apply() }
    fun remove(table: String) = save(all() - table)
    fun toggle(table: String): Boolean {
        val on = !has(table)
        if (on) add(table) else remove(table)
        return on
    }
    fun clear() = save(emptySet())

    private fun save(s: Set<String>) { prefs.edit().putStringSet("tables", s).apply() }

    // ---- 서빙 순서 (테이블별 주문 시작 시각·금액·우선 시각) ----
    fun orderSince(t: String) = prefs.getLong("seen:$t", 0L)
    fun lastAmount(t: String) = prefs.getLong("amt:$t", 0L)
    fun priority(t: String) = prefs.getLong("prio:$t", 0L)
    fun startOrder(t: String, at: Long, amount: Long) {
        prefs.edit().putLong("seen:$t", at).putLong("amt:$t", amount).remove("prio:$t").apply()
    }
    fun setAmount(t: String, amount: Long) { prefs.edit().putLong("amt:$t", amount).apply() }
    fun setPriority(t: String, at: Long) { prefs.edit().putLong("prio:$t", at).apply() }
    fun forgetOrder(t: String) {
        if (orderSince(t) != 0L) prefs.edit().remove("seen:$t").remove("amt:$t").remove("prio:$t").apply()
    }

    /** 테이블 이동: 서빙완료 표시와 순서 기록을 새 테이블로 옮깁니다. */
    fun move(from: String, to: String) {
        val wasMarked = has(from)
        val e = prefs.edit()
        listOf("seen:", "amt:", "prio:").forEach { k ->
            if (prefs.contains(k + from)) e.putLong(k + to, prefs.getLong(k + from, 0L))
            e.remove(k + from)
        }
        e.apply()
        val s = all() - from
        save(if (wasMarked) s + to else s)
    }

    /** 진단 표시(화면 왼쪽 위 신호 기록)를 켤지 */
    var diagOverlay: Boolean
        get() = prefs.getBoolean("diag", false)
        set(v) { prefs.edit().putBoolean("diag", v).apply() }

    /** 이지포스 패키지 이름 (비어 있으면 자동으로 찾음) */
    var targetPackage: String
        get() = prefs.getString("target", "") ?: ""
        set(v) { prefs.edit().putString("target", v).apply() }
}
