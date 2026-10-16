package top.apricityx.workshop.steam.protocol

import java.io.IOException
import java.util.LinkedHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** No credentials or Steam messages are sent by a probe. All probe sockets are cancelled. */
class SteamCmServerSelector(
    private val webSocketFactory: SteamWebSocketFactory,
    private val networkScope: () -> String = { "default" },
    private val health: SteamCmServerHealth = SteamCmServerHealth(),
    val probeTimeoutMs: Long = 6_000,
    val selectionAndLogonTimeoutMs: Long = 45_000,
    val optimisticConnectTimeoutMs: Long = 750,
    private val concurrency: Int = 3,
    private val candidateLimit: Int = 20,
    private val collectionWindowMs: Long = 150,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    init {
        require(probeTimeoutMs > 0 && selectionAndLogonTimeoutMs > 0)
        require(optimisticConnectTimeoutMs > 0)
        require(concurrency > 0 && candidateLimit > 0 && collectionWindowMs > 0)
    }

    // Capture once per round: callbacks from an old network must not poison a new network.
    fun scope(): String = networkScope()
    fun loggedOn(scope: String, server: CmServer) = health.loggedOn(scope, server)
    fun failed(scope: String, server: CmServer) = health.failed(scope, server)
    fun preferred(scope: String, servers: List<CmServer>): CmServer? = health.rank(scope, servers).firstOrNull()

    suspend fun select(
        servers: List<CmServer>,
        scope: String = scope(),
        forceProbe: Boolean = false,
    ): List<CmServer> = coroutineScope {
        val ranked = health.rank(scope, servers.distinctBy(CmServer::websocketUri))
        if (ranked.isEmpty() || (!forceProbe && health.isRecentWinner(scope, ranked.first()))) return@coroutineScope ranked
        val eligible = ranked.filterNot { health.isCooling(scope, it) }
            .ifEmpty { ranked }.take(candidateLimit)
        val queue = Channel<CmServer>(eligible.size)
        eligible.forEach { queue.trySend(it) }
        queue.close()
        val results = Channel<Pair<CmServer, Boolean>>(eligible.size)
        val workers = List(minOf(concurrency, eligible.size)) {
            launch {
                for (server in queue) {
                    val started = nowNanos()
                    val success = try {
                        withTimeout(probeTimeoutMs) { probe(server) }
                        health.probed(scope, server, (nowNanos() - started) / 1_000_000.0)
                        true
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (error !is IOException && error !is TimeoutCancellationException) throw error
                        health.failed(scope, server)
                        false
                    }
                    results.send(server to success)
                }
            }
        }
        val successful = mutableListOf<CmServer>()
        try {
            var received = 0
            while (received < eligible.size && successful.isEmpty()) {
                val (server, ok) = results.receive()
                received++
                if (ok) successful += server
            }
            // Do not wait for a blackholed endpoint once a usable handshake is available.
            withTimeoutOrNull(collectionWindowMs) {
                while (received < eligible.size) {
                    val (server, ok) = results.receive()
                    received++
                    if (ok) successful += server
                }
            }
        } finally {
            workers.forEach { it.cancel() }
            workers.forEach { it.join() }
            queue.cancel()
            results.cancel()
        }
        health.rank(scope, successful) + health.rank(scope, ranked.filterNot { it in successful })
    }

    private suspend fun probe(server: CmServer) {
        val opened = CompletableDeferred<Unit>()
        val socket = webSocketFactory.newWebSocket(
            Request.Builder().url(server.websocketUri).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    opened.completeExceptionally(t)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    opened.completeExceptionally(IOException("CM probe closed: $code"))
                }
            },
        )
        try { opened.await() } finally { socket.cancel() }
    }
}

/** Bounded, process-local history. No account material or persistent network identifiers. */
class SteamCmServerHealth(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Entry(
        var latencyMs: Double = Double.MAX_VALUE,
        var successAt: Long? = null,
        var failures: Int = 0,
        var retryAt: Long = 0,
    )
    private val entries = object : LinkedHashMap<Pair<String, String>, Entry>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, Entry>?) = size > 128
    }
    private fun entry(scope: String, server: CmServer) = entries.getOrPut(scope to server.websocketUri) { Entry() }
    @Synchronized fun probed(scope: String, server: CmServer, latencyMs: Double) {
        val entry = entry(scope, server)
        entry.latencyMs = if (entry.latencyMs == Double.MAX_VALUE) latencyMs else .7 * entry.latencyMs + .3 * latencyMs
        // Handshake success must not erase a login-routing failure/cooldown.
    }
    @Synchronized fun loggedOn(scope: String, server: CmServer) {
        entry(scope, server).apply { successAt = nowMs(); failures = 0; retryAt = 0 }
    }
    @Synchronized fun failed(scope: String, server: CmServer) {
        entry(scope, server).apply {
            failures = (failures + 1).coerceAtMost(4)
            successAt = null
            retryAt = nowMs() + 30_000L * failures
        }
    }
    @Synchronized fun isCooling(scope: String, server: CmServer) = entry(scope, server).retryAt > nowMs()
    @Synchronized fun isRecentWinner(scope: String, server: CmServer): Boolean {
        val entry = entry(scope, server)
        return !isCooling(scope, server) && entry.successAt?.let { nowMs() - it < 600_000 } == true
    }
    @Synchronized fun rank(scope: String, servers: List<CmServer>): List<CmServer> = servers.sortedWith(
        compareBy<CmServer> { isCooling(scope, it) }
            .thenByDescending { isRecentWinner(scope, it) }
            .thenBy { entry(scope, it).latencyMs },
    )
}
