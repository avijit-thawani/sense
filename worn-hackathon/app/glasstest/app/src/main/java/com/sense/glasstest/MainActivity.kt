package com.sense.glasstest

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One-shot hardware check for the MicroLumin M6 glasses (plain Android 9, no vendor SDK).
 * Runs display, camera, microphone, speaker, text-to-speech and Wi-Fi checks on launch, then
 * keeps logging every key and touch event. Results go to the screen and to logcat tag GlassTest.
 *
 * Pick tests with: adb shell am start -S -n com.sense.glasstest/.MainActivity --es tests camera,mic
 */
class MainActivity : Activity() {
    private lateinit var text: TextView
    private lateinit var scroll: ScrollView
    private val lines = ArrayList<String>()
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Black is see-through on the waveguide, so: black background, bright text.
        text = TextView(this).apply {
            setTextColor(Color.GREEN); setBackgroundColor(Color.BLACK)
            textSize = 16f; gravity = Gravity.START; setPadding(12, 8, 12, 8)
        }
        scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(text) }
        setContentView(scroll)

        // Opened from the "Sound Demo" icon: run only the sound demo, and let a tap replay it.
        soundDemoMode = intent.component?.className?.endsWith("SoundDemo") == true
        val wanted = (intent.getStringExtra("tests") ?: if (soundDemoMode) "spatial" else "display,wifi,tts,camera,mic,speaker").split(",")
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            say("permissions missing: install with adb install -g")
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        }
        Thread {
            for (t in wanted) {
                try {
                    when (t.trim()) {
                        "display" -> testDisplay()
                        "wifi" -> testWifi()
                        "tts" -> testTts()
                        "camera" -> testCamera()
                        "mic" -> testMic()
                        "speaker" -> testSpeaker()
                        "gestures" -> testGestures()
                        "echo" -> testEcho()
                        "loud" -> testLoud()
                        "sensors" -> testSensors()
                        "spatial" -> testSpatial()
                        "join" -> joinWifi()
                        "net" -> testInternet()
                        "lag" -> Lag.run(this) { say(it) }
                    }
                } catch (e: Throwable) {
                    say("$t FAILED: ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(TAG, "test $t", e)
                }
            }
            say("DONE. Press buttons / touchpad / ring now: events are logged.")
        }.start()
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        if (quiet) return
        runOnUiThread {
            lines.add(line)
            text.text = lines.takeLast(40).joinToString("\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    // ---- guided gesture mapping: one prompt at a time on the display, events logged per step ----
    private fun testGestures() {
        val steps = (intent.getStringExtra("steps") ?: "SWIPE FORWARD|SWIPE BACK|SWIPE UP|SWIPE DOWN|TAP once|DOUBLE TAP|HOLD bar 2 sec|CAMERA BUTTON press|CAMERA BUTTON hold 2 sec").split("|")
        // One short prompt, large and centred: the edges of the display are hard to see when worn.
        quiet = true
        runOnUiThread {
            text.textSize = 34f; text.gravity = Gravity.CENTER
            text.minimumHeight = resources.displayMetrics.heightPixels
        }
        big("GESTURES\ndo each one ONCE")
        Thread.sleep(4000)
        for ((i, s) in steps.withIndex()) {
            Log.i(TAG, "STEP ${i + 1}/${steps.size}: $s")
            big("${i + 1} of ${steps.size}\n$s")
            Thread.sleep(6500)
            Log.i(TAG, "END STEP ${i + 1}")
            big("...")
            Thread.sleep(900)
        }
        big("DONE\nthanks")
        Thread.sleep(2500)
        quiet = false
        runOnUiThread { text.textSize = 16f; text.gravity = Gravity.START; text.minimumHeight = 0; lines.clear() }
        say("Gesture mapping finished.")
    }

    /** While true, say() only logs and leaves the display alone. */
    @Volatile private var quiet = false

    private fun big(message: String) = runOnUiThread { text.text = message }

    /** Big message with a smaller line of instructions kept underneath it. */
    private fun bigWithHint(message: String, hint: String) = runOnUiThread {
        val all = android.text.SpannableString("$message\n\n$hint")
        all.setSpan(android.text.style.RelativeSizeSpan(0.55f), message.length, all.length, 0)
        text.text = all
    }

    // ---- motion and ambient sensors: 4 s still, then 8 s while the wearer moves their head ----
    private fun testSensors() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val wanted = listOf(
            android.hardware.Sensor.TYPE_ACCELEROMETER to "accel m/s2",
            android.hardware.Sensor.TYPE_GYROSCOPE to "gyro rad/s",
            android.hardware.Sensor.TYPE_MAGNETIC_FIELD to "compass uT",
            android.hardware.Sensor.TYPE_ROTATION_VECTOR to "rotation",
            android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR to "game rotation",
            android.hardware.Sensor.TYPE_LIGHT to "light lux",
            android.hardware.Sensor.TYPE_PROXIMITY to "proximity",
            android.hardware.Sensor.TYPE_PRESSURE to "pressure hPa",
            android.hardware.Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT to "on-head",
        )
        class Stat { var n = 0; var min = FloatArray(3) { Float.MAX_VALUE }; var max = FloatArray(3) { -Float.MAX_VALUE }; var last = FloatArray(3) }
        for ((phase, secs) in listOf("HOLD STILL" to 4, "NOD, then TURN head left/right, then TILT" to 8)) {
            val stats = HashMap<Int, Stat>()
            val listener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(e: android.hardware.SensorEvent) {
                    val s = stats.getOrPut(e.sensor.type) { Stat() }
                    s.n++
                    for (i in 0 until minOf(3, e.values.size)) {
                        val v = e.values[i]; s.last[i] = v
                        if (v < s.min[i]) s.min[i] = v
                        if (v > s.max[i]) s.max[i] = v
                    }
                }
                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
            }
            val missing = ArrayList<String>()
            for ((type, name) in wanted) {
                val sensor = sm.getDefaultSensor(type)
                if (sensor == null) missing.add(name) else sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
            }
            say("sensors: $phase for ${secs}s" + if (missing.isEmpty()) "" else " (not present: ${missing.joinToString()})")
            Thread.sleep(secs * 1000L)
            sm.unregisterListener(listener)
            for ((type, name) in wanted) {
                val s = stats[type] ?: continue
                val axes = if (type in listOf(android.hardware.Sensor.TYPE_LIGHT, android.hardware.Sensor.TYPE_PROXIMITY, android.hardware.Sensor.TYPE_PRESSURE, android.hardware.Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)) 1 else 3
                val range = (0 until axes).joinToString(" ") { "%.2f..%.2f".format(s.min[it], s.max[it]) }
                say("  $name: ${s.n / secs}/s  range $range")
            }
            val silent = wanted.filter { sm.getDefaultSensor(it.first) != null && stats[it.first] == null }.map { it.second }
            if (silent.isNotEmpty()) say("  no readings from: ${silent.joinToString()}")
        }
    }

    // ---- display ----
    private fun testDisplay() {
        val m = resources.displayMetrics
        say("display: ${m.widthPixels}x${m.heightPixels} dpi=${m.densityDpi} (if you can read this, text works)")
    }

    // ---- wifi ----
    private fun testWifi() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo
        val ip = info.ipAddress
        val ipStr = "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        say("wifi: enabled=${wm.isWifiEnabled} ssid=${info.ssid} ip=$ipStr rssi=${info.rssi}")
    }

    // ---- join a Wi-Fi network (Android 9 still allows apps to add one). Password comes in as an
    //      intent extra at run time and is never logged or stored by this app. ----
    @Suppress("DEPRECATION")
    private fun joinWifi() {
        val ssid = intent.getStringExtra("ssid") ?: return say("join: no ssid given")
        val psk = intent.getStringExtra("psk")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) { wm.isWifiEnabled = true; Thread.sleep(3000) }
        val conf = android.net.wifi.WifiConfiguration().apply {
            SSID = "\"$ssid\""
            if (psk.isNullOrEmpty()) allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
            else preSharedKey = "\"$psk\""
        }
        val id = wm.addNetwork(conf)
        if (id < 0) return say("join: the system refused to add \"$ssid\"")
        wm.disconnect(); wm.enableNetwork(id, true); wm.reconnect()
        say("join: asked to connect to \"$ssid\", waiting...")
        for (i in 0 until 20) {
            Thread.sleep(1000)
            val info = wm.connectionInfo
            if (info.ipAddress != 0 && info.ssid == "\"$ssid\"") break
        }
        testWifi()
    }

    private fun testInternet() {
        for (url in listOf("https://connectivitycheck.gstatic.com/generate_204", "https://openrouter.ai/api/v1/models")) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 6000
                val code = c.responseCode
                val n = try { c.inputStream.use { it.readBytes().size } } catch (e: Exception) { 0 }
                say("net: ${java.net.URL(url).host} -> HTTP $code, $n bytes, ${SystemClock.elapsedRealtime() - t0} ms")
                c.disconnect()
            } catch (e: Exception) {
                say("net: ${java.net.URL(url).host} FAILED after ${SystemClock.elapsedRealtime() - t0} ms: ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    // ---- text to speech ----
    private fun testTts() {
        val latch = CountDownLatch(1)
        var status = -99
        runOnUiThread {
            tts = TextToSpeech(this) { s -> status = s; latch.countDown() }
        }
        val answered = latch.await(5, TimeUnit.SECONDS)
        val engines = tts?.engines?.joinToString { it.name } ?: ""
        say("tts: init=${if (!answered) "no answer in 5s" else if (status == TextToSpeech.SUCCESS) "ok" else "error $status"} engines=[$engines]")
        if (answered && status == TextToSpeech.SUCCESS) {
            tts?.speak("Text to speech works on the glasses.", TextToSpeech.QUEUE_FLUSH, null, "t1")
            Thread.sleep(2500)
        }
    }

    // ---- camera ----
    @SuppressLint("MissingPermission")
    private fun testCamera() {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull() ?: return say("camera: no camera listed")
        val chars = cm.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(ImageFormat.JPEG)
        say("camera: id=$id jpeg sizes=${sizes.sortedByDescending { it.width }.joinToString(" ") { "${it.width}x${it.height}" }}")
        // Two sizes: a small one like sense uses today, and a mid one.
        val targets = (intent.getStringExtra("sizes") ?: "1024,1920").split(",").map { it.trim().toInt() }
        for (target in targets) {
            val size = sizes.minByOrNull { abs(it.width - target) } ?: continue
            cameraRun(cm, id, size)
        }
    }

    @SuppressLint("MissingPermission")
    private fun cameraRun(cm: CameraManager, id: String, size: Size) {
        val thread = HandlerThread("cam").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 3)
        var device: CameraDevice? = null
        try {
            val t0 = SystemClock.elapsedRealtime()
            val opened = CountDownLatch(1)
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; opened.countDown() }
                override fun onDisconnected(d: CameraDevice) { d.close() }
                override fun onError(d: CameraDevice, error: Int) { say("camera: open error $error"); d.close(); opened.countDown() }
            }, handler)
            if (!opened.await(6, TimeUnit.SECONDS) || device == null) return say("camera ${size.width}x${size.height}: did not open")
            val tOpen = SystemClock.elapsedRealtime() - t0

            var session: CameraCaptureSession? = null
            val configured = CountDownLatch(1)
            device!!.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; configured.countDown() }
                override fun onConfigureFailed(s: CameraCaptureSession) { configured.countDown() }
            }, handler)
            if (!configured.await(6, TimeUnit.SECONDS) || session == null) return say("camera ${size.width}x${size.height}: session failed")
            val tSession = SystemClock.elapsedRealtime() - t0

            // Five stills in a row: the first includes exposure settling, later ones show the warm speed.
            val times = ArrayList<Long>()
            var lastBytes = 0
            for (i in 0 until 5) {
                val got = CountDownLatch(1)
                var bytes: ByteArray? = null
                reader.setOnImageAvailableListener({ r ->
                    r.acquireLatestImage()?.use { img ->
                        val buf = img.planes[0].buffer
                        bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                    }
                    got.countDown()
                }, handler)
                val req = device!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.JPEG_QUALITY, 85.toByte())
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                }.build()
                val ts = SystemClock.elapsedRealtime()
                session!!.capture(req, null, handler)
                if (!got.await(8, TimeUnit.SECONDS)) { say("camera: still $i timed out"); break }
                times.add(SystemClock.elapsedRealtime() - ts)
                bytes?.let {
                    lastBytes = it.size
                    File(getExternalFilesDir(null), "cam_${size.width}_$i.jpg").writeBytes(it)
                }
            }
            say("camera ${size.width}x${size.height}: open ${tOpen}ms, ready ${tSession}ms, stills ${times.joinToString("/")}ms, last ${lastBytes / 1024} KB")
        } finally {
            device?.close(); reader.close(); thread.quitSafely()
        }
    }

    // ---- microphone ----
    @SuppressLint("MissingPermission")
    private fun testMic() {
        val rate = 16000
        val seconds = 3
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
        if (rec.state != AudioRecord.STATE_INITIALIZED) return say("mic: could not initialise")
        say("mic: recording ${seconds}s, say something...")
        val pcm = ShortArray(rate * seconds)
        rec.startRecording()
        var n = 0
        while (n < pcm.size) {
            val r = rec.read(pcm, n, pcm.size - n)
            if (r <= 0) break
            n += r
        }
        rec.stop(); rec.release()
        var sum = 0.0; var peak = 0
        for (i in 0 until n) { val v = pcm[i].toInt(); sum += v * v; if (abs(v) > peak) peak = abs(v) }
        val rms = if (n > 0) sqrt(sum / n) else 0.0
        writeWav(File(getExternalFilesDir(null), "mic.wav"), pcm, n, rate)
        lastRecording = pcm.copyOf(n)
        say("mic: got $n samples, rms=${rms.toInt()} peak=$peak of 32767 (${if (peak > 500) "hearing sound" else "silent?"})")
    }

    private var lastRecording: ShortArray? = null

    private fun writeWav(f: File, pcm: ShortArray, n: Int, rate: Int) {
        RandomAccessFile(f, "rw").use { out ->
            out.setLength(0)
            val dataLen = n * 2
            fun i32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun i16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            out.writeBytes("RIFF"); i32(36 + dataLen); out.writeBytes("WAVEfmt "); i32(16); i16(1); i16(1)
            i32(rate); i32(rate * 2); i16(2); i16(16); out.writeBytes("data"); i32(dataLen)
            val bytes = ByteArray(dataLen)
            for (i in 0 until n) { bytes[2 * i] = pcm[i].toByte(); bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
            out.write(bytes)
        }
    }

    // ---- speaker ----
    private fun testSpeaker() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        say("speaker: music volume ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}; playing two beeps, then your recording")
        val rate = 16000
        val beep = ShortArray(rate * 6 / 10) { i ->
            val f = if (i < rate * 3 / 10) 660.0 else 880.0
            (sin(2 * PI * f * i / rate) * 9000).toInt().toShort()
        }
        play(beep, rate)
        lastRecording?.let { play(it, rate) }
        say("speaker: playback finished (did you hear beeps and your own voice?)")
    }

    // Full-volume check: a rising five-note scale, twice, at near-maximum amplitude.
    private fun testLoud() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        say("loud: volume $before -> ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/$max, playing a rising scale twice")
        val rate = 16000
        val notes = listOf(523.0, 659.0, 784.0, 1047.0, 1319.0)
        val per = rate * 35 / 100
        val scale = ShortArray(per * notes.size) { i ->
            val f = notes[i / per]
            val fade = minOf(1.0, (i % per) / 400.0, (per - i % per) / 400.0)
            (sin(2 * PI * f * i / rate) * 28000 * fade).toInt().toShort()
        }
        repeat(2) { play(scale, rate); Thread.sleep(300) }
        say("loud: done (volume left at max)")
    }

    // ---- direction and distance by sound: label on the display while the matching sound plays ----
    // The glasses have two speakers (left, right), so left-right is real stereo. "Behind" cannot be
    // real with two speakers; here it is a made-up cue (a lower, duller tone) to see if it reads well.

    /** angle in degrees (-90 = hard left, +90 = hard right), loudness 0..1, pulses per second. */
    private class Cue(val label: String, val angle: Double, val level: Double, val pps: Double, val behind: Boolean = false)

    private val cues = listOf(
        Cue("LEFT only\n9 o'clock", -90.0, 1.0, 5.0),
        Cue("RIGHT only\n3 o'clock", 90.0, 1.0, 5.0),
        Cue("CENTER\n12 o'clock", 0.0, 1.0, 5.0),
        Cue("10 o'clock", -60.0, 1.0, 5.0),
        Cue("11 o'clock", -30.0, 1.0, 5.0),
        Cue("12 o'clock", 0.0, 1.0, 5.0),
        Cue("1 o'clock", 30.0, 1.0, 5.0),
        Cue("2 o'clock", 60.0, 1.0, 5.0),
        Cue("BEHIND LEFT\n(low tone)", -60.0, 1.0, 5.0, behind = true),
        Cue("BEHIND RIGHT\n(low tone)", 60.0, 1.0, 5.0, behind = true),
        Cue("FAR\nquiet + slow", 0.0, 0.3, 2.0),
        Cue("MIDDLE", 0.0, 0.6, 4.0),
        Cue("NEAR\nloud + fast", 0.0, 1.0, 9.0),
        Cue("NEAR on LEFT", -70.0, 1.0, 9.0),
        Cue("FAR on RIGHT", 70.0, 0.3, 2.0),
    )

    private fun testSpatial() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        quiet = true
        runOnUiThread {
            text.textSize = 34f; text.gravity = Gravity.CENTER
            text.minimumHeight = resources.displayMetrics.heightPixels
        }
        if (soundDemoMode) {
            interactiveSpatial()
        } else {
            big("SOUND DEMO\nwatch + listen"); Thread.sleep(2000)
            for (c in cues) {
                Log.i(TAG, "spatial: ${c.label.replace("\n", " ")} angle=${c.angle} level=${c.level} pps=${c.pps}")
                big(c.label)
                playStereo(cueSound(c, 1.0, (stereoRate * 1.7).toInt()))
                Thread.sleep(500)
            }
        }
        quiet = false
        runOnUiThread { text.textSize = 16f; text.gravity = Gravity.START; text.minimumHeight = 0; lines.clear() }
        say("spatial: finished")
    }

    private var soundDemoMode = false
    @Volatile private var interactive = false
    @Volatile private var nextRequested = false
    @Volatile private var pitchSteps = 0          // each step is two semitones

    /**
     * Hands-on version: the current cue keeps pulsing until you TAP for the next one.
     * SWIPE FORWARD raises the pitch, SWIPE BACK lowers it, while it plays.
     */
    private fun interactiveSpatial() {
        big("SOUND DEMO\ntap = next\nswipe = pitch"); Thread.sleep(2500)
        interactive = true
        @Suppress("DEPRECATION")
        val track = AudioTrack(AudioManager.STREAM_MUSIC, stereoRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            AudioTrack.getMinBufferSize(stereoRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 2, AudioTrack.MODE_STREAM)
        val boost = try {
            android.media.audiofx.LoudnessEnhancer(track.audioSessionId).apply { setTargetGain(900); enabled = true }
        } catch (e: Throwable) { Log.w(TAG, "no loudness boost: $e"); null }
        track.play()
        var i = 0
        try {
            while (!isFinishing) {
                val c = cues[i % cues.size]
                nextRequested = false
                var shownPitch = Int.MIN_VALUE
                Log.i(TAG, "spatial: ${c.label.replace("\n", " ")}")
                while (!nextRequested && !isFinishing) {
                    val steps = pitchSteps
                    if (steps != shownPitch) {
                        shownPitch = steps
                        bigWithHint(
                            "${i % cues.size + 1}/${cues.size}  ${c.label}\npitch ${if (steps > 0) "+" else ""}$steps",
                            "TAP: next sound\nSWIPE forward / back: pitch up / down\nDOUBLE TAP: exit",
                        )
                    }
                    // One pulse period at a time, so a pitch change is heard on the very next pulse.
                    val chunk = cueSound(c, Math.pow(2.0, steps * 2 / 12.0), (stereoRate / c.pps).toInt())
                    track.write(chunk, 0, chunk.size)
                }
                i++
            }
        } finally {
            interactive = false
            boost?.release(); track.release()
        }
    }

    private val stereoRate = 48000

    /** [frames] of interleaved stereo pulses placed at an angle using loudness difference and a small arrival-time difference. */
    private fun cueSound(c: Cue, pitch: Double, frames: Int): ShortArray {
        val pan = (c.angle + 90) / 180 * (PI / 2)
        val gl = kotlin.math.cos(pan); val gr = sin(pan)
        val delay = (0.00065 * abs(sin(c.angle * PI / 180)) * stereoRate).toInt()   // far ear hears it up to 0.65 ms later
        val base = (if (c.behind) 330.0 else 880.0) * pitch
        val period = (stereoRate / c.pps).toInt()
        val on = minOf(period * 55 / 100, stereoRate * 9 / 100)
        val bright = if (c.behind) 0.15 else 0.5      // fewer overtones = duller sound for "behind"
        val mono = DoubleArray(frames) { i ->
            val p = i % period
            if (p >= on) 0.0 else {
                val env = minOf(1.0, p / 200.0, (on - p) / 200.0)
                val t = p.toDouble() / stereoRate
                env * (sin(2 * PI * base * t) + bright * sin(2 * PI * base * 2 * t) + bright * 0.6 * sin(2 * PI * base * 3 * t)) / (1 + bright * 1.6)
            }
        }
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val li = if (c.angle > 0) i - delay else i     // sound from the right reaches the left ear later
            val ri = if (c.angle < 0) i - delay else i
            val l = if (li >= 0) mono[li] else 0.0
            val r = if (ri >= 0) mono[ri] else 0.0
            out[2 * i] = (l * gl * c.level * 30000).toInt().toShort()
            out[2 * i + 1] = (r * gr * c.level * 30000).toInt().toShort()
        }
        return out
    }

    private fun playStereo(pcm: ShortArray) {
        @Suppress("DEPRECATION")
        val track = AudioTrack(AudioManager.STREAM_MUSIC, stereoRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, pcm.size * 2, AudioTrack.MODE_STATIC)
        track.write(pcm, 0, pcm.size)
        // Extra loudness on top of full volume; the system limits it so it should not distort badly.
        val boost = try {
            android.media.audiofx.LoudnessEnhancer(track.audioSessionId).apply { setTargetGain(900); enabled = true }
        } catch (e: Throwable) { Log.w(TAG, "no loudness boost: $e"); null }
        track.play()
        Thread.sleep(pcm.size / 2 * 1000L / stereoRate + 150)
        boost?.release()
        track.release()
    }

    private fun play(pcm: ShortArray, rate: Int) {
        @Suppress("DEPRECATION")
        val track = AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, pcm.size * 2, AudioTrack.MODE_STATIC)
        track.write(pcm, 0, pcm.size)
        track.play()
        Thread.sleep(pcm.size * 1000L / rate + 200)
        track.release()
    }

    // ---- input: log everything ----
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        say("key ${if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"} ${KeyEvent.keyCodeToString(event.keyCode)} (${event.keyCode}) scan=${event.scanCode} dev=${event.device?.name} repeat=${event.repeatCount}")
        if (interactive) {
            // Sound demo controls: tap = next cue, swipe forward / back = pitch up / down. Nothing leaves the app.
            if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER -> nextRequested = true
                KeyEvent.KEYCODE_DPAD_RIGHT -> pitchSteps = minOf(pitchSteps + 1, 6)
                KeyEvent.KEYCODE_DPAD_LEFT -> pitchSteps = maxOf(pitchSteps - 1, -6)
                // A double tap arrives as F12 and then, from the system, BACK. Leave on the BACK so nothing spills into the menu.
                KeyEvent.KEYCODE_BACK -> { Log.i(TAG, "double tap: exit"); finish() }
            }
            return true
        }
        if (echo) {
            // Echo mode: show each press in big text and swallow it, so nothing (not even Back) leaves the app.
            if (event.action == KeyEvent.ACTION_DOWN) {
                echoCount++
                big("#$echoCount\n${KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")}" + if (event.repeatCount > 0) "\n(held)" else "")
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Volatile private var echo = false
    private var echoCount = 0

    /** Shows the name of every key the glasses send, until the app is stopped from the laptop. */
    private fun testEcho() {
        quiet = true; echo = true
        runOnUiThread {
            text.textSize = 34f; text.gravity = Gravity.CENTER
            text.minimumHeight = resources.displayMetrics.heightPixels
        }
        big("ECHO\ntry any gesture")
        Thread.sleep(intent.getIntExtra("seconds", 90) * 1000L)
        echo = false; quiet = false
        runOnUiThread { text.textSize = 16f; text.gravity = Gravity.START; text.minimumHeight = 0; lines.clear() }
        say("Echo finished.")
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action != MotionEvent.ACTION_MOVE) say("touch ${MotionEvent.actionToString(ev.action)} x=${ev.x.toInt()} y=${ev.y.toInt()} dev=${ev.device?.name}")
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        say("motion ${MotionEvent.actionToString(ev.action)} dev=${ev.device?.name}")
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }

    companion object { const val TAG = "GlassTest" }
}
