package org.relay.core.ready

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Where the pack is asked for: a position (the backend picks the nearest district) or a district id. */
sealed interface PackQuery {
    data class At(val lat: Double, val lon: Double) : PackQuery
    data class District(val id: String) : PackQuery
}

/** A transport or HTTP problem. [status] is the HTTP status, or 0 for a transport failure. */
class ReadyApiException(message: String, val status: Int = 0, cause: Throwable? = null) : IOException(message, cause)

/** The three phone calls of the Ready contract. Bodies are returned as the backend sent them so the store keeps unknown fields. */
interface ReadyApi {
    /** `GET /v1/ready/pack` (device key). */
    @Throws(ReadyApiException::class)
    fun pack(query: PackQuery, lang: String): String

    /** `POST /v1/ready/plan` (device key). */
    @Throws(ReadyApiException::class)
    fun plan(lang: String, lat: Double?, lon: Double?, district: String?, household: Household): String

    /** `GET {audio_url}` (device key) → the WAV, or null when the backend has none (404) or failed to make it (503): use on-device speech. */
    @Throws(ReadyApiException::class)
    fun audio(audioUrl: String): ByteArray?

    /** `GET /v1/public/shelters?district=` (no key) → the body, or null when nothing is published yet (404). */
    @Throws(ReadyApiException::class)
    fun publicShelters(district: String): String? = null

    /** `GET /v1/announcements/active?district=` (device key) → the body. Phones with signal poll it with the Ready refresh (contract section 5.1). */
    @Throws(ReadyApiException::class)
    fun activeAnnouncements(district: String): String? = null
}

/** [ReadyApi] over `HttpURLConnection` (the same stack as the gateway sync). Blocking; call from a worker thread. */
class HttpReadyApi(
    private val baseUrl: String,
    private val deviceKey: String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 15_000,
) : ReadyApi {
    private fun base() = baseUrl.trim().trimEnd('/')

    override fun pack(query: PackQuery, lang: String): String {
        val where = when (query) {
            is PackQuery.At -> "lat=${query.lat}&lon=${query.lon}"
            is PackQuery.District -> "district=" + URLEncoder.encode(query.id, "UTF-8")
        }
        return request("GET", "/v1/ready/pack?$where&lang=" + URLEncoder.encode(lang, "UTF-8"), null).text()
    }

    override fun plan(lang: String, lat: Double?, lon: Double?, district: String?, household: Household): String {
        val body = JSONObject().put("lang", lang).put("household", household.toJson())
        if (lat != null && lon != null) body.put("lat", lat).put("lon", lon)
        else if (district != null) body.put("district", district)
        return request("POST", "/v1/ready/plan", body.toString().toByteArray(Charsets.UTF_8)).text()
    }

    override fun audio(audioUrl: String): ByteArray? {
        val r = request("GET", if (audioUrl.startsWith("/")) audioUrl else "/$audioUrl", null, okStatuses = setOf(404, 503))
        return if (r.status == 404 || r.status == 503) null else r.body
    }

    override fun publicShelters(district: String): String? {
        val r = request("GET", "/v1/public/shelters?district=" + URLEncoder.encode(district, "UTF-8"), null, okStatuses = setOf(404), sendKey = false)
        return if (r.status == 404) null else r.text()
    }

    override fun activeAnnouncements(district: String): String =
        request("GET", "/v1/announcements/active?district=" + URLEncoder.encode(district, "UTF-8"), null).text()

    private class Response(val status: Int, val body: ByteArray) { fun text() = body.toString(Charsets.UTF_8) }

    private fun request(method: String, path: String, body: ByteArray?, okStatuses: Set<Int> = emptySet(), sendKey: Boolean = true): Response {
        val url = try { URL(base() + path) } catch (e: Exception) { throw ReadyApiException("bad backend URL '$baseUrl'", cause = e) }
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                setRequestProperty("Accept", if (path.contains("/audio")) "audio/wav" else "application/json")
                if (sendKey) setRequestProperty("X-Relay-Key", deviceKey)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setFixedLengthStreamingMode(body.size)
                }
            }
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { s -> ByteArrayOutputStream().also { s.copyTo(it) }.toByteArray() } ?: ByteArray(0)
            if (code !in 200..299 && code !in okStatuses) {
                throw ReadyApiException("HTTP $code${bytes.toString(Charsets.UTF_8).take(120).let { if (it.isBlank()) "" else ": $it" }}", status = code)
            }
            return Response(code, bytes)
        } catch (e: ReadyApiException) {
            throw e
        } catch (e: Exception) {
            throw ReadyApiException("${e.javaClass.simpleName}: ${e.message}", cause = e)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
