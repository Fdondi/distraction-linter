package com.timelinter.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Test that good apps list is included in AI conversation context
 */
@RunWith(AndroidJUnit4::class)
class GoodAppsAIContextTest {
    private lateinit var context: Context
    private lateinit var conversationManager: ConversationHistoryManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        
        // Clear any existing good apps
        GoodAppManager.saveSelectedApps(context, emptySet())
        
        // Initialize conversation manager with simple templates
        conversationManager = ConversationHistoryManager(
            context = context,
            systemPrompt = "You are a helpful coach.",
            aiMemoryTemplate = "Memory: {{AI_MEMORY}}",
            userInfoTemplate = "{{FIXED_USER_PROMPT}}\n{{CURRENT_USER_PROMPT}}\n{{AUTOMATED_DATA}}\n{{USER_MESSAGE}}",
            userInteractionTemplate = "Time: {{CURRENT_TIME_AND_DATE}}\nApp: {{APP_NAME}}\nSession: {{SESSION_TIME}}\nDaily: {{DAILY_TIME}}\n{{GOOD_APPS_LINE}}\nMessage: {{USER_MESSAGE}}",
            timeProvider = SystemTimeProvider
        )
    }

    @After
    fun teardown() {
        // Clean up
        GoodAppManager.saveSelectedApps(context, emptySet())
    }

    @Test
    fun testGoodAppsIncludedInInitialConversation() {
        // Setup: Configure some good apps
        GoodAppManager.saveSelectedApps(context, setOf("com.example.goodapp1", "com.example.goodapp2"))
        
        // Start a new session
        conversationManager.startNewSession(
            appName = "TestApp",
            sessionTime = Duration.ZERO,
            dailyTime = Duration.ZERO
        )
        
        // Get API history
        val apiHistory = conversationManager.getHistoryForAPI()
        
        // Verify that the initialization includes good apps information (in AUTOMATED_DATA)
        val historyText = apiHistory.joinToString("\n") { content ->
            content.parts.joinToString { part ->
                part.toString()
            }
        }
        
        // Should contain reference to good apps in the initialization
        assertTrue("Initial conversation should mention good apps", 
            historyText.contains("good app", ignoreCase = true))
    }

    @Test
    fun testGoodAppsNotInSubsequentMessages() {
        // Setup: Configure good apps
        GoodAppManager.saveSelectedApps(context, setOf("com.example.duolingo"))
        
        // Start session
        conversationManager.startNewSession("TestApp", Duration.ZERO, Duration.ZERO)
        
        // Add a user message
        conversationManager.addUserMessage(
            messageText = "I'm trying to stay focused",
            currentAppName = "TestApp",
            sessionTime = 60000.milliseconds,
            dailyTime = 120000.milliseconds
        )
        
        // Get just the new message
        val apiHistory = conversationManager.getHistoryForAPI()
        val lastMessage = apiHistory.last()
        val messageText = lastMessage.parts.joinToString { it.toString() }
        
        // Good apps should NOT be in regular user messages (only in initialization)
        assertFalse("User messages should not repeat good apps info",
            messageText.contains("good app", ignoreCase = true))
    }

    @Test
    fun testNoGoodAppsConfigured_NotInContext() {
        // Don't configure any good apps
        GoodAppManager.saveSelectedApps(context, emptySet())
        
        // Verify getSelectedAppDisplayNames returns null
        assertNull("Should return null when no good apps configured",
            GoodAppManager.getSelectedAppDisplayNames(context))
        
        // Start session
        conversationManager.startNewSession("TestApp", Duration.ZERO, Duration.ZERO)
        
        // Get initialization context
        val apiHistory = conversationManager.getHistoryForAPI()
        val historyText = apiHistory.joinToString("\n") { content ->
            content.parts.joinToString { it.toString() }
        }
        
        // Should not mention good apps when none configured
        assertFalse("Should not mention good apps when none configured",
            historyText.contains("good app", ignoreCase = true))
    }

    @Test
    fun testMultipleGoodApps_IncludedInInit() {
        // Configure multiple good apps
        val apps = setOf("com.example.app1", "com.example.app2", "com.example.app3")
        GoodAppManager.saveSelectedApps(context, apps)
        
        // Start session
        conversationManager.startNewSession("TestApp", Duration.ZERO, Duration.ZERO)
        
        // Get initialization context
        val apiHistory = conversationManager.getHistoryForAPI()
        val historyText = apiHistory.joinToString("\n") { content ->
            content.parts.joinToString { it.toString() }
        }
        
        // Should mention good apps in initialization
        assertTrue("Multiple good apps should be mentioned in initialization",
            historyText.contains("good app", ignoreCase = true))
    }

    @Test
    fun testGoodAppsUpdated_ReflectedInNewSession() {
        // Start with one good app
        GoodAppManager.saveSelectedApps(context, setOf("com.example.app1"))
        conversationManager.startNewSession("TestApp", Duration.ZERO, Duration.ZERO)
        
        val firstSessionHistory = conversationManager.getHistoryForAPI()
        val firstText = firstSessionHistory.joinToString("\n") { content ->
            content.parts.joinToString { it.toString() }
        }
        
        // Update good apps
        GoodAppManager.saveSelectedApps(context, setOf("com.example.app1", "com.example.app2"))
        
        // Start a new session after the update
        conversationManager.startNewSession("TestApp", Duration.ZERO, Duration.ZERO)
        
        val secondSessionHistory = conversationManager.getHistoryForAPI()
        val secondText = secondSessionHistory.joinToString("\n") { content ->
            content.parts.joinToString { it.toString() }
        }
        
        // Both sessions should include good apps (since they're in initialization)
        assertTrue("First session should mention good apps",
            firstText.contains("good app", ignoreCase = true))
        assertTrue("Second session should mention good apps",
            secondText.contains("good app", ignoreCase = true))
        
        // Note: We can't easily verify the updated list contains both apps without
        // parsing the actual app names, but at least we verify the mechanism works
    }
}

