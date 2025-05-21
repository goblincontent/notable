package com.olup.notable

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import com.olup.notable.classes.AnonymousUserManager

class WebViewActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingText: TextView
    
    companion object {
        private const val TAG = "WebViewActivity"
        private const val EXTRA_AUTH_URL = "auth_url"
        
        fun createIntent(context: Context, authUrl: String? = null): Intent {
            return Intent(context, WebViewActivity::class.java).apply {
                putExtra(EXTRA_AUTH_URL, authUrl)
            }
        }
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set a simple layout with WebView
        setContentView(R.layout.activity_webview)
        
        // Set title
        title = "Web Authentication"
        
        // Get references to views
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        loadingText = findViewById(R.id.loadingText)
        
        // Set a timeout to close the activity if auth takes too long
        webView.postDelayed({
            if (!isFinishing && !isDestroyed) {
                Log.d(TAG, "Auth timeout - closing WebViewActivity")
                finishWithCleanup()
            }
        }, 120000) // 2 minute timeout
        
        // Configure WebView - optimized for e-ink
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            allowContentAccess = true
            allowFileAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            
            // E-ink optimizations
            blockNetworkImage = false  // Allow images but they may not render well
            loadsImagesAutomatically = true
            defaultTextEncodingName = "UTF-8"
            
            // Disable animations and transitions that might not work well on e-ink
            setLayoutAlgorithm(android.webkit.WebSettings.LayoutAlgorithm.NORMAL)
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        
        // Disable hardware acceleration which can cause issues on e-ink
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        
        // Set WebViewClient with e-ink optimizations
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                loadingText.visibility = View.VISIBLE
                webView.visibility = View.INVISIBLE
                Log.d(TAG, "Page started loading: $url")
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                
                // Hide loading indicators
                progressBar.visibility = View.GONE
                loadingText.visibility = View.GONE
                
                // Show WebView
                webView.visibility = View.VISIBLE
                
                // Force a refresh for e-ink display
                webView.invalidate()
                
                // Execute JavaScript to simplify the page for e-ink
                webView.evaluateJavascript(
                    "" +
                    "document.body.style.backgroundColor = '#FFFFFF';" +
                    "document.body.style.color = '#000000';" +
                    "var elements = document.querySelectorAll('*');" +
                    "for (var i = 0; i < elements.length; i++) {" +
                    "  var el = elements[i];" +
                    "  el.style.transition = 'none';" +
                    "  el.style.animation = 'none';" +
                    "}",
                    null
                )
                
                Log.d(TAG, "Page loaded: $url")
            }
            
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                Log.e(TAG, "Error loading page: $description")
                loadingText.text = "Error: $description"
                progressBar.visibility = View.GONE
            }
        }
        
        // Add JavaScript interface
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidAuth")
        
        // Load URL
        val authUrl = intent.getStringExtra(EXTRA_AUTH_URL) ?: "https://einkassistant.com/android-auth"
        Log.d(TAG, "Loading URL: $authUrl")
        webView.loadUrl(authUrl)
    }
    
    // Helper method to properly finish the activity and clean up WebView
    fun finishWithCleanup() {
        try {
            // Clean up WebView properly
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.clearCache(true)
            webView.clearFormData()
            
            // Remove all WebView callbacks
            webView.setWebViewClient(WebViewClient()) // Set to empty WebViewClient instead of null
            webView.removeJavascriptInterface("AndroidAuth")
            
            // Set result and finish
            setResult(RESULT_OK)
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error during WebView cleanup", e)
            finish()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            // Clean up WebView properly
            webView.stopLoading()
            webView.clearHistory()
            webView.clearCache(true)
            // Remove WebView from its parent before destroying
            val parent = webView.parent as? android.view.ViewGroup
            parent?.removeView(webView)
            // Now it's safe to destroy the WebView
            webView.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error during WebView destruction", e)
        }
    }
    
    class WebAppInterface(private val context: Context) {
        private val anonymousUserManager = AnonymousUserManager(context)
        
        @JavascriptInterface
        fun getAnonymousId(): String {
            val id = anonymousUserManager.getAnonymousId()
            Log.d(TAG, "Providing anonymous ID to web: $id")
            return id
        }
        
        @JavascriptInterface
        fun onAuthSuccess(token: String) {
            Log.d(TAG, "Auth success received from web with token")
            val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("auth_token", token).apply()
            
            // Close the WebView activity using runOnUiThread to ensure UI operations happen on the main thread
            if (context is WebViewActivity) {
                (context as WebViewActivity).runOnUiThread {
                    context.finishWithCleanup()
                }
            }
        }
        
        companion object {
            private const val TAG = "WebAppInterface"
        }
    }
}
