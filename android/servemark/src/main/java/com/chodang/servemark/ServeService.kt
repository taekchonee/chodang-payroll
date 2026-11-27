package com.chodang.servemark

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 이지포스 화면을 '읽기만' 합니다(누르거나 바꾸지 않음).
 * - 테이블을 길게 누르면 그 테이블에 '서빙완료' 표시
 * - 테이블에 금액이 사라지면(결제 완료, 하늘색으로 돌아옴) 표시 삭제
 * 표시는 터치를 통과시키는 투명 창(TYPE_ACCESSIBILITY_OVERLAY)에 그립니다.
 */
class ServeService : AccessibilityService() {

    companion object {
        @Volatile var instance: ServeService? = null
            private set
        private val TABLE_LABEL = Regex("^(\\d{1,3}|포장\\s*\\d{0,3})$")
        private val AMOUNT = Regex("[\\d,]{3,}\\s*원")
    }

    /** 화면에서 찾은 테이블 하나 */
    data class Table(val label: String, val bounds: Rect, val occupied: Boolean, val texts: List<String>)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var marks: Marks
    private lateinit var wm: WindowManager
    private var overlay: OverlayView? = null
    private var visibleTables: List<Table> = emptyList()
    private var targetVisible = false
    private val scanRunnable = Runnable { scan() }
    private val ticker = object : Runnable {
        override fun run() { scan(); handler.postDelayed(this, 3000) }
    }

