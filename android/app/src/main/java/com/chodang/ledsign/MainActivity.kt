package com.chodang.ledsign

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

class MainActivity : Activity() {

    private val modeLabels = listOf("자동" to "auto", "고정" to "static", "왼쪽으로 흐름" to "left", "오른쪽으로 흐름" to "right")
    private val fontSizes = listOf(12, 13, 14, 15, 16)
    private val dayLabels = listOf(
        Calendar.SUNDAY to "일", Calendar.MONDAY to "월", Calendar.TUESDAY to "화", Calendar.WEDNESDAY to "수",
        Calendar.THURSDAY to "목", Calendar.FRIDAY to "금", Calendar.SATURDAY to "토",
    )

    private lateinit var schedule: StoreSchedule
    private lateinit var statusView: TextView
    private lateinit var locationButton: Button
    private lateinit var soldOutButton: Button
    private lateinit var closedTodayButton: Button
    private lateinit var textInput: EditText
    private lateinit var colorSpinner: Spinner
    private lateinit var modeSpinner: Spinner
    private lateinit var fontSpinner: Spinner
    private lateinit var boldCheck: CheckBox
    private lateinit var orientationSpinner: Spinner
    private lateinit var previewView: ImageView
    private val statusTextInputs = mutableMapOf<StoreStatus, EditText>()
    private val statusColorSpinners = mutableMapOf<StoreStatus, Spinner>()

    private val service: LedService? get() = LedService.instance
    private val controller: SignController? get() = service?.controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        schedule = StoreSchedule(this)
        setContentView(buildUi())
        if (hasPermissions()) LedService.start(this) else requestPermissionsNow()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------- 화면 ----------

