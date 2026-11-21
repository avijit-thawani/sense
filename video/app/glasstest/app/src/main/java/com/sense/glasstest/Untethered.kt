package com.sense.glasstest

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

// Running Sense with no laptop. Ported from the vendor's GlassesAppTemplate (sdk/GlassesAppTemplate.zip).
//
// The glasses refuse to start sideloaded apps at boot (so Boot.kt's BOOT_COMPLETED never arrives) and
// their menu does not list them. An accessibility service is the exception: once switched on over adb
// (app/sense-demo.sh does it), Android reconnects it after every power-on. Ours opens Sense, then keeps
// a small server up so a phone on the same Wi-Fi can bring Sense back after it was closed:
//   curl http://<glasses-ip>:8080/open
//   curl http://<glasses-ip>:8080/ping

private const val TAG = "Sense"
private const val PORT = 8080

private fun openSense(context: Context, reason: String) {
    Log.i(TAG, "untethered: opening Sense ($reason)")
    context.startActivity(Intent(context, Sense::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Reconnected by Android at every boot once enabled. Opens Sense if the glasses have just powered on. */
class OpenService : AccessibilityService() {
    override fun onServiceConnected() {
        Log.i(TAG, "untethered: OpenService connected")
        startForegroundService(Intent(this, KeepAliveService::class.java))
        // Only right after power-on: the service also reconnects after every reinstall from the laptop.
        if (SystemClock.elapsedRealtime() < 5 * 60_000) openSense(this, "boot")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}
}

/** Keeps the app's process alive in the background and answers the Wi-Fi commands. */
class KeepAliveService : Service() {
    private var server: ServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("keepalive", "Background", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(1, Notification.Builder(this, "keepalive")
            .setContentTitle("Sense").setSmallIcon(android.R.drawable.ic_dialog_info).build())
        // The glasses power on with Wi-Fi switched off; Sense needs it to reach the model. The venue network is saved.
        try {
            @Suppress("DEPRECATION")
            val on = (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).setWifiEnabled(true)
            Log.i(TAG, "untethered: Wi-Fi switched on: $on")
        } catch (e: Throwable) { Log.w(TAG, "untethered: Wi-Fi not switched on: $e") }
        Thread({ serve() }, "untethered-server").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { server?.close() } catch (_: IOException) {}
        super.onDestroy()
    }

    /** Only "open" and "ping": anyone on the same Wi-Fi can send these, so nothing here can stop a demo. */
    private fun serve() {
        try {
            val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
            server = s
            Log.i(TAG, "untethered: listening on port $PORT")
            while (true) s.accept().use { client ->
                try {
                    client.soTimeout = 5000
                    val line = client.getInputStream().bufferedReader().readLine() ?: return@use
                    val path = line.split(" ").getOrNull(1)?.substringBefore('?') ?: "/"
                    val (status, body) = when (path) {
                        "/open" -> { openSense(applicationContext, "wifi"); "200 OK" to """{"ok":true}""" }
                        "/", "/ping" -> "200 OK" to """{"ok":true,"app":"$packageName"}"""
                        else -> "404 Not Found" to """{"ok":false}"""
                    }
                    client.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                        flush()
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "untethered: request failed: ${e.message}")
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "untethered: server stopped: ${e.message}")
        }
    }
}
