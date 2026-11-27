package com.chodang.servemark

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 주방 주문표(티켓). 홀 포스기 테이블 칸에서 읽은 메뉴로 만듭니다.
 * - 새 주문: 테이블의 메뉴 전체로 티켓 하나
 * - 추가 주문: 조리 안 된 티켓이 있으면 거기에 더하고, 없으면 '추가' 티켓을 새로 (맨 앞)
 * - 주방 '조리완료' → cooked 시각 기록 (되돌리기 가능)
 * - 홀 '서빙완료' 또는 결제/취소로 테이블이 비면 → 그 테이블 티켓 삭제
 * 메인 스레드에서 바꾸고, 주방 화면 서버(다른 스레드)는 json() 으로 읽기만 합니다.
 */
class Kitchen(context: Context) {

    companion object {
        /** 주방에 보낼 필요 없는 메뉴 (이름에 이 말이 들어가면 제외) */
        val EXCLUDE = listOf("소주", "맥주", "막걸리", "모주", "음료", "공기밥")
        /** 주방에 흐리게만 보여 줄 메뉴 (담아 나가기만 하는 것, 할인) */
        val LOW = listOf("초당순두부", "할인")
        /** 여러 주문을 묶어 한 번에 조리하는 메뉴 → 주방 화면 위에 합계 */
        val BATCH = listOf("제육불고기")

        fun forKitchen(name: String) = EXCLUDE.none { name.contains(it) }
    }

    private fun kitchenOnly(m: Map<String, Int>) = LinkedHashMap(m.filterKeys { forKitchen(it) })

    data class Ticket(
        val id: Long,
        val table: String,
        val items: LinkedHashMap<String, Int>,
        val add: Boolean,
        val created: Long,
        var cooked: Long = 0L,
    )

    private val prefs = context.applicationContext.getSharedPreferences("kitchen", Context.MODE_PRIVATE)
    private val tickets = mutableListOf<Ticket>()
    /** 테이블별 마지막으로 읽은 메뉴 (추가 주문을 알아채기 위함) */
    private val lastItems = mutableMapOf<String, Map<String, Int>>()
    private var nextId = System.currentTimeMillis()
    @Volatile private var snapshot: String = "{\"tickets\":[]}"

    init { load() }

    // ---------------- 읽기 ----------------

    @Synchronized fun knownTable(t: String) = t in lastItems

    @Synchronized fun hasCookedOnly(t: String): Boolean {
        val mine = tickets.filter { it.table == t }
        return mine.isNotEmpty() && mine.all { it.cooked > 0 }
    }

    /** 주방 화면용 JSON (서버 스레드에서 읽음) */
    fun json(): String = snapshot.let {
        JSONObject(it).put("now", System.currentTimeMillis()).toString()
    }

    // ---------------- 홀 화면을 읽고 반영 ----------------

    /** 테이블에 주문이 처음 보였을 때 */
    @Synchronized fun newOrder(table: String, items: Map<String, Int>, now: Long) {
        tickets.removeAll { it.table == table }
        lastItems[table] = items
        val k = kitchenOnly(items)
        if (k.isNotEmpty()) tickets += Ticket(nextId++, table, k, false, now)
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
        val anyAdded = added.isNotEmpty()
        added.keys.retainAll { forKitchen(it) }
        removed.keys.retainAll { forKitchen(it) }

        val open = tickets.lastOrNull { it.table == table && it.cooked == 0L }
        // 조리 전에 빠진 메뉴는 조리 안 된 티켓에서 뺍니다.
        if (open != null) removed.forEach { (k, q) ->
            val left = (open.items[k] ?: 0) - q
            if (left > 0) open.items[k] = left else open.items.remove(k)
        }
        if (added.isNotEmpty()) {
            if (open != null) added.forEach { (k, q) -> open.items[k] = (open.items[k] ?: 0) + q }
            else tickets += Ticket(nextId++, table, added, true, now)
        }
        tickets.removeAll { it.items.isEmpty() }
        save()
        return anyAdded
    }

    /** 결제·취소로 테이블이 비었을 때, 또는 홀에서 서빙완료 했을 때 */
    @Synchronized fun clearTable(table: String, forget: Boolean) {
        val changed = tickets.removeAll { it.table == table }
        if (forget) lastItems.remove(table)
        if (changed || forget) save()
    }

    // ---------------- 주방에서 누름 ----------------

    @Synchronized fun cook(id: Long, now: Long) {
        tickets.firstOrNull { it.id == id && it.cooked == 0L }?.let { it.cooked = now; save() }
    }

    @Synchronized fun undo(id: Long) {
        tickets.firstOrNull { it.id == id && it.cooked > 0L }?.let { it.cooked = 0L; save() }
    }

    // ---------------- 저장 ----------------

    private fun save() {
        val arr = JSONArray()
        tickets.forEach { t ->
            arr.put(JSONObject().put("id", t.id).put("table", t.table).put("add", t.add)
                .put("created", t.created).put("cooked", t.cooked)
                .put("items", JSONArray().apply { t.items.forEach { (k, q) -> put(JSONArray().put(k).put(q)) } }))
        }
        val last = JSONObject()
        lastItems.forEach { (t, m) -> last.put(t, JSONObject(m as Map<*, *>)) }
        snapshot = JSONObject().put("tickets", arr)
            .put("low", JSONArray(LOW)).put("batch", JSONArray(BATCH)).toString()
        prefs.edit().putString("tickets", arr.toString()).putString("last", last.toString())
            .putLong("nextId", nextId).apply()
    }

    private fun load() {
        nextId = maxOf(nextId, prefs.getLong("nextId", 0L))
        runCatching {
            val arr = JSONArray(prefs.getString("tickets", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val items = LinkedHashMap<String, Int>()
                val ia = o.getJSONArray("items")
                for (j in 0 until ia.length()) items[ia.getJSONArray(j).getString(0)] = ia.getJSONArray(j).getInt(1)
                tickets += Ticket(o.getLong("id"), o.getString("table"), items, o.getBoolean("add"),
                    o.getLong("created"), o.getLong("cooked"))
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
