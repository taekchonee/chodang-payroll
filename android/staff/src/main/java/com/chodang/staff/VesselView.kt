package com.chodang.staff

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * 국 바트(스테인리스 통, 1/4·1/2)와 밥솥을 한 줄로 그립니다. 왼쪽이 지금 쓰는 것, 사이에 › 표시.
 * 남은 양만큼 채우고 가운데에 %(또는 '비었음', 짓는 중이면 남은 분)를 적습니다.
 */
class VesselView(ctx: Context) : View(ctx) {

    /** type: "1/4", "1/2", "pot", "cook" / color: "hwangtae", "miyeok", "rice" */
    data class Item(val type: String, val pct: Int, val label: String, val now: Boolean, val color: String, val note: String = "", val idx: Int = -1)

    var items: List<Item> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }

    /** 그림을 길게 누르면 (idx 가 있는 것만) */
    var onLongPressItem: ((Item) -> Unit)? = null
    /** 크게 한 개만 그릴 때 (고치기 창) */
    var big = false
    private val hits = mutableListOf<Pair<ClosedFloatingPointRange<Float>, Item>>()
    private var downX = 0f

    init {
        setOnTouchListener { _, e -> if (e.action == android.view.MotionEvent.ACTION_DOWN) downX = e.x; false }
        setOnLongClickListener {
            val hit = hits.firstOrNull { downX in it.first && it.second.idx >= 0 }?.second
            if (hit != null && onLongPressItem != null) { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); onLongPressItem?.invoke(hit); true } else false
        }
    }

    private val d = resources.displayMetrics.density
    private val vesselH get() = (if (big) 110 else 60) * d
    private val rimH = 8 * d
    private val labelH = 22 * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; style = Paint.Style.STROKE
        strokeWidth = 4 * d; color = Color.WHITE; strokeJoin = Paint.Join.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 12 * d }

    private fun units(t: String) = when (t) { "1/4" -> 1.0f; "1/2" -> 1.5f; else -> 1.25f }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (rimH + vesselH + labelH + 6 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (items.isEmpty()) return
        val gap = 22 * d
        val total = items.sumOf { units(it.type).toDouble() }.toFloat()
        // 1단위 폭: 화면 폭에 맞추되 너무 크지 않게
        val unit = minOf((width - gap * (items.size - 1) - 8 * d) / total, (if (big) 110 else 74) * d)
        var x = if (big) (width - unit * total) / 2 else 4 * d
        hits.clear()
        val top = rimH
        items.forEachIndexed { i, it ->
            val w = unit * units(it.type)
            hits += (x - gap / 2)..(x + w + gap / 2) to it
            if (it.type == "pot" || it.type == "cook") pot(c, x, top, w, it) else vat(c, x, top, w, it)
            label.color = Color.parseColor(if (it.now) "#111827" else "#6B7280")
            label.typeface = if (it.now) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val half = label.measureText(it.label) / 2
            c.drawText(it.label, (x + w / 2).coerceIn(half + 1, width - half - 1), top + vesselH + 16 * d, label)
            x += w
            if (i < items.size - 1) {
                text.textSize = 20 * d; text.color = Color.parseColor("#9CA3AF")
                c.drawText("›", x + gap / 2, top + vesselH / 2 + 7 * d, text)
                x += gap
            }
        }
    }

    private fun liquid(color: String) = when (color) {
        "miyeok" -> "#6F8248" to "#8FA163"
        "rice" -> "#F3EBD3" to "#FBF6E8"
        else -> "#E3C27E" to "#F1DDAE"
    }

    private fun centerText(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: String) {
        text.textSize = size; halo.textSize = size; text.color = Color.parseColor(color)
        c.drawText(s, cx, cy, halo); c.drawText(s, cx, cy, text)
    }

    /** 스테인리스 바트: 윗테두리 + 아래로 살짝 좁아지는 몸통 + 둥근 바닥 + 눈금 */
    private fun vat(c: Canvas, x: Float, y: Float, w: Float, it: Item) {
        val ins = w * 0.09f; val r = 9 * d; val by = y + vesselH
        val body = Path().apply {
            moveTo(x, y); lineTo(x + w, y); lineTo(x + w - ins, by - r)
            quadTo(x + w - ins, by, x + w - ins - r, by); lineTo(x + ins + r, by)
            quadTo(x + ins, by, x + ins, by - r); close()
        }
        fill.color = Color.parseColor("#F3F4F6"); c.drawPath(body, fill)
        val p = it.pct.coerceIn(0, 100) / 100f
        if (p > 0) {
            val (liq, surf) = liquid(it.color)
            val fy = by - (vesselH - 3 * d) * p
            c.save(); c.clipPath(body)
            fill.color = Color.parseColor(liq); c.drawRect(x - 2, fy, x + w + 2, by + 2, fill)
            fill.color = Color.parseColor(surf); c.drawRect(x - 2, fy, x + w + 2, fy + 3 * d, fill)
            c.restore()
        }
        stroke.strokeWidth = 1 * d; stroke.color = Color.parseColor("#9CA3AF"); stroke.pathEffect = null
        for (q in listOf(0.25f, 0.5f, 0.75f)) {
            val ty = by - (vesselH - 3 * d) * q
            val edge = x + w - ins * (1 - (by - ty) / vesselH)
            c.drawLine(edge - 10 * d, ty, edge - 3 * d, ty, stroke)
        }
        stroke.strokeWidth = (if (it.now) 2.5f else 1.8f) * d
        stroke.color = Color.parseColor(if (it.now) "#4B5563" else "#A7ADB6")
        c.drawPath(body, stroke)
        fill.color = Color.parseColor(if (it.now) "#6B7280" else "#B8BDC5")
        c.drawRoundRect(RectF(x - 5 * d, y - 4 * d, x + w + 5 * d, y + 3 * d), 3.5f * d, 3.5f * d, fill)
        val s = if (it.pct <= 0) "비었음" else "${it.pct}%"
        centerText(c, s, x + w / 2, y + vesselH / 2 + 6 * d, (if (it.pct <= 0) 12 else 15) * d, if (it.pct <= 0) "#9CA3AF" else "#1F2937")
    }

    /** 밥솥: 둥근 몸통 + 뚜껑 + 손잡이. 짓는 중이면 점선 + 남은 분 */
    private fun pot(c: Canvas, x: Float, y: Float, w: Float, it: Item) {
        val cook = it.type == "cook"
        val body = RectF(x + 2 * d, y + 4 * d, x + w - 2 * d, y + vesselH)
        val p = it.pct.coerceIn(0, 100) / 100f
        if (!cook && p > 0) {
            val fy = body.bottom - (body.height() - 4 * d) * p
            c.save(); c.clipRect(body.left, fy, body.right, body.bottom)
            fill.color = Color.parseColor("#F3EBD3"); c.drawRoundRect(body, 12 * d, 12 * d, fill)
            c.restore()
        }
        stroke.strokeWidth = 2 * d
        stroke.color = Color.parseColor(if (cook) "#9CA3AF" else "#C9B98A")
        stroke.pathEffect = if (cook) DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f) else null
        c.drawRoundRect(body, 12 * d, 12 * d, stroke)
        val lid = RectF(x - 2 * d, y - 3 * d, x + w + 2 * d, y + 4 * d)
        val knob = RectF(x + w / 2 - 9 * d, y - 8 * d, x + w / 2 + 9 * d, y - 3 * d)
        if (cook) { c.drawRoundRect(lid, 3 * d, 3 * d, stroke); c.drawRoundRect(knob, 3 * d, 3 * d, stroke) }
        else { fill.color = Color.parseColor("#C9B98A"); c.drawRoundRect(lid, 3 * d, 3 * d, fill); c.drawRoundRect(knob, 3 * d, 3 * d, fill) }
        stroke.pathEffect = null
        val s = if (cook) it.note else if (it.pct <= 0) "비었음" else "${it.pct}%"
        centerText(c, s, x + w / 2, y + vesselH / 2 + 9 * d, 15 * d, if (cook) "#6B7280" else if (it.pct <= 0) "#9CA3AF" else "#1F2937")
    }
}
