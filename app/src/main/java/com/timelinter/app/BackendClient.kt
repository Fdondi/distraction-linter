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
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object BackendClient {
    private const val BASE_URL = "https://my-gemini-backend-834588824353.europe-west1.run.app"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    
    // Increased timeouts to handle cold server starts
    // Connect timeout: 30s (time to establish connection, including cold start)
    // Read timeout: 90s (time to read response, including AI generation)
    // Write timeout: 30s (time to send request)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class GenerateRequest(
        val model: String = "default",
        val prompt: String? = null, // legacy compat
        val contents: List<BackendContent> = emptyList(),
    )

    @Serializable
    data class FunctionCall(
        val name: String,
        val args: Map<String, JsonElement> = emptyMap()
    )

    @Serializable
    data class GenerateResponse(
        val result: String? = null,  // null when response only contains function calls, no text
        val function_calls: List<FunctionCall> = emptyList()
    )

    @Serializable
    data class BackendPart(val text: String)

    @Serializable
    data class BackendContent(
        val role: String,
        val parts: List<BackendPart>
    )

    @Serializable
    data class AuthStatusResponse(val status: String)

    @Serializable
    data class ExchangeTokenResponse(
        val token: String,
        val expiresAt: String
    )

    @Throws(IOException::class, Exception::class)
    fun generate(
        token: String,
        model: String,
        contents: List<BackendContent>,
        prompt: String? = null,
    ): GenerateResponse {
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
            return generateResponse
        }
    }

    @Throws(IOException::class, Exception::class)
    fun checkAuthStatus(token: String) {
        val request = Request.Builder()
            .url("$BASE_URL/api/auth/status")
            .addHeader("Authorization", "Bearer $token")
            .get()
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
            // Ensure we can parse the response to confirm shape; ignore content otherwise.
            if (responseBody != null) {
                json.decodeFromString(AuthStatusResponse.serializer(), responseBody)
            }
        }
    }

    @Throws(IOException::class, Exception::class)
    fun exchangeToken(googleIdToken: String): ExchangeTokenResponse {
        val request = Request.Builder()
            .url("$BASE_URL/api/auth/exchange")
            .addHeader("Authorization", "Bearer $googleIdToken")
            .post("".toRequestBody(JSON_MEDIA_TYPE))
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
            return json.decodeFromString(ExchangeTokenResponse.serializer(), bodyString)
        }
    }

    /**
     * Parses an ISO 8601 date string to milliseconds since epoch.
     */
    fun parseExpiresAt(isoString: String): Long {
        return try {
            Instant.parse(isoString).toEpochMilli()
        } catch (e: Exception) {
            // Fallback: if parsing fails, assume 30 days from now
            System.currentTimeMillis() + (30L * 24 * 60 * 60 * 1000)
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

