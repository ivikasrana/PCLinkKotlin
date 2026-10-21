package com.vik.pclink

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import com.vik.pclink.ui.PCLinkApp
import com.vik.pclink.ui.copyText

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)
    private var pendingCopy: String? = null

    private fun applyBars(dark: Boolean) {
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) pendingCopy = intent.getStringExtra(EXTRA_COPY)
        setContent { PCLinkApp(resumeTick.intValue, onDark = ::applyBars) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingCopy = intent.getStringExtra(EXTRA_COPY)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val text = pendingCopy
        if (hasFocus && text != null) {
            pendingCopy = null
            copyText(this, text)
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_COPY = "copy"
    }
}
