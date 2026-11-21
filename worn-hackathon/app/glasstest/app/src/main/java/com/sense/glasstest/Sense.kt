package com.sense.glasstest

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
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
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Minimal sense on the M6 glasses, everything over the glasses' own Wi-Fi.
 * Built for a wearer who cannot see the display: every state has a sound or a spoken line, and
 * nothing needs the display (it mirrors the same information; --ez screen false blanks it).
 *   start: two rising notes, then the spoken instructions      tap heard: one short note
 *   listening: rising pair; stopped listening: falling pair    failed: two low notes + "Failed..."
 *   exit: three falling notes
 *   TAP            listens for 2 s. Silence: describes what is ahead. Speech: the request goes to the
 *                  model with the photo, and the model picks what to do: answer the question,
 *                  read or convey text ("read this slide"), find ("find my phone"), live describe
 *                  ("keep describing"), recall ("what did that sign say earlier"), or settings
 *                  ("volume down", "change voice to Chris", "what are my settings").
 *                  Modes that keep running (find, live) say how to end them when they start.
 *   SWIPE          forward / back: volume up / down
 *   DOUBLE TAP     stop whatever is happening (speech, listening, find); twice within 2.5 s: exit
 *   TRIPLE TAP     spoken instructions
 * After any spoken answer, and after the glasses ask something, the microphone opens by itself for
 * 3 s (soft rising pair) for a follow-up; if nobody speaks it closes (soft falling pair). At rest
 * it never listens without a tap.
 * Find mode: photos run continuously and a beep comes from the side the thing is on, faster as it
 * nears the centre; when it is not in view the voice suggests where to look, and says which side it
 * was last seen on if it is lost. TAP or DOUBLE TAP stops. A soft tick plays while the model thinks.
 *
 * Every run is recorded, for demo clips: the camera records video for the whole run (video_N.mp4
 * in the session folder) and the microphone records everything (audio.pcm, 16 kHz mono).
 * --ez record false turns both off.
 * Leave with two DOUBLE TAPS so the video file is closed properly; a forced stop loses the last one.
 *
 * Keys are never in the APK: sense-demo.sh pushes openrouter_key.txt and elevenlabs_key.txt.
 * Without the ElevenLabs key the answer is only shown, not spoken.
 * Start: adb shell am start -S -n com.sense.glasstest/.Sense   (optional: --es model <id>)
 * Logs: adb logcat -s Sense. Each run writes files/sessions/<start time>/session.jsonl with the
 * photos (and question recordings) next to it, in the layout tools/moment_timeline.py reads.
 */
class Sense : Activity() {
    private lateinit var text: TextView
    private var key = ""
    private var voiceKey = ""
    private var model = "google/gemini-3.1-flash-lite"
    @Volatile private var busy = false          // from press until the answer text is in hand
    @Volatile private var listening = false     // recording a question
    @Volatile private var speechGen = 0         // bumping it cuts off whatever is being spoken
    private var count = 0
    @Volatile private var finding = false       // find mode is running
    @Volatile private var live = false          // live describe is running
    private var voiceName = "Alice"
    private val memory = ArrayList<String>()    // what was asked and said, newest last: the text the "recall" tool answers from
    @Volatile private var thinking = false      // waiting on the model: a soft tick plays
    @Volatile private var lastStarted = false   // the last recording contained speech
    @Volatile private var beepMuted = false     // find mode: quiet while the voice speaks
    @Volatile private var cueX = -1             // find mode: where the thing is, 0 (left) to 100 (right); -1 = not seen
    @Volatile private var cueY = 50             // find mode: 0 (top of view) to 100 (bottom)
    @Volatile private var cueSize = 20          // find mode: share of the view's width it covers, 0 to 100
    @Volatile private var cueBox: IntArray? = null   // find mode: [top, left, bottom, right] in the photo, each 0 to 1000
    private lateinit var boxView: BoxView
    // How the see-through display sits inside the camera's picture: the share of the photo's width
    // (and height) the display covers, and where the display's centre falls in the photo (0 to 1).
    // First guesses, not measured: tune until the box sits on the real thing.
    private var arW = 0.30f
    private var arCx = 0.5f
    private var arCy = 0.5f
    private var cueFromMs = 0L                  // capture time of the photo that cue came from
    private lateinit var sessionDir: File

    private val camThread = HandlerThread("cam").apply { start() }
    private val camHandler = Handler(camThread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var recordRun = true                        // every run is recorded; --ez record false turns it off
    private var liveMin = 5                             // --ei live_min N: live describe ends itself after this long
    private var recorder: android.media.MediaRecorder? = null
    private var recSurface: android.view.Surface? = null
    private var videoCount = 0
    private val sayCount = java.util.concurrent.atomic.AtomicInteger()    // say_NNN.pcm: each spoken line as it was played
    private val beepCount = java.util.concurrent.atomic.AtomicInteger()   // beeps_NN.pcm: each find run's beeps as played
    @Volatile private var micRunning = false
    @Volatile private var questionTap: ByteArrayOutputStream? = null   // while a question is spoken, the run's mic feeds this too

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Black is see-through on the display; the centre is the easiest part to read when worn.
        text = TextView(this).apply {
            setTextColor(Color.GREEN); setBackgroundColor(Color.BLACK)
            textSize = 26f; gravity = Gravity.CENTER; setPadding(40, 20, 40, 20)
        }
        boxView = BoxView(this)
        setContentView(android.widget.FrameLayout(this).apply { setBackgroundColor(Color.BLACK); addView(text); addView(boxView) })
        val prefs = getSharedPreferences("sense", Context.MODE_PRIVATE)
        arW = intent.getFloatExtra("ar_w", prefs.getFloat("ar_w", arW))
        arCx = intent.getFloatExtra("ar_cx", prefs.getFloat("ar_cx", arCx))
        arCy = intent.getFloatExtra("ar_cy", prefs.getFloat("ar_cy", arCy))
        prefs.edit().putFloat("ar_w", arW).putFloat("ar_cx", arCx).putFloat("ar_cy", arCy).apply()
        intent.getStringExtra("model")?.let { model = it }
        recordRun = intent.getBooleanExtra("record", true)
        liveMin = intent.getIntExtra("live_min", liveMin)
        if (!intent.getBooleanExtra("screen", true)) { text.visibility = android.view.View.INVISIBLE; boxView.visibility = android.view.View.INVISIBLE }
        key = loadKey("openrouter_key.txt")
        voiceName = prefs.getString("voice", voiceName)!!.takeIf { it in VOICES } ?: "Alice"
        voiceKey = loadKey("elevenlabs_key.txt")
        sessionDir = File(getExternalFilesDir(null), "sessions/" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
        sessionDir.mkdirs()
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, prefs.getInt("volume", maxVol).coerceIn(3, maxVol), 0)
        Thread { hum() }.start()
        log(JSONObject().put("event", "start").put("at", now()).put("model", model).put("voice", voiceKey.isNotEmpty())
            .put("utc_offset_min", java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000))   // log times are the glasses' local time
        Thread {
            if (key.isEmpty()) { tone(220, 180); return@Thread }
            tone(660, 880)
            if (voiceKey.isEmpty()) return@Thread
            val gen = speechGen
            for (p in PROMPTS) if (!voiceFile(p).exists()) try { fetchVoice(p).use { it.readBytes() }.let { saveVoice(p, it) } } catch (e: Throwable) { Log.w(TAG, "prompt not fetched: $e") }
            if (speechGen == gen) speak(P_READY, "", System.currentTimeMillis(), keep = true)   // short; the full instructions are on triple tap
        }.start()
        if (key.isEmpty()) show("NO API KEY", "run app/sense-demo.sh from the laptop")
        else show(IDLE, IDLE_HINT + if (voiceKey.isEmpty()) "\n(no voice key: text only)" else "")
        if (recordRun) Thread { micLoop() }.start()
        Thread { batteryLoop() }.start()
        Thread { loadMemory() }.start()
        Thread { try { openCamera() } catch (e: Throwable) { Log.e(TAG, "camera open", e) } }.start()
    }

