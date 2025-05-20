package com.olup.notable

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.olup.notable.classes.AnonymousUserManager

class WebViewActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_AUTH_URL = "auth_url"
        
        fun createIntent(context: Context, authUrl: String? = null): Intent {
            return Intent(context, WebViewActivity::class.java).apply {
                putExtra(EXTRA_AUTH_URL, authUrl)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val authUrl = intent.getStringExtra(EXTRA_AUTH_URL) ?: "https://main.d1w6ier3v2wtaz.amplifyapp.com/android-auth"
        
        setContent {
            AuthWebView(
                url = authUrl,
                onClose = { finish() }
            )
        }
    }
    
    class WebAppInterface(private val context: Context) {
        private val anonymousUserManager = AnonymousUserManager(context)
        
        @JavascriptInterface
        fun getAnonymousId(): String {
            return anonymousUserManager.getAnonymousId()
        }
        
        @JavascriptInterface
        fun onAuthSuccess(token: String) {
            // Store the token
            val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("auth_token", token).apply()
            
            // Close the WebView activity
            if (context is WebViewActivity) {
                context.finish()
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AuthWebView(url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Authentication") },
                actions = {
                    FloatingActionButton(
                        onClick = onClose,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            val webView = remember {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = WebViewClient()
                    addJavascriptInterface(WebViewActivity.WebAppInterface(context), "AndroidAuth")
                }
            }
            
            AndroidView(
                factory = { webView },
                update = { it.loadUrl(url) }
            )
            
            DisposableEffect(Unit) {
                onDispose {
                    webView.destroy()
                }
            }
        }
    }
}
