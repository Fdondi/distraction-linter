@file:OptIn(ExperimentalTime::class)

package com.timelinter.app

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Serializable
private data class PersistedAllow(
    val key: String,
    val appName: String?,
    val expiresAtMs: Long
)

data class TemporaryAllow(
    val appName: String?,
    val expiresAt: Instant
) {
    fun remainingDuration(now: Instant): Duration = (expiresAt - now).coerceAtLeast(ZERO)
    fun isExpired(now: Instant): Boolean = now >= expiresAt
    internal fun storageKey(): String = appName?.ifBlank { null }?.let { it } ?: TemporaryAllowStore.GLOBAL_KEY
}

object TemporaryAllowStore {
    internal const val GLOBAL_KEY = "_GLOBAL_ALLOW"
    private const val PREF_NAME = "temporary_allows"
    private const val PREF_KEY = "entries"
    private const val TAG = "TemporaryAllowStore"
    private val json = Json { ignoreUnknownKeys = true }

    private val _allows = MutableStateFlow<List<TemporaryAllow>>(emptyList())
    val allows: StateFlow<List<TemporaryAllow>> = _allows.asStateFlow()

    @Synchronized
    fun refresh(context: Context, timeProvider: TimeProvider = SystemTimeProvider) {
        val now = timeProvider.now()
        val persisted = loadPersisted(context)
        val active = persisted
            .groupBy { it.key }
            .mapNotNull { entry -> entry.value.maxByOrNull { it.expiresAtMs } }
            .filter { it.expiresAtMs > now.toEpochMilliseconds() }

        if (active.size != persisted.size) {
            save(context, active)
        }

        _allows.value = active
            .map { TemporaryAllow(it.appName, Instant.fromEpochMilliseconds(it.expiresAtMs)) }
            .sortedBy { it.expiresAt }
    }

    @Synchronized
    fun upsertAllow(
        context: Context,
        appName: String?,
        duration: Duration,
        timeProvider: TimeProvider = SystemTimeProvider
    ) {
        if (!duration.isPositive()) {
            removeAllow(context, appName)
            return
        }

        val now = timeProvider.now()
        val key = normalizeKey(appName)
        val expiresAt = now + duration
        val current = loadPersisted(context)
        val updated = current.filterNot { it.key == key } + PersistedAllow(
            key = key,
            appName = appName?.ifBlank { null },
            expiresAtMs = expiresAt.toEpochMilliseconds()
        )

        save(context, updated)

        _allows.value = updated
            .filter { it.expiresAtMs > now.toEpochMilliseconds() }
            .map { TemporaryAllow(it.appName, Instant.fromEpochMilliseconds(it.expiresAtMs)) }
            .sortedBy { it.expiresAt }

        Log.d(TAG, "Upserted temporary allow for ${appName ?: "all apps"} until $expiresAt")
    }

    @Synchronized
    fun removeAllow(context: Context, appName: String?) {
        val key = normalizeKey(appName)
        val updated = loadPersisted(context).filterNot { it.key == key }
        save(context, updated)
        _allows.value = updated
            .map { TemporaryAllow(it.appName, Instant.fromEpochMilliseconds(it.expiresAtMs)) }
            .sortedBy { it.expiresAt }
    }

    @Synchronized
    fun cleanupExpired(context: Context, timeProvider: TimeProvider = SystemTimeProvider) {
        refresh(context, timeProvider)
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit { clear() }
        _allows.value = emptyList()
    }

    fun getActiveAllows(context: Context, timeProvider: TimeProvider = SystemTimeProvider): List<TemporaryAllow> {
        refresh(context, timeProvider)
        return _allows.value
    }

    private fun loadPersisted(context: Context): List<PersistedAllow> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<PersistedAllow>>(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Could not decode persisted allows, clearing", e)
            prefs.edit { remove(PREF_KEY) }
            emptyList()
        }
    }

    private fun save(context: Context, entries: List<PersistedAllow>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val payload = json.encodeToString(entries)
        prefs.edit { putString(PREF_KEY, payload) }
    }

    private fun normalizeKey(appName: String?): String {
        return appName?.takeIf { it.isNotBlank() } ?: GLOBAL_KEY
    }
}

