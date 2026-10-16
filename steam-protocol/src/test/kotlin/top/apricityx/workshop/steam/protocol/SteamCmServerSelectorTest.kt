package top.apricityx.workshop.steam.protocol

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class SteamCmServerSelectorTest {
    private val servers = (1..8).map { CmServer("cm$it.example:443", "websockets") }

    @Test fun fastNodeWinsWithoutWaitingForBlackholeAndEveryProbeIsCancelled() = runBlocking {
        Fixture(mapOf("cm1.example" to 10_000L, "cm2.example" to 10_000L, "cm3.example" to 10L)).use { fixture ->
            val selector = SteamCmServerSelector(fixture.factory, collectionWindowMs = 20)
            val selected = withTimeout(2_000) { selector.select(servers) }
            assertEquals(servers[2], selected.first())
            assertTrue(fixture.peak.get() <= 3)
            assertEquals(0, fixture.active.get())
            assertTrue(fixture.sockets.all { it.cancelled })
            assertEquals(0, fixture.messages.get())
        }
    }

    @Test fun failedNodesCoolDownAndNetworkScopesAreIndependent() {
        var now = 0L
        val health = SteamCmServerHealth { now }
        health.loggedOn("wifi", servers[1])
        assertEquals(servers[1], health.rank("wifi", servers).first())
        assertEquals(servers[0], health.rank("mobile", servers).first())
        health.failed("wifi", servers[1])
        health.probed("wifi", servers[1], 1.0)
        assertTrue(health.isCooling("wifi", servers[1]))
        assertFalse(health.isRecentWinner("wifi", servers[1]))
        assertEquals(servers[1], health.rank("wifi", servers).last())
        now = 30_001
        assertFalse(health.isCooling("wifi", servers[1]))
        health.loggedOn("wifi", servers[1])
        now += 600_001
        assertFalse(health.isRecentWinner("wifi", servers[1]))
    }

    @Test fun recentWinnerSkipsProbesAndCallerCancellationCleansUp() = runBlocking {
        Fixture(emptyMap()).use { fixture ->
            val health = SteamCmServerHealth()
            health.loggedOn("wifi", servers[4])
            val selector = SteamCmServerSelector(fixture.factory, { "wifi" }, health)
            assertEquals(servers[4], selector.select(servers).first())
            assertTrue(fixture.sockets.isEmpty())
            runCatching { withTimeout(50) { selector.select(servers, "mobile") } }
            assertTrue(fixture.sockets.isNotEmpty())
            assertEquals(0, fixture.active.get())
        }
    }

    @Test fun probesBeyondTheFirstThreeFailuresAndDeduplicates() = runBlocking {
        Fixture(mapOf("cm1.example" to -1L, "cm2.example" to -1L, "cm3.example" to -1L, "cm4.example" to 10L)).use { fixture ->
            val selector = SteamCmServerSelector(fixture.factory, collectionWindowMs = 20)
            val selected = withTimeout(2_000) { selector.select(servers + servers) }
            assertEquals(servers[3], selected.first())
            assertEquals(8, selected.size)
            assertEquals(0, fixture.active.get())
        }
    }

    @Test fun slowHistoricalWinnerDoesNotSuppressForcedProbing() = runBlocking {
        Fixture(mapOf("cm1.example" to 10_000L, "cm2.example" to 10L)).use { fixture ->
            val health = SteamCmServerHealth().apply { loggedOn("wifi", servers.first()) }
            val selector = SteamCmServerSelector(fixture.factory, { "wifi" }, health, collectionWindowMs = 20)
            assertEquals(servers[1], selector.select(servers, forceProbe = true).first())
            assertEquals(0, fixture.active.get())
        }
    }

    @Test fun allFailuresRemainAvailableAsFallbackAndHistoryIsBounded() = runBlocking {
        Fixture(servers.associate { it.endpoint.substringBefore(':') to -1L }).use { fixture ->
            val selector = SteamCmServerSelector(fixture.factory)
            assertEquals(servers.toSet(), selector.select(servers).toSet())
            assertEquals(0, fixture.active.get())
        }
        val health = SteamCmServerHealth()
        repeat(1_000) { health.loggedOn("network-$it", servers.first()) }
        assertFalse(health.isRecentWinner("network-0", servers.first()))
    }

    @Test fun smallParallelSelectionImprovesTimeToTransportInSlowFirstNodeScenario() = runBlocking {
        val delays = mapOf("cm1.example" to 2_400L, "cm2.example" to 2_400L, "cm3.example" to 10L)
        suspend fun elapsed(selected: Boolean): Long {
            Fixture(delays).use { fixture ->
                val start = System.nanoTime()
                val selector = if (selected) SteamCmServerSelector(fixture.factory) else null
                OkHttpSteamCmSession(webSocketFactory = fixture.factory, serverSelector = selector).use {
                    withTimeout(5_000) { it.connect(servers) }
                }
                assertEquals(0, fixture.active.get())
                return (System.nanoTime() - start) / 1_000_000
            }
        }
        val serialMs = elapsed(false)
        val selectedMs = elapsed(true)
        println("Controlled slow-first CM A/B: serial=${serialMs}ms selected=${selectedMs}ms")
        assertTrue("Expected selection to beat slow-first baseline: $serialMs vs $selectedMs", selectedMs < serialMs / 2)
    }

    @Test fun healthyFirstNodeUsesOneConnectionWithoutProbing() = runBlocking {
        Fixture(mapOf("cm1.example" to 10L)).use { fixture ->
            OkHttpSteamCmSession(
                webSocketFactory = fixture.factory,
                serverSelector = SteamCmServerSelector(fixture.factory),
            ).use { it.connect(servers) }
            assertEquals(1, fixture.sockets.size)
            assertEquals(0, fixture.active.get())
        }
    }

    internal class Fixture(private val delays: Map<String, Long>) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val messages = AtomicInteger()
        val sockets = CopyOnWriteArrayList<ProbeSocket>()
        val factory = SteamWebSocketFactory { request, listener ->
            val socket = ProbeSocket(request, active, messages)
            sockets += socket
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            socket.job = scope.launch {
                val wait = delays[request.url.host] ?: 10_000L
                if (wait < 0) listener.onFailure(socket, IOException("unreachable"), null)
                else {
                    delay(wait)
                    listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(101).message("Switching Protocols").build())
                }
            }
            socket
        }
        override fun close() { sockets.forEach { it.cancel() }; scope.cancel() }
    }

    internal class ProbeSocket(
        private val request: Request,
        private val active: AtomicInteger,
        private val messages: AtomicInteger,
    ) : WebSocket {
        var job: kotlinx.coroutines.Job? = null
        @Volatile var cancelled = false
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages.incrementAndGet(); return true }
        override fun send(bytes: ByteString): Boolean { messages.incrementAndGet(); return true }
        override fun close(code: Int, reason: String?) = true
        @Synchronized override fun cancel() {
            if (!cancelled) { cancelled = true; active.decrementAndGet(); job?.cancel() }
        }
    }
}
