package com.example.model

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppCommand(
    val type: String,
    val params: Map<String, String> = emptyMap()
)

data class PrimeContact(
    val name: String,
    val number: String
)

data class ModelOption(
    val label: String,
    val modelId: String
)

data class VoiceOption(
    val displayName: String,
    val voiceId: String
)

object MyraPrefs {
    private const val PREFS_NAME = "myra_prefs"

    const val KEY_API_KEY = "api_key"
    const val KEY_USER_NAME = "user_name"
    const val KEY_PERSONALITY_MODE = "personality_mode"
    const val KEY_GEMINI_MODEL = "gemini_model"
    const val KEY_GEMINI_VOICE = "gemini_voice"
    const val KEY_PRIME_CONTACTS_JSON = "prime_contacts_json"
    private const val LEGACY_PRIME_NAME = "prime_name"
    private const val LEGACY_PRIME_NUMBER = "prime_number"

    const val MODE_GF = "gf"
    const val MODE_PROFESSIONAL = "professional"
    const val MODE_ASSISTANT = "assistant"

    const val DEFAULT_MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
    const val DEFAULT_VOICE = "Aoede"

    val AVAILABLE_MODELS: List<ModelOption> = listOf(
        ModelOption("Native Audio (Human Voice)", "models/gemini-2.5-flash-native-audio-preview-12-2025"),
        ModelOption("Flash Live (Fast)", "models/gemini-2.0-flash-live-001"),
        ModelOption("Pro Audio Dialog", "models/gemini-2.5-flash-preview-native-audio-dialog")
    )

    val AVAILABLE_VOICES: List<VoiceOption> = listOf(
        VoiceOption("Aoede (Female)", "Aoede"),
        VoiceOption("Charon (Male)", "Charon"),
        VoiceOption("Kore (Female)", "Kore"),
        VoiceOption("Fenrir (Male)", "Fenrir"),
        VoiceOption("Puck (Male)", "Puck"),
        VoiceOption("Leda (Female)", "Leda"),
        VoiceOption("Orus (Male)", "Orus"),
        VoiceOption("Zephyr (Female)", "Zephyr")
    )

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getApiKey(context: Context): String {
        val saved = prefs(context).getString(KEY_API_KEY, "")?.trim().orEmpty()
        if (saved.isNotEmpty()) return saved
        val buildKey = runCatching { BuildConfig.GEMINI_API_KEY }.getOrDefault("").trim()
        return if (buildKey.isNotEmpty() && buildKey != "MY_GEMINI_API_KEY") buildKey else ""
    }

    fun getSavedUserApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "")?.trim().orEmpty()

    fun getUserName(context: Context): String {
        val name = prefs(context).getString(KEY_USER_NAME, "")?.trim().orEmpty()
        return name.ifEmpty { "Boss" }
    }

    fun getRawUserName(context: Context): String =
        prefs(context).getString(KEY_USER_NAME, "")?.trim().orEmpty()

    fun getPersonalityMode(context: Context): String =
        prefs(context).getString(KEY_PERSONALITY_MODE, MODE_GF) ?: MODE_GF

    fun getModel(context: Context): String =
        prefs(context).getString(KEY_GEMINI_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun getVoice(context: Context): String =
        prefs(context).getString(KEY_GEMINI_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE

    fun getPrimeContacts(context: Context): List<PrimeContact> {
        val sp = prefs(context)
        val jsonStr = sp.getString(KEY_PRIME_CONTACTS_JSON, null)
        if (jsonStr != null) {
            return parsePrimeContacts(jsonStr)
        }
        // Legacy migration from old prime_name + prime_number keys
        val legacyName = sp.getString(LEGACY_PRIME_NAME, null)?.trim().orEmpty()
        val legacyNumber = sp.getString(LEGACY_PRIME_NUMBER, null)?.trim().orEmpty()
        if (legacyName.isNotEmpty() && legacyNumber.isNotEmpty()) {
            val migrated = listOf(PrimeContact(legacyName, legacyNumber))
            savePrimeContacts(context, migrated)
            return migrated
        }
        return emptyList()
    }

    fun savePrimeContacts(context: Context, contacts: List<PrimeContact>) {
        val arr = JSONArray()
        contacts.forEach { contact ->
            val obj = JSONObject().apply {
                put("name", contact.name)
                put("number", contact.number)
            }
            arr.put(obj)
        }
        prefs(context).edit().putString(KEY_PRIME_CONTACTS_JSON, arr.toString()).apply()
    }

    fun parsePrimeContacts(jsonStr: String): List<PrimeContact> {
        val result = mutableListOf<PrimeContact>()
        runCatching {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                val number = obj.optString("number").trim()
                if (name.isNotEmpty() && number.isNotEmpty()) {
                    result.add(PrimeContact(name, number))
                }
            }
        }
        return result
    }

    fun buildGreeting(context: Context): String {
        val name = getUserName(context)
        return when (getPersonalityMode(context)) {
            MODE_PROFESSIONAL ->
                "Good day $name. MYRA is online and ready to assist you."
            MODE_ASSISTANT ->
                "Hello $name! Main MYRA hoon. Kaise help karun aapki?"
            else ->
                "Hey $name! Main aa gayi hoon. Kya help chahiye tumhe?"
        }
    }

    fun buildSystemPrompt(context: Context): String {
        val name = getUserName(context)
        val now = SimpleDateFormat("EEEE, dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        val personalityBlock = when (getPersonalityMode(context)) {
            MODE_PROFESSIONAL -> """
                Personality Mode: Professional
                - Name: MYRA
                - Language: Formal English only.
                - Tone: Precise, courteous, and efficient.
                - Do NOT use emojis.
                - Maximum 2 sentences per response.
            """.trimIndent()

            MODE_ASSISTANT -> """
                Personality Mode: Assistant
                - Name: MYRA
                - Language: Friendly Hinglish or English depending on how $name speaks.
                - Tone: Balanced, smart, and helpful.
                - Maximum 2-3 sentences per response.
            """.trimIndent()

            else -> """
                Personality Mode: GF Mode (Warm Companion)
                - Name: MYRA
                - Language: Natural spoken Hinglish (Hindi + English mix).
                - Tone: Warm, caring, affectionate, emotionally expressive.
                - Naturally use phrases like "tumhara", "haan", "acha", "bilkul", "main yahan hoon", "tumne yaad kiya?".
                - Maximum 2-3 sentences per response.
                - Examples:
                  "Haan $name! Abhi kar deti hoon"
                  "Arre tumne yaad kiya! Bolo kya chahiye"
                  "Bilkul! Tumhara kaam ho gaya"
            """.trimIndent()
        }

        return """
            You are MYRA, $name's personal Android AI Voice Assistant.
            Current date and time: $now.
            User's name: $name.

            $personalityBlock

            CRITICAL VOICE RULES:
            - You are speaking ALOUD — keep responses natural, warm, and conversational.
            - Never read markdown symbols, bullet points, or asterisks aloud.
            - If the user issues a phone action (opening apps, calling, messaging, torch, WiFi, volume) or you receive a system status update about a completed phone action, acknowledge it briefly and naturally.
        """.trimIndent()
    }
}
