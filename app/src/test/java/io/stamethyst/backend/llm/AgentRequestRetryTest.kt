package io.stamethyst.backend.llm

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.exception.HttpException
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import java.io.IOException
import java.util.concurrent.CancellationException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AgentRequestRetryTest {
    @Test
    fun retryPolicy_rejectsPermanentErrorsAndCancellation() {
        listOf(400, 401, 403, 404, 422).forEach {
            assertFalse(isRetryableAgentRequestFailure(HttpException(it, "invalid request")))
        }
        assertFalse(isRetryableAgentRequestFailure(CancellationException()))
        assertFalse(isRetryableAgentRequestFailure(IllegalStateException("bad state")))
        assertFalse(isRetryableAgentRequestFailure(IOException(CancellationException())))
        listOf(408, 429, 500, 502, 503).forEach {
            assertTrue(isRetryableAgentRequestFailure(HttpException(it, "temporary")))
        }
        assertTrue(isRetryableAgentRequestFailure(IOException("connection reset")))
    }

    @Test
    fun permanentFailure_isNotRetried() {
        var attempts = 0
        val model = object : ChatModel {
            override fun doChat(request: ChatRequest): ChatResponse {
                attempts++
                throw HttpException(401, "bad key")
            }
        }
        val error = runCatching {
            PolicyGatedAgentGateway(model, AgentToolRegistry(emptyList()), onRetry = { _, _, _ ->
                fail("permanent errors must not retry")
            }).respond("system", "user")
        }.exceptionOrNull()
        assertTrue(error is HttpException)
        assertEquals(1, attempts)
    }

    @Test
    fun streaming_deliversPartialOutputBeforeCompletionWithoutDuplicatingIt() {
        val delivered = ArrayList<String>()
        val streaming = object : StreamingChatModel {
            override fun doChat(request: ChatRequest, handler: StreamingChatResponseHandler) {
                handler.onPartialResponse("hello")
                assertEquals(listOf("hello"), delivered)
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("hello")).build())
                handler.onPartialResponse("late")
            }
        }
        val gateway = PolicyGatedAgentGateway(object : ChatModel {}, AgentToolRegistry(emptyList()))
        val result = gateway.respondStreaming("system", "user", streaming, { delivered += it })
        assertEquals("hello", result.text)
        assertEquals(listOf("hello"), delivered)
    }

    @Test(timeout = 2_000)
    fun streaming_missingTerminalCallback_timesOutInsteadOfWaitingForever() {
        val gateway = PolicyGatedAgentGateway(
            object : ChatModel {}, AgentToolRegistry(emptyList()),
            streamingTimeoutMillis = 20,
            onRetry = { _, _, error ->
                assertTrue(error is java.net.SocketTimeoutException)
                throw CancellationException("stop after first timeout")
            },
        )
        val error = runCatching {
            gateway.respondStreaming("system", "user", object : StreamingChatModel {
                override fun doChat(request: ChatRequest, handler: StreamingChatResponseHandler) = Unit
            }, {})
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }

    @Test(timeout = 4_000)
    fun streaming_retrySeparatesPartialFailedAttemptAndIgnoresLateCallbacks() {
        var attempt = 0
        var oldHandler: StreamingChatResponseHandler? = null
        val failedOutput = ArrayList<String>()
        val output = StringBuilder()
        val gateway = PolicyGatedAgentGateway(object : ChatModel {}, AgentToolRegistry(emptyList()),
            onRetry = { _, _, _ ->
                failedOutput += output.toString()
                output.setLength(0)
            },
        )
        val result = gateway.respondStreaming("system", "user", object : StreamingChatModel {
            override fun doChat(request: ChatRequest, handler: StreamingChatResponseHandler) {
                if (attempt++ == 0) {
                    oldHandler = handler
                    handler.onPartialResponse("partial")
                    handler.onError(IOException("connection reset"))
                } else {
                    oldHandler!!.onPartialResponse("stale")
                    handler.onPartialResponse("recovered")
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("recovered")).build())
                }
            }
        }, { output.append(it) })
        assertEquals(listOf("partial"), failedOutput)
        assertEquals("recovered", output.toString())
        assertEquals("recovered", result.text)
    }

    @Test(timeout = 2_000)
    fun cancellationDuringBackoff_preventsNextRequest() {
        var cancelled = false
        var attempts = 0
        val model = object : ChatModel {
            override fun doChat(request: ChatRequest): ChatResponse {
                attempts++
                throw IOException("temporary")
            }
        }
        val error = runCatching {
            PolicyGatedAgentGateway(model, AgentToolRegistry(emptyList()),
                checkCancelled = { if (cancelled) throw CancellationException() },
                onRetry = { _, _, _ -> cancelled = true },
            ).respond("system", "user")
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, attempts)
    }

    @Test(timeout = 6_000)
    fun transientFailure_stopsAfterTwoRetries() {
        var attempts = 0
        val retries = ArrayList<Int>()
        val model = object : ChatModel {
            override fun doChat(request: ChatRequest): ChatResponse {
                attempts++
                throw IOException("temporary")
            }
        }
        val error = runCatching {
            PolicyGatedAgentGateway(model, AgentToolRegistry(emptyList()),
                onRetry = { number, _, _ -> retries += number },
            ).respond("system", "user")
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(3, attempts)
        assertEquals(listOf(1, 2), retries)
    }

    @Test(timeout = 10_000)
    fun httpFailure_hasNoHiddenModelRetries() {
        MockWebServer().use { server ->
            server.start()
            repeat(6) { server.enqueue(MockResponse.Builder().code(503).body("unavailable").build()) }
            val config = OpenAiCompatibleModelConfig(
                baseUrl = server.url("/v1/").toString(), apiKey = "test", modelName = "test",
            )
            val retries = ArrayList<Int>()
            val error = runCatching {
                PolicyGatedAgentGateway(AgentChatModelFactory.create(config), AgentToolRegistry(emptyList()),
                    onRetry = { number, _, _ -> retries += number },
                ).respond("system", "user")
            }.exceptionOrNull()
            assertNotNull(error)
            assertEquals(listOf(1, 2), retries)
            assertEquals(3, server.requestCount)
        }
    }
}
