package com.example.ai

import com.example.model.AppCommand

object CommandParser {

    fun parse(rawText: String): AppCommand? {
        val text = rawText.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase().replace(Regex("[^a-z0-9\\s+]"), " ").replace(Regex("\\s+"), " ").trim()

        // 1. Prime Contacts — Call
        parsePrimeCall(lower)?.let { return it }

        // 2. Prime Contacts — Message
        parsePrimeMessage(lower)?.let { return it }

        // 3. Volume controls
        if (lower.contains("volume up") || lower.contains("volume badha") ||
            lower.contains("awaaz badha") || lower.contains("awaz badha") ||
            lower.contains("increase volume") || lower.contains("sound badha")
        ) {
            return AppCommand("VOLUME_UP")
        }
        if (lower.contains("volume down") || lower.contains("volume kam") ||
            lower.contains("awaaz kam") || lower.contains("awaz kam") ||
            lower.contains("decrease volume") || lower.contains("lower volume") ||
            lower.contains("sound kam")
        ) {
            return AppCommand("VOLUME_DOWN")
        }

        // 4. Flashlight / Torch
        if ((lower.contains("torch") || lower.contains("flashlight") || lower.contains("flash light")) &&
            (lower.contains("on") || lower.contains("chalu") || lower.contains("jala") || lower.contains("start"))
        ) {
            return AppCommand("FLASHLIGHT_ON")
        }
        if ((lower.contains("torch") || lower.contains("flashlight") || lower.contains("flash light")) &&
            (lower.contains("off") || lower.contains("band") || lower.contains("stop") || lower.contains("bujha"))
        ) {
            return AppCommand("FLASHLIGHT_OFF")
        }

        // 5. WiFi
        if ((lower.contains("wifi") || lower.contains("wi fi")) &&
            (lower.contains("on") || lower.contains("chalu") || lower.contains("enable") || lower.contains("start"))
        ) {
            return AppCommand("WIFI_ON")
        }
        if ((lower.contains("wifi") || lower.contains("wi fi")) &&
            (lower.contains("off") || lower.contains("band") || lower.contains("disable") || lower.contains("stop"))
        ) {
            return AppCommand("WIFI_OFF")
        }

        // 6. Bluetooth
        if (lower.contains("bluetooth") &&
            (lower.contains("on") || lower.contains("chalu") || lower.contains("enable") || lower.contains("start"))
        ) {
            return AppCommand("BLUETOOTH_ON")
        }
        if (lower.contains("bluetooth") &&
            (lower.contains("off") || lower.contains("band") || lower.contains("disable") || lower.contains("stop"))
        ) {
            return AppCommand("BLUETOOTH_OFF")
        }

        // 7. Close App
        val closeMatch = Regex("^(close|band karo)\\s+(.+)$").find(lower)
            ?: Regex("^(.+?)\\s+(band karo|band kar do|close karo|close kar do)$").find(lower)
        if (closeMatch != null) {
            val target = if (closeMatch.groupValues[1] in setOf("close", "band karo")) {
                closeMatch.groupValues[2].trim()
            } else {
                closeMatch.groupValues[1].trim()
            }
            if (target.isNotEmpty() && !target.contains("torch") && !target.contains("wifi") && !target.contains("bluetooth")) {
                return AppCommand("CLOSE_APP", mapOf("app_name" to target))
            }
        }
        if (lower == "close app" || lower == "app band karo" || lower == "go home" || lower == "home jao") {
            return AppCommand("CLOSE_APP")
        }

        // 8. WhatsApp Call / Message
        if (lower.contains("whatsapp")) {
            if (lower.contains("call")) {
                val target = extractTargetName(lower, listOf("whatsapp call", "call on whatsapp", "whatsapp pe call", "ko whatsapp call karo", "whatsapp", "call", "karo", "lagao", "ko", "to"))
                if (target.isNotEmpty()) {
                    return AppCommand("WHATSAPP_CALL", mapOf("name" to target))
                }
            } else if (lower.contains("msg") || lower.contains("message") || lower.contains("bhejo") || lower.contains("send") || lower.contains("karo")) {
                if (!lower.contains("open") && !lower.contains("kholo") && !lower.contains("khol") && !lower.contains("band")) {
                    val parsed = parseWhatsAppMessage(lower)
                    if (parsed != null) return parsed
                }
            }
        }

        // 9. SMS
        if (lower.contains("sms") || lower.startsWith("send message") || lower.startsWith("message bhejo") || lower.startsWith("msg bhejo")) {
            val parsed = parseSms(lower)
            if (parsed != null) return parsed
        }

        // 10. Open App ("YouTube kholo", "open YouTube", "Instagram chalao")
        val openPrefix = Regex("^(?:open|launch|start|kholo|khol do|chalao)\\s+(.+)$").find(lower)
        if (openPrefix != null) {
            val appName = cleanAppName(openPrefix.groupValues[1])
            if (appName.isNotEmpty()) {
                return AppCommand("OPEN_APP", mapOf("app_name" to appName))
            }
        }
        val openSuffix = Regex("^(.+?)\\s+(?:kholo|khol do|kholna|open karo|open kar do|chalao|chala do)$").find(lower)
        if (openSuffix != null) {
            val appName = cleanAppName(openSuffix.groupValues[1])
            if (appName.isNotEmpty()) {
                return AppCommand("OPEN_APP", mapOf("app_name" to appName))
            }
        }

        // 11. Phone Call ("[name] ko call karo" / "call [name]")
        val callSuffix = Regex("^(.+?)\\s+(?:ko\\s+)?(?:call karo|call kar do|call lagao|phone karo|phone lagao|ring karo)$").find(lower)
        if (callSuffix != null) {
            val target = callSuffix.groupValues[1].trim()
            if (target.isNotEmpty()) {
                return AppCommand("CALL", mapOf("name" to target))
            }
        }
        val callPrefix = Regex("^(?:call|dial|phone|ring)\\s+(?:to\\s+)?(.+)$").find(lower)
        if (callPrefix != null) {
            val target = callPrefix.groupValues[1].removeSuffix(" please").trim()
            if (target.isNotEmpty()) {
                return AppCommand("CALL", mapOf("name" to target))
            }
        }

        return null
    }

