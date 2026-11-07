package com.leslie.tvhome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives JMGO's AI_VOICE broadcast even when the process was not already listening. */
class VoiceCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = VoiceAssistant.onRemoteBroadcast(context, intent)
}