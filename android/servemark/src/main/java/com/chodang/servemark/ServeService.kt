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
import android.widget.TextView

/**
 * 이지포스 화면을 읽습니다. 누르는 것은 서빙완료 표시 직후 열린 주문 화면의 X 하나뿐입니다.
 * 주문 화면의 할인·서비스 버튼 자리에 서빙완료 버튼을 덮어 띄웁니다(그 두 버튼은 가려짐).
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
        /** 조리완료 후 이 시간이 지나면 홀에 '서빙 확인' 깜빡임 */
        const val SERVE_CHECK_MS = 1 * 60_000L
        /** 조리완료 후 이 시간이 지나면 자동 서빙완료 */
        const val AUTO_SERVE_MS = 3 * 60_000L
        private val ITEM = Regex("^(.*\\D)\\s+(\\d{1,3})$")
    }

    /** 화면에서 찾은 테이블 하나 */
    data class Table(val label: String, val bounds: Rect, val occupied: Boolean, val texts: List<String>)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var marks: Marks
    lateinit var kitchen: Kitchen
        private set
    private var kds: KdsServer? = null
    private lateinit var wm: WindowManager
    private var overlay: OverlayView? = null
    private var visibleTables: List<Table> = emptyList()
    private var targetVisible = false
    private val scanRunnable = Runnable { scan() }
    private val ticker = object : Runnable {
        override fun run() {
            if (::kitchen.isInitialized) kitchen.expire(System.currentTimeMillis())
            scan(); handler.postDelayed(this, 3000)
        }
    }

    override fun onServiceConnected() {
        instance = this
        marks = Marks(this)
        kitchen = Kitchen(this)
        kds = KdsServer(this, kitchen) { overlay?.invalidate() }.also { it.start() }
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
        kds?.stop()
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        hideSaleButton()
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
        // 다른 앱(카톡 등)의 글자는 기록하지 않습니다. 이지포스 신호만, 자주 바뀌는 신호는 빼고.
        if (pkg != marks.targetPackage) { overlay?.invalidate(); return }
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            Diag.add(describe(e))
            overlay?.invalidate()
        }

        if (e.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) onLongPress(e)
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWindowClass = e.className?.toString() ?: ""
            // 이지포스의 "주문을 취소하시겠습니까?" 창 → 곧 테이블이 비면 결제가 아니라 취소로 봅니다.
            if (lastWindowClass.contains("AlertDialog") && e.text.joinToString(" ").contains("취소")) {
                cancelPromptAt = System.currentTimeMillis()
            }
            if (System.currentTimeMillis() < closeDeadline) handler.post(closeOrderScreen)
        }

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
        // 서빙완료 → 주방에서 숨김, 해제 → 되살림
        if (on) kitchen.serve(label, System.currentTimeMillis()) else kitchen.unserve(label)
        Diag.add("길게 누름: $label 서빙완료 ${if (on) "표시" else "해제"}")
        overlay?.invalidate()
        handler.post(scanRunnable)
        // 손을 떼면 이지포스가 일반 터치로도 받아 주문 화면(매출등록)을 엽니다 → 4초 안에 열리면 X 를 한 번 눌러 닫습니다.
        closeDeadline = System.currentTimeMillis() + 4000
    }

    // ------------------------------------------------------------------
    // 길게 누른 직후 열린 주문 화면 닫기 (이 앱이 이지포스를 누르는 유일한 곳)
    // ------------------------------------------------------------------

    @Volatile private var closeDeadline = 0L
    @Volatile private var lastWindowClass = ""

    private val closeOrderScreen = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() >= closeDeadline) return
            // 주문 화면(EasySale)일 때만, 그 화면의 X(btnClose) 하나만 누릅니다.
            if (!lastWindowClass.endsWith("EasySale")) return
            val root = rootInActiveWindow ?: return run { handler.postDelayed(this, 200) }
            if (root.packageName?.toString() != marks.targetPackage) return
            val btn = root.findAccessibilityNodeInfosByViewId("${marks.targetPackage}:id/btnClose")
                .firstOrNull { it.isVisibleToUser && (it.isClickable || it.parent?.isClickable == true) }
            if (btn == null) { handler.postDelayed(this, 200); return }
            closeDeadline = 0L  // 한 번만
            val target = if (btn.isClickable) btn else btn.parent
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Diag.add("주문 화면 자동 닫기: ${if (ok) "성공" else "실패"}")
        }
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
            hideSaleButton()
            overlay?.invalidate()
            return
        }
        updateSaleButton(root)
        val found = mutableListOf<Table>()
        findTables(root, found, 0)
        // 같은 테이블이 겹쳐 잡히면 가장 작은 칸만 남깁니다.
        visibleTables = found.groupBy { it.label }.map { (_, list) -> list.minByOrNull { it.bounds.width() * it.bounds.height() }!! }
        Diag.lastScan = "테이블 ${visibleTables.size}개: " + visibleTables.joinToString(" ") { it.label + if (it.occupied) "(주문)" else "" }

        // 테이블 화면이 확실할 때만(5개 이상 보일 때) 결제 완료·새 주문·추가 주문을 판단합니다.
        if (visibleTables.size >= 5) {
            updateQueue()
        }
        overlay?.invalidate()
    }

    // ------------------------------------------------------------------
    // 서빙 순서: 주문이 처음 보인 순서. 서빙을 끝낸 테이블에 추가 주문이 오면 맨 앞(우선).
    // ------------------------------------------------------------------

    /** 서빙을 기다리는 테이블 (앞이 다음 서빙). 포장 제외. */
    @Volatile var queue: List<String> = emptyList()
        private set

    private fun amountOf(t: Table): Long =
        t.texts.mapNotNull { AMOUNT.find(it)?.value?.filter(Char::isDigit)?.toLongOrNull() }.maxOrNull() ?: 0L

    /** 테이블 칸 글씨에서 메뉴와 수량을 읽습니다 (예: "짬뽕순두부 1"). */
    private fun itemsOf(t: Table): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        t.texts.forEach { line ->
            if (TABLE_LABEL.matches(line) || AMOUNT.containsMatchIn(line)) return@forEach
            ITEM.matchEntire(line)?.let { r ->
                val name = r.groupValues[1].trim()
                m[name] = (m[name] ?: 0) + r.groupValues[2].toInt()
            }
        }
        return m
    }

    /** 방금 비워진 테이블: 몇 초 기다려 '이동'인지 '취소'인지 '결제'인지 가립니다. */
    private data class Gone(val items: Map<String, Int>, val at: Long)
    private val gone = mutableMapOf<String, Gone>()
    @Volatile private var cancelPromptAt = 0L

    /** 비워진 테이블 정리: 취소 창이 떴었으면 주방에 '주문 취소', 아니면 결제로 보고 조용히 정리 */
    private fun finishGone(table: String, g: Gone) {
        gone.remove(table)
        val cancelled = g.at - cancelPromptAt in 0..60_000
        if (cancelled) {
            kitchen.voidTable(table, g.at)
            Diag.add("$table 주문 취소 → 주방에 취소 표시")
        } else {
            kitchen.clearTable(table, forget = true)
            if (marks.has(table)) Diag.add("$table 결제 완료 → 서빙완료 표시 삭제")
        }
        marks.remove(table)
        marks.forgetOrder(table)
    }

    private fun updateQueue() {
        val now = System.currentTimeMillis()
        val byLabel = visibleTables.associateBy { it.label }
        // 1) 비워진 테이블 기록 (이전에 주문이 있었던 곳)
        visibleTables.forEach { t ->
            if (t.label.startsWith("포장") || t.occupied || t.label in gone) return@forEach
            if (kitchen.knownTable(t.label) || marks.orderSince(t.label) != 0L) {
                gone[t.label] = Gone(kitchen.itemsOf(t.label), now)
            }
        }
        visibleTables.forEach { t ->
            if (t.label.startsWith("포장") || !t.occupied) return@forEach
            val amount = amountOf(t)
            val items = itemsOf(t)
            // 2) 새로 주문이 보인 테이블이 방금 비워진 테이블과 메뉴가 같으면 → 테이블 이동
            if (marks.orderSince(t.label) == 0L && !kitchen.knownTable(t.label) && items.isNotEmpty()) {
                val from = gone.entries.firstOrNull { (_, g) -> g.items == items }?.key
                if (from != null) {
                    gone.remove(from)
                    kitchen.move(from, t.label)
                    marks.move(from, t.label)
                    Diag.add("테이블 이동: $from → ${t.label}")
                    return@forEach
                }
            }
            // 다시 주문이 생긴 테이블은 '비워짐' 기록을 먼저 정리
            gone[t.label]?.let { finishGone(t.label, it) }
            val seen = marks.orderSince(t.label)
            if (seen == 0L || !kitchen.knownTable(t.label)) {
                if (seen == 0L) {
                    marks.startOrder(t.label, now, amount)
                    Diag.add("${t.label} 새 주문 → 서빙 순서에 추가")
                }
                kitchen.newOrder(t.label, items, now)
            } else if (kitchen.update(t.label, items, now) || amount > marks.lastAmount(t.label)) {
                marks.setAmount(t.label, amount)
                if (marks.has(t.label)) {
                    // 서빙 끝난 테이블의 추가 주문 → 표시를 풀고 맨 앞으로
                    marks.remove(t.label)
                    marks.setPriority(t.label, now)
                    Diag.add("${t.label} 추가 주문 → 서빙완료 해제, 우선 서빙")
                }
            } else if (amount < marks.lastAmount(t.label)) {
                marks.setAmount(t.label, amount)  // 일부 취소
            }
        }
        // 3) 6초 지나도 이동으로 이어지지 않은 비워진 테이블 → 취소/결제로 정리
        gone.entries.toList().forEach { (table, g) ->
            if (now - g.at > 6_000 && byLabel[table]?.occupied != true) finishGone(table, g)
        }
        // 조리완료 후 3분이 지나도 서빙완료가 안 되면 자동으로 서빙완료 처리 (순서 줄이 막히지 않게)
        visibleTables.forEach { t ->
            if (!t.occupied || marks.has(t.label)) return@forEach
            val c = kitchen.cookedWaiting(t.label)
            if (c > 0L && now - c > AUTO_SERVE_MS) {
                marks.add(t.label); kitchen.serve(t.label, now)
                Diag.add("${t.label} 조리완료 후 3분 → 자동 서빙완료")
            }
        }
        val marked = marks.all()
        queue = visibleTables
            .filter { it.occupied && !it.label.startsWith("포장") && it.label !in marked }
            .map { it.label }
            .sortedWith(compareBy<String>({ if (marks.priority(it) > 0) 0 else 1 },
                { marks.priority(it).takeIf { p -> p > 0 } ?: marks.orderSince(it) }))
    }

    // ------------------------------------------------------------------
    // 주문 화면(매출등록)의 '할인'·'서비스' 자리에 덮어 띄우는 서빙완료 버튼
    // ------------------------------------------------------------------

    private var saleButton: TextView? = null
    private var saleTable: String? = null
    private val saleTableText = Regex("(테이블|포장)\\s*/\\s*(\\d+)")

    private fun updateSaleButton(root: AccessibilityNodeInfo) {
        if (!lastWindowClass.endsWith("EasySale")) return hideSaleButton()
        fun exact(t: String) = root.findAccessibilityNodeInfosByText(t)
            .firstOrNull { it.text?.toString()?.trim() == t && it.isVisibleToUser }
        val discount = exact("할인")
        val service = exact("서비스")
        val tableNode = root.findAccessibilityNodeInfosByText("/")
            .firstOrNull { saleTableText.containsMatchIn(it.text?.toString() ?: "") }
        val m = tableNode?.text?.toString()?.let { saleTableText.find(it) }
        if (discount == null || service == null || m == null) return hideSaleButton()
        val table = if (m.groupValues[1] == "포장") "포장" + m.groupValues[2] else m.groupValues[2]
        val r = Rect().also { discount.getBoundsInScreen(it) }
        r.union(Rect().also { service.getBoundsInScreen(it) })
        showSaleButton(table, r)
    }

    private fun showSaleButton(table: String, r: Rect) {
        saleTable = table
        val marked = marks.has(table)
        val b = saleButton ?: TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 17f
            setOnClickListener { onSaleButton() }
        }.also { saleButton = it }
        b.text = if (marked) "서빙완료 해제" else "서빙완료"
        b.setBackgroundColor(Color.parseColor(if (marked) "#6B7280" else "#15803D"))
        val lp = WindowManager.LayoutParams(
            r.width(), r.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = r.left; y = r.top }
        if (b.parent == null) wm.addView(b, lp) else wm.updateViewLayout(b, lp)
    }

    private fun hideSaleButton() {
        saleButton?.takeIf { it.parent != null }?.let { runCatching { wm.removeView(it) } }
        saleTable = null
    }

    private fun onSaleButton() {
        val table = saleTable ?: return
        val on = marks.toggle(table)
        if (on) kitchen.serve(table, System.currentTimeMillis()) else kitchen.unserve(table)
        Diag.add("주문 화면 버튼: $table 서빙완료 ${if (on) "표시" else "해제"}")
        hideSaleButton()
        // 표시 후 주문 화면을 닫습니다(그 화면의 X 하나만).
        closeDeadline = System.currentTimeMillis() + 4000
        handler.post(closeOrderScreen)
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
        private val redFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E6C62828") }
        private val redStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C62828"); style = Paint.Style.STROKE; strokeWidth = 3 * d
        }
        private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E2430") }
        private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 14 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val boxText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 17 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }

        /** 서빙 순서 번호, 다음 서빙(빨간 띠), 남은 서빙 수 */
        private var blinking = false
        private val amberFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E6D97706") }
        private val amberStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D97706"); style = Paint.Style.STROKE; strokeWidth = 3 * d
        }

        private fun drawQueue(c: Canvas) {
            blinking = false
            val q = queue
            val byLabel = visibleTables.associateBy { it.label }
            q.forEachIndexed { i, label ->
                val t = byLabel[label] ?: return@forEachIndexed
                val r = RectF(t.bounds).apply { offset(-loc[0].toFloat(), -loc[1].toFloat()) }
                val cw = kitchen.cookedWaiting(label)
                val late = cw > 0L && System.currentTimeMillis() - cw > SERVE_CHECK_MS
                if (late) {
                    blinking = true
                    if ((System.currentTimeMillis() / 500) % 2 == 0L) {
                        c.drawRect(r, amberStroke)
                        val h = minOf(28 * d, r.height() / 2.5f)
                        val band = RectF(r.left, r.bottom - h, r.right, r.bottom)
                        c.drawRect(band, amberFill)
                        c.drawText("서빙 확인", band.centerX(), band.centerY() + text.textSize / 3, text)
                    }
                } else if (i == 0) {
                    c.drawRect(r, redStroke)
                    val h = minOf(28 * d, r.height() / 2.5f)
                    val band = RectF(r.left, r.bottom - h, r.right, r.bottom)
                    c.drawRect(band, redFill)
                    c.drawText("다음 서빙", band.centerX(), band.centerY() + text.textSize / 3, text)
                }
                val rad = 13 * d
                val cx = r.left + rad + 4 * d
                val cy = r.top + rad + 4 * d
                badge.color = Color.parseColor(if (i == 0) "#C62828" else "#1E2430")
                c.drawCircle(cx, cy, rad, badge)
                c.drawText("${i + 1}", cx, cy + badgeText.textSize / 3, badgeText)
            }
            // 남은 서빙 수: 포장0 칸 아래 빈 곳
            val anchor = visibleTables.firstOrNull { it.label.startsWith("포장") }
            val box = if (anchor != null) {
                val a = RectF(anchor.bounds).apply { offset(-loc[0].toFloat(), -loc[1].toFloat()) }
                RectF(a.left, a.bottom + 14 * d, a.right, a.bottom + 14 * d + 52 * d)
            } else RectF(width - 190 * d, height - 70 * d, width - 16 * d, height - 18 * d)
            boxFill.color = Color.parseColor(if (q.isEmpty()) "#15803D" else "#C62828")
            c.drawRoundRect(box, 10 * d, 10 * d, boxFill)
            c.drawText(if (q.isEmpty()) "남은 서빙 없음" else "서빙 대기 ${q.size}",
                box.centerX(), box.centerY() + boxText.textSize / 3, boxText)
            if (blinking) postInvalidateDelayed(500)
        }

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
                if (visibleTables.size >= 5) drawQueue(c)
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
