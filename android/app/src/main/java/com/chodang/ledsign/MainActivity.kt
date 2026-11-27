package com.chodang.ledsign

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

/**
 * 포스기 화면용 앱 UI. (AndroidX 없이 기본 위젯만 사용 — Android 8 에서도 같은 모양)
 *
 * 탭 3개: 지금(상태·자주 쓰는 버튼) / 시간표·문구 / 설정.
 */
class MainActivity : Activity() {

    // ---------- 색 ----------
    private object C {
        val bg = Color.parseColor("#F4F5F7")
        val card = Color.WHITE
        val line = Color.parseColor("#E5E7EB")
        val lineStrong = Color.parseColor("#D1D5DB")
        val text = Color.parseColor("#111827")
        val sub = Color.parseColor("#6B7280")
        val faint = Color.parseColor("#9CA3AF")
        val header = Color.parseColor("#1E2430")
        val headerSub = Color.parseColor("#AEB6C4")
        val field = Color.parseColor("#F3F4F6")
        val primary = Color.parseColor("#2557A7")
        val green = Color.parseColor("#15803D")
        val greenBg = Color.parseColor("#ECFDF3")
        val amber = Color.parseColor("#D97706")
        val amberBg = Color.parseColor("#FFFBEB")
        val amberText = Color.parseColor("#92400E")
        val red = Color.parseColor("#C62828")
        val redBg = Color.parseColor("#FEF2F2")
        val soldOut = Color.parseColor("#E07A10")
        val holiday = Color.parseColor("#6D4FC2")
        val signBg = Color.BLACK  // 간판 그림 바탕과 같은 색
    }

    private val dayLabels = listOf(
        Calendar.SUNDAY to "일", Calendar.MONDAY to "월", Calendar.TUESDAY to "화", Calendar.WEDNESDAY to "수",
        Calendar.THURSDAY to "목", Calendar.FRIDAY to "금", Calendar.SATURDAY to "토",
    )

