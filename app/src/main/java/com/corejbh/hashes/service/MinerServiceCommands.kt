package com.corejbh.hashes.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * The two ways a screen talks to [MinerForegroundService]: start the foreground
 * service with the session, or take it down.
 *
 * Starting goes through `ContextCompat.startForegroundService`, which is the only
 * call that lets Android 8+ create the service; the service then puts its
 * notification up within the platform's five seconds. Stopping uses
 * `stopService`, which is allowed from anywhere and ends in the service's
 * `onDestroy`, where the session is stopped and the notification removed.
 */
object MinerServiceCommands {

    fun start(context: Context) {
        val intent = Intent(context, MinerForegroundService::class.java)
            .setAction(MinerForegroundService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, MinerForegroundService::class.java))
    }
}
