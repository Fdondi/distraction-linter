package com.timelinter.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackendAuthHelperTest {

    private var storedAppToken: String? = "old-token"
    private var storedGoogleIdToken: String? = null
    private var signInCalls = 0
    private var saveTokenCalls = 0
    private val backendTokens = mutableListOf<String>()

    @Before
    fun setUp() {
        storedAppToken = "old-token"
        storedGoogleIdToken = null
        signInCalls = 0
        saveTokenCalls = 0
        backendTokens.clear()
    }

    @Test
    fun usesStoredTokenWhenPresent() = runBlocking {
        val helper = createHelper()

        helper.generateWithAutoRefresh(model = "model", contents = emptyList())

        assertEquals(listOf("old-token"), backendTokens)
        assertEquals("old-token", storedAppToken)
        assertEquals(0, signInCalls)
        assertEquals(0, saveTokenCalls)
    }

    @Test
    fun refreshesWhenNoToken() = runBlocking {
        // No token
        storedAppToken = null

        val helper = createHelper()

        helper.generateWithAutoRefresh(model = "model", contents = emptyList())

        assertEquals(listOf("fresh-token"), backendTokens)
        assertEquals("fresh-token", storedAppToken)
        assertEquals(1, signInCalls)
        assertEquals(1, saveTokenCalls)
    }


    private fun createHelper(signInResult: String? = "fresh-token"): BackendAuthHelper {
        return BackendAuthHelper(
            signIn = {
                signInCalls++
                signInResult
            },
            getAppToken = { storedAppToken },
            saveAppToken = { token, expiresAtMs ->
                saveTokenCalls++
                storedAppToken = token
                // Note: expiresAtMs is saved but not checked locally - backend is authority
            },
            clearAppToken = { storedAppToken = null },
            getGoogleIdToken = { storedGoogleIdToken },
            backend = object : BackendGateway {
                override suspend fun generate(
                    token: String,
                    model: String,
                    contents: List<BackendClient.BackendContent>,
                    prompt: String?
                ): BackendClient.GenerateResponse {
                    backendTokens.add(token)
                    return BackendClient.GenerateResponse(result = "ok", function_calls = emptyList())
                }
            },
            checkAuthStatus = { token ->
                // Simulate backend check - always succeeds for tests
            }
        )
    }
}