    private lateinit var schedule: StoreSchedule
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refreshAll(); handler.postDelayed(this, 3000) }
    }

    // 갱신해야 하는 뷰들
    private lateinit var statusPill: TextView
    private lateinit var warningBox: LinearLayout
    private lateinit var warningText: TextView
    private lateinit var warningButton: TextView
    private lateinit var signFrame: FrameLayout
    private lateinit var signImage: ImageView
    private lateinit var nowText: TextView
    private lateinit var healthDot: View
    private lateinit var healthText: TextView
    private lateinit var nextText: TextView
    private lateinit var resultText: TextView
    private lateinit var soldOutButton: TextView
    private lateinit var closedTodayButton: TextView
    private lateinit var openButton: TextView
    private lateinit var closeButton: TextView
    private val dayChips = mutableMapOf<Int, TextView>()
    private val statusPreviews = mutableMapOf<StoreStatus, ImageView>()
    private val statusNames = mutableMapOf<StoreStatus, TextView>()
    private val statusValues = mutableMapOf<StoreStatus, TextView>()
    private lateinit var directInput: EditText
    private val orientationChips = mutableMapOf<Orientation, TextView>()
    private val tabs = mutableListOf<TextView>()
    private val pages = mutableListOf<View>()
    private var currentTab = 0
    private var shownPreview: Bitmap? = null
    private var busy = false

    /** 뷰를 만들 때 쓰는 Context (apply 블록 안에서도 액티비티를 가리키도록) */
    private val ctx: Context get() = this

    private val service: LedService? get() = LedService.instance
    private val controller: SignController? get() = service?.controller
    private val signPrefs get() = getSharedPreferences("sign", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TextRenderer.loadFont(ctx)
        schedule = StoreSchedule(ctx)
        window.statusBarColor = C.header
        // 키보드가 올라오면 화면을 줄여 입력칸이 가려지지 않게
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        setContentView(buildUi())
        if (hasPermissions()) LedService.start(ctx) else requestPermissionsNow()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    // =====================================================================
    // 화면 구성
    // =====================================================================

    private fun buildUi(): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.bg)
        }
        root.addView(header())

        warningBox = warningBanner()
        root.addView(warningBox, matchWrap().apply { setMargins(dp(16), dp(12), dp(16), 0) })

        val wide = resources.configuration.screenWidthDp >= 700
        val body = FrameLayout(ctx)
        pages += homePage(wide)
        pages += schedulePage(wide)
        pages += settingsPage()
        pages.forEach { body.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        selectTab(0)
        return root
    }

    private fun header(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(C.header)
        setPadding(dp(20), dp(8), dp(16), dp(8))
        minimumHeight = dp(56)
        addView(TextView(context).apply {
            text = "입구 간판"
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, wrapWrap().apply { rightMargin = dp(20) })

        val seg = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Color.parseColor("#2C3444"), 10)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        listOf("지금", "시간표·문구", "설정").forEachIndexed { i, name ->
            val tab = TextView(context).apply {
                text = name; textSize = 15f; gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(18), dp(7), dp(18), dp(7))
                setOnClickListener { selectTab(i) }
            }
            tabs += tab
            seg.addView(tab, wrapWrap())
        }
        addView(seg)
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))

        statusPill = TextView(context).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        addView(statusPill)
    }

    private fun selectTab(i: Int) {
        currentTab = i
        pages.forEachIndexed { k, p -> p.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { k, t ->
            val on = k == i
            t.background = if (on) rounded(Color.WHITE, 8) else null
            t.setTextColor(if (on) C.text else C.headerSub)
        }
        if (i != 0) hideKeyboard()
    }

    private fun warningBanner() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(C.amberBg, 12, Color.parseColor("#F5C26B"))
        setPadding(dp(16), dp(10), dp(10), dp(10))
        warningText = TextView(context).apply { textSize = 15f; setTextColor(C.amberText); typeface = Typeface.DEFAULT_BOLD }
        addView(warningText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        warningButton = pillButton("", C.amber, Color.WHITE) {}
        addView(warningButton)
        visibility = View.GONE
    }

    // =====================================================================
    // 탭 1: 지금
    // =====================================================================

    private fun homePage(wide: Boolean): View {
        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        right.addView(statusCard(), matchWrap())
        right.addView(todayCard(), matchWrap().apply { topMargin = dp(12) })
        right.addView(directCard(), matchWrap().apply { topMargin = dp(12) })

        if (!wide) {
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(signCard(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)))
                addView(right, matchWrap().apply { topMargin = dp(12) })
            }
            return ScrollView(ctx).apply { addView(col) }
        }
        // 화면이 낮으면 오른쪽만 스크롤 (평소엔 스크롤 없이 다 보임)
        val rightScroll = ScrollView(ctx).apply { addView(right); isFillViewport = true; isVerticalScrollBarEnabled = false }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(signCard(), LinearLayout.LayoutParams(dp(190), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(rightScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(16) })
        }
    }

    /** 왼쪽: 지금 간판 모습 (검은 액자, 남는 높이에 맞춰 도트를 정수배로 키움) */
    private fun signCard(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C.signBg, 16)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        gravity = Gravity.CENTER_HORIZONTAL
        addView(TextView(context).apply {
            text = "실제 간판 모습"; textSize = 12f; setTextColor(Color.parseColor("#8A93A6"))
            gravity = Gravity.CENTER
        }, matchWrap())
        signImage = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER }
        signFrame = FrameLayout(context).apply {
            addView(signImage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                if (r - l != or - ol || b - t != ob - ot) handler.post { shownPreview?.let { fitSign(it) } }
            }
        }
        addView(signFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
    }

    private fun statusCard(): View = card {
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("지금 간판"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(outlineButton("↻ 시간표대로 다시 보내기") { resend() })
        addView(top)

        nowText = TextView(ctx).apply {
            textSize = 30f; setTextColor(C.text); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, 0)
        }
        addView(nowText)

        val health = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        healthDot = View(ctx)
        health.addView(healthDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) })
        healthText = TextView(ctx).apply { textSize = 15f; setTextColor(C.text); typeface = Typeface.DEFAULT_BOLD }
        health.addView(healthText)
        addView(health)

        nextText = TextView(ctx).apply { textSize = 15f; setTextColor(C.sub); setPadding(0, dp(4), 0, 0) }
        addView(nextText)

    }

    private fun todayCard(): View = card {
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("오늘만 바꾸기"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(ctx).apply { text = "다음 날 자동으로 풀림"; textSize = 12f; setTextColor(C.faint) })
        addView(top)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        soldOutButton = bigButton { schedule.soldOutToday = !schedule.soldOutToday; onScheduleChanged() }
        closedTodayButton = bigButton { schedule.closedToday = !schedule.closedToday; onScheduleChanged() }
        row.addView(soldOutButton, LinearLayout.LayoutParams(0, dp(72), 1f).apply { rightMargin = dp(6) })
        row.addView(closedTodayButton, LinearLayout.LayoutParams(0, dp(72), 1f).apply { leftMargin = dp(6) })
        addView(row)
    }

    private fun directCard(): View = card {
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("직접 보내기"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(ctx).apply { text = "다음 시간표 변경 때까지 유지"; textSize = 12f; setTextColor(C.faint) })
        addView(top)
        directInput = EditText(ctx).apply {
            hint = "간판에 띄울 문구  (| = 화면 나누기)"
            textSize = 16f
            setSingleLine()
            setTextColor(C.text)
            setHintTextColor(C.faint)
            background = rounded(C.field, 10)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        row.addView(directInput, LinearLayout.LayoutParams(0, dp(44), 1f))
        row.addView(outlineButton("미리보기") { showDirectPreview() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { leftMargin = dp(8) })
        row.addView(pillButton("보내기", C.primary, Color.WHITE) {
            val t = directInput.text.toString().trim()
            if (t.isNotEmpty()) {
                hideKeyboard()
                send("'$t' 보내는 중…") { it.show(ShowRequest(t, LedColor.WHITE)) }
            }
        }.apply { background = touchable(C.primary, 10) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { leftMargin = dp(8) })
        addView(row)
    }

    // =====================================================================
    // 탭 2: 시간표·문구
    // =====================================================================

    private fun schedulePage(wide: Boolean): View {
        if (!wide) return scrollColumn(listOf(hoursCard(), textsCard()))
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), 0)
            addView(scrollColumn(listOf(hoursCard()), pad = false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(scrollColumn(listOf(textsCard()), pad = false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(16) })
        }
    }

    private fun hoursCard(): View = card {
        addView(cardTitle("영업 시간"))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        openButton = timeChip().also { b ->
            b.setOnClickListener { pickTime(schedule.openMin) { schedule.openMin = it; onScheduleChanged() } }
        }
        closeButton = timeChip().also { b ->
            b.setOnClickListener { pickTime(schedule.closeMin) { schedule.closeMin = it; onScheduleChanged() } }
        }
        row.addView(labeled("오픈", openButton), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(ctx).apply {
            text = "~"; textSize = 22f; setTextColor(C.faint); setPadding(dp(10), 0, dp(10), dp(14))
        })
        row.addView(labeled("마감", closeButton), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(row)

        addView(subTitle("정기 휴무일"))
        val days = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        dayLabels.forEachIndexed { i, (day, name) ->
            val chip = TextView(ctx).apply {
                text = name; textSize = 16f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                setOnClickListener { schedule.setHolidayDay(day, !schedule.isHolidayDay(day)); onScheduleChanged() }
            }
            dayChips[day] = chip
            days.addView(chip, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) leftMargin = dp(6) })
        }
        addView(days)

        addView(divider(top = 16))
        addView(switchRow("시간표대로 자동으로 바꾸기", null, schedule.autoEnabled) {
            schedule.autoEnabled = it; onScheduleChanged()
        })
        addView(switchRow("휴무 앞뒤에도 휴무 문구", "휴무 전날 마감 후 ~ 휴무 다음 날 오픈 전", schedule.holidayAroundClosed) {
            schedule.holidayAroundClosed = it; onScheduleChanged()
        })
    }

    private fun textsCard(): View = card {
        addView(cardTitle("간판 문구"))
        addView(hint("눌러서 고치기 · 띄어쓰기 = 조금 띄우기 · | = 화면 나누기").apply { setPadding(0, 0, 0, dp(6)) })
        StoreStatus.entries.forEachIndexed { i, s ->
            val item = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(14), dp(10))
                background = touchable(C.card, 12, C.line)
                setOnClickListener { editStatusText(s) }
            }
            val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER }
            statusPreviews[s] = img
            item.addView(FrameLayout(ctx).apply {
                background = rounded(C.signBg, 8)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                minimumWidth = dp(56)
                addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }, wrapWrap())

            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
            val name = TextView(ctx).apply { textSize = 13f; setTextColor(C.sub); typeface = Typeface.DEFAULT_BOLD }
            val value = TextView(ctx).apply { textSize = 18f; setTextColor(C.text); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(2), 0, 0) }
            statusNames[s] = name
            statusValues[s] = value
            texts.addView(name)
            texts.addView(value)
            item.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            item.addView(TextView(ctx).apply { text = "고치기 ›"; textSize = 14f; setTextColor(C.primary); typeface = Typeface.DEFAULT_BOLD })
            addView(item, matchWrap().apply { topMargin = dp(if (i == 0) 4 else 8) })
        }
    }

    // =====================================================================
    // 탭 3: 설정
    // =====================================================================

    private fun settingsPage(): View {
        val c = card {
            addView(cardTitle("패널 방향"))
            addView(hint("글자가 거꾸로 보이면 다른 방향을 고르세요. CoolLED 앱의 270° = 세로 A").apply { setPadding(0, 0, 0, dp(10)) })
            val orientRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            Orientation.entries.forEachIndexed { i, o ->
                val chip = TextView(ctx).apply {
                    text = o.label; textSize = 15f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(8), dp(11), dp(8), dp(11))
                    setOnClickListener {
                        signPrefs.edit().putString("orientation", o.name).apply()
                        controller?.orientation = o
                        onScheduleChanged()
                    }
                }
                orientationChips[o] = chip
                orientRow.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) leftMargin = dp(8)
                })
            }
            addView(orientRow)

            addView(divider(top = 16))
            addView(switchRow("글자 굵게", "CoolLED 앱의 B(굵게)와 같음", signPrefs.getBoolean("bold", true)) {
                signPrefs.edit().putBoolean("bold", it).putFloat("font_px", 16f).apply()
                onScheduleChanged()
            })

            addView(divider(top = 16))
            addView(subTitle("패널 연결"))
            addView(buttonRow(
                "패널 다시 찾기" to { send("패널을 찾는 중…") { it.findSign() } },
                "패널 켜기" to { send("패널 켜는 중…") { it.power(true) } },
                "패널 끄기" to { send("패널 끄는 중…") { it.power(false) } },
            ))
            addView(subTitle("포스기 설정"))
            addView(buttonRow(
                "블루투스·위치 권한" to { requestPermissionsNow() },
                "절전 예외" to { askIgnoreBatteryOptimization() },
                "위치(GPS) 설정" to { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            ))
            addView(subTitle("최근 기록"))
            resultText = TextView(ctx).apply { textSize = 13f; setTextColor(C.sub) }
            addView(resultText)
            addView(subTitle("앱"))
            addView(buttonRow("앱 업데이트" to { runUpdate() }))
            addView(hint("글꼴: Neo둥근모 (OFL-1.1)  ·  패널 통신: coolledx (MIT)").apply { setPadding(0, dp(16), 0, 0) })
        }
        val holder = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(c, LinearLayout.LayoutParams(minOf(dp(620), resources.displayMetrics.widthPixels - dp(32)), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return ScrollView(ctx).apply { addView(holder) }
    }

    // =====================================================================
    // 동작
    // =====================================================================

    private fun onScheduleChanged() {
        service?.scheduler?.kick(forceResend = true)
        refreshAll()
        handler.postDelayed({ refreshAll() }, 6000)
    }

    private fun resend() {
        service?.scheduler?.kick(forceResend = true) ?: toast("앱 권한을 먼저 허용해 주세요")
        resultText.text = "보내는 중…"
        healthText.text = "보내는 중…"
        handler.postDelayed({ refreshAll() }, 5000)
    }

    private fun send(progress: String, job: (SignController) -> String) {
        val c = controller ?: return toast("앱 권한을 먼저 허용해 주세요")
        if (busy) return toast("이전 작업을 처리하는 중입니다")
        busy = true
        resultText.text = progress
        healthText.text = progress
        Thread {
            val msg = try { job(c) } catch (e: Exception) { "실패: ${e.message}" }
            runOnUiThread {
                busy = false
                refreshAll()
                toast(msg)
            }
        }.start()
    }

    private fun showDirectPreview() {
        val t = directInput.text.toString().trim()
        if (t.isEmpty()) return toast("먼저 문구를 입력해 주세요")
        val bmp = previewBitmap(t, LedColor.WHITE) ?: return toast("앱 권한을 먼저 허용해 주세요")
        hideKeyboard()
        AlertDialog.Builder(ctx)
            .setTitle("간판 미리보기")
            .setView(previewBox(bmp, 4))
            .setPositiveButton("보내기") { _, _ -> send("'$t' 보내는 중…") { it.show(ShowRequest(t, LedColor.WHITE)) } }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun previewBox(bmp: Bitmap, k: Int) = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(16), dp(20), 0)
        addView(FrameLayout(ctx).apply {
            background = rounded(C.signBg, 10)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(ImageView(ctx).apply { setImageBitmap(scale(bmp, k)) })
        }, wrapWrap())
    }

    private fun editStatusText(s: StoreStatus) {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val input = EditText(ctx).apply {
            setText(schedule.text(s)); textSize = 20f; setSingleLine()
            setSelection(text.length)
        }
        val previewImg = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER }
        var color = schedule.color(s)
        fun update() {
            val bmp = previewBitmap(input.text.toString(), color)
            if (bmp != null) previewImg.setImageBitmap(scale(bmp, 3))
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(x: android.text.Editable?) = update()
        })
        val swatches = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, dp(12)) }
        val dots = mutableListOf<View>()
        LedColor.entries.forEach { lc ->
            val dot = View(ctx).apply {
                setOnClickListener {
                    color = lc
                    dots.forEachIndexed { i, d -> d.background = swatch(LedColor.entries[i], LedColor.entries[i] == color) }
                    update()
                }
                background = swatch(lc, lc == color)
            }
            dots += dot
            swatches.addView(dot, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(10) })
        }
        left.addView(hint("띄어쓰기 = 조금 띄우기,  | = 화면 나누기 (예: 재료소진|조기마감)"))
        left.addView(input)
        left.addView(subTitle("글자색").apply { setPadding(0, dp(8), 0, 0) })
        left.addView(swatches)
        box.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(FrameLayout(ctx).apply {
            background = rounded(C.signBg, 10)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(previewImg)
        }, wrapWrap().apply { leftMargin = dp(20) })
        update()

        AlertDialog.Builder(ctx)
            .setTitle("${s.label} 문구")
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("저장") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) schedule.setText(s, t)
                schedule.setColor(s, color)
                onScheduleChanged()
            }
            .setNeutralButton("기본값") { _, _ ->
                schedule.setText(s, s.defaultText)
                schedule.setColor(s, s.defaultColor)
                onScheduleChanged()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun pickTime(current: Int, set: (Int) -> Unit) {
        TimePickerDialog(this, { _, h, m -> set(h * 60 + m) }, current / 60, current % 60, true).show()
    }

    // =====================================================================
    // 화면 갱신
    // =====================================================================

    @SuppressLint("SetTextI18n")
    private fun refreshAll() {
        val svc = service
        val c = controller

        // 상단 상태와 경고
        val needPerm = !hasPermissions()
        val locationOff = c?.locationBlocksScan() == true
        when {
            needPerm -> showWarning("블루투스 권한이 필요합니다.", "권한 허용") { requestPermissionsNow() }
            locationOff -> showWarning("위치(GPS)가 꺼져 있어 패널을 찾을 수 없습니다.", "위치 켜기") {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            else -> warningBox.visibility = View.GONE
        }
        val last = c?.lastStatus ?: ""
        val failed = last.contains("실패")
        val ok = last.contains("성공")
        val (pillText, pillFg, pillBg) = when {
            svc == null -> Triple("● 꺼짐", C.headerSub, Color.parseColor("#2C3444"))
            failed -> Triple("● 전송 실패", Color.parseColor("#FCA5A5"), Color.parseColor("#4A1D1D"))
            c?.signName == null && !ok -> Triple("● 패널 미연결", Color.parseColor("#FCD34D"), Color.parseColor("#45370F"))
            else -> Triple("● 간판 연결됨", Color.parseColor("#4ADE80"), Color.parseColor("#143625"))
        }
        statusPill.text = pillText
        statusPill.setTextColor(pillFg)
        statusPill.background = rounded(pillBg, 50)

        // 지금 간판
        val target = schedule.target()
        val shown = c?.lastText ?: schedule.text(target)
        nowText.text = shown.replace("|", " / ").replace(Regex(" +"), " ")
        val preview = c?.lastPreview ?: previewBitmap(schedule.text(target), schedule.color(target))
        if (preview != null && preview !== shownPreview) { shownPreview = preview; fitSign(preview) }
        nextText.text = nextChangeText(target)
        val time = Regex("^(\\d\\d:\\d\\d)").find(last)?.groupValues?.get(1)
        val (hText, hColor) = when {
            svc == null -> "앱이 아직 시작되지 않았습니다" to C.faint
            busy -> "보내는 중…" to C.amber
            failed -> "전송 실패 · 잠시 뒤 다시 시도합니다" to C.red
            ok -> "정상 · ${time ?: ""} 간판에 보냄" to C.green
            else -> "아직 간판에 보낸 적 없음" to C.faint
        }
        healthText.text = hText
        healthText.setTextColor(hColor)
        healthDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(hColor) }
        resultText.text = buildString {
            append(last.ifEmpty { "기록 없음" })
            svc?.scheduler?.lastMessage?.takeIf { it != "-" }?.let { append('\n').append(it) }
        }

        // 오늘만 바꾸기
        styleToggle(soldOutButton, schedule.soldOutToday, C.soldOut,
            "재료소진 조기마감", "누르면 바로 바뀝니다", "조기마감 중", "누르면 취소")
        styleToggle(closedTodayButton, schedule.closedToday, C.holiday,
            "오늘 임시 휴무", "오늘 하루만 휴무 문구", "임시 휴무 중", "누르면 취소")

        // 영업 시간
        openButton.text = StoreSchedule.fmt(schedule.openMin)
        closeButton.text = StoreSchedule.fmt(schedule.closeMin)
        dayChips.forEach { (day, chip) ->
            val on = schedule.isHolidayDay(day)
            chip.background = touchable(if (on) C.red else C.field, 10)
            chip.setTextColor(if (on) Color.WHITE else C.text)
        }

        // 문구 목록
        StoreStatus.entries.forEach { s ->
            previewBitmap(schedule.text(s), schedule.color(s))?.let { bmp ->
                val k = maxOf(1, dp(120) / bmp.height)
                statusPreviews[s]?.setImageBitmap(Bitmap.createScaledBitmap(bmp, bmp.width * k, bmp.height * k, false))
            }
            val isNow = s == target
            statusNames[s]?.text = if (isNow) "${s.label}  ·  지금 표시 중" else s.label
            statusNames[s]?.setTextColor(if (isNow) C.green else C.sub)
            statusValues[s]?.text = schedule.text(s).replace("|", "  |  ")
        }

        // 설정
        val current = Orientation.parse(signPrefs.getString("orientation", null))
        orientationChips.forEach { (o, chip) ->
            val on = o == current
            chip.background = touchable(if (on) C.primary else C.field, 10)
            chip.setTextColor(if (on) Color.WHITE else C.text)
        }
    }

    /** 간판 그림을 액자 크기에 맞게 도트 단위 정수배로 키웁니다(도트가 뭉개지지 않게). */
    private fun fitSign(bmp: Bitmap) {
        val w = signFrame.width
        val h = signFrame.height
        if (w <= 0 || h <= 0) { signImage.setImageBitmap(scale(bmp, 3)); return }
        val k = maxOf(1, minOf(w / bmp.width, h / bmp.height))
        signImage.setImageBitmap(Bitmap.createScaledBitmap(bmp, bmp.width * k, bmp.height * k, false))
    }

    /** 다음 시간표 변경 시각을 찾습니다(최대 8일 앞까지, 1분 단위). */
    private fun nextChangeText(now: StoreStatus): String {
        if (!schedule.autoEnabled) return "자동 전환이 꺼져 있습니다"
        val cal = Calendar.getInstance()
        cal.set(Calendar.SECOND, 0)
        for (i in 1..(8 * 24 * 60)) {
            cal.add(Calendar.MINUTE, 1)
            val t = schedule.target(cal)
            if (t != now) {
                val days = listOf("", "일", "월", "화", "수", "목", "금", "토")
                val today = Calendar.getInstance()
                val diff = cal.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR)
                val dayText = when (diff) {
                    0 -> "오늘"
                    1, -364, -365 -> "내일(${days[cal.get(Calendar.DAY_OF_WEEK)]})"
                    else -> "${days[cal.get(Calendar.DAY_OF_WEEK)]}요일"
                }
                val whenText = "$dayText " + StoreSchedule.fmt(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE))
                return "다음  $whenText  →  ${schedule.text(t).replace("|", " / ").replace(Regex(" +"), " ")}"
            }
        }
        return "시간표상 바뀌지 않습니다"
    }

    /** 실제로 보이는 모습(세로)의 미리보기. 서비스가 없으면 null */
    private fun previewBitmap(text: String, color: LedColor): Bitmap? {
        val c = controller ?: return null
        if (text.isBlank()) return null
        return try { c.preview(ShowRequest(text, color)).signPreview } catch (_: Exception) { null }
    }

    private fun scale(bmp: Bitmap, k: Int): Bitmap =
        Bitmap.createScaledBitmap(bmp, bmp.width * dp(k) / dp(1), bmp.height * dp(k) / dp(1), false)

    private fun showWarning(msg: String, action: String, onClick: () -> Unit) {
        warningBox.visibility = View.VISIBLE
        warningText.text = msg
        warningButton.text = action
        warningButton.setOnClickListener { onClick() }
    }

    private fun styleToggle(v: TextView, on: Boolean, color: Int, offTitle: String, offSub: String, onTitle: String, onSub: String) {
        val title = if (on) onTitle else offTitle
        val sub = if (on) onSub else offSub
        v.text = SpannableStringBuilder().apply {
            append(title, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("\n")
            append(sub, AbsoluteSizeSpan(13, true), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        v.background = touchable(if (on) color else C.card, 12, if (on) color else C.lineStrong)
        v.setTextColor(if (on) Color.WHITE else C.text)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() }
    }

    // =====================================================================
    // 권한
    // =====================================================================

    private fun neededPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    private fun hasPermissions() = neededPermissions()
        .filter { it != Manifest.permission.POST_NOTIFICATIONS }
        .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPermissionsNow() = requestPermissions(neededPermissions(), 1)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPermissions()) {
            LedService.start(ctx)
            handler.postDelayed({ refreshAll() }, 500)
        } else {
            toast("블루투스 권한이 있어야 간판을 바꿀 수 있습니다")
        }
    }

    @SuppressLint("BatteryLife")
    private fun askIgnoreBatteryOptimization() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("이미 절전 예외로 설정되어 있습니다")
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // =====================================================================
    // 작은 부품들
    // =====================================================================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun wrapWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** 앱 업데이트 버튼 */
    private fun runUpdate() {
        val u = Updater(this, "led-sign.apk")
        toast("새 버전 확인 중…")
        u.check { latest, cur ->
            if (latest == null) toast("업데이트 확인 실패 (인터넷 연결을 확인해 주세요)")
            else if (latest <= cur) toast("최신 버전입니다 (버전 $cur)")
            else u.install { toast(it) }
        }
    }


    private fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun touchable(fill: Int, radiusDp: Int, stroke: Int? = null) =
        RippleDrawable(ColorStateList.valueOf(Color.parseColor("#22000000")), rounded(fill, radiusDp, stroke), null)

    private fun swatch(c: LedColor, selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(c.argb)
        setStroke(dp(if (selected) 4 else 1), if (selected) C.primary else C.lineStrong)
    }

    /** 흰 카드: 그림자 대신 얇은 테두리 (안드로이드 8 저사양에서도 선명) */
    private fun card(body: LinearLayout.() -> Unit): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C.card, 14, C.line)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        body()
    }

    private fun scrollColumn(cards: List<View>, pad: Boolean = true): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            if (pad) setPadding(dp(16), dp(16), dp(16), dp(16)) else setPadding(0, 0, 0, dp(16))
        }
        cards.forEachIndexed { i, c -> col.addView(c, matchWrap().apply { if (i > 0) topMargin = dp(12) }) }
        return ScrollView(ctx).apply { addView(col); isVerticalScrollBarEnabled = false }
    }

    private fun label(t: String) = TextView(ctx).apply {
        text = t; textSize = 14f; setTextColor(C.sub); typeface = Typeface.DEFAULT_BOLD
    }

    private fun cardTitle(t: String) = TextView(ctx).apply {
        text = t; textSize = 17f; setTextColor(C.text); typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(12))
    }

    private fun subTitle(t: String) = TextView(ctx).apply {
        text = t; textSize = 14f; setTextColor(C.sub); typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(16), 0, dp(8))
    }

    private fun hint(t: String) = TextView(ctx).apply {
        text = t; textSize = 13f; setTextColor(C.sub); setPadding(0, dp(6), 0, 0)
    }

    private fun divider(top: Int) = View(ctx).apply { setBackgroundColor(C.line) }.also {
        it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(top) }
    }

    private fun pillButton(t: String, fill: Int, textColor: Int, onClick: () -> Unit) = TextView(ctx).apply {
        text = t; textSize = 15f; setTextColor(textColor); typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = touchable(fill, 50)
        setOnClickListener { onClick() }
    }

    private fun outlineButton(t: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = t; textSize = 14f; setTextColor(C.text); typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = touchable(C.card, 10, C.lineStrong)
        setOnClickListener { onClick() }
    }

    private fun bigButton(onClick: () -> Unit) = TextView(ctx).apply {
        textSize = 18f; gravity = Gravity.CENTER
        setLineSpacing(dp(2).toFloat(), 1f)
        setOnClickListener { onClick() }
    }

    private fun timeChip() = TextView(ctx).apply {
        textSize = 28f; typeface = Typeface.DEFAULT_BOLD; setTextColor(C.text); gravity = Gravity.CENTER
        setPadding(0, dp(10), 0, dp(10))
        background = touchable(C.field, 12)
    }

    private fun labeled(label: String, v: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply { text = label; textSize = 13f; setTextColor(C.sub); setPadding(dp(2), 0, 0, dp(6)) })
        addView(v, matchWrap())
    }

    private fun switchRow(label: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(14), 0, 0)
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(context).apply { text = label; textSize = 16f; setTextColor(C.text) })
        if (sub != null) texts.addView(TextView(context).apply { text = sub; textSize = 12f; setTextColor(C.sub); setPadding(0, dp(2), 0, 0) })
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(Switch(context).apply {
            isChecked = checked
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(C.primary, Color.parseColor("#F9FAFB")))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.parseColor("#8FA9D6"), C.lineStrong))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        })
    }

    private fun buttonRow(vararg items: Pair<String, () -> Unit>) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        items.forEachIndexed { i, (t, f) ->
            addView(outlineButton(t, f).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) leftMargin = dp(8)
                })
        }
    }
}
