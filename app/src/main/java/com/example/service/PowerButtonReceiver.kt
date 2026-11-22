package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

class PowerButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PowerButtonReceiver"
        private const val DOUBLE_PRESS_WINDOW_MS = 600L

        @Volatile
        private var lastPressTimeMs = 0L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_SCREEN_OFF || action == Intent.ACTION_SCREEN_ON) {
            val now = SystemClock.elapsedRealtime()
            val delta = now - lastPressTimeMs
            lastPressTimeMs = now

            if (delta in 50L..DOUBLE_PRESS_WINDOW_MS) {
                Log.d(TAG, "Double power press detected (${delta}ms) — triggering MYRA overlay")
                lastPressTimeMs = 0L
                val serviceIntent = Intent(context, MyraOverlayService::class.java).apply {
                    this.action = MyraOverlayService.ACTION_SHOW_OVERLAY
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to trigger MyraOverlayService", e)
                }
            }
        }
    }
}
