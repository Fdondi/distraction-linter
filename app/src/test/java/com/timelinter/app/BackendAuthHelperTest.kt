package com.timelinter.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackendAuthHelperTest {

    @Test
    fun refreshesTokenOn401AndRetriesOnce() = runBlocking {
        var storedToken: String? = "expired"
        var signInCalls = 0

        val gateway = object : BackendGateway {
            val calls = mutableListOf<String>()
            override suspend fun generate(
                token: String,
                model: String,
                contents: List<BackendClient.BackendContent>,
                prompt: String?
            ): String {
                calls += token
                return if (token == "expired") {
                    throw BackendHttpException(401, "Invalid ID token")
                } else {
                    "OK"
                }
            }
        }

        val helper = BackendAuthHelper(
            signIn = {
                signInCalls += 1
                "fresh-token"
            },
            getStoredToken = { storedToken },
            saveToken = { storedToken = it },
            clearToken = { storedToken = null },
            backend = gateway
        )

        val result = helper.generateWithAutoRefresh(model = "m", contents = emptyList())

        assertEquals("OK", result)
        assertEquals(listOf("expired", "fresh-token"), gateway.calls)
        assertEquals("fresh-token", storedToken)
        assertEquals(1, signInCalls)
    }

    @Test
    fun usesExistingTokenWhenValid() = runBlocking {
        var storedToken: String? = "valid"
        var signInCalls = 0

        val gateway = object : BackendGateway {
            val calls = mutableListOf<String>()
            override suspend fun generate(
                token: String,
                model: String,
                contents: List<BackendClient.BackendContent>,
                prompt: String?
            ): String {
                calls += token
                return "OK"
            }
        }

        val helper = BackendAuthHelper(
            signIn = {
                signInCalls += 1
                "new-token"
            },
            getStoredToken = { storedToken },
            saveToken = { storedToken = it },
            clearToken = { storedToken = null },
            backend = gateway
        )

        val result = helper.generateWithAutoRefresh(model = "m", contents = emptyList())

        assertEquals("OK", result)
        assertEquals(listOf("valid"), gateway.calls)
        assertEquals("valid", storedToken)
        assertEquals(0, signInCalls)
    }

    @Test
    fun failsWhenRefreshReturnsNull() = runBlocking {
        var storedToken: String? = "expired"

        val helper = BackendAuthHelper(
            signIn = { null },
            getStoredToken = { storedToken },
            saveToken = { storedToken = it },
            clearToken = { storedToken = null },
            backend = object : BackendGateway {
                override suspend fun generate(token: String, prompt: String, model: String): String {
                    throw BackendHttpException(401, "Invalid ID token")
                }
            }
        )

        try {
            helper.generateWithAutoRefresh(model = "m", contents = emptyList())
            fail("Expected BackendAuthException to be thrown")
        } catch (e: BackendAuthException) {
            assertTrue(e.message!!.contains("Unable to refresh"))
        }
    }
}




