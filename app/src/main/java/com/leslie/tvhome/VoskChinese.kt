package com.leslie.tvhome

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/** Thin wrapper for the optional offline Mandarin model, used when Android exposes no recognizer. */
internal object VoskChinese {
    const val MODEL_DIR = "vosk-model-small-cn-0.22"
    @Volatile private var model: Model? = null

    fun directory(ctx: Context): File = File(requireNotNull(ctx.getExternalFilesDir(null)), MODEL_DIR)

    fun installed(ctx: Context): Boolean {
        val dir = directory(ctx)
        return File(dir, "am").isDirectory && File(dir, "conf").isDirectory
    }

    @Synchronized
    fun load(ctx: Context): Model? {
        model?.let { return it }
        if (!installed(ctx)) return null
        return try {
            Model(directory(ctx).absolutePath).also { model = it }
        } catch (e: Throwable) {
            throw IllegalStateException("Vosk 模型加载失败: ${e.message}", e)
        }
    }

    fun text(json: String, key: String): String = runCatching { JSONObject(json).optString(key) }.getOrDefault("").trim()

    fun preload(ctx: Context) {
        if (!installed(ctx)) return
        Thread({
            VoiceDiagnostics.log(ctx, "offline: preload start")
            runCatching { load(ctx) }
                .onSuccess { VoiceDiagnostics.log(ctx, "offline: preload ready") }
                .onFailure { VoiceDiagnostics.log(ctx, "offline: preload error ${it.javaClass.simpleName}: ${it.message}") }
        }, "TigrisVoskPreload").start()
    }
}

