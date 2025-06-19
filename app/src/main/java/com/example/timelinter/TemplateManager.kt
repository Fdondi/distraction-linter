package com.example.timelinter

import android.content.Context
import android.util.Log
import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.Template
import com.github.jknack.handlebars.io.StringTemplateSource
import java.util.Date

/**
 * Template manager using Handlebars.java for proper template processing
 * instead of primitive string replace operations
 */
class TemplateManager(private val context: Context) {
    private val TAG = "TemplateManager"
    private val handlebars = Handlebars()
    
    // Compiled templates (cached for performance)
    private var userInteractionTemplate: Template? = null
    private var aiMemoryTemplate: Template? = null
    private var userInfoTemplate: Template? = null
    
    init {
        try {
            // Load and compile templates once
            userInteractionTemplate = compileTemplate(
                readRawResource(R.raw.gemini_user_template),
                "user_interaction"
            )
            aiMemoryTemplate = compileTemplate(
                readRawResource(R.raw.ai_memory_template),
                "ai_memory"
            )
            userInfoTemplate = compileTemplate(
                readRawResource(R.raw.user_info_template),
                "user_info"
            )
            Log.d(TAG, "Templates compiled successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error compiling templates", e)
        }
    }
    
    private fun compileTemplate(templateContent: String, templateName: String): Template {
        val source = StringTemplateSource(templateName, templateContent)
        return handlebars.compile(source)
    }
    
    private fun readRawResource(resourceId: Int): String {
        return try {
            context.resources.openRawResource(resourceId).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading raw resource $resourceId", e)
            ""
        }
    }
    
    /**
     * Render user interaction template with context data
     */
    fun renderUserInteraction(
        currentTimeAndDate: String = Date().toString(),
        appName: String,
        sessionTime: String,
        dailyTime: String,
        userMessage: String
    ): String {
        return try {
            val context = mapOf(
                "CURRENT_TIME_AND_DATE" to currentTimeAndDate,
                "APP_NAME" to appName,
                "SESSION_TIME" to sessionTime,
                "DAILY_TIME" to dailyTime,
                "USER_MESSAGE" to userMessage
            )
            userInteractionTemplate?.apply(context) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering user interaction template", e)
            "Error rendering template: ${e.message}"
        }
    }
    
    /**
     * Render AI memory template with memory content
     */
    fun renderAIMemory(aiMemory: String): String {
        return try {
            val context = mapOf("AI_MEMORY" to aiMemory)
            aiMemoryTemplate?.apply(context) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering AI memory template", e)
            "Error rendering template: ${e.message}"
        }
    }
    
    /**
     * Render user info template with user data
     */
    fun renderUserInfo(
        fixedUserPrompt: String,
        currentUserPrompt: String,
        automatedData: String,
        userMessage: String
    ): String {
        return try {
            val context = mapOf(
                "FIXED_USER_PROMPT" to fixedUserPrompt,
                "CURRENT_USER_PROMPT" to currentUserPrompt,
                "AUTOMATED_DATA" to automatedData,
                "USER_MESSAGE" to userMessage
            )
            userInfoTemplate?.apply(context) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering user info template", e)
            "Error rendering template: ${e.message}"
        }
    }
    
    /**
     * Render any template string with given context
     */
    fun renderTemplate(templateContent: String, context: Map<String, Any>): String {
        return try {
            val template = compileTemplate(templateContent, "dynamic_${System.currentTimeMillis()}")
            template.apply(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering dynamic template", e)
            "Error rendering template: ${e.message}"
        }
    }
    
    /**
     * Helper method to format duration consistently
     */
    fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> "$hours h ${minutes % 60} min"
            minutes > 0 -> "$minutes min ${seconds % 60} s"
            else -> "$seconds s"
        }
    }
} 