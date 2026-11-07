package com.leslie.tvhome

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout

/** Fallback surface when the remote voice key is pressed while no Tigris activity is visible. */
class VoiceActivity : Activity() {
    companion object {
        private const val EXTRA_INVOKE = "com.leslie.tvhome.VOICE_INVOKE"
        private const val EXTRA_TEXT = "com.leslie.tvhome.VOICE_TEXT"

        fun launch(context: Context, text: String? = null) {
            context.startActivity(Intent(context, VoiceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_INVOKE, text == null)
                .putExtra(EXTRA_TEXT, text))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.TRANSPARENT) })
        val text = intent.getStringExtra(EXTRA_TEXT)
        if (!text.isNullOrBlank()) {
            intent.removeExtra(EXTRA_TEXT)
            window.decorView.post { VoiceAssistant.acceptTranscript(this, text) }
        } else if (intent.getBooleanExtra(EXTRA_INVOKE, false)) {
            intent.removeExtra(EXTRA_INVOKE)
            window.decorView.post { VoiceAssistant.beginFromActivity(this) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        VoiceAssistant.onPermissionResult(this, requestCode, grantResults)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (VoiceAssistant.handleBack(this, event.keyCode, event.action)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
