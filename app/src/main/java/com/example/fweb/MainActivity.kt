package com.example.fweb

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var addressBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var backButton: Button
    private lateinit var forwardButton: Button
    private lateinit var refreshButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        titleView = TextView(this).apply {
            textSize = 14f
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
        }

        addressBar = EditText(this).apply {
            hint = "Search or enter address"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
        }

        progressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            visibility = ProgressBar.GONE
        }

        backButton = makeButton("←") {
            if (webView.canGoBack()) webView.goBack()
        }
        forwardButton = makeButton("→") {
            if (webView.canGoForward()) webView.goForward()
        }
        refreshButton = makeButton("↻") {
            if (progressBar.visibility == ProgressBar.VISIBLE) {
                webView.stopLoading()
            } else {
                webView.reload()
            }
        }

        val goButton = makeButton("Go") { loadAddress() }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(backButton)
            addView(forwardButton)
            addView(
                addressBar,
                LinearLayout.LayoutParams(0, dp(48), 1f)
            )
            addView(goButton)
            addView(refreshButton)
        }

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                // Leave file access and content access disabled unless your
                // app has a specific, carefully reviewed need for them.
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = handleNavigation(request.url)

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: Bitmap?
                ) {
                    addressBar.setText(url.orEmpty())
                    updateButtons()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    addressBar.setText(url.orEmpty())
                    updateButtons()
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, progress: Int) {
                    progressBar.progress = progress
                    progressBar.visibility =
                        if (progress < 100) ProgressBar.VISIBLE else ProgressBar.GONE
                    refreshButton.text = if (progress < 100) "×" else "↻"
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    titleView.text = title.orEmpty()
                }
            }
        }

        addressBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE
            ) {
                loadAddress()
                true
            } else {
                false
            }
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar)
            addView(progressBar)
            addView(
                titleView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(32)
                )
            )
            addView(
                webView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }

        setContentView(page)

        if (savedInstanceState == null) {
            webView.loadUrl("https://duckduckgo.com")
        } else {
            webView.restoreState(savedInstanceState)
        }
        updateButtons()
    }

    private fun makeButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun loadAddress() {
        val input = addressBar.text.toString().trim()
        if (input.isEmpty()) return

        val target = addressOrSearchUrl(input)
        webView.loadUrl(target)
    }

    private fun addressOrSearchUrl(input: String): String {
        val candidate = if (
            input.startsWith("https://", ignoreCase = true) ||
            input.startsWith("http://", ignoreCase = true)
        ) {
            input
        } else if (
            !input.contains(" ") &&
            input.contains(".")
        ) {
            "https://$input"
        } else {
            return "https://duckduckgo.com/?q=${Uri.encode(input)}"
        }

        val uri = runCatching { Uri.parse(candidate) }.getOrNull()
        return if (
            uri != null &&
            (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
            !uri.host.isNullOrBlank()
        ) {
            candidate
        } else {
            "https://duckduckgo.com/?q=${Uri.encode(input)}"
        }
    }

    private fun handleNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()

        if (scheme == "https" || scheme == "http") {
            return false // Let WebView load web pages.
        }

        // Hand non-web links to Android, rather than loading them in WebView.
        if (scheme in setOf("mailto", "tel", "geo")) {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }.onFailure {
                Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    private fun updateButtons() {
        if (!::webView.isInitialized) return
        backButton.isEnabled = webView.canGoBack()
        forwardButton.isEnabled = webView.canGoForward()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Use OnBackPressedDispatcher in a modern AndroidX app")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
