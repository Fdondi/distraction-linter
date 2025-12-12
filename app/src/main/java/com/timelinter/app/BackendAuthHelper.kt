package com.timelinter.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Exception representing an HTTP failure from the backend.
 */
class BackendHttpException(val statusCode: Int, message: String) : IOException(message)

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
    ): String
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
    ): String {
        return withContext(Dispatchers.IO) {
            BackendClient.generate(token, model, contents, prompt)
        }
    }
}

/**
 * Handles retrieving, refreshing, and persisting Google ID tokens for backend calls.
 *
 * Flow:
 * - Use stored token if present.
 * - If missing, attempt sign-in to obtain one.
 * - If backend returns 401, clear token, re-sign, and retry once.
 */
class BackendAuthHelper(
    private val signIn: suspend () -> String?,
    private val getStoredToken: () -> String?,
    private val saveToken: (String) -> Unit,
    private val clearToken: () -> Unit,
    private val backend: BackendGateway,
) {
    suspend fun generateWithAutoRefresh(
        model: String,
        contents: List<BackendClient.BackendContent>,
        prompt: String? = null,
    ): String {
        var token = getStoredToken()
        if (token.isNullOrEmpty()) {
            token = obtainFreshToken() ?: throw BackendAuthException("No Google ID token available")
        }

        try {
            return backend.generate(token, model, contents, prompt)
        } catch (e: BackendHttpException) {
            if (e.statusCode != 401) throw e
            // Token rejected; clear and retry once with a fresh token.
            clearToken()
            val refreshed = obtainFreshToken()
                ?: throw BackendAuthException("Unable to refresh Google ID token")
            return backend.generate(refreshed, model, contents, prompt)
        }
    }

    private suspend fun obtainFreshToken(): String? {
        val newToken = signIn() ?: return null
        saveToken(newToken)
        return newToken
    }
}




