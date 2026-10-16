package io.stamethyst.backend.workshop

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl

class WorkshopSteamWebSessionTest {
    @Test
    fun revokedScopeDropsCookiesAndIgnoresLateResponses() {
        var scope: String? = "account"
        val jar = SteamWebSessionCookieJar(sessionScopeProvider = { scope })
        val url = "https://steamcommunity.com/workshop/".toHttpUrl()
        val cookie = Cookie.Builder().name("steamLoginSecure").value("test-token")
            .domain("steamcommunity.com").path("/").secure().build()
        jar.saveFromResponse(url, listOf(cookie))
        assertEquals(listOf(cookie), jar.loadForRequest(url))
        scope = null
        assertEquals(emptyList<Cookie>(), jar.loadForRequest(url))
        jar.saveFromResponse(url, listOf(cookie))
        scope = "new-account"
        assertEquals(emptyList<Cookie>(), jar.loadForRequest(url))
        jar.saveFromResponse(url, listOf(cookie))
        jar.clear()
        assertEquals(emptyList<Cookie>(), jar.loadForRequest(url))
    }

    @Test
    fun expirationMillisOrNull_readsJwtExpiration() {
        val token = jwtWithPayload("{\"sub\":\"76561198000000000\",\"exp\":1900000000}")

        assertEquals(1_900_000_000_000L, token.expirationMillisOrNull())
    }

    @Test
    fun expirationMillisOrNull_rejectsMissingOrInvalidExpiration() {
        assertNull(jwtWithPayload("{\"sub\":\"76561198000000000\"}").expirationMillisOrNull())
        assertNull(jwtWithPayload("{\"exp\":0}").expirationMillisOrNull())
        assertNull("not-a-jwt".expirationMillisOrNull())
    }

    private fun jwtWithPayload(payload: String): String = listOf(
        Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".toByteArray()),
        Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()),
        "signature",
    ).joinToString(".")
}
