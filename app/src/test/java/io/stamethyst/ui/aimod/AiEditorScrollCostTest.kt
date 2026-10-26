package io.stamethyst.ui.aimod

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.stamethyst.ui.MarkdownDocumentParser
import io.stamethyst.ui.MarkdownInlineParser
import io.stamethyst.ui.buildSimpleMarkdownAnnotatedStringForTest
import io.stamethyst.ui.parseSimpleMarkdownOutlineForTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.AbstractList

/**
 * Cost evidence for the AI editor's transcript. The numbers come from the real code that the UI
 * runs, not from a re-implementation:
 *
 *  - one row entering the viewport parses its markdown chunk on the composition (UI) thread,
 *  - one 500 ms conversation poll compares the re-read message with the in-memory one and then
 *    rebuilds every transcript row of that message.
 *
 * Run with `./gradlew :app:aiEditorScrollCostTest`; it is skipped in the normal unit test task
 * because timing assertions do not belong in a shared build.
 */
class AiEditorScrollCostTest {
    @Before
    fun onlyWhenRequested() {
        assumeTrue(System.getProperty("sts.runAiEditorScrollCost") == "true")
    }

    @Test
    fun markdownInlineParseScalesLinearly() {
        val sizes = listOf(512, 1024, 2048, 4096)
        val times = sizes.associateWith { size ->
            bestOf { buildSimpleMarkdownAnnotatedStringForTest(markdownOf(size)) }
        }
        println("== markdown inline parse (real buildSimpleMarkdownAnnotatedString) ==")
        println("  one dense paragraph (agent prose, no blank lines):")
        times.forEach { (size, nanos) ->
            println("    %5d chars: %7.2f ms".format(size, nanos / 1e6))
        }
        val ratio = times.getValue(4096).toDouble() / times.getValue(1024).toDouble()
        println("    4096/1024 = %.1fx (linear ≈ 4x, quadratic ≈ 16x)".format(ratio))
        // Linear parsing scales ~4x for 4x the input; the old per-character URL probe was ~16x.
        assertTrue("inline parse is not linear (ratio=$ratio)", ratio <= 8.0)

        // Production splits at blank lines first, so the same 2048-char row can be one long
        // paragraph (worst case above) or many short ones. The block parser hands each paragraph
        // to the same inline builder, so measure that shape too instead of only the worst case.
        val paragraphs = spacedMarkdownOf(2048).split("\n\n")
        val spaced = bestOf { paragraphs.forEach { buildSimpleMarkdownAnnotatedStringForTest(it) } }
        println("  2048 chars in ${paragraphs.size} short paragraphs: %.2f ms".format(spaced / 1e6))

        // The block pass (headings, lists, tables, images) runs before the inline pass.
        val blockParse = bestOf { parseSimpleMarkdownOutlineForTest(markdownOf(2048)) }
        println("  block parse of one 2048-char chunk: %.3f ms".format(blockParse / 1e6))
    }

    @Test
    fun expandingAThinkingBlockStaysWithinABudget() {
        // 237,304 chars is the thinking size of one exported slaytheperktree turn.
        val chunks = splitAiMarkdown(thinkingLikeProduction())
        val total = bestOf(3) { parseLikeProduction(chunks) }
        println("== expanded thinking block (237 KB) ==")
        println("  ${chunks.size} chunks, production block split: %.0f ms of parse".format(total / 1e6))
        assertTrue("expanding a big thinking block must stay cheap", total <= 200_000_000L)
    }

    @Test
    fun oneScrolledInRowStaysWithinAFrameBudget() {
        val denseRow = bestOf { buildSimpleMarkdownAnnotatedStringForTest(markdownOf(2048)) }
        val proseParagraphs = spacedMarkdownOf(2048).split("\n\n")
        val proseRow = bestOf { proseParagraphs.forEach { buildSimpleMarkdownAnnotatedStringForTest(it) } }
        println("== one 2048-char transcript row ==")
        println("  one dense paragraph: %.2f ms".format(denseRow / 1e6))
        println("  ${proseParagraphs.size} short paragraphs (production shape): %.2f ms".format(proseRow / 1e6))
        assertTrue("a dense row must fit in a frame", denseRow <= 8_000_000L)

        // A long agent answer is split into 2048-char rows; each row pays this cost again every
        // time it scrolls back into the viewport because remember{} dies with the composition.
        val chunks = splitAiMarkdown(markdownOf(24 * 1024))
        val all = bestOf(3) { parseLikeProduction(chunks) }
        println("  24 KiB answer in ${chunks.size} rows: %.1f ms of parse in total".format(all / 1e6))
        assertTrue("a long answer must stay cheap to parse", all <= 50_000_000L)
    }

