package io.github.asagnc.eta.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.asagnc.eta.data.model.AppearanceAccentColor
import io.github.asagnc.eta.data.model.AppearancePaletteStyle
import io.github.asagnc.eta.data.model.AppearanceSettings
import io.github.asagnc.eta.data.model.AppearanceThemeMode
import io.github.asagnc.eta.data.model.AppearanceTopBarBlurStyle
import io.github.asagnc.eta.data.model.Settings
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

internal object SettingsDataStore {
    private const val STORE_NAME = "eta_settings"

    private val SELECTED_PROVIDER_ID = stringPreferencesKey("selected_provider_id")
    private val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
    private val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
    private val LINUX_DISTRIBUTION = stringPreferencesKey("linux_distribution")
    private val APPEARANCE_THEME_MODE = stringPreferencesKey("appearance_theme_mode")
    private val APPEARANCE_MONET_ENABLED = booleanPreferencesKey("appearance_monet_enabled")
    private val APPEARANCE_PALETTE_STYLE = stringPreferencesKey("appearance_palette_style")
    private val APPEARANCE_ACCENT_COLOR = stringPreferencesKey("appearance_accent_color")
    private val APPEARANCE_PURE_BLACK_ENABLED = booleanPreferencesKey("appearance_pure_black_enabled")
    private val APPEARANCE_BLUR_ENABLED = booleanPreferencesKey("appearance_blur_enabled")
    private val APPEARANCE_TOP_BAR_BLUR_STYLE = stringPreferencesKey("appearance_top_bar_blur_style")
    private val APPEARANCE_SWIPE_DISMISS_ENABLED =
        booleanPreferencesKey("appearance_swipe_dismiss_enabled")
    private val APPEARANCE_PREDICTIVE_BACK_ENABLED =
        booleanPreferencesKey("appearance_predictive_back_enabled")
    private val APPEARANCE_INTERFACE_SCALE = floatPreferencesKey("appearance_interface_scale")
    private const val SELECTED_MODEL_BY_PROVIDER_PREFIX = "selected_model_id_by_provider."

