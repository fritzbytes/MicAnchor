package com.micanchor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Restarts the anchor service after a reboot.
 *
 * This device's kiosk launcher has been unreliable about broadcast delivery, so the
 * Magisk service.d script in extras/ is included as a belt-and-braces backup.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.i("MicAnchor", "boot received (${intent.action}), starting service")
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MicAnchorService::class.java)
                )
            }
        }
    }
}
