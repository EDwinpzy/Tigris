package com.leslie.tvhome

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.Executors

/**
 * JMGO's remote sends com.jmgo.action.AI_VOICE with operate=NEAR_START/NEAR_STOP.
 * Tigris listens to that same event. With an EditText focused it inserts the transcript;
 * otherwise it runs a small voice assistant and speaks the configured model's answer.
 */
object VoiceAssistant {
    private const val TAG = "TigrisVoice"
    private const val ACTION_AI_VOICE = "com.jmgo.action.AI_VOICE"
    private const val EXTRA_OPERATE = "operate"
    private const val EXTRA_DATA = "data"
    private const val PERMISSION_REQUEST = 4817
    private const val MAX_RECORDING_MS = 30_000L
    private const val MIN_RECORDING_MS = 350L

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var initialized = false
    private var current = WeakReference<Activity>(null)
    private var panel: VoicePanel? = null
    private var target: WeakReference<android.widget.EditText>? = null
    private var isDictation = false
    private var state = State.IDLE
    private var recognizer: SpeechRecognizer? = null
    private var recorder: MediaRecorder? = null
    private var offline: OfflineSpeechSession? = null
    private var recordingFile: File? = null
    private var recordingStarted = 0L
    private var stopPending = false
    private var lastStartAt = 0L
    private var vendorTtsCancelCount = 0
    private var player: MediaPlayer? = null
    private var generation = 0
    @Volatile private var observeOnly = false
    private var vendorAsr = false
    private val remoteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            VoiceDiagnostics.log(context, "receiver: dynamic AI_VOICE delivered")
            onRemoteBroadcast(context, intent)
        }
    }
    private val stopWatchdog = Runnable {
        if (state == State.LISTENING && offline != null) showError(I18n.text(R.string.voice_no_speech))
    }
    private val vendorTtsWatchdog = object : Runnable {
        override fun run() {
            if (!vendorAsr || state != State.LISTENING) return
            VoiceVendorBridge.cancelVendorTts(app)
            vendorTtsCancelCount++
            if (vendorTtsCancelCount < 4) main.postDelayed(this, 1_500L)
        }
    }

    private enum class State { IDLE, LISTENING, PROCESSING }


    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        vendorAsr = runCatching {
            app.packageManager.getPackageInfo("com.jmgo.ai.voice", 0)
            true
        }.getOrDefault(false)
        val filter = IntentFilter(ACTION_AI_VOICE)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(remoteReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(remoteReceiver, filter)
        }
        VoiceDiagnostics.log(app, "receiver: dynamic AI_VOICE registered")
        if (vendorAsr) Thread { VoiceVendorBridge.prepareVendorRecording(app) }.start()

        (app as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) { current = WeakReference(activity) }
            override fun onActivityPaused(activity: Activity) {
                if (current.get() === activity) current = WeakReference(null)
            }
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (current.get() === activity) {
                    generation++
                    panel?.close(immediate = true)
                    panel = null
                    if (state != State.IDLE) cancelCapture()
                    current = WeakReference(null)
                }
            }
        })
    }

    fun onRemoteBroadcast(context: Context, intent: Intent) {
        if (intent.action != ACTION_AI_VOICE) return
        val operate = intent.getStringExtra(EXTRA_OPERATE).orEmpty().uppercase(Locale.US)
        val dataLength = intent.getStringExtra(EXTRA_DATA)?.length ?: 0
        Log.i(TAG, "AI_VOICE operate=$operate dataLength=$dataLength")
        VoiceDiagnostics.log(context, "AI_VOICE operate=$operate dataLength=$dataLength")
        if (observeOnly && (operate == "NEAR_STOP" || operate == "STOP")) {
            VoiceVendorBridge.logSnapshot(context)
            main.postDelayed({ VoiceVendorBridge.logSnapshot(context) }, 2_500L)
        }
        when (operate) {
            "NEAR_START", "START" -> if (!observeOnly) begin(context)
            "NEAR_STOP", "STOP" -> if (!observeOnly) stopListening()
        }
    }

    fun setObserveOnly(enabled: Boolean) {
        observeOnly = enabled
        VoiceDiagnostics.log(app, "vendor: observeOnly=$enabled")
    }

    fun onPermissionResult(activity: Activity, requestCode: Int, grantResults: IntArray) {
        if (requestCode != PERMISSION_REQUEST || current.get() !== activity) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCapture(activity)
        } else {
            showError(I18n.text(R.string.voice_permission_denied))
        }
    }

    fun acceptTranscript(context: Context, text: String) {
        if (!Prefs(context).voiceEnabled || text.isBlank()) return
        val activity = current.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (activity == null) {
            VoiceActivity.launch(context, text)
            return
        }
        if (state != State.IDLE) return
        generation++
        val focused = findFocusedEditText(activity)
        isDictation = focused != null
        target = focused?.let { WeakReference(it) }
        showPanel(activity)
        finishTranscript(text.trim())
    }
    fun beginFromRemoteKey(context: Context) = begin(context)
    fun stopFromRemoteKey() = stopListening()
    fun isSessionActive(): Boolean = state != State.IDLE
    fun usesVendorAsr(): Boolean = vendorAsr

    /** Lets the hosting Activity consume Back even when the overlay view did not get focus. */
    fun handleBack(activity: Activity, keyCode: Int, action: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK || panel == null) return false
        if (current.get() !== activity) return false
        if (action == KeyEvent.ACTION_UP) panel?.close()
        return true
    }

    fun beginFromActivity(activity: Activity) {
        current = WeakReference(activity)
        if (state == State.IDLE) beginForActivity(activity)
    }

    private fun begin(context: Context) {
        if (!Prefs(context).voiceEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (state != State.IDLE || now - lastStartAt < 700L) return
        lastStartAt = now
        stopPending = false
        val activity = current.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (activity == null) {
            if (vendorAsr) {
                VoiceVendorBridge.beginSession()
                Thread { VoiceVendorBridge.prepareVendorRecording(app) }.start()
                startVendorTtsWatchdog()
                state = State.LISTENING
            } else {
                VoiceActivity.launch(context)
            }
        } else {
            beginForActivity(activity)
        }
    }

    private fun beginForActivity(activity: Activity) {
        if (state != State.IDLE) return
        val focused = findFocusedEditText(activity)
        isDictation = focused != null
        target = focused?.let { WeakReference(it) }
        if (vendorAsr) {
            VoiceVendorBridge.beginSession()
            Thread { VoiceVendorBridge.prepareVendorRecording(app) }.start()
            startVendorTtsWatchdog()
            state = State.LISTENING
            return
        }
        showPanel(activity)
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            panel?.status(I18n.text(R.string.voice_permission_ask))
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST)
            return
        }
        startCapture(activity)
    }

    private fun startCapture(activity: Activity) {
        if (state != State.IDLE) return
        state = State.LISTENING
        stopPending = false
        panel?.status(I18n.text(R.string.voice_listening))
        val prefs = Prefs(activity)
        val provider = prefs.voiceSttProvider
        val useSystem = provider == Prefs.VOICE_STT_SYSTEM ||
            (provider == Prefs.AUTO && SpeechRecognizer.isRecognitionAvailable(activity))
        val useOffline = provider == Prefs.VOICE_STT_OFFLINE ||
            (provider == Prefs.AUTO && !useSystem && VoskChinese.installed(activity))
        when {
            useSystem -> startSystemRecognizer(activity, prefs)
            useOffline -> startOfflineRecognizer(activity)
            else -> startRecorder(activity, prefs)
        }
    }

    private fun startSystemRecognizer(activity: Activity, prefs: Prefs) {
        try {
            val r = SpeechRecognizer.createSpeechRecognizer(activity.applicationContext)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() { panel?.status(I18n.text(R.string.voice_listening)) }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { panel?.status(I18n.text(R.string.voice_recognizing)) }
                override fun onError(error: Int) {
                    if (state == State.LISTENING) showError(recognitionError(error))
                }
                override fun onResults(results: Bundle?) {
                    finishTranscript(bestResult(results))
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    bestResult(partialResults).takeIf { it.isNotBlank() }?.let {
                        if (isDictation) panel?.status(it) else panel?.body(it)
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.voiceSttLanguage)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, prefs.voiceSttLanguage)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            r.startListening(intent)
            main.postDelayed(maxRecording, MAX_RECORDING_MS)
        } catch (e: Exception) {
            destroyRecognizer()
            showError(e.message ?: I18n.text(R.string.voice_unavailable))
        }
    }

    private fun startRecorder(activity: Activity, prefs: Prefs) {
        if (prefs.voiceSttUrl.isBlank()) {
            showError(I18n.text(R.string.voice_need_config))
            return
        }
        if (prefs.voiceSttKey.isBlank() && prefs.voiceSttUrl.contains("api.openai.com", ignoreCase = true)) {
            showError(I18n.text(R.string.voice_need_stt_key))
            return
        }
        val file = File(activity.cacheDir, "voice/capture-${System.nanoTime()}.m4a")
        file.parentFile?.mkdirs()
        try {
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(activity) else @Suppress("DEPRECATION") MediaRecorder()
            recorder = r
            recordingFile = file
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setAudioChannels(1)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recordingStarted = SystemClock.elapsedRealtime()
            main.postDelayed(maxRecording, MAX_RECORDING_MS)
        } catch (e: Exception) {
            releaseRecorder()
            showError(e.message ?: I18n.text(R.string.voice_unavailable))
        }
    }

    private fun startOfflineRecognizer(activity: Activity) {
        panel?.status(I18n.text(R.string.voice_loading_offline))
        val token = generation
        val session = OfflineSpeechSession(
            activity.applicationContext,
            onPartial = { text -> main.post { if (token == generation && modeListening()) panel?.body(text) } },
            onFinal = { text -> main.post { if (token == generation && modeListening()) finishTranscript(text) } },
            onFailure = { error -> main.post { if (token == generation) showError(error.message ?: I18n.text(R.string.voice_unavailable)) } },
            onReady = { main.post { if (token == generation && modeListening()) panel?.status(I18n.text(R.string.voice_listening)) } },
        )
        offline = session
        session.start()
    }

    private fun modeListening() = state == State.LISTENING

    private fun releaseOffline() {
        offline?.close()
        offline = null
    }
    private val maxRecording = Runnable {
        if (state == State.LISTENING) {
            stopPending = true
            stopListening()
        }
    }

    private fun stopListening() {
        if (vendorAsr) {
            if (state != State.LISTENING) return
            main.removeCallbacks(vendorTtsWatchdog)
            state = State.PROCESSING
            panel?.status(I18n.text(R.string.voice_recognizing))
            val token = generation
            io.execute {
                val text = VoiceVendorBridge.awaitLatestAsr(app, 6_000L)
                main.post {
                    if (token != generation || state != State.PROCESSING) return@post
                    if (text == null) {
                        VoiceDiagnostics.log(app, "vendor: transcript not found")
                        VoiceVendorBridge.finishSession()
                        VoiceVendorBridge.cancelVendor(app)
                        showVendorError(I18n.text(R.string.voice_vendor_no_result))
                    } else {
                        VoiceDiagnostics.log(app, "vendor: transcript=$text")
                        VoiceVendorBridge.finishSession()
                        VoiceVendorBridge.cancelVendor(app)
                        finishVendorTranscript(text)
                    }
                }
            }
            return
        }
        if (state != State.LISTENING) {
            if (lastStartAt > 0 && SystemClock.elapsedRealtime() - lastStartAt < 5_000L) stopPending = true
            return
        }
        stopPending = true
        main.removeCallbacks(maxRecording)
        recognizer?.let {
            panel?.status(I18n.text(R.string.voice_recognizing))
            runCatching { it.stopListening() }
        }
        offline?.let {
            panel?.status(I18n.text(R.string.voice_recognizing))
            it.stop()
            main.removeCallbacks(stopWatchdog)
            main.postDelayed(stopWatchdog, 5_000L)
        }
        recorder?.let { stopRecorderAndTranscribe() }
    }

    private fun stopRecorderAndTranscribe() {
        val started = recordingStarted
        val file = recordingFile
        val elapsed = SystemClock.elapsedRealtime() - started
        releaseRecorder()
        if (file == null || !file.isFile || file.length() == 0L || elapsed < MIN_RECORDING_MS) {
            file?.delete()
            showError(I18n.text(R.string.voice_too_short))
            return
        }
        state = State.PROCESSING
        panel?.status(I18n.text(R.string.voice_recognizing))
        val prefs = Prefs(app)
        val token = generation
        io.execute {
            val result = runCatching { VoiceNetwork.transcribe(app, file, prefs) }
            file.delete()
            main.post {
                if (token != generation || panel == null) return@post
                result.onSuccess { finishTranscript(it) }
                    .onFailure {
                        Log.w(TAG, "STT failed: ${it.message}")
                        showError(it.message ?: I18n.text(R.string.voice_network_error))
                    }
            }
        }
    }

    private fun bestResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()

    private fun finishVendorTranscript(text: String) {
        val activity = current.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (activity == null) {
            state = State.IDLE
            VoiceActivity.launch(app, text)
            return
        }
        if (panel == null) {
            if (!isDictation) target = null
            showPanel(activity)
        }
        finishTranscript(text)
    }

    private fun showVendorError(message: String) {
        val activity = current.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (activity == null) {
            showError(message)
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            return
        }
        if (panel == null) {
            isDictation = false
            target = null
            showPanel(activity)
        }
        showError(message)
    }

    private fun finishTranscript(text: String) {
        main.removeCallbacks(maxRecording)
        main.removeCallbacks(stopWatchdog)
        destroyRecognizer()
        releaseOffline()
        if (text.isBlank()) {
            showError(I18n.text(R.string.voice_no_speech))
            return
        }
        state = State.PROCESSING
        if (isDictation) {
            if (insertIntoFocused(text)) {
                panel?.status(I18n.text(R.string.voice_inserted))
                main.postDelayed({ finishPanel() }, 650L)
            } else {
                showError(I18n.text(R.string.voice_input_lost))
            }
            return
        }
        panel?.body(text)
        panel?.status(I18n.text(R.string.voice_thinking))
        val prefs = Prefs(app)
        if (prefs.voiceLlmKey.isBlank() && prefs.voiceLlmUrl.contains("api.openai.com", ignoreCase = true)) {
            showError(I18n.text(R.string.voice_need_llm_key))
            return
        }
        val token = generation
        io.execute {
            val result = runCatching { VoiceNetwork.chat(text, prefs) }
            main.post {
                if (token != generation || panel == null) return@post
                result.onSuccess { answer ->
                    panel?.answer(answer)
                    panel?.status(I18n.text(R.string.voice_speaking))
                    speak(answer)
                }.onFailure {
                    Log.w(TAG, "LLM failed: ${it.message}")
                    showError(it.message ?: I18n.text(R.string.voice_network_error))
                }
            }
        }
    }

    private fun insertIntoFocused(text: String): Boolean {
        val edit = target?.get() ?: return false
        if (!edit.isAttachedToWindow) return false
        val value = edit.text ?: return false
        val rawStart = edit.selectionStart.takeIf { it >= 0 } ?: value.length
        val rawEnd = edit.selectionEnd.takeIf { it >= 0 } ?: rawStart
        val start = minOf(rawStart, rawEnd).coerceIn(0, value.length)
        val end = maxOf(rawStart, rawEnd).coerceIn(start, value.length)
        value.replace(start, end, text)
        edit.setSelection((start + text.length).coerceAtMost(edit.text.length))
        edit.requestFocus()
        return true
    }

    private fun showPanel(activity: Activity) {
        panel?.close(immediate = true)
        val host = activity.findViewById<ViewGroup>(android.R.id.content)
            ?: activity.window.decorView as? ViewGroup
            ?: return
        panel = VoicePanel(host, !isDictation) {
            onPanelClosed(activity)
        }.show()
    }

    private fun showError(message: String) {
        state = State.IDLE
        main.removeCallbacks(maxRecording)
        main.removeCallbacks(stopWatchdog)
        destroyRecognizer()
        releaseOffline()
        releaseRecorder()
        panel?.error(message)
        if (isDictation) main.postDelayed({ finishPanel() }, 3_500L)
    }

    private fun finishPanel() {
        panel?.close()
        panel = null
        state = State.IDLE
        isDictation = false
        target = null
        stopPending = false
    }

    private fun onPanelClosed(activity: Activity) {
        generation++
        cancelCapture()
        panel = null
        isDictation = false
        target = null
        stopPending = false
        if (activity is VoiceActivity && !activity.isFinishing && !activity.isDestroyed) activity.finish()
    }

    private fun cancelCapture() {
        main.removeCallbacks(maxRecording)
        main.removeCallbacks(stopWatchdog)
        main.removeCallbacks(vendorTtsWatchdog)
        destroyRecognizer()
        releaseOffline()
        releaseRecorder()
        recordingFile?.delete()
        state = State.IDLE
    }

    private fun startVendorTtsWatchdog() {
        main.removeCallbacks(vendorTtsWatchdog)
        vendorTtsCancelCount = 0
        main.postDelayed(vendorTtsWatchdog, 1_000L)
    }

    private fun destroyRecognizer() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun releaseRecorder() {
        recorder?.let {
            runCatching { it.stop() }
            runCatching { it.reset() }
            runCatching { it.release() }
        }
        recorder = null
    }

    private fun findFocusedEditText(activity: Activity): android.widget.EditText? {
        fun scan(v: View?): android.widget.EditText? {
            if (v == null) return null
            if (v is android.widget.EditText && v.isFocused) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) scan(v.getChildAt(i))?.let { return it }
            return null
        }
        return scan(activity.currentFocus) ?: scan(activity.window.decorView)
    }

    private fun recognitionError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> I18n.text(R.string.voice_no_speech)
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> I18n.text(R.string.voice_network_error)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> I18n.text(R.string.voice_permission_denied)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> I18n.text(R.string.voice_busy)
        else -> I18n.text(R.string.voice_unavailable)
    }

    private fun speak(text: String) {
        val prefs = Prefs(app)
        when (prefs.voiceTtsProvider) {
            Prefs.VOICE_TTS_SYSTEM -> if (!systemTts(text)) fallbackTts(text, prefs)
            Prefs.VOICE_TTS_JMGO -> if (!jmgoTts(text)) cloudTts(text, prefs)
            Prefs.VOICE_TTS_CLOUD -> cloudTts(text, prefs)
            else -> if (!systemTts(text) { fallbackTts(text, prefs) }) Unit
        }
    }

    private fun systemTts(text: String, onUnavailable: () -> Unit = {}): Boolean {
        val hasEngine = app.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0
        ).isNotEmpty()
        if (!hasEngine) {
            onUnavailable()
            return false
        }
        var engine: TextToSpeech? = null
        var settled = false
        fun fail() {
            if (settled) return
            settled = true
            runCatching { engine?.shutdown() }
            onUnavailable()
        }
        return try {
            engine = TextToSpeech(app) { status ->
                main.post {
                    if (settled) return@post
                    if (status != TextToSpeech.SUCCESS) {
                        fail()
                        return@post
                    }
                    val language = engine?.setLanguage(Locale.SIMPLIFIED_CHINESE) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    if (language < TextToSpeech.LANG_AVAILABLE) {
                        fail()
                        return@post
                    }
                    settled = true
                    engine?.setSpeechRate(0.95f)
                    engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { main.post { runCatching { engine?.shutdown() } } }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { main.post { runCatching { engine?.shutdown() } } }
                    })
                    engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tigris-voice-${System.nanoTime()}")
                }
            }
            main.postDelayed({ fail() }, 1_800L)
            true
        } catch (e: Exception) {
            fail()
            false
        }
    }

    private fun fallbackTts(text: String, prefs: Prefs) {
        if (jmgoTts(text)) return
        cloudTts(text, prefs)
    }

    private fun jmgoTts(text: String): Boolean = runCatching {
        val intent = Intent("TTS_PLAY")
            .setPackage("com.jmgo.ai.voice")
            .putExtra(EXTRA_DATA, text)
        app.sendBroadcast(intent)
        true
    }.getOrDefault(false)

    private fun cloudTts(text: String, prefs: Prefs) {
        io.execute {
            val result = runCatching { VoiceNetwork.speech(app, text, prefs) }
            main.post {
                result.onSuccess { playAudio(it) }.onFailure {
                    Log.w(TAG, "TTS unavailable: ${it.message}")
                    panel?.status(I18n.text(R.string.voice_tts_unavailable))
                }
            }
        }
    }

    private fun playAudio(file: File) {
        runCatching {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    it.release()
                    file.delete()
                    if (player === it) player = null
                }
                prepare()
                start()
            }
        }.onFailure {
            file.delete()
            panel?.status(I18n.text(R.string.voice_tts_unavailable))
        }
    }
}

