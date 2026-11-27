package com.chodang.servemark

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 주방 주문표(티켓). 홀 포스기 테이블 칸에서 읽은 메뉴로 만듭니다.
 * - 새 주문: 테이블의 메뉴 전체로 티켓 하나 (kind=new)
 * - 추가 주문: 조리 전 티켓이 있으면 거기에 더하고, 없으면 '추가' 티켓을 새로 (맨 앞, kind=add)
 * - 메뉴 변경(빠지고 늘어남): 조리 전 티켓이면 그 안에서 바꾸고, 없으면 '변경' 티켓 (kind=change)
 * - 취소된 메뉴는 바로 지우지 않고 취소선으로 잠깐(CANCEL_SHOW) 보여 줍니다.
 * - 주문 전체 취소 → 카드에 '주문 취소'를 잠깐 띄운 뒤 삭제
 * - 테이블 이동 → 티켓을 새 테이블로 옮김 (movedFrom 기록)
 * - 주방 '조리완료' → cooked 시각 기록 (되돌리기 가능)
 * - 홀 '서빙완료' 또는 결제로 테이블이 비면 → 조용히 삭제
 * 메인 스레드에서 바꾸고, 주방 화면 서버(다른 스레드)는 json() 으로 읽기만 합니다.
 */
class Kitchen(context: Context) {

    companion object {
        /** 조리가 필요 없는 메뉴: 카드 아래에 작게만 표시, 이것만으로는 주방 카드를 만들지 않음 */
        val EXTRA = listOf("소주", "맥주", "막걸리", "모주", "음료", "공기밥", "할인")
        /** 담아 나가기만 하는 메뉴: 같은 크기로 살짝 흐리게 */
        val LOW = listOf("초당순두부")
        /** 여러 주문을 묶어 한 번에 조리하는 메뉴 → 주방 화면 위에 합계 */
        val BATCH = listOf("제육불고기")
        /** 취소 표시를 남겨 두는 시간 */
        const val CANCEL_SHOW = 90_000L

        /** 조리가 필요한 메뉴인지 */
        fun forKitchen(name: String) = EXTRA.none { name.contains(it) }
        private fun needsCooking(m: Map<String, Int>) = m.keys.any { forKitchen(it) }
    }

    data class Ticket(
        val id: Long,
        var table: String,
        val items: LinkedHashMap<String, Int>,
        val kind: String,          // new / add / change
        val created: Long,
        var cooked: Long = 0L,
        val cancelled: LinkedHashMap<String, Int> = LinkedHashMap(),
        var cancelAt: Long = 0L,   // 마지막으로 메뉴가 취소된 시각
        var voided: Long = 0L,     // 주문 전체가 취소된 시각
        var movedFrom: String = "",
    )

    private val prefs = context.applicationContext.getSharedPreferences("kitchen", Context.MODE_PRIVATE)
    private val tickets = mutableListOf<Ticket>()
    /** 테이블별 마지막으로 읽은 메뉴 (추가·취소를 알아채기 위함) */
    private val lastItems = mutableMapOf<String, Map<String, Int>>()
    private var nextId = System.currentTimeMillis()
    @Volatile private var snapshot: String = "{\"tickets\":[]}"

    init { load() }

    // ---------------- 읽기 ----------------

    @Synchronized fun knownTable(t: String) = t in lastItems
    @Synchronized fun itemsOf(t: String): Map<String, Int> = lastItems[t] ?: emptyMap()

    /** 주방 화면용 JSON (서버 스레드에서 읽음) */
    fun json(): String = snapshot.let {
        JSONObject(it).put("now", System.currentTimeMillis()).toString()
    }

    // ---------------- 홀 화면을 읽고 반영 ----------------

    /** 테이블에 주문이 처음 보였을 때 */
    @Synchronized fun newOrder(table: String, items: Map<String, Int>, now: Long) {
        tickets.removeAll { it.table == table && it.voided == 0L }
        lastItems[table] = items
        if (needsCooking(items)) tickets += Ticket(nextId++, table, LinkedHashMap(items), "new", now)
        save()
    }

    /**
     * 같은 테이블의 메뉴가 바뀌었을 때. 늘어난 메뉴가 있으면 true.
     * 메뉴를 못 읽은 경우(빈 목록)는 무시합니다.
     */
    @Synchronized fun update(table: String, items: Map<String, Int>, now: Long): Boolean {
        if (items.isEmpty()) return false
        val before = lastItems[table] ?: emptyMap()
        val added = LinkedHashMap<String, Int>()
        val removed = LinkedHashMap<String, Int>()
        (before.keys + items.keys).forEach { k ->
            val d = (items[k] ?: 0) - (before[k] ?: 0)
            if (d > 0) added[k] = d else if (d < 0) removed[k] = -d
        }
        lastItems[table] = items
        if (added.isEmpty() && removed.isEmpty()) return false

        val live = tickets.filter { it.table == table && it.voided == 0L }
        // 빠진 메뉴: 조리 전 티켓 먼저, 없으면 조리완료 티켓에서 빼고 취소선으로 남깁니다.
        removed.forEach { (k, q) ->
            var left = q
            (live.filter { it.cooked == 0L }.reversed() + live.filter { it.cooked > 0L }.reversed()).forEach { t ->
                if (left == 0) return@forEach
                val have = t.items[k] ?: 0
                if (have <= 0) return@forEach
                val take = minOf(have, left)
                if (have - take > 0) t.items[k] = have - take else t.items.remove(k)
                t.cancelled[k] = (t.cancelled[k] ?: 0) + take
                t.cancelAt = now
                left -= take
            }
        }
        val open = live.lastOrNull { it.cooked == 0L }
        if (added.isNotEmpty()) {
            if (open != null) added.forEach { (k, q) -> open.items[k] = (open.items[k] ?: 0) + q }
            else if (needsCooking(added)) {
                val kind = if (needsCooking(removed)) "change" else "add"
                tickets += Ticket(nextId++, table, added, kind, now).also { t ->
                    if (kind == "change") { t.cancelled.putAll(removed); t.cancelAt = now }
                }
            }
        }
        // 조리 전 티켓에 조리할 메뉴가 하나도 안 남으면 → 전체 취소로 보여 줌
        tickets.filter { it.voided == 0L && it.cooked == 0L && !needsCooking(it.items) }.forEach {
            if (it.cancelled.isNotEmpty()) it.voided = now
        }
        tickets.removeAll { it.voided == 0L && it.cooked == 0L && !needsCooking(it.items) && it.cancelled.isEmpty() }
        save()
        return added.isNotEmpty()
    }

