package com.rafi.phonelink

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.PaintDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.security.SecureRandom

class MainActivity : Activity() {

    private val reqCapture = 42
    private lateinit var status: TextView
    private lateinit var codeView: TextView
    private lateinit var urlBox: EditText
    private val prefs by lazy { getSharedPreferences("p", MODE_PRIVATE) }

    // theme
    private val cBg = Color.parseColor("#0F0C29")
    private val cPanel = Color.parseColor("#1E1B3A")
    private val cAccent = Color.parseColor("#00D4FF")
    private val cAccent2 = Color.parseColor("#7B2FF7")
    private val cOk = Color.parseColor("#2EE6A6")
    private val cText = Color.parseColor("#EEEEEE")
    private val cMuted = Color.parseColor("#8891A8")

    private fun newCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun pill(fillColor: Int, strokeColor: Int = Color.TRANSPARENT): GradientDrawable {
        val d = GradientDrawable()
        d.cornerRadius = 28f
        d.setColor(fillColor)
        if (strokeColor != Color.TRANSPARENT) d.setStroke(2, strokeColor)
        return d
    }

    private fun gradientPill(c1: Int, c2: Int): GradientDrawable {
        val d = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2))
        d.cornerRadius = 28f
        return d
    }

    private fun btn(label: String, accent: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setTextColor(if (accent) Color.parseColor("#06101A") else cText)
        b.isAllCaps = false
        b.textSize = 15f
        b.setPadding(32, 28, 32, 28)
        b.background = if (accent) gradientPill(cAccent, cAccent2) else pill(cPanel, Color.parseColor("#33FFFFFF"))
        b.stateListAnimator = null
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = 18
        b.layoutParams = lp
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (prefs.getString("code", null) == null) {
            prefs.edit().putString("code", newCode()).apply()
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(56, 120, 56, 80)
        root.setBackgroundColor(cBg)

        val title = TextView(this)
        title.text = "⚡ PhoneLink"
        title.textSize = 30f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        title.paint.shader = LinearGradient(0f, 0f, 420f, 0f, cAccent, cAccent2, Shader.TileMode.CLAMP)
        root.addView(title)

        val sub = TextView(this)
        sub.text = "Remote control, wired up."
        sub.setTextColor(cMuted)
        sub.setPadding(0, 6, 0, 24)
        root.addView(sub)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(36, 36, 36, 36)
        card.background = pill(cPanel).apply { cornerRadius = 36f }
        root.addView(card)

        val label = TextView(this)
        label.text = "PAIRING CODE"
        label.setTextColor(cMuted)
        label.textSize = 12f
        card.addView(label)

        codeView = TextView(this)
        codeView.textSize = 34f
        codeView.setTextColor(cAccent)
        codeView.setTypeface(codeView.typeface, android.graphics.Typeface.BOLD)
        codeView.setTextIsSelectable(true)
        codeView.text = prefs.getString("code", "")
        codeView.setPadding(0, 4, 0, 0)
        card.addView(codeView)

        card.addView(btn("↻ New code") {
            val c = newCode()
            prefs.edit().putString("code", c).apply()
            codeView.text = c
        })

        urlBox = EditText(this)
        urlBox.hint = "Server URL e.g. wss://your-app.onrender.com"
        urlBox.setHintTextColor(cMuted)
        urlBox.setTextColor(cText)
        urlBox.setText(prefs.getString("url", ""))
        urlBox.setSingleLine()
        urlBox.setPadding(28, 24, 28, 24)
        urlBox.background = pill(Color.parseColor("#120F26"), Color.parseColor("#33FFFFFF"))
        val urlLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        urlLp.topMargin = 28
        urlBox.layoutParams = urlLp
        root.addView(urlBox)

        root.addView(btn("1 · Enable accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("2 · Start sharing + control", accent = true) { startSharing() })
        root.addView(btn("■ Stop") {
            startService(Intent(this, LinkService::class.java).setAction(LinkService.ACTION_STOP))
            root.postDelayed({ refresh() }, 400)
        })
        root.addView(btn("⚙ Settings") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })

        status = TextView(this)
        status.setTextColor(cMuted)
        status.setPadding(8, 36, 8, 0)
        root.addView(status)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(cBg)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        urlBox.setText(prefs.getString("url", ""))
        codeView.text = prefs.getString("code", "")
        refresh()
    }

    private fun refresh() {
        val a11y = if (ControlService.instance != null) "ON ✓" else "OFF ✗"
        val link = if (LinkService.running) "RUNNING ●" else "STOPPED ○"
        status.text = "Accessibility: $a11y\nLink: $link"
        status.setTextColor(if (ControlService.instance != null && LinkService.running) cOk else cMuted)
    }

    private fun startSharing() {
        val url = urlBox.text.toString().trim()
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            Toast.makeText(this, "URL must start with ws:// or wss://", Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit().putString("url", url).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, LinkService::class.java)
            i.putExtra("url", prefs.getString("url", ""))
            i.putExtra("code", prefs.getString("code", ""))
            i.putExtra("resultCode", resultCode)
            i.putExtra("data", data)
            startForegroundService(i)
            findViewById<View>(android.R.id.content).postDelayed({ refresh() }, 800)
        }
    }
}
