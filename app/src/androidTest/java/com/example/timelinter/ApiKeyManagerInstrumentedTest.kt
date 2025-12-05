package com.example.timelinter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ApiKeyManagerInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearAll()
    }

    @After
    fun tearDown() {
        clearAll()
    }

    @Test
    fun saveAndRetrieveApiKey() {
        val apiKey = "test-api-key-123"

        ApiKeyManager.saveKey(context, apiKey)

        assertTrue(ApiKeyManager.hasKey(context))
        assertEquals(apiKey, ApiKeyManager.getKey(context))
    }

    @Test
    fun clearApiKeyRemovesValue() {
        ApiKeyManager.saveKey(context, "to-be-cleared")

        ApiKeyManager.clearKey(context)

        assertFalse(ApiKeyManager.hasKey(context))
        assertEquals(null, ApiKeyManager.getKey(context))
    }

    @Test
    fun storedApiKeyIsNotPlaintext() {
        val apiKey = "super-secret-value"

        ApiKeyManager.saveKey(context, apiKey)

        val rawPrefs = context.getSharedPreferences(
            "com.example.timelinter.encrypted_prefs",
            Context.MODE_PRIVATE
        )
        val stored = rawPrefs.getString("gemini_api_key", null)

        assertNotNull(stored)
        assertNotEquals(apiKey, stored)
    }

    private fun clearAll() {
        val rawPrefs = context.getSharedPreferences(
            "com.example.timelinter.encrypted_prefs",
            Context.MODE_PRIVATE
        )
        rawPrefs.edit().clear().commit()
    }
}



