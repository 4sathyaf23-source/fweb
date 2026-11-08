package com.example.fweb

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var addressBar: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        addressBar = EditText(this).apply {
            hint = "Search or enter address"
            singleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
        }

        fun addButton(label: String, action: () -> Unit) {
            toolbar.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            })
        }

        addButton("←") {
            if (webView.canGoBack()) webView.goBack()
        }

        addButton("→") {
            if (webView.canGoForward()) webView.goForward()
        }

        toolbar.addView(
            addressBar,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        addButton("Go") { loadAddress() }
        addButton("↻") { webView.reload() }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    addressBar.setText(url.orEmpty())
                }
            }
            webChromeClient = WebChromeClient()
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

        page.addView(toolbar)
        page.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(page)
        webView.loadUrl("https://duckduckgo.com")
    }

    private fun loadAddress() {
        val input = addressBar.text.toString().trim()
        if (input.isEmpty()) return

        val url = when {
            input.startsWith("https://", ignoreCase = true) ||
                input.startsWith("http://", ignoreCase = true) -> input
            input.contains(" ") || !input.contains(".") ->
                "https://duckduckgo.com/?q=${Uri.encode(input)}"
            else -> "https://$input"
        }

        webView.loadUrl(url)
    }

    @Deprecated("Deprecated by Android; retained for basic back-button handling")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
