package top.apricityx.workshop.steam.protocol

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide CM connection pool.
 *
 * Every feature (market queries, web-token issuance, workshop downloads, cloud
 * sync, achievements, presence) borrows the same underlying [OkHttpSteamCmSession]
 * instead of opening its own websocket per operation. Connect requests are
 * serialized through one mutex and deduplicated: once the shared transport is
 * logged on, further connects with the same refresh token return immediately,
 * which removes the per-RPC/per-file logon storms that trip Steam rate limiting.
 *
 * Borrowed handles ([asCmSession]) report [SteamCmSession.close] as a no-op so
 * existing `sessionFactory().use { }` call sites keep compiling while no longer
 * tearing down the shared transport. A server-side logoff or socket failure
 * clears the session state; the next borrowed connect transparently re-logons.
 */
class SharedSteamCmSession(
    private val sessionFactory: () -> SteamCmSession,
    private val accountProvider: suspend () -> SteamAccountSession?,
) {
    private val mutex = Mutex()
    private val stateLock = Any()
    private var underlying: SteamCmSession? = null
    private val disconnected = MutableStateFlow<SessionContext?>(null)
    @Volatile private var revoked = false
    @Volatile private var loggedOnAccount: SteamAccountSession? = null
    private var failedLogon: FailedLogon? = null

    private data class FailedLogon(val account: SteamAccountSession?, val error: Exception, val retryAfterNanos: Long)

    /**
     * Returns a [SteamCmSession] handle whose close() never closes the shared
     * transport and whose connect methods reuse an existing logon.
     */
    fun asCmSession(): SteamCmSession = Borrowed()

    private fun transport(): SteamCmSession = synchronized(stateLock) {
        checkActive()
        underlying ?: sessionFactory().also { underlying = it }
    }

    val currentSession: StateFlow<SessionContext?>
        get() = synchronized(stateLock) { underlying?.currentSession ?: disconnected }

    private fun checkActive() {
        if (revoked) throw SteamSessionRevokedException()
    }

    private suspend fun currentAccount(): SteamAccountSession? {
        checkActive()
        return accountProvider().also { checkActive() }
    }

    private fun sameAccount(first: SteamAccountSession?, second: SteamAccountSession?): Boolean =
        first?.steamId == second?.steamId && first?.refreshToken == second?.refreshToken &&
            first?.accountName == second?.accountName

    /**
     * Ensures the shared transport is connected and logged on.
     *
     * Pass an explicit [requestedAccount] to pin the credentials for this call;
     * pass null to use whatever [accountProvider] currently reports, falling
     * back to an anonymous logon when no account is available. An already
     * authenticated transport is reused only while its credentials are current.
     */
    suspend fun ensureLoggedOn(
        servers: List<CmServer>,
        requestedAccount: SteamAccountSession?,
    ): SessionContext = mutex.withLock {
        val account = currentAccount()
        // Explicit credentials are a claim, never authority. A task created before logout
        // must not be allowed to resurrect its captured refresh token.
        if (requestedAccount != null && !sameAccount(requestedAccount, account)) {
            throw SteamSessionRevokedException()
        }
        // Queued callers must not turn one failed logon into an authentication storm.
        failedLogon?.let { failure ->
            if (sameAccount(failure.account, account) && System.nanoTime() < failure.retryAfterNanos) throw failure.error
        }
        if (!sameAccount(loggedOnAccount, account)) {
            synchronized(stateLock) {
                checkActive()
                underlying?.close()
                underlying = null
            }
        }
        val session = transport()
        try {
            val result = if (account != null) session.connectWithRefreshToken(servers, account)
                else session.connectAnonymous(servers)
            if (!sameAccount(account, currentAccount())) {
                session.close()
                throw SteamSessionRevokedException()
            }
            synchronized(stateLock) {
                checkActive()
                loggedOnAccount = account
                failedLogon = null
            }
            result
        } catch (error: Exception) {
            // A cancelled/failed logon must not leave an account-bearing retry plan behind.
            synchronized(stateLock) {
                if (!revoked && error !is java.util.concurrent.CancellationException) {
                    failedLogon = FailedLogon(account, error, System.nanoTime() + 30_000_000_000L)
                }
                if (underlying === session) {
                    session.close()
                    underlying = null
                    loggedOnAccount = null
                }
            }
            throw error
        }
    }

    /**
     * Permanently revokes this manager and all of its borrowed handles, including
     * queued/in-flight connects. The owner must create a new manager afterwards.
     */
    fun invalidate() {
        synchronized(stateLock) {
            revoked = true
            underlying?.close()
            underlying = null
            loggedOnAccount = null
            failedLogon = null
        }
    }

    private suspend fun checkedTransport(expectedAccount: SteamAccountSession?): SteamCmSession {
        val account = currentAccount()
        if (!sameAccount(loggedOnAccount, account) || !sameAccount(expectedAccount, account)) {
            throw SteamSessionRevokedException()
        }
        return transport()
    }

    private inner class Borrowed : SteamCmSession {
        private var expectedAccount: SteamAccountSession? = null
        private var connected = false

        private suspend fun connectBorrowed(servers: List<CmServer>, account: SteamAccountSession?): SessionContext {
            val claimedAccount = account ?: currentAccount()
            val result = ensureLoggedOn(servers, claimedAccount)
            expectedAccount = claimedAccount
            connected = true
            return result
        }

        private suspend fun checkedTransport(): SteamCmSession {
            if (!connected) throw SteamProtocolException("Steam CM handle has not connected")
            return this@SharedSteamCmSession.checkedTransport(expectedAccount)
        }

        override val currentSession get() = this@SharedSteamCmSession.currentSession

        override suspend fun connect(servers: List<CmServer>) {
            connectBorrowed(servers, null)
        }

        override suspend fun connectAnonymous(servers: List<CmServer>): SessionContext =
            connectBorrowed(servers, null)

        override suspend fun connectWithRefreshToken(
            servers: List<CmServer>,
            account: SteamAccountSession,
        ): SessionContext = connectBorrowed(servers, account)

        override suspend fun <T : com.google.protobuf.MessageLite> callServiceMethod(
            methodName: String,
            request: com.google.protobuf.MessageLite,
            parser: com.google.protobuf.Parser<T>,
        ): T = checkedTransport().callServiceMethod(methodName, request, parser)

        override suspend fun <T : com.google.protobuf.MessageLite> sendClientMessage(
            emsg: Int,
            request: com.google.protobuf.MessageLite,
            responseEmsg: Int,
            parser: com.google.protobuf.Parser<T>,
        ): T = checkedTransport().sendClientMessage(emsg, request, responseEmsg, parser)

        override suspend fun <T : com.google.protobuf.MessageLite> sendClientMessage(
            emsg: Int,
            request: com.google.protobuf.MessageLite,
            responseEmsg: Int,
            parser: com.google.protobuf.Parser<T>,
            routingAppId: UInt?,
        ): T = checkedTransport().sendClientMessage(emsg, request, responseEmsg, parser, routingAppId)

        override suspend fun sendClientMessage(emsg: Int, request: com.google.protobuf.MessageLite) =
            checkedTransport().sendClientMessage(emsg, request)

        override suspend fun sendClientMessage(
            emsg: Int,
            request: com.google.protobuf.MessageLite,
            routingAppId: UInt,
        ) = checkedTransport().sendClientMessage(emsg, request, routingAppId)

        override suspend fun requestDepotDecryptionKey(appId: UInt, depotId: UInt): ByteArray =
            checkedTransport().requestDepotDecryptionKey(appId, depotId)

        override suspend fun requestAppProductInfo(appId: UInt): SteamAppProductInfo =
            checkedTransport().requestAppProductInfo(appId)

        /** Borrowed handle: the shared transport stays open for other features. */
        override fun close() = Unit
    }
}
