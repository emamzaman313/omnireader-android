package ir.hamghadam.omnireader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MainActivity : Activity() {
    private data class SharedFile(val uri: Uri, val mime: String)
    private val sharedFiles = ConcurrentHashMap<String, SharedFile>()
    private lateinit var webView: WebView
    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/shared/", object : WebViewAssetLoader.PathHandler {
                override fun handle(path: String): WebResourceResponse? {
                    val item = sharedFiles[path] ?: return null
                    return try {
                        val stream = contentResolver.openInputStream(item.uri) ?: return null
                        WebResourceResponse(item.mime, null, 200, "OK",
                            mapOf("Cache-Control" to "no-store"), stream)
                    } catch (_: Exception) { null }
                }
            }).build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncoming(intent)
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                deliverFiles()
            }
        }
        setContentView(webView)
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
        deliverFiles()
    }

    private fun handleIncoming(source: Intent?) {
        if (source == null) return
        val uris = mutableListOf<Uri>()
        source.data?.let { uris.add(it) }
        source.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add)
        }
        if (source.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            (source.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let(uris::add)
        }
        if (source.action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            source.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::addAll)
        }
        uris.distinct().forEach { uri ->
            val id = UUID.randomUUID().toString()
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            sharedFiles[id] = SharedFile(uri, mime)
            pending.add(mapOf("id" to id, "name" to displayName(uri), "mime" to mime))
        }
    }

    private val pending = mutableListOf<Map<String, String>>()
    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "file"
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun deliverFiles() {
        if (pending.isEmpty() || !::webView.isInitialized) return
        val json = org.json.JSONArray()
        pending.forEach { item ->
            json.put(org.json.JSONObject()
                .put("name", item["name"])
                .put("mime", item["mime"])
                .put("url", "https://appassets.androidplatform.net/shared/" + item["id"]))
        }
        pending.clear()
        webView.post {
            webView.evaluateJavascript("window.onAndroidFiles && window.onAndroidFiles($json)", null)
        }
    }
}