    private fun buildUi(): View {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(title("LED 간판"))
        statusView = TextView(this).apply { textSize = 15f }
        root.addView(statusView)
        root.addView(row(
            button("새로고침") { refreshStatus() },
            button("패널 찾기") { runInBackground { it.findSign() } },
        ))
        root.addView(row(
            button("권한 허용") { requestPermissionsNow() },
            button("절전 예외 설정") { askIgnoreBatteryOptimization() },
        ))
        locationButton = button("위치(GPS) 켜기") {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        root.addView(row(locationButton))

        // 오늘만 바꾸기
        root.addView(title("오늘만 바꾸기"))
        soldOutButton = button("") {
            schedule.soldOutToday = !schedule.soldOutToday
            onScheduleChanged()
        }
        closedTodayButton = button("") {
            schedule.closedToday = !schedule.closedToday
            onScheduleChanged()
        }
        root.addView(row(soldOutButton, closedTodayButton))
        root.addView(row(button("시간표대로 다시 맞추기") {
            schedule.soldOutToday = false
            schedule.closedToday = false
            onScheduleChanged()
        }))

        // 시간표
        root.addView(title("영업 시간표"))
        root.addView(CheckBox(this).apply {
            text = "시간표대로 자동 전환"
            isChecked = schedule.autoEnabled
            setOnCheckedChangeListener { _, on -> schedule.autoEnabled = on; onScheduleChanged() }
        })
        root.addView(row(
            label("오픈"), timeButton({ schedule.openMin }) { schedule.openMin = it },
            label("마감"), timeButton({ schedule.closeMin }) { schedule.closeMin = it },
        ))
        root.addView(label("정기 휴무 요일"))
        root.addView(row(*dayLabels.map { (day, name) ->
            CheckBox(this).apply {
                text = name
                isChecked = schedule.isHolidayDay(day)
                setOnCheckedChangeListener { _, on -> schedule.setHolidayDay(day, on); onScheduleChanged() }
            }
        }.toTypedArray()))
        root.addView(CheckBox(this).apply {
            text = "휴무 전날 마감 후부터 휴무 다음 날 오픈 전까지 휴무 문구 표시"
            isChecked = schedule.holidayAroundClosed
            setOnCheckedChangeListener { _, on -> schedule.holidayAroundClosed = on; onScheduleChanged() }
        })

        // 문구와 색
        root.addView(title("상태별 문구와 색"))
        root.addView(label("| 를 넣으면 화면을 나눠 한 화면씩 번갈아 보여줍니다. 예) 재료소진|조기마감"))
        StoreStatus.entries.forEach { s ->
            val input = EditText(this).apply { setText(schedule.text(s)); setSingleLine() }
            val color = spinner(LedColor.entries.map { it.label }).apply { setSelection(schedule.color(s).ordinal) }
            statusTextInputs[s] = input
            statusColorSpinners[s] = color
            root.addView(row(label(s.label).apply { minWidth = dp(90) }, input, color))
        }
        root.addView(row(button("문구 저장") {
            StoreStatus.entries.forEach { s ->
                val t = statusTextInputs[s]!!.text.toString().trim()
                if (t.isNotEmpty()) schedule.setText(s, t)
                schedule.setColor(s, LedColor.entries[statusColorSpinners[s]!!.selectedItemPosition])
            }
            toast("저장했습니다")
            onScheduleChanged()
        }))

        // 글자 크기
        root.addView(title("글자"))
        val signPrefs = getSharedPreferences("sign", Context.MODE_PRIVATE)
        fontSpinner = spinner(fontSizes.map { "${it}px" }).apply {
            setSelection(fontSizes.indexOf(signPrefs.getFloat("font_px", 16f).toInt()).coerceAtLeast(0))
        }
        boldCheck = CheckBox(this).apply { text = "굵게"; isChecked = signPrefs.getBoolean("bold", true) }
        orientationSpinner = spinner(Orientation.entries.map { it.label }).apply {
            setSelection(Orientation.parse(signPrefs.getString("orientation", null)).ordinal)
        }
        root.addView(row(label("패널 방향"), orientationSpinner))
        root.addView(label("세로 패널은 글자를 하나씩 눕혀 보냅니다(CoolLED 앱의 270° = 세로 A). 한 화면에 6글자, 공백은 반 글자 간격입니다."))
        root.addView(row(label("크기"), fontSpinner, boldCheck))
        root.addView(row(button("이 설정으로 간판 다시 보내기") {
            controller?.let { applyFont(it) }
            onScheduleChanged()
        }))

        // 직접 보내기
        root.addView(title("직접 보내기"))
        textInput = EditText(this).apply { hint = "간판에 띄울 문구"; setSingleLine() }
        root.addView(textInput)
        colorSpinner = spinner(LedColor.entries.map { it.label }).apply { setSelection(LedColor.GREEN.ordinal) }
        modeSpinner = spinner(modeLabels.map { it.first })
        root.addView(row(label("색"), colorSpinner, label("방식"), modeSpinner))

        previewView = ImageView(this).apply {
            setBackgroundColor(Color.rgb(25, 25, 25))
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
        }
        root.addView(previewView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8); bottomMargin = dp(8) })

        root.addView(row(
            button("미리보기") { showPreview() },
            button("간판에 보내기") {
                val t = textInput.text.toString().trim()
                if (t.isNotEmpty()) runInBackground { applyFont(it); it.show(currentRequest(t)) }
            },
        ))
        root.addView(label("직접 보낸 문구는 다음 시간표 전환 때까지 그대로 유지됩니다."))

        root.addView(title("패널"))
        root.addView(row(
            button("켜기") { runInBackground { it.power(true) } },
            button("끄기") { runInBackground { it.power(false) } },
        ))

