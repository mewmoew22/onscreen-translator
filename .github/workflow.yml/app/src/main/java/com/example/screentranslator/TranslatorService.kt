package com.example.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.math.abs

class TranslatorService : Service() {

    companion object {
        const val ACTION_STOP = "stop"
        const val CHANNEL = "translator"
        const val SCAN_INTERVAL_MS = 1200L
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0
    private var capH = 0
    private var overlay: OverlayView? = null
    private var bubble: TextView? = null
    private var paused = false
    private var running = false
    private val cache = HashMap<String, String>()
    private val cjk = Regex("[\\u4e00-\\u9fff]")

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        startForegroundCompat()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { shutdown() }
            }
        }, handler)

        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                Toast.makeText(this, "Translation model ready", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Model download failed - check internet", Toast.LENGTH_LONG).show()
            }

        addOverlay()
        addBubble()
        handler.postDelayed({ scan() }, 800)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Translator", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, TranslatorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_delete), "Stop", stopPi
        ).build()
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Screen Translator running")
            .addAction(action)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun addOverlay() {
        val v = OverlayView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(v, lp)
        overlay = v
    }

    private fun addBubble() {
        val size = (48 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = "ON"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(170, 30, 30, 30))
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 20
        lp.y = 200

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        var downTime = 0L

        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    downTime = e.eventTime
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (e.eventTime - downTime > 700) shutdown() else togglePause()
                    }
                }
            }
            true
        }
        wm.addView(tv, lp)
        bubble = tv
    }

    private fun togglePause() {
        paused = !paused
        bubble?.text = if (paused) "OFF" else "ON"
        if (paused) overlay?.setLabels(emptyList())
    }

    @Suppress("DEPRECATION")
    private fun realSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private fun ensureCapture(sw: Int, sh: Int) {
        val scale = minOf(1f, 1280f / maxOf(sw, sh))
        val w = (sw * scale).toInt().coerceAtLeast(2)
        val h = (sh * scale).toInt().coerceAtLeast(2)
        if (w == capW && h == capH && virtualDisplay != null) return
        capW = w
        capH = h
        reader?.close()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        val dpi = resources.displayMetrics.densityDpi
        if (virtualDisplay == null) {
            virtualDisplay = projection?.createVirtualDisplay(
                "st", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
        } else {
            virtualDisplay?.resize(w, h, dpi)
            virtualDisplay?.surface = r.surface
        }
    }

    private fun scheduleNext() {
        if (running) handler.postDelayed({ scan() }, SCAN_INTERVAL_MS)
    }

    private fun scan() {
        if (!running) return
        if (paused) {
            scheduleNext()
            return
        }
        val (sw, sh) = realSize()
        ensureCapture(sw, sh)
        // Hide our own labels so the capture sees the real game screen
        overlay?.visibility = View.INVISIBLE
        handler.postDelayed({
            val bmp = grabBitmap()
            overlay?.visibility = View.VISIBLE
            if (bmp == null) {
                scheduleNext()
            } else {
                recognize(bmp, sw, sh)
            }
        }, 120)
    }

    private fun grabBitmap(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return null
        try {
            val plane = img.planes[0]
            val pw = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(pw, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            return if (pw == img.width) full
            else Bitmap.createBitmap(full, 0, 0, img.width, img.height)
        } finally {
            img.close()
        }
    }

    private fun recognize(bmp: Bitmap, sw: Int, sh: Int) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                val sx = sw.toFloat() / bmp.width
                val sy = sh.toFloat() / bmp.height
                val lines = ArrayList<Pair<Rect, String>>()
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        if (cjk.containsMatchIn(line.text)) {
                            lines.add(
                                Pair(
                                    Rect(
                                        (box.left * sx).toInt(), (box.top * sy).toInt(),
                                        (box.right * sx).toInt(), (box.bottom * sy).toInt()
                                    ),
                                    line.text
                                )
                            )
                        }
                    }
                }
                translateAll(lines)
            }
            .addOnFailureListener { scheduleNext() }
    }

    private fun translateAll(lines: List<Pair<Rect, String>>) {
        if (lines.isEmpty()) {
            overlay?.setLabels(emptyList())
            scheduleNext()
            return
        }
        val out = ArrayList<Label>()
        var pending = lines.size
        fun done() {
            pending--
            if (pending == 0) {
                if (!paused) overlay?.setLabels(out)
                scheduleNext()
            }
        }
        for ((rect, src) in lines) {
            val cached = cache[src]
            if (cached != null) {
                out.add(Label(rect, cached))
                done()
            } else {
                translator.translate(src)
                    .addOnSuccessListener { t ->
                        cache[src] = t
                        out.add(Label(rect, t))
                        done()
                    }
                    .addOnFailureListener { done() }
            }
        }
    }

    private fun shutdown() {
        if (!running) {
            stopSelf()
            return
        }
        running = false
        handler.removeCallbacksAndMessages(null)
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
        bubble = null
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