    @Test
    fun scrollingBackReusesTheParsedRow() {
        val chunk = markdownOf(2048)
        // Hoist the styles: building SpanStyles inside the timed block would measure allocation,
        // not the parse or the cache lookup.
        val styles = inlineStyles()
        val cold = bestOf(runs = 5, warmup = 0) {
            MarkdownInlineParser.clearCache()
            MarkdownInlineParser.parse(chunk, styles)
        }
        // Second pass over the same row: a cache hit, which is what scrolling back costs now.
        val warm = bestOf(runs = 7, warmup = 2) {
            repeat(100) { MarkdownInlineParser.parse(chunk, styles) }
        } / 100
        println("== scroll back over an already seen row (2048 chars) ==")
        println("  first parse (cache miss): %.3f ms".format(cold / 1e6))
        println("  scroll back (cache hit):  %.4f ms".format(warm / 1e6))
        assertTrue("a cache hit must be much cheaper than the parse", warm * 5 < cold)

        val markdown = markdownOf(2048)
        val coldDocument = bestOf(runs = 5, warmup = 0) {
            MarkdownDocumentParser.clearCache()
            MarkdownDocumentParser.parse(markdown)
        }
        val warmDocument = bestOf { MarkdownDocumentParser.parse(markdown) }
        println("  block parse first time:   %.3f ms".format(coldDocument / 1e6))
        println("  block parse scroll back:  %.4f ms".format(warmDocument / 1e6))
        assertTrue("cached blocks must be reused", warmDocument * 5 < coldDocument)
    }

    private fun inlineStyles() = InlineStyles(
        linkStyles = TextLinkStyles(),
        boldStyle = SpanStyle(fontWeight = FontWeight.Bold),
        italicStyle = SpanStyle(fontStyle = FontStyle.Italic),
        codeStyle = SpanStyle(fontFamily = FontFamily.Monospace),
        strikeStyle = SpanStyle(textDecoration = TextDecoration.LineThrough),
    )

    private data class InlineStyles(
        val linkStyles: TextLinkStyles,
        val boldStyle: SpanStyle,
        val italicStyle: SpanStyle,
        val codeStyle: SpanStyle,
        val strikeStyle: SpanStyle,
    )

    private fun MarkdownInlineParser.parse(text: String, styles: InlineStyles) = parse(
        text = text,
        linkStyles = styles.linkStyles,
        boldStyle = styles.boldStyle,
        italicStyle = styles.italicStyle,
        codeStyle = styles.codeStyle,
        strikeStyle = styles.strikeStyle,
    )

    @Test
    fun pollNeverTouchesStoredPartsToDetectChanges() {
        val counted = CountingParts(legacyParts())
        val inMemory = AiEditorMessage(2, false, "", parts = counted)
        val messages = mutableListOf(inMemory)

        // The store hands back a fresh instance for the one changed message; identity is enough.
        val polled = inMemory.copy(parts = counted.backing.toList())
        assertTrue("a new instance must be adopted", applyIncomingMessages(messages, listOf(polled)))
        assertEquals(0, counted.reads)
        assertSame(polled, messages.single())

        // An unchanged message keeps its instance: no replacement, no part reads.
        assertFalse("an unchanged message must not be replaced", applyIncomingMessages(messages, listOf(polled)))
        println("== change detection per poll ==")
        println("  parts in the message: ${counted.backing.size}")
        println("  part reads to notice the change: ${counted.reads} (deep compare needed ${counted.backing.size})")
        assertEquals(0, counted.reads)
    }

