package top.apricityx.workshop.steam.protocol

import java.io.File
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in, credential-free A/B. Does not prove authenticated Cloud RPC availability. */
class SteamCmSelectionBenchmarkTest {
    @Test fun compareDirectoryOrderWithSelectionOnRealNetwork() = runBlocking {
        assumeTrue("Set STS_CM_BENCHMARK=1 to contact Steam", System.getenv("STS_CM_BENCHMARK") == "1")
        val client = OkHttpClient.Builder().build()
        try {
            val servers = SteamDirectoryClient(client).loadServers()
            require(servers.isNotEmpty()) { "Steam returned no websocket candidates" }
            val rounds = (System.getenv("STS_CM_BENCHMARK_ROUNDS")?.toIntOrNull() ?: 6).coerceIn(2, 30)
            val rows = mutableListOf("round,mode,success,elapsed_ms,connections")
            val timings = mutableMapOf("serial" to mutableListOf<Long>(), "selected" to mutableListOf<Long>())
            val successes = mutableMapOf("serial" to 0, "selected" to 0)
            repeat(rounds) { round ->
                // Alternate order to reduce warm DNS/TLS and changing-network bias.
                val modes = if (round % 2 == 0) listOf("serial", "selected") else listOf("selected", "serial")
                for (mode in modes) {
                    val connections = java.util.concurrent.atomic.AtomicInteger()
                    val factory = SteamWebSocketFactory { request, listener ->
                        connections.incrementAndGet()
                        client.newWebSocket(request, listener)
                    }
                    val started = System.nanoTime()
                    val success = withTimeoutOrNull(45_000) {
                        val selector = if (mode == "selected") SteamCmServerSelector(factory) else null
                        OkHttpSteamCmSession(client, webSocketFactory = factory, serverSelector = selector).use { session ->
                            // Transport-only: sends ClientHello, never ClientLogon or account credentials.
                            try { session.connect(servers); true } catch (error: java.io.IOException) { false }
                            catch (error: SteamProtocolException) { false }
                        }
                    } ?: false
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    timings.getValue(mode) += elapsed
                    if (success) successes[mode] = successes.getValue(mode) + 1
                    rows += "$round,$mode,$success,$elapsed,${connections.get()}"
                }
            }
            val output = File(System.getenv("STS_CM_BENCHMARK_OUT") ?: "../agent-tmp/cm-selection-benchmark.csv")
            output.parentFile?.mkdirs()
            output.writeText(rows.joinToString("\n", postfix = "\n"))
            val summary = timings.entries.joinToString("\n") { (mode, values) ->
                val sorted = values.sorted()
                "$mode success=${successes.getValue(mode)}/$rounds p50=${sorted[(sorted.size - 1) / 2]}ms p95=${sorted[(ceil(sorted.size * .95).toInt() - 1).coerceAtLeast(0)]}ms"
            }
            File(output.path + ".summary.txt").writeText(summary + "\n")
            println("CM benchmark: ${output.absolutePath}\n$summary")
            // No universal speedup assertion: on a healthy first node, probing can add overhead.
            assertTrue("Neither strategy reached Steam; see $output", successes.values.sum() > 0)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
