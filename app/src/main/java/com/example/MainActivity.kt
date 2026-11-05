package com.example

import android.app.PictureInPictureParams
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.core.view.WindowCompat
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
    private var networkCallbackRegistered = false

    private val connectNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            activeBrowserViewModel?.onNetworkAvailabilityChanged(true)
        }

        override fun onLost(network: Network) {
            activeBrowserViewModel?.onNetworkAvailabilityChanged(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val darkWindowBg = AndroidColor.parseColor("#060E1B")
        val darkNavBarBg = AndroidColor.parseColor("#071222")
        window.setBackgroundDrawable(ColorDrawable(darkWindowBg))
        window.decorView.setBackgroundColor(darkWindowBg)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(darkNavBarBg)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            @Suppress("DEPRECATION")
            window.isStatusBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

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

            LaunchedEffect(vm) {
                vm.onAppForegrounded(applicationContext)
            }

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

            // Apply browser orientation preference when not in immersive fullscreen video
            LaunchedEffect(settings.browserOrientationMode) {
                val targetOrientation = when (settings.browserOrientationMode) {
                    com.example.data.local.BrowserOrientationMode.SYSTEM ->
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    com.example.data.local.BrowserOrientationMode.PORTRAIT ->
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    com.example.data.local.BrowserOrientationMode.LANDSCAPE ->
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    com.example.data.local.BrowserOrientationMode.SENSOR ->
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                }
                if ((window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN) == 0) {
                    requestedOrientation = targetOrientation
                }
            }

            NyxoraTheme(themeMode = settings.themeMode) {
                if (!splashCompleted && settings.launchAnimationEnabled) {
                    NyxoraLaunchSplash(
                        reducedMotion = settings.reducedMotion,
                        logoGlowIntensityScale = settings.logoGlowIntensity,
                        performanceMode = settings.animationPerformanceMode,
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

    override fun onStart() {
        super.onStart()
        activeBrowserViewModel?.onAppForegrounded(this)
        if (!networkCallbackRegistered) {
            runCatching {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.registerDefaultNetworkCallback(connectNetworkCallback)
                networkCallbackRegistered = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activeBrowserViewModel?.onAppForegrounded(this)
        activeBrowserViewModel?.refreshApkInstallPermission(this)
        activeBrowserViewModel?.checkForApkUpdates(force = false)
    }

    override fun onStop() {
        if (networkCallbackRegistered) {
            runCatching {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(connectNetworkCallback)
            }
            networkCallbackRegistered = false
        }
        if (isFinishing) {
            val vm = activeBrowserViewModel
            if (vm?.settings?.value?.clearDataOnExit == true) {
                vm.clearBrowsingData(clearHistory = true, clearCookiesAndStorage = true)
            }
        }
        activeBrowserViewModel?.onAppBackgrounded(this)
        super.onStop()
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
            val vm = activeBrowserViewModel
            if (vm?.settings?.value?.clearDataOnExit == true) {
                vm.clearBrowsingData(clearHistory = true, clearCookiesAndStorage = true)
            }
            vm?.wipePrivateSessionData()
        }
        activeBrowserViewModel = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SKIP_SPLASH = "com.example.extra.SKIP_SPLASH"
    }
}

