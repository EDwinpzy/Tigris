package com.leslie.tvhome

import android.content.Context
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reads the vendor ASR trace through the TV's local ADB shell. A normal app UID
 * cannot read the vendor process's log lines; the local ADB path can.
 */
internal object VoiceVendorBridge {
    private const val ASR_RESULT_FILE = "/sdcard/asrResult.txt"
    private const val MAX_TRACE_CHARS = 600
    private const val VENDOR_PACKAGE = "com.jmgo.ai.voice"
    private const val LOGCAT_COMMAND =
        "/system/bin/logcat -d -v threadtime -b main -t 20000"
    private val logTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var sessionStartedAt = 0L

    fun logSnapshot(ctx: Context) {
        val file = File(ASR_RESULT_FILE)
        if (!file.isFile) {
            VoiceDiagnostics.log(ctx, "vendor: asr file missing")
        } else {
            VoiceDiagnostics.log(ctx, "vendor: asr file size=${file.length()}")
            val text = runCatching { file.readText().takeLast(MAX_TRACE_CHARS) }
                .onFailure {
                    VoiceDiagnostics.log(ctx, "vendor: asr read failed ${it.javaClass.simpleName}: ${it.message}")
                }
                .getOrNull()
            if (!text.isNullOrBlank()) VoiceDiagnostics.log(ctx, "vendor: asr tail=${text.replace('\n', '|')}")
        }
        logLogcatAsr(ctx)
    }

    fun beginSession() {
        sessionStartedAt = System.currentTimeMillis() - 200L
    }

    fun finishSession() {
        sessionStartedAt = 0L
    }

    fun prepareVendorRecording(ctx: Context) {
        val output = LocalAdb.shell(
            ctx,
            "cmd appops set com.jmgo.ai.voice RECORD_AUDIO allow; " +
                "cmd appops start --uid 1000 RECORD_AUDIO; " +
                "setprop jmgo.ai.debug_asr 1; echo vendor-prep-ok",
            8_000,
        )
        VoiceDiagnostics.log(
            ctx,
            "vendor: recording prep=${output?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: "failed"}",
        )
    }

    fun awaitLatestAsr(ctx: Context, timeoutMs: Long = 6_000L): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            latestAsrText(ctx)?.let { return it }
            Thread.sleep(250L)
        }
        return null
    }

    fun cancelVendor(ctx: Context) {
        listOf("TTS_CANCEL", "DISMISS_DIALOG", "HIDE_BLACKBG").forEach { action ->
            runCatching { ctx.sendBroadcast(Intent(action).setPackage(VENDOR_PACKAGE)) }
        }
    }

    fun cancelVendorTts(ctx: Context) {
        runCatching { ctx.sendBroadcast(Intent("TTS_CANCEL").setPackage(VENDOR_PACKAGE)) }
    }

    fun parseAsrText(line: String): String? {
        val marker = "onAsr asr is "
        val start = line.indexOf(marker)
        if (start < 0) return null
        val rest = line.substring(start + marker.length)
        val end = rest.indexOf("- isFinished is")
        if (end < 0) return null
        return rest.substring(0, end).trim().takeIf { it.isNotEmpty() }
    }

    private fun latestAsrText(ctx: Context): String? {
        val started = sessionStartedAt
        val baseline = if (started == 0L) null else {
            synchronized(logTimeFormat) { logTimeFormat.format(Date(started)) }
        }
        return readVendorLogcat(ctx).asReversed().asSequence()
            .filter { line ->
                baseline == null || line.substringBefore(' ').contains('-') &&
                    line.take(18) >= baseline
            }
            .firstNotNullOfOrNull(::parseVendorText)
    }

    internal fun parseVendorText(line: String): String? =
        parseAsrText(line) ?: parseWriteText(line)

    private fun parseWriteText(line: String): String? {
        val marker = "writeText: "
        val start = line.indexOf(marker)
        if (start < 0) return null
        return line.substring(start + marker.length)
            .trim()
            .trim('“', '”', '"', '\'')
            .takeIf { it.isNotEmpty() }
    }

    private fun logLogcatAsr(ctx: Context) {
        val allLines = runCatching { readVendorLogcat(ctx) }.getOrElse {
            VoiceDiagnostics.log(ctx, "vendor: logcat failed ${it.javaClass.simpleName}: ${it.message}")
            return
        }
        VoiceDiagnostics.log(ctx, "vendor: logcat total lines=${allLines.size}")
        VoiceDiagnostics.log(
            ctx,
            "vendor: logcat sample=${allLines.takeLast(4).joinToString(" || ") { it.take(MAX_TRACE_CHARS) }}",
        )
        val lines = allLines.filter { it.contains("onAsr asr is") }
        VoiceDiagnostics.log(ctx, "vendor: logcat onAsr lines=${lines.size}")
        lines.takeLast(8).forEach { VoiceDiagnostics.log(ctx, "vendor: ${it.take(MAX_TRACE_CHARS)}") }
    }

    private fun readVendorLogcat(ctx: Context): List<String> {
        LocalAdb.shell(ctx, LOGCAT_COMMAND, 8_000)?.let { output ->
            return output.lineSequence().toList()
        }
        return readAppLogcat()
    }

    /** Fallback for a device where the in-app local ADB key has not been authorized. */
    private fun readAppLogcat(): List<String> {
        val builder = ProcessBuilder(
            "/system/bin/logcat",
            "-d",
            "-v",
            "brief",
            "-b",
            "main",
            "-t",
            "20000",
        ).redirectErrorStream(true)
        builder.environment().remove("ANDROID_LOG_TAGS")
        val process = builder.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(2, TimeUnit.SECONDS)
        return output.lineSequence().toList()
    }

    fun latestTranscript(text: String): String? =
        text.lineSequence()
            .map { it.substringAfter("asr识别结果:", "").trim() }
            .lastOrNull { it.isNotBlank() }
}