    private fun parsePrimeCall(lower: String): AppCommand? {
        val isCall = lower.contains("call") || lower.contains("phone") || lower.contains("dial")
        if (!isCall) return null

        val index = extractPrimeIndex(lower) ?: return null
        return AppCommand("PRIME_CALL", mapOf("index" to index.toString()))
    }

    private fun parsePrimeMessage(lower: String): AppCommand? {
        val isMsg = lower.contains("message") || lower.contains("msg") || lower.contains("text") || lower.contains("sms")
        if (!isMsg) return null

        val index = extractPrimeIndex(lower) ?: return null
        val msg = extractMessageBody(lower)
        return AppCommand("PRIME_MSG", mapOf("index" to index.toString(), "message" to msg))
    }

    private fun extractPrimeIndex(lower: String): Int? {
        return when {
            lower.contains("third contact") || lower.contains("third prime") || lower.contains("3rd contact") -> 2
            lower.contains("second contact") || lower.contains("second prime") || lower.contains("2nd contact") ||
                lower.contains("dusre contact") || lower.contains("doosre contact") -> 1
            lower.contains("first contact") || lower.contains("first prime") || lower.contains("1st contact") ||
                lower.contains("close friend") || lower.contains("best friend") ||
                lower.contains("my love") || lower.contains("meri jaan") ||
                lower.contains("prime contact") || lower.contains("special person") ||
                lower.contains("gf") || lower.contains("girlfriend") || lower.contains("boyfriend") -> 0
            else -> null
        }
    }

    private fun parseSms(lower: String): AppCommand? {
        // e.g. "sms bhejo rahul ko hello kaise ho" or "send sms to rahul saying hello"
        val hinglish = Regex("(?:sms|message|msg)\\s+(?:bhejo|send karo|karo)?\\s*(.+?)\\s+ko(?:\\s+(.+))?$").find(lower)
        if (hinglish != null) {
            val name = hinglish.groupValues[1].trim()
            val body = hinglish.groupValues[2].trim()
            if (name.isNotEmpty()) {
                return AppCommand("SMS", mapOf("name" to name, "message" to body))
            }
        }
        val english = Regex("(?:send\\s+)?(?:sms|message|msg)\\s+to\\s+(.+?)(?:\\s+(?:saying|that|:)\\s+(.+))?$").find(lower)
        if (english != null) {
            val name = english.groupValues[1].trim()
            val body = english.groupValues[2].trim()
            if (name.isNotEmpty()) {
                return AppCommand("SMS", mapOf("name" to name, "message" to body))
            }
        }
        return null
    }

    private fun parseWhatsAppMessage(lower: String): AppCommand? {
        val hinglish = Regex("whatsapp\\s+(?:msg|message)?\\s*(?:karo|bhejo|send karo)?\\s*(.+?)\\s+ko(?:\\s+(.+))?$").find(lower)
        if (hinglish != null) {
            val name = hinglish.groupValues[1].trim()
            val body = hinglish.groupValues[2].trim()
            if (name.isNotEmpty()) {
                return AppCommand("WHATSAPP_MSG", mapOf("name" to name, "message" to body))
            }
        }
        val english = Regex("(?:send\\s+)?whatsapp\\s+(?:message|msg)?\\s*to\\s+(.+?)(?:\\s+(?:saying|that)\\s+(.+))?$").find(lower)
        if (english != null) {
            val name = english.groupValues[1].trim()
            val body = english.groupValues[2].trim()
            if (name.isNotEmpty()) {
                return AppCommand("WHATSAPP_MSG", mapOf("name" to name, "message" to body))
            }
        }
        val shortForm = Regex("^(.+?)\\s+ko\\s+whatsapp\\s+(?:karo|bhejo|message karo|msg karo)(?:\\s+(.+))?$").find(lower)
        if (shortForm != null) {
            val name = shortForm.groupValues[1].trim()
            val body = shortForm.groupValues[2].trim()
            if (name.isNotEmpty()) {
                return AppCommand("WHATSAPP_MSG", mapOf("name" to name, "message" to body))
            }
        }
        return null
    }

    private fun extractMessageBody(lower: String): String {
        val markers = listOf(" saying ", " that ", " ki ", " likho ", " bolo ")
        for (marker in markers) {
            val idx = lower.indexOf(marker)
            if (idx != -1) {
                return lower.substring(idx + marker.length).trim()
            }
        }
        return ""
    }

    private fun extractTargetName(lower: String, stopWords: List<String>): String {
        var cleaned = lower
        for (word in stopWords) {
            cleaned = cleaned.replace(Regex("\\b${Regex.escape(word)}\\b"), " ")
        }
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    private fun cleanAppName(raw: String): String {
        return raw.replace(Regex("\\b(app|application|please|myra|zara|abhi)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
