package xyz.desent.data.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingPixelDetectorTest {

    @Test
    fun `detects classic 1x1 img with width and height attributes`() {
        val html = """<p>Hello</p><img src="https://cdn.example.com/p.gif" width="1" height="1" alt="">"""
        val scan = TrackingPixelDetector.scan(html)
        assertTrue(scan.hasTrackingPixels)
        assertEquals(1, scan.pixelCount)
        assertTrue(scan.matchedSignals.contains("tiny-dimension"))
    }

    @Test
    fun `detects zero-size image via inline style`() {
        val html = """<img src="https://example.com/img.png" style="width:0px;height:0px;border:0">"""
        val scan = TrackingPixelDetector.scan(html)
        assertTrue(scan.hasTrackingPixels)
        assertTrue(scan.matchedSignals.contains("tiny-dimension"))
    }

    @Test
    fun `detects hidden image via display none`() {
        val html = """<img src="https://example.com/img.png" style="display:none">"""
        val scan = TrackingPixelDetector.scan(html)
        assertTrue(scan.hasTrackingPixels)
        assertTrue(scan.matchedSignals.contains("tiny-dimension"))
    }

    @Test
    fun `detects known tracker domains`() {
        val mailchimp = """<img src="https://ct.mc0.to/abc123.gif" width="300" height="300">"""
        val sendgrid = """<img src="https://u123.ct.sendgrid.net/wf/open?upn=abc">"""
        val hubspot = """<img src="https://track.hubspot.com/e.gif">"""
        for (html in listOf(mailchimp, sendgrid, hubspot)) {
            val scan = TrackingPixelDetector.scan(html)
            assertTrue("expected tracker in $html", scan.hasTrackingPixels)
            assertTrue(scan.matchedSignals.contains("known-tracker"))
        }
    }

    @Test
    fun `detects suspicious url tokens in path and query`() {
        val html = """<img src="https://mailer.example.com/img/pixel.png?mid=123">"""
        val scan = TrackingPixelDetector.scan(html)
        assertTrue(scan.hasTrackingPixels)
        assertTrue(scan.matchedSignals.contains("suspicious-url"))
    }

    @Test
    fun `counts multiple pixels across mixed signals`() {
        val html = """
            <img src="https://cdn.example.com/logo.png" width="600">
            <img src="https://mailer.example.com/1x1.gif" width="1" height="1">
            <img src="https://cdn.example.com/sig.gif" width="1" height="1">
            <img src="https://ct.mc0.to/open.gif">
            <img src="https://example.com/assets/beacon.png" width="40">
        """.trimIndent()
        val scan = TrackingPixelDetector.scan(html)
        assertEquals(4, scan.pixelCount)
        assertTrue(scan.matchedSignals.containsAll(listOf("tiny-dimension", "known-tracker", "suspicious-url")))
    }

    @Test
    fun `ignores normal sized remote images`() {
        val html = """<img src="https://example.com/logo.png" width="600" height="200">"""
        assertFalse(TrackingPixelDetector.hasTrackingPixels(html))
    }

    @Test
    fun `ignores tracker-like naming in the host only`() {
        val html = """<img src="https://pixelcraft.com/logo.png" width="600">"""
        assertFalse(TrackingPixelDetector.hasTrackingPixels(html))
    }

    @Test
    fun `ignores inline data uris even at tiny sizes`() {
        val html = """<img src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" width="1" height="1">"""
        assertFalse(TrackingPixelDetector.hasTrackingPixels(html))
    }

    @Test
    fun `ignores plain text and quoted replies`() {
        val html = "On Tue someone wrote:\n> see https://example.com/pixel.gif\n> and 1x1 images"
        assertFalse(TrackingPixelDetector.hasTrackingPixels(html))
    }

    @Test
    fun `empty body yields empty scan`() {
        val scan = TrackingPixelDetector.scan("")
        assertFalse(scan.hasTrackingPixels)
        assertEquals(0, scan.pixelCount)
        assertTrue(scan.matchedSignals.isEmpty())
    }
}
