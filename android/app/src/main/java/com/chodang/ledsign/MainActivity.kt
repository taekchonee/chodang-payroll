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
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

/**
 * 포스기 화면용 앱 UI. (AndroidX 없이 기본 위젯만 사용 — Android 8 에서도 같은 모양)
 */
class MainActivity : Activity() {

    // ---------- 색 ----------
    private object C {
        val bg = Color.parseColor("#F2F3F7")
        val card = Color.WHITE
        val text = Color.parseColor("#1B1D29")
        val sub = Color.parseColor("#6B7085")
        val line = Color.parseColor("#E3E5EC")
        val header = Color.parseColor("#1F2336")
        val primary = Color.parseColor("#3D5AFE")
        val orange = Color.parseColor("#FF7A1A")
        val purple = Color.parseColor("#7E57C2")
        val green = Color.parseColor("#22B35E")
        val red = Color.parseColor("#E5484D")
        val amber = Color.parseColor("#FFB020")
        val chip = Color.parseColor("#EEF0F6")
        val signBg = Color.parseColor("#0B0C10")
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
    private lateinit var signImage: ImageView
    private lateinit var nowText: TextView
    private lateinit var nextText: TextView
    private lateinit var resultText: TextView
    private lateinit var soldOutButton: TextView
    private lateinit var closedTodayButton: TextView
    private lateinit var openButton: TextView
    private lateinit var closeButton: TextView
    private val dayChips = mutableMapOf<Int, TextView>()
    private val statusPreviews = mutableMapOf<StoreStatus, ImageView>()
    private val statusTexts = mutableMapOf<StoreStatus, TextView>()
    private lateinit var directInput: EditText
    private lateinit var directPreview: ImageView
    private lateinit var settingsBody: LinearLayout
    private val orientationChips = mutableMapOf<Orientation, TextView>()
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

        val wide = resources.configuration.screenWidthDp >= 800
        val leftCards = listOf(nowCard(), todayCard(), directCard())
        val rightCards = listOf(hoursCard(), textsCard(), settingsCard())

        val body: View = if (wide) {
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(column(leftCards), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(column(rightCards), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
        } else {
            column(leftCards + rightCards)
        }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun column(cards: List<View>): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        cards.forEach { col.addView(it, matchWrap().apply { topMargin = dp(12) }) }
        return ScrollView(ctx).apply { addView(col); isFillViewport = true }
    }

    private fun header(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(C.header)
        setPadding(dp(20), dp(14), dp(16), dp(14))
        addView(TextView(context).apply {
            text = "입구 간판"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusPill = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = rounded(Color.parseColor("#33FFFFFF"), 50)
        }
        addView(statusPill)
    }

    private fun warningBanner() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Color.parseColor("#FFF4DB"), 14, Color.parseColor("#FFD68A"))
        setPadding(dp(16), dp(12), dp(12), dp(12))
        warningText = TextView(context).apply { textSize = 15f; setTextColor(Color.parseColor("#7A4B00")) }
        addView(warningText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        warningButton = pillButton("", C.amber, Color.WHITE) {}
        addView(warningButton)
        visibility = View.GONE
    }

    // ---------- 지금 간판 ----------
    private fun nowCard(): View = card("지금 간판") {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val frame = FrameLayout(ctx).apply {
            background = rounded(C.signBg, 14)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        signImage = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true }
        frame.addView(signImage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(frame)

        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, 0, 0)
        }
        nowText = TextView(ctx).apply { textSize = 30f; setTextColor(C.text); typeface = Typeface.DEFAULT_BOLD }
        nextText = TextView(ctx).apply { textSize = 16f; setTextColor(C.sub); setPadding(0, dp(6), 0, 0) }
        resultText = TextView(ctx).apply { textSize = 13f; setTextColor(C.sub); setPadding(0, dp(14), 0, 0) }
        info.addView(nowText)
        info.addView(nextText)
        info.addView(resultText)
        info.addView(pillButton("지금 시간표대로 다시 보내기", C.chip, C.text) { resend() },
            wrapWrap().apply { topMargin = dp(14) })
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(row)
    }

    // ---------- 오늘만 바꾸기 ----------
    private fun todayCard(): View = card("오늘만 바꾸기") {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        soldOutButton = bigButton { schedule.soldOutToday = !schedule.soldOutToday; onScheduleChanged() }
        closedTodayButton = bigButton { schedule.closedToday = !schedule.closedToday; onScheduleChanged() }
        row.addView(soldOutButton, LinearLayout.LayoutParams(0, dp(96), 1f).apply { rightMargin = dp(6) })
        row.addView(closedTodayButton, LinearLayout.LayoutParams(0, dp(96), 1f).apply { leftMargin = dp(6) })
        addView(row)
        addView(hint("둘 다 다음 날이 되면 자동으로 풀립니다. 조기마감은 마감 시간이 되면 '영업종료'로 바뀝니다."))
    }

