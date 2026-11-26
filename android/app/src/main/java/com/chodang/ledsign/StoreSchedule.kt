package com.chodang.ledsign

import android.content.Context
import java.util.Calendar

/** 간판에 띄우는 상태 */
enum class StoreStatus(val label: String, val defaultText: String, val defaultColor: LedColor) {
    OPEN("영업중", "영업중", LedColor.GREEN),
    BREAK("브레이크타임", "브레이크타임", LedColor.YELLOW),
    CLOSED("영업종료", "영업종료", LedColor.RED),
    HOLIDAY("휴무", "월요일 휴무", LedColor.RED),
    SOLDOUT("조기마감", "재료소진 조기마감", LedColor.RED),
}

/**
 * 영업 시간표와 수동 설정(조기마감, 임시 휴무)을 저장하고,
 * 지금 간판에 무엇을 띄워야 하는지 계산합니다.
 */
class StoreSchedule(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("schedule", Context.MODE_PRIVATE)

    var autoEnabled: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) = prefs.edit().putBoolean("auto", v).apply()

    /** 하루 중 분 단위 (예: 11:00 → 660) */
    var openMin: Int
        get() = prefs.getInt("open", 11 * 60)
        set(v) = prefs.edit().putInt("open", v).apply()
    var breakStartMin: Int
        get() = prefs.getInt("break_start", 15 * 60)
        set(v) = prefs.edit().putInt("break_start", v).apply()
    var breakEndMin: Int
        get() = prefs.getInt("break_end", 17 * 60)
        set(v) = prefs.edit().putInt("break_end", v).apply()
    var closeMin: Int
        get() = prefs.getInt("close", 21 * 60)
        set(v) = prefs.edit().putInt("close", v).apply()
    var useBreak: Boolean
        get() = prefs.getBoolean("use_break", true)
        set(v) = prefs.edit().putBoolean("use_break", v).apply()

    /** 정기 휴무 요일 (Calendar.SUNDAY=1 … SATURDAY=7). 기본은 월요일. */
    fun isHolidayDay(day: Int) = prefs.getBoolean("holiday_$day", day == Calendar.MONDAY)
    fun setHolidayDay(day: Int, on: Boolean) = prefs.edit().putBoolean("holiday_$day", on).apply()

    fun text(s: StoreStatus): String = prefs.getString("text_${s.name}", null) ?: s.defaultText
    fun setText(s: StoreStatus, t: String) = prefs.edit().putString("text_${s.name}", t).apply()
    fun color(s: StoreStatus): LedColor = LedColor.parse(prefs.getString("color_${s.name}", s.defaultColor.name))
    fun setColor(s: StoreStatus, c: LedColor) = prefs.edit().putString("color_${s.name}", c.name).apply()

    /** 조기마감/임시 휴무는 누른 날짜에만 유효합니다. */
    private fun today(cal: Calendar = Calendar.getInstance()) =
        cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)

    var soldOutToday: Boolean
        get() = prefs.getInt("soldout_day", -1) == today()
        set(v) = prefs.edit().putInt("soldout_day", if (v) today() else -1).apply()
    var closedToday: Boolean
        get() = prefs.getInt("closed_day", -1) == today()
        set(v) = prefs.edit().putInt("closed_day", if (v) today() else -1).apply()

    fun target(cal: Calendar = Calendar.getInstance()): StoreStatus {
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val day = cal.get(Calendar.DAY_OF_WEEK)
        val open = openMin
        val close = closeMin
        // 자정을 넘겨 마감하는 경우(예: 11:00~01:00)도 처리
        val inBusinessHours = if (close > open) now in open until close else now >= open || now < close
        return when {
            closedToday || isHolidayDay(day) -> StoreStatus.HOLIDAY
            !inBusinessHours -> StoreStatus.CLOSED
            soldOutToday -> StoreStatus.SOLDOUT
            useBreak && now in breakStartMin until breakEndMin -> StoreStatus.BREAK
            else -> StoreStatus.OPEN
        }
    }

    fun request(s: StoreStatus) = ShowRequest(text(s), color(s))

    companion object {
        fun fmt(min: Int) = "%02d:%02d".format(min / 60, min % 60)
    }
}
