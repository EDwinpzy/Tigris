package com.leslie.tvhome

import android.content.Context
import java.io.File

/** Small on-device diagnostic log; never stores microphone samples. */
internal object VoiceDiagnostics {
    private const val FILE = "voice-debug.log"
    private const val LIMIT = 256 * 1024L

    @Synchronized
    fun log(ctx: Context, message: String) {
        runCatching {
            val file = File(ctx.getExternalFilesDir(null), FILE)
            if (file.length() > LIMIT) file.writeText("")
            file.appendText("${System.currentTimeMillis()} $message\n", Charsets.UTF_8)
        }
    }
}