/** Owns the recognizer and microphone reader for one push-to-talk interaction. */
internal class OfflineSpeechSession(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val onReady: () -> Unit,
) {
    private var recorder: AudioRecord? = null
    private var recognizer: Recognizer? = null
    private var rawCapture: FileOutputStream? = null
    private val finished = AtomicBoolean(false)
    @Volatile private var stopped = false

    val start: () -> Unit = {
        Thread({ capture() }, "TigrisVosk").start()
    }

    private fun capture() {
        var maxPeak = 0
        var totalSamples = 0L
        var nonZeroSamples = 0L
        var sumAbs = 0L
        var lastTranscript = ""
        var readLogs = 0
        var lastProgressLog = 0L
        VoiceDiagnostics.log(ctx, "offline: start")
        try {
            val model = VoskChinese.load(ctx) ?: run {
                VoiceDiagnostics.log(ctx, "offline: model missing at ${VoskChinese.directory(ctx).absolutePath}")
                val dir = VoskChinese.directory(ctx)
                throw IllegalStateException("离线识别模型未安装: ${dir.absolutePath} exists=${dir.exists()} am=${File(dir, "am").isDirectory} conf=${File(dir, "conf").isDirectory}")
            }
            if (stopped) {
                VoiceDiagnostics.log(ctx, "offline: stopped before recognizer")
                return
            }
            VoiceDiagnostics.log(ctx, "offline: model loaded")
            val r = Recognizer(model, SAMPLE_RATE.toFloat())
            recognizer = r

            val audioManager = ctx.getSystemService(AudioManager::class.java)
            val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            inputDevices.forEach {
                VoiceDiagnostics.log(ctx, "offline: input type=${it.type} product=${it.productName} address=${it.address}")
            }
            val remoteMic = inputDevices.firstOrNull {
                it.productName.contains("ble", ignoreCase = true) ||
                    it.address.contains("vendor=hy", ignoreCase = true)
            }
            val min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (min <= 0) throw IllegalStateException("无法创建 16 kHz 麦克风缓冲")
            val samples = ShortArray(maxOf(2048, min / 2))
            val rawFile = File(ctx.getExternalFilesDir(null), "voice-capture.raw")
            rawCapture = FileOutputStream(rawFile, false)
            VoiceDiagnostics.log(ctx, "offline: raw capture ${rawFile.absolutePath}")
            val input = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                samples.size * 2,
            )
            recorder = input
            if (remoteMic != null) {
                VoiceDiagnostics.log(ctx, "offline: prefer remote mic product=${remoteMic.productName} accepted=${input.setPreferredDevice(remoteMic)}")
            }
            VoiceDiagnostics.log(ctx, "offline: source=VOICE_RECOGNITION rate=$SAMPLE_RATE channels=mono")
            VoiceDiagnostics.log(ctx, "offline: AudioRecord state=${input.state}")
            if (input.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("麦克风无法初始化")
            input.startRecording()
            VoiceDiagnostics.log(ctx, "offline: startRecording state=${input.recordingState} routed=${input.routedDevice?.productName}")
            onReady()
            while (!stopped) {
                val count = input.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                if (count == 0) {
                    Thread.sleep(10)
                    continue
                }
                if (count <= 0) {
                    VoiceDiagnostics.log(ctx, "offline: read returned $count")
                    continue
                }

                totalSamples += count
                val rawBytes = ByteArray(count * 2)
                for (i in 0 until count) {
                    val sample = samples[i].toInt()
                    if (sample != 0) nonZeroSamples++
                    maxPeak = max(maxPeak, abs(sample))
                    sumAbs += abs(sample).toLong()
                    rawBytes[i * 2] = (sample and 0xff).toByte()
                    rawBytes[i * 2 + 1] = ((sample ushr 8) and 0xff).toByte()
                }
                rawCapture?.write(rawBytes)
                val now = System.currentTimeMillis()
                val progress = now - lastProgressLog >= 1000L
                if (progress) {
                    VoiceDiagnostics.log(ctx, "offline: loop peak=$maxPeak samples=$totalSamples rec=${input.recordingState} stopped=$stopped")
                    lastProgressLog = now
                }
                if (readLogs < 3) {
                    VoiceDiagnostics.log(ctx, "offline: read samples=$count")
                    readLogs++
                }
                val complete = r.acceptWaveForm(samples, count)
                val key = if (complete) "text" else "partial"
                val json = if (complete) r.result else r.partialResult
                if (progress) VoiceDiagnostics.log(ctx, "offline: vosk ${json.take(240)}")
                val candidate = VoskChinese.text(json, key)
                if (candidate.isNotBlank() && candidate != lastTranscript) {
                    lastTranscript = candidate
                    VoiceDiagnostics.log(ctx, "offline: transcript=$candidate complete=$complete")
                    onPartial(candidate)
                }
            }

            VoiceDiagnostics.log(ctx, "offline: loop exited peak=$maxPeak samples=$totalSamples avgAbs=${sumAbs / max(1L, totalSamples)}")
            if (maxPeak == 0 || nonZeroSamples == 0L) {
                VoiceDiagnostics.log(ctx, "offline: silent capture")
                if (finished.compareAndSet(false, true)) onFailure(IllegalStateException("麦克风没有采到声音（峰值 0，样本 $totalSamples）"))
                return
            }

            val text = lastTranscript
            VoiceDiagnostics.log(ctx, "offline: using last transcript text=$text")
            Log.i("TigrisVoice", "offline recognition peak=$maxPeak samples=$totalSamples nonzero=$nonZeroSamples text=$text")
            VoiceDiagnostics.log(ctx, "offline: finish peak=$maxPeak samples=$totalSamples nonzero=$nonZeroSamples avgAbs=${sumAbs / max(1L, totalSamples)} text=$text")
            if (!finished.compareAndSet(false, true)) return
            if (text.isNotBlank()) onFinal(text)
            else onFailure(IllegalStateException("没有识别到语音（峰值 $maxPeak，样本 $totalSamples）"))
        } catch (e: Throwable) {
            VoiceDiagnostics.log(ctx, "offline: error ${e.javaClass.simpleName}: ${e.message}")
            if (finished.compareAndSet(false, true)) onFailure(e)
        } finally {
            release()
        }
    }

    fun stop() {
        stopped = true
        VoiceDiagnostics.log(ctx, "offline: stop requested")
        val result = runCatching { recorder?.stop() }
        VoiceDiagnostics.log(ctx, "offline: recorder stop result=${result.isSuccess}")
    }

    fun close() {
        stopped = true
        runCatching { recorder?.stop() }
        release()
    }

    private fun release() {
        val input = recorder
        recorder = null
        if (input != null) {
            runCatching { input.stop() }
            runCatching { input.release() }
        }
        val r = recognizer
        recognizer = null
        runCatching { r?.close() }
        runCatching { rawCapture?.flush() }
        runCatching { rawCapture?.close() }
        rawCapture = null
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
    }
}


