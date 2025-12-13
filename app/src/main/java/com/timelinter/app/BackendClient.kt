package com.timelinter.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

object BackendClient {
    private const val BASE_URL = "https://my-gemini-backend-834588824353.europe-west1.run.app"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class GenerateRequest(
        val model: String = "default",
        val prompt: String? = null, // legacy compat
        val contents: List<BackendContent> = emptyList(),
    )

    @Serializable
    data class GenerateResponse(
        val result: String
    )

    @Serializable
    data class BackendPart(val text: String)

    @Serializable
    data class BackendContent(
        val role: String,
        val parts: List<BackendPart>
    )

    @Throws(IOException::class, Exception::class)
    fun generate(
        token: String,
        model: String,
        contents: List<BackendContent>,
        prompt: String? = null,
    ): String {
        val safeContents = if (contents.isEmpty() && prompt != null) {
            listOf(BackendContent(role = "user", parts = listOf(BackendPart(text = prompt))))
        } else {
            contents
        }
        val requestBody = GenerateRequest(model = model, prompt = prompt, contents = safeContents)
        val jsonBody = json.encodeToString(GenerateRequest.serializer(), requestBody)
        
        val request = Request.Builder()
            .url("$BASE_URL/api/generate")
            .addHeader("Authorization", "Bearer $token")
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            if (!response.isSuccessful) {
                val parsed = parseError(responseBody)
                val errorMessage = parsed.message ?: (responseBody ?: "Empty response body")
                throw BackendHttpException(
                    statusCode = response.code,
                    message = errorMessage,
                    code = parsed.code
                )
            }

            val bodyString = responseBody ?: throw IOException("Empty response body")
            val generateResponse = json.decodeFromString(GenerateResponse.serializer(), bodyString)
            return generateResponse.result
        }
    }

    private data class ParsedError(val code: String?, val message: String?)

    private fun parseError(body: String?): ParsedError {
        if (body.isNullOrBlank()) return ParsedError(code = null, message = null)

        return try {
            val element: JsonElement = json.parseToJsonElement(body)
            val root = element.jsonObject
            val detail = root["detail"] ?: return ParsedError(code = null, message = null)
            if (detail is JsonPrimitive) {
                return ParsedError(code = null, message = detail.contentOrNull)
            }
            if (detail is JsonObject) {
                val code = detail["code"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                val message = detail["message"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                    ?: detail["detail"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                return ParsedError(code = code, message = message)
            }
            ParsedError(code = null, message = null)
        } catch (e: Exception) {
            ParsedError(code = null, message = null)
        }
    }
}