    // ---------- 영업 시간 ----------
    private fun hoursCard(): View = card("영업 시간") {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        openButton = timeChip { schedule.openMin }.also { b ->
            b.setOnClickListener { pickTime(schedule.openMin) { schedule.openMin = it; onScheduleChanged() } }
        }
        closeButton = timeChip { schedule.closeMin }.also { b ->
            b.setOnClickListener { pickTime(schedule.closeMin) { schedule.closeMin = it; onScheduleChanged() } }
        }
        row.addView(labeled("오픈", openButton), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(ctx).apply {
            text = "~"; textSize = 26f; setTextColor(C.sub); setPadding(dp(12), dp(18), dp(12), 0)
        })
        row.addView(labeled("마감", closeButton), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(row)

        addView(subTitle("정기 휴무일"))
        val days = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        dayLabels.forEach { (day, name) ->
            val chip = TextView(ctx).apply {
                text = name; textSize = 17f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                setOnClickListener { schedule.setHolidayDay(day, !schedule.isHolidayDay(day)); onScheduleChanged() }
            }
            dayChips[day] = chip
            days.addView(chip, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        addView(days)

        addView(switchRow("휴무 전날 마감 후 ~ 휴무 다음 날 오픈 전에도 휴무 문구", schedule.holidayAroundClosed) {
            schedule.holidayAroundClosed = it; onScheduleChanged()
        })
        addView(switchRow("시간표대로 자동으로 바꾸기", schedule.autoEnabled) {
            schedule.autoEnabled = it; onScheduleChanged()
        })
    }

    // ---------- 간판 문구 ----------
    private fun textsCard(): View = card("간판 문구") {
        addView(hint("눌러서 수정하세요. 띄어쓰기는 조금 띄우기, | 는 화면 나누기(번갈아 표시)입니다."))
        val scroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        StoreStatus.entries.forEach { s ->
            val item = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = touchable(C.chip, 14)
                setOnClickListener { editStatusText(s) }
            }
            val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true }
            statusPreviews[s] = img
            item.addView(FrameLayout(ctx).apply {
                background = rounded(C.signBg, 8)
                setPadding(dp(6), dp(6), dp(6), dp(6))
                addView(img)
            })
            val label = TextView(ctx).apply {
                text = s.label; textSize = 15f; setTextColor(C.text); typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
            }
            statusTexts[s] = label
            item.addView(label)
            row.addView(item, wrapWrap().apply { rightMargin = dp(10) })
        }
        scroll.addView(row)
        addView(scroll)
    }

    // ---------- 직접 보내기 ----------
    private fun directCard(): View = card("직접 보내기") {
        directInput = EditText(ctx).apply {
            hint = "간판에 띄울 문구"
            textSize = 18f
            setSingleLine()
            background = rounded(C.chip, 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { updateDirectPreview() }
            })
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(directInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(pillButton("보내기", C.primary, Color.WHITE) {
            val t = directInput.text.toString().trim()
            if (t.isNotEmpty()) send("'$t' 보내는 중…") { it.show(ShowRequest(t, LedColor.WHITE)) }
        }, wrapWrap().apply { leftMargin = dp(10) })
        addView(row)
        directPreview = ImageView(ctx).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START }
        addView(FrameLayout(ctx).apply {
            background = rounded(C.signBg, 10)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(directPreview)
        }, wrapWrap().apply { topMargin = dp(10) })
        addView(hint("직접 보낸 문구는 다음 시간표 변경 때까지 유지됩니다."))
    }

    // ---------- 설정 ----------
    private fun settingsCard(): View = card(null) {
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
        }
        val title = TextView(ctx).apply { text = "설정"; textSize = 19f; setTextColor(C.text); typeface = Typeface.DEFAULT_BOLD }
        val arrow = TextView(ctx).apply { text = "펼치기 ▾"; textSize = 15f; setTextColor(C.primary) }
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(arrow)
        addView(head)

        settingsBody = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        head.setOnClickListener {
            val open = settingsBody.visibility != View.VISIBLE
            settingsBody.visibility = if (open) View.VISIBLE else View.GONE
            arrow.text = if (open) "접기 ▴" else "펼치기 ▾"
        }

        settingsBody.addView(subTitle("패널 방향"))
        settingsBody.addView(hint("글자가 거꾸로 보이면 다른 방향을 고르세요. CoolLED 앱의 270° = 세로 A"))
        val orientRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        Orientation.entries.forEach { o ->
            val chip = TextView(ctx).apply {
                text = o.label; textSize = 15f; gravity = Gravity.CENTER
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setOnClickListener {
                    signPrefs.edit().putString("orientation", o.name).apply()
                    controller?.orientation = o
                    onScheduleChanged()
                }
            }
            orientationChips[o] = chip
            orientRow.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
        settingsBody.addView(orientRow)

        settingsBody.addView(switchRow("글자 굵게", signPrefs.getBoolean("bold", true)) {
            signPrefs.edit().putBoolean("bold", it).putFloat("font_px", 16f).apply()
            onScheduleChanged()
        })

        settingsBody.addView(subTitle("패널 연결"))
        settingsBody.addView(buttonRow(
            "패널 다시 찾기" to { send("패널을 찾는 중…") { it.findSign() } },
            "패널 켜기" to { send("패널 켜는 중…") { it.power(true) } },
            "패널 끄기" to { send("패널 끄는 중…") { it.power(false) } },
        ))
        settingsBody.addView(subTitle("포스기 설정"))
        settingsBody.addView(buttonRow(
            "블루투스·위치 권한" to { requestPermissionsNow() },
            "절전 예외" to { askIgnoreBatteryOptimization() },
            "위치(GPS) 설정" to { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
        ))
        settingsBody.addView(hint("글꼴: Neo둥근모 (OFL-1.1)  ·  패널 통신: coolledx (MIT)"))
        addView(settingsBody)
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
        handler.postDelayed({ refreshAll() }, 5000)
    }

    private fun send(progress: String, job: (SignController) -> String) {
        val c = controller ?: return toast("앱 권한을 먼저 허용해 주세요")
        if (busy) return toast("이전 작업을 처리하는 중입니다")
        busy = true
        resultText.text = progress
        Thread {
            val msg = try { job(c) } catch (e: Exception) { "실패: ${e.message}" }
            runOnUiThread {
                busy = false
                refreshAll()
                toast(msg)
            }
        }.start()
    }

    private fun editStatusText(s: StoreStatus) {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val input = EditText(ctx).apply {
            setText(schedule.text(s)); textSize = 20f; setSingleLine()
            setSelection(text.length)
        }
        val preview = ImageView(ctx).apply { adjustViewBounds = true }
        var color = schedule.color(s)
        fun update() {
            val bmp = previewBitmap(input.text.toString(), color)
            if (bmp != null) preview.setImageBitmap(bmp)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(x: Editable?) = update()
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
            swatches.addView(dot, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(10) })
        }
        box.addView(hint("띄어쓰기 = 조금 띄우기,  | = 화면 나누기 (예: 재료소진|조기마감)"))
        box.addView(input)
        box.addView(swatches)
        box.addView(FrameLayout(ctx).apply {
            background = rounded(C.signBg, 10)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(preview)
        }, wrapWrap())
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
        val (pillText, pillColor) = when {
            svc == null -> "● 꺼짐" to C.sub
            last.contains("실패") -> "● 전송 실패" to C.red
            c?.signName == null -> "● 패널 미연결" to C.amber
            else -> "● 연결됨" to C.green
        }
        statusPill.text = pillText
        statusPill.setTextColor(Color.WHITE)
        statusPill.background = rounded(pillColor, 50)

        // 지금 간판
        val target = schedule.target()
        val shown = c?.lastText ?: schedule.text(target)
        nowText.text = shown.replace("|", " / ")
        val preview = c?.lastPreview ?: previewBitmap(schedule.text(target), schedule.color(target), raw = true)
        preview?.let { signImage.setImageBitmap(scale(it, 5)) }
        nextText.text = nextChangeText(target)
        resultText.text = buildString {
            append(last.ifEmpty { "아직 보낸 적 없음" })
            svc?.scheduler?.lastMessage?.takeIf { it != "-" }?.let { append("\n").append(it) }
        }

        // 오늘만 바꾸기
        styleToggle(soldOutButton, schedule.soldOutToday, C.orange,
            "재료소진 조기마감", "조기마감 중\n눌러서 취소")
        styleToggle(closedTodayButton, schedule.closedToday, C.purple,
            "오늘 임시 휴무", "임시 휴무 중\n눌러서 취소")

        // 영업 시간
        openButton.text = StoreSchedule.fmt(schedule.openMin)
        closeButton.text = StoreSchedule.fmt(schedule.closeMin)
        dayChips.forEach { (day, chip) ->
            val on = schedule.isHolidayDay(day)
            chip.background = rounded(if (on) C.red else C.chip, 50)
            chip.setTextColor(if (on) Color.WHITE else C.text)
        }

        // 문구 미리보기
        StoreStatus.entries.forEach { s ->
            previewBitmap(schedule.text(s), schedule.color(s))?.let { statusPreviews[s]?.setImageBitmap(scale(it, 2)) }
            statusTexts[s]?.text = if (s == target) "${s.label} · 지금" else s.label
            statusTexts[s]?.setTextColor(if (s == target) C.primary else C.text)
        }
        if (directPreview.drawable == null) updateDirectPreview()

        // 설정
        val current = Orientation.parse(signPrefs.getString("orientation", null))
        orientationChips.forEach { (o, chip) ->
            val on = o == current
            chip.background = rounded(if (on) C.primary else C.chip, 12)
            chip.setTextColor(if (on) Color.WHITE else C.text)
        }
    }

    private fun updateDirectPreview() {
        val t = directInput.text.toString().trim().ifEmpty { "직접입력" }
        previewBitmap(t, LedColor.WHITE)?.let { directPreview.setImageBitmap(scale(it, 2)) }
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
                val sameDay = cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
                val whenText = (if (sameDay) "오늘" else "${days[cal.get(Calendar.DAY_OF_WEEK)]}요일") +
                    " " + StoreSchedule.fmt(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE))
                return "다음: $whenText ‘${schedule.text(t).replace("|", " / ")}’"
            }
        }
        return "시간표상 바뀌지 않습니다"
    }

    /** 실제로 보이는 모습(세로)의 미리보기. 서비스가 없으면 null */
    private fun previewBitmap(text: String, color: LedColor, raw: Boolean = false): Bitmap? {
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

    private fun styleToggle(v: TextView, on: Boolean, color: Int, offText: String, onText: String) {
        v.text = if (on) onText else offText
        v.background = touchable(if (on) color else C.card, 18, if (on) color else C.line)
        v.setTextColor(if (on) Color.WHITE else C.text)
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
        setStroke(dp(if (selected) 4 else 1), if (selected) C.primary else C.line)
    }

    private fun card(title: String?, body: LinearLayout.() -> Unit): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C.card, 18)
        elevation = dp(1).toFloat()
        setPadding(dp(20), dp(18), dp(20), dp(18))
        if (title != null) addView(TextView(context).apply {
            text = title; textSize = 19f; setTextColor(C.text); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        })
        body()
    }

    private fun subTitle(t: String) = TextView(ctx).apply {
        text = t; textSize = 15f; setTextColor(C.sub); typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun hint(t: String) = TextView(ctx).apply {
        text = t; textSize = 13f; setTextColor(C.sub); setPadding(0, dp(8), 0, 0)
    }

    private fun pillButton(t: String, fill: Int, textColor: Int, onClick: () -> Unit) = TextView(ctx).apply {
        text = t; textSize = 15f; setTextColor(textColor); typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(12), dp(18), dp(12))
        background = touchable(fill, 50)
        setOnClickListener { onClick() }
    }

    private fun bigButton(onClick: () -> Unit) = TextView(ctx).apply {
        textSize = 19f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun timeChip(get: () -> Int) = TextView(ctx).apply {
        text = StoreSchedule.fmt(get())
        textSize = 30f; typeface = Typeface.DEFAULT_BOLD; setTextColor(C.text); gravity = Gravity.CENTER
        setPadding(0, dp(12), 0, dp(12))
        background = touchable(C.chip, 14)
    }

    private fun labeled(label: String, v: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply { text = label; textSize = 14f; setTextColor(C.sub); setPadding(dp(4), 0, 0, dp(6)) })
        addView(v, matchWrap())
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(14), 0, 0)
        addView(TextView(context).apply { text = label; textSize = 16f; setTextColor(C.text) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(Switch(context).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        })
    }

    private fun buttonRow(vararg items: Pair<String, () -> Unit>) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        items.forEachIndexed { i, (t, f) ->
            addView(pillButton(t, C.chip, C.text, f).apply { textSize = 14f; setPadding(dp(8), dp(12), dp(8), dp(12)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) leftMargin = dp(8)
                })
        }
    }
}
