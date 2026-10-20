package io.stamethyst.backend.llm

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.ToolChoice
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata
import io.stamethyst.ui.aimod.buildAiEditorSystemPrompt
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real, billable API test. Ordinary test/check/build never runs this class. */
class AgentCacheNetworkAcceptanceTest {
    @Test fun longPrefixSurvivesToolStateChangesAndRestoreWithHighMeasuredCacheReuse() {
        assumeTrue("Run :app:aiCacheAcceptanceTest explicitly; this test consumes API quota.",
            System.getProperty("sts.runAiCacheAcceptance") == "true")
        // Unique synthetic content: no user conversations, local files or Mod data leave the machine.
        val marker = UUID.randomUUID().toString()
        val config = try { OpenCodeLiveTestConfig.load().copy(cacheAffinity = marker) } catch (_: Exception) {
            throw AssertionError("Cannot read local OpenCode configuration; select a JSON config/provider using STS_AI_CACHE_OPENCODE_CONFIG / STS_AI_CACHE_PROVIDER.")
        }
        val model = AgentChatModelFactory.create(config)
        val fixed = buildAiEditorSystemPrompt("Synthetic", "synthetic", File("/synthetic/workspace")) +
            "\nYou are testing prompt cache reuse. Do not call tools. Reply only OK.\n" +
            "Synthetic acceptance run: $marker\n" + (1..400).joinToString("\n") {
                "Reference entry $it: inspect source before editing; never compile, enable or package a patch in this test."
            }
        val tool = ToolSpecification.builder().name("inspect_synthetic_reference")
            .description("Test-only schema. Must not be called.").build()
        var manager = AgentContextManager(AgentContextState(messages = listOf(
            AgentContextMessage(1, "user", "Read the reference and reply OK without calling any tool."),
        )), fixed, tool.toString(), AgentContextBudget(), 2, model, {})
        val registry = AgentToolRegistry(listOf(object : AgentTool {
            override val specification = tool
            override val safety = AgentToolSafety.READ_ONLY
            override fun execute(arguments: String): String = error("No tools should execute in cache acceptance")
        }))
        fun request(label: String): List<dev.langchain4j.data.message.ChatMessage> {
            val prepared = manager.prepare()
            val response = try {
                model.chat(ChatRequest.builder().messages(prepared).toolSpecifications(registry.toolSpecifications())
                    .maxOutputTokens(manager.budget.outputReserve).build())
            } catch (error: Exception) {
                // Do not let HTTP exceptions echo provider configuration or headers into test reports.
                throw safeFailure("Live cache request $label", error)
            }
            assertFalse("Test response must not call tools", response.aiMessage().hasToolExecutionRequests())
            assertTrue("Expected visible output", response.aiMessage().text().orEmpty().isNotBlank())
            manager.observe(response, manager.requestEstimate())
            // Diagnostics contain only whitelisted numeric usage counters, never raw HTTP bodies.
            val rawBody = (response.metadata() as? OpenAiChatResponseMetadata)?.rawHttpResponse()?.body()
            val rawUsage = rawBody?.let { org.json.JSONObject(it).optJSONObject("usage") }
            val safeUsage = listOf("prompt_tokens", "input_tokens", "completion_tokens", "total_tokens",
                "prompt_cache_hit_tokens", "prompt_cache_miss_tokens", "cached_tokens", "cache_read_input_tokens")
                .mapNotNull { key -> (rawUsage?.opt(key) as? Number)?.let { "$key=$it" } }
            val detail = rawUsage?.optJSONObject("prompt_tokens_details")?.opt("cached_tokens") as? Number
            println("Cache counters $label: ${safeUsage.joinToString()}, details.cached_tokens=$detail")
            val stats = manager.state.cacheStats
            println("Cache acceptance $label: input=${stats.lastInputTokens}, cached=${stats.lastReadTokens}, rate=${stats.lastRate}")
            assertNotNull("Provider must report cached input tokens", stats.lastReadTokens)
            manager.append(response.aiMessage())
            return prepared
        }
        val cold = request("cold")
        manager.setActivePatch("synthetic-alpha")
        assertEquals(cold, manager.prepare().take(cold.size))
        request("workspace-created")
        // DeepSeek's disk cache can be populated asynchronously. Probe a bounded number of times.
        val warmRates = ArrayList<Double>()
        repeat(3) { index ->
            if (index > 0) Thread.sleep(2000)
            manager.setActivePatch("synthetic-${index + 1}")
            val beforeRestore = manager.prepare()
            val restored = Json.decodeFromString<AgentContextState>(Json.encodeToString(manager.state))
            manager = AgentContextManager(restored, fixed, tool.toString(), AgentContextBudget(), 3 + index.toLong(), model, {})
            assertEquals(beforeRestore, manager.prepare())
            request("restored-${index + 1}")
            warmRates += requireNotNull(manager.state.cacheStats.lastRate)
        }
        assertTrue("Warm continuation must reach >= 90% cached input; measured=$warmRates", warmRates.max() >= 0.90)
        assertEquals(fixed, (manager.prepare().first() as SystemMessage).text())

        // Exercise actual provider-generated tool IDs/thinking too, not fabricated assistant calls.
        val seed = try {
            model.chat(ChatRequest.builder().messages(manager.prepare())
                .toolSpecifications(registry.toolSpecifications()).toolChoice(ToolChoice.REQUIRED)
                .maxOutputTokens(manager.budget.outputReserve).build())
        } catch (error: Exception) {
            throw safeFailure("Live synthetic tool-call request", error)
        }
        assertTrue("Provider must generate an actual tool call", seed.aiMessage().hasToolExecutionRequests())
        manager.observe(seed, manager.requestEstimate())
        manager.append(seed.aiMessage())
        seed.aiMessage().toolExecutionRequests().forEach { call ->
            assertEquals("inspect_synthetic_reference", call.name())
            manager.append(ToolExecutionResultMessage.from(call, "Synthetic inspection complete. No files changed."))
        }
        manager.setActivePatch("synthetic-after-tool")
        val toolContext = manager.prepare()
        manager = AgentContextManager(Json.decodeFromString(Json.encodeToString(manager.state)), fixed,
            tool.toString(), AgentContextBudget(), 7, model, {})
        assertEquals(toolContext, manager.prepare())
        request("after-real-tool-restore")
        assertTrue("Actual tool history must retain high prefix reuse", manager.state.cacheStats.lastRate!! >= 0.90)

        println("Cache acceptance non-streaming passed: weightedTotal=${manager.state.cacheStats.totalRate}, responses=${manager.state.cacheStats.requests}")
    }

