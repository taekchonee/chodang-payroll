package com.chodang.ledsign

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
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

class MainActivity : Activity() {

    private data class Preset(val text: String, val color: LedColor)

    private val presets = listOf(
        Preset("영업중", LedColor.GREEN),
        Preset("브레이크타임", LedColor.YELLOW),
        Preset("영업종료", LedColor.RED),
        Preset("월요일 휴무", LedColor.RED),
        Preset("재료소진 조기마감", LedColor.RED),
    )
    private val modeLabels = listOf("자동" to "auto", "고정" to "static", "왼쪽으로 흐름" to "left", "오른쪽으로 흐름" to "right")
    private val fontSizes = listOf(12, 13, 14, 15, 16)

    private lateinit var statusView: TextView
    private lateinit var textInput: EditText
    private lateinit var colorSpinner: Spinner
    private lateinit var modeSpinner: Spinner
    private lateinit var fontSpinner: Spinner
    private lateinit var boldCheck: CheckBox
    private lateinit var previewView: ImageView

    private val controller: SignController? get() = LedService.instance?.controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        statusView = TextView(this).apply { textSize = 15f; setTextIsSelectable(true) }
        root.addView(statusView)
        root.addView(row(
            button("새로고침") { refreshStatus() },
            button("패널 찾기") { runInBackground { it.findSign() } },
        ))
        root.addView(row(
            button("권한 허용") { requestPermissionsNow() },
            button("절전 예외 설정") { askIgnoreBatteryOptimization() },
        ))

        root.addView(title("빠른 문구"))
        presets.chunked(2).forEach { pair ->
            root.addView(row(*pair.map { p ->
                button(p.text) { send(ShowRequest(p.text, p.color)) }
            }.toTypedArray()))
        }

        root.addView(title("직접 입력"))
        textInput = EditText(this).apply { hint = "간판에 띄울 문구"; setSingleLine() }
        root.addView(textInput)
        colorSpinner = spinner(LedColor.entries.map { it.label })
        colorSpinner.setSelection(LedColor.GREEN.ordinal)
        modeSpinner = spinner(modeLabels.map { it.first })
        root.addView(row(label("색"), colorSpinner, label("방식"), modeSpinner))

        fontSpinner = spinner(fontSizes.map { "${it}px" })
        boldCheck = CheckBox(this).apply { text = "굵게"; isChecked = true }
        root.addView(row(label("글자 크기"), fontSpinner, boldCheck))

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
                if (t.isNotEmpty()) send(currentRequest(t))
            },
        ))

        root.addView(title("패널"))
        root.addView(row(
            button("켜기") { runInBackground { it.power(true) } },
            button("끄기") { runInBackground { it.power(false) } },
        ))

        return ScrollView(this).apply { addView(root) }
    }

    private fun currentRequest(text: String) = ShowRequest(
        text = text,
        color = LedColor.entries[colorSpinner.selectedItemPosition],
        mode = modeLabels[modeSpinner.selectedItemPosition].second,
    )

    private fun applyFont(c: SignController) {
        c.setFont(fontSizes[fontSpinner.selectedItemPosition].toFloat(), boldCheck.isChecked)
    }

    private fun showPreview() {
        val c = controller ?: return toastStatus("서비스가 아직 시작되지 않았습니다. 권한을 허용해 주세요.")
        val t = textInput.text.toString().trim().ifEmpty { "영업중" }
        applyFont(c)
        val r = c.preview(currentRequest(t))
        setPreview(r.preview)
        toastStatus(if (r.fits) "한 화면에 들어갑니다" else "패널보다 길어서 흐르게 표시됩니다")
    }

    private fun send(req: ShowRequest) {
        runInBackground {
            applyFont(it)
            it.show(req)
        }
    }

    private fun runInBackground(job: (SignController) -> String) {
        val c = controller ?: return toastStatus("서비스가 아직 시작되지 않았습니다. 권한을 허용해 주세요.")
        statusView.text = "보내는 중…"
        Thread {
            val msg = try { job(c) } catch (e: Exception) { "실패: ${e.message}" }
            runOnUiThread {
                refreshStatus()
                c.lastPreview?.let { setPreview(it) }
                toastStatus(msg)
            }
        }.start()
    }

    @SuppressLint("SetTextI18n")
    private fun refreshStatus() {
        val svc = LedService.instance
        val c = svc?.controller
        val ip = LedService.localIp() ?: "(네트워크 없음)"
        val (w, h) = c?.signSize ?: (CoolLedBle.DEFAULT_WIDTH to CoolLedBle.DEFAULT_HEIGHT)
        statusView.text = buildString {
            appendLine("서비스: " + when {
                svc == null -> "꺼짐"
                svc.serverError != null -> "웹 서버 오류 (${svc.serverError})"
                else -> "실행 중"
            })
            appendLine("HA 주소: http://$ip:${LedService.PORT}")
            appendLine("접속 키: ${LedService.accessKey(this@MainActivity)}")
            appendLine("패널: ${c?.signName ?: "아직 못 찾음"} (${w}×${h})")
            appendLine("현재 문구: ${c?.lastText ?: "-"}")
            append("최근 결과: ${c?.lastStatus ?: "-"}")
        }
    }

    private fun toastStatus(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun setPreview(bmp: Bitmap) {
        val scale = 6
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) requestPermissions(neededPermissions(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPermissions()) {
            LedService.start(this)
            statusView.postDelayed({ refreshStatus() }, 500)
        } else {
            toastStatus("블루투스 권한이 있어야 간판을 바꿀 수 있습니다")
        }
    }

    @SuppressLint("BatteryLife")
    private fun askIgnoreBatteryOptimization() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toastStatus("이미 절전 예외로 설정되어 있습니다")
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
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(8))
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
            val weight = if (v is Button || v is Spinner) 1f else 0f
            addView(v, LinearLayout.LayoutParams(
                if (weight > 0) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight
            ))
        }
    }
}
