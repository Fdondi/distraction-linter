package com.timelinter.app

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

object AuthManager {
    private const val TAG = "AuthManager"
    // Web Client ID used for Google Sign-In; must match the backend configuration.
    private const val WEB_CLIENT_ID = "834588824353-dmcktqcifmgaovhfr0b37bdejjdq7lbn.apps.googleusercontent.com" 

    suspend fun signIn(context: Context, forceAccountPicker: Boolean = false): String? {
        val credentialManager = CredentialManager.create(context)

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(WEB_CLIENT_ID)
            // Disable auto-select when explicitly switching accounts
            .setAutoSelectEnabled(!forceAccountPicker)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val result = credentialManager.getCredential(
                request = request,
                context = context,
            )
            handleSignIn(context, result)
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Sign in failed", e)
            null
        } catch (e: Exception) {
             Log.e(TAG, "Sign in failed (generic)", e)
             null
        }
    }

    private fun handleSignIn(context: Context, result: GetCredentialResponse): String? {
        val credential = result.credential
        return when (credential) {
            is CustomCredential -> {
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    try {
                        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                        Log.d(TAG, "Signed in as: ${googleIdTokenCredential.displayName}")
                        val token = googleIdTokenCredential.idToken
                        ApiKeyManager.saveGoogleIdToken(context, token)
                        token
                    } catch (e: Exception) {
                        Log.e(TAG, "Received an invalid google id token response", e)
                        null
                    }
                } else {
                    Log.e(TAG, "Unexpected custom credential type: ${credential.type}")
                    null
                }
            }
            else -> {
                Log.e(TAG, "Unexpected credential type")
                null
            }
        }
    }
}

