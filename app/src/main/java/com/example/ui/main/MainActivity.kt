package com.example.ui.main

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.R
import com.example.ai.AudioEngine
import com.example.ai.CommandParser
import com.example.ai.GeminiLiveClient
import com.example.databinding.ActivityMainBinding
import com.example.model.MyraPrefs
import com.example.service.CallMonitorService
import com.example.service.MyraOverlayService
import com.example.ui.settings.SettingsActivity
import com.example.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val STATUS_UPDATE_INTERVAL_MS = 30_000L
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var chatAdapter: ChatAdapter
    private var geminiLive: GeminiLiveClient? = null
    private var audioEngine: AudioEngine? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val inputBuffer = StringBuilder()
    private val outputBuffer = StringBuilder()

    private var isMuted = false
    private var isInCallMode = false
    private var hasGreeted = false
    private var lastConfigFingerprint: String = ""

    private val statusUpdateRunnable = object : Runnable {
        override fun run() {
            updateSystemHud()
            mainHandler.postDelayed(this, STATUS_UPDATE_INTERVAL_MS)
        }
    }

    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CallMonitorService.ACTION_CALL_ENDED) {
                isInCallMode = false
                audioEngine?.setCallMode(false)
                stopCallSpeechRecognizer()
                setActiveMode(false)
                updateStatusText(getString(R.string.status_listening))
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            hasPermission(Manifest.permission.RECORD_AUDIO)
        if (micGranted) {
            startSystemServices()
            if (geminiLive?.isReady() == true) {
                audioEngine?.startRecording()
            }
        }
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val currentFingerprint = buildConfigFingerprint()
        if (currentFingerprint != lastConfigFingerprint) {
            lastConfigFingerprint = currentFingerprint
            hasGreeted = false
            reconnectGeminiLive()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lastConfigFingerprint = buildConfigFingerprint()
        applyWindowInsets()
        initViews()
        observeViewModel()
        checkPermissions()
        startSystemServices()
        startStatusUpdates()
        registerCallEndedReceiver()

        mainHandler.postDelayed({ initGeminiLive() }, 300L)
        handleIncomingCallIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingCallIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateSystemHud()
        if (!isMuted && !isInCallMode) {
            audioEngine?.setMuted(false)
        }
    }

    override fun onPause() {
        super.onPause()
        audioEngine?.setMuted(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        stopCallSpeechRecognizer()
        geminiLive?.release()
        audioEngine?.release()
        try {
            unregisterReceiver(callEndedReceiver)
        } catch (_: Exception) {
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            val horizontalPad = (16 * resources.displayMetrics.density).toInt()
            view.setPadding(
                horizontalPad + bars.left,
                bars.top,
                horizontalPad + bars.right,
                bars.bottom
            )
            insets
        }
    }

    private fun initViews() {
        chatAdapter = ChatAdapter()
        val lm = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.chatRecycler.layoutManager = lm
        binding.chatRecycler.adapter = chatAdapter

        binding.settingsBtn.setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        binding.orbView.setOnClickListener {
            if (MyraPrefs.getApiKey(this).isBlank()) {
                settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
            } else {
                toggleMute()
            }
        }

        binding.micButton.setOnClickListener {
            if (MyraPrefs.getApiKey(this).isBlank()) {
                Toast.makeText(this, R.string.status_no_api_key, Toast.LENGTH_SHORT).show()
                settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
                return@setOnClickListener
            }
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                checkPermissions()
                return@setOnClickListener
            }
            toggleMute()
        }

        binding.micButton.setOnLongClickListener {
            interruptMyraSpeaking()
            true
        }

        binding.sendBtn.setOnClickListener {
            submitTypedMessage()
        }

        binding.messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitTypedMessage()
                true
            } else {
                false
            }
        }

        updateMicVisuals()
    }

    private fun observeViewModel() {
        viewModel.commandResult.observe(this) { result ->
            if (!result.isNullOrBlank()) {
                viewModel.clearCommandResult()
                if (geminiLive?.isReady() == true) {
                    geminiLive?.sendText("System update for MYRA: $result Confirm briefly to the user.")
                } else {
                    addChatMessage(ChatMessage(result, isUser = false))
                }
            }
        }
    }

    private fun submitTypedMessage() {
        val text = binding.messageInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.messageInput.setText("")

        addChatMessage(ChatMessage(text, isUser = true))

        // Check local phone commands first
        val cmd = CommandParser.parse(text)
        if (cmd != null) {
            viewModel.executeCommand(cmd)
        }

        if (geminiLive?.isReady() == true) {
            binding.orbView.setOrbState(OrbAnimationView.OrbState.THINKING)
            updateStatusText(getString(R.string.status_thinking))
            geminiLive?.sendText(text)
        } else if (cmd == null) {
            val apiKey = MyraPrefs.getApiKey(this)
            if (apiKey.isBlank()) {
                addChatMessage(
                    ChatMessage(
                        "Please add your Gemini API key in Settings so I can reply live!",
                        isUser = false
                    )
                )
            } else {
                addChatMessage(
                    ChatMessage(
                        "Connecting to Gemini Live right now… please try again in a moment.",
                        isUser = false
                    )
                )
                geminiLive?.connect()
            }
        }
    }

    private fun checkPermissions() {
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            needed.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = needed.filter { !hasPermission(it) }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun startSystemServices() {
        if (hasPermission(Manifest.permission.RECORD_AUDIO) && !MyraOverlayService.isRunning) {
            startForegroundServiceSafely(Intent(this, MyraOverlayService::class.java))
        }
        if (hasPermission(Manifest.permission.READ_PHONE_STATE) && !CallMonitorService.isRunning) {
            startForegroundServiceSafely(Intent(this, CallMonitorService::class.java))
        }
    }

    private fun startForegroundServiceSafely(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Deferred starting service ${intent.component?.className}", e)
        }
    }

    private fun startStatusUpdates() {
        mainHandler.removeCallbacks(statusUpdateRunnable)
        mainHandler.post(statusUpdateRunnable)
    }

    private fun updateSystemHud() {
        // Battery %
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        binding.batteryText.text = if (pct in 0..100) "$pct%" else "--%"

        // RAM usage
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val availGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
            val totalGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
            binding.ramText.text = String.format(Locale.US, "RAM %.1f/%.1fG", availGb, totalGb)
        }

        // Current Time
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        binding.timeText.text = timeFmt.format(Date())
    }

    private fun registerCallEndedReceiver() {
        val filter = IntentFilter(CallMonitorService.ACTION_CALL_ENDED)
        ContextCompat.registerReceiver(
            this,
            callEndedReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun initGeminiLive() {
        val apiKey = MyraPrefs.getApiKey(this)
        if (apiKey.isBlank()) {
            binding.orbView.setOrbState(OrbAnimationView.OrbState.IDLE)
            binding.waveformView.stopAnimation()
            updateStatusText(getString(R.string.status_no_api_key))
            return
        }

        val engine = AudioEngine(this).also { audioEngine = it }
        val client = GeminiLiveClient(this).also { geminiLive = it }

        binding.orbView.setOrbState(OrbAnimationView.OrbState.THINKING)
        updateStatusText(getString(R.string.status_connecting))

        engine.onAudioCaptured = { pcm ->
            if (!isInCallMode && !isMuted) {
                client.sendAudio(pcm)
            }
        }

        engine.onAmplitudeChanged = { rms ->
            runOnUiThread {
                binding.waveformView.setAmplitude(rms)
                binding.orbView.setAmplitude(rms)
            }
        }

        engine.onSpeakingStarted = {
            runOnUiThread {
                binding.orbView.setOrbState(OrbAnimationView.OrbState.SPEAKING)
                binding.waveformView.startAnimation()
                setActiveMode(true)
                updateStatusText(getString(R.string.status_speaking))
            }
        }

        engine.onSpeakingStopped = {
            runOnUiThread {
                if (isMuted) {
                    binding.orbView.setOrbState(OrbAnimationView.OrbState.IDLE)
                    binding.waveformView.stopAnimation()
                    setActiveMode(false)
                    updateStatusText(getString(R.string.status_muted))
                } else {
                    binding.orbView.setOrbState(OrbAnimationView.OrbState.LISTENING)
                    binding.waveformView.startAnimation()
                    setActiveMode(true)
                    updateStatusText(getString(R.string.status_listening))
                }
            }
        }

        client.onConnected = {
            runOnUiThread {
                if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    engine.startRecording()
                }
                engine.startPlayback()
                engine.setMuted(isMuted)

                if (isMuted) {
                    binding.orbView.setOrbState(OrbAnimationView.OrbState.IDLE)
                    setActiveMode(false)
                    updateStatusText(getString(R.string.status_muted))
                } else {
                    binding.orbView.setOrbState(OrbAnimationView.OrbState.LISTENING)
                    binding.waveformView.startAnimation()
                    setActiveMode(true)
                    updateStatusText(getString(R.string.status_listening))
                }
                updateMicVisuals()

                if (!hasGreeted) {
                    hasGreeted = true
                    mainHandler.postDelayed({ sendGreeting() }, 600L)
                }
            }
        }

        client.onAudioReceived = { pcm24k ->
            engine.queueAudio(pcm24k)
        }

        client.onInputTranscript = { chunk ->
            synchronized(inputBuffer) {
                inputBuffer.append(chunk)
            }
        }

        client.onOutputTranscript = { chunk ->
            synchronized(outputBuffer) {
                outputBuffer.append(chunk)
            }
        }

        client.onTurnComplete = {
            val userText: String
            val myraText: String
            synchronized(inputBuffer) {
                userText = inputBuffer.toString().trim()
                inputBuffer.clear()
            }
            synchronized(outputBuffer) {
                myraText = outputBuffer.toString().trim()
                outputBuffer.clear()
            }

            runOnUiThread {
                if (userText.isNotEmpty()) {
                    addChatMessage(ChatMessage(userText, isUser = true))
                    val cmd = CommandParser.parse(userText)
                    if (cmd != null) {
                        viewModel.executeCommand(cmd)
                    }
                }
                if (myraText.isNotEmpty()) {
                    addChatMessage(ChatMessage(myraText, isUser = false))
                }
            }
        }

        client.onInterrupted = {
            engine.clearPlaybackQueue()
        }

        client.onDisconnected = { reason ->
            runOnUiThread {
                Log.w(TAG, "Gemini Live disconnected: $reason")
                binding.orbView.setOrbState(OrbAnimationView.OrbState.IDLE)
                binding.waveformView.stopAnimation()
                setActiveMode(false)
                updateStatusText(getString(R.string.status_reconnecting))
            }
        }

        client.onError = { errMsg ->
            runOnUiThread {
                Log.e(TAG, "Gemini Live error: $errMsg")
                binding.orbView.setOrbState(OrbAnimationView.OrbState.IDLE)
                binding.waveformView.stopAnimation()
                setActiveMode(false)
                updateStatusText(errMsg)
            }
        }

        client.connect()
    }

    private fun reconnectGeminiLive() {
        geminiLive?.release()
        audioEngine?.release()
        geminiLive = null
        audioEngine = null
        initGeminiLive()
    }

    private fun sendGreeting() {
        val greetingPrompt = MyraPrefs.buildGreeting(this)
        geminiLive?.sendText("Greet the user warmly right now by saying: \"$greetingPrompt\"")
    }

    private fun toggleMute() {
        isMuted = !isMuted
        audioEngine?.setMuted(isMuted)
        updateMicVisuals()

        if (isMuted) {
            binding.orbView.setOrbState(OrbAnimationView.OrbState.IDLE)
            binding.waveformView.stopAnimation()
            setActiveMode(false)
            updateStatusText(getString(R.string.status_muted))
        } else {
            if (geminiLive == null) {
                initGeminiLive()
            } else if (geminiLive?.isReady() == true) {
                audioEngine?.startRecording()
                binding.orbView.setOrbState(OrbAnimationView.OrbState.LISTENING)
                binding.waveformView.startAnimation()
                setActiveMode(true)
                updateStatusText(getString(R.string.status_listening))
            } else {
                geminiLive?.connect()
                binding.orbView.setOrbState(OrbAnimationView.OrbState.THINKING)
                updateStatusText(getString(R.string.status_connecting))
            }
        }
    }

    private fun interruptMyraSpeaking() {
        vibrateShort()
        audioEngine?.clearPlaybackQueue()
        geminiLive?.sendInterrupt()
        if (!isMuted && geminiLive?.isReady() == true) {
            binding.orbView.setOrbState(OrbAnimationView.OrbState.LISTENING)
            updateStatusText(getString(R.string.status_listening))
        } else {
            binding.orbView.setOrbState(OrbAnimationView.OrbState.IDLE)
            updateStatusText(getString(R.string.status_idle))
        }
        Toast.makeText(this, "MYRA interrupted", Toast.LENGTH_SHORT).show()
    }

    private fun updateMicVisuals() {
        val active = !isMuted && geminiLive?.isReady() == true
        binding.micButton.setImageResource(if (active) R.drawable.ic_mic_on else R.drawable.ic_mic_off)
        binding.micButton.setBackgroundResource(
            if (active) R.drawable.bg_mic_button_active else R.drawable.bg_mic_button
        )
    }

    private fun setActiveMode(active: Boolean) {
        val targetAlpha = if (active) 0.08f else 0f
        val duration = if (active) 300L else 500L
        binding.redOverlay.animate()
            .alpha(targetAlpha)
            .setDuration(duration)
            .start()
    }

    private fun updateStatusText(text: String) {
        binding.statusText.text = text
    }

    private fun addChatMessage(message: ChatMessage) {
        if (chatAdapter.addMessage(message)) {
            binding.chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    // ==================== INCOMING CALL HANDLING ====================

    private fun handleIncomingCallIntent(intent: Intent?) {
        if (intent == null) return
        val isIncoming = intent.getBooleanExtra(CallMonitorService.EXTRA_INCOMING_CALL, false)
        if (!isIncoming) return
        val callerName = intent.getStringExtra(CallMonitorService.EXTRA_CALLER_NAME) ?: "Unknown"
        intent.removeExtra(CallMonitorService.EXTRA_INCOMING_CALL)
        announceCall(callerName)
    }

    private fun announceCall(callerName: String) {
        isInCallMode = true
        audioEngine?.setCallMode(true)
        setActiveMode(true)
        binding.orbView.setOrbState(OrbAnimationView.OrbState.ACTIVE)
        updateStatusText("Incoming call: $callerName")

        val announcement = "Sir, $callerName ka call aa raha hai. Uthau ya reject karu?"
        addChatMessage(ChatMessage(announcement, isUser = false))
        geminiLive?.sendText("Announce this incoming call immediately in your voice: \"$announcement\"")

        // Listen for user's voice decision after MYRA finishes announcing
        mainHandler.postDelayed({
            if (isInCallMode) {
                startCallDecisionStt(callerName)
            }
        }, 4500L)
    }

    private fun startCallDecisionStt(callerName: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            isInCallMode = false
            audioEngine?.setCallMode(false)
            return
        }

        stopCallSpeechRecognizer()
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = sr

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                updateStatusText("Bolo: Uthao ya Reject?")
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                isInCallMode = false
                audioEngine?.setCallMode(false)
                setActiveMode(false)
            }

            override fun onResults(results: Bundle?) {
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.lowercase()
                    .orEmpty()

                if (spoken.contains("utha") || spoken.contains("haan") || spoken.contains("han") ||
                    spoken.contains("accept") || spoken.contains("pick") || spoken.contains("yes")
                ) {
                    val ok = viewModel.acceptCall()
                    val msg = if (ok) "Call accept kar liya $callerName ka." else "Call accept nahi ho paya."
                    addChatMessage(ChatMessage(msg, isUser = false))
                    geminiLive?.sendText("Confirm briefly to the user: $msg")
                } else if (spoken.contains("reject") || spoken.contains("nahi") || spoken.contains("mat") ||
                    spoken.contains("cut") || spoken.contains("kaat") || spoken.contains("decline") || spoken.contains("no")
                ) {
                    val ok = viewModel.rejectCall()
                    val msg = if (ok) "$callerName ka call reject kar diya." else "Call reject nahi ho paya."
                    addChatMessage(ChatMessage(msg, isUser = false))
                    geminiLive?.sendText("Confirm briefly to the user: $msg")
                }

                isInCallMode = false
                audioEngine?.setCallMode(false)
                setActiveMode(false)
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val sttIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            sr.startListening(sttIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting call decision STT", e)
            isInCallMode = false
            audioEngine?.setCallMode(false)
        }
    }

    private fun stopCallSpeechRecognizer() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
    }

    private fun vibrateShort() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(45L)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun buildConfigFingerprint(): String {
        return listOf(
            MyraPrefs.getApiKey(this),
            MyraPrefs.getUserName(this),
            MyraPrefs.getModel(this),
            MyraPrefs.getVoice(this),
            MyraPrefs.getPersonalityMode(this)
        ).joinToString("|")
    }
}
