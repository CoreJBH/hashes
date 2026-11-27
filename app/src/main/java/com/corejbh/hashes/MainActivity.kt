package com.corejbh.hashes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.corejbh.hashes.ui.HashesTheme
import com.corejbh.hashes.ui.MinerScreen

/**
 * One screen for now: the Phase 1 placeholder that runs the engine against a
 * fixed header so the native core can be seen working on a device.
 *
 * The session is created here rather than inside composition so the activity's
 * lifecycle is what opens and closes the engine, and screen-off is what stops
 * it.
 */
class MainActivity : ComponentActivity() {

    private lateinit var session: MinerSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = MinerSession(this)
        setContent {
            HashesTheme {
                MinerScreen(session)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        session.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        session.close()
    }
}
