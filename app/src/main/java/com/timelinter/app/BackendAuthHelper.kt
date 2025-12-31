package com.timelinter.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Exception representing an HTTP failure from the backend.
 */
class BackendHttpException(
    val statusCode: Int,
    message: String,
    val code: String? = null,
) : IOException(message)

object BackendAccessCode {
    const val PENDING_APPROVAL = "PENDING_APPROVAL"
    const val ACCESS_REFUSED = "ACCESS_REFUSED"
}

/**
 * Exception representing an authentication/refresh failure when talking to the backend.
 */
class BackendAuthException(message: String) : Exception(message)

/**
 * Abstraction over the backend generate call, primarily to enable testing and retries.
 */
interface BackendGateway {
    suspend fun generate(
        token: String,
        model: String,
        contents: List<BackendClient.BackendContent>,
        prompt: String? = null,
    ): BackendClient.GenerateResponse
}

/**
 * Real gateway that delegates to [BackendClient].
 */
class RealBackendGateway : BackendGateway {
    override suspend fun generate(
        token: String,
        model: String,
        contents: List<BackendClient.BackendContent>,
        prompt: String?,
    ): BackendClient.GenerateResponse {
        return withContext(Dispatchers.IO) {
            BackendClient.generate(token, model, contents, prompt)
        }
    }
}

/**
 * Handles retrieving, refreshing, and persisting app tokens for backend calls.
 *
 * Flow:
 * - Use stored app token if present and not expired.
 * - If app token is missing or expired, get Google ID token (or trigger sign-in).
 * - Exchange Google ID token for a new app token (valid for 1 month).
 * - Use app token for API calls.
 * - If backend returns 401, clear tokens, re-sign, exchange, and retry once.
 */
class BackendAuthHelper(
    private val signIn: suspend () -> String?,
    private val getAppToken: () -> String?,
    private val isAppTokenExpired: () -> Boolean,
    private val saveAppToken: (String, Long) -> Unit,
    private val clearAppToken: () -> Unit,
    private val getGoogleIdToken: () -> String?,
    private val backend: BackendGateway,
    private val timeProviderMs: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun generateWithAutoRefresh(
        model: String,
        contents: List<BackendClient.BackendContent>,
        prompt: String? = null,
    ): BackendClient.GenerateResponse {
        var token = ensureValidAppToken()
        if (token == null) {
            throw BackendAuthException("No app token available")
        }

        try {
            return backend.generate(token, model, contents, prompt)
        } catch (e: BackendHttpException) {
            if (e.statusCode != 401) throw e
            // Token rejected; clear and retry once with a fresh token.
            clearAppToken()
            val refreshed = ensureValidAppToken()
                ?: throw BackendAuthException("Unable to refresh app token")
            return backend.generate(refreshed, model, contents, prompt)
        }
    }

    /**
     * Ensures we have a valid app token. Returns the token or null if unable to obtain one.
     * This will exchange a Google ID token for an app token if needed.
     */
    suspend fun ensureValidAppToken(): String? {
        val appToken = getAppToken()
        if (!appToken.isNullOrEmpty() && !isAppTokenExpired()) {
            return appToken
        }

        // App token is missing or expired, need to exchange
        return exchangeForAppToken()
    }

    private suspend fun exchangeForAppToken(): String? {
        // Get Google ID token (or trigger sign-in)
        var googleIdToken = getGoogleIdToken()
        if (googleIdToken.isNullOrEmpty()) {
            googleIdToken = signIn() ?: return null
        }

        // Exchange Google ID token for app token
        return try {
            withContext(Dispatchers.IO) {
                val response = BackendClient.exchangeToken(googleIdToken)
                val expiresAtMs = BackendClient.parseExpiresAt(response.expiresAt)
                saveAppToken(response.token, expiresAtMs)
                response.token
            }
        } catch (e: BackendHttpException) {
            if (e.statusCode == 401) {
                // Google ID token expired, try signing in again
                val freshGoogleIdToken = signIn() ?: return null
                try {
                    withContext(Dispatchers.IO) {
                        val response = BackendClient.exchangeToken(freshGoogleIdToken)
                        val expiresAtMs = BackendClient.parseExpiresAt(response.expiresAt)
                        saveAppToken(response.token, expiresAtMs)
                        response.token
                    }
                } catch (e2: Exception) {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}




