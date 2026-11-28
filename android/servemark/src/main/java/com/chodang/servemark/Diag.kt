package com.chodang.servemark

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** 진단 기록: 메모리 최근 300줄 + 기기 내부 파일(최근 7일)에 모든 앱 로그를 저장합니다. */
object Diag {
    private const val MAX = 300
    private const val KEEP_DAYS = 7L
    private val lines = ArrayDeque<String>()
    /** 패키지별 신호 수 (이지포스 패키지 이름을 알아내기 위함) */
    val packages = linkedMapOf<String, Int>()
    @Volatile var lastTree: String = "(아직 기록 없음)"
    @Volatile var lastScan: String = "(아직 없음)"

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
    private var logDir: File? = null
    private val io = Executors.newSingleThreadExecutor()

    fun init(ctx: Context) {
        val dir = File(ctx.applicationContext.filesDir, "logs").apply { mkdirs() }
        logDir = dir
        io.execute {
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
            dir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".log") && f.lastModified() < cutoff) f.delete()
            }
        }
    }

    fun add(line: String) = add("servemark", line)

    @Synchronized fun add(app: String, line: String) {
        val stamp = timeFmt.format(Date())
        val tag = if (app == "servemark") "" else "[$app] "
        val entry = "$stamp $tag$line"
        lines.addLast(entry)
        while (lines.size > MAX) lines.removeFirst()
        val dir = logDir ?: return
        val day = dayFmt.format(Date())
        io.execute {
            runCatching { File(dir, "$day.log").appendText("$entry\n", Charsets.UTF_8) }
        }
    }

    @Synchronized fun countPackage(p: String) {
        packages[p] = (packages[p] ?: 0) + 1
    }

    @Synchronized fun recent(n: Int): List<String> = lines.toList().takeLast(n)

    fun readLogs(app: String? = null, limit: Int = 500): String {
        val dir = logDir
        val fromFile = if (dir != null) {
            runCatching {
                val files = dir.listFiles { f -> f.name.endsWith(".log") }
                    ?.sortedBy { it.name }?.takeLast(2).orEmpty()
                val all = files.flatMap { it.readLines(Charsets.UTF_8) }
                if (app.isNullOrBlank() || app == "all") all
                else if (app == "servemark") all.filter { !it.contains("] ") || it.substringAfter(" ").startsWith("[servemark]") }
                else all.filter { it.contains("[$app] ") }
            }.getOrNull()
        } else null
        val list = if (!fromFile.isNullOrEmpty()) fromFile.takeLast(limit) else recent(limit)
        return list.joinToString("\n")
    }

    @Synchronized fun clear() { lines.clear(); packages.clear() }
}
