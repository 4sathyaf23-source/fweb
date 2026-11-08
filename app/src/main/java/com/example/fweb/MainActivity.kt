package com.example.fweb

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.util.Patterns
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts

/**
 * fweb — a small, security-minded WebView browser.
 *
 * Needs: androidx.activity:activity-ktx, INTERNET permission, and (API < 29 only)
 * WRITE_EXTERNAL_STORAGE for downloads. See manifest notes in the chat reply.
 */
class MainActivity : ComponentActivity() {

    private lateinit var page: LinearLayout
    private lateinit var webView: WebView
    private lateinit var addressBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var backButton: Button
    private lateinit var forwardButton: Button
    private lateinit var refreshButton: Button

    private lateinit var defaultUserAgent: String
    private var desktopMode = false
    private var isLoading = false
    private var currentTitle = ""
    private var currentUrl = ""

    // Fullscreen video
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // <input type="file"> support
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            filePathCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
            filePathCallback = null
        }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)

        buildUi()

        defaultUserAgent = webView.settings.userAgentString
        desktopMode = savedInstanceState?.getBoolean(KEY_DESKTOP) ?: false
        if (desktopMode) applyDesktopMode(reload = false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        val restored = savedInstanceState != null && webView.restoreState(savedInstanceState) != null
        if (!restored) webView.loadUrl(incomingUrl(intent) ?: HOME_URL)
        updateButtons()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingUrl(intent)?.let { webView.loadUrl(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
        outState.putBoolean(KEY_DESKTOP, desktopMode)
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        titleView = TextView(this).apply {
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }

        addressBar = EditText(this).apply {
            hint = "Search or enter address"
            setSingleLine(true)
            setSelectAllOnFocus(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    loadAddress()
                    true
                } else false
            }
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }

        backButton = makeButton("←") { if (webView.canGoBack()) webView.goBack() }
        forwardButton = makeButton("→") { if (webView.canGoForward()) webView.goForward() }
        refreshButton = makeButton("↻") { if (isLoading) webView.stopLoading() else webView.reload() }
        val menuButton = makeButton("⋮") { showMenu(it) }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(backButton, LinearLayout.LayoutParams(dp(44), dp(48)))
            addView(forwardButton, LinearLayout.LayoutParams(dp(44), dp(48)))
            addView(addressBar, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(refreshButton, LinearLayout.LayoutParams(dp(44), dp(48)))
            addView(menuButton, LinearLayout.LayoutParams(dp(44), dp(48)))
        }

        webView = createWebView()

        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(android.R.attr.colorBackground, 0xFFFFFFFF.toInt()))
            addView(toolbar)
            addView(progressBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))
            addView(titleView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)))
            addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

            // Edge-to-edge: keep the toolbar clear of system bars and the keyboard.
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                    v.setPadding(b.left, b.top, b.right, b.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(
                        insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                    )
                }
                insets
            }
        }
        setContentView(page)
    }

    private fun createWebView(): WebView = WebView(this).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleNavigation(request)

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                currentUrl = url.orEmpty()
                if (!addressBar.hasFocus()) addressBar.setText(currentUrl)
                refreshTitle()
                updateButtons()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                currentUrl = url.orEmpty()
                if (!addressBar.hasFocus()) addressBar.setText(currentUrl)
                refreshTitle()
                updateButtons()
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (!request.isForMainFrame) return
                val url = TextUtils.htmlEncode(request.url.toString())
                val reason = TextUtils.htmlEncode(error.description.toString())
                val html = """
                    <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                    <style>body{font-family:sans-serif;padding:32px;color:#444}
                    a{color:#1a73e8}</style></head>
                    <body><h2>Can't open this page</h2><p>$reason</p>
                    <p style="word-break:break-all">$url</p>
                    <p><a href="$url">Try again</a></p></body></html>
                """.trimIndent()
                view.loadDataWithBaseURL(request.url.toString(), html, "text/html", "UTF-8", null)
            }
            // SSL errors: default behaviour (cancel) is kept on purpose.
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, progress: Int) {
                isLoading = progress < 100
                progressBar.progress = progress
                progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                refreshButton.text = if (isLoading) "×" else "↻"
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                currentTitle = title.orEmpty()
                refreshTitle()
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    fileChooser.launch(params.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    filePathCallback = null
                    callback.onReceiveValue(null)
                    false
                }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                page.visibility = View.GONE
                addContentView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                setImmersive(true)
            }

            override fun onHideCustomView() = hideCustomView()
        }

        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun makeButton(label: String, action: (View) -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { action(it) }
        }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Home")
            menu.add(0, 2, 1, "Share link")
            menu.add(0, 3, 2, "Open in another app")
            menu.add(0, 4, 3, "Desktop site").apply {
                isCheckable = true
                isChecked = desktopMode
            }
            menu.add(0, 5, 4, "Clear browsing data")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> webView.loadUrl(HOME_URL)
                    2 -> shareCurrentPage()
                    3 -> openExternally(currentUrl)
                    4 -> {
                        desktopMode = !desktopMode
                        applyDesktopMode(reload = true)
                    }
                    5 -> clearBrowsingData()
                }
                true
            }
            show()
        }
    }

    // ---------------------------------------------------------------- navigation

    private fun incomingUrl(i: Intent?): String? {
        if (i?.action != Intent.ACTION_VIEW) return null
        val data = i.data ?: return null
        return if (data.scheme.equals("http", true) || data.scheme.equals("https", true)) {
            data.toString()
        } else null
    }

    private fun loadAddress() {
        val input = addressBar.text.toString().trim()
        if (input.isEmpty()) return
        webView.loadUrl(addressOrSearchUrl(input))
        addressBar.clearFocus()
        webView.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(addressBar.windowToken, 0)
    }

    private fun addressOrSearchUrl(input: String): String {
        val hasScheme = input.startsWith("https://", true) || input.startsWith("http://", true)
        val candidate = when {
            hasScheme -> input
            !input.contains(' ') &&
                (Patterns.WEB_URL.matcher(input).matches() || input.startsWith("localhost")) ->
                "https://$input"
            else -> return searchUrl(input)
        }
        val uri = runCatching { Uri.parse(candidate) }.getOrNull()
        return if (uri?.host.isNullOrBlank()) searchUrl(input) else candidate
    }

    private fun searchUrl(query: String) = "$SEARCH_URL${Uri.encode(query)}"

    private fun handleNavigation(request: WebResourceRequest): Boolean {
        val uri = request.url
        val scheme = uri.scheme?.lowercase()

        if (scheme == "https" || scheme == "http") return false

        // Only follow non-web links the user actually tapped, never silent redirects.
        if (!request.hasGesture()) return true

        when (scheme) {
            "mailto", "tel", "geo", "sms" -> openExternally(uri.toString())
            "intent" -> {
                val i = runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
                if (i != null) {
                    i.addCategory(Intent.CATEGORY_BROWSABLE)
                    i.component = null
                    i.selector = null
                    try {
                        startActivity(i)
                    } catch (e: ActivityNotFoundException) {
                        val fallback = i.getStringExtra("browser_fallback_url")
                        if (fallback != null &&
                            (fallback.startsWith("https://") || fallback.startsWith("http://"))
                        ) webView.loadUrl(fallback)
                        else toast("No app can open this link")
                    }
                }
            }
        }
        return true
    }

    private fun openExternally(url: String) {
        if (url.isBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            toast("No app can open this link")
        }
    }

    private fun shareCurrentPage() {
        if (currentUrl.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, currentTitle)
            putExtra(Intent.EXTRA_TEXT, currentUrl)
        }
        startActivity(Intent.createChooser(send, "Share link"))
    }

    // ---------------------------------------------------------------- features

    private fun applyDesktopMode(reload: Boolean) {
        webView.settings.apply {
            userAgentString = if (desktopMode) {
                defaultUserAgent
                    .replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)")
                    .replace(" Mobile", "")
            } else defaultUserAgent
            useWideViewPort = desktopMode
            loadWithOverviewMode = desktopMode
        }
        if (reload) webView.reload()
    }

    private fun clearBrowsingData() {
        webView.clearCache(true)
        webView.clearHistory()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        updateButtons()
        toast("Browsing data cleared")
    }

    private fun startDownload(url: String, userAgent: String?, disposition: String?, mime: String?) {
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            toast("This download type isn't supported")
            return
        }
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mime)
                userAgent?.let { addRequestHeader("User-Agent", it) }
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, disposition, mime)
                )
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast("Downloading…")
        } catch (e: SecurityException) {
            toast("Storage permission needed to download")
        } catch (e: Exception) {
            toast("Download failed")
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun hideCustomView() {
        val view = customView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        page.visibility = View.VISIBLE
        setImmersive(false)
    }

    private fun setImmersive(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (enabled) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                c.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                if (enabled) {
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                } else View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun refreshTitle() {
        val secure = currentUrl.startsWith("https://", true)
        val prefix = when {
            currentUrl.isBlank() || currentUrl.startsWith("data:") -> ""
            secure -> "🔒 "
            else -> "⚠ Not secure · "
        }
        titleView.text = prefix + currentTitle
    }

    private fun updateButtons() {
        backButton.isEnabled = webView.canGoBack()
        forwardButton.isEnabled = webView.canGoForward()
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val HOME_URL = "https://duckduckgo.com"
        private const val SEARCH_URL = "https://duckduckgo.com/?q="
        private const val KEY_DESKTOP = "desktop_mode"
    }
}
