package com.sense.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Models offered in Settings and their per-moment cost estimates. The estimate is our measured token
 * use with Gemini 3.1 Flash Lite (one 1024×768 photo + prompt in, a short answer out) priced at each
 * model's OpenRouter rates. Image tokenizers differ between providers, so other models are approximate.
 */
object Models {
    /** bench: thinking level -> (median seconds, $ per moment) measured on the dev moments (tools/climb.py). */
    class Model(val id: String, val name: String, val inPerM: Double, val outPerM: Double, val reasons: Boolean,
                val bench: Map<String, Pair<Double, Double>> = emptyMap()) {
        /** Provider logo for the picker. */
        val logo: Int get() = when (id.substringBefore('/')) {
            "openai" -> R.drawable.logo_openai; "anthropic" -> R.drawable.logo_claude
            "qwen" -> R.drawable.logo_qwen; else -> R.drawable.logo_gemini
        }
    }

    // The shortlist from the Oct 30 2026 benchmark (docs/evals.md): the rest scored lower or took 2–8 s.
    // Prices are $ per million tokens from openrouter.ai/api/v1/models, refreshed live when online.
    private val BUILT_IN = listOf(
        Model("google/gemini-3.1-flash-lite", "Gemini 3.1 Flash Lite", 0.25, 1.5, true,
            mapOf("none" to (1.28 to 0.00046), "low" to (1.82 to 0.00064))),
        Model("google/gemini-3.5-flash-lite", "Gemini 3.5 Flash Lite", 0.30, 2.5, true,
            mapOf("low" to (1.30 to 0.00061))),
        Model("google/gemma-4-31b-it", "Gemma 4 31B", 0.20, 0.40, true,
            mapOf("none" to (0.64 to 0.00042), "low" to (4.20 to 0.00110))),
        Model("google/gemini-4-argon", "Gemini 4 Argon", 2.0, 10.0, true,
            mapOf("low" to (4.57 to 0.00379))),
    )

    /** Median seconds from request to answer on the benchmark, if measured. */
    fun latency(m: Model, effort: String) = m.bench[effort]?.first

    /** Measured on the phone with Gemini 3.1 Flash Lite (median $0.00045 per moment, 1024×768 photo):
     *  tokens in per call (photo + prompt) and out per answer. */
    const val TOKENS_IN = 1600
    const val TOKENS_OUT = 30

    /** Rough extra output tokens spent thinking, per reasoning effort. */
    val REASONING_TOKENS = linkedMapOf("none" to 0, "low" to 400)

    /** max_tokens per effort: reasoning models return nothing if thinking uses up the budget. */
    fun maxTokens(base: Int, effort: String) = base + when (effort) { "low" -> 2000; "medium" -> 4000; "high" -> 8000; else -> 0 }

    fun all(ctx: Context): List<Model> {
        val cached = ctx.getSharedPreferences("sense", Context.MODE_PRIVATE).getString("prices", null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return BUILT_IN
        return BUILT_IN.map { m ->
            cached.optJSONObject(m.id)?.let { p ->
                Model(m.id, m.name, p.getDouble("in"), p.getDouble("out"), p.optBoolean("reasons", m.reasons), m.bench)
            } ?: m
        }
    }

    /** Models whose OpenRouter endpoint rejects reasoning "none" (seen Oct 30 2026), plus any found since. */
    private val THINKING_REQUIRED = setOf("google/gemini-3.5-flash-lite", "openai/gpt-5-nano", "google/gemini-4-argon")

    fun thinkingRequired(ctx: Context, id: String) = id in THINKING_REQUIRED ||
        id in ctx.getSharedPreferences("sense", Context.MODE_PRIVATE).getStringSet("thinking_required", emptySet())!!

    fun rememberThinkingRequired(ctx: Context, id: String) {
        val prefs = ctx.getSharedPreferences("sense", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("thinking_required", prefs.getStringSet("thinking_required", emptySet())!! + id).apply()
    }

    /** The effort actually sent: "none" becomes "low" where thinking can't be turned off. */
    fun effort(ctx: Context, id: String, wanted: String) = if (wanted == "none" && thinkingRequired(ctx, id)) "low" else wanted

    fun find(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }
        ?: Model(id, id.substringAfter('/'), 0.0, 0.0, true)

    /** Measured benchmark cost where we have it, else our token use priced at the model's rates. */
    fun estimateUsd(m: Model, effort: String): Double {
        m.bench[effort]?.let { return it.second }
        val out = TOKENS_OUT + (if (m.reasons) REASONING_TOKENS[effort] ?: 0 else 0)
        return (TOKENS_IN * m.inPerM + out * m.outPerM) / 1e6
    }

    /** Refreshes prices from OpenRouter (public, no key). Network: call off the main thread. */
    fun refresh(ctx: Context): Boolean = runCatching {
        val conn = URL("https://openrouter.ai/api/v1/models").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 15000
        val data = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("data")
        val ids = BUILT_IN.map { it.id }.toSet()
        val out = JSONObject()
        for (i in 0 until data.length()) {
            val m = data.getJSONObject(i)
            if (m.getString("id") !in ids) continue
            val p = m.getJSONObject("pricing")
            val params = m.optJSONArray("supported_parameters")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            out.put(m.getString("id"), JSONObject()
                .put("in", p.getString("prompt").toDouble() * 1e6)
                .put("out", p.getString("completion").toDouble() * 1e6)
                .put("reasons", "reasoning" in params))
        }
        ctx.getSharedPreferences("sense", Context.MODE_PRIVATE).edit()
            .putString("prices", out.toString()).putLong("prices_at", System.currentTimeMillis()).apply()
        true
    }.getOrDefault(false)
}
