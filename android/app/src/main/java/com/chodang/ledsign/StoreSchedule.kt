package com.chodang.ledsign

import android.content.Context
import java.util.Calendar

/** 간판에 띄우는 상태 */
enum class StoreStatus(val label: String, val defaultText: String, val defaultColor: LedColor) {
    // 공백은 반 글자 간격 (CoolLED 앱에서 쓰던 표기 그대로)
    OPEN("영업중", "영 업 중", LedColor.WHITE),
    CLOSED("영업종료", "영업 종료", LedColor.WHITE),
    HOLIDAY("휴무", "월요일 휴무", LedColor.WHITE),
    // | 로 나누면 화면을 나눠, 패널이 "재료소진" → "조기마감" 을 한 화면씩 번갈아 보여줍니다.
    SOLDOUT("조기마감", "재료소진|조기마감", LedColor.WHITE),
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
        get() = prefs.getInt("open", 9 * 60)
        set(v) = prefs.edit().putInt("open", v).apply()
    var closeMin: Int
        get() = prefs.getInt("close", 14 * 60 + 30)
        set(v) = prefs.edit().putInt("close", v).apply()

    /** 정기 휴무 전날 마감 후 ~ 휴무 다음 날 오픈 전에도 휴무 문구를 띄웁니다. */
    var holidayAroundClosed: Boolean
        get() = prefs.getBoolean("holiday_around", true)
        set(v) = prefs.edit().putBoolean("holiday_around", v).apply()

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
        // 마감 후 시간이 오픈 전(새벽)인지, 마감 뒤(저녁)인지
        val beforeOpen = if (close > open) now < open else now in close until open
        val prevDay = if (day == Calendar.SUNDAY) Calendar.SATURDAY else day - 1
        val nextDay = if (day == Calendar.SATURDAY) Calendar.SUNDAY else day + 1
        return when {
            closedToday || isHolidayDay(day) -> StoreStatus.HOLIDAY
            !inBusinessHours && holidayAroundClosed &&
                ((beforeOpen && isHolidayDay(prevDay)) || (!beforeOpen && isHolidayDay(nextDay))) -> StoreStatus.HOLIDAY
            !inBusinessHours -> StoreStatus.CLOSED
            soldOutToday -> StoreStatus.SOLDOUT
            else -> StoreStatus.OPEN
        }
    }

    fun request(s: StoreStatus) = ShowRequest(text(s), color(s))

    companion object {
        fun fmt(min: Int) = "%02d:%02d".format(min / 60, min % 60)
    }
}