    /** Every 30 s: battery level, current and temperature, with what the app was doing, to size the drain per mode. */
    private fun batteryLoop() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        while (!isFinishing && !isDestroyed) {
            try {
                val i = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                log(JSONObject().put("event", "battery").put("at", now())
                    .put("level", i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1))
                    .put("plugged", (i?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
                    .put("temp_c", (i?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0)
                    .put("mv", i?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0))
                    .put("current_ua", bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
                    .put("charge_uah", bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
                    .put("doing", if (live) "live" else if (finding) "find" else if (busy) "busy" else "idle").put("video", recorder != null)
                    .put("screen", text.visibility == android.view.View.VISIBLE))
            } catch (e: Throwable) { Log.w(TAG, "battery: $e") }
            Thread.sleep(30000)
        }
    }

    /** Recording runs only: one microphone stream for the whole run, saved raw; questions are cut from the same stream. */
    @SuppressLint("MissingPermission")
    private fun micLoop() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { log(JSONObject().put("event", "audio_error").put("error", "microphone did not start")); return }
        val buf = ByteArray(3200)   // 0.1 s
        try {
            File(sessionDir, "audio.pcm").outputStream().use { out ->
                rec.startRecording()
                micRunning = true
                var first = true
                while (micRunning) {
                    val r = rec.read(buf, 0, buf.size)
                    if (r <= 0) break
                    if (first) { first = false; log(JSONObject().put("event", "audio_start").put("at", at(System.currentTimeMillis() - r * 1000L / (RATE * 2))).put("file", "audio.pcm")) }
                    out.write(buf, 0, r)
                    questionTap?.let { synchronized(it) { it.write(buf, 0, r) } }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "mic loop", e)
        } finally {
            micRunning = false
            try { rec.stop() } catch (_: Throwable) {}
            rec.release()
        }
    }

    /** Private storage first. A key pushed to the external folder (fallback) is moved in and deleted. */
    private fun loadKey(name: String): String {
        val private = File(filesDir, name)
        val pushed = File(getExternalFilesDir(null), name)
        if (pushed.exists()) { private.writeText(pushed.readText().trim()); pushed.delete() }
        return if (private.exists()) private.readText().trim() else ""
    }

    private fun show(message: String, hint: String = "") = runOnUiThread {
        val all = SpannableString(if (hint.isEmpty()) message else "$message\n\n$hint")
        if (hint.isNotEmpty()) all.setSpan(RelativeSizeSpan(0.55f), message.length, all.length, 0)
        text.text = all
    }

    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    @Synchronized private fun at(ms: Long) = stamp.format(Date(ms))
    private fun now() = at(System.currentTimeMillis())

    @Synchronized
    private fun log(row: JSONObject) {
        Log.i(TAG, row.toString())
        File(sessionDir, "session.jsonl").appendText(row.toString() + "\n")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)}")
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER -> when {
                    // A triple tap arrives as a double tap and then, about half a second later, one more tap.
                    System.currentTimeMillis() - lastDoubleMs < 800 -> { lastDoubleMs = 0; help() }
                    listening -> listening = false
                    finding -> stopFind()
                    live -> stopLive()
                    else -> moment()
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> volume(1)
                KeyEvent.KEYCODE_DPAD_LEFT -> volume(-1)
                // A double tap arrives as F12 and then, from the system, BACK.
                KeyEvent.KEYCODE_BACK -> {
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastDoubleMs < 2500) {              // double tap twice: leave
                        speechGen++; Thread { tone(880, 660, 440) }.start(); finish()
                    } else {                                        // double tap: stop whatever is going on
                        lastDoubleMs = nowMs
                        cancelledPress = pressGen
                        auditioning = null
                        speechGen++
                        if (finding) stopFind() else if (live) stopLive() else { listening = false; Thread { tone(990, 660) }.start() }
                        log(JSONObject().put("event", "stop").put("at", at(nowMs)))
                    }
                }
            }
        }
        return true   // nothing spills into the glasses' menu
    }

    @Volatile private var lastDoubleMs = 0L
    @Volatile private var pressGen = 0          // counts describe / ask / find presses
    @Volatile private var cancelledPress = -1   // the press a double tap cancelled: its answer is dropped

    /** Triple tap: the spoken instructions (stored on the glasses, so no network needed after the first time). */
    private fun help() {
        speechGen++
        log(JSONObject().put("event", "help").put("at", now()))
        Thread { speak(P_HELP, "", System.currentTimeMillis(), keep = true) }.start()
    }

    /** Swipes: one volume step, confirmed by a note at the new level (two notes at either end of the range). */
    private fun volume(step: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val v = (before + step).coerceIn(3, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        getSharedPreferences("sense", Context.MODE_PRIVATE).edit().putInt("volume", v).apply()
        log(JSONObject().put("event", "volume").put("at", now()).put("volume", v).put("max", max))
        val f = if (step > 0) 880 else 660
        Thread { if (v == before) tone(f, f) else tone(f) }.start()
        if (!finding && !busy) show("Volume $v of $max")
    }

    /** A soft tick twice a second while the model is thinking, so silence never means "did it hear me?". */
    private fun hum() {
        val period = RATE * 45 / 100
        val on = RATE * 6 / 100
        val pcm = ShortArray(period) { i ->
            if (i >= on) 0 else (minOf(1.0, i / 150.0, (on - i) / 150.0) * Math.sin(2 * Math.PI * 330 * i / RATE) * 6000).toInt().toShort()
        }
        @Suppress("DEPRECATION")
        val track = AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), AudioTrack.MODE_STREAM)
        track.play()
        try {
            var ticking = false   // logged, so a demo video can put the ticks back (app/screen_demo.py)
            while (!isDestroyed) {
                if (thinking) {
                    if (!ticking) { ticking = true; log(JSONObject().put("event", "tick_start").put("at", now())) }
                    track.write(pcm, 0, pcm.size)
                } else {
                    if (ticking) { ticking = false; log(JSONObject().put("event", "tick_stop").put("at", now())) }
                    Thread.sleep(40)
                }
            }
        } finally { try { track.release() } catch (_: Throwable) {} }
    }

    /**
     * One tap. The photo is taken and a plain description requested at once, while the microphone
     * listens for 2 s. If the wearer says nothing, the description is spoken (no time lost). If they
     * speak, the description is dropped and their words go to the model with the photo; the model
     * replies with what to do: answer, describe, read, or find.
     *
     * With [followUp], the glasses have just offered choices (settings, voices) and open the microphone
     * themselves, no tap needed: silence then means "nothing", not "describe", and the model is told
     * what was offered so that a bare word ("Will", "louder") makes sense.
     */
    private fun moment(followUp: String? = null) {
        if (busy || finding || live || key.isEmpty()) return
        busy = true
        speechGen++                      // a new press cuts off the previous answer
        val pressMs = System.currentTimeMillis()
        val n = ++count
        val press = ++pressGen
        val photoName = "%03d.jpg".format(n)
        val picking = auditioning            // set if this tap cut into a voice introducing itself
        auditioning = null
        val context = followUp ?: if (picking != null || System.currentTimeMillis() < voiceChoiceUntil) VOICE_CONTEXT else null
        listening = true
        show("Listening...", if (picking != null) "stay quiet to choose $picking" else "speak, or stay quiet for a description")
        Thread {
            if (followUp != null) tone(660, 990, soft = true)      // soft rising pair: the microphone is open, "your turn"
            else Thread { tone(880) }.start()         // heard at once, so the wearer knows the tap registered
            val row = JSONObject().put("n", n).put("press_at", at(pressMs)).put("photo", photoName).put("model", model)
            val marks = JSONObject()
            try {
                var jpeg: ByteArray? = null
                var photoError: Throwable? = null
                val photoThread = Thread {
                    try {
                        synchronized(marks) { marks.put("photo_requested", now()) }
                        jpeg = photo()
                        synchronized(marks) { marks.put("photo_in_hand", now()) }
                        File(sessionDir, photoName).writeBytes(jpeg!!)
                    } catch (e: Throwable) { photoError = e }
                }.apply { start() }
                var plain: Reply? = null
                var plainError: Throwable? = null
                var plainS = 0.0
                var plainAt = 0L
                var plainSent = ""
                val plainThread = Thread {
                    photoThread.join()
                    if (followUp != null) return@Thread          // a follow-up never falls back to describing
                    val j = jpeg ?: return@Thread
                    try {
                        plainSent = now()
                        val t0 = System.nanoTime()
                        plain = askModel(j, null)
                        plainS = (System.nanoTime() - t0) / 1e9
                        plainAt = System.currentTimeMillis()
                    } catch (e: Throwable) { plainError = e }
                }.apply { start() }

                val recStart = System.currentTimeMillis()
                val wav = record(if (followUp != null) 30 else 20)      // a follow-up window stays open 3 s, a tap's 2 s
                val releaseMs = System.currentTimeMillis()
                if (cancelledPress == press) { photoThread.join(); log(row.put("cancelled", true)); show("Stopped"); return@Thread }
                val spoke = lastStarted
                if (!spoke && followUp != null) {      // nobody spoke: a soft falling pair says the microphone has closed
                    log(row.put("mode", "follow_up").put("ended_by", lastEnd)); tone(880, 660, soft = true); return@Thread
                }
                if (!spoke && picking != null) {
                    chooseVoice(picking)
                    log(JSONObject().put("event", "setting").put("at", now()).put("setting", "voice").put("value", picking).put("by", "tap"))
                    show("Voice: $picking")
                    busy = false
                    speak("Hi, I'm $picking. I'm your voice now.", "", pressMs)
                    return@Thread
                }
                thinking = true
                val reply: Reply
                val modelS: Double
                val answeredMs: Long
                var action = "describe"
                var heard = ""
                var target = ""
                var setting = ""
                var value = ""
                var say: String
                if (spoke) {
                    Thread { tone(990, 660) }.start()
                    val wavName = photoName.replace(".jpg", ".wav")
                    File(sessionDir, wavName).writeBytes(wav)
                    // No separate speech-to-text step: the model hears the recording itself.
                    row.put("release_at", at(releaseMs)).put("stt_at", at(releaseMs))
                        .put("question_audio", wavName).put("question_audio_start_at", at(recStart)).put("mic", "glasses")
                    show("Thinking...")
                    photoThread.join()
                    photoError?.let { throw it }
                    marks.put("request_sent", now())
                    val t0 = System.nanoTime()
                    reply = chat(JSONArray().put(JSONObject().put("type", "text").put("text", ROUTE_PROMPT + (if (context != null) " Context: $context" else ""))).put(imagePart(jpeg!!)).put(audioPart(wav)), 250)
                    modelS = (System.nanoTime() - t0) / 1e9
                    answeredMs = System.currentTimeMillis()
                    val j = try { JSONObject(reply.text.substring(reply.text.indexOf('{'), reply.text.lastIndexOf('}') + 1)) } catch (e: Throwable) { null }
                    heard = j?.optString("heard").orEmpty()
                    action = j?.optString("action", "answer") ?: "answer"
                    target = j?.optString("target").orEmpty().trim().take(40)
                    setting = j?.optString("setting").orEmpty().trim().lowercase()
                    value = j?.optString("value").orEmpty().trim()
                    say = (j?.optString("say") ?: reply.text).trim()
                } else {
                    plainThread.join()
                    photoError?.let { throw it }
                    plainError?.let { throw it }
                    reply = plain!!; modelS = plainS; answeredMs = plainAt; say = reply.text
                    marks.put("request_sent", plainSent)
                }
                marks.put("answer_received", at(answeredMs))
                if (cancelledPress == press) { log(row.put("marks", marks).put("cancelled", true)); show("Stopped"); return@Thread }
                val cameraS = (stamp.parse(marks.getString("photo_in_hand"))!!.time - stamp.parse(marks.getString("photo_requested"))!!.time) / 1000.0
                val fromMs = if (spoke) releaseMs else pressMs
                row.put("mode", if (spoke) "ask" else "describe").put("action", action).put("ended_by", lastEnd)
                    .put("marks", marks).put("answered_at", at(answeredMs)).put("captured_at", marks.getString("photo_in_hand"))
                    .put("camera_s", cameraS).put("model_s", modelS).put("total_s", (answeredMs - pressMs) / 1000.0)
                    .put("question", heard).put("target", target).put("text", say).put("photo_kb", jpeg!!.size / 1024)
                    .put("tokens_in", reply.tokensIn).put("tokens_out", reply.tokensOut).put("cost_usd", reply.cost)
                    .put("sent_kb", reply.kb).put("upload_s", reply.uploadS).put("wait_s", reply.waitS).put("read_s", reply.readS)
                    .put("device", "m6-glasses")
                log(row)
                thinking = false
                busy = false
                when {
                    action == "find" && target.isNotEmpty() -> { remember("asked to find $target"); startFind(target); return@Thread }
                    action == "live" -> { startLive(); return@Thread }
                    // "Stop", "never mind", "cancel": the wearer wants nothing done. One soft note, no speech, no description.
                    action == "none" -> { show("OK", "\"$heard\""); tone(660); return@Thread }
                    action == "settings" -> say = applySetting(setting, value)
                    action == "recall" -> {
                        thinking = true
                        say = try { recall(heard) } finally { thinking = false }
                        log(JSONObject().put("event", "recall").put("at", now()).put("question", heard).put("text", say))
                    }
                    else -> remember((if (heard.isNotEmpty()) "asked \"$heard\": " else "saw: ") + say)
                }
                show(say, (if (heard.isNotEmpty()) "\"$heard\" ($action)\n" else "") +
                    "photo %.1f s, model %.1f s, answer %.1f s after %s".format(cameraS, modelS, (answeredMs - fromMs) / 1000.0, if (spoke) "you stopped" else "the tap"))
                val gen = speechGen
                if (voiceKey.isNotEmpty() && say.isNotEmpty()) speak(say, photoName, pressMs)
                // The microphone opens by itself (rising pair) only after a settings reply, where the glasses have
                // just offered choices. After an ordinary answer it stays closed: a follow-up needs a tap. (It used
                // to open after every answer; in a noisy room that was confusing. Removed Sat 1:26 PM.)
                if (action == "settings" && say.isNotEmpty() && speechGen == gen && pressGen == press && !finding && !live && auditioning == null) {
                    busy = false
                    moment(SETTINGS_CONTEXT)
                }
            } catch (e: Throwable) {
                thinking = false
                Log.e(TAG, "moment", e)
                log(row.put("marks", marks).put("error", "${e.javaClass.simpleName}: ${e.message}".take(300)))
                show("Failed", "${e.javaClass.simpleName}: ${e.message}".take(160))
                failed(e)
            } finally {
                if (pressGen == press) { busy = false; listening = false; thinking = false }   // unless a newer press has taken over
            }
        }.start()
    }

    // ---- camera: opened once and kept open (about 0.25 s per photo at 1280x960) ----

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun openCamera() {
        if (session != null) return
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull() ?: error("no camera")
        val sizes = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.JPEG)
        val size = sizes.minByOrNull { abs(it.width - 1280) }!!
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        var rec: android.media.MediaRecorder? = null
        var videoName = ""
        if (recordRun) try {
            // Same shape as the photos (4:3) when the camera offers it, so the video shows what the model saw.
            val vs = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(android.media.MediaRecorder::class.java)
            val v = vs.firstOrNull { it.width == 1280 && it.height == 960 } ?: vs.firstOrNull { it.width == 1280 && it.height == 720 }
                ?: vs.minByOrNull { abs(it.width - 1280) }!!
            videoName = "video_%d.mp4".format(++videoCount)
            rec = android.media.MediaRecorder().apply {
                setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(File(sessionDir, videoName).absolutePath)
                setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(5_000_000)
                setVideoFrameRate(25)
                setVideoSize(v.width, v.height)
                prepare()
            }
            Log.i(TAG, "video ${v.width}x${v.height}")
        } catch (e: Throwable) {
            Log.e(TAG, "video setup", e)
            log(JSONObject().put("event", "video_error").put("error", "${e.javaClass.simpleName}: ${e.message}".take(200)))
            try { rec?.release() } catch (_: Throwable) {}
            rec = null
        }
        val surfaces = if (rec != null) listOf(r.surface, rec.surface) else listOf(r.surface)

        val opened = CountDownLatch(1)
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { device = d; opened.countDown() }
            // The camera button also opens the system camera, which takes ours away: reopen on the next tap.
            override fun onDisconnected(d: CameraDevice) { d.close(); device = null; session = null; opened.countDown() }
            override fun onError(d: CameraDevice, error: Int) { d.close(); device = null; session = null; opened.countDown() }
        }, camHandler)
        opened.await(6, TimeUnit.SECONDS)
        val d = device ?: error("camera did not open")

        val configured = CountDownLatch(1)
        d.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { session = s; configured.countDown() }
            override fun onConfigureFailed(s: CameraCaptureSession) { configured.countDown() }
        }, camHandler)
        configured.await(6, TimeUnit.SECONDS)
        if (session == null) {
            try { rec?.release() } catch (_: Throwable) {}
            if (rec != null) { recordRun = false; log(JSONObject().put("event", "video_error").put("error", "camera refused photo + video together; video off")) }
            d.close(); device = null
            error("camera session failed")
        }
        reader = r
        if (rec != null) {
            recSurface = rec.surface
            session!!.setRepeatingRequest(d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(rec.surface) }.build(), null, camHandler)
            rec.start()
            recorder = rec
            log(JSONObject().put("event", "video_start").put("at", now()).put("file", videoName))
        }
        Log.i(TAG, "camera ready at ${size.width}x${size.height}")
    }

    @Synchronized
    private fun photo(): ByteArray {
        openCamera()
        val d = device ?: error("camera not open")
        val s = session ?: error("camera not ready")
        val r = reader!!
        val got = CountDownLatch(1)
        var bytes: ByteArray? = null
        r.setOnImageAvailableListener({ ir ->
            ir.acquireLatestImage()?.use { img ->
                val buf = img.planes[0].buffer
                bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            }
            got.countDown()
        }, camHandler)
        val snap = if (recSurface == null) 2 else intent.getIntExtra("snap", 1)
        val req = d.createCaptureRequest(if (snap % 2 == 1) CameraDevice.TEMPLATE_VIDEO_SNAPSHOT else CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(r.surface)
            if (snap < 2) recSurface?.let { addTarget(it) }      // keep the video running through the photo
            set(CaptureRequest.JPEG_QUALITY, 85.toByte())
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }.build()
        s.capture(req, null, camHandler)
        if (!got.await(8, TimeUnit.SECONDS)) error("photo timed out")
        return bytes ?: error("empty photo")
    }

    // ---- microphone: records until the next tap (or 12 s), returns a 16 kHz mono WAV ----

    @SuppressLint("MissingPermission")
    private fun record(nothingChunks: Int = 50): ByteArray {
        if (micRunning) {
            val tap = ByteArrayOutputStream()
            questionTap = tap
            val ender = Ender(nothingChunks, ambient)
            var fed = 0
            while (listening && micRunning && tap.size() < RATE * 2 * 9) {
                Thread.sleep(20)
                val all = synchronized(tap) { tap.toByteArray() }
                while (all.size - fed >= 3200) { if (ender.feed(all, fed, 3200)) listening = false; fed += 3200 }
            }
            lastEnd = if (ender.done) ender.why else if (tap.size() >= RATE * 2 * 9) "limit" else "tap"
            lastStarted = ender.started
            ambient = ender.ambientOut
            listening = false
            questionTap = null
            return wav(synchronized(tap) { tap.toByteArray() })
        }
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
        if (rec.state != AudioRecord.STATE_INITIALIZED) error("microphone did not start")
        val pcm = ByteArrayOutputStream()
        val buf = ByteArray(3200)   // 0.1 s
        val ender = Ender(nothingChunks, ambient)
        rec.startRecording()
        try {
            while (listening && pcm.size() < RATE * 2 * 9) {
                val r = rec.read(buf, 0, buf.size)
                if (r <= 0) break
                pcm.write(buf, 0, r)
                if (ender.feed(buf, 0, r)) break
            }
            lastEnd = if (ender.done) ender.why else if (pcm.size() >= RATE * 2 * 9) "limit" else "tap"
            lastStarted = ender.started
            ambient = ender.ambientOut
        } finally {
            listening = false
            rec.stop(); rec.release()
        }
        return wav(pcm.toByteArray())
    }

    @Volatile private var lastEnd = ""      // how the last recording ended: quiet, nothing, tap, limit

    @Volatile private var ambient = 0.0     // the room's level outside speech, carried from one recording to the next

    /**
     * Decides whether the wearer spoke and when they finished, from loudness alone, on the glasses.
     * The room's own level is near the quietest of what has been heard (and what the last recording
     * found), so a loud room raises the bar instead of hiding speech. Speech is two 0.1 s chunks in a
     * row well above that; it has ended when 7 of the last 9 chunks are back down.
     * It cannot tell the wearer from someone next to them.
     */
    private class Ender(private val nothingChunks: Int, private val ambientIn: Double) {
        var done = false; var why = ""
        var started = false
        var ambientOut = ambientIn
        private val levels = ArrayList<Double>()
        private val flags = ArrayList<Boolean>()
        private var base = 0.0
        private var chunks = 0
        private var onset = 0
        fun feed(b: ByteArray, off: Int, n: Int): Boolean {
            var sum = 0.0
            var i = off
            while (i + 1 < off + n) { val v = (b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8); sum += v.toDouble() * v; i += 2 }
            val rms = Math.sqrt(sum / (n / 2))
            chunks++
            if (chunks <= 2) return false                    // the tap note is still in the microphone
            levels.add(rms)
            if (!started) {
                val low = levels.sorted()[levels.size / 6]          // near the quietest heard so far
                base = if (ambientIn > 0) minOf(low, ambientIn * 1.15) else low
            }
            val speech = rms > maxOf(base * 1.7, 700.0)
            flags.add(speech)
            if (!started && flags.size >= 2 && speech && flags[flags.size - 2]) { started = true; onset = flags.size }
            if (started && flags.size - onset >= 8 && flags.takeLast(9).count { !it } >= 7) { done = true; why = "quiet" }
            else if (!started && chunks >= nothingChunks) { done = true; why = "nothing" }      // nobody spoke
            if (done) {
                val calm = levels.filterIndexed { k, _ -> !flags[k] }.sorted()
                if (calm.size >= 4) ambientOut = calm[calm.size / 6]
            }
            return done
        }
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val n = pcm.size
        val out = ByteArrayOutputStream(44 + n)
        fun i32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun i16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); i32(36 + n); out.write("WAVEfmt ".toByteArray()); i32(16); i16(1); i16(1)
        i32(RATE); i32(RATE * 2); i16(2); i16(16); out.write("data".toByteArray()); i32(n)
        out.write(pcm)
        return out.toByteArray()
    }

    // ---- model: same request as the phone app's Brain.describe; a question goes along as audio ----

    private class Reply(val text: String, val tokensIn: Int, val tokensOut: Int, val cost: Double,
                        val uploadS: Double = 0.0, val waitS: Double = 0.0, val readS: Double = 0.0, val kb: Int = 0)

    private fun askModel(jpeg: ByteArray, questionWav: ByteArray?): Reply {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", if (questionWav == null) PROMPT else ASK_PROMPT))
            .put(imagePart(jpeg))
        if (questionWav != null) content.put(audioPart(questionWav))
        return chat(content, if (questionWav == null) 80 else 120)
    }

    private fun imagePart(jpeg: ByteArray) = JSONObject().put("type", "image_url").put("image_url",
        JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)))

    private fun audioPart(wav: ByteArray) = JSONObject().put("type", "input_audio").put("input_audio",
        JSONObject().put("data", Base64.encodeToString(wav, Base64.NO_WRAP)).put("format", "wav"))

    private fun chat(content: JSONArray, maxTokens: Int): Reply {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("provider", JSONObject().put("sort", "latency"))
            .put("reasoning", JSONObject().put("effort", "none"))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val conn = URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $key")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-Title", "sense")
        conn.setRequestProperty("HTTP-Referer", "https://localhost/sense-glasses")
        // Sent as it is written (not buffered), so the three parts of the wait can be timed apart:
        // sending the request, waiting for the first byte back, reading the answer.
        val bytes = body.toString().toByteArray()
        conn.setFixedLengthStreamingMode(bytes.size)
        val t0 = System.nanoTime()
        conn.outputStream.use { it.write(bytes) }
        val t1 = System.nanoTime()
        val code = conn.responseCode
        val t2 = System.nanoTime()
        val reply = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
        val t3 = System.nanoTime()
        if (code !in 200..299) error("HTTP $code: ${reply.take(120)}")
        val json = JSONObject(reply)
        val usage = json.optJSONObject("usage")
        return Reply(json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim().ifEmpty { "(no answer)" },
            usage?.optInt("prompt_tokens") ?: 0, usage?.optInt("completion_tokens") ?: 0, usage?.optDouble("cost", 0.0) ?: 0.0,
            (t1 - t0) / 1e9, (t2 - t1) / 1e9, (t3 - t2) / 1e9, bytes.size / 1024)
    }

    // ---- memory, settings, live describe: the other tools the model can pick ----

    @Synchronized
    private fun remember(line: String) {
        memory.add(SimpleDateFormat("HH:mm", Locale.US).format(Date()) + " " + line.take(200))
        while (memory.size > 60) memory.removeAt(0)
    }

    /** Earlier sessions' answers, so "what did it say this morning" has something to draw on. Called once at start. */
    private fun loadMemory() {
        try {
            val rows = ArrayList<String>()
            sessionDir.parentFile?.listFiles()?.sortedBy { it.name }?.takeLast(6)?.forEach { dir ->
                val f = File(dir, "session.jsonl")
                if (dir != sessionDir && f.exists()) f.forEachLine { l ->
                    try {
                        val r = JSONObject(l)
                        val t = r.optString("text")
                        if (t.isNotEmpty() && r.has("press_at") && r.optString("mode") != "find")
                            rows.add(r.getString("press_at").substring(11, 16) + " " + (r.optString("question").let { if (it.isEmpty()) "saw: " else "asked \"$it\": " }) + t.take(200))
                    } catch (_: Throwable) {}
                }
            }
            synchronized(this) { memory.addAll(0, rows.takeLast(40)) }
        } catch (e: Throwable) { Log.w(TAG, "memory: $e") }
    }

    /** Answers a question about earlier from the text notes alone (no photo): a second, small model call. */
    private fun recall(question: String): String {
        val notes = synchronized(this) { memory.joinToString("\n") }
        if (notes.isEmpty()) return "Nothing remembered yet."
        return chat(JSONArray().put(JSONObject().put("type", "text").put("text", RECALL_PROMPT.replace("{notes}", notes).replace("{question}", question))), 80).text
    }

    /** Spoken settings: volume, voice, or a read-out of what is set. Returns the line to speak. */
    private fun applySetting(setting: String, value: String): String {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val prefs = getSharedPreferences("sense", Context.MODE_PRIVATE)
        val line = when (setting) {
            "volume" -> {
                val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val v = (value.toIntOrNull() ?: when { value.startsWith("up") || value.contains("loud") -> cur + 3; value.startsWith("down") || value.contains("quiet") -> cur - 3
                    value.contains("max") -> max; else -> cur }).coerceIn(3, max)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                prefs.edit().putInt("volume", v).apply()
                "Volume $v of $max."
            }
            "voice" -> {
                val name = VOICES.keys.firstOrNull { it.equals(value, ignoreCase = true) }
                if (name == null) { audition(); "" }       // no name given: let them hear each one
                else { chooseVoice(name); "Hi, I'm $name. I'm your voice now." }
            }
            else -> "Volume ${am.getStreamVolume(AudioManager.STREAM_MUSIC)} of $max. Voice $voiceName. You can say volume up, volume down, or change voice."
        }
        log(JSONObject().put("event", "setting").put("at", now()).put("setting", setting).put("value", value).put("said", line))
        return line
    }

    private fun chooseVoice(name: String) {
        voiceName = name
        auditioning = null
        voiceChoiceUntil = 0L
        getSharedPreferences("sense", Context.MODE_PRIVATE).edit().putString("voice", name).apply()
    }

    @Volatile private var auditioning: String? = null   // the voice introducing itself right now
    @Volatile private var voiceChoiceUntil = 0L         // until then, a spoken name is taken as a voice choice

    /**
     * Each voice introduces itself in turn. A tap while one is speaking chooses it (tap and stay quiet),
     * or tap and say "choose Chris". Double tap leaves the voice as it was. Nothing changes unless chosen.
     */
    private fun audition() {
        Thread {
            val gen = speechGen
            log(JSONObject().put("event", "audition").put("at", now()))
            voiceChoiceUntil = System.currentTimeMillis() + 60_000
            speak(P_AUDITION, "", System.currentTimeMillis(), keep = true)
            for (name in VOICES.keys) {
                if (speechGen != gen) return@Thread          // a tap or double tap cut in
                auditioning = name
                show("Hi, I'm $name", "TAP: choose   DOUBLE TAP: keep $voiceName")
                speak("Hi, I'm $name.", "", System.currentTimeMillis(), keep = true, voice = name)
                if (speechGen != gen) return@Thread          // leave `auditioning` set: the tap that cut in may be choosing it
                Thread.sleep(500)
            }
            auditioning = null
            // All heard and none tapped: ask, and listen for a name without needing a tap.
            if (speechGen == gen) { speak(P_WHICH, "", System.currentTimeMillis(), keep = true); if (speechGen == gen && !busy) moment(VOICE_CONTEXT) }
        }.start()
    }

    /**
     * Live describe: photo, model, speak, repeat. The model is told what was just said and answers
     * only with what is new, or SILENT. It says how to stop when it starts, and ends itself after 5 minutes
     * (liveMin: longer for a battery run).
     */
    private fun startLive() {
        val startMs = System.currentTimeMillis()
        live = true
        log(JSONObject().put("event", "live_start").put("at", at(startMs)))
        show("LIVE", "TAP: stop")
        Thread {
            val said = ArrayList<String>()
            try {
                speak(P_LIVE_ON, "", startMs, keep = true)
                while (live && System.currentTimeMillis() - startMs < liveMin * 60_000L) {
                    val n = synchronized(this) { ++count }
                    val photoName = "%03d.jpg".format(n)
                    val capMs = System.currentTimeMillis()
                    try {
                        val jpeg = photo()
                        File(sessionDir, photoName).writeBytes(jpeg)
                        val t0 = System.nanoTime()
                        val reply = chat(JSONArray().put(JSONObject().put("type", "text").put("text",
                            LIVE_PROMPT.replace("{said}", if (said.isEmpty()) "(nothing yet)" else said.takeLast(6).joinToString(" | ")))).put(imagePart(jpeg)), 50)
                        val line = reply.text.trim()
                        val quiet = line.trim(' ', '.', '"').equals("SILENT", ignoreCase = true) || line.isEmpty()
                        log(JSONObject().put("mode", "live").put("n", n).put("photo", photoName).put("press_at", at(capMs)).put("answered_at", now())
                            .put("model_s", (System.nanoTime() - t0) / 1e9).put("text", if (quiet) "" else line).put("silent", quiet).put("cost_usd", reply.cost))
                        if (!live) break
                        if (quiet) { Thread.sleep(300); continue }
                        said.add(line); remember("saw: $line")
                        show(line, "LIVE   TAP: stop")
                        if (voiceKey.isNotEmpty()) speak(line, photoName, capMs)
                    } catch (e: Throwable) {
                        Log.e(TAG, "live frame", e)
                        log(JSONObject().put("mode", "live").put("n", n).put("press_at", at(capMs)).put("error", "${e.javaClass.simpleName}: ${e.message}".take(300)))
                        if (live) { failed(e); Thread.sleep(1500) }
                    }
                }
            } finally {
                if (live) stopLive()      // ran out its time
            }
        }.start()
    }

    private fun stopLive() {
        if (!live) return
        live = false
        speechGen++
        log(JSONObject().put("event", "live_stop").put("at", now()))
        show(IDLE, IDLE_HINT)
        Thread { speak(P_LIVE_OFF, "", System.currentTimeMillis(), keep = true) }.start()
    }

    // ---- find mode: say what you want, then photos run continuously and a beep points at it ----
    // The beep comes from the side the thing is on (louder and a touch earlier in that ear), pulses
    // faster the nearer it is to the centre of view, is higher in pitch the higher it is in view and
    // louder the more of the view it fills. A slow low tick means "not in view".

    private fun startFind(target: String) {
        val startMs = System.currentTimeMillis()
        log(JSONObject().put("event", "find_start").put("at", at(startMs)).put("target", target))
        cueX = -1; cueFromMs = 0L; beepMuted = false; findSpoken = false
        lastSeenX = -1; missCount = 0; lastGuideMs = startMs; heldCount = 0; heldFlags.clear()
        synchronized(findRecent) { findRecent.clear() }
        finding = true
        // The words move to a small line at the bottom, leaving the display clear for the box.
        runOnUiThread { text.textSize = 15f; text.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
        show("find: $target", "looking...  TAP: stop")
        Thread { beeper() }.start()
        sayInFind("Finding $target. Tap to stop.", "", startMs)
        // Two photo-and-ask loops, half a beat apart: about twice the updates of one.
        // Four photo-and-ask loops, 0.4 s apart: a new answer about every 0.4 s, each about 1.5 s old.
        // Photos go at half size (640 wide): measured 1.55 s photo-to-answer against 1.79 s at full size.
        // --ei find_loops N and --ei find_small 0 change these for comparison.
        val small = intent.getIntExtra("find_small", 1) != 0
        for (k in 0 until intent.getIntExtra("find_loops", 4).coerceIn(1, 6)) Thread { Thread.sleep(400L * k); findLoop(target, small) }.start()
    }

    /**
     * Speaks during find with the beeps held back. Lines are spoken one after another, never on top of
     * each other: "Finding phone. Tap to stop." finishes before "phone, on the table" starts.
     */
    private fun sayInFind(line: String, photoName: String, fromMs: Long) {
        if (voiceKey.isEmpty()) return
        val gen = speechGen
        Thread {
            synchronized(findSpeech) {
                if (!finding || speechGen != gen) return@Thread      // find ended, or was cut off, while this line waited
                beepMuted = true
                try { speak(line, photoName, fromMs) } finally { beepMuted = false }
            }
        }.start()
    }

    private val findSpeech = Any()
    private var lastSeenX = -1      // where the thing was when last seen (0 left to 100 right), -1 = never
    private var missCount = 0       // answers in a row without it
    private var lastGuideMs = 0L    // when the voice last gave guidance
    private var heldCount = 0       // of the last 3 answers, how many say it is in the wearer's hand
    private val heldFlags = ArrayList<Boolean>()

    /** Half size in each direction (1280x960 -> 640x480), about a third of the bytes. */
    private fun shrink(jpeg: ByteArray): ByteArray {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }) ?: return jpeg
        return ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it); bmp.recycle() }.toByteArray()
    }
    private val findRecent = ArrayList<Pair<Long, String>>()   // (photo time, where it was or "not in view"): told to the model with each new photo

    /** While finding: swipes change how much of the photo the display is taken to cover, until the box sits on the thing. */
    private fun tuneBox(factor: Float) {
        arW = (arW * factor).coerceIn(0.1f, 1f)
        getSharedPreferences("sense", Context.MODE_PRIVATE).edit().putFloat("ar_w", arW).apply()
        log(JSONObject().put("event", "ar_tune").put("at", now()).put("ar_w", arW.toDouble()))
        boxView.postInvalidate()
    }

    /** Draws the find box on the see-through display. Black stays transparent, so only the outline shows. */
    private inner class BoxView(context: Context) : android.view.View(context) {
        private val line = android.graphics.Paint().apply { color = Color.GREEN; style = android.graphics.Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }
        private val fill = android.graphics.Paint().apply { color = Color.GREEN; style = android.graphics.Paint.Style.FILL; isAntiAlias = true }
        private val small = android.graphics.Paint().apply { color = Color.GREEN; textSize = 18f; isAntiAlias = true }

        override fun onDraw(c: android.graphics.Canvas) {
            if (!finding) return
            val w = width.toFloat(); val h = height.toFloat()
            c.drawText("box scale %.2f".format(arW), 8f, 22f, small)
            val b = cueBox ?: return
            // Photo position (0 to 1) -> display pixel, through the display's window in the photo.
            fun px(u: Float) = w / 2 + (u - arCx) / arW * w
            fun py(v: Float) = h / 2 + (v - arCy) / arW * h
            val l = px(b[1] / 1000f); val r = px(b[3] / 1000f); val t = py(b[0] / 1000f); val bt = py(b[2] / 1000f)
            if (r > 0 && l < w && bt > 0 && t < h) { c.drawRect(l, t, r, bt, line); return }
            // Outside what the display covers: an arrow on the edge, pointing the way to turn.
            val cx = (l + r) / 2; val cy = (t + bt) / 2
            val ax = cx.coerceIn(40f, w - 40f); val ay = cy.coerceIn(40f, h - 40f)
            val ang = Math.atan2((cy - h / 2).toDouble(), (cx - w / 2).toDouble())
            val path = android.graphics.Path()
            for ((i, a) in listOf(0.0, 2.5, -2.5).withIndex()) {
                val x = ax + (34 * Math.cos(ang + a)).toFloat(); val y = ay + (34 * Math.sin(ang + a)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            c.drawPath(path, fill)
        }
    }

    private fun stopFind(gotIt: Boolean = false) {
        if (!finding) return
        finding = false
        cueBox = null
        runOnUiThread { text.textSize = 26f; text.gravity = Gravity.CENTER; boxView.invalidate() }
        speechGen++
        log(JSONObject().put("event", "find_stop").put("at", now()).put("got_it", gotIt))
        // The end is always spoken, so the wearer knows the beeps stopped on purpose.
        // Done: a rising three-note chime, then "You have it." Stopped by hand: the falling pair, then "Find off."
        Thread { if (gotIt) tone(660, 880, 1320, ms = 110) else tone(990, 660); speak(if (gotIt) P_GOT_IT else P_FIND_OFF, "", System.currentTimeMillis(), keep = true) }.start()
show(IDLE, IDLE_HINT)
    }

    private var findSpoken = false

    /** The last few sightings as a line for the model, so its hint can say "it was on your left a moment ago". */
    private fun recentSightings(nowMs: Long): String = synchronized(findRecent) {
        if (findRecent.isEmpty()) "(first photo)"
        else findRecent.takeLast(5).joinToString("; ") { "%.0f s ago: %s".format((nowMs - it.first) / 1000.0, it.second) }
    }

    private fun findLoop(target: String, small: Boolean) {
        while (finding) {
            val n = synchronized(this) { ++count }
            val photoName = "%03d.jpg".format(n)
            val capMs = System.currentTimeMillis()
            try {
                val full = photo()
                val gotMs = System.currentTimeMillis()
                File(sessionDir, photoName).writeBytes(full)
                val jpeg = if (small) shrink(full) else full
                val shrinkS = (System.currentTimeMillis() - gotMs) / 1000.0
                val t0 = System.nanoTime()
                val reply = chat(JSONArray().put(JSONObject().put("type", "text").put("text", FIND_PROMPT.replace("{target}", target).replace("{recent}", recentSightings(capMs)))).put(imagePart(jpeg)), 90)
                val modelS = (System.nanoTime() - t0) / 1e9
                val found = Regex("\"found\"\\s*:\\s*true").containsMatchIn(reply.text)
                // The box is [top, left, bottom, right], each 0 to 1000: the layout this model family is trained to give.
                val box = Regex("\"box_2d\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)").find(reply.text)
                    ?.groupValues?.drop(1)?.map { it.toInt().coerceIn(0, 1000) }
                val x = box?.let { (it[1] + it[3]) / 20 }
                val y = box?.let { (it[0] + it[2]) / 20 } ?: 50
                val size = box?.let { abs(it[3] - it[1]) / 10 } ?: 20
                val where = Regex("\"where\"\\s*:\\s*\"([^\"]*)\"").find(reply.text)?.groupValues?.get(1).orEmpty()
                val hint = Regex("\"hint\"\\s*:\\s*\"([^\"]*)\"").find(reply.text)?.groupValues?.get(1).orEmpty()
                val held = Regex("\"held\"\\s*:\\s*true").containsMatchIn(reply.text)
                val seen = found && x != null
                synchronized(findRecent) { findRecent.add(capMs to (if (seen) where.ifEmpty { "in view" } + (if (held) " (in their hand)" else "") else "not in view")); findRecent.sortBy { it.first } }
                log(JSONObject().put("mode", "find").put("n", n).put("target", target).put("photo", photoName)
                    .put("press_at", at(capMs)).put("captured_at", at(gotMs)).put("answered_at", now())
                    .put("camera_s", (gotMs - capMs) / 1000.0).put("model_s", modelS).put("found", seen)
                    .put("x", if (seen) x else JSONObject.NULL).put("y", if (seen) y else JSONObject.NULL)
                    .put("size", if (seen) size else JSONObject.NULL).put("where", where).put("hint", hint).put("held", held)
                    .put("small", small).put("sent_kb", reply.kb).put("shrink_s", shrinkS).put("upload_s", reply.uploadS).put("wait_s", reply.waitS).put("read_s", reply.readS)
                    .put("box", if (seen) JSONArray(box) else JSONObject.NULL).put("ar_w", arW.toDouble()).put("cost_usd", reply.cost))
                if (!finding) break
                var sayIt = false
                var guide = ""
                synchronized(this) {
                    if (capMs > cueFromMs) {          // answers can come back out of order: keep only the newest photo's
                        cueFromMs = capMs
                        cueY = y; cueSize = size
                        cueBox = if (seen) box!!.toIntArray() else null
                        boxView.postInvalidate()
                        cueX = if (seen) x!! else -1
                        if (seen && !findSpoken && where.isNotEmpty()) { findSpoken = true; sayIt = true }
                        val nowMs = System.currentTimeMillis()
                        heldFlags.add(seen && held)
                        heldCount = heldFlags.takeLast(3).count { it }      // 2 of the last 3: one stray "no" does not reset it
                        if (seen) { lastSeenX = x!!; missCount = 0 } else {
                            missCount++
                            if (missCount == 3 && lastSeenX >= 0) {
                                // Just lost it: say which way it went out of view, from where it was last seen.
                                guide = "Lost it. It was " + (if (lastSeenX < 38) "to your left." else if (lastSeenX > 62) "to your right." else "straight ahead.")
                                lastGuideMs = nowMs
                            } else if (missCount >= 3 && hint.isNotEmpty() && nowMs - lastGuideMs > 7000) {
                                guide = hint; lastGuideMs = nowMs          // not in view: where to look next
                            }
                        }
                    } else return@synchronized
                    val side = when { !seen -> "not in view"; x!! < 38 -> "LEFT"; x > 62 -> "RIGHT"; else -> "AHEAD" } +
                        if (!seen) "" else if (y < 33) ", high" else if (y > 67) ", low" else ""
                    show("$target: $side", (if (seen) "$where  " else "") + "TAP: stop")
                }
                if (finding && synchronized(this) { heldCount } >= 2) { stopFind(gotIt = true); break }     // picked up: done
                if (sayIt) { lastGuideMs = System.currentTimeMillis(); sayInFind("$target, $where", photoName, capMs) }
                else if (guide.isNotEmpty() && finding) { log(JSONObject().put("event", "find_guide").put("at", now()).put("said", guide)); sayInFind(guide, photoName, capMs) }
            } catch (e: Throwable) {
                Log.e(TAG, "find frame", e)
                log(JSONObject().put("mode", "find").put("n", n).put("photo", photoName).put("press_at", at(capMs))
                    .put("error", "${e.javaClass.simpleName}: ${e.message}".take(300)))
                Thread.sleep(500)
            }
        }
    }

    /** Plays the current cue, one pulse at a time, until find mode stops. */
    private fun beeper() {
        val rate = 48000
        @Suppress("DEPRECATION")
        val track = AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 2, AudioTrack.MODE_STREAM)
        val boost = try {
            android.media.audiofx.LoudnessEnhancer(track.audioSessionId).apply { setTargetGain(900); enabled = true }
        } catch (e: Throwable) { null }
        track.play()
        // Everything sent to the speakers here is also kept in the session folder (48 kHz stereo, raw),
        // silences included, so a demo video can put the beeps back exactly (app/screen_demo.py).
        val keptName = "beeps_%02d.pcm".format(beepCount.incrementAndGet())
        val kept = try { File(sessionDir, keptName).outputStream().buffered() } catch (e: Throwable) { null }
        fun keep(s: ShortArray, n: Int) {
            try {
                val b = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                b.asShortBuffer().put(s, 0, n)
                kept?.write(b.array())
            } catch (_: Throwable) {}
        }
        log(JSONObject().put("event", "beeps").put("at", now()).put("file", keptName).put("rate", rate).put("channels", 2))
        try {
            while (finding) {
                val x = cueX
                if (beepMuted) { val quiet = ShortArray(rate / 5); track.write(quiet, 0, quiet.size); keep(quiet, quiet.size); continue }
                val off = if (x < 0) 0.0 else abs(x - 50) / 50.0                  // 0 = centre, 1 = edge of view
                val pps = if (x < 0) 1.2 else if (off <= 0.2) 9.0 else 2.5 + 5.0 * (1 - off)
                // Pitch follows height in view: about 1100 Hz at the top, 660 in the middle, 390 at the bottom.
                val freq = if (x < 0) 330.0 else 660.0 * Math.pow(2.0, (50 - cueY) / 50.0 * 0.75)
                val level = if (x < 0) 0.3 else 0.45 + 0.55 * minOf(1.0, cueSize / 40.0)
                val angle = if (x < 0) 0.0 else (x - 50) / 50.0 * 80.0             // exaggerated so the side is obvious
                val pan = (angle + 90) / 180 * (Math.PI / 2)
                val gl = Math.cos(pan); val gr = Math.sin(pan)
                // The far ear also hears it up to 0.65 ms later, as with a real sound from that side.
                val delay = (0.00065 * abs(Math.sin(angle * Math.PI / 180)) * rate).toInt()
                val dl = if (angle > 0) delay else 0
                val dr = if (angle < 0) delay else 0
                val period = (rate / pps).toInt()
                val on = minOf(period / 2, rate * 8 / 100)
                val out = ShortArray(period * 2)
                for (i in 0 until on) {
                    val env = minOf(1.0, i / 200.0, (on - i) / 200.0)
                    val t = i.toDouble() / rate
                    val v = env * (Math.sin(2 * Math.PI * freq * t) + 0.4 * Math.sin(4 * Math.PI * freq * t)) / 1.4 * level * 30000
                    if (i + dl < period) out[2 * (i + dl)] = (v * gl).toInt().toShort()
                    if (i + dr < period) out[2 * (i + dr) + 1] = (v * gr).toInt().toShort()
                }
                track.write(out, 0, out.size)
                keep(out, out.size)
            }
        } finally {
            try { kept?.close() } catch (_: Throwable) {}
            try { boost?.release(); track.pause(); track.flush(); track.release() } catch (_: Throwable) {}
        }
    }

    // ---- voice: the glasses have no text-to-speech, so ElevenLabs streams the audio and we play it as it arrives ----

    /** Short notes, one after another: the non-spoken half of the interface. Returns when they have played. */
    private fun tone(vararg freqs: Int, ms: Int = 85, soft: Boolean = false) {
        try {
            val per = RATE * ms / 1000
            val pcm = ShortArray(per * freqs.size) { i ->
                val p = i % per
                val env = minOf(1.0, p / 80.0, (per - p) / 80.0)
                (env * Math.sin(2 * Math.PI * freqs[i / per] * p / RATE) * (if (soft) 8000 else 22000)).toInt().toShort()
            }
            @Suppress("DEPRECATION")
            val t = AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, pcm.size * 2, AudioTrack.MODE_STATIC)
            t.write(pcm, 0, pcm.size)
            t.play()
            log(JSONObject().put("event", "tone").put("at", now()).put("freqs", JSONArray(freqs.toList())).put("ms", ms).put("soft", soft))
            Thread.sleep(ms.toLong() * freqs.size + 40)
            t.release()
        } catch (e: Throwable) { Log.w(TAG, "tone: $e") }
    }

    /** Two low notes, then a spoken reason. The spoken lines are stored on the glasses, so they play with no network. */
    private fun failed(e: Throwable) {
        tone(220, 180)
        val network = e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException
        if (voiceKey.isNotEmpty()) speak(if (network) P_NO_NETWORK else P_FAILED, "", System.currentTimeMillis(), keep = true)
    }

    private fun voiceFile(text: String, voice: String = voiceName) = File(filesDir, "voice/" + voice + "_" + Integer.toHexString(text.hashCode()) + ".pcm")

    private fun saveVoice(text: String, pcm: ByteArray, voice: String = voiceName) { voiceFile(text, voice).apply { parentFile?.mkdirs() }.writeBytes(pcm) }

    private fun fetchVoice(text: String, voice: String = voiceName): java.io.InputStream {
        val conn = URL("https://api.elevenlabs.io/v1/text-to-speech/${VOICES[voice]}/stream?output_format=pcm_16000").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        conn.doOutput = true
        conn.setRequestProperty("xi-api-key", voiceKey)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(JSONObject().put("text", text).put("model_id", VOICE_MODEL).toString().toByteArray()) }
        val code = conn.responseCode
        if (code !in 200..299) error("voice HTTP $code: ${conn.errorStream?.bufferedReader()?.readText()?.take(120)}")
        return conn.inputStream
    }

    /** Speaks [answer]. With [keep], the audio is stored on the glasses and replayed from there next time. */
    private fun speak(answer: String, photoName: String, pressMs: Long, keep: Boolean = false, voice: String = voiceName) {
        val gen = speechGen
        val requestedMs = System.currentTimeMillis()
        var track: AudioTrack? = null
        var boost: android.media.audiofx.LoudnessEnhancer? = null
        var played: java.io.OutputStream? = null   // what is spoken is also kept in the session folder (say_NNN.pcm, 16 kHz mono), for demo videos
        try {
            val stored = voiceFile(answer, voice).takeIf { it.exists() }
            val source = stored?.inputStream() ?: fetchVoice(answer, voice)
            val all = if (keep && stored == null) ByteArrayOutputStream() else null
            @Suppress("DEPRECATION")
            track = AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE), AudioTrack.MODE_STREAM)
            boost = try {
                android.media.audiofx.LoudnessEnhancer(track.audioSessionId).apply { setTargetGain(900); enabled = true }
            } catch (e: Throwable) { null }
            track.play()
            var audioMs = 0L
            var total = 0
            val buf = ByteArray(4096)
            var held = 0          // an odd byte left over, so every write is whole 16-bit samples
            var complete = false
            source.use { input ->
                while (speechGen == gen) {
                    val r = input.read(buf, held, buf.size - held)
                    if (r < 0) { complete = true; break }
                    all?.write(buf, held, r)
                    val have = held + r
                    val even = have and 1.inv()
                    if (even > 0) {
                        if (audioMs == 0L) {
                            audioMs = System.currentTimeMillis()
                            val playedName = "say_%03d.pcm".format(sayCount.incrementAndGet())
                            played = try { File(sessionDir, playedName).outputStream().buffered() } catch (e: Throwable) { null }
                            log(JSONObject().put("event", "spoke").put("photo", photoName).put("audio_at", at(audioMs)).put("audio", playedName)
                                .put("voice", (if (stored != null) "stored " else "") + voice).put("said", answer.take(80)).put("voice_wait_s", (audioMs - requestedMs) / 1000.0)
                                .put("press_to_voice_s", (audioMs - pressMs) / 1000.0))
                        }
                        track.write(buf, 0, even)
                        try { played?.write(buf, 0, even) } catch (_: Throwable) {}
                        total += even
                    }
                    held = have - even
                    if (held == 1) buf[0] = buf[even]
                }
            }
            if (complete && all != null) saveVoice(answer, all.toByteArray(), voice)
            // The write returns before the sound has finished: wait out what is still queued.
            val endMs = audioMs + total / 2 * 1000L / RATE + 150
            while (speechGen == gen && System.currentTimeMillis() < endMs) Thread.sleep(20)
            if (audioMs != 0L) log(JSONObject().put("event", "spoke_done").put("photo", photoName).put("done_at", now())
                .put("cut_off", speechGen != gen))
        } catch (e: Throwable) {
            Log.e(TAG, "speak", e)
            log(JSONObject().put("event", "voice_error").put("photo", photoName).put("error", "${e.javaClass.simpleName}: ${e.message}".take(300)))
        } finally {
            try { played?.close() } catch (_: Throwable) {}
            try { boost?.release(); track?.pause(); track?.flush(); track?.release() } catch (_: Throwable) {}
        }
    }

    /** Leaving the front (system camera, recorder, menu) stops find mode, so it cannot keep taking paid photos unseen. */
    override fun onPause() {
        if (finding) stopFind()
        if (live) stopLive()
        listening = false; speechGen++
        closeCamera()
        super.onPause()
    }

    /** Ends the video file properly and frees the camera; the next photo opens it again (a new video file). */
    @Synchronized
    private fun closeCamera() {
        recorder?.let {
            try { it.stop(); log(JSONObject().put("event", "video_stop").put("at", now())) } catch (e: Throwable) { Log.e(TAG, "video stop", e) }
            try { it.release() } catch (_: Throwable) {}
        }
        recorder = null; recSurface = null
        try { session?.close(); device?.close(); reader?.close() } catch (_: Throwable) {}
        session = null; device = null; reader = null
    }

    override fun onDestroy() {
        speechGen++; listening = false; finding = false; live = false; micRunning = false
        closeCamera()
        camThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val TAG = "Sense"
        const val RATE = 16000
        // Stock ElevenLabs voices the free plan may use; the first is the default. Picked from notes/voices/round2.
        val VOICES = linkedMapOf("Alice" to "Xb7hH8MSUJpSbSDYk0k2", "Chris" to "iP95p4xoKVk53GoZ742B", "Will" to "bIHbv24MWmeRgasZH58o",
            "Roger" to "CwhRBWXzGAHq8TQ4Fs17", "River" to "SAz9YHcvj6GT2YYXdXww", "Sarah" to "EXAVITQu4vr4xnSDxMaL")
        const val VOICE_MODEL = "eleven_v4_turbo"
        const val P_AUDITION = "Tap when you hear the voice you want."
        const val P_WHICH = "Which one?"
        const val VOICE_CONTEXT = "they are choosing a voice for the glasses from Alice, Chris, Will, Roger, River, Sarah. " +
            "A bare name, or a word that sounds like one ('well' is Will), means settings, voice, with that name. 'None' or 'keep it' means none."
        const val SETTINGS_CONTEXT = "they were just told their settings and that they can say volume up, volume down, or change voice. " +
            "A short reply such as 'louder', 'down', 'change voice' is a settings request. If they say nothing meaningful, action is none."
        const val P_GOT_IT = "You have it."
        const val P_FIND_OFF = "Find off."
        const val P_LIVE_ON = "Live on. Tap to stop."
        const val P_LIVE_OFF = "Live off."
        const val LIVE_PROMPT = "You are the eyes of a blind person wearing camera glasses, describing continuously as they move. " +
            "Already said recently: {said}. Look at the new photo and say only what is new or has changed and is worth knowing, " +
            "in at most 10 words; a close person or obstacle first. No filler, no invented details. If nothing new, reply exactly SILENT."
        const val RECALL_PROMPT = "Notes of what a blind person's camera glasses saw and said earlier (time, then what was asked or seen):\n{notes}\n\n" +
            "They now ask: \"{question}\". Answer from the notes in at most 20 words, with the time if it helps. If the notes do not cover it, say so."

        const val P_READY = "Ready. Triple tap for help."
        const val P_HELP = "Tap, then speak, or stay quiet for a description. Swipe for volume. Double tap to stop."
        const val P_FIND_WHAT = "Find what?"
        const val P_UNCLEAR = "I did not catch that."
        const val P_FAILED = "That failed. Try again."
        const val P_NO_NETWORK = "No connection."
        val PROMPTS = listOf(P_READY, P_HELP, P_AUDITION, P_WHICH, P_LIVE_ON, P_LIVE_OFF, P_GOT_IT, P_FIND_OFF, P_FIND_WHAT, P_UNCLEAR, P_FAILED, P_NO_NETWORK)
        const val IDLE = "TAP"
        const val IDLE_HINT = "then speak: a question, 'read this', 'find my ...',\n'keep describing', 'settings'\nor stay quiet for a description\nswipe: volume   double tap: stop (twice: exit)\ntriple tap: instructions"
        const val ROUTE_PROMPT = "You are the eyes of a blind person, worn as glasses. The audio is what they said just after tapping; " +
            "the photo is what is in front of them. Decide what they want and reply with only JSON: " +
            "{\"heard\": their words, \"action\": one of answer, describe, read, find, live, recall, settings, none, \"target\": \"\", \"setting\": \"\", \"value\": \"\", \"say\": what to speak back}. " +
            "answer: a question about what is in front of them; say is the answer in at most 15 words. " +
            "read: they want a page, slide, sign, screen, label, menu or diagram read or conveyed. say first names what it is in a few words " +
            "('Slide titled ...', 'Flowchart of ...', 'Menu'), then gives the content in a sensible order: the title and main text as written; " +
            "for a diagram, chart or table, describe its structure and what leads to what instead of stringing the labels together. " +
            "At most 90 words. Read every word in order only if they ask for word for word. If nothing is readable, say so. " +
            "find: they want to locate something ('find my phone', 'where is the door'); target is the thing in 1 to 4 words; say is empty. " +
            "live: they want continuous description as they move ('keep describing', 'live mode'); say is empty. " +
            "recall: they ask about something from earlier, not what is in view now ('what did that sign say', 'what did I ask before'); say is empty. " +
            "settings: they want to change or hear a setting; setting is volume (value: up, down, max, or a number 3 to 15), " +
            "voice (value: the name if they give one of Alice, Chris, Will, Roger, River, Sarah; otherwise empty, e.g. 'change voice', 'try the voices'), " +
            "or status ('settings', 'open settings', 'what are my settings'); say is empty. " +
            "none: their words mean do nothing ('stop', 'stop find mode', 'never mind', 'cancel', 'that's all', 'thanks'); say is empty. " +
            "describe: the audio has no clear words from them (noise, other people talking); say is the most useful thing about what is in front of them " +
            "in at most 15 words, a close person or obstacle first. Money: name the notes from their numerals. No filler, no invented details."
        const val PROMPT = "You are the eyes of a blind person, worn as glasses. Say the most useful thing about " +
            "what is in front of them right now, in at most 15 words. A person or obstacle close in front " +
            "comes first. If there is text worth reading (sign, screen, label), read the key part in quotes. " +
            "Otherwise name what is ahead and where. No filler like 'I see'. Don't invent details. " +
            "If it is too dark or blurry to tell: 'Too dark to see.'"
        const val ASK_PROMPT = "You are the eyes of a blind person, worn as glasses. The audio is their spoken question " +
            "about the photo in front of them. Reply in exactly two lines. Line 1: the question as you heard it. " +
            "Line 2: the answer, at most 15 words, no filler, no invented details. If you cannot make out a " +
            "question, line 1 is '(unclear)' and line 2 is 'I did not catch that.'"
        const val TARGET_PROMPT = "The audio is a blind person saying what they are looking for. Reply with only the " +
            "name of the thing, 1 to 4 words, lower case, e.g. 'water bottle'. If you cannot make it out, reply '(unclear)'."
        const val FIND_PROMPT = "A blind person wearing camera glasses is looking for: {target}. Look at the photo. " +
            "Earlier photos, oldest first: {recent}. " +
            "Reply with only JSON: {\"found\": true or false, \"box_2d\": [ymin, xmin, ymax, xmax] around it, each from " +
            "0 to 1000, \"where\": at most 6 words, e.g. 'on the table, slightly left', " +
            "\"held\": true only if the wearer is now holding it in their own hand, " +
            "\"hint\": only when not found, at most 8 words on where to look next, using what is visible and the earlier photos, e.g. " +
            "'table ahead, move closer to check it', 'it was on your left, turn back', 'only a wall here, turn around'}. " +
            "If you are not sure it is in the photo, found is false."
    }
}
