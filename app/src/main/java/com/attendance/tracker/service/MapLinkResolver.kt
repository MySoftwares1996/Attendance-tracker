package com.attendance.tracker.service

import com.attendance.tracker.core.LatLng
import com.attendance.tracker.core.MapLinkParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

sealed class LinkResult {
    data class Found(val point: LatLng) : LinkResult()
    data class Failed(val message: String) : LinkResult()
}

/** Turns any Google Maps link (including maps.app.goo.gl short links) into coordinates. */
object MapLinkResolver {
    private const val NO_COORDS =
        "This link doesn't contain the exact spot. In Google Maps, long-press the exact place to drop a pin, then tap Share and copy the link."

    suspend fun resolve(text: String): LinkResult = withContext(Dispatchers.IO) {
        MapLinkParser.parse(text)?.let { return@withContext LinkResult.Found(it) }
        var url = MapLinkParser.extractUrl(text)
            ?: return@withContext LinkResult.Failed("Paste a Google Maps link or coordinates like 12.9716, 77.5946")
        try {
            repeat(8) {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 12_000
                    readTimeout = 12_000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile")
                    setRequestProperty("Accept-Language", "en")
                }
                try {
                    val code = conn.responseCode
                    val location = conn.getHeaderField("Location")
                    if (code in 300..399 && location != null) {
                        url = URL(URL(url), location).toString()
                        MapLinkParser.parse(url)?.let { p -> return@withContext LinkResult.Found(p) }
                    } else {
                        val body = (if (code < 400) conn.inputStream else conn.errorStream)
                            ?.bufferedReader()?.use { r -> r.readText().take(400_000) } ?: ""
                        return@withContext MapLinkParser.parse(body, strict = true)?.let { p -> LinkResult.Found(p) }
                            ?: LinkResult.Failed(NO_COORDS)
                    }
                } finally {
                    conn.disconnect()
                }
            }
            LinkResult.Failed(NO_COORDS)
        } catch (e: Exception) {
            LinkResult.Failed("Couldn't open the link. Check your internet connection and try again.")
        }
    }
}
