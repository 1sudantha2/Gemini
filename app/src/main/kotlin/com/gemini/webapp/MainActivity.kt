package com.gemini.webapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        const val HOME = "https://gemini.google.com/app"
        private val ALLOWED_HOSTS = listOf(
            "gemini.google.com", "accounts.google.com", "myaccount.google.com",
            "accounts.youtube.com", "google.com", "gstatic.com", "googleusercontent.com",
            "googleapis.com", "aistudio.google.com", "one.google.com"
        )
        // Desktop-class Chrome UA on Android keeps Google serving the full, fast web app.
        private const val UA_SUFFIX = " GeminiApp/1.0"

        // Injected once per page: momentum scrolling, no tap-highlight flash, no 300 ms tap delay,
        // GPU-promoted scroll containers and no overscroll glow/refresh gestures inside the page.
        private const val SMOOTH_JS = """(function(){
if(window.__gmPerf)return;window.__gmPerf=1;
var s=document.createElement('style');s.id='__gm_smooth';
s.textContent=[
'html,body{overscroll-behavior-y:none!important;-webkit-tap-highlight-color:transparent!important}',
'::-webkit-scrollbar{width:0!important;height:0!important}',
/* Off-screen chat turns are skipped by layout & paint -> long chats scroll like short ones */
'.conversation-container,user-query,model-response,message-content{content-visibility:auto;contain-intrinsic-size:auto 320px}',
/* GPU-expensive effects on low-end phones */
'*{backdrop-filter:none!important;-webkit-backdrop-filter:none!important;text-shadow:none!important}',
'.response-container,.markdown,.markdown *,code,pre{box-shadow:none!important;animation:none!important;transition:none!important}',
/* Streaming text: no per-chunk fade/blur/typing animations */
'.streaming *,[class*=streaming],[class*=typing],[class*=shimmer],[class*=skeleton]{animation:none!important;transition:none!important;filter:none!important}'
].join('');
(document.head||document.documentElement).appendChild(s);
/* Throttle the site's per-token scroll-into-view calls during streaming to at most one per frame */
var raf=0,orig=Element.prototype.scrollIntoView;
Element.prototype.scrollIntoView=function(a){var el=this;if(raf)return;raf=requestAnimationFrame(function(){raf=0;try{orig.call(el,a&&typeof a==='object'?Object.assign({},a,{behavior:'auto'}):a)}catch(e){}})};
})();"""
    }

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var offline: View
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private var firstPaintDone = false

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val cb = fileCallback ?: return@registerForActivityResult
        cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(it.resultCode, it.data))
        fileCallback = null
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val req = pendingPermission ?: return@registerForActivityResult
        val granted = req.resources.filter { r ->
            when (r) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> grants[Manifest.permission.RECORD_AUDIO] == true
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> grants[Manifest.permission.CAMERA] == true
                else -> false
            }
        }
        if (granted.isEmpty()) req.deny() else req.grant(granted.toTypedArray())
        pendingPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Keep the splash up only until the WebView has painted its first frame.
        splash.setKeepOnScreenCondition { !firstPaintDone }

        setContentView(R.layout.activity_main)
        web = findViewById(R.id.webview)
        progress = findViewById(R.id.progress)
        offline = findViewById(R.id.offline)
        findViewById<View>(R.id.retry).setOnClickListener { offline.visibility = View.GONE; web.reload() }

        configureWebView()

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(intent?.dataString?.takeIf { it.startsWith("https://gemini.google.com") } ?: HOME)

        // Safety: never keep the splash longer than 1.5 s even if paint callback is late.
        web.postDelayed({ firstPaintDone = true }, 1500)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        // LAYER_TYPE_NONE lets Chromium's own compositor drive rendering (faster than a
        // forced hardware layer, which re-uploads the full texture on every scroll frame).
        web.setLayerType(View.LAYER_TYPE_NONE, null)
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isNestedScrollingEnabled = false
        web.isHapticFeedbackEnabled = false
        web.isLongClickable = true

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT   // static shell is served from ShellCache; content must stay fresh
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            loadWithOverviewMode = false
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkImage = false
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Low-RAM friendly: default text zoom & no legacy plugins/geolocation.
            textZoom = 100
            setGeolocationEnabled(false)
            userAgentString = userAgentString.replace("; wv", "") + UA_SUFFIX
            offscreenPreRaster = false  // extra raster/RAM on low-end GPUs -> more jank than it saves
        }

        // Gemini ships its own native dark theme; algorithmic darkening would re-process every
        // paint on the CPU and is a major source of scroll/streaming jank -> keep it OFF.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Never let the OS throttle the renderer while we are visible (prevents "freezes"
            // that appear after the app has been open for a while).
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            // Chromium already defers offscreen work; make sure prerender/prefetch is on.
            web.settings.safeBrowsingEnabled = false
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (!url.startsWith("https://")) return null
                val u = try { URL(url) } catch (_: Exception) { return null }
                return if (ShellCache.isStatic(u, request.method, request.requestHeaders["Accept"]))
                    ShellCache.serve(u, request.requestHeaders) else null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host ?: return false
                val internal = ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
                if (internal && uri.scheme == "https") return false
                // External links & non-https schemes -> system handler.
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
                progress.progress = 5
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                firstPaintDone = true
                offline.visibility = View.GONE
                view.evaluateJavascript(SMOOTH_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                firstPaintDone = true
                view.evaluateJavascript(SMOOTH_JS, null)
                progress.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame && !ShellCache.hasCachedShell()) {
                    firstPaintDone = true
                        progress.visibility = View.GONE
                    offline.visibility = View.VISIBLE
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                // Renderer was killed by the OS (memory pressure). Rebuild instead of crashing.
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                recreate()
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                if (newProgress >= 100) progress.visibility = View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try { filePicker.launch(params.createIntent()); true } catch (_: Exception) { fileCallback = null; false }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val needed = mutableListOf<String>()
                request.resources.forEach {
                    when (it) {
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> needed += Manifest.permission.RECORD_AUDIO
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> needed += Manifest.permission.CAMERA
                    }
                }
                if (needed.isEmpty()) { request.deny(); return }
                val missing = needed.filter { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) request.grant(request.resources)
                else { pendingPermission = request; permissions.launch(missing.toTypedArray()) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.takeIf { it.startsWith("https://gemini.google.com") }?.let { web.loadUrl(it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() { super.onResume(); web.onResume(); web.resumeTimers() }

    override fun onPause() {
        // Pause JS timers when backgrounded -> near-zero CPU while not visible.
        web.onPause(); web.pauseTimers()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        (web.parent as? ViewGroup)?.removeView(web)
        web.stopLoading()
        web.webChromeClient = null
        web.destroy()
        super.onDestroy()
    }
}