    override fun onServiceConnected() {
        instance = this
        marks = Marks(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        addOverlay()
        Diag.add("서비스 시작")
        handler.post(ticker)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ------------------------------------------------------------------
    // 신호
    // ------------------------------------------------------------------

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        Diag.countPackage(pkg)
        if (marks.targetPackage.isEmpty() && looksLikePos(pkg)) {
            marks.targetPackage = pkg
            Diag.add("이지포스 패키지 찾음: $pkg")
        }
        // 화면이 자주 바뀌는 신호는 기록을 줄입니다.
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            Diag.add(describe(e))
            overlay?.invalidate()
        }
        if (pkg != marks.targetPackage) return

        if (e.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) onLongPress(e)

        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, 250)
    }

    private fun looksLikePos(pkg: String) =
        pkg.contains("easypos", true) || pkg.contains("kicc", true) || pkg.contains("easy_pos", true)

    private fun describe(e: AccessibilityEvent): String {
        val src = e.source
        val b = Rect().also { src?.getBoundsInScreen(it) }
        val id = src?.viewIdResourceName?.substringAfter(":id/")
        val txt = (e.text.joinToString("/").ifBlank { src?.text?.toString() ?: "" }).take(40)
        val desc = (e.contentDescription ?: src?.contentDescription)?.toString()?.take(30)
        return buildString {
            append(AccessibilityEvent.eventTypeToString(e.eventType).removePrefix("TYPE_"))
            append(" ").append(e.packageName?.toString()?.substringAfterLast('.'))
            append(" ").append(e.className?.toString()?.substringAfterLast('.'))
            if (txt.isNotBlank()) append(" \"").append(txt).append('"')
            if (!desc.isNullOrBlank()) append(" d=").append(desc)
            if (id != null) append(" #").append(id)
            if (src != null) append(" [${b.left},${b.top},${b.right},${b.bottom}]")
        }
    }

    // ------------------------------------------------------------------
    // 길게 누르기 → 표시 켜기/끄기
    // ------------------------------------------------------------------

    private fun onLongPress(e: AccessibilityEvent) {
        val src = e.source
        val label = src?.let { tableLabelIn(it) } ?: run {
            // 누른 자리를 포함하는 테이블을 찾습니다.
            val r = Rect().also { src?.getBoundsInScreen(it) }
            visibleTables.firstOrNull { it.bounds.contains(r.centerX(), r.centerY()) }?.label
        }
        if (label == null) {
            Diag.add("길게 누름: 테이블을 못 찾음")
            return
        }
        val table = visibleTables.firstOrNull { it.label == label }
        if (table != null && !table.occupied && !marks.has(label)) {
            Diag.add("길게 누름: $label 은 빈 테이블이라 표시 안 함")
            return
        }
        val on = marks.toggle(label)
        Diag.add("길게 누름: $label 서빙완료 ${if (on) "표시" else "해제"}")
        overlay?.invalidate()
    }

    private fun tableLabelIn(n: AccessibilityNodeInfo): String? {
        val texts = mutableListOf<String>()
        collectTexts(n, texts, 0)
        return texts.firstOrNull { TABLE_LABEL.matches(it) }
    }

    // ------------------------------------------------------------------
    // 화면 읽기: 테이블 위치·금액 유무
    // ------------------------------------------------------------------

    fun scan() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        targetVisible = pkg != null && pkg == marks.targetPackage
        if (root == null || !targetVisible) {
            visibleTables = emptyList()
            overlay?.invalidate()
            return
        }
        val found = mutableListOf<Table>()
        findTables(root, found, 0)
        // 같은 테이블이 겹쳐 잡히면 가장 작은 칸만 남깁니다.
        visibleTables = found.groupBy { it.label }.map { (_, list) -> list.minByOrNull { it.bounds.width() * it.bounds.height() }!! }
        Diag.lastScan = "테이블 ${visibleTables.size}개: " + visibleTables.joinToString(" ") { it.label + if (it.occupied) "(주문)" else "" }

        // 테이블 화면이 확실할 때만(5개 이상 보일 때) 결제 완료를 판단해 표시를 지웁니다.
        if (visibleTables.size >= 5) {
            val byLabel = visibleTables.associateBy { it.label }
            marks.all().forEach { m ->
                val t = byLabel[m]
                if (t != null && !t.occupied) {
                    marks.remove(m)
                    Diag.add("$m 결제 완료 → 서빙완료 표시 삭제")
                }
            }
        }
        overlay?.invalidate()
    }

    private fun findTables(n: AccessibilityNodeInfo, out: MutableList<Table>, depth: Int) {
        if (depth > 40) return
        val texts = mutableListOf<String>()
        collectTexts(n, texts, 0)
        val label = texts.firstOrNull { TABLE_LABEL.matches(it) }
        val isCell = label != null && (n.isClickable || n.isLongClickable) && texts.count { TABLE_LABEL.matches(it) } == 1
        if (isCell) {
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r.width() > 20 && r.height() > 20) {
                out += Table(label!!, r, texts.any { AMOUNT.containsMatchIn(it) }, texts)
                return
            }
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { findTables(it, out, depth + 1) }
    }

    private fun collectTexts(n: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 12 || out.size > 40) return
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it.lines().map { l -> l.trim() }.filter { l -> l.isNotEmpty() } }
        n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        for (i in 0 until n.childCount) n.getChild(i)?.let { collectTexts(it, out, depth + 1) }
    }

    /** 지금 화면의 구조를 글로 남깁니다(진단용). */
    fun dumpTree(): String {
        val sb = StringBuilder()
        val ws = runCatching { windows }.getOrNull().orEmpty()
        if (ws.isEmpty()) rootInActiveWindow?.let { dumpNode(it, sb, 0) }
        ws.forEach { w ->
            sb.append("== 창 ${w.type} ${w.title ?: ""}\n")
            w.root?.let { dumpNode(it, sb, 0) }
        }
        Diag.lastTree = sb.toString().ifEmpty { "(화면 구조를 못 읽음)" }
        Diag.add("화면 구조 기록 (${Diag.lastTree.lines().size}줄)")
        return Diag.lastTree
    }

    private fun dumpNode(n: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 30 || sb.length > 60_000) return
        val r = Rect().also { n.getBoundsInScreen(it) }
        sb.append("  ".repeat(depth))
            .append(n.className?.toString()?.substringAfterLast('.'))
        n.viewIdResourceName?.let { sb.append(" #").append(it.substringAfter(":id/")) }
        n.text?.let { sb.append(" \"").append(it.toString().replace("\n", "⏎").take(40)).append('"') }
        n.contentDescription?.let { sb.append(" d=").append(it.toString().take(30)) }
        if (n.isClickable) sb.append(" C")
        if (n.isLongClickable) sb.append(" L")
        sb.append(" [${r.left},${r.top},${r.right},${r.bottom}]\n")
        for (i in 0 until n.childCount) n.getChild(i)?.let { dumpNode(it, sb, depth + 1) }
    }

    fun refresh() { scan(); overlay?.invalidate() }

    // ------------------------------------------------------------------
    // 표시 창 (터치는 그대로 아래 이지포스로)
    // ------------------------------------------------------------------

    private fun addOverlay() {
        val v = OverlayView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(v, lp)
        overlay = v
    }

    private inner class OverlayView(ctx: Context) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E615803D") }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#15803D"); style = Paint.Style.STROKE; strokeWidth = 3 * d
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 15 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        private val diagBg = Paint().apply { color = Color.parseColor("#CC000000") }
        private val diagText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7CFC9A"); textSize = 10 * d }
        private val loc = IntArray(2)

        override fun onDraw(c: Canvas) {
            getLocationOnScreen(loc)
            if (targetVisible) {
                val marked = marks.all()
                visibleTables.filter { it.label in marked }.forEach { t ->
                    val r = RectF(t.bounds).apply { offset(-loc[0].toFloat(), -loc[1].toFloat()) }
                    c.drawRect(r, stroke)
                    val h = minOf(28 * d, r.height() / 2.5f)
                    val band = RectF(r.left, r.bottom - h, r.right, r.bottom)
                    c.drawRect(band, fill)
                    c.drawText("서빙완료", band.centerX(), band.centerY() + text.textSize / 3, text)
                }
            }
            if (marks.diagOverlay) {
                val lines = listOf(Diag.lastScan.take(70)) + Diag.recent(6).map { it.take(70) }
                val lh = diagText.textSize * 1.3f
                val w = 460 * d
                c.drawRect(0f, height - lh * lines.size - 8 * d, w, height.toFloat() - 0f, diagBg)
                lines.forEachIndexed { i, l -> c.drawText(l, 6 * d, height - lh * (lines.size - i - 1) - 6 * d, diagText) }
            }
        }
    }
}
