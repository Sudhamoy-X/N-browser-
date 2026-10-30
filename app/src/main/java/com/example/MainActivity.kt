package com.example

import android.app.PictureInPictureParams
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.domain.browser.BrowserNetworkClient
import com.example.ui.components.NyxoraLaunchSplash
import com.example.ui.screens.NyxoraMainBrowserScreen
import com.example.ui.theme.NyxoraTheme
import com.example.ui.viewmodel.NyxoraBrowserViewModel

class MainActivity : ComponentActivity() {

    internal var activeBrowserViewModel: NyxoraBrowserViewModel? = null
    private var pendingIntentUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialIntentUrl = intent?.dataString?.trim()
        if (savedInstanceState == null && !initialIntentUrl.isNullOrBlank()) {
            pendingIntentUrl = initialIntentUrl
        }
        val skipSplash = intent?.getBooleanExtra(EXTRA_SKIP_SPLASH, false) == true ||
            !initialIntentUrl.isNullOrBlank()

        setContent {
            var splashCompleted by rememberSaveable { mutableStateOf(skipSplash) }

            val vm: NyxoraBrowserViewModel = viewModel(
                key = "nyxora_browser_vm",
                factory = NyxoraBrowserViewModel.provideFactory()
            )
            activeBrowserViewModel = vm

            val settings by vm.settings.collectAsStateWithLifecycle()
            val isPrivateMode by vm.isPrivateMode.collectAsStateWithLifecycle()

            // Warm up browser engine, HTTP cache, and DNS prefetch AFTER the first frame is drawn
            LaunchedEffect(Unit) {
                withFrameNanos { /* Wait for first UI frame to complete */ }
                BrowserNetworkClient.warmUpEngineAfterFirstFrame(applicationContext)
            }

            // Handle initial or new incoming VIEW intent URLs (including HTTPS App Links & nyxora://connect)
            val externalUrl = pendingIntentUrl
            LaunchedEffect(externalUrl) {
                if (!externalUrl.isNullOrBlank()) {
                    pendingIntentUrl = null
                    splashCompleted = true
                    vm.navigateInActiveTab(externalUrl)
                }
            }

            // Block screenshots / Recents previews in Private Mode when enabled
            LaunchedEffect(isPrivateMode, settings.incognitoBlockScreenshots) {
                if (isPrivateMode && settings.incognitoBlockScreenshots) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            NyxoraTheme(themeMode = settings.themeMode) {
                if (!splashCompleted) {
                    NyxoraLaunchSplash(
                        reducedMotion = settings.reducedMotion,
                        onSplashCompleted = { splashCompleted = true }
                    )
                } else {
                    NyxoraMainBrowserScreen(
                        viewModel = vm
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incomingUrl = intent.dataString?.trim()
        if (!incomingUrl.isNullOrBlank()) {
            pendingIntentUrl = incomingUrl
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val vm = activeBrowserViewModel ?: return
        val currentSettings = vm.settings.value
        val currentTab = vm.activeTab.value
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            currentSettings.pipEnabled &&
            !currentTab.isHomePage &&
            window.decorView.keepScreenOn
        ) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Exception) {
                // Ignore if device or current state does not support PiP
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.example.domain.browser.BrowserDiagnosticsEngine.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            com.example.domain.browser.FaviconCacheManager.trimMemory()
            val vm = activeBrowserViewModel
            vm?.suspendExcessInactiveTabs(forceAggressive = true)
            if (vm?.hasInitializedWebViewEngine == true && java.io.File("/dev/dri/renderD128").exists()) {
                runCatching { CookieManager.getInstance().flush() }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            activeBrowserViewModel?.wipePrivateSessionData()
        }
        activeBrowserViewModel = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SKIP_SPLASH = "com.example.extra.SKIP_SPLASH"
    }
}

