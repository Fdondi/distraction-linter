package com.timelinter.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackendAuthHelperTest {

    private var storedToken: String? = "old-token"
    private var lastRefreshMs: Long? = null
    private var signInCalls = 0
    private val backendTokens = mutableListOf<String>()

    @Before
    fun setUp() {
        storedToken = "old-token"
        lastRefreshMs = null
        signInCalls = 0
        backendTokens.clear()
    }

    @Test
    fun autoRefreshesWhenTokenIsOlderThanInterval() = runBlocking {
        val now = BackendAuthHelper.AUTO_REFRESH_INTERVAL_MS + 1234L
        lastRefreshMs = 0L

        val helper = createHelper(now)

        helper.generateWithAutoRefresh(model = "model", contents = emptyList())

        assertEquals(listOf("fresh-token"), backendTokens)
        assertEquals("fresh-token", storedToken)
        assertEquals(now, lastRefreshMs)
        assertEquals(1, signInCalls)
    }

    @Test
    fun usesStoredTokenWhenRefreshIsRecent() = runBlocking {
        val now = 1000L
        lastRefreshMs = now - 1L // inside interval

        val helper = createHelper(now)

        helper.generateWithAutoRefresh(model = "model", contents = emptyList())

        assertEquals(listOf("old-token"), backendTokens)
        assertEquals("old-token", storedToken)
        assertEquals(now - 1L, lastRefreshMs)
        assertEquals(0, signInCalls)
    }

    @Test
    fun doesNotRefreshAtTwentyNineDays() = runBlocking {
        val oneDayMs = 24L * 60 * 60 * 1000
        val now = BackendAuthHelper.AUTO_REFRESH_INTERVAL_MS - oneDayMs
        lastRefreshMs = 0L

        val helper = createHelper(now)

        val result = helper.generateWithAutoRefresh(model = "model", contents = emptyList())

        assertEquals(listOf("old-token"), backendTokens)
        assertEquals("old-token", storedToken)
        assertEquals(0L, lastRefreshMs)
        assertEquals(0, signInCalls)
        assertEquals("ok", result)
    }

    private fun createHelper(now: Long): BackendAuthHelper {
        return BackendAuthHelper(
            signIn = {
                signInCalls++
                "fresh-token"
            },
            getStoredToken = { storedToken },
            saveToken = { token -> storedToken = token },
            clearToken = { storedToken = null },
            backend = object : BackendGateway {
                override suspend fun generate(
                    token: String,
                    model: String,
                    contents: List<BackendClient.BackendContent>,
                    prompt: String?
                ): String {
                    backendTokens.add(token)
                    return "ok"
                }
            },
            getLastRefreshTimeMs = { lastRefreshMs },
            saveLastRefreshTimeMs = { time -> lastRefreshMs = time },
            timeProviderMs = { now }
        )
    }
}