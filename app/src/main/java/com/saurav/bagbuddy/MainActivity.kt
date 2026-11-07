package com.saurav.bagbuddy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.saurav.bagbuddy.companion.MusicSessionManager
import com.saurav.bagbuddy.simulator.BagBuddyScreen
import com.saurav.bagbuddy.ui.theme.BagBuddyTheme

class MainActivity : ComponentActivity() {
    private val musicSessionManager by lazy(LazyThreadSafetyMode.NONE) {
        MusicSessionManager(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BagBuddyTheme {
                BagBuddyScreen(mediaSessionRepository = musicSessionManager)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        musicSessionManager.start()
    }

    override fun onResume() {
        super.onResume()
        musicSessionManager.refresh()
    }

    override fun onStop() {
        musicSessionManager.stop()
        super.onStop()
    }
}
