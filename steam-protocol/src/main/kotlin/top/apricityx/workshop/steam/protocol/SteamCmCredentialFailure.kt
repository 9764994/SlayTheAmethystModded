package top.apricityx.workshop.steam.protocol

/**
 * Only an explicit ClientLogon credential rejection warrants a re-login suggestion.
 * A failed connection, rate limit, routing failure or generic AccessDenied does not
 * establish that the saved refresh token is invalid. Do not match exception text.
 */
fun Throwable.isSteamCmCredentialRejection(): Boolean =
    this is SteamAuthenticationException && resultCode in setOf(
        5,   // InvalidPassword (ClientLogon using a refresh token)
        27,  // Expired
        106, // InvalidSignature
        109, // ExpiredLoginAuthCode
    )
