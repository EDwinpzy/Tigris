package com.leslie.tvhome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Local-only switch used while validating the vendor ASR bridge. */
class VoiceDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION -> VoiceAssistant.setObserveOnly(intent.getBooleanExtra("enabled", false))
            ACTION_INJECT_TEXT -> {
                val text = intent.getStringExtra("text")?.trim().orEmpty()
                if (text.isNotEmpty()) VoiceActivity.launch(context, text)
            }
        }
    }

    companion object {
        const val ACTION = "com.leslie.tvhome.VOICE_OBSERVE_ONLY"
        const val ACTION_INJECT_TEXT = "com.leslie.tvhome.VOICE_INJECT_TEXT"
    }
}
