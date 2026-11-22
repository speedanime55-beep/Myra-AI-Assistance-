package com.example.ai

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.model.MyraPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeminiLiveClient(private val context: Context) {

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val WS_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        private const val SESSION_RENEW_AFTER_MS = 540_000L // 9 minutes
        private const val KEEPALIVE_INTERVAL_MS = 8_000L    // 8 seconds
        private const val RECONNECT_DELAY_MS = 3_000L       // 3 seconds
        private const val SILENT_PCM_BYTES = 1024
    }

    var onConnected: (() -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null
    var onAudioReceived: ((ByteArray) -> Unit)? = null
    var onInputTranscript: ((String) -> Unit)? = null
    var onOutputTranscript: ((String) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onInterrupted: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null
    private val isSetupComplete = AtomicBoolean(false)
    private val shouldReconnect = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)

    private var keepAliveJob: Job? = null
    private var sessionRenewJob: Job? = null
    private var reconnectJob: Job? = null
    private val silentChunkBase64: String =
        Base64.encodeToString(ByteArray(SILENT_PCM_BYTES), Base64.NO_WRAP)

    fun isReady(): Boolean = isSetupComplete.get() && webSocket != null

    fun connect() {
        val apiKey = MyraPrefs.getApiKey(context)
        if (apiKey.isBlank()) {
            onError?.invoke("Missing Gemini API Key. Open Settings to configure.")
            return
        }
        shouldReconnect.set(true)
        reconnectJob?.cancel()
        openSocket(apiKey)
    }

    private fun openSocket(apiKey: String) {
        if (!isConnecting.compareAndSet(false, true)) return
        closeCurrentSocketOnly()

        val url = "$WS_ENDPOINT?key=$apiKey"
        val request = Request.Builder().url(url).build()

        Log.d(TAG, "Opening Gemini Live WebSocket...")
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened, sending setup payload")
                isConnecting.set(false)
                sendSetupMessage(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerPayload(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleServerPayload(bytes.utf8())
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                handleSocketLost("Disconnected ($code)")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val httpCode = response?.code
                val msg = if (httpCode != null) {
                    "Connection failed (HTTP $httpCode): ${t.message ?: "Unknown error"}"
                } else {
                    "Connection failed: ${t.message ?: "Network error"}"
                }
                Log.e(TAG, msg, t)
                isConnecting.set(false)
                onError?.invoke(msg)
                handleSocketLost(msg)
            }
        })
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val model = MyraPrefs.getModel(context)
        val voice = MyraPrefs.getVoice(context)
        val systemPrompt = MyraPrefs.buildSystemPrompt(context)

        val setupJson = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", model)
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", systemPrompt))
                    })
                })
                put("generation_config", JSONObject().apply {
                    put("response_modalities", JSONArray().apply {
                        put("AUDIO")
                    })
                    put("speech_config", JSONObject().apply {
                        put("voice_config", JSONObject().apply {
                            put("prebuilt_voice_config", JSONObject().apply {
                                put("voice_name", voice)
                            })
                        })
                    })
                    put("temperature", 0.9)
                })
                put("output_audio_transcription", JSONObject())
                put("input_audio_transcription", JSONObject())
            })
        }

        val sent = ws.send(setupJson.toString())
        Log.d(TAG, "Setup message sent=$sent model=$model voice=$voice")
    }

    private fun handleServerPayload(raw: String) {
        try {
            val json = JSONObject(raw)

            if (json.has("setupComplete")) {
                if (isSetupComplete.compareAndSet(false, true)) {
                    Log.d(TAG, "Gemini Live setupComplete received")
                    startKeepAliveLoop()
                    startSessionRenewTimer()
                    onConnected?.invoke()
                }
                return
            }

            val serverContent = json.optJSONObject("serverContent") ?: return

            if (serverContent.optBoolean("interrupted", false)) {
                onInterrupted?.invoke()
            }

            // Parse input audio transcription (what user said)
            serverContent.optJSONObject("inputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotEmpty() }
                ?.let { text -> onInputTranscript?.invoke(text) }

            // Parse output audio transcription (what MYRA said)
            serverContent.optJSONObject("outputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotEmpty() }
                ?.let { text -> onOutputTranscript?.invoke(text) }

            // Parse PCM 24kHz audio parts
            val modelTurn = serverContent.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val inlineData = part.optJSONObject("inlineData")
                    val b64 = inlineData?.optString("data")
                    if (!b64.isNullOrEmpty()) {
                        val pcm = Base64.decode(b64, Base64.DEFAULT)
                        if (pcm.isNotEmpty()) {
                            onAudioReceived?.invoke(pcm)
                        }
                    }
                }
            }

            // Flush transcripts when turn completes
            if (serverContent.optBoolean("turnComplete", false)) {
                onTurnComplete?.invoke()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server payload", e)
        }
    }

    fun sendAudio(pcmBytes: ByteArray) {
        val ws = webSocket ?: return
        if (!isSetupComplete.get() || pcmBytes.isEmpty()) return
        val encoded = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        sendEncodedPcm(ws, encoded)
    }

    private fun sendEncodedPcm(ws: WebSocket, base64Pcm: String) {
        val payload = JSONObject().apply {
            put("realtime_input", JSONObject().apply {
                put("media_chunks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("mime_type", "audio/pcm;rate=16000")
                        put("data", base64Pcm)
                    })
                })
            })
        }
        ws.send(payload.toString())
    }

    fun sendText(message: String) {
        val ws = webSocket ?: return
        if (!isSetupComplete.get() || message.isBlank()) return
        val payload = JSONObject().apply {
            put("client_content", JSONObject().apply {
                put("turns", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", message))
                        })
                    })
                })
                put("turn_complete", true)
            })
        }
        ws.send(payload.toString())
    }

    fun sendInterrupt() {
        val ws = webSocket ?: return
        if (!isSetupComplete.get()) return
        val payload = JSONObject().apply {
            put("client_content", JSONObject().apply {
                put("turns", JSONArray())
                put("turn_complete", true)
            })
        }
        ws.send(payload.toString())
    }

    private fun startKeepAliveLoop() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive && isSetupComplete.get()) {
                delay(KEEPALIVE_INTERVAL_MS)
                val ws = webSocket
                if (ws != null && isSetupComplete.get()) {
                    sendEncodedPcm(ws, silentChunkBase64)
                }
            }
        }
    }

    private fun startSessionRenewTimer() {
        sessionRenewJob?.cancel()
        sessionRenewJob = scope.launch {
            delay(SESSION_RENEW_AFTER_MS)
            if (isActive && shouldReconnect.get()) {
                Log.d(TAG, "Renewing Gemini Live session after 9 minutes")
                val apiKey = MyraPrefs.getApiKey(context)
                if (apiKey.isNotBlank()) {
                    isSetupComplete.set(false)
                    keepAliveJob?.cancel()
                    closeCurrentSocketOnly()
                    openSocket(apiKey)
                }
            }
        }
    }

    private fun handleSocketLost(reason: String) {
        isConnecting.set(false)
        val wasConnected = isSetupComplete.getAndSet(false)
        keepAliveJob?.cancel()
        sessionRenewJob?.cancel()
        webSocket = null

        if (wasConnected) {
            onDisconnected?.invoke(reason)
        }

        if (shouldReconnect.get()) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (!isActive || !shouldReconnect.get()) return@launch
            val apiKey = MyraPrefs.getApiKey(context)
            if (apiKey.isNotBlank()) {
                Log.d(TAG, "Auto-reconnecting Gemini Live WebSocket...")
                openSocket(apiKey)
            }
        }
    }

    private fun closeCurrentSocketOnly() {
        try {
            webSocket?.close(1000, "Resetting session")
        } catch (_: Exception) {
        }
        webSocket = null
    }

    fun disconnect() {
        shouldReconnect.set(false)
        isSetupComplete.set(false)
        isConnecting.set(false)
        keepAliveJob?.cancel()
        sessionRenewJob?.cancel()
        reconnectJob?.cancel()
        closeCurrentSocketOnly()
    }

    fun release() {
        disconnect()
        scope.cancel()
    }
}
