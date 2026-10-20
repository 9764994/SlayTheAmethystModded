package io.stamethyst.backend.llm

import dev.langchain4j.agent.tool.ToolSpecification
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AgentToolSchedulerGatewayTest {
    @Test(timeout = 20_000)
    fun nonStreamingLoopRunsToolsInParallelAndWaitsBeforeNextModelTurn() = verifyLoop(streaming = false)

    @Test(timeout = 20_000)
    fun streamingLoopUsesSameSchedulerAndPreservesToolCallIds() = verifyLoop(streaming = true)

    private fun verifyLoop(streaming: Boolean) {
        MockWebServer().use { server ->
            server.start()
            val started = CountDownLatch(2)
            val secondCompleted = CountDownLatch(1)
            val tools = (0..1).map { index ->
                object : AgentTool {
                    override val specification = ToolSpecification.builder().name("read$index")
                        .description("Read an independent resource").build()
                    override val safety = AgentToolSafety.READ_ONLY
                    override val concurrency = AgentToolConcurrency.PARALLEL
                    override fun execute(arguments: String): String {
                        started.countDown()
                        check(started.await(3, TimeUnit.SECONDS)) { "Tools were not run concurrently" }
                        if (index == 0) check(secondCompleted.await(3, TimeUnit.SECONDS))
                        return "result$index"
                    }
                }
            }
            val registry = AgentToolRegistry(tools, onToolExecution = {
                if (it.name == "read1" && it.result != null) secondCompleted.countDown()
            })
            val calls = """[
                {"index":0,"id":"call-0","type":"function","function":{"name":"read0","arguments":"{}"}},
                {"index":1,"id":"call-1","type":"function","function":{"name":"read1","arguments":"{}"}}
            ]""".trimIndent()
            if (streaming) {
                server.enqueue(streamResponse("""{"role":"assistant","tool_calls":$calls}""", "tool_calls"))
                server.enqueue(streamResponse("""{"role":"assistant","content":"done"}""", "stop"))
            } else {
                server.enqueue(response("""{"role":"assistant","tool_calls":$calls}""", "tool_calls"))
                server.enqueue(response("""{"role":"assistant","content":"done"}""", "stop"))
            }
            val config = OpenAiCompatibleModelConfig(server.url("/v1/").toString(), "test-key", "test-model")
            val model = AgentChatModelFactory.create(config)
            val caller = Thread.currentThread()
            val checkpoints = mutableListOf<AgentContextState>()
            val manager = AgentContextManager(
                AgentContextState(messages = listOf(AgentContextMessage(1, "user", "go"))),
                "inspect", registry.toolSpecifications().joinToString("\n"), AgentContextBudget(), 2, model,
                checkpoint = { state ->
                    assertEquals("checkpoints must stay on the agent thread", caller, Thread.currentThread())
                    checkpoints += state
                },
            )
            val gateway = PolicyGatedAgentGateway(model, registry, contextManager = manager)
            val reply = if (streaming) gateway.respondStreaming(
                "inspect", "go", requireNotNull(AgentChatModelFactory.createStreaming(config)), onText = {},
            ) else gateway.respond("inspect", "go")

            assertEquals("done", reply.text)
            assertEquals(1, reply.toolRounds)
            assertEquals(2, server.requestCount)
            server.takeRequest()
            val request = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject
            val messages = request["messages"]!!.jsonArray.map { it.jsonObject }
            val results = messages.filter { it["role"]!!.jsonPrimitive.content == "tool" }
            assertEquals(listOf("call-0", "call-1"), results.map { it["tool_call_id"]!!.jsonPrimitive.content })
            assertEquals(listOf("result0", "result1"), results.map { it["content"]!!.jsonPrimitive.content })
            assertTrue(messages.any { it["tool_calls"]?.jsonArray?.size == 2 })
            val saved = manager.state.messages
            assertEquals(listOf("call-0", "call-1"), saved.filter { it.role == "tool" }.map { it.callId })
            assertTrue("assistant calls must be persisted before tools run", checkpoints.any {
                it.messages.any { message -> message.calls.size == 2 } && it.messages.none { message -> message.role == "tool" }
            })
            assertTrue("each ordered result must be checkpointed", checkpoints.any {
                it.messages.count { message -> message.role == "tool" } == 1
            })
        }
    }

    private fun response(message: String, finish: String) = MockResponse.Builder().code(200).body(
        """{"id":"test","object":"chat.completion","created":0,"model":"test-model",
            "choices":[{"index":0,"message":$message,"finish_reason":"$finish"}]}""".trimIndent(),
    ).build()

    private fun streamResponse(delta: String, finish: String): MockResponse {
        // SSE data must be a single line, even when the embedded call array was built over lines.
        val chunk = """{"id":"test","object":"chat.completion.chunk","created":0,"model":"test-model",
            "choices":[{"index":0,"delta":$delta,"finish_reason":"$finish"}]}""".replace("\n", "")
        return MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream")
            .body("data: $chunk\n\ndata: [DONE]\n\n").build()
    }
}
