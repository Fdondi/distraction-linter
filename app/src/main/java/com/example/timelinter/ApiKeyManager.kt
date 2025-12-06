package com.example.timelinter

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ApiKeyManager {

    private const val PREFERENCE_FILE_KEY = "com.example.timelinter.encrypted_prefs"
    private const val API_KEY_PREF = "gemini_api_key"
    private const val GOOGLE_ID_TOKEN_PREF = "google_id_token"
    private const val HEADS_UP_INFO_SHOWN_PREF = "heads_up_info_shown"
    private const val USER_NOTES_PREF = "user_notes"
    private const val COACH_NAME_PREF = "coach_name"
    private const val FIRST_BOOT_TUTORIAL_SHOWN_PREF = "first_boot_tutorial_shown"
    private const val TAG = "ApiKeyManager"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "com.example.timelinter.master_key"
    private const val AES_MODE = "AES/GCM/NoPadding"
    private const val GCM_TAG_SIZE = 128

    fun saveKey(context: Context, apiKey: String) {
        if (apiKey.isBlank()) {
            Log.w(TAG, "Attempted to save a blank API key.")
            return
        }
        putEncryptedString(context, API_KEY_PREF, apiKey)
        Log.i(TAG, "API Key saved successfully.")
    }

    fun getKey(context: Context): String? {
        val key = getEncryptedString(context, API_KEY_PREF, null)
        Log.d(TAG, "API Key loaded: ${if (key.isNullOrEmpty()) "Not Found" else "Found"}")
        return key
    }

    fun saveGoogleIdToken(context: Context, token: String) {
        if (token.isBlank()) return
        putEncryptedString(context, GOOGLE_ID_TOKEN_PREF, token)
        Log.i(TAG, "Google ID Token saved successfully.")
    }

    fun getGoogleIdToken(context: Context): String? {
        return getEncryptedString(context, GOOGLE_ID_TOKEN_PREF, null)
    }

    fun hasKey(context: Context): Boolean {
        return !getKey(context).isNullOrEmpty()
    }

    fun clearKey(context: Context) {
        getPreferences(context).edit().remove(API_KEY_PREF).apply()
        Log.i(TAG, "API Key cleared.")
    }

    fun setHeadsUpInfoShown(context: Context) {
        getPreferences(context).edit().putBoolean(HEADS_UP_INFO_SHOWN_PREF, true).apply()
        Log.i(TAG, "Heads-up info shown flag set.")
    }

    fun hasHeadsUpInfoBeenShown(context: Context): Boolean {
        val shown = getPreferences(context).getBoolean(HEADS_UP_INFO_SHOWN_PREF, false)
        Log.d(TAG, "Heads-up info shown flag checked: $shown")
        return shown
    }

    fun saveUserNotes(context: Context, notes: String) {
        putEncryptedString(context, USER_NOTES_PREF, notes)
        Log.i(TAG, "User notes saved successfully.")
    }

    fun getUserNotes(context: Context): String {
        val notes = getEncryptedString(context, USER_NOTES_PREF, "") ?: ""
        Log.d(TAG, "User notes loaded: ${if (notes.isEmpty()) "Empty" else "Found (${notes.length} chars)"}")
        return notes
    }

    fun hasUserNotes(context: Context): Boolean {
        return getUserNotes(context).isNotEmpty()
    }

    fun clearUserNotes(context: Context) {
        getPreferences(context).edit().remove(USER_NOTES_PREF).apply()
        Log.i(TAG, "User notes cleared.")
    }

    fun saveCoachName(context: Context, name: String) {
        putEncryptedString(context, COACH_NAME_PREF, name)
        Log.i(TAG, "Coach name saved successfully: $name")
    }

    fun getCoachName(context: Context): String {
        val name = getEncryptedString(context, COACH_NAME_PREF, "Adam") ?: "Adam"
        Log.d(TAG, "Coach name loaded: $name")
        return name
    }

    fun hasCoachName(context: Context): Boolean {
        return !getEncryptedString(context, COACH_NAME_PREF, null).isNullOrEmpty()
    }

    fun clearCoachName(context: Context) {
        getPreferences(context).edit().remove(COACH_NAME_PREF).apply()
        Log.i(TAG, "Coach name cleared.")
    }

    fun setFirstBootTutorialShown(context: Context) {
        getPreferences(context).edit().putBoolean(FIRST_BOOT_TUTORIAL_SHOWN_PREF, true).apply()
        Log.i(TAG, "First boot tutorial shown flag set.")
    }

    fun hasFirstBootTutorialBeenShown(context: Context): Boolean {
        val shown = getPreferences(context).getBoolean(FIRST_BOOT_TUTORIAL_SHOWN_PREF, false)
        Log.d(TAG, "First boot tutorial shown flag checked: $shown")
        return shown
    }

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFERENCE_FILE_KEY, Context.MODE_PRIVATE)
    }

    private fun putEncryptedString(context: Context, key: String, value: String) {
        val encrypted = encrypt(value)
        if (encrypted == null) {
            Log.e(TAG, "Failed to encrypt value for $key; value not saved.")
            return
        }
        getPreferences(context).edit().putString(key, encrypted).apply()
    }

    private fun getEncryptedString(
        context: Context,
        key: String,
        defaultValue: String?
    ): String? {
        val stored = getPreferences(context).getString(key, null) ?: return defaultValue
        val decrypted = decrypt(stored)
        if (decrypted == null) {
            Log.e(TAG, "Failed to decrypt value for $key; using default.")
        }
        return decrypted ?: defaultValue
    }

    private fun encrypt(value: String): String? {
        return try {
            val cipher = Cipher.getInstance(AES_MODE)
            val secretKey = getOrCreateSecretKey() ?: return null
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val byteBuffer = ByteBuffer.allocate(4 + iv.size + encryptedBytes.size)
            byteBuffer.putInt(iv.size)
            byteBuffer.put(iv)
            byteBuffer.put(encryptedBytes)
            Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            null
        }
    }

    private fun decrypt(storedValue: String?): String? {
        if (storedValue.isNullOrBlank()) return null
        return try {
            val data = Base64.decode(storedValue, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(data)
            val ivSize = buffer.int
            if (ivSize <= 0 || ivSize > buffer.remaining()) return null
            val iv = ByteArray(ivSize)
            buffer.get(iv)
            val cipherText = ByteArray(buffer.remaining())
            buffer.get(cipherText)

            val cipher = Cipher.getInstance(AES_MODE)
            val secretKey = getOrCreateSecretKey() ?: return null
            val spec = GCMParameterSpec(GCM_TAG_SIZE, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            null
        }
    }

    private fun getOrCreateSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val parameterSpec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(parameterSpec)
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to obtain secret key from Android Keystore", e)
            null
        }
    }
}