/** Full assistant card, or a small non-focusable bubble while dictating into an EditText. */
private class VoicePanel(
    private val host: ViewGroup,
    private val full: Boolean,
    private val onClosed: () -> Unit,
) {
    val root = FrameLayout(host.context).apply {
        setBackgroundColor(if (full) Color.argb(210, 0, 0, 0) else Color.TRANSPARENT)
        isClickable = full
        isFocusable = full
        translationZ = host.context.dp(500).toFloat()
    }
    private val card = LinearLayout(host.context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(host.context.dp(30), host.context.dp(24), host.context.dp(30), host.context.dp(22))
        background = GradientDrawable().apply {
            setColor(Color.argb(240, 28, 31, 38))
            cornerRadius = host.context.dp(24).toFloat()
        }
    }
    private val title = host.context.text(25f, weight = 600).apply {
        text = I18n.text(if (full) R.string.voice_assistant else R.string.voice_dictation)
        gravity = Gravity.CENTER
    }
    private val progress = ProgressBar(host.context).apply {
        indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        visibility = View.VISIBLE
    }
    private val status = host.context.text(16f, Color.argb(210, 255, 255, 255)).apply {
        text = I18n.text(R.string.voice_listening)
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val body = host.context.text(18f, Color.WHITE).apply {
        visibility = View.GONE
        gravity = Gravity.CENTER
        maxLines = 4
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(host.context.dp(3).toFloat(), 1f)
    }
    private val answerScroll = ScrollView(host.context)
    private val answer = host.context.text(19f, Color.WHITE).apply {
        visibility = View.GONE
        setLineSpacing(host.context.dp(4).toFloat(), 1f)
        isFocusable = false
    }
    private val hint = host.context.text(13f, Color.argb(145, 255, 255, 255)).apply {
        text = if (full) I18n.text(R.string.voice_press_back) else ""
        gravity = Gravity.CENTER
    }

    init {
        card.addView(title, LinearLayout.LayoutParams(-2, -2))
        card.addView(progress, LinearLayout.LayoutParams(host.context.dp(28), host.context.dp(28)).apply {
            topMargin = host.context.dp(14)
        })
        card.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = host.context.dp(10) })
        card.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = host.context.dp(12) })
        answerScroll.apply {
            isFillViewport = false
            isFocusable = false
            visibility = View.GONE
            addView(answer, ViewGroup.LayoutParams(-1, -2))
        }
        card.addView(answerScroll, LinearLayout.LayoutParams(-1, host.context.dp(250)).apply {
            topMargin = host.context.dp(14)
            bottomMargin = host.context.dp(12)
        })
        card.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = host.context.dp(10) })
        val width = if (full) host.context.dp(820) else host.context.dp(560)
        val params = if (full) {
            FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        } else {
            FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = host.context.dp(86)
            }
        }
        root.addView(card, params)
        root.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                close()
                true
            } else false
        }
    }

    fun show(): VoicePanel {
        root.alpha = 0f
        host.addView(root, FrameLayout.LayoutParams(-1, -1))
        root.animate().alpha(1f).setDuration(160).start()
        if (full) root.post { root.requestFocus() }
        return this
    }

    fun status(value: String) = apply {
        status.text = value
        progress.visibility = if (value == I18n.text(R.string.voice_speaking) || value == I18n.text(R.string.voice_inserted)) View.GONE else View.VISIBLE
    }

    fun body(value: String) = apply {
        body.text = value
        body.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
        answerScroll.visibility = View.GONE
    }

    fun answer(value: String) = apply {
        body.visibility = View.GONE
        answer.text = value
        answer.visibility = View.VISIBLE
        answerScroll.visibility = View.VISIBLE
        progress.visibility = View.GONE
        root.requestFocus()
    }

    fun error(value: String) = apply {
        status.text = value
        progress.visibility = View.GONE
        body.visibility = View.GONE
        answerScroll.visibility = View.GONE
    }

    fun close(immediate: Boolean = false) {
        if (root.parent == null) return
        if (immediate) {
            host.removeView(root)
            onClosed()
        } else {
            root.animate().alpha(0f).setDuration(130).withEndAction {
                if (root.parent != null) host.removeView(root)
                onClosed()
            }.start()
        }
    }
}


