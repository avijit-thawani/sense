package com.sense.glasstest

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * How old is a live camera frame when it reaches our code? Point the glasses at a running clock:
 * frames stream in for 10 s, each is stamped with the glasses' clock on arrival, and every 12th is
 * saved as lag_<arrival time in ms>.jpg. The clock visible in the picture, minus the arrival time,
 * is the lag. Run: am start -S -n com.sense.glasstest/.MainActivity --es tests lag
 */
object Lag {
    @SuppressLint("MissingPermission")
    fun run(ctx: Activity, say: (String) -> Unit) {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.first()
        val source = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        val thread = HandlerThread("lag").apply { start() }
        val handler = Handler(thread.looper)
        val saver = HandlerThread("lagsave").apply { start() }
        val saveHandler = Handler(saver.looper)
        val reader = ImageReader.newInstance(1280, 960, ImageFormat.YUV_420_888, 4)
        val dir = File(ctx.getExternalFilesDir(null), "lag").apply { deleteRecursively(); mkdirs() }
        val ages = ArrayList<Double>()      // arrival minus the camera's own timestamp, in ms
        val arrivals = ArrayList<Long>()
        var n = 0
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val wall = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtimeNanos()
            n++
            ages.add((elapsed - img.timestamp) / 1e6)
            arrivals.add(wall)
            if (n > 20 && n % 12 == 0) {
                val nv21 = toNv21(img)
                val w = img.width; val h = img.height
                saveHandler.post { File(dir, "lag_$wall.jpg").outputStream().use { YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 90, it) } }
            }
            img.close()
        }, handler)

        var device: CameraDevice? = null
        val opened = CountDownLatch(1)
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { device = d; opened.countDown() }
            override fun onDisconnected(d: CameraDevice) { d.close(); opened.countDown() }
            override fun onError(d: CameraDevice, error: Int) { d.close(); opened.countDown() }
        }, handler)
        opened.await(6, TimeUnit.SECONDS)
        val d = device ?: return say("lag: camera did not open")
        val ready = CountDownLatch(1)
        d.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                s.setRepeatingRequest(d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(reader.surface) }.build(), null, handler)
                ready.countDown()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { ready.countDown() }
        }, handler)
        ready.await(6, TimeUnit.SECONDS)
        say("lag: filming for 10 s, look at the clock")
        Thread.sleep(10000)
        d.close(); thread.quitSafely(); saver.quitSafely()
        if (ages.size < 10) return say("lag: only ${ages.size} frames arrived")
        val sorted = ages.drop(10).sorted()
        val secs = (arrivals.last() - arrivals.first()) / 1000.0
        say("lag: ${ages.size} frames, %.1f a second; camera timestamp source=%s (1 = same clock as ours)".format((ages.size - 1) / secs, source))
        say("lag: frame age on arrival by the camera's own timestamp: median %.0f ms, fastest %.0f, slowest %.0f".format(sorted[sorted.size / 2], sorted.first(), sorted.last()))
    }

    private fun toNv21(img: Image): ByteArray {
        val w = img.width; val h = img.height
        val out = ByteArray(w * h * 3 / 2)
        val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
        val yb = y.buffer
        for (row in 0 until h) { yb.position(row * y.rowStride); yb.get(out, row * w, w) }
        val ub = u.buffer; val vb = v.buffer
        var i = w * h
        for (row in 0 until h / 2) for (col in 0 until w / 2) {
            out[i++] = vb.get(row * v.rowStride + col * v.pixelStride)
            out[i++] = ub.get(row * u.rowStride + col * u.pixelStride)
        }
        return out
    }
}
