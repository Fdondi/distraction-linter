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
    private val getLastRefreshTimeMs: () -> Long? = { null },
    private val saveLastRefreshTimeMs: (Long) -> Unit = {},
    private val timeProviderMs: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val AUTO_REFRESH_INTERVAL_MS: Long = 30L * 24 * 60 * 60 * 1000 // 30 days
    }

    suspend fun generateWithAutoRefresh(
        model: String,
        contents: List<BackendClient.BackendContent>,
        prompt: String? = null,
    ): String {
        var token = getStoredToken()
        val now = timeProviderMs()

        val shouldProactivelyRefresh = shouldRefresh(now, token)
        if (token.isNullOrEmpty()) {
            token =
                obtainFreshToken(now) ?: throw BackendAuthException("No Google ID token available")
        } else if (shouldProactivelyRefresh) {
            val refreshed = obtainFreshToken(now)
            if (refreshed != null) {
                token = refreshed
            }
        }

        try {
            return backend.generate(token, model, contents, prompt)
        } catch (e: BackendHttpException) {
            if (e.statusCode != 401) throw e
            // Token rejected; clear and retry once with a fresh token.
            clearToken()
            val refreshed = obtainFreshToken(now)
                ?: throw BackendAuthException("Unable to refresh Google ID token")
            return backend.generate(refreshed, model, contents, prompt)
        }
    }

    private fun shouldRefresh(now: Long, token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val lastRefresh = getLastRefreshTimeMs() ?: return true
        return now - lastRefresh >= AUTO_REFRESH_INTERVAL_MS
    }

    private suspend fun obtainFreshToken(now: Long = timeProviderMs()): String? {
        val newToken = signIn() ?: return null
        saveToken(newToken)
        saveLastRefreshTimeMs(now)
        return newToken
    }
}




