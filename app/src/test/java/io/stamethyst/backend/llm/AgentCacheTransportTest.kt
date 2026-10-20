package io.stamethyst.backend.llm

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentCacheTransportTest {
    @Test fun readsDeepSeekNativeAndOpenAiUsageFromRealNonStreamingDecoder() {
        MockWebServer().use { server ->
            server.start()
            val model = AgentChatModelFactory.create(config(server))
            listOf("\"prompt_cache_hit_tokens\":80", "\"prompt_tokens_details\":{\"cached_tokens\":80}").forEach { field ->
                server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json")
                    .body("""{"id":"test","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":1,"total_tokens":101,$field}}""").build())
                val response = model.chat(ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from("inspect")).build())
                assertEquals(AgentCacheUsage(100, 80, null), AgentCacheUsage.from(response))
            }
        }
    }

    @Test fun streamingUsageSurvivesSseDoneAndIsObservedOnce() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                .body("""
                    data: {"id":"test","choices":[{"index":0,"delta":{"role":"assistant","content":"OK"},"finish_reason":null}]}

                    data: {"id":"test","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                    data: {"id":"test","choices":[],"usage":{"prompt_tokens":100,"completion_tokens":1,"total_tokens":101,"prompt_cache_hit_tokens":80}}

                    data: [DONE]

                """.trimIndent() + "\n").build())
            val model = AgentChatModelFactory.create(config(server))
            val manager = AgentContextManager(AgentContextState(), "system", "", AgentContextBudget(), 1, model, {})
            PolicyGatedAgentGateway(model, AgentToolRegistry(emptyList()), contextManager = manager)
                .respondStreaming("", "", AgentChatModelFactory.createStreaming(config(server))!!, {})
            assertEquals(1L, manager.state.cacheStats.requests)
            assertEquals(80L, manager.state.cacheStats.lastReadTokens)
            assertEquals(0.8, manager.state.cacheStats.lastRate!!, 0.00001)
            assertTrue(server.takeRequest().body!!.utf8().contains("include_usage"))
        }
    }

    @Test fun responsesEndpointUsesInclusiveInputAndNativeCachedDetails() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json")
                .body("""{"id":"resp-fixture","object":"response","status":"completed","output":[{"id":"msg-fixture","type":"message","role":"assistant","content":[{"type":"output_text","text":"OK","annotations":[]}]}],"usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":80},"output_tokens":1,"total_tokens":101}}""").build())
            val model = AgentChatModelFactory.create(config(server).copy(endpoint = LlmEndpoint.RESPONSES))
            val response = model.chat(ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from("inspect")).build())
            assertEquals(AgentCacheUsage(100, 80, null), AgentCacheUsage.from(response))
        }
    }

    @Test fun streamingFallbackCountsAllCompletedResponsesWithoutInventingMissingCacheUsage() {
        var streams = 0
        val streaming = object : dev.langchain4j.model.chat.StreamingChatModel {
            override fun doChat(request: ChatRequest, handler: dev.langchain4j.model.chat.response.StreamingChatResponseHandler) {
                streams++
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("")).build())
            }
        }
        val model = object : ChatModel {
            override fun doChat(request: ChatRequest): ChatResponse = ChatResponse.builder().aiMessage(AiMessage.from("OK"))
                .tokenUsage(dev.langchain4j.model.openai.OpenAiTokenUsage.builder().inputTokenCount(100)
                    .inputTokensDetails(dev.langchain4j.model.openai.OpenAiTokenUsage.InputTokensDetails.builder()
                        .cachedTokens(80).build()).build()).build()
        }
        val manager = AgentContextManager(AgentContextState(), "system", "", AgentContextBudget(), 1, model, {})
        val reply = PolicyGatedAgentGateway(model, AgentToolRegistry(emptyList()), contextManager = manager)
            .respondStreaming("", "", streaming, {})
        assertEquals("OK", reply.text)
        assertEquals(2, streams)
        assertEquals(3L, manager.state.cacheStats.requests)
        assertEquals(1L, manager.state.cacheStats.reportedRequests)
        assertEquals(0.8, manager.state.cacheStats.lastRate!!, 0.00001)
        assertEquals(0.8, manager.state.cacheStats.totalRate!!, 0.00001)
    }

    @Test fun responseWithoutCacheUsageClearsLastValueWithoutDilutingKnownCumulativeRate() {
        MockWebServer().use { server ->
            server.start()
            val model = AgentChatModelFactory.create(config(server))
            val manager = AgentContextManager(AgentContextState(), "system", "", AgentContextBudget(), 1, model, {})
            listOf(""""prompt_cache_hit_tokens":80""", """"unused":0""").forEach { counter ->
                server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json")
                    .body("""{"choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":1,"total_tokens":101,$counter}}""").build())
                PolicyGatedAgentGateway(model, AgentToolRegistry(emptyList()), contextManager = manager).respond("", "")
            }
            assertNull(manager.state.cacheStats.lastRate)
            assertEquals(0.8, manager.state.cacheStats.totalRate!!, 0.00001)
            assertEquals(2L, manager.state.cacheStats.requests)
            assertEquals(1L, manager.state.cacheStats.reportedRequests)
        }
    }

    @Test fun emptyRetryResponsesAreCountedAndToolsHaveCanonicalOrder() {
        var count = 0
        val model = object : ChatModel {
            override fun doChat(request: ChatRequest): ChatResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from(if (++count == 1) "" else "OK")).build()
        }
        val manager = AgentContextManager(AgentContextState(), "system", "", AgentContextBudget(), 1, model, {})
        PolicyGatedAgentGateway(model, AgentToolRegistry(emptyList()), contextManager = manager).respond("", "")
        assertEquals(2L, manager.state.cacheStats.requests)
        assertEquals(0L, manager.state.cacheStats.reportedRequests)
        fun tool(name: String) = object : AgentTool {
            override val specification = ToolSpecification.builder().name(name).build()
            override val safety = AgentToolSafety.READ_ONLY
            override fun execute(arguments: String) = "OK"
        }
        val a = tool("a")
        val z = tool("z")
        assertEquals(AgentToolRegistry(listOf(a, z)).toolSpecifications(), AgentToolRegistry(listOf(z, a)).toolSpecifications())
    }

    @Test fun workspaceCreationKeepsWirePrefixToolsAndAffinityAndNeverSplitsToolResults() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json")
                .body("""{"choices":[{"index":0,"message":{"role":"assistant","content":"","tool_calls":[{"id":"create-1","type":"function","function":{"name":"create","arguments":"{}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":100,"completion_tokens":10,"prompt_cache_hit_tokens":0}}""").build())
            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json")
                .body("""{"choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":120,"completion_tokens":1,"prompt_cache_hit_tokens":96}}""").build())
            val model = AgentChatModelFactory.create(config(server).copy(cacheAffinity = "synthetic-session"))
            val manager = AgentContextManager(AgentContextState(messages = listOf(AgentContextMessage(1, "user", "create"))),
                "fixed system", "", AgentContextBudget(), 2, model, {})
            val tool = object : AgentTool {
                override val specification = ToolSpecification.builder().name("create").build()
                override val safety = AgentToolSafety.READ_ONLY
                override fun execute(arguments: String): String {
                    manager.setActivePatch("alpha")
                    return """{"patch_id":"alpha"}"""
                }
            }
            PolicyGatedAgentGateway(model, AgentToolRegistry(listOf(tool)), contextManager = manager).respond("", "")
            val first = server.takeRequest()
            val second = server.takeRequest()
            val a = JSONObject(first.body!!.utf8())
            val b = JSONObject(second.body!!.utf8())
            val old = a.getJSONArray("messages")
            val next = b.getJSONArray("messages")
            (0 until old.length()).forEach { assertEquals(old.getJSONObject(it).toString(), next.getJSONObject(it).toString()) }
            assertEquals(a.getJSONArray("tools").toString(), b.getJSONArray("tools").toString())
            assertEquals("assistant", next.getJSONObject(2).getString("role"))
            assertEquals("tool", next.getJSONObject(3).getString("role"))
            assertEquals("user", next.getJSONObject(4).getString("role"))
            assertTrue(next.getJSONObject(4).getString("content").contains("alpha"))
            assertEquals("synthetic-session", first.headers["x-session-affinity"])
            assertEquals(first.headers["x-session-affinity"], second.headers["x-session-affinity"])
            assertFalse(a.has("prompt_cache_key"))
            assertFalse(a.has("cache_control"))
            assertEquals(2L, manager.state.cacheStats.reportedRequests)
            assertEquals(96.0 / 220, manager.state.cacheStats.totalRate!!, 0.00001)
        }
    }

    @Test fun successfulResponseCheckpointFailureDoesNotRepeatApiRequest() {
        var calls = 0
        var failCheckpoint = false
        val model = object : ChatModel {
            override fun doChat(request: ChatRequest): ChatResponse {
                calls++
                failCheckpoint = true
                return ChatResponse.builder().aiMessage(AiMessage.from("OK")).build()
            }
        }
        val manager = AgentContextManager(AgentContextState(), "system", "", AgentContextBudget(), 1, model,
            { if (failCheckpoint) throw java.io.IOException("checkpoint unavailable") })
        assertThrows(java.io.IOException::class.java) {
            PolicyGatedAgentGateway(model, AgentToolRegistry(emptyList()), contextManager = manager).respond("", "")
        }
        assertEquals(1, calls)
    }

    @Test fun exclusiveGatewayInputIsAlsoNormalizedForContextCalibration() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json")
                .body("""{"choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"input_tokens":0,"completion_tokens":1,"total_tokens":101,"prompt_tokens_details":{"cached_tokens":32000}}}""").build())
            val model = AgentChatModelFactory.create(config(server))
            val manager = AgentContextManager(AgentContextState(), "system", "", AgentContextBudget(), 1, model, {})
            PolicyGatedAgentGateway(model, AgentToolRegistry(emptyList()), contextManager = manager).respond("", "")
            assertEquals(32100, manager.state.lastInputTokens)
            assertTrue(manager.state.cacheStats.inputExcludesCached)
            assertTrue(manager.state.cacheStats.lastRate!! > 0.99)
            assertTrue("Never calibrate occupancy against the uncached subset", manager.state.estimatedTokens!! >= 32100)
        }
    }

    private fun config(server: MockWebServer) = OpenAiCompatibleModelConfig(
        server.url("/v1/").toString(), "local-test-key", "deepseek-v4-flash",
    )
}
