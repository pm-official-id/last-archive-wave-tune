/*
 * ArchiveTune (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.tidal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Media3's DashManifestParser reads SegmentTemplate/SegmentList timescale and duration via
 * Long.parseLong; decimals throw NumberFormatException and seeking resets to 0. These tests pin
 * the ported native-LastWave sanitization that integer-izes such attributes.
 */
class DashManifestSanitizerTest {
    @Test
    fun `float timescale on SegmentTemplate is rounded to integer`() {
        val xml = """<SegmentTemplate timescale="44100.000" media="a/${'$'}Number$.m4s"/>"""
        val out = DashManifestSanitizer.sanitize(xml)
        assertTrue(out.contains("""timescale="44100""""))
        assertFalse(out.contains("44100.000"))
    }

    @Test
    fun `float duration with timescale is rounded to integer`() {
        val xml = """<SegmentTemplate timescale="1000" duration="10080.000"/>"""
        val out = DashManifestSanitizer.sanitize(xml)
        assertTrue(out.contains("""duration="10080""""))
        assertTrue(out.contains("""timescale="1000""""))
    }

    @Test
    fun `float duration without timescale is converted to millis`() {
        val xml = """<SegmentList duration="10.008">"""
        val out = DashManifestSanitizer.sanitize(xml)
        assertTrue(out.contains("""timescale="1000""""))
        assertTrue(out.contains("""duration="10008""""))
    }

    @Test
    fun `integer attributes are left untouched`() {
        val xml = """<SegmentTemplate timescale="44100" duration="10080" media="a/${'$'}Number$.m4s"/>"""
        assertEquals(xml, DashManifestSanitizer.sanitize(xml))
    }

    @Test
    fun `xml without segment timing attributes is returned as is`() {
        val xml = """<MPD type="static"><Period></Period></MPD>"""
        assertEquals(xml, DashManifestSanitizer.sanitize(xml))
    }

    @Test
    fun `full tidal style manifest is sanitized without breaking other attributes`() {
        val dollar = "$"
        val xml =
            """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT240.192S">""" +
                """<Period><AdaptationSet mimeType="audio/mp4" codecs="flac">""" +
                """<Representation id="0" bandwidth="969849"><SegmentTemplate timescale="44100.0" """ +
                """initialization="${dollar}RepresentationID${dollar}/init.m4s" media="${dollar}RepresentationID${dollar}/${dollar}Number${dollar}.m4s" """ +
                """startNumber="1"><SegmentTimeline><S t="0" d="441000" r=""/></SegmentTimeline>""" +
                """</SegmentTemplate></Representation></AdaptationSet></Period></MPD>"""
        val out = DashManifestSanitizer.sanitize(xml)
        assertTrue(out.contains("""timescale="44100""""))
        assertTrue(out.contains("""mediaPresentationDuration="PT240.192S""""))
        assertTrue(out.contains("""initialization="${dollar}RepresentationID${dollar}/init.m4s""""))
        assertTrue(out.contains("""<S t="0" d="441000" r=""/>"""))
    }
}
