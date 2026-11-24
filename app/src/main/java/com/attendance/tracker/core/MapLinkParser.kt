package com.attendance.tracker.core

import java.net.URLDecoder

/**
 * Reads coordinates out of Google Maps links / shared text, without any API key.
 * Supported: place links (!3d…!4d…), q= / query= / ll= / center= / destination=,
 * viewport links (@lat,lng), geo: URIs and plain "lat, lng" text.
 */
object MapLinkParser {
    private val urlRe = Regex("""https?://[^\s"'<>]+""")
    private val shortHosts = setOf("maps.app.goo.gl", "goo.gl", "g.co", "maps.google.com", "goo.gle")

    private const val NUM = """(-?\d{1,3}\.\d+)"""
    private val placePin = Regex("""!3d$NUM!4d$NUM""")
    private val queryParam = Regex("""[?&](?:q|query|ll|sll|center|destination|daddr|saddr|location)=(?:loc:)?$NUM\s*,\s*\+?$NUM""")
    private val atViewport = Regex("""@$NUM,$NUM""")
    private val geoUri = Regex("""geo:$NUM,$NUM""")
    private val plain = Regex("""^\s*$NUM\s*[, ]\s*$NUM\s*$""")
    private val loose = Regex("""(-?\d{1,2}\.\d{4,})\s*,\s*(-?\d{1,3}\.\d{4,})""")

    fun extractUrl(text: String): String? = urlRe.find(text)?.value?.trimEnd(')', '.', ',', ']')

    fun isShortLink(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        if (host == "maps.google.com") return !url.contains("q=") && !url.contains("@")
        return host in shortHosts
    }

    /**
     * @param strict only use well-structured patterns (for scanning web pages).
     */
    fun parse(raw: String, strict: Boolean = false): LatLng? {
        val text = decode(decode(raw))
        val patterns = buildList {
            add(placePin); add(queryParam); add(geoUri); add(atViewport)
            if (!strict) { add(plain); add(loose) }
        }
        for (p in patterns) {
            for (m in p.findAll(text)) {
                val lat = m.groupValues[1].toDoubleOrNull() ?: continue
                val lng = m.groupValues[2].toDoubleOrNull() ?: continue
                if (lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)) return LatLng(lat, lng)
            }
        }
        return null
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
}
