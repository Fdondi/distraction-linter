package com.timelinter.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
                throw BackendHttpException(
                    statusCode = response.code,
                    message = responseBody ?: "Empty response body"
                )
            }

            val bodyString = responseBody ?: throw IOException("Empty response body")
            val generateResponse = json.decodeFromString(GenerateResponse.serializer(), bodyString)
            return generateResponse.result
        }
    }
}

