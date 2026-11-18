package com.rafi.phonelink

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ControlService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ControlService? = null
    }

    private val main = Handler(Looper.getMainLooper())

    private fun buzz() {
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        if (!prefs.getBoolean("haptics", true)) return
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        main.post {
            buzz()
            val p = Path()
            p.moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) p.lineTo(x2, y2)
            val stroke = GestureDescription.StrokeDescription(p, 0, ms.coerceIn(1, 5000))
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }
    }

    fun global(name: String) {
        main.post {
            buzz()
            val a = when (name) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                "quick" -> GLOBAL_ACTION_QUICK_SETTINGS
                "power" -> GLOBAL_ACTION_POWER_DIALOG
                "lock" -> GLOBAL_ACTION_LOCK_SCREEN
                "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
                else -> return@post
            }
            performGlobalAction(a)
        }
    }

    private fun currentText(n: AccessibilityNodeInfo): String {
        if (n.isShowingHintText) return ""
        return n.text?.toString().orEmpty()
    }

    private fun setText(n: AccessibilityNodeInfo, value: String) {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun typeText(s: String) {
        main.post {
            val n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@post
            setText(n, currentText(n) + s)
        }
    }

    fun backspace() {
        main.post {
            val n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@post
            setText(n, currentText(n).dropLast(1))
        }
    }
}