    @Test
    fun pollReusesUnchangedRows() {
        val parts = legacyParts()
        val first = AiEditorMessage(2, false, "", streaming = true, parts = parts)
        val before = buildAiTranscriptRows(first)

        // One poll: the same logical message with one more streamed thinking delta, delivered as a
        // fresh instance (the store re-read it).
        val polled = first.copy(parts = parts + AiMessagePart(AiMessagePartKind.THINKING, "续写"))
        val fresh = buildAiTranscriptRows(polled)
        val reusedRows = reuseTranscriptRows(before, fresh)
        val newInstances = reusedRows.count { row -> before.none { it === row } }
        println("== row reuse per poll ==")
        println("  rows: ${before.size} -> ${fresh.size}")
        println("  row instances recreated: $newInstances (previously all ${fresh.size})")
        assertTrue("only the rows that grew may be recreated", newInstances <= 2)
        assertSame("unchanged rows keep their instance", before.first(), reusedRows.first())

        val rebuild = bestOf(3) { reuseTranscriptRows(before, fresh) }
        println("  reuse pass: %.3f ms".format(rebuild / 1e6))
    }

    @Test
    fun openingALegacyMessageDecodesMegabytes() {
        val json = Json
        val serializer = ListSerializer(AiMessagePart.serializer())
        val parts = legacyParts()
        val encoded = json.encodeToString(serializer, parts)
        val decode = bestOf(3) { json.decodeFromString(serializer, encoded) }
        println("== opening a legacy message (one-time, not per poll) ==")
        println("  encoded size: %.2f MB for ${parts.size} parts".format(encoded.length / 1e6))
        println("  decode: %.1f ms".format(decode / 1e6))
        assertTrue("the stored payload must be megabytes", encoded.length >= 1_000_000)
        assertTrue("decoding must be measurable", decode >= 5_000_000L)
    }

    @Test
    fun onePollStaysCheap() {
        val json = Json
        val serializer = ListSerializer(AiMessagePart.serializer())
        // New turns store coalesced parts, so a poll re-reads a small message, not megabytes.
        val baseParts = listOf(
            AiMessagePart(AiMessagePartKind.THINKING, "思考".repeat(400)),
            AiMessagePart(AiMessagePartKind.TEXT, "正文".repeat(300)),
            AiMessagePart(AiMessagePartKind.TOOL, toolId = "tool"),
        )
        val tools = listOf(AiToolCall("tool", "read_workspace_file", "{}", result = "ok"))
        val inMemory = AiEditorMessage(2, false, "", streaming = true, parts = baseParts, tools = tools)
        val baseRows = buildAiTranscriptRows(inMemory)
        var recreated = 0

        val total = bestOf {
            val messages = mutableListOf(inMemory)
            val cache = mutableMapOf(inMemory.id to baseRows)
            val stored = baseParts.mapIndexed { index, part ->
                if (index == baseParts.lastIndex - 1) part.copy(content = part.content + "续") else part
            }
            // IO thread: the store re-reads only the message whose changed_revision moved.
            val polled = inMemory.copy(
                parts = json.decodeFromString(serializer, json.encodeToString(serializer, stored)),
            )
            // UI thread: adopt the new instance by identity.
            check(applyIncomingMessages(messages, listOf(polled)))
            // Background: rebuild the rows and keep the ones that did not change.
            val previousRows = cache.getValue(polled.id)
            val fresh = buildAiTranscriptRows(polled)
            val rows = reuseTranscriptRows(previousRows, fresh)
            cache[polled.id] = rows
            recreated = rows.count { row -> previousRows.none { it === row } }
            check(rows.isNotEmpty())
        }
        println("== one 500 ms poll, end to end (coalesced message, ${baseRows.size} rows) ==")
        println("  decode + identity adopt + rebuild + reuse: %.3f ms".format(total / 1e6))
        println("  row instances recreated: $recreated (previously all ${baseRows.size})")
        assertTrue("a poll must stay far below a frame", total <= 3_000_000L)
        assertTrue("only the growing rows may be recreated", recreated in 1..2)
    }

