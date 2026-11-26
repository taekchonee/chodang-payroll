package com.chodang.ledsign

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 포스기를 켜거나 앱을 업데이트하면 서비스를 자동으로 다시 시작합니다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try {
                LedService.start(context)
            } catch (_: Exception) {
                // 권한이 아직 없으면 앱을 한 번 열어야 시작됩니다.
            }
        }
    }
}
