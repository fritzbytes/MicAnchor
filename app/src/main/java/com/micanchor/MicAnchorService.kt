package com.micanchor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlin.concurrent.thread

/**
 * Holds an AudioRecord open at the MT8167's hardware-native capture format so that
 * AudioFlinger's shared HAL input stream is established at 48000Hz/stereo.
 *
 * Any other app that subsequently asks for 16000Hz/mono is attached to this already-open
 * stream and gets AudioFlinger's own (working) software resampler, instead of causing a
 * fresh HAL stream to be opened at 16k -- which is what triggers MediaTek's BesRecord SRC
 * and produces the slowed-down/deep-voiced audio.
 *
 * The captured samples are read and discarded. The read() call is what matters; an
 * unread stream can stall or be torn down.
 */
class MicAnchorService : Service() {

    companion object {
        private const val TAG = "MicAnchor"
        private const val CHANNEL_ID = "mic_anchor"
        private const val NOTIF_ID = 0x4D41

        const val ACTION_STOP = "com.micanchor.STOP"

        // Do not change these. They are the hardware's native capture format.
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private const val BACKOFF_START_MS = 1_000L
        private const val BACKOFF_MAX_MS = 30_000L
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "stop requested")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running) {
            startForeground(NOTIF_ID, buildNotification())
            acquireWakeLock()
            running = true
            worker = thread(name = "mic-anchor", isDaemon = false) { captureLoop() }
            Log.i(TAG, "anchor service started")
        }

        // Ask the system to restart us if we are killed.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "anchor service stopping")
        running = false
        worker?.interrupt()
        worker = null
        releaseWakeLock()
        super.onDestroy()
    }

    private fun captureLoop() {
        var backoff = BACKOFF_START_MS

        while (running) {
            var recorder: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                if (minBuf <= 0) {
                    throw IllegalStateException("getMinBufferSize returned $minBuf")
                }

                // Generous buffer so a scheduling hiccup does not overrun the stream.
                val bufSize = minBuf * 4

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufSize
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord failed to initialize (state=${recorder.state})")
                }

                recorder.startRecording()

                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("startRecording did not take effect")
                }

                Log.i(
                    TAG,
                    "anchor stream open: ${recorder.sampleRate}Hz " +
                        "channels=${recorder.channelCount} bufSize=$bufSize"
                )

                backoff = BACKOFF_START_MS

                val sink = ShortArray(bufSize / 2)
                while (running) {
                    val n = recorder.read(sink, 0, sink.size)
                    if (n < 0) {
                        Log.w(TAG, "read() returned $n, reopening stream")
                        break
                    }
                    // Samples are intentionally discarded. Nothing is stored or transmitted.
                }
            } catch (t: Throwable) {
                Log.e(TAG, "capture failed", t)
            } finally {
                try {
                    if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "stop() threw", t)
                }
                try {
                    recorder?.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "release() threw", t)
                }
            }

            if (running) {
                Log.i(TAG, "retrying in ${backoff}ms")
                try {
                    Thread.sleep(backoff)
                } catch (e: InterruptedException) {
                    return
                }
                backoff = (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
            }
        }

        Log.i(TAG, "capture loop exited")
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mic anchor",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps the audio input stream open at the hardware-native format."
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, MicAnchorService::class.java).apply {
            action = ACTION_STOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val stopPending = PendingIntent.getService(this, 0, stopIntent, flags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Mic anchor active")
            .setContentText("Audio input held at 48kHz stereo")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Stop",
                    stopPending
                ).build()
            )
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG::anchor").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (t: Throwable) {
            Log.w(TAG, "wakelock release threw", t)
        }
        wakeLock = null
    }
}
