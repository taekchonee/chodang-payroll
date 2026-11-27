package com.chodang.servemark

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 진단 기록: 최근 신호와 마지막 화면 구조 (앱 화면·진단 오버레이에서 봄). */
object Diag {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    /** 패키지별 신호 수 (이지포스 패키지 이름을 알아내기 위함) */
    val packages = linkedMapOf<String, Int>()
    @Volatile var lastTree: String = "(아직 기록 없음)"
    @Volatile var lastScan: String = "(아직 없음)"

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    @Synchronized fun add(line: String) {
        lines.addLast("${fmt.format(Date())} $line")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized fun countPackage(p: String) {
        packages[p] = (packages[p] ?: 0) + 1
    }

    @Synchronized fun recent(n: Int): List<String> = lines.toList().takeLast(n)

    @Synchronized fun clear() { lines.clear(); packages.clear() }
}
