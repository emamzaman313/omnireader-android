package ir.hamghadam.omnireader

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MainActivity : Activity() {
    private data class SharedFile(val uri: Uri, val mime: String)
    private data class PendingFile(val id: String, val name: String, val mime: String, val size: Long)
    private val sharedFiles = ConcurrentHashMap<String, SharedFile>()
    private val pending = mutableListOf<PendingFile>()
    private lateinit var webView: WebView
    private var pageReady = false

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
                    } catch (error: Exception) {
                        Log.e("OmniReader", "Cannot open shared file", error)
                        null
                    }
                }
            }).build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
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
                    pageReady = true
                    deliverFiles()
                }
            }
            setContentView(webView)
            webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        } catch (error: Exception) {
            Log.e("OmniReader", "Startup failed", error)
            showStartupError(error)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        try {
            handleIncoming(intent)
            deliverFiles()
        } catch (error: Exception) {
            Log.e("OmniReader", "Incoming file failed", error)
        }
    }

    private fun handleIncoming(source: Intent?) {
        if (source == null) return
        val uris = linkedSetOf<Uri>()
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
        uris.forEach { uri ->
            try {
                val id = UUID.randomUUID().toString()
                val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
                    ?: "application/octet-stream"
                val (name, size) = fileMetadata(uri)
                sharedFiles[id] = SharedFile(uri, mime)
                pending.add(PendingFile(id, name, mime, size))
            } catch (error: Exception) {
                Log.e("OmniReader", "Skipping unreadable URI: $uri", error)
            }
        }
    }

    private fun fileMetadata(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "file"
        var size = 0L
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = cursor.getString(ni) ?: name
                    if (si >= 0 && !cursor.isNull(si)) size = cursor.getLong(si).coerceAtLeast(0L)
                }
            }
        }.onFailure { Log.w("OmniReader", "Metadata unavailable", it) }
        return name to size
    }

    private fun deliverFiles() {
        if (!pageReady || pending.isEmpty() || !::webView.isInitialized) return
        val batch = pending.toList()
        val json = JSONArray()
        batch.forEach { item ->
            json.put(JSONObject().put("name", item.name).put("mime", item.mime)
                .put("size", item.size)
                .put("url", "https://appassets.androidplatform.net/shared/" + item.id))
        }
        webView.evaluateJavascript("window.onAndroidFiles ? (window.onAndroidFiles($json), true) : false") { result ->
            if (result == "true") pending.removeAll(batch.toSet())
        }
    }

    private fun showStartupError(error: Exception) {
        val message = TextView(this).apply {
            text = "همه‌خوان نتوانست صفحهٔ اصلی را باز کند.\n\n" +
                (error.localizedMessage ?: error.javaClass.simpleName) +
                "\n\nلطفاً Android System WebView یا Chrome را به‌روز کنید و دوباره تلاش کنید."
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(8, 17, 30))
        }
        setContentView(message)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}