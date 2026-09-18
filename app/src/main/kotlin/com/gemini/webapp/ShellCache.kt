package com.gemini.webapp

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Persistent "site structure" cache.
 *
 * Static assets that make up the Gemini web app shell (JS bundles, CSS, fonts, images,
 * WASM) are stored on disk forever and served straight from disk on every launch
 * (stale-while-revalidate).  Dynamic content – the XHR/fetch API calls that carry
 * actual conversation data – is never intercepted and always goes to the network.
 */
object ShellCache {
    private val STATIC_EXT = setOf(
        "js", "mjs", "css", "woff", "woff2", "ttf", "otf", "png", "jpg", "jpeg",
        "gif", "webp", "svg", "ico", "wasm", "avif"
    )
    private val STATIC_HOSTS = setOf(
        "www.gstatic.com", "ssl.gstatic.com", "fonts.gstatic.com", "fonts.googleapis.com",
        "www.google.com", "lh3.googleusercontent.com"
    )
    private val MIME = mapOf(
        "js" to "application/javascript", "mjs" to "application/javascript",
        "css" to "text/css", "woff" to "font/woff", "woff2" to "font/woff2",
        "ttf" to "font/ttf", "otf" to "font/otf", "png" to "image/png",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
        "webp" to "image/webp", "svg" to "image/svg+xml", "ico" to "image/x-icon",
        "wasm" to "application/wasm", "avif" to "image/avif"
    )
    private const val MAX_ASSET_BYTES = 8L * 1024 * 1024
    private const val REVALIDATE_AFTER_MS = 6L * 60 * 60 * 1000 // 6 h

    private lateinit var dir: File
    private val revalidating = ConcurrentHashMap.newKeySet<String>()
    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "shell-cache").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    fun init(ctx: Context) {
        dir = File(ctx.cacheDir, "shell").apply { mkdirs() }
    }

    /** Returns true if this URL is a cacheable static asset (not an API/content call). */
    fun isStatic(url: URL, method: String, accept: String?): Boolean {
        if (method != "GET") return false
        if (accept != null && accept.startsWith("application/json")) return false
        val path = url.path.lowercase()
        // Never cache HTML documents or Google's batchexecute / RPC content endpoints.
        if (path.contains("batchexecute") || path.contains("/data/") || path.endsWith("/app") || path.contains("/app/")) return false
        val ext = path.substringAfterLast('.', "")
        if (ext in STATIC_EXT) return true
        // gstatic serves versioned bundles without extensions, e.g. /_/js/k=... and /_/ss/k=...
        if (url.host in STATIC_HOSTS && (path.contains("/_/js/") || path.contains("/_/ss/"))) return true
        // gemini.google.com serves its own versioned UI bundles here; content RPCs live under /_/BardChatUi/data/
        return url.host == "gemini.google.com" && path.startsWith("/_/bardchatui/") && !path.contains("/data/")
    }

    fun serve(url: URL, headers: Map<String, String>): WebResourceResponse? {
        val key = key(url.toString())
        val body = File(dir, key)
        val meta = File(dir, "$key.m")
        if (body.exists() && body.length() > 0) {
            val mime = meta.takeIf { it.exists() }?.readText()?.ifBlank { null } ?: guessMime(url)
            if (System.currentTimeMillis() - body.lastModified() > REVALIDATE_AFTER_MS) revalidate(url, headers, key)
            return WebResourceResponse(mime, "utf-8", 200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*", "X-Shell-Cache" to "HIT"),
                body.inputStream().buffered(128 * 1024))
        }
        // Cache miss: fetch synchronously (WebView calls us off the UI thread), store and stream.
        return fetch(url, headers, key)
    }

    private fun fetch(url: URL, headers: Map<String, String>, key: String): WebResourceResponse? {
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 15000; instanceFollowRedirects = true
                headers.forEach { (k, v) -> if (!k.equals("Accept-Encoding", true)) setRequestProperty(k, v) }
            }
            if (conn.responseCode != 200) { conn.disconnect(); return null }
            val bytes = conn.inputStream.use { it.readBytes() }
            val mime = conn.contentType?.substringBefore(';')?.trim()?.ifBlank { null } ?: guessMime(url)
            conn.disconnect()
            if (bytes.size <= MAX_ASSET_BYTES) io.execute { write(key, bytes, mime) }
            WebResourceResponse(mime, "utf-8", 200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*", "X-Shell-Cache" to "MISS"),
                ByteArrayInputStream(bytes))
        } catch (_: Exception) { null } // fall back to WebView's own network stack
    }

    private fun revalidate(url: URL, headers: Map<String, String>, key: String) {
        if (!revalidating.add(key)) return
        io.execute {
            try { fetch(url, headers, key) } finally { revalidating.remove(key) }
        }
    }

    private fun write(key: String, bytes: ByteArray, mime: String) {
        try {
            val tmp = File(dir, "$key.tmp")
            tmp.writeBytes(bytes)
            File(dir, "$key.m").writeText(mime)
            tmp.renameTo(File(dir, key))
        } catch (_: Exception) {}
    }

    /** True once at least one shell asset is on disk – the structure can render offline. */
    fun hasCachedShell(): Boolean = dir.listFiles()?.any { !it.name.endsWith(".m") && !it.name.endsWith(".tmp") } == true

    fun clear() { io.execute { dir.listFiles()?.forEach { it.delete() } } }

    private fun guessMime(url: URL): String {
        val path = url.path.lowercase()
        MIME[path.substringAfterLast('.', "")]?.let { return it }
        return when {
            path.contains("/_/js/") -> "application/javascript"
            path.contains("/_/ss/") -> "text/css"
            else -> "application/octet-stream"
        }
    }

    private fun key(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