    private val APP_LAUNCH_COUNT = intPreferencesKey("app_launch_count")
    private val RETIRED_INPUT_TOKENS = longPreferencesKey("retired_input_tokens")
    private val RETIRED_OUTPUT_TOKENS = longPreferencesKey("retired_output_tokens")
    private val RETIRED_CACHED_TOKENS = longPreferencesKey("retired_cached_tokens")
    private val RETIRED_CONVERSATIONS = intPreferencesKey("retired_conversations")
    private val RETIRED_MESSAGES = intPreferencesKey("retired_messages")
    private val RETIRED_HEATMAP_JSON = stringPreferencesKey("retired_heatmap_json")
    private val MODEL_USAGE_JSON = stringPreferencesKey("model_usage_json")

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = STORE_NAME)

    @Volatile
    private lateinit var dataStore: DataStore<Preferences>

    fun init(context: Context) {
        if (!::dataStore.isInitialized) {
            dataStore = context.applicationContext.dataStore
        }
    }

    fun settingsFlow(): Flow<Settings> {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw cause
                }
            }
            .map { preferences -> preferences.toSettings() }
    }

    suspend fun settings(): Settings = settingsFlow().first()

    suspend fun updateSettings(transform: (Settings) -> Settings) {
        ensureInitialized()
        dataStore.edit { prefs ->
            val current = prefs.toSettings()
            val updated = transform(current)
            prefs.putOrRemove(SELECTED_PROVIDER_ID, updated.selectedProviderId)
            prefs.putOrRemove(SELECTED_MODEL_ID, updated.selectedModelId)
            prefs[MEMORY_ENABLED] = updated.memoryEnabled
            prefs.putAppearance(updated.appearance.normalized())
        }
    }

    fun selectedProviderIdFlow(): Flow<String?> =
        settingsFlow().map { it.selectedProviderId }

    fun selectedModelIdFlow(): Flow<String?> =
        settingsFlow().map { it.selectedModelId }

    suspend fun selectedModelIdForProvider(providerId: String): String? {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw cause
                }
            }
            .map { prefs -> prefs[selectedModelByProviderKey(providerId)] }
            .first()
    }

    fun memoryEnabledFlow(): Flow<Boolean> =
        settingsFlow().map { it.memoryEnabled }

    fun linuxDistributionFlow(): Flow<String?> {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) emit(emptyPreferences()) else throw cause
            }
            .map { preferences -> preferences[LINUX_DISTRIBUTION] }
    }

    fun linuxBackendFlow(distribution: String): Flow<String?> {
        ensureInitialized()
        return dataStore.data.catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }.map { it[stringPreferencesKey("linux_backend.$distribution")] }
    }

    suspend fun setLinuxBackend(distribution: String, backend: String?) {
        ensureInitialized()
        dataStore.edit { preferences ->
            val key = stringPreferencesKey("linux_backend.$distribution")
            if (backend == null) preferences.remove(key) else preferences[key] = backend
        }
    }

    fun appearanceSettingsFlow(): Flow<AppearanceSettings> =
        settingsFlow().map { it.appearance }

    suspend fun setSelectedProviderId(id: String?) {
        updateSettings { it.copy(selectedProviderId = id) }
    }

    suspend fun setSelectedModelId(id: String?) {
        updateSettings { it.copy(selectedModelId = id) }
    }

    suspend fun setSelection(providerId: String?, modelId: String?) {
        ensureInitialized()
        dataStore.edit { prefs ->
            val previousProviderId = prefs[SELECTED_PROVIDER_ID]
            val previousModelId = prefs[SELECTED_MODEL_ID]
            if (previousProviderId != null && previousModelId != null) {
                prefs[selectedModelByProviderKey(previousProviderId)] = previousModelId
            }

            prefs.putOrRemove(SELECTED_PROVIDER_ID, providerId)
            prefs.putOrRemove(SELECTED_MODEL_ID, modelId)
            if (providerId != null && modelId != null) {
                prefs[selectedModelByProviderKey(providerId)] = modelId
            }
        }
    }

    suspend fun clearSelectedModelIdForProvider(providerId: String) {
        ensureInitialized()
        dataStore.edit { prefs ->
            prefs.remove(selectedModelByProviderKey(providerId))
        }
    }

    suspend fun setMemoryEnabled(enabled: Boolean) {
        updateSettings { it.copy(memoryEnabled = enabled) }
    }

    suspend fun setLinuxDistribution(value: String?) {
        ensureInitialized()
        dataStore.edit { preferences -> preferences.putOrRemove(LINUX_DISTRIBUTION, value) }
    }

    suspend fun setAppearanceSettings(settings: AppearanceSettings) {
        updateSettings { it.copy(appearance = settings.normalized()) }
    }

    suspend fun updateAppearanceSettings(transform: (AppearanceSettings) -> AppearanceSettings) {
        updateSettings { settings ->
            settings.copy(appearance = transform(settings.appearance).normalized())
        }
    }

    private fun ensureInitialized() {
        check(::dataStore.isInitialized) {
            "SettingsDataStore.init(context) must be called in Application.onCreate()"
        }
    }

    private fun selectedModelByProviderKey(providerId: String): Preferences.Key<String> =
        stringPreferencesKey("$SELECTED_MODEL_BY_PROVIDER_PREFIX$providerId")

    private fun MutablePreferences.putOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value.isNullOrBlank()) {
            remove(key)
        } else {
            this[key] = value
        }
    }

    private fun Preferences.toSettings(): Settings = Settings(
        selectedProviderId = this[SELECTED_PROVIDER_ID],
        selectedModelId = this[SELECTED_MODEL_ID],
        memoryEnabled = this[MEMORY_ENABLED] ?: true,
        appearance = AppearanceSettings(
            themeMode = AppearanceThemeMode.fromPersistedValue(this[APPEARANCE_THEME_MODE]),
            monetEnabled = this[APPEARANCE_MONET_ENABLED] ?: false,
            paletteStyle = AppearancePaletteStyle.fromPersistedValue(this[APPEARANCE_PALETTE_STYLE]),
            accentColor = AppearanceAccentColor.fromPersistedValue(this[APPEARANCE_ACCENT_COLOR]),
            pureBlackEnabled = this[APPEARANCE_PURE_BLACK_ENABLED] ?: false,
            blurEnabled = this[APPEARANCE_BLUR_ENABLED] ?: true,
            topBarBlurStyle = AppearanceTopBarBlurStyle.fromPersistedValue(
                this[APPEARANCE_TOP_BAR_BLUR_STYLE],
            ),
            swipeDismissEnabled = this[APPEARANCE_SWIPE_DISMISS_ENABLED] ?: true,
            predictiveBackEnabled = this[APPEARANCE_PREDICTIVE_BACK_ENABLED] ?: true,
            interfaceScale = this[APPEARANCE_INTERFACE_SCALE] ?: 1f,
        ).normalized(),
    )

    private fun MutablePreferences.putAppearance(settings: AppearanceSettings) {
        this[APPEARANCE_THEME_MODE] = settings.themeMode.persistedValue
        this[APPEARANCE_MONET_ENABLED] = settings.monetEnabled
        this[APPEARANCE_PALETTE_STYLE] = settings.paletteStyle.persistedValue
        this[APPEARANCE_ACCENT_COLOR] = settings.accentColor.persistedValue
        this[APPEARANCE_PURE_BLACK_ENABLED] = settings.pureBlackEnabled
        this[APPEARANCE_BLUR_ENABLED] = settings.blurEnabled
        this[APPEARANCE_TOP_BAR_BLUR_STYLE] = settings.topBarBlurStyle.persistedValue
        this[APPEARANCE_SWIPE_DISMISS_ENABLED] = settings.swipeDismissEnabled
        this[APPEARANCE_PREDICTIVE_BACK_ENABLED] = settings.predictiveBackEnabled
        this[APPEARANCE_INTERFACE_SCALE] = settings.interfaceScale
    }

    suspend fun launchCount(): Int {
        ensureInitialized()
        return dataStore.data
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .map { prefs -> prefs[APP_LAUNCH_COUNT] ?: 0 }
            .first()
    }

    suspend fun incrementLaunchCount() {
        ensureInitialized()
        dataStore.edit { prefs ->
            prefs[APP_LAUNCH_COUNT] = (prefs[APP_LAUNCH_COUNT] ?: 0) + 1
        }
    }

    suspend fun modelUsageJson(): String {
        ensureInitialized()
        return dataStore.data
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .map { prefs -> prefs[MODEL_USAGE_JSON].orEmpty() }
            .first()
    }

    suspend fun addModelUsage(json: String) {
        ensureInitialized()
        dataStore.edit { prefs ->
            prefs.putOrRemove(MODEL_USAGE_JSON, json.takeIf { it.isNotBlank() })
        }
    }

    suspend fun retiredUsage(): RetiredUsage {
        ensureInitialized()
        return dataStore.data
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .map { prefs ->
                RetiredUsage(
                    inputTokens = prefs[RETIRED_INPUT_TOKENS] ?: 0L,
                    outputTokens = prefs[RETIRED_OUTPUT_TOKENS] ?: 0L,
                    cachedTokens = prefs[RETIRED_CACHED_TOKENS] ?: 0L,
                    conversations = prefs[RETIRED_CONVERSATIONS] ?: 0,
                    messages = prefs[RETIRED_MESSAGES] ?: 0,
                    heatmap = decodeHeatmap(prefs[RETIRED_HEATMAP_JSON]),
                )
            }
            .first()
    }

    /** 清空会话时把即将丢失的历史累计（token、会话/消息数、热力图）合并保留下来。 */
    suspend fun addRetiredUsage(
        inputTokens: Long,
        outputTokens: Long,
        cachedTokens: Long,
        conversations: Int,
        messages: Int,
        heatmap: Map<LocalDate, Int>,
    ) {
        ensureInitialized()
        if (
            inputTokens <= 0L && outputTokens <= 0L && cachedTokens <= 0L &&
            conversations <= 0 && messages <= 0 && heatmap.isEmpty()
        ) {
            return
        }
        dataStore.edit { prefs ->
            prefs[RETIRED_INPUT_TOKENS] = (prefs[RETIRED_INPUT_TOKENS] ?: 0L) + inputTokens.coerceAtLeast(0L)
            prefs[RETIRED_OUTPUT_TOKENS] = (prefs[RETIRED_OUTPUT_TOKENS] ?: 0L) + outputTokens.coerceAtLeast(0L)
            prefs[RETIRED_CACHED_TOKENS] = (prefs[RETIRED_CACHED_TOKENS] ?: 0L) + cachedTokens.coerceAtLeast(0L)
            prefs[RETIRED_CONVERSATIONS] = (prefs[RETIRED_CONVERSATIONS] ?: 0) + conversations.coerceAtLeast(0)
            prefs[RETIRED_MESSAGES] = (prefs[RETIRED_MESSAGES] ?: 0) + messages.coerceAtLeast(0)
            if (heatmap.isNotEmpty()) {
                val merged = decodeHeatmap(prefs[RETIRED_HEATMAP_JSON]).toMutableMap()
                heatmap.forEach { (day, count) ->
                    if (count > 0) merged[day] = (merged[day] ?: 0) + count
                }
                prefs[RETIRED_HEATMAP_JSON] = encodeHeatmap(merged)
            }
        }
    }

    private fun encodeHeatmap(heatmap: Map<LocalDate, Int>): String {
        val json = JSONObject()
        heatmap.forEach { (day, count) -> if (count > 0) json.put(day.toString(), count) }
        return json.toString()
    }

    private fun decodeHeatmap(raw: String?): Map<LocalDate, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return buildMap {
            json.keys().forEach { key ->
                val day = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@forEach
                val count = json.optInt(key)
                if (count > 0) put(day, count)
            }
        }
    }
}

internal data class RetiredUsage(
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cachedTokens: Long = 0L,
    val conversations: Int = 0,
    val messages: Int = 0,
    val heatmap: Map<LocalDate, Int> = emptyMap(),
)
