package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * ============================================================================
 * FCL AI-Advisor — Service (HTTP)
 * ============================================================================
 *
 * Ondersteunt twee providers: Anthropic Claude en Google Gemini.
 * Gemini heeft een gratis tier — handig om te testen zonder betaalrekening.
 *
 * Claude:  https://api.anthropic.com/v1/messages
 * Gemini:  https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}
 *
 * Bewust geen multi-fallback-logica (dat is AIMI's complexiteit voor
 * per-cyclus-aanroepen) — 1x/dag, dus één provider volstaat.
 *
 * API-sleutel/model komen uit FclAiAdvisorSettingsStore (eigen,
 * AAPS-onafhankelijke opslag — nooit uit app.aaps.core.keys).
 */
object FclAiAdvisorService {

    private const val CLAUDE_URL        = "https://api.anthropic.com/v1/messages"
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val GEMINI_BASE_URL   = "https://generativelanguage.googleapis.com/v1beta/models"

    private const val MAX_TOKENS_CLAUDE = 1500
    // Gemini knipt bij 1500 tokens een JSON-response af halverwege ("End of input at character 18").
    // Groter budget voorkomt dat; gratis tier ondersteunt 8192 output-tokens.
    private const val MAX_TOKENS_GEMINI = 4096
    private const val TIMEOUT_MS = 30_000

    sealed class Result {
        data class Success(val rawText: String) : Result()
        // httpCode (28/07/2026): los veld i.p.v. de tekst parsen, zodat
        // callAdvisor() een 503 betrouwbaar kan onderscheiden van andere
        // fouten (verkeerde/verlopen key, timeout, ...) — zie hieronder.
        // Blijft null bij een lokale fout (timeout/verbindingsfout) waar
        // geen HTTP-respons is ontvangen.
        data class Failure(val reasonNl: String, val httpCode: Int? = null) : Result()
    }

    /**
     * Synchrone aanroep — altijd op een achtergrond-thread.
     * Probeert keys in volgorde; bij een fout (HTTP 4xx/5xx, timeout) wordt
     * de volgende key geprobeerd. Lege keys worden overgeslagen.
     *
     * UITZONDERING — HTTP 503 (28/07/2026, de gebruiker: "is het een optie om bij
     * een 503 error de 2de key niet te gebruiken"): een 503 betekent dat
     * Google's servers tijdelijk overbelast zijn, niet dat déze key ongeldig
     * of leeg is. De 2e key aanroepen lost dat niet op (dezelfde
     * overbelaste dienst) en verdubbelt alleen het aantal requests dat
     * mogelijk tegen de dag-quota meetelt. Bij een 503 wordt daarom meteen
     * gestopt — geen 2e key geprobeerd — en stuurt FclAiAdvisorScheduler
     * de retry na RETRY_INTERVAL naar dezelfde eerste key.
     */
    fun callAdvisor(
        provider: FclAiAdvisorSettingsStore.Provider,
        apiKeys: List<String>,
        prompt: String,
        model: String
    ): Result {
        if (apiKeys.isEmpty()) return Result.Failure("Geen API-sleutel ingesteld")
        var lastError = "Onbekende fout"
        for (key in apiKeys) {
            if (key.isBlank()) continue
            val result = try {
                when (provider) {
                    FclAiAdvisorSettingsStore.Provider.CLAUDE -> callClaude(key, prompt, model)
                    FclAiAdvisorSettingsStore.Provider.GEMINI -> callGemini(key, prompt, model)
                }
            } catch (t: Throwable) {
                Result.Failure("Verbindingsfout (${provider.name}): ${t.message}")
            }
            when (result) {
                is Result.Success -> return result
                is Result.Failure -> {
                    lastError = result.reasonNl
                    if (result.httpCode == 503) return result  // niet naar 2e key, zie kdoc
                    // anders: probeer volgende key
                }
            }
        }
        return Result.Failure(lastError)
    }

    // ── Claude ───────────────────────────────────────────────────────────────

    private fun callClaude(apiKey: String, prompt: String, model: String): Result {
        val connection = (URL(CLAUDE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", MAX_TOKENS_CLAUDE)
            put("messages", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }
            ))
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            val err = readStream(connection.errorStream)
            return Result.Failure("Claude HTTP $code: $err", httpCode = code)
        }
        val resp = JSONObject(readStream(connection.inputStream))
        val content = resp.optJSONArray("content") ?: return Result.Failure("Geen 'content' in Claude-antwoord")
        val text = (0 until content.length())
            .mapNotNull { content.optJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .joinToString("") { it.optString("text", "") }
        return if (text.isBlank()) Result.Failure("Leeg antwoord van Claude")
        else Result.Success(text)
    }

    // ── Gemini ───────────────────────────────────────────────────────────────

    private fun callGemini(apiKey: String, prompt: String, model: String): Result {
        val url = "$GEMINI_BASE_URL/$model:generateContent?key=$apiKey"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        val body = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(
                        JSONObject().apply { put("text", prompt) }
                    ))
                }
            ))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("maxOutputTokens", MAX_TOKENS_GEMINI)
                put("responseMimeType", "application/json")
            })
        }
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            val err = readStream(connection.errorStream)
            return Result.Failure("Gemini HTTP $code: $err", httpCode = code)
        }
        val resp = JSONObject(readStream(connection.inputStream))
        // Gemini: candidates[0].content.parts[0].text
        val text = resp.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text", "") ?: ""
        return if (text.isBlank()) Result.Failure("Leeg antwoord van Gemini")
        else Result.Success(text)
    }

    // ── Hulp ─────────────────────────────────────────────────────────────────

    private fun readStream(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }
}