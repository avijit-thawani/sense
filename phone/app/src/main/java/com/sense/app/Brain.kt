package com.sense.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Same prompt, brightening and repeat rules as tools/describe.py (items mode). */
object Brain {
    const val PROMPT = "You are the eyes of a blind person. List up to 3 things you can see, most " +
        "important first: people and hazards, then nearby objects. Each item 1-4 words " +
        "with position, items separated by ';'. Quote text only if clearly legible. " +
        "No articles, no filler, no full sentences. Example: " +
        "'Person ahead, close; chair left; window behind'. " +
        "If too dark or blurry to be sure, reply exactly SILENT."
    private const val ITEM_MEMORY_MS = 8000L
    private const val ITEM_OVERLAP = 0.67
    private const val MAX_ITEMS_SPOKEN = 2
    private val STOPWORDS = ("a an the is are of on in at to and with near your you there this it its now some " +
        "left right center centre ahead front close very far behind background foreground " +
        "bottom top upper lower middle side straight forward slightly centered edge nearby below above")
        .split(" ").toSet()

    data class Answer(val text: String, val seconds: Double, val costUsd: Double)

    /** Contrast stretch (1% per channel) then gamma-lift dark frames toward mean 0.45, like enhance.py "auto". */
    fun brighten(jpeg: ByteArray): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val hist = Array(3) { IntArray(256) }
        for (p in px) {
            hist[0][(p shr 16) and 255]++; hist[1][(p shr 8) and 255]++; hist[2][p and 255]++
        }
        val cut = px.size / 100
        val luts = Array(3) { c ->
            var lo = 0; var acc = 0
            while (lo < 255 && acc + hist[c][lo] <= cut) { acc += hist[c][lo]; lo++ }
            var hi = 255; acc = 0
            while (hi > lo && acc + hist[c][hi] <= cut) { acc += hist[c][hi]; hi-- }
            IntArray(256) { v -> if (hi <= lo) v else ((v - lo) * 255.0 / (hi - lo)).roundToInt().coerceIn(0, 255) }
        }
        var sum = 0.0
        for (p in px) {
            val r = luts[0][(p shr 16) and 255]; val g = luts[1][(p shr 8) and 255]; val b = luts[2][p and 255]
            sum += 0.299 * r + 0.587 * g + 0.114 * b
        }
        val mean = sum / px.size / 255
        val gamma = if (mean > 0.02 && mean < 0.45) ln(0.45) / ln(mean) else 1.0
        val g = IntArray(256) { v -> (255 * (v / 255.0).pow(gamma)).roundToInt() }
        for (i in px.indices) {
            val p = px[i]
            px[i] = (0xFF shl 24) or (g[luts[0][(p shr 16) and 255]] shl 16) or
                (g[luts[1][(p shr 8) and 255]] shl 8) or g[luts[2][p and 255]]
        }
        val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    fun describe(jpeg: ByteArray, key: String, model: String): Answer {
        val image = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 80)
            .put("provider", JSONObject().put("sort", "latency"))
            .put("reasoning", JSONObject().put("effort", "none"))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", PROMPT))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image))))))
        val t0 = System.nanoTime()
        val conn = URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $key")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-Title", "sense")
        conn.setRequestProperty("HTTP-Referer", "https://localhost/sense-android")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
        val secs = (System.nanoTime() - t0) / 1e9
        if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(200)}")
        val json = JSONObject(text)
        val answer = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        val cost = json.optJSONObject("usage")?.optDouble("cost", 0.0) ?: 0.0
        return Answer(answer, secs, cost)
    }

    private fun words(text: String) =
        text.split(" ").map { it.trim('.', ',', ';', ':', '!', '?', '"', '\'').lowercase() }
            .filter { it.isNotEmpty() && it !in STOPWORDS }.toSet()

    private val said = ArrayDeque<Pair<Long, Set<String>>>()

    /** Returns (status, text to speak or null); remembers what is spoken. */
    @Synchronized
    fun decide(text: String, now: Long): Pair<String, String?> {
        if (text.trim(' ', '.', '"', '\'').uppercase() == "SILENT") return "silent" to null
        while (said.size > 20) said.removeFirst()
        val recent = said.filter { now - it.first < ITEM_MEMORY_MS }.map { it.second }.toMutableList()
        val fresh = mutableListOf<String>()
        for (item in text.split(";").map { it.trim(' ', '.') }) {
            val w = words(item)
            if (w.isNotEmpty() && recent.none { old -> (w intersect old).size.toDouble() / w.size >= ITEM_OVERLAP }) {
                fresh += item
                recent += w
            }
        }
        val spoken = fresh.take(MAX_ITEMS_SPOKEN)
        if (spoken.isEmpty()) return "repeat" to null
        spoken.forEach { said.addLast(now to words(it)) }
        return "spoken" to spoken.joinToString(". ") { it.replaceFirstChar(Char::uppercase) } + "."
    }
}
