package com.ultidraft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ultidraft.data.BookSession
import com.ultidraft.ui.UltidraftApp
import com.ultidraft.ui.UltidraftTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UltidraftTheme {
                UltidraftApp()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Cheap, local, and frequent. The synced sidecar is only written on pause.
        BookSession.rememberPosition(this)
    }
}
