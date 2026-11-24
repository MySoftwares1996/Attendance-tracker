package com.attendance.tracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLinkParserTest {
    private fun check(text: String, lat: Double, lng: Double) {
        val p = MapLinkParser.parse(text) ?: error("no coords in: $text")
        assertEquals(lat, p.lat, 1e-6); assertEquals(lng, p.lng, 1e-6)
    }

    @Test fun placeLinkPrefersPinOverViewport() = check(
        "https://www.google.com/maps/place/Office/@12.9715000,77.5940000,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d12.9716123!4d77.5946456!16s",
        12.9716123, 77.5946456)

    @Test fun viewportLink() = check("https://www.google.com/maps/@12.9716,77.5946,18z", 12.9716, 77.5946)
    @Test fun queryLink() = check("https://maps.google.com/?q=12.9716,77.5946", 12.9716, 77.5946)
    @Test fun searchApiLink() = check("https://www.google.com/maps/search/?api=1&query=28.6139%2C77.2090", 28.6139, 77.2090)
    @Test fun encodedConsentRedirect() = check(
        "https://consent.google.com/m?continue=https://www.google.com/maps/place/X/%4019.0760,72.8777,17z/data%3D!3d19.0761!4d72.8778&gl=IN",
        19.0761, 72.8778)
    @Test fun geoUri() = check("geo:13.0827,80.2707?q=13.0827,80.2707(Office)", 13.0827, 80.2707)
    @Test fun plainCoordinates() = check(" 17.3850, 78.4867 ", 17.3850, 78.4867)
    @Test fun negativeCoordinates() = check("https://www.google.com/maps/@-33.8688,151.2093,15z", -33.8688, 151.2093)
    @Test fun sharedTextWithLabel() = check("ABC Tech Park\nhttps://www.google.com/maps?q=12.9352,77.6245", 12.9352, 77.6245)

    @Test fun shortLinksDetected() {
        assertTrue(MapLinkParser.isShortLink("https://maps.app.goo.gl/AbCdEf123"))
        assertTrue(MapLinkParser.isShortLink("https://goo.gl/maps/xyz"))
        assertFalse(MapLinkParser.isShortLink("https://www.google.com/maps/@12.9,77.5,15z"))
        assertEquals("https://maps.app.goo.gl/AbCdEf123", MapLinkParser.extractUrl("My office https://maps.app.goo.gl/AbCdEf123"))
    }

    @Test fun noCoordinates() {
        assertNull(MapLinkParser.parse("https://maps.app.goo.gl/AbCdEf123"))
        assertNull(MapLinkParser.parse("hello world"))
    }
}
