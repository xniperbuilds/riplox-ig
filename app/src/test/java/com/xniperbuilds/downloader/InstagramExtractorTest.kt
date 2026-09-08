package com.xniperbuilds.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two pieces of Route 0 that are pure logic — and the two that fail SILENTLY when wrong.
 *
 * Neither of these throws or logs anything useful on a mistake: a bad shortcode or a bad media
 * id simply produces a 404, the extractor returns null, and the app falls back to the engine
 * exactly as if the post were unsupported. Photos would stop working, downloads would go back
 * to being slow, and nothing anywhere would say why. Using the app could never reveal it, so a
 * test has to.
 */
class InstagramExtractorTest {

    // ── shortcode → media id ───────────────────────────────────────────────────────────────
    // The alphabet is "A..Za..z0..9-_", so these are the base-64 identities: 'A' is digit 0,
    // 'B' is 1, "BA" is 1*64 + 0, and the last two characters are 62 and 63.

    @Test
    fun `first alphabet character is zero`() {
        assertEquals("0", InstagramExtractor.mediaId("A"))
    }

    @Test
    fun `second alphabet character is one`() {
        assertEquals("1", InstagramExtractor.mediaId("B"))
    }

    @Test
    fun `two characters are base 64`() {
        assertEquals("64", InstagramExtractor.mediaId("BA"))
        assertEquals("65", InstagramExtractor.mediaId("BB"))
    }

    @Test
    fun `dash and underscore are the last two digits`() {
        assertEquals("62", InstagramExtractor.mediaId("-"))
        assertEquals("63", InstagramExtractor.mediaId("_"))
    }

    @Test
    fun `lowercase follows uppercase`() {
        // 'a' is the 27th character of the alphabet → digit 26
        assertEquals("26", InstagramExtractor.mediaId("a"))
        // '0' comes after the 52 letters → digit 52
        assertEquals("52", InstagramExtractor.mediaId("0"))
    }

    @Test
    fun `a real length shortcode exceeds Long range handling`() {
        // 11 characters of the top digit — comfortably past Long.MAX_VALUE (about 9.2e18),
        // which is why this uses BigInteger. If someone "simplifies" it to Long, this fails.
        val id = InstagramExtractor.mediaId("___________")
        assertEquals("73786976294838206463", id)
    }

    @Test
    fun `a character outside the alphabet is rejected`() {
        assertNull(InstagramExtractor.mediaId("abc!def"))
        assertNull(InstagramExtractor.mediaId(""))
    }

    // ── url → shortcode ────────────────────────────────────────────────────────────────────

    @Test
    fun `post reel and tv urls all yield the shortcode`() {
        assertEquals("CGh0iLABc8P", InstagramExtractor.shortcodeOf("https://www.instagram.com/p/CGh0iLABc8P/"))
        assertEquals("CGh0iLABc8P", InstagramExtractor.shortcodeOf("https://www.instagram.com/reel/CGh0iLABc8P/"))
        assertEquals("CGh0iLABc8P", InstagramExtractor.shortcodeOf("https://www.instagram.com/reels/CGh0iLABc8P/"))
        assertEquals("CGh0iLABc8P", InstagramExtractor.shortcodeOf("https://www.instagram.com/tv/CGh0iLABc8P/"))
    }

    @Test
    fun `a share link with its tracking query still parses`() {
        assertEquals(
            "CGh0iLABc8P",
            InstagramExtractor.shortcodeOf("https://www.instagram.com/reel/CGh0iLABc8P/?igsh=MXY5ZzB4")
        )
    }

    @Test
    fun `a username in the path does not confuse it`() {
        assertEquals(
            "Cx_9-yZ",
            InstagramExtractor.shortcodeOf("https://instagram.com/someuser/p/Cx_9-yZ/")
        )
    }

    @Test
    fun `stories and profiles are handed to the engine, not guessed at`() {
        // Null is not a failure here — it is how this route says "not mine". Stories live
        // behind a different endpoint, and yt-dlp already handles them.
        assertNull(InstagramExtractor.shortcodeOf("https://www.instagram.com/stories/someuser/3211122334455/"))
        assertNull(InstagramExtractor.shortcodeOf("https://www.instagram.com/someuser/"))
        assertNull(InstagramExtractor.shortcodeOf("https://www.instagram.com/"))
    }
}