        updateTodayButtons()
        return ScrollView(this).apply { addView(root) }
    }

    private fun onScheduleChanged() {
        updateTodayButtons()
        controller?.let { applyFont(it) }
        service?.scheduler?.kick(forceResend = true)
        statusView.postDelayed({ refreshStatus() }, 300)
        statusView.postDelayed({ refreshStatus() }, 8000)
    }

    private fun updateTodayButtons() {
        soldOutButton.text = if (schedule.soldOutToday) "조기마감 취소" else "재료소진 조기마감"
        closedTodayButton.text = if (schedule.closedToday) "임시 휴무 취소" else "오늘 임시 휴무"
    }

    private fun timeButton(get: () -> Int, set: (Int) -> Unit): Button {
        val b = Button(this)
        b.text = StoreSchedule.fmt(get())
        b.setOnClickListener {
            val m = get()
            TimePickerDialog(this, { _, h, min ->
                set(h * 60 + min)
                b.text = StoreSchedule.fmt(h * 60 + min)
                onScheduleChanged()
            }, m / 60, m % 60, true).show()
        }
        return b
    }

    private fun currentRequest(text: String) = ShowRequest(
        text = text,
        color = LedColor.entries[colorSpinner.selectedItemPosition],
        mode = modeLabels[modeSpinner.selectedItemPosition].second,
    )

    private fun applyFont(c: SignController) {
        c.setFont(fontSizes[fontSpinner.selectedItemPosition].toFloat(), boldCheck.isChecked)
        c.orientation = Orientation.entries[orientationSpinner.selectedItemPosition]
    }

    private fun showPreview() {
        val c = controller ?: return toast("서비스가 아직 시작되지 않았습니다. 권한을 허용해 주세요.")
        val t = textInput.text.toString().trim().ifEmpty { schedule.text(schedule.target()) }
        applyFont(c)
        val r = c.preview(currentRequest(t))
        setPreview(r.preview)
        toast(if (r.fits) "한 화면에 들어갑니다" else "한 화면을 넘어서 나눠 보여줍니다")
    }

    private fun runInBackground(job: (SignController) -> String) {
        val c = controller ?: return toast("서비스가 아직 시작되지 않았습니다. 권한을 허용해 주세요.")
        statusView.text = "보내는 중…"
        Thread {
            val msg = try { job(c) } catch (e: Exception) { "실패: ${e.message}" }
            runOnUiThread {
                refreshStatus()
                c.lastPreview?.let { setPreview(it) }
                toast(msg)
            }
        }.start()
    }

    @SuppressLint("SetTextI18n")
    private fun refreshStatus() {
        val svc = service
        val c = svc?.controller
        val (w, h) = c?.signSize ?: (CoolLedBle.DEFAULT_WIDTH to CoolLedBle.DEFAULT_HEIGHT)
        val target = schedule.target()
        val locationOff = c?.locationBlocksScan() == true
        locationButton.visibility = if (locationOff) View.VISIBLE else View.GONE
        statusView.text = buildString {
            appendLine("서비스: " + if (svc == null) "꺼짐 (권한을 허용해 주세요)" else "실행 중")
            if (locationOff) appendLine("⚠ 위치(GPS)가 꺼져 있어 패널을 찾을 수 없습니다")
            appendLine("패널: ${c?.signName ?: "아직 못 찾음"} (${w}×${h})")
            appendLine("지금 시간표: ${target.label}" + if (schedule.autoEnabled) "" else " (자동 전환 꺼짐)")
            appendLine("간판 문구: ${c?.lastText ?: "-"}")
            appendLine("최근 결과: ${c?.lastStatus ?: "-"}")
            append("자동 전환: ${svc?.scheduler?.lastMessage ?: "-"}")
        }
        updateTodayButtons()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun setPreview(bmp: Bitmap) {
        val scale = if (bmp.width > 150) 3 else 5
        previewView.setImageBitmap(Bitmap.createScaledBitmap(bmp, bmp.width * scale, bmp.height * scale, false))
    }

    // ---------- 권한 ----------

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

    private fun requestPermissionsNow() {
        requestPermissions(neededPermissions(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPermissions()) {
            LedService.start(this)
            statusView.postDelayed({ refreshStatus() }, 500)
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

    // ---------- 작은 도우미 ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun label(t: String) = TextView(this).apply { text = t; setPadding(0, 0, dp(8), 0) }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { v ->
            val weight = if (v is Button || v is Spinner || v is EditText) 1f else 0f
            addView(v, LinearLayout.LayoutParams(
                if (weight > 0) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight
            ))
        }
    }
}
