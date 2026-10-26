package io.stamethyst.ui.aimod

import org.junit.Assert.*
import org.junit.Test

class AiTranscriptTest {
    @Test fun plainTextChunksDoNotInjectCodeFences() {
        val text = "```\n" + "plain\n".repeat(2000) + "```"
        val chunks = splitAiMarkdown(text, preserveFences = false)
        assertEquals(2, chunks.sumOf { Regex("```").findAll(it).count() })
    }

    @Test fun mergesManyDeltasWithoutChangingToolOrder() {
        val parts = List(20_000) { AiMessagePart(AiMessagePartKind.TEXT, "字") } +
            AiMessagePart(AiMessagePartKind.TOOL, toolId = "tool") +
            AiMessagePart(AiMessagePartKind.THINKING, "thought")
        val merged = coalesceAiParts(parts)
        assertEquals(3, merged.size)
        assertEquals("字".repeat(20_000), merged[0].content)
        assertEquals("tool", merged[1].toolId)
        assertEquals(merged, coalesceAiParts(merged))
    }

    @Test fun largeTurnIsSplitIntoBoundedLazyRowsWithOneFooter() {
        val message = AiEditorMessage(2, false, "", parts = listOf(
            AiMessagePart(AiMessagePartKind.TEXT, "x".repeat(100_000)),
            AiMessagePart(AiMessagePartKind.TOOL, toolId = "tool"),
        ), tools = listOf(AiToolCall("tool", "read", "{}")), failed = true)
        val rows = buildAiTranscriptRows(message)
        assertTrue(rows.size > 40)
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
        assertEquals(1, rows.count { it.footer })
        assertTrue(rows.last().message.failed)
        assertTrue(rows.dropLast(1).none { it.message.failed })
        assertTrue(rows.flatMap { it.message.parts }.all { it.content.length < 4096 })
        assertEquals(1, rows.sumOf { it.message.tools.size })
    }

    @Test fun splitCodeReopensFenceAndDoesNotBreakSurrogatePairs() {
        val chunks = splitAiMarkdown("```java\n" + "🙂".repeat(10_000) + "\n```", 257)
        assertTrue(chunks.size > 10)
        chunks.forEach { chunk ->
            assertTrue(chunk.startsWith("```java\n"))
            assertTrue(chunk.endsWith("```"))
            val body = chunk.removePrefix("```java\n").removeSuffix("```").trimEnd('\n')
            assertEquals("", body.replace("🙂", ""))
        }
    }
    @Test fun incomingMessagesKeepUnchangedInstances() {
        val first = AiEditorMessage(1, false, "", parts = List(4) { AiMessagePart(AiMessagePartKind.TEXT, "x") })
        val second = AiEditorMessage(2, false, "done", streaming = false)
        val current = mutableListOf(first)

        assertFalse("an identical instance is not a change", applyIncomingMessages(current, listOf(first)))
        assertSame(first, current.single())

        // The store re-reads only the changed message, so only that one is a new instance.
        val updatedFirst = first.copy(streaming = true)
        assertTrue(applyIncomingMessages(current, listOf(updatedFirst, second)))
        assertSame(updatedFirst, current[0])
        assertSame(second, current[1])

        assertTrue("a removed tail is a change", applyIncomingMessages(current, listOf(updatedFirst)))
        assertEquals(listOf(updatedFirst), current)
    }

    @Test fun unchangedRowsKeepTheirInstancesAcrossAPoll() {
        val message = AiEditorMessage(7, false, "", streaming = true, parts = listOf(
            AiMessagePart(AiMessagePartKind.THINKING, "think"),
            AiMessagePart(AiMessagePartKind.TEXT, "a".repeat(100)),
            AiMessagePart(AiMessagePartKind.TOOL, toolId = "tool"),
        ), tools = listOf(AiToolCall("tool", "read_workspace_file", "{}")))
        val before = buildAiTranscriptRows(message)

        // The streaming tail grows: everything before it renders the same and must be reused.
        val grown = message.copy(parts = listOf(
            AiMessagePart(AiMessagePartKind.THINKING, "think"),
            AiMessagePart(AiMessagePartKind.TEXT, "a".repeat(150)),
            AiMessagePart(AiMessagePartKind.TOOL, toolId = "tool"),
        ))
        val after = reuseTranscriptRows(before, buildAiTranscriptRows(grown))

        assertEquals(before.map { it.key }, after.map { it.key })
        assertSame(before.first(), after.first())
        assertEquals(
            "only the grown text row is a new instance",
            listOf(1),
            after.indices.filter { index -> before[index] !== after[index] },
        )
    }

    @Test fun aRowThatChangesKindIsNotReused() {
        val message = AiEditorMessage(8, false, "", parts = listOf(
            AiMessagePart(AiMessagePartKind.THINKING, "think"),
            AiMessagePart(AiMessagePartKind.TEXT, "text"),
        ))
        val before = buildAiTranscriptRows(message)
        val replaced = message.copy(parts = listOf(
            AiMessagePart(AiMessagePartKind.THINKING, "think"),
            AiMessagePart(AiMessagePartKind.TOOL, toolId = "tool"),
        ), tools = listOf(AiToolCall("tool", "read_workspace_file", "{}")))
        val after = reuseTranscriptRows(before, buildAiTranscriptRows(replaced))

        assertEquals(before.map { it.key }, after.map { it.key })
        assertSame(before.first(), after.first())
        assertNotSame(before[1], after[1])
    }
}
