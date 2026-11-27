package com.chodang.servemark

import android.content.Context

/** 서빙완료로 표시된 테이블 이름들 (앱을 다시 켜도 유지). */
class Marks(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("marks", Context.MODE_PRIVATE)

    fun all(): Set<String> = prefs.getStringSet("tables", emptySet())!!.toSet()

    fun has(table: String) = table in all()

    fun add(table: String) = save(all() + table)
    fun remove(table: String) = save(all() - table)
    fun toggle(table: String): Boolean {
        val on = !has(table)
        if (on) add(table) else remove(table)
        return on
    }
    fun clear() = save(emptySet())

    private fun save(s: Set<String>) { prefs.edit().putStringSet("tables", s).apply() }

    /** 진단 표시(화면 왼쪽 위 신호 기록)를 켤지 */
    var diagOverlay: Boolean
        get() = prefs.getBoolean("diag", false)
        set(v) { prefs.edit().putBoolean("diag", v).apply() }

    /** 이지포스 패키지 이름 (비어 있으면 자동으로 찾음) */
    var targetPackage: String
        get() = prefs.getString("target", "") ?: ""
        set(v) { prefs.edit().putString("target", v).apply() }
}
