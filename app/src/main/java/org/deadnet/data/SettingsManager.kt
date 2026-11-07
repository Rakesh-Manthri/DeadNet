package org.deadnet.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages user preferences and mesh routing configurations.
 */
class SettingsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "deadnet_settings"
        private const val KEY_NODE_NAME = "node_display_name"
        private const val KEY_TTL_HOURS = "default_ttl_hours"
        private const val KEY_TOKEN_QUOTA = "token_quota"
        private const val KEY_SCAN_PROFILE = "scan_profile"
        private const val KEY_THEME_MODE = "theme_mode"

        val DEFAULT_NODE_NAME: String by lazy {
            "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _nodeDisplayName = MutableStateFlow(
        prefs.getString(KEY_NODE_NAME, DEFAULT_NODE_NAME) ?: DEFAULT_NODE_NAME
    )
    val nodeDisplayName: StateFlow<String> = _nodeDisplayName.asStateFlow()

    private val _defaultTtlHours = MutableStateFlow(
        prefs.getInt(KEY_TTL_HOURS, 24)
    )
    val defaultTtlHours: StateFlow<Int> = _defaultTtlHours.asStateFlow()

    private val _tokenQuota = MutableStateFlow(
        prefs.getInt(KEY_TOKEN_QUOTA, 5)
    )
    val tokenQuota: StateFlow<Int> = _tokenQuota.asStateFlow()

    private val _scanProfile = MutableStateFlow(
        prefs.getString(KEY_SCAN_PROFILE, "BALANCED") ?: "BALANCED"
    )
    val scanProfile: StateFlow<String> = _scanProfile.asStateFlow()

    private val _themeMode = MutableStateFlow(
        prefs.getString(KEY_THEME_MODE, "SYSTEM") ?: "SYSTEM"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    fun setNodeDisplayName(name: String) {
        val trimmed = name.trim().ifBlank { DEFAULT_NODE_NAME }
        prefs.edit().putString(KEY_NODE_NAME, trimmed).apply()
        _nodeDisplayName.value = trimmed
    }

    fun setDefaultTtlHours(hours: Int) {
        val clamped = hours.coerceIn(1, 168)
        prefs.edit().putInt(KEY_TTL_HOURS, clamped).apply()
        _defaultTtlHours.value = clamped
    }

    fun setTokenQuota(quota: Int) {
        val clamped = quota.coerceIn(2, 10)
        prefs.edit().putInt(KEY_TOKEN_QUOTA, clamped).apply()
        _tokenQuota.value = clamped
    }

    fun setScanProfile(profile: String) {
        prefs.edit().putString(KEY_SCAN_PROFILE, profile).apply()
        _scanProfile.value = profile
    }

    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME_MODE, mode).apply()
        _themeMode.value = mode
    }

    fun resetToDefaults() {
        prefs.edit().clear().apply()
        _nodeDisplayName.value = DEFAULT_NODE_NAME
        _defaultTtlHours.value = 24
        _tokenQuota.value = 5
        _scanProfile.value = "BALANCED"
        _themeMode.value = "SYSTEM"
    }
}
