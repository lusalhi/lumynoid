package dev.lusalhi.lumynoid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkPolicyTest {
    @Test fun allowsHttpHttpsMailto() {
        assertEquals("https://e.com/p?q=1", openableUri("https://e.com/p?q=1"))
        assertEquals("http://e.com", openableUri("http://e.com"))
        assertEquals("mailto:a@b.co", openableUri("mailto:a@b.co"))
    }

    @Test fun schemeIsCaseInsensitiveAndUrlIsTrimmed() {
        assertEquals("HTTPS://E.com", openableUri("  HTTPS://E.com \n"))
        assertEquals("MailTo:a@b.co", openableUri("MailTo:a@b.co"))
    }

    @Test fun rejectsUnsafeAndRelative() {
        listOf(
            "javascript:alert(1)", "intent://x#Intent;end", "file:///etc/passwd", "content://x/y",
            "foo", "/foo", "//e.com/a", "", "   ", "http:", ":https", "data:text/html,hi", " javascript:x",
        ).forEach { assertNull(it, openableUri(it)) }
    }

    @Test fun imageLabelUsesAltOrUrl() {
        val t = "Image: %1\$s"
        assertEquals("Image: chart", imageLinkLabel("chart", "https://e.com/a.png", t))
        assertEquals("Image: chart", imageLinkLabel("  chart ", "https://e.com/a.png", t))
        assertEquals("Image: https://e.com/a.png", imageLinkLabel(null, "https://e.com/a.png", t))
        assertEquals("Image: https://e.com/a.png", imageLinkLabel("  ", "https://e.com/a.png", t))
    }
}
