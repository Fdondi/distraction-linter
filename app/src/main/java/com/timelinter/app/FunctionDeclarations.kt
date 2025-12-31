package com.timelinter.app

import com.google.ai.client.generativeai.type.FunctionDeclaration
import com.google.ai.client.generativeai.type.FunctionType
import com.google.ai.client.generativeai.type.Schema
import com.google.ai.client.generativeai.type.Tool

/**
 * Defines function declarations for the Gemini API function calling feature.
 * These declarations tell the model what functions are available and how to call them.
 */
object FunctionDeclarations {
    
    /**
     * Function declaration for the "allow" function.
     * Grants additional time to the user, optionally for a specific app.
     */
    val allowFunction: FunctionDeclaration = FunctionDeclaration(
        name = "allow",
        description = "Grants additional time to the user. Use this when the user requests a break, acknowledges they need time, or when you want to give them extra time. If the user mentions a duration (e.g., 'back in 15 minutes'), use that duration. If no duration is given, choose a reasonable default (e.g., 10-20 minutes based on the activity).",
        parameters = listOf(
            Schema(
                name = "minutes",
                description = "The number of minutes to grant. Must be a positive integer.",
                type = FunctionType.INTEGER
            ),
            Schema(
                name = "app",
                description = "Optional. The name of the specific app to allow time for. If not provided, the allowance applies to all apps.",
                type = FunctionType.STRING,
                nullable = true
            )
        ),
        requiredParameters = listOf("minutes")
    )

    /**
     * Function declaration for the "remember" function.
     * Stores information in the AI's memory for future conversations.
     */
    val rememberFunction: FunctionDeclaration = FunctionDeclaration(
        name = "remember",
        description = "Stores information in the AI's memory so it can be recalled in future conversations. Use this to save important facts, preferences, or context that should persist across conversations. If minutes is provided, the memory will expire after that duration. If minutes is omitted, the memory is permanent.",
        parameters = listOf(
            Schema(
                name = "content",
                description = "The information to remember. Should be a clear, concise fact or piece of context.",
                type = FunctionType.STRING
            ),
            Schema(
                name = "minutes",
                description = "Optional. The number of minutes after which this memory should expire. If omitted, the memory is permanent.",
                type = FunctionType.INTEGER,
                nullable = true
            )
        ),
        requiredParameters = listOf("content")
    )

    /**
     * Creates a Tool object containing both function declarations.
     * This is what gets passed to the model's generateContent call for direct API mode.
     * Note: Backend mode handles function declarations internally - no need to send them from frontend.
     */
    fun createTool(): Tool {
        return Tool(
            functionDeclarations = listOf(allowFunction, rememberFunction)
        )
    }
}
