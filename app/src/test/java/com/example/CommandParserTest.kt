package com.example

import com.example.ai.CommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CommandParserTest {

    @Test
    fun parsesOpenAndCloseAppsInHinglishAndEnglish() {
        val openYoutube = CommandParser.parse("YouTube kholo")
        assertNotNull(openYoutube)
        assertEquals("OPEN_APP", openYoutube?.type)
        assertEquals("youtube", openYoutube?.params?.get("app_name"))

        val openInstagram = CommandParser.parse("open Instagram")
        assertEquals("OPEN_APP", openInstagram?.type)
        assertEquals("instagram", openInstagram?.params?.get("app_name"))

        val closeWhatsapp = CommandParser.parse("WhatsApp band karo")
        assertEquals("CLOSE_APP", closeWhatsapp?.type)
        assertEquals("whatsapp", closeWhatsapp?.params?.get("app_name"))
    }

    @Test
    fun parsesPrimeContactsAndDirectCalls() {
        val primeFriend = CommandParser.parse("mere close friend ko call karo")
        assertEquals("PRIME_CALL", primeFriend?.type)
        assertEquals("0", primeFriend?.params?.get("index"))

        val primeSecond = CommandParser.parse("call my second contact")
        assertEquals("PRIME_CALL", primeSecond?.type)
        assertEquals("1", primeSecond?.params?.get("index"))

        val primeMsg = CommandParser.parse("meri jaan ko msg karo")
        assertEquals("PRIME_MSG", primeMsg?.type)
        assertEquals("0", primeMsg?.params?.get("index"))

        val directCall = CommandParser.parse("Rahul ko call karo")
        assertEquals("CALL", directCall?.type)
        assertEquals("rahul", directCall?.params?.get("name"))
    }

    @Test
    fun parsesDeviceControlsAndConversationalFallback() {
        assertEquals("VOLUME_UP", CommandParser.parse("volume badhao")?.type)
        assertEquals("VOLUME_DOWN", CommandParser.parse("volume down")?.type)
        assertEquals("FLASHLIGHT_ON", CommandParser.parse("torch on karo")?.type)
        assertEquals("FLASHLIGHT_OFF", CommandParser.parse("flashlight off")?.type)
        assertEquals("WIFI_ON", CommandParser.parse("WiFi on karo")?.type)
        assertEquals("BLUETOOTH_OFF", CommandParser.parse("Bluetooth off")?.type)
        assertNull(CommandParser.parse("Aaj mausam kaisa hai?"))
    }
}
