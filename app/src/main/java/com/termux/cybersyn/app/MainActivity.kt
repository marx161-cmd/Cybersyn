package com.termux.cybersyn.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.termux.cybersyn.core.logging.AppLogger
import androidx.activity.compose.setContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import com.termux.cybersyn.core.contexts.NfcContextEvents
import com.termux.cybersyn.core.contexts.NfcTagWriteSession
import com.termux.cybersyn.core.engine.AutomationService
import com.termux.cybersyn.ui.screens.ActiveAutomationUi
import com.termux.cybersyn.ui.theme.CybersynTheme
import com.termux.cybersyn.ui.theme.ThemeMode
import com.termux.cybersyn.ui.theme.ThemePreference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            val themeMode by ThemePreference.observe(this).collectAsState(initial = ThemeMode.System)
            val darkTheme = when (themeMode) {
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
                ThemeMode.HighContrast -> true
                ThemeMode.System -> isSystemInDarkTheme()
            }
            CybersynTheme(darkTheme = darkTheme, highContrast = themeMode == ThemeMode.HighContrast) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ActiveAutomationUi(db = CybersynApp_NoHilt.db)
                }
            }
        }
        startAutomationService()
        handleNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: Intent?) {
        if (intent == null) return
        if (NfcTagWriteSession.isArmed()) {
            // Tag connect/write/format is blocking I/O; running it on the UI thread during
            // onCreate/onNewIntent risks jank or an ANR. Hop to a background thread and let
            // the result surface through NfcTagWriteSession.results.
            lifecycleScope.launch(Dispatchers.IO) {
                val writeResult = NfcTagWriteSession.writeFromIntent(intent)
                if (writeResult != null) {
                    AppLogger.debug("MainActivity", writeResult.message)
                }
            }
            return
        }
        if (NfcContextEvents.publishFromIntent(intent)) {
            AppLogger.debug("MainActivity", "NFC tag event accepted")
        }
    }

    private fun startAutomationService() {
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AutomationService::class.java)
                    .putExtra(AutomationService.EXTRA_STARTED_FROM_VISIBLE_UI, true),
            )
        }.onFailure { error ->
            AppLogger.error("MainActivity", "Failed to start Cybersyn automation service", error)
        }
    }
}
