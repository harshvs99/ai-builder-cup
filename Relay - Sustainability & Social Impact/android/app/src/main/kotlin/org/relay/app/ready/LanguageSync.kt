package org.relay.app.ready

import org.relay.app.MeshRepository
import org.relay.core.lang.LanguageCatalog
import java.net.HttpURLConnection
import java.net.URL

/**
 * `GET {base}/v1/languages` (no key): which languages the backend has switched on. A good response is cached in
 * [org.relay.app.Settings.serverLanguagesJson]; a failure keeps the cached copy (or the build default). Blocking; call from a worker thread.
 */
object LanguageSync {
    fun refresh(baseUrl: String, timeoutMs: Int = 5_000): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(baseUrl.trim().trimEnd('/') + "/v1/languages")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = timeoutMs; readTimeout = timeoutMs; useCaches = false
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode !in 200..299) return false
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (LanguageCatalog.parseServer(body) == null) return false // not the expected shape: keep what we have
            MeshRepository.settings.update { it.copy(serverLanguagesJson = body) }
            true
        } catch (e: Exception) {
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
