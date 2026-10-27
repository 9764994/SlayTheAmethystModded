package io.stamethyst.backend.llm

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata
import dev.langchain4j.http.client.sse.ServerSentEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class AgentCacheUsageTest {
    private fun parse(json: String) = AgentCacheUsage.parse(Json.parseToJsonElement(json).jsonObject)

    @Test fun normalizesOpenAiResponsesAndDeepSeekWithoutDoubleCounting() {
        assertEquals(AgentCacheUsage(100, 80, null), parse("""{"prompt_tokens":100,"prompt_tokens_details":{"cached_tokens":80},"prompt_cache_hit_tokens":70}"""))
        assertEquals(AgentCacheUsage(100, 64, null), parse("""{"prompt_tokens":100,"prompt_cache_hit_tokens":64,"prompt_cache_miss_tokens":36}"""))
        assertEquals(AgentCacheUsage(100, 90, null), parse("""{"input_tokens":100,"input_tokens_details":{"cached_tokens":90}}"""))
        assertEquals(AgentCacheUsage(100, 50, 20), parse("""{"prompt_tokens":100,"cache_read_input_tokens":50,"cache_created_input_tokens":20}"""))
    }

    @Test fun missingInvalidAndZeroAreDistinct() {
        assertNull(parse("""{"prompt_tokens":100}""").read)
        assertEquals(0L, parse("""{"prompt_tokens":100,"prompt_cache_hit_tokens":0}""").read)
        assertNull(parse("""{"prompt_tokens":100,"prompt_cache_hit_tokens":101}""").read)
        assertNull(parse("""{"prompt_tokens":100,"prompt_cache_hit_tokens":-1}""").read)
        assertNull(parse("""{"prompt_cache_hit_tokens":10}""").read)
        assertNull(AgentCacheStats().lastRate)
        assertNull(AgentCacheStats().observe(AgentCacheUsage(0, 0, null)).lastRate)
    }

    @Test fun streamingIgnoresDoneAndNullUsageAfterTheFinalCounterEvent() {
        val response = ChatResponse.builder().aiMessage(AiMessage.from("OK"))
            .metadata(OpenAiChatResponseMetadata.builder().rawServerSentEvents(listOf(
                ServerSentEvent(null, """{"usage":{"prompt_tokens":100,"prompt_cache_hit_tokens":80}}"""),
                ServerSentEvent(null, """{"usage":null,"choices":[]}"""),
                ServerSentEvent(null, "[DONE]"),
            )).build()).build()
        assertEquals(AgentCacheUsage(100, 80, null), AgentCacheUsage.from(response))
    }

    @Test fun cumulativeRateIsTokenWeightedAndMissingStatisticsAreExcluded() {
        val stats = AgentCacheStats()
            .observe(AgentCacheUsage(100, 0, null))
            .observe(AgentCacheUsage(900, 900, null))
            .observe(AgentCacheUsage(500, null, null))
        assertEquals(0.9, stats.totalRate!!, 0.00001)
        assertNull(stats.lastRate)
        assertNull(stats.lastReadTokens)
        assertEquals(3L, stats.requests)
        assertEquals(2L, stats.reportedRequests)
        assertEquals(stats, Json.decodeFromString<AgentCacheStats>(Json.encodeToString(stats)))
        assertEquals(AgentCacheStats(), Json.decodeFromString<AgentContextState>("{}").cacheStats)
    }

    @Test fun learnsExclusiveGatewayConventionAndNormalizesFutureAndEarlierInputs() {
        val cold = parse("""{"prompt_tokens":31642,"input_tokens":0,"completion_tokens":54,"total_tokens":31696,"prompt_tokens_details":{"cached_tokens":1280}}""")
        val hot = parse("""{"prompt_tokens":187,"input_tokens":0,"completion_tokens":68,"total_tokens":255,"prompt_tokens_details":{"cached_tokens":32768}}""")
        assertEquals(32955L, hot.input)
        assertEquals(32768L, hot.read)
        assertTrue(hot.inputExcludesCached)
        val stats = AgentCacheStats().observe(cold).observe(hot)
        assertTrue(stats.inputExcludesCached)
        assertEquals(31642L + 1280 + 32955, stats.inputTokens)
        assertTrue(stats.lastRate!! > 0.99)
        val next = AgentCacheUsage.parse(Json.parseToJsonElement("""{"prompt_tokens":200,"prompt_tokens_details":{"cached_tokens":128}}""").jsonObject,
            inputExcludesCached = stats.inputExcludesCached)
        assertEquals(328L, next.input)
        assertTrue(stats.observe(next).totalRate!! <= 1.0)
        assertFalse(parse("""{"prompt_tokens":10,"prompt_tokens_details":{"cached_tokens":20}}""").inputExcludesCached)
        val withWrite = AgentCacheUsage.parse(Json.parseToJsonElement(
            """{"prompt_tokens":200,"prompt_tokens_details":{"cached_tokens":128,"cache_write_tokens":64}}""").jsonObject,
            inputExcludesCached = true)
        assertEquals(392L, withWrite.input)
        assertEquals(64L, withWrite.write)
        assertNull(parse("""{"prompt_tokens":100,"prompt_cache_hit_tokens":"invalid"}""").read)
        assertNull(parse("""{"prompt_tokens":100,"prompt_cache_hit_tokens":null}""").read)
    }

    @Test fun patchChangesAppendWithoutRewritingSystemOrHistoryAndRestoreIsStable() {
        val manager = manager(AgentContextState(messages = listOf(AgentContextMessage(1, "user", "inspect"))))
        val first = manager.prepare()
        manager.setActivePatch("alpha")
        val second = manager.prepare()
        assertEquals(first, second.take(first.size))
        assertTrue((second.last() as UserMessage).singleText().contains("alpha"))
        assertEquals(second, manager.prepare())
        manager.setActivePatch("beta")
        val third = manager.prepare()
        assertEquals(second, third.take(second.size))
        val restored = manager(Json.decodeFromString(Json.encodeToString(manager.state))).prepare()
        assertEquals(third, restored)
        assertEquals("fixed system", (third.first() as SystemMessage).text())
    }

    @Test fun compactionKeepsFixedSystemAndRestoresCurrentPatchState() {
        val manager = manager(AgentContextState(activePatchId = "alpha", messages = listOf(
            AgentContextMessage(1, "user", "Do not enable"),
            AgentContextMessage(2, "assistant", "obsolete diagnostics ".repeat(2500)),
            AgentContextMessage(3, "user", "continue"),
        )))
        val before = manager.prepare()
        val after = manager.prepare(force = true)
        assertEquals(before.first(), after.first())
        assertTrue((after[1] as UserMessage).singleText().contains("<conversation-checkpoint>"))
        assertTrue((after.last() as UserMessage).singleText().contains("alpha"))
        assertEquals(after, manager.prepare())
        assertEquals(after, manager(manager.state).prepare())
    }

    @Test fun longToolLoopWithPatchNoticeKeepsTheActualUserGoalAfterCompaction() {
        val original = AgentContextState(activePatchId = "alpha", messages = listOf(
            AgentContextMessage(1, "user", "Never enable alpha"),
            AgentContextMessage(2, "assistant", calls = listOf(AgentContextToolCall("big", "read", "{}"))),
            AgentContextMessage(2, "tool", "obsolete ".repeat(10000), callId = "big", toolName = "read"),
        ))
        val manager = manager(original)
        val compacted = manager.prepare(force = true)
        assertEquals("Never enable alpha", manager.state.messages.first().text)
        assertTrue(manager.state.summary.contains("do not enable"))
        assertTrue((compacted.last() as UserMessage).singleText().contains("Current active patch ID: alpha"))
        assertEquals(compacted, manager.prepare())
        assertEquals(compacted, manager(manager.state).prepare())
    }

    @Test fun rollbackRemovesPatchNoticesForRemovedSourcesButKeepsSpentUsage() {
        val stats = AgentCacheStats().observe(AgentCacheUsage(1000, 500, null))
        val original = AgentContextState(activePatchId = "beta", cacheStats = stats, messages = listOf(
            AgentContextMessage(1, "user", "inspect alpha"),
            AgentContextMessage(2, "user", "state alpha", patchState = "alpha"),
            AgentContextMessage(3, "user", "state beta", patchState = "beta"),
        ))
        val retained = original.retainSources(setOf(1, 2))
        assertEquals("alpha", retained.activePatchId)
        assertEquals(stats, retained.cacheStats)
    }

    private fun manager(state: AgentContextState) = AgentContextManager(state, "fixed system", "tools",
        AgentContextBudget(), 4, object : ChatModel {
            override fun doChat(request: ChatRequest): ChatResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("## Objective\nVerified memory; do not enable.")).build()
        }, {})
}
