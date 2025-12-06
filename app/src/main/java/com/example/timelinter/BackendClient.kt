package com.example.timelinter

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

object BackendClient {
    // Placeholder URL - specific to Android Emulator accessing host localhost
    private const val BASE_URL = "http://10.0.2.2:8000" 
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class GenerateRequest(
        val prompt: String,
        val model: String
    )

    @Serializable
    data class GenerateResponse(
        val result: String
    )

    @Throws(IOException::class, Exception::class)
    fun generate(token: String, prompt: String, model: String): String {
        val requestBody = GenerateRequest(prompt, model)
        val jsonBody = json.encodeToString(GenerateRequest.serializer(), requestBody)
        
        val request = Request.Builder()
            .url("$BASE_URL/api/generate")
            .addHeader("Authorization", "Bearer $token")
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected code $response")
            }
            
            val responseBody = response.body?.string() ?: throw IOException("Empty response body")
            val generateResponse = json.decodeFromString(GenerateResponse.serializer(), responseBody)
            return generateResponse.result
        }
    }
}

