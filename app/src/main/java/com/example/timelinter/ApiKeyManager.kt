package com.example.timelinter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object ApiKeyManager {

    private const val PREFERENCE_FILE_KEY = "com.example.timelinter.encrypted_prefs"
    private const val API_KEY_PREF = "gemini_api_key"
    private const val TAG = "ApiKeyManager"

    private fun getEncryptedPreferences(context: Context): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFERENCE_FILE_KEY,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating EncryptedSharedPreferences", e)
            null
        }
    }

    fun saveKey(context: Context, apiKey: String) {
        if (apiKey.isBlank()) {
             Log.w(TAG, "Attempted to save a blank API key.")
             return
        }
        getEncryptedPreferences(context)?.edit()?.putString(API_KEY_PREF, apiKey)?.apply()
        Log.i(TAG, "API Key saved successfully.")
    }

    fun getKey(context: Context): String? {
        val key = getEncryptedPreferences(context)?.getString(API_KEY_PREF, null)
        Log.d(TAG, "API Key loaded: ${if (key.isNullOrEmpty()) "Not Found" else "Found"}")
        return key
    }

    fun hasKey(context: Context): Boolean {
        return !getKey(context).isNullOrEmpty()
    }

    fun clearKey(context: Context) {
         getEncryptedPreferences(context)?.edit()?.remove(API_KEY_PREF)?.apply()
         Log.i(TAG, "API Key cleared.")
    }
} 