    @Test fun streamingEndpointReportsWarmCacheUsage() {
        assumeTrue("Run :app:aiCacheAcceptanceTest explicitly; this test consumes API quota.",
            System.getProperty("sts.runAiCacheAcceptance") == "true")
        val marker = UUID.randomUUID().toString()
        val config = try { OpenCodeLiveTestConfig.load().copy(cacheAffinity = marker) } catch (_: Exception) {
            throw AssertionError("Cannot read local OpenCode JSON configuration/API credential.")
        }
        val model = AgentChatModelFactory.create(config)
        val stream = AgentChatModelFactory.createStreaming(config)!!
        val fixed = "Reply only OK; do not use tools. Synthetic streaming cache run $marker.\n" +
            (1..400).joinToString("\n") { "Reference $it: this is a synthetic read-only cache acceptance test, not a real Mod or user file." }
        val manager = AgentContextManager(AgentContextState(messages = listOf(AgentContextMessage(1, "user", "Reply only OK."))),
            fixed, "", AgentContextBudget(), 2, model, {})
        val warm = try {
            model.chat(ChatRequest.builder().messages(manager.prepare()).maxOutputTokens(1024).build())
        } catch (error: Exception) { throw safeFailure("Streaming warm-up", error) }
        manager.observe(warm, manager.requestEstimate())
        manager.append(warm.aiMessage())
        manager.setActivePatch("synthetic-stream")
        val result = AtomicReference<ChatResponse?>()
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        // Exactly one streamed request. No non-streaming fallback can make this test pass falsely.
        try {
            stream.chat(ChatRequest.builder().messages(manager.prepare()).maxOutputTokens(1024).build(),
                object : StreamingChatResponseHandler {
                    override fun onPartialResponse(partialResponse: String) = Unit
                    override fun onCompleteResponse(complete: ChatResponse) { result.set(complete); done.countDown() }
                    override fun onError(error: Throwable) { failure.set(error); done.countDown() }
                })
        } catch (error: Exception) { throw safeFailure("Streaming endpoint", error) }
        assertTrue("Streaming response timed out", done.await(310, TimeUnit.SECONDS))
        failure.get()?.let { throw safeFailure("Streaming endpoint", it) }
        val response = requireNotNull(result.get())
        assertTrue("Streaming endpoint must return visible text", response.aiMessage().text().orEmpty().isNotBlank())
        manager.observe(response, manager.requestEstimate())
        println("Cache acceptance streaming: input=${manager.state.cacheStats.lastInputTokens}, cached=${manager.state.cacheStats.lastReadTokens}, rate=${manager.state.cacheStats.lastRate}")
        assertNotNull("Streaming must retain cache statistics", manager.state.cacheStats.lastReadTokens)
        assertTrue("Streaming must preserve the warm prefix", manager.state.cacheStats.lastRate!! >= 0.90)
    }

    private fun safeFailure(stage: String, error: Throwable): AssertionError {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val types = chain.joinToString(" -> ") { it.javaClass.simpleName }
        val status = chain.filterIsInstance<dev.langchain4j.exception.HttpException>().firstOrNull()?.statusCode()
        val reason = when {
            chain.any { it.message.orEmpty().contains("reasoning_content", ignoreCase = true) } -> "reasoning-field-protocol"
            chain.any { it.message.orEmpty().contains("tool", ignoreCase = true) } -> "tool-protocol"
            chain.any { it.message.orEmpty().contains("timeout", ignoreCase = true) } -> "timeout"
            else -> "unspecified"
        }
        return AssertionError("$stage failed ($types; status=$status; category=$reason); endpoint/credentials/body omitted.")
    }
}
