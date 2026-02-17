package com.timelinter.app

import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.TextPart

/**
 * Converts a Gemini SDK List<Content> conversation history into a plain text prompt
 * formatted with Gemma's chat template markers (<start_of_turn>/<end_of_turn>).
 *
 * Used for on-device inference where the model expects a single text prompt
 * rather than structured Content objects.
 *
 * Also adapts tool-calling instructions from structured function calls
 * to text-based format, since on-device models don't support native function calling.
 */
object ContentToTextConverter {

    private const val CLOUD_TOOL_INSTRUCTION = "When needed, CALL FUNCTIONS (no textual commands):"
    private const val ON_DEVICE_TOOL_INSTRUCTION = "When needed, write tool calls as text in your response:"

    internal const val BREVITY_INSTRUCTION =
        "\n[IMPORTANT: Keep your response to 1-2 short sentences maximum. Be concise.]"

    /**
     * Convert conversation history to Gemma chat-template formatted text.
     *
     * A brevity instruction is appended to the last user turn so the
     * on-device model keeps its output short (fits notifications, saves GPU).
     *
     * Output format:
     * ```
     * <start_of_turn>user
     * [message]<end_of_turn>
     * <start_of_turn>model
     * [message]<end_of_turn>
     * ...
     * <start_of_turn>model
     * ```
     * The trailing `<start_of_turn>model\n` prompts the model to generate its response.
     */
    fun convert(history: List<Content>): String {
        val sb = StringBuilder()

        val lastUserIndex = history.indexOfLast { (it.role ?: "user") == "user" }

        for ((index, content) in history.withIndex()) {
            val role = content.role ?: "user"
            val text = content.parts
                .filterIsInstance<TextPart>()
                .joinToString("\n") { it.text }

            // Adapt tool instructions for text-based calling
            var adaptedText = text.replace(CLOUD_TOOL_INSTRUCTION, ON_DEVICE_TOOL_INSTRUCTION)

            // Append brevity instruction to the last user turn
            if (index == lastUserIndex) {
                adaptedText += BREVITY_INSTRUCTION
            }

            sb.append("<start_of_turn>")
            sb.append(role)
            sb.append('\n')
            sb.append(adaptedText)
            sb.append("<end_of_turn>\n")
        }

        // Prompt the model to generate a response
        sb.append("<start_of_turn>model\n")

        return sb.toString()
    }
}
