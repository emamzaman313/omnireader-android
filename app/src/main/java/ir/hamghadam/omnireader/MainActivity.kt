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
    private data class PendingFile(val id: String, val name: String, val mime: String, val size: Long)
    private val sharedFiles = ConcurrentHashMap<String, SharedFile>()
    private val pending = mutableListOf<PendingFile>()
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
        source.data?.let(uris::add)
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
            val (name, size) = fileMetadata(uri)
            pending.add(PendingFile(id, name, mime, size))
        }
    }

    private fun fileMetadata(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "file"
        var size = 0L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).coerceAtLeast(0L)
            }
        }
        return name to size
    }

    private fun deliverFiles() {
        if (pending.isEmpty() || !::webView.isInitialized) return
        val json = org.json.JSONArray()
        pending.forEach { item ->
            json.put(org.json.JSONObject()
                .put("name", item.name)
                .put("mime", item.mime)
                .put("size", item.size)
                .put("url", "https://appassets.androidplatform.net/shared/" + item.id))
        }
        pending.clear()
        webView.post {
            webView.evaluateJavascript("window.onAndroidFiles && window.onAndroidFiles($json)", null)
        }
    }
}
