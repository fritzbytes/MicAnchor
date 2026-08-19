package com.micanchor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * No UI. Exists only so the app has a launcher entry and somewhere to request
 * RECORD_AUDIO at runtime. Starts the service and immediately finishes.
 *
 * On this device the kiosk launcher may swallow the permission dialog, so the
 * permission can also be granted from a host machine:
 *   adb shell pm grant com.micanchor android.permission.RECORD_AUDIO
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_MIC = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            launchService()
            finish()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQ_MIC &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            launchService()
        } else {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun launchService() {
        val intent = Intent(this, MicAnchorService::class.java)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Mic anchor started", Toast.LENGTH_SHORT).show()
    }
}
