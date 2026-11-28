package com.corejbh.hashes.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.corejbh.hashes.MainActivity
import com.corejbh.hashes.R
import com.corejbh.hashes.notification.MinerNotificationContent
import com.corejbh.hashes.session.MiningSession
import com.corejbh.hashes.session.MiningSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the mining session alive while the terminal is not on screen.
 *
 * The session itself lives in [MiningSession]; this service exists for one
 * reason: Android stops an app's work when its process leaves the foreground,
 * and a foreground service with a `dataSync` type is the sanctioned way to keep
 * a socket and a worker set running. It also gives the user the only handle on a
 * background session they need: a notification with the live hash rate and a
 * `STOP` action.
 *
 * Lifecycle: `ACTION_START` puts the service in the foreground and starts the
 * session; `ACTION_STOP`, the notification's `STOP`, or the user swiping the app
 * out of the recent tasks (`onTaskRemoved`) stops the session, drops the
 * notification and ends the service. `START_NOT_STICKY` means the system never
 * brings a session back on its own after it kills one.
 */
class MinerForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MiningSession
    private var notificationJob: Job? = null
    private var inForeground = false
    private var lastPublished = 0L

    override fun onCreate() {
        super.onCreate()
        session = MiningSession.get(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startInForeground(session.state.value)
                // The terminal validates first; this is the same check one layer
                // down, so a session is never shown as running without a link.
                if (session.start()) {
                    // The shade gets the running state at once, not a second after.
                    publish(session.state.value)
                    observeNotification()
                } else {
                    endSession()
                }
            }

            ACTION_STOP -> endSession()

            else -> {
                /* A restart with no action (or an unknown one) has no session to
                 * resume: nothing is started silently behind the user's back. */
                endSession()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** The user swiped the app out of the recent tasks: stop, as the brief says. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        endSession()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * API 35+ ends a `dataSync` foreground service after the platform's daily
     * budget. Mining stops with it instead of dying half-alive.
     */
    override fun onTimeout(startId: Int) {
        endSession()
    }

    override fun onDestroy() {
        session.stop()
        notificationJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------- notification

    private fun observeNotification() {
        if (notificationJob != null) return
        notificationJob = scope.launch {
            session.state.collect { state ->
                if (!state.mining) return@collect
                val now = SystemClock.elapsedRealtime()
                if (now - lastPublished < UPDATE_INTERVAL_MS) return@collect
                lastPublished = now
                publish(state)
            }
        }
    }

    private fun startInForeground(state: MiningSessionState) {
        val notification = buildNotification(state)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        inForeground = true
    }

    private fun publish(state: MiningSessionState) {
        val manager = NotificationManagerCompat.from(this)
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(state))
        } catch (_: SecurityException) {
            /* POST_NOTIFICATIONS denied: the service still runs, the shade just
             * has nothing to show. The session is unaffected. */
        }
    }

    private fun buildNotification(state: MiningSessionState): Notification {
        val content = MinerNotificationContent.of(state)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MinerForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hashes)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.bigText))
            // A colorized card carries the terminal's phosphor identity into the
            // shade: the system paints the whole card in this field instead of a
            // generic surface and switches to light text on top of it.
            .setColorized(true)
            .setColor(NOTIFICATION_COLOR)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.notification_stop), stop)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    // ---------------------------------------------------------------- teardown

    private fun endSession() {
        session.stop()
        notificationJob?.cancel()
        notificationJob = null
        if (inForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.corejbh.hashes.action.START_MINING"
        const val ACTION_STOP = "com.corejbh.hashes.action.STOP_MINING"

        private const val CHANNEL_ID = "mining"
        private const val NOTIFICATION_ID = 0x485348 // "HSH"

        /** Deep phosphor green: white text stays legible on the colorized card. */
        private const val NOTIFICATION_COLOR = 0xFF2E7D32.toInt()

        /** The shade refreshes a little slower than the session loop. */
        private const val UPDATE_INTERVAL_MS = 1_000L
    }
}
