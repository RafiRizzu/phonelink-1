package com.rafi.phonelink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class LinkService : Service() {

    companion object {
        const val ACTION_STOP = "stop"
        @Volatile
        var running = false
    }

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private val reconnect = Runnable { connect() }

    private var url = ""
    private var code = ""
    private var screenW = 1
    private var screenH = 1
    private var lastFrame = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("capture")
        thread.start()
        handler = Handler(thread.looper)
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startFg()

        url = intent.getStringExtra("url") ?: ""
        code = intent.getStringExtra("code") ?: ""
        val resultCode = intent.getIntExtra("resultCode", 0)
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null || url.isEmpty() || code.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)

        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        val cw = 540
        val ch = (screenH.toFloat() * cw / screenW).toInt()

        val r = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ onFrame(it) }, handler)
        display = proj.createVirtualDisplay(
            "phonelink", cw, ch, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )

        running = true
        connect()
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("link", "Remote control", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, LinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "link")
            .setContentTitle("PhoneLink active")
            .setContentText("Screen shared. Remote control on.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_delete), "Stop", stop
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun connect() {
        if (!running) return
        ws?.cancel()
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("role", "phone")
                    .put("code", code)
                    .put("w", screenW)
                    .put("h", screenH)
                    .put("a11y", ControlService.instance != null)
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleCommand(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                retry()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                retry()
            }
        })
    }

    private fun retry() {
        if (!running) return
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, 3000)
    }

    private fun handleCommand(text: String) {
        val svc = ControlService.instance ?: return
        try {
            val m = JSONObject(text)
            fun nx(k: String) = m.getDouble(k).coerceIn(0.0, 1.0).toFloat() * screenW
            fun ny(k: String) = m.getDouble(k).coerceIn(0.0, 1.0).toFloat() * screenH
            when (m.getString("t")) {
                "tap" -> svc.swipe(nx("x"), ny("y"), nx("x"), ny("y"), 50)
                "swipe" -> svc.swipe(nx("x1"), ny("y1"), nx("x2"), ny("y2"), m.optLong("ms", 300))
                "key" -> svc.global(m.getString("k"))
                "text" -> svc.typeText(m.getString("s"))
                "backspace" -> svc.backspace()
            }
        } catch (_: Exception) {
        }
    }

    private fun onFrame(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            val prefs = getSharedPreferences("p", MODE_PRIVATE)
            val fps = prefs.getString("fps", "10")?.toLongOrNull()?.coerceIn(1, 30) ?: 10L
            val throttleMs = 1000L / fps

            val now = System.currentTimeMillis()
            val sock = ws
            if (sock == null || now - lastFrame < throttleMs || sock.queueSize() > 1_000_000L) return
            lastFrame = now

            val p = img.planes[0]
            val pad = (p.rowStride - p.pixelStride * img.width) / p.pixelStride
            val full = Bitmap.createBitmap(img.width + pad, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(p.buffer)
            val bmp = if (pad > 0) Bitmap.createBitmap(full, 0, 0, img.width, img.height) else full

            val jpegQ = when (prefs.getString("quality", "medium")) {
                "low" -> 30
                "high" -> 80
                else -> 50
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, jpegQ, out)
            sock.send(out.toByteArray().toByteString())
        } finally {
            img.close()
        }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(reconnect)
        ws?.close(1000, null)
        display?.release()
        reader?.close()
        projection?.stop()
        thread.quitSafely()
        super.onDestroy()
    }
}