    /**
     * Optional real-sample measurement: point `-Psts.aiTranscriptSampleDir=/path` at a directory
     * with `real-thinking.txt` / `real-answer.txt` extracted from an exported conversation.
     */
    @Test
    fun measureRealExportedTurn() {
        val dir = System.getProperty("sts.aiTranscriptSampleDir") ?: return
        val thinking = java.io.File(dir, "real-thinking.txt").takeIf { it.isFile }?.readText() ?: return
        val answer = java.io.File(dir, "real-answer.txt").takeIf { it.isFile }?.readText().orEmpty()
        val thinkingChunks = splitAiMarkdown(thinking)
        val thinkingCost = bestOf(2) { parseLikeProduction(thinkingChunks) }
        val paras = thinking.split("\n\n").filter { it.isNotBlank() }
        println("== real slaytheperktree turn (msg 14) ==")
        println("  thinking: ${thinking.length} chars, ${paras.size} paragraphs, longest ${paras.maxOf { it.length }}")
        println("  expanded thinking: ${thinkingChunks.size} chunks -> %.0f ms of parse".format(thinkingCost / 1e6))
        if (answer.isNotEmpty()) {
            val answerChunks = splitAiMarkdown(answer)
            val answerCost = bestOf(3) { parseLikeProduction(answerChunks) }
            println("  answer: ${answer.length} chars in ${answerChunks.size} chunks -> %.1f ms".format(answerCost / 1e6))
            println("  longest answer paragraph: ${answer.split("\n\n").maxOfOrNull { it.length }}")
        }
    }

    /** Mirrors the two conversations in the export: one part per streamed token delta. */
    private fun legacyParts(): List<AiMessagePart> = buildList {
        repeat(57_500) { add(AiMessagePart(AiMessagePartKind.THINKING, "思考片段$it")) }
        repeat(29) { add(AiMessagePart(AiMessagePartKind.TEXT, "正文")) }
        repeat(36) { add(AiMessagePart(AiMessagePartKind.TOOL, toolId = "tool$it")) }
    }

    private class CountingParts(val backing: List<AiMessagePart>) : AbstractList<AiMessagePart>() {
        var reads = 0
            private set

        override val size: Int get() = backing.size

        override fun get(index: Int): AiMessagePart {
            reads++
            return backing[index]
        }
    }

    private fun markdownOf(chars: Int): String {
        val sb = StringBuilder(chars + PARAGRAPH.length)
        while (sb.length < chars) sb.append(PARAGRAPH)
        return sb.substring(0, chars)
    }

    /** Same prose, but separated by blank lines so production parses many short paragraphs. */
    private fun spacedMarkdownOf(chars: Int): String {
        val sb = StringBuilder(chars + PARAGRAPH.length * 2)
        while (sb.length < chars) sb.append(PARAGRAPH).append('\n')
        return sb.substring(0, chars)
    }

    /** 237 KB of the same prose: the size of one exported thinking block. */
    private fun thinkingLikeProduction(): String = markdownOf(237_304)

    /**
     * Production renders one row per 2048-char chunk and the block parser hands each block to the
     * same inline builder. Prose blocks are paragraphs, so splitting on blank lines matches what
     * `parseSimpleMarkdown` does; code and list blocks are cheaper than this model.
     */
    private fun parseLikeProduction(chunks: List<String>) {
        chunks.forEach { chunk ->
            chunk.split("\n\n").forEach { block -> buildSimpleMarkdownAnnotatedStringForTest(block) }
        }
    }

    /** Best of several timed runs, with warmup, so a single GC pause does not decide the number. */
    private fun bestOf(runs: Int = 7, warmup: Int = 3, block: () -> Unit): Long {
        repeat(warmup) { block() }
        var best = Long.MAX_VALUE
        repeat(runs) {
            val start = System.nanoTime()
            block()
            best = minOf(best, System.nanoTime() - start)
        }
        return best
    }

    private companion object {
        /** Realistic agent prose: markdown markers force the rich path and there is no bare URL. */
        const val PARAGRAPH =
            "本次补丁改动了 agent_workspace/patch_source/perktreebutton/PerkTreeMenuEntry.java，" +
                "并在 `SimpleMarkdownContent` 前后各加了一段 **说明**，然后确认 render 路径没有重复解析。\n"
    }
}
