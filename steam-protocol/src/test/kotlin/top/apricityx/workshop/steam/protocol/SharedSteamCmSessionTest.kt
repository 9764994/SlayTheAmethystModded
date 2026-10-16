package top.apricityx.workshop.steam.protocol

import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SharedSteamCmSessionTest {
    private val servers = listOf(CmServer("cm.example:443", "websockets"))
    private val account = SteamAccountSession("account", 76561198000000001L, "token")

    @Test fun concurrentBorrowersShareOneLogonAndCloseDoesNotDisconnect() = runBlocking {
        val transport = FakeSession()
        val shared = SharedSteamCmSession({ transport }, { account })
        (1..20).map { async { shared.asCmSession().use { it.connectWithRefreshToken(servers, account) } } }.awaitAll()
        assertEquals(listOf(account), transport.logons)
        assertFalse(transport.closed)
        shared.invalidate()
        assertTrue(transport.closed)
    }

    @Test fun capturedAccountCannotLogOnAfterCredentialsWereCleared() = runBlocking {
        var saved: SteamAccountSession? = account
        var created = 0
        val shared = SharedSteamCmSession({ created++; FakeSession() }, { saved })
        val borrower = shared.asCmSession()
        saved = null
        assertRevoked { borrower.connectWithRefreshToken(servers, account) }
        assertEquals(0, created)
    }

    @Test fun invalidatingUnusedManagerDoesNotCreateATransport() = runBlocking {
        var created = 0
        val shared = SharedSteamCmSession({ created++; FakeSession() }, { account })
        val borrower = shared.asCmSession()
        shared.invalidate()
        shared.invalidate()
        assertNull(borrower.currentSession.value)
        assertRevoked { borrower.connectAnonymous(servers) }
        assertEquals(0, created)
    }

    @Test fun invalidateWhileAccountProviderIsSuspendedStopsLogon() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var created = 0
        val shared = SharedSteamCmSession({ created++; FakeSession() }, {
            entered.complete(Unit)
            resume.await()
            account
        })
        val pending = async { runCatching { shared.asCmSession().connectWithRefreshToken(servers, account) } }
        entered.await()
        shared.invalidate()
        resume.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is SteamSessionRevokedException)
        assertEquals(0, created)
    }

    @Test fun invalidateDuringLogonClosesTransportAndRejectsQueuedBorrowers() = runBlocking {
        val pendingLogon = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val transport = FakeSession { entered.complete(Unit); pendingLogon.await() }
        val shared = SharedSteamCmSession({ transport }, { account })
        val pending = async { runCatching { shared.asCmSession().connectWithRefreshToken(servers, account) } }
        entered.await()
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { shared.asCmSession().connectWithRefreshToken(servers, account) }
        }
        shared.invalidate()
        assertTrue(transport.closed)
        pendingLogon.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is SteamSessionRevokedException)
        assertTrue(queued.await().exceptionOrNull() is SteamSessionRevokedException)
    }

    @Test fun accountChangedWhileLogonWasInFlightCannotPublishSession() = runBlocking {
        var saved: SteamAccountSession? = account
        val transport = FakeSession { saved = null }
        val shared = SharedSteamCmSession({ transport }, { saved })
        assertRevoked { shared.asCmSession().connectWithRefreshToken(servers, account) }
        assertTrue(transport.closed)
    }

    @Test fun missingAccountReplacesAuthenticatedTransportWithAnonymous() = runBlocking {
        var saved: SteamAccountSession? = account
        val transports = mutableListOf<FakeSession>()
        val shared = SharedSteamCmSession({ FakeSession().also(transports::add) }, { saved })
        val old = shared.asCmSession()
        old.connectWithRefreshToken(servers, account)
        saved = null
        shared.asCmSession().connectAnonymous(servers)
        assertTrue(transports.first().closed)
        assertEquals(listOf<SteamAccountSession?>(null), transports.last().logons)
        assertRevoked { old.requestDepotDecryptionKey(1u, 2u) }
    }

    @Test fun oldBorrowerCannotSendAsAnotherAccount() = runBlocking {
        var saved = account
        val shared = SharedSteamCmSession({ FakeSession() }, { saved })
        val old = shared.asCmSession()
        old.connectWithRefreshToken(servers, account)
        saved = account.copy(steamId = account.steamId + 1, refreshToken = "new-token")
        shared.asCmSession().connectWithRefreshToken(servers, saved)
        assertRevoked { old.requestDepotDecryptionKey(1u, 2u) }
    }

    @Test fun newManagerCanConnectAnonymouslyAfterRevocation() = runBlocking {
        val old = SharedSteamCmSession({ FakeSession() }, { account })
        old.invalidate()
        val transport = FakeSession()
        val fresh = SharedSteamCmSession({ transport }, { null })
        fresh.asCmSession().connectAnonymous(servers)
        assertEquals(listOf<SteamAccountSession?>(null), transport.logons)
    }

    @Test fun failedLogonDiscardsTransportWithoutPoisoningFutureLogins() = runBlocking {
        val failed = FakeSession { throw SteamAuthenticationException(5, "rejected") }
        val recovered = FakeSession()
        var created = 0
        var saved = account
        val shared = SharedSteamCmSession({ if (created++ == 0) failed else recovered }, { saved })
        assertTrue(runCatching { shared.asCmSession().connectWithRefreshToken(servers, account) }.exceptionOrNull()
            is SteamAuthenticationException)
        assertTrue(failed.closed)
        // The same rejected credentials are throttled across queued callers.
        assertTrue(runCatching { shared.asCmSession().connectWithRefreshToken(servers, account) }.isFailure)
        assertEquals(1, created)
        saved = account.copy(refreshToken = "new-token")
        shared.asCmSession().connectWithRefreshToken(servers, saved)
        assertEquals(listOf(saved), recovered.logons)
    }

    private suspend fun assertRevoked(block: suspend () -> Unit) {
        try { block(); fail("Expected terminal revocation") } catch (_: SteamSessionRevokedException) { }
    }

    private class FakeSession(private val beforeLogon: suspend () -> Unit = {}) : SteamCmSession {
        override val currentSession = MutableStateFlow<SessionContext?>(null)
        val logons = mutableListOf<SteamAccountSession?>()
        var closed = false
        override suspend fun connect(servers: List<CmServer>) = Unit
        override suspend fun connectAnonymous(servers: List<CmServer>) = logon(null)
        override suspend fun connectWithRefreshToken(servers: List<CmServer>, account: SteamAccountSession) = logon(account)
        private suspend fun logon(account: SteamAccountSession?): SessionContext {
            if (closed) throw SteamSessionRevokedException()
            currentSession.value?.let { return it }
            beforeLogon()
            if (closed) throw SteamSessionRevokedException()
            logons += account
            return SessionContext(1, account?.steamId ?: 0L, 0u, 30).also { currentSession.value = it }
        }
        override suspend fun <T : MessageLite> callServiceMethod(methodName: String, request: MessageLite, parser: Parser<T>): T = error("unused")
        override suspend fun <T : MessageLite> sendClientMessage(emsg: Int, request: MessageLite, responseEmsg: Int, parser: Parser<T>): T = error("unused")
        override suspend fun sendClientMessage(emsg: Int, request: MessageLite) = Unit
        override suspend fun sendClientMessage(emsg: Int, request: MessageLite, routingAppId: UInt) = Unit
        override suspend fun requestDepotDecryptionKey(appId: UInt, depotId: UInt) = byteArrayOf(1)
        override suspend fun requestAppProductInfo(appId: UInt): SteamAppProductInfo = error("unused")
        override fun close() { closed = true; currentSession.value = null }
    }
}
