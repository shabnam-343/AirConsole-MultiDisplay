package com.example.multidisplaypoc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Presentation
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.CopyOnWriteArraySet

internal const val MIRROR_CAPTURE_DISPLAY_NAME = "MultiDisplayMirror"

/** Receives captured frames of the source display. */
interface FrameSink {
    fun onFrame(bitmap: Bitmap, source: Rect)
}

object MirrorFrames {
    val sinks = CopyOnWriteArraySet<FrameSink>()
}

/** View-only window that shows the captured source display on a secondary display. */
class MirrorPresentation(context: Context, display: Display) : Presentation(context, display), FrameSink {
    private lateinit var surfaceView: SurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        surfaceView = SurfaceView(context).apply { holder.setFormat(PixelFormat.RGBA_8888) }
        setContentView(surfaceView)
    }

    override fun onStart() {
        super.onStart()
        MirrorFrames.sinks.add(this)
    }

    override fun onStop() {
        MirrorFrames.sinks.remove(this)
        super.onStop()
    }

    override fun onFrame(bitmap: Bitmap, source: Rect) {
        val holder: SurfaceHolder = surfaceView.holder
        val canvas = holder.surface.takeIf { it.isValid }?.let { holder.lockCanvas() } ?: return
        try {
            canvas.drawBitmap(bitmap, source, Rect(0, 0, canvas.width, canvas.height), null)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }
}

class MirrorService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        stopMirroring()
        startInForeground()

        val manager = getSystemService(MediaProjectionManager::class.java)
        val newProjection = manager.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), data)
        if (newProjection == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val workThread = HandlerThread("mirror").apply { start() }
        val handler = Handler(workThread.looper)
        newProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)

        val metrics = DisplayMetrics()
        getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            .getRealMetrics(metrics)
        val scale = minOf(1f, MAX_SIDE / maxOf(metrics.widthPixels, metrics.heightPixels).toFloat())
        val width = (metrics.widthPixels * scale).toInt()
        val height = (metrics.heightPixels * scale).toInt()

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var frame: Bitmap? = null
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            image.use {
                val plane = it.planes[0]
                val stride = plane.rowStride / plane.pixelStride
                var bitmap = frame
                if (bitmap == null || bitmap.width != stride || bitmap.height != height) {
                    bitmap = Bitmap.createBitmap(stride, height, Bitmap.Config.ARGB_8888)
                    frame = bitmap
                }
                plane.buffer.rewind()
                bitmap.copyPixelsFromBuffer(plane.buffer)
                val area = Rect(0, 0, width, height)
                MirrorFrames.sinks.forEach { sink -> sink.onFrame(bitmap, area) }
            }
        }, handler)

        virtualDisplay = newProjection.createVirtualDisplay(
            MIRROR_CAPTURE_DISPLAY_NAME, width, height, metrics.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
        projection = newProjection
        imageReader = reader
        thread = workThread
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopMirroring()
        super.onDestroy()
    }

    private fun stopMirroring() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        thread?.quitSafely()
        thread = null
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen mirroring", NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Mirroring to other displays")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "mirror"
        private const val MAX_SIDE = 1920f
    }
}
