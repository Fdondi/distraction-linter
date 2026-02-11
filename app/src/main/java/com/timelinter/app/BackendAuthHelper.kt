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
 * - Use stored app token if present (backend is authority on expiration).
 * - If app token is missing, get Google ID token (or trigger sign-in) and exchange for app token.
 * - Use app token for API calls.
 * - If backend returns 401, clear tokens and try to get a fresh one.
 *
 * Key principle: The backend is the authority on token expiration and enforces the monthly
 * Google sign-in requirement. The client simply renews when the backend says the token is expired.
 */
class BackendAuthHelper(
    private val signIn: suspend () -> String?,
    private val getAppToken: () -> String?,
    private val saveAppToken: (String, Long) -> Unit,
    private val clearAppToken: () -> Unit,
    private val getGoogleIdToken: () -> String?,
    private val backend: BackendGateway,
    private val checkAuthStatus: suspend (String) -> Unit,
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
            // Token rejected by backend (backend is source of truth for expiration).
            // Clear the token and try to get a fresh one.
            clearAppToken()
            val refreshed = ensureValidAppToken()
                ?: throw BackendAuthException("Unable to refresh app token")
            return backend.generate(refreshed, model, contents, prompt)
        }
    }

    /**
     * Checks token validity with the backend. Returns true if token is valid, false otherwise.
     * This is used when the app opens to verify the token without sending a message.
     */
    suspend fun checkTokenWithBackend(): Boolean {
        val token = getAppToken() ?: return false
        return try {
            checkAuthStatus(token)
            true
        } catch (e: BackendHttpException) {
            if (e.statusCode == 401) {
                // Token expired according to backend
                clearAppToken()
                false
            } else {
                // Other error, assume token is still valid (don't clear on network errors)
                true
            }
        } catch (e: Exception) {
            // Network or other error, assume token is still valid
            true
        }
    }

    /**
     * Ensures we have a valid app token. Returns the token or null if unable to obtain one.
     * This will exchange a Google ID token for an app token if needed.
     * 
     * Key principle: The backend is the authority on token expiration. We don't check locally.
     * If we have a token, we use it and let the backend decide if it's valid.
     * The backend enforces the monthly Google sign-in requirement.
     */
    suspend fun ensureValidAppToken(): String? {
        val appToken = getAppToken()
        
        // If we have a token, use it - backend will tell us if it's expired
        if (!appToken.isNullOrEmpty()) {
            return appToken
        }

        // No token at all, exchange for a new one
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




