package com.nexus.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode(val label: String) {
    SYSTEM("Как в системе"),
    LIGHT("Светлая"),
    DARK("Тёмная"),
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 0 — хранить без ограничения. */
    val retentionDays: Int = 0,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "nexus_settings")

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val themeKey = stringPreferencesKey("theme_mode")
    private val retentionKey = intPreferencesKey("retention_days")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            themeMode = ThemeMode.entries.firstOrNull { it.name == prefs[themeKey] } ?: ThemeMode.SYSTEM,
            retentionDays = prefs[retentionKey] ?: 0,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun setRetentionDays(days: Int) {
        context.settingsDataStore.edit { it[retentionKey] = days }
    }
}
