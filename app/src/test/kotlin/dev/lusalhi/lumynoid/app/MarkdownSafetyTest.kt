package dev.lusalhi.lumynoid.app

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The renderer library ships Java 21 bytecode, so these rows need a JDK 21 test JVM; the project toolchain and CI
 * run 17, where they are skipped (see the story's Implementation Notes).
 */
class MarkdownSafetyTest {
    @Before fun needsJdk21() = assumeTrue((System.getProperty("java.specification.version")?.toIntOrNull() ?: 0) >= 21)

    private val template = "Image: %1\$s"

    private fun render(markdown: String) = run {
        val refs = ReferenceLinkHandlerImpl()
        val style = SpanStyle()
        markdown.buildMarkdownAnnotatedString(
            style = TextStyle(),
            annotatorSettings = DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(style = style),
                codeSpanStyle = style,
                annotator = safeMarkdownAnnotator(refs, style, template),
                referenceLinkHandler = refs,
                highlightSpanStyle = style,
            ),
        )
    }

    @Test fun inlineHtmlStaysLiteralText() {
        val out = render("a <b>y</b> c")
        assertEquals("a <b>y</b> c", out.text)
        assertTrue(out.getLinkAnnotations(0, out.length).isEmpty())
    }

    @Test fun imgTagStaysLiteralText() {
        val out = render("see <img src=\"https://e.com/a.png\"> ok")
        assertEquals("see <img src=\"https://e.com/a.png\"> ok", out.text)
        assertTrue(out.getLinkAnnotations(0, out.length).isEmpty())
    }

    @Test fun imageBecomesLabelledLink() {
        val out = render("![chart](https://e.com/a.png)")
        assertEquals("Image: chart", out.text)
        val links = out.getLinkAnnotations(0, out.length)
        assertEquals(1, links.size)
        assertEquals("https://e.com/a.png", (links[0].item as LinkAnnotation.Url).url)
    }

    @Test fun imageWithoutAltShowsUrl() {
        val out = render("![](https://e.com/a.png)")
        assertEquals("Image: https://e.com/a.png", out.text)
    }
}
