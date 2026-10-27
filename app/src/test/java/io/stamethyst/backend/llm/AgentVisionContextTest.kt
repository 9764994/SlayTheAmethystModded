package io.stamethyst.backend.llm

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.output.FinishReason
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AgentVisionContextTest {
    private val pngBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))

    private fun screenshot(name: String = "screen.png") =
        AgentContextAttachment(name = name, mime = "image/png", data = pngBase64)

    private fun manager(
        state: AgentContextState,
        supportsImageInput: Boolean = true,
    ) = AgentContextManager(
        initial = state,
        systemPrompt = "system",
        toolSchema = "tools",
        budget = AgentContextBudget(200_000),
        sourceMessageId = 10,
        model = fakeModel(),
        checkpoint = {},
        supportsImageInput = supportsImageInput,
    )

    @Test
    fun toolImagesBecomeOneUserTurnAfterTheWholeToolBatch() {
        val state = AgentContextState(messages = listOf(
            AgentContextMessage(1, "user", "Look at the screenshot"),
            AgentContextMessage(2, "assistant", calls = listOf(
                AgentContextToolCall("call-image", "read_agent_workspace_file", "{}"),
                AgentContextToolCall("call-text", "read_agent_workspace_file", "{}"),
            )),
            AgentContextMessage(2, "tool", "{\"type\":\"image\"}", callId = "call-image",
                toolName = "read_agent_workspace_file", attachments = listOf(screenshot())),
            AgentContextMessage(2, "tool", "note text", callId = "call-text",
                toolName = "read_agent_workspace_file"),
        ))

        val prepared = manager(state).prepare()

        assertEquals(6, prepared.size)
        assertTrue(prepared[3] is ToolExecutionResultMessage)
        assertTrue(prepared[4] is ToolExecutionResultMessage)
        val mediaTurn = prepared[5] as UserMessage
        assertEquals(2, mediaTurn.contents().size)
        assertEquals(SYNTHETIC_ATTACHMENT_PROMPT, (mediaTurn.contents()[0] as TextContent).text())
        val image = (mediaTurn.contents()[1] as ImageContent).image()
        assertEquals("image/png", image.mimeType())
        assertEquals(pngBase64, image.base64Data())
    }

    @Test
    fun imageAttachmentsFromTheUserStayInTheUserTurn() {
        val record = AgentContextMessage(
            1, "user", "What is wrong here?",
            attachments = listOf(
                screenshot("crash.png"),
                AgentContextAttachment("log.txt", "java.lang.IllegalStateException"),
            ),
        )

        val message = record.toChatMessages().single() as UserMessage

        assertEquals(2, message.contents().size)
        val text = (message.contents()[0] as TextContent).text()
        assertTrue(text.startsWith("What is wrong here?"))
        assertTrue(text.contains("Attachment: log.txt"))
        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertEquals("image/png", (message.contents()[1] as ImageContent).image().mimeType())
    }

    @Test
    fun disabledImageInputDescribesImagesInsteadOfSendingThem() {
        val state = AgentContextState(messages = listOf(
            AgentContextMessage(1, "user", "Look", attachments = listOf(screenshot())),
            AgentContextMessage(2, "assistant", calls = listOf(
                AgentContextToolCall("call-image", "read_agent_workspace_file", "{}"),
            )),
            AgentContextMessage(2, "tool", "{}", callId = "call-image",
                toolName = "read_agent_workspace_file", attachments = listOf(screenshot())),
        ))

        val prepared = manager(state, supportsImageInput = false).prepare()

        prepared.forEach { message ->
            if (message is UserMessage) {
                assertTrue(
                    "no image parts may be sent to a text-only model",
                    message.contents().none { it is ImageContent },
                )
            }
        }
        val mediaTurn = prepared.last() as UserMessage
        assertTrue(
            mediaTurn.contents().filterIsInstance<TextContent>().any { it.text().contains("cannot view images") },
        )
    }

    @Test
    fun toolImageTokenEstimateDoesNotCountBase64() {
        val small = AgentContextMessage(1, "tool", "{}", callId = "a", toolName = "read",
            attachments = listOf(AgentContextAttachment("a.png", mime = "image/png", data = "AAAA")))
        val huge = small.copy(attachments = listOf(
            AgentContextAttachment("a.png", mime = "image/png", data = Base64.getEncoder().encodeToString(ByteArray(400_000))),
        ))

        assertEquals(AgentTokenEstimate.message(small), AgentTokenEstimate.message(huge))
        assertEquals(
            AGENT_IMAGE_TOKEN_ESTIMATE,
            AgentTokenEstimate.message(small) - AgentTokenEstimate.message(small.copy(attachments = emptyList())),
        )
    }

    @Test
    fun imageAttachmentsSurviveContextSerialization() {
        val state = AgentContextState(messages = listOf(
            AgentContextMessage(1, "user", "Look", attachments = listOf(screenshot())),
        ))

        val restored = Json.decodeFromString<AgentContextState>(Json.encodeToString(state))

        assertEquals(screenshot(), restored.messages.single().attachments.single())
        assertEquals("image/png", restored.messages.single().attachments.single().mime)
    }

    @Test
    fun legacyTextAttachmentsKeepWorking() {
        val legacy = Json.decodeFromString<AgentContextState>(
            """{"messages":[{"sourceMessageId":1,"role":"user","text":"x","attachments":[{"name":"a.txt","content":"hi"}]}]}""",
        )

        val attachment = legacy.messages.single().attachments.single()
        assertEquals("text/plain", attachment.mime)
        assertEquals("", attachment.data)
        assertFalse(attachment.isImage)
        assertTrue((legacy.messages.single().toChatMessages().single() as UserMessage).singleText().contains("hi"))
    }

    @Test
    fun toolMediaIsNotReplayedAsToolText() {
        val record = AgentContextMessage(2, "tool", "{\"type\":\"image\"}", callId = "a",
            toolName = "read_agent_workspace_file", attachments = listOf(screenshot()))

        val toolMessage = record.toChatMessages().single() as ToolExecutionResultMessage

        assertNull(toolMessage.contents().firstOrNull { it is ImageContent })
        assertEquals("{\"type\":\"image\"}", toolMessage.text())
    }

    private fun fakeModel() = object : ChatModel {
        override fun doChat(request: ChatRequest): ChatResponse =
            ChatResponse.builder().aiMessage(AiMessage.from("summary")).finishReason(FinishReason.STOP).build()
    }
}
