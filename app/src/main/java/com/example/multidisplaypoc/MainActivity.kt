package com.example.multidisplaypoc

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.http.SslError
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.SslErrorHandler
import android.content.Intent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.viewinterop.AndroidView
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.media.projection.MediaProjectionConfig
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.multidisplaypoc.ui.theme.MultiDisplayPOCTheme

private data class DisplayTarget(
    val id: Int,
    val name: String,
    val supportsPresentation: Boolean
)

class MainActivity : ComponentActivity() {
    private val displayManager by lazy {
        getSystemService(DISPLAY_SERVICE) as DisplayManager
    }

    private val presentations = mutableMapOf<Int, MirrorPresentation>()
    private var projecting = false
    private var desiredIds = emptySet<Int>()
    private var selectedIds by mutableStateOf(emptySet<Int>())
    private var mirrorError by mutableStateOf<String?>(null)

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                projecting = true
                startForegroundService(
                    Intent(this, MirrorService::class.java)
                        .putExtra(MirrorService.EXTRA_CODE, result.resultCode)
                        .putExtra(MirrorService.EXTRA_DATA, data)
                )
                syncPresentations()
            } else {
                desiredIds = emptySet()
            }
        }

    private var airConsoleWebView: WebView? = null

    // Created on first use, so the page loads while it has a real size; reused across tab switches.
    private fun airConsole(): WebView =
        airConsoleWebView ?: createAirConsoleWebView(this).also { airConsoleWebView = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MultiDisplayPOCTheme {
                var selectedTab by rememberSaveable { mutableStateOf(0) }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Text("▶") },
                                label = { Text("Share") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Text("🎮") },
                                label = { Text("AirConsole") }
                            )
                            NavigationBarItem(
                                selected = false,
                                onClick = { finishAndRemoveTask() },
                                icon = { Text("X") },
                                label = { Text("Exit") }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        if (selectedTab == 0) {
                            DisplaySharingScreen(
                                displayManager = displayManager,
                                sourceDisplayId = Display.DEFAULT_DISPLAY,
                                selectedDisplayIds = selectedIds,
                                isMirroring = projecting,
                                mirrorError = mirrorError,
                                onToggle = ::onToggle,
                                onStartMirroring = ::startMirroring
                            )
                        } else {
                            AirConsoleScreen(airConsole())
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        airConsoleWebView?.onResume()
    }

    override fun onPause() {
        airConsoleWebView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        airConsoleWebView?.destroy()
        airConsoleWebView = null
        presentations.values.forEach { it.dismiss() }
        presentations.clear()
        if (projecting) stopService(Intent(this, MirrorService::class.java))
        super.onDestroy()
    }

    private fun onToggle(id: Int, selected: Boolean) {
        if (selected) {
            if (id !in selectedIds) {
                selectedIds = selectedIds + id
                onSelectionChanged(selectedIds)
            }
        } else if (id in selectedIds) {
            selectedIds = selectedIds - id
            onSelectionChanged(selectedIds)
        }
    }

    private fun onSelectionChanged(ids: Set<Int>) {
        desiredIds = ids
        mirrorError = null
        when {
            ids.isEmpty() && projecting -> {
                projecting = false
                stopService(Intent(this, MirrorService::class.java))
                syncPresentations()
            }
            projecting -> syncPresentations()
        }
    }

    private fun startMirroring() {
        if (projecting || selectedIds.isEmpty()) return

        desiredIds = selectedIds
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun syncPresentations() {
        presentations.keys.filter { it !in desiredIds }.forEach { presentations.remove(it)?.dismiss() }
        if (!projecting) return
        desiredIds.filter { it !in presentations }.forEach { id ->
            val display = displayManager.getDisplay(id)
            if (display == null) {
                rejectDisplay(id, "The display is no longer available.")
                return@forEach
            }
            if ((display.flags and Display.FLAG_PRESENTATION) == 0) {
                rejectDisplay(id, "Android does not allow app presentations on this display.")
                return@forEach
            }

            val presentation = MirrorPresentation(this, display)
            try {
                presentation.show()
                presentations[id] = presentation
            } catch (error: WindowManager.InvalidDisplayException) {
                rejectDisplay(id, "Android rejected this display for mirroring.", error)
            } catch (error: SecurityException) {
                rejectDisplay(id, "The app is not permitted to present on this display.", error)
            }
        }
    }

    private fun rejectDisplay(id: Int, message: String, cause: Exception? = null) {
        reportPresentationError(id, message, cause)
        desiredIds = desiredIds - id
        selectedIds = selectedIds - id
        presentations.remove(id)?.dismiss()
    }

    private fun reportPresentationError(id: Int, message: String, cause: Exception? = null) {
        val detail = "Display $id: $message"
        android.util.Log.e("DisplayMirroring", detail, cause)
        mirrorError = detail
    }
}

@androidx.compose.runtime.Composable
private fun DisplaySharingScreen(
    displayManager: DisplayManager,
    sourceDisplayId: Int,
    selectedDisplayIds: Set<Int>,
    isMirroring: Boolean,
    mirrorError: String?,
    onToggle: (Int, Boolean) -> Unit,
    onStartMirroring: () -> Unit
) {
    var displays by remember { mutableStateOf(secondaryDisplays(displayManager, sourceDisplayId)) }

    DisposableEffect(displayManager, sourceDisplayId) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                displays = secondaryDisplays(displayManager, sourceDisplayId)
            }

            override fun onDisplayRemoved(displayId: Int) {
                displays = secondaryDisplays(displayManager, sourceDisplayId)
                onToggle(displayId, false)
            }

            override fun onDisplayChanged(displayId: Int) {
                val updatedDisplays = secondaryDisplays(displayManager, sourceDisplayId)
                displays = updatedDisplays
                if (updatedDisplays.none { it.id == displayId && it.supportsPresentation }) {
                    onToggle(displayId, false)
                }
            }
        }
        displayManager.registerDisplayListener(listener, null)

        onDispose {
            displayManager.unregisterDisplayListener(listener)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF0B1118)
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "AIRCONSOLE  /  MULTI-DISPLAY",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF79D6C1),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Share the game",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color(0xFFF2F5F7),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "One game on the main screen. View-only mirrors on other displays.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFB2BEC8)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel(title = "MIRROR DISPLAYS")
                Text(
                    text = "${selectedDisplayIds.size} selected",
                    color = Color(0xFFB2BEC8),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            if (displays.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF17232D))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "No other displays detected",
                            color = Color(0xFFF2F5F7),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Connect or enable a secondary AAOS display to select it here.",
                            color = Color(0xFFB2BEC8),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                displays.forEach { display ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF17232D))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = display.name,
                                    color = Color(0xFFF2F5F7),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (display.supportsPresentation) {
                                        "Display ${display.id} · view only"
                                    } else {
                                        "Display ${display.id} · unavailable for app mirroring"
                                    },
                                    color = Color(0xFFB2BEC8),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Switch(
                                checked = display.id in selectedDisplayIds,
                                enabled = display.supportsPresentation,
                                onCheckedChange = { selected -> onToggle(display.id, selected) }
                            )
                        }
                    }
                }
            }

            if (displays.any { !it.supportsPresentation }) {
                Text(
                    text = "Some detected displays are restricted by Android and cannot host app presentations. The device manufacturer must enable this for those screens.",
                    color = Color(0xFFFFC979),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            mirrorError?.let { error ->
                Text(
                    text = error,
                    color = Color(0xFFFF9E9E),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (isMirroring) {
                Text(
                    text = "Mirroring is active. Display selections update without asking again.",
                    color = Color(0xFF79D6C1),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Button(
                    onClick = onStartMirroring,
                    enabled = selectedDisplayIds.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start mirroring")
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF202B32))
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(text = "i", color = Color(0xFF79D6C1), fontWeight = FontWeight.Bold)
                    Text(
                        text = "Select all displays you want to mirror, then tap Start mirroring. Android requires screen-capture permission whenever a new mirroring session starts. Keep this app open while mirroring.",
                        color = Color(0xFFD0D8DE),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

private const val AIRCONSOLE_URL = "https://www.airconsole.com/"

@SuppressLint("SetJavaScriptEnabled")
private fun createAirConsoleWebView(context: Context): WebView {
    val webView = WebView(context)
    webView.setBackgroundColor(android.graphics.Color.BLACK)
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        useWideViewPort = true
        loadWithOverviewMode = true
        builtInZoomControls = false
        displayZoomControls = false
        setSupportZoom(false)
        javaScriptCanOpenWindowsAutomatically = true
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // AirConsole expects a desktop browser.
        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    }
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            android.util.Log.d("AirConsoleWeb", "finished $url")
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            android.util.Log.e("AirConsoleWeb", "error ${request?.url}: ${error?.description}")
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            android.util.Log.e("AirConsoleWeb", "http ${errorResponse?.statusCode} ${request?.url}")
        }
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            // Debug builds only: lets the emulator load pages behind a proxy that re-signs HTTPS.
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            if (debuggable) handler?.proceed() else handler?.cancel()
        }
    }
    webView.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
            android.util.Log.d("AirConsoleWeb", "${message?.message()} (${message?.sourceId()}:${message?.lineNumber()})")
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            request?.let { r -> r.grant(r.resources) }
        }
    }
    webView.loadUrl(AIRCONSOLE_URL)
    return webView
}

@androidx.compose.runtime.Composable
private fun AirConsoleScreen(webView: WebView) {
    var canGoBack by remember { mutableStateOf(false) }
    BackHandler(enabled = canGoBack) { webView.goBack() }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView
        },
        update = { canGoBack = it.canGoBack() }
    )
}

@androidx.compose.runtime.Composable
private fun SectionLabel(title: String) {
    Text(
        text = title,
        color = Color(0xFF91A2AE),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold
    )
}

private fun secondaryDisplays(
    displayManager: DisplayManager,
    sourceDisplayId: Int
): List<DisplayTarget> = displayManager.displays
    .filter { it.displayId != sourceDisplayId && it.name != MIRROR_CAPTURE_DISPLAY_NAME }
.map {
    DisplayTarget(
        id = it.displayId,
        name = it.name,
        supportsPresentation = (it.flags and Display.FLAG_PRESENTATION) != 0
    )
}