    /** 주문 전체 취소: 카드에 '주문 취소'를 잠깐 띄운 뒤 expire() 가 지움 */
    @Synchronized fun voidTable(table: String, now: Long) {
        tickets.filter { it.table == table && it.voided == 0L }.forEach { it.voided = now }
        lastItems.remove(table)
        save()
    }

    /** 결제로 테이블이 비었을 때(forget=true), 또는 홀에서 서빙완료 했을 때 → 조용히 삭제 */
    @Synchronized fun clearTable(table: String, forget: Boolean) {
        val changed = tickets.removeAll { it.table == table && it.voided == 0L }
        if (forget) lastItems.remove(table)
        if (changed || forget) save()
    }

    /** 테이블 이동: 주문표를 새 테이블로 옮깁니다. */
    @Synchronized fun move(from: String, to: String) {
        tickets.filter { it.table == from }.forEach { it.table = to; it.movedFrom = from }
        lastItems.remove(from)?.let { lastItems[to] = it }
        save()
    }

    /** 오래된 취소 표시 정리 (주기적으로 호출) */
    @Synchronized fun expire(now: Long) {
        var changed = tickets.removeAll { it.voided > 0L && now - it.voided > CANCEL_SHOW }
        tickets.forEach { t ->
            if (t.cancelled.isNotEmpty() && now - t.cancelAt > CANCEL_SHOW) { t.cancelled.clear(); changed = true }
        }
        if (changed) save()
    }

    // ---------------- 주방에서 누름 ----------------

    @Synchronized fun cook(id: Long, now: Long) {
        tickets.firstOrNull { it.id == id && it.cooked == 0L && it.voided == 0L }?.let { it.cooked = now; save() }
    }

    @Synchronized fun undo(id: Long) {
        tickets.firstOrNull { it.id == id && it.cooked > 0L }?.let { it.cooked = 0L; save() }
    }

    // ---------------- 저장 ----------------

    private fun pairs(m: Map<String, Int>) = JSONArray().apply { m.forEach { (k, q) -> put(JSONArray().put(k).put(q)) } }
    private fun unpairs(a: JSONArray?) = LinkedHashMap<String, Int>().apply {
        if (a != null) for (j in 0 until a.length()) put(a.getJSONArray(j).getString(0), a.getJSONArray(j).getInt(1))
    }

    private fun save() {
        val arr = JSONArray()
        tickets.forEach { t ->
            arr.put(JSONObject().put("id", t.id).put("table", t.table).put("kind", t.kind)
                .put("add", t.kind != "new").put("created", t.created).put("cooked", t.cooked)
                .put("items", pairs(t.items)).put("cancelled", pairs(t.cancelled))
                .put("cancelAt", t.cancelAt).put("voided", t.voided).put("movedFrom", t.movedFrom))
        }
        val last = JSONObject()
        lastItems.forEach { (t, m) -> last.put(t, JSONObject(m as Map<*, *>)) }
        snapshot = JSONObject().put("tickets", arr)
            .put("low", JSONArray(LOW)).put("extra", JSONArray(EXTRA)).put("batch", JSONArray(BATCH)).toString()
        prefs.edit().putString("tickets", arr.toString()).putString("last", last.toString())
            .putLong("nextId", nextId).apply()
    }

    private fun load() {
        nextId = maxOf(nextId, prefs.getLong("nextId", 0L))
        runCatching {
            val arr = JSONArray(prefs.getString("tickets", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = o.optString("kind", if (o.optBoolean("add")) "add" else "new")
                tickets += Ticket(o.getLong("id"), o.getString("table"), unpairs(o.getJSONArray("items")), kind,
                    o.getLong("created"), o.optLong("cooked"), unpairs(o.optJSONArray("cancelled")),
                    o.optLong("cancelAt"), o.optLong("voided"), o.optString("movedFrom", ""))
            }
            val last = JSONObject(prefs.getString("last", "{}"))
            last.keys().forEach { t ->
                val m = last.getJSONObject(t)
                lastItems[t] = m.keys().asSequence().associateWith { m.getInt(it) }
            }
        }
        save()
    }
}
