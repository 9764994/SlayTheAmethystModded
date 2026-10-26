package io.stamethyst.ui

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inline pass is a single scanner that appends plain runs in bulk, so these cases pin the
 * output (text plus spans) that the per-character implementation produced.
 */
class SimpleMarkdownInlineParserTest {

    private fun spansOf(annotated: androidx.compose.ui.text.AnnotatedString) =
        annotated.spanStyles.map { it.start to it.end }

    @Test
    fun boldAndItalicKeepTheirTextAndRange() {
        val bold = buildSimpleMarkdownAnnotatedStringForTest("a**b**c")
        assertEquals("abc", bold.text)
        assertEquals(listOf(1 to 2), spansOf(bold))
        assertEquals(FontWeight.Bold, bold.spanStyles.single().item.fontWeight)

        val italic = buildSimpleMarkdownAnnotatedStringForTest("a_b_c")
        assertEquals("abc", italic.text)
        assertEquals(listOf(1 to 2), spansOf(italic))
        assertEquals(FontStyle.Italic, italic.spanStyles.single().item.fontStyle)

        val twoItalics = buildSimpleMarkdownAnnotatedStringForTest("*a* and *b*")
        assertEquals("a and b", twoItalics.text)
        assertEquals(listOf(0 to 1, 6 to 7), spansOf(twoItalics))
    }

    @Test
    fun strikeAndCodeKeepTheirTextAndRange() {
        val struck = buildSimpleMarkdownAnnotatedStringForTest("~~x~~")
        assertEquals("x", struck.text)
        assertEquals(TextDecoration.LineThrough, struck.spanStyles.single().item.textDecoration)

        val code = buildSimpleMarkdownAnnotatedStringForTest("run `a + b` now")
        assertEquals("run a + b now", code.text)
        assertEquals(FontFamily.Monospace, code.spanStyles.single().item.fontFamily)
        assertEquals(listOf(4 to 9), spansOf(code))
    }

    @Test
    fun unmatchedMarkersStayLiteralText() {
        assertEquals("**unclosed", buildSimpleMarkdownAnnotatedStringForTest("**unclosed").text)
        assertEquals("~~unclosed", buildSimpleMarkdownAnnotatedStringForTest("~~unclosed").text)
        assertEquals("*unclosed", buildSimpleMarkdownAnnotatedStringForTest("*unclosed").text)
        assertEquals("`unclosed", buildSimpleMarkdownAnnotatedStringForTest("`unclosed").text)
        assertEquals("[unclosed", buildSimpleMarkdownAnnotatedStringForTest("[unclosed").text)
        // "__x__" keeps the outer underscores, exactly like the per-character implementation.
        val doubled = buildSimpleMarkdownAnnotatedStringForTest("__x__")
        assertEquals("_x_", doubled.text)
        assertEquals(listOf(1 to 2), spansOf(doubled))
        assertTrue(buildSimpleMarkdownAnnotatedStringForTest("**unclosed").spanStyles.isEmpty())
    }

    @Test
    fun escapesDropTheBackslashAndKeepTheCharacter() {
        val escaped = buildSimpleMarkdownAnnotatedStringForTest("a\\*b\\*c")
        assertEquals("a*b*c", escaped.text)
        assertTrue(escaped.spanStyles.isEmpty())
        assertEquals("trailing\\", buildSimpleMarkdownAnnotatedStringForTest("trailing\\").text)
    }

    @Test
    fun linksAndBareUrlsSurviveTheRewrite() {
        val link = buildSimpleMarkdownAnnotatedStringForTest("查看 [日志](https://example.com/logs.zip)")
        assertEquals("查看 日志", link.text)
        val linkAnnotation = link.getLinkAnnotations(0, link.length).single()
        assertEquals("https://example.com/logs.zip", (linkAnnotation.item as LinkAnnotation.Url).url)

        val bare = buildSimpleMarkdownAnnotatedStringForTest("链接：https://example.com/path?a=1.")
        assertEquals("链接：https://example.com/path?a=1.", bare.text)
        assertEquals(
            "https://example.com/path?a=1",
            (bare.getLinkAnnotations(0, bare.length).single().item as LinkAnnotation.Url).url,
        )
        // A URL inside a word is still detected and keeps the surrounding runs intact.
        val inline = buildSimpleMarkdownAnnotatedStringForTest("see(https://e.com/a)ok")
        assertEquals("see(https://e.com/a)ok", inline.text)
        assertEquals(1, inline.getLinkAnnotations(0, inline.length).size)
    }

    @Test
    fun plainRunsAreAppendedWithoutChangingTheText() {
        val text = "普通文本 with spaces, 数字 123 和标点！".repeat(40)
        val annotated = buildSimpleMarkdownAnnotatedStringForTest(text)
        assertEquals(text, annotated.text)
        assertTrue(annotated.spanStyles.isEmpty())
    }

    @Test
    fun inlineParseCacheReturnsTheSameInstanceForTheSameInput() {
        MarkdownInlineParser.clearCache()
        val first = MarkdownInlineParser.parse(
            text = "缓存 `code` 与 **粗体**",
            linkStyles = androidx.compose.ui.text.TextLinkStyles(),
            boldStyle = androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold),
            italicStyle = androidx.compose.ui.text.SpanStyle(fontStyle = FontStyle.Italic),
            codeStyle = androidx.compose.ui.text.SpanStyle(fontFamily = FontFamily.Monospace),
            strikeStyle = androidx.compose.ui.text.SpanStyle(textDecoration = TextDecoration.LineThrough),
        )
        val second = MarkdownInlineParser.parse(
            text = "缓存 `code` 与 **粗体**",
            linkStyles = androidx.compose.ui.text.TextLinkStyles(),
            boldStyle = androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold),
            italicStyle = androidx.compose.ui.text.SpanStyle(fontStyle = FontStyle.Italic),
            codeStyle = androidx.compose.ui.text.SpanStyle(fontFamily = FontFamily.Monospace),
            strikeStyle = androidx.compose.ui.text.SpanStyle(textDecoration = TextDecoration.LineThrough),
        )
        assertSame(first, second)
    }

    @Test
    fun documentParseCacheReturnsTheSameBlocksAndDetectsPlainText() {
        MarkdownDocumentParser.clearCache()
        val markdown = "## 标题\n\n段落 **内容**"
        val first = MarkdownDocumentParser.parse(markdown)
        val second = MarkdownDocumentParser.parse(markdown)
        assertSame(first, second)
        assertEquals(false, first.plainText)
        assertEquals(2, first.blocks.size)

        val plain = MarkdownDocumentParser.parse("就是一段没有标记的普通文字")
        assertTrue(plain.plainText)
        assertTrue(plain.blocks.isEmpty())
    }
}
