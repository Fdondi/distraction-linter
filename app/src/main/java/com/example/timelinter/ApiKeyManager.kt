package com.example.timelinter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object ApiKeyManager {

    private const val PREFERENCE_FILE_KEY = "com.example.timelinter.encrypted_prefs"
    private const val API_KEY_PREF = "gemini_api_key"
    private const val HEADS_UP_INFO_SHOWN_PREF = "heads_up_info_shown"
    private const val USER_NOTES_PREF = "user_notes"
    private const val COACH_NAME_PREF = "coach_name"
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

    fun setHeadsUpInfoShown(context: Context) {
        getEncryptedPreferences(context)?.edit()?.putBoolean(HEADS_UP_INFO_SHOWN_PREF, true)?.apply()
        Log.i(TAG, "Heads-up info shown flag set.")
    }

    fun hasHeadsUpInfoBeenShown(context: Context): Boolean {
        val shown = getEncryptedPreferences(context)?.getBoolean(HEADS_UP_INFO_SHOWN_PREF, false) ?: false
        Log.d(TAG, "Heads-up info shown flag checked: $shown")
        return shown
    }

    fun saveUserNotes(context: Context, notes: String) {
        getEncryptedPreferences(context)?.edit()?.putString(USER_NOTES_PREF, notes)?.apply()
        Log.i(TAG, "User notes saved successfully.")
    }

    fun getUserNotes(context: Context): String {
        val notes = getEncryptedPreferences(context)?.getString(USER_NOTES_PREF, "") ?: ""
        Log.d(TAG, "User notes loaded: ${if (notes.isEmpty()) "Empty" else "Found (${notes.length} chars)"}")
        return notes
    }

    fun hasUserNotes(context: Context): Boolean {
        return getUserNotes(context).isNotEmpty()
    }

    fun clearUserNotes(context: Context) {
        getEncryptedPreferences(context)?.edit()?.remove(USER_NOTES_PREF)?.apply()
        Log.i(TAG, "User notes cleared.")
    }

    fun saveCoachName(context: Context, name: String) {
        getEncryptedPreferences(context)?.edit()?.putString(COACH_NAME_PREF, name)?.apply()
        Log.i(TAG, "Coach name saved successfully: $name")
    }

    fun getCoachName(context: Context): String {
        val name = getEncryptedPreferences(context)?.getString(COACH_NAME_PREF, "Adam") ?: "Adam"
        Log.d(TAG, "Coach name loaded: $name")
        return name
    }

    fun hasCoachName(context: Context): Boolean {
        val name = getEncryptedPreferences(context)?.getString(COACH_NAME_PREF, null)
        return !name.isNullOrEmpty()
    }

    fun clearCoachName(context: Context) {
        getEncryptedPreferences(context)?.edit()?.remove(COACH_NAME_PREF)?.apply()
        Log.i(TAG, "Coach name cleared.")
    }

    private const val FIRST_BOOT_TUTORIAL_SHOWN_PREF = "first_boot_tutorial_shown"

    fun setFirstBootTutorialShown(context: Context) {
        getEncryptedPreferences(context)?.edit()?.putBoolean(FIRST_BOOT_TUTORIAL_SHOWN_PREF, true)?.apply()
        Log.i(TAG, "First boot tutorial shown flag set.")
    }

    fun hasFirstBootTutorialBeenShown(context: Context): Boolean {
        val shown = getEncryptedPreferences(context)?.getBoolean(FIRST_BOOT_TUTORIAL_SHOWN_PREF, false) ?: false
        Log.d(TAG, "First boot tutorial shown flag checked: $shown")
        return shown
    }
} 