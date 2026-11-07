package com.leslie.tvhome

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/** Enables Tigris's own accessibility component without touching vendor components. */
internal object VoiceAccessibility {
    private fun component(ctx: Context) = ComponentName(ctx, HomeGuardService::class.java)

    fun ensure(ctx: Context): Boolean = runCatching {
        val cn = component(ctx)
        ctx.packageManager.setComponentEnabledSetting(
            cn,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        val full = "${cn.packageName}/${cn.className}"
        val short = cn.flattenToShortString()
        val current = Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services").orEmpty()
        val ids = current.split(':').filter { it.isNotBlank() && it != "null" }.toMutableList()
        if (ids.none { it == full || it == short }) ids += full
        Settings.Secure.putString(ctx.contentResolver, "enabled_accessibility_services", ids.joinToString(":"))
        Settings.Secure.putInt(ctx.contentResolver, "accessibility_enabled", 1)
        Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services")
            ?.split(':')?.any { it == full || it == short } == true
    }.onFailure { Log.w("TigrisVoice", "enable accessibility failed", it) }.getOrDefault(false)
}
