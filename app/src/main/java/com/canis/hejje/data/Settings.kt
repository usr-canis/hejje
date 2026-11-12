package com.canis.hejje.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

data class SettingsData(
    val carAddress: String? = null,
    val carName: String? = null,
    val autoRecord: Boolean = true,
    val mergeGapMin: Int = 90,
    val minTripM: Int = 0,
    val miles: Boolean = false,
    /** 0 = best accuracy (1 s charging / 2 s battery), 1 = battery saver (2 s / 5 s). */
    val gpsMode: Int = 0,
    /** 0 = low, 1 = normal, 2 = high. */
    val brakeSensitivity: Int = 1,
    val placeLookup: Boolean = true,
    val tileCacheMb: Int = 200,
    /** 0 = right away, 1 = after 5 minutes, 2 = when the trip can no longer continue, 3 = off. */
    val summaryTiming: Int = 0,
    val widgetLastTrip: Boolean = true,
    /** Offer to turn Location on when a drive starts with it off, and off again afterwards. */
    val locationPrompts: Boolean = true,
    /** 0 = follow system, 1 = light, 2 = dark. */
    val theme: Int = 0,
    val backupUri: String? = null,
    val lastBackup: Long = 0,
    val backupKeep: Int = 4,
    val updateAutoCheck: Boolean = true,
    val updateWifiOnly: Boolean = false,
    val updateNotify: Boolean = true,
    val skippedVersion: String? = null,
    val lastUpdateCheck: Long = 0,
    /** The newest release found by the last check, as JSON, so it can be shown again without asking GitHub. */
    val pendingUpdate: String? = null,
    val zonesInApp: Boolean = false,
    val routeBySpeed: Boolean = true,
    val onboardingDone: Boolean = false,
    val lastAutoStart: Long = 0,
    val lastProblem: String? = null,
    val lastProblemAt: Long = 0,
    val parkedLat: Double? = null,
    val parkedLon: Double? = null,
    val parkedAt: Long = 0,
) {
    val mergeGapMs: Long get() = mergeGapMin * 60_000L
    val brakeThreshold: Float get() = floatArrayOf(3.6f, 3.0f, 2.5f)[brakeSensitivity.coerceIn(0, 2)]
    val accelThreshold: Float get() = floatArrayOf(3.2f, 2.7f, 2.3f)[brakeSensitivity.coerceIn(0, 2)]
}

private val Context.store: DataStore<Preferences> by preferencesDataStore("settings")

class SettingsStore(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.store

    private object K {
        val carAddress = stringPreferencesKey("carAddress")
        val carName = stringPreferencesKey("carName")
        val autoRecord = booleanPreferencesKey("autoRecord")
        val mergeGapMin = intPreferencesKey("mergeGapMin")
        val minTripM = intPreferencesKey("minTripM")
        val miles = booleanPreferencesKey("miles")
        val gpsMode = intPreferencesKey("gpsMode")
        val brakeSensitivity = intPreferencesKey("brakeSensitivity")
        val placeLookup = booleanPreferencesKey("placeLookup")
        val tileCacheMb = intPreferencesKey("tileCacheMb")
        val summaryTiming = intPreferencesKey("summaryTiming")
        val widgetLastTrip = booleanPreferencesKey("widgetLastTrip")
        val locationPrompts = booleanPreferencesKey("locationPrompts")
        val theme = intPreferencesKey("theme")
        val backupUri = stringPreferencesKey("backupUri")
        val lastBackup = longPreferencesKey("lastBackup")
        val backupKeep = intPreferencesKey("backupKeep")
        val updateAutoCheck = booleanPreferencesKey("updateAutoCheck")
        val updateWifiOnly = booleanPreferencesKey("updateWifiOnly")
        val updateNotify = booleanPreferencesKey("updateNotify")
        val skippedVersion = stringPreferencesKey("skippedVersion")
        val lastUpdateCheck = longPreferencesKey("lastUpdateCheck")
        val pendingUpdate = stringPreferencesKey("pendingUpdate")
        val zonesInApp = booleanPreferencesKey("zonesInApp")
        val routeBySpeed = booleanPreferencesKey("routeBySpeed")
        val onboardingDone = booleanPreferencesKey("onboardingDone")
        val lastAutoStart = longPreferencesKey("lastAutoStart")
        val lastProblem = stringPreferencesKey("lastProblem")
        val lastProblemAt = longPreferencesKey("lastProblemAt")
        val parkedLat = doublePreferencesKey("parkedLat")
        val parkedLon = doublePreferencesKey("parkedLon")
        val parkedAt = longPreferencesKey("parkedAt")
    }

    private fun read(p: Preferences): SettingsData {
        val d = SettingsData()
        return SettingsData(
            carAddress = p[K.carAddress], carName = p[K.carName],
            autoRecord = p[K.autoRecord] ?: d.autoRecord,
            mergeGapMin = p[K.mergeGapMin] ?: d.mergeGapMin,
            minTripM = p[K.minTripM] ?: d.minTripM,
            miles = p[K.miles] ?: d.miles,
            gpsMode = p[K.gpsMode] ?: d.gpsMode,
            brakeSensitivity = p[K.brakeSensitivity] ?: d.brakeSensitivity,
            placeLookup = p[K.placeLookup] ?: d.placeLookup,
            tileCacheMb = p[K.tileCacheMb] ?: d.tileCacheMb,
            summaryTiming = p[K.summaryTiming] ?: d.summaryTiming,
            widgetLastTrip = p[K.widgetLastTrip] ?: d.widgetLastTrip,
            locationPrompts = p[K.locationPrompts] ?: d.locationPrompts,
            theme = p[K.theme] ?: d.theme,
            backupUri = p[K.backupUri], lastBackup = p[K.lastBackup] ?: 0,
            backupKeep = p[K.backupKeep] ?: d.backupKeep,
            updateAutoCheck = p[K.updateAutoCheck] ?: d.updateAutoCheck,
            updateWifiOnly = p[K.updateWifiOnly] ?: d.updateWifiOnly,
            updateNotify = p[K.updateNotify] ?: d.updateNotify,
            skippedVersion = p[K.skippedVersion], lastUpdateCheck = p[K.lastUpdateCheck] ?: 0,
            pendingUpdate = p[K.pendingUpdate],
            zonesInApp = p[K.zonesInApp] ?: d.zonesInApp,
            routeBySpeed = p[K.routeBySpeed] ?: d.routeBySpeed,
            onboardingDone = p[K.onboardingDone] ?: d.onboardingDone,
            lastAutoStart = p[K.lastAutoStart] ?: 0,
            lastProblem = p[K.lastProblem], lastProblemAt = p[K.lastProblemAt] ?: 0,
            parkedLat = p[K.parkedLat], parkedLon = p[K.parkedLon], parkedAt = p[K.parkedAt] ?: 0,
        )
    }

    private fun write(p: androidx.datastore.preferences.core.MutablePreferences, s: SettingsData) {
        fun <T> set(key: Preferences.Key<T>, value: T?) { if (value == null) p.remove(key) else p[key] = value }
        set(K.carAddress, s.carAddress); set(K.carName, s.carName); set(K.autoRecord, s.autoRecord)
        set(K.mergeGapMin, s.mergeGapMin); set(K.minTripM, s.minTripM); set(K.miles, s.miles)
        set(K.gpsMode, s.gpsMode); set(K.brakeSensitivity, s.brakeSensitivity); set(K.placeLookup, s.placeLookup)
        set(K.tileCacheMb, s.tileCacheMb); set(K.summaryTiming, s.summaryTiming); set(K.widgetLastTrip, s.widgetLastTrip)
        set(K.locationPrompts, s.locationPrompts)
        set(K.theme, s.theme); set(K.backupUri, s.backupUri); set(K.lastBackup, s.lastBackup); set(K.backupKeep, s.backupKeep)
        set(K.updateAutoCheck, s.updateAutoCheck); set(K.updateWifiOnly, s.updateWifiOnly); set(K.updateNotify, s.updateNotify)
        set(K.skippedVersion, s.skippedVersion); set(K.lastUpdateCheck, s.lastUpdateCheck); set(K.pendingUpdate, s.pendingUpdate)
        set(K.zonesInApp, s.zonesInApp)
        set(K.routeBySpeed, s.routeBySpeed); set(K.onboardingDone, s.onboardingDone); set(K.lastAutoStart, s.lastAutoStart)
        set(K.lastProblem, s.lastProblem); set(K.lastProblemAt, s.lastProblemAt)
        set(K.parkedLat, s.parkedLat); set(K.parkedLon, s.parkedLon); set(K.parkedAt, s.parkedAt)
    }

    /** Always holds the latest settings; loaded once up front so background receivers can read it at once. */
    private val state = MutableStateFlow(runBlocking { read(store.data.first()) })

    init {
        scope.launch { store.data.map(::read).collect { state.value = it } }
    }

    /** Always holds the latest settings, including a change made a moment ago by [update]. */
    val data: StateFlow<SettingsData> = state.asStateFlow()

    val current: SettingsData get() = data.value

    suspend fun update(change: (SettingsData) -> SettingsData) {
        // Publish the saved result right away, so code that reads settings next sees the change.
        state.value = read(store.edit { p -> write(p, change(read(p))) })
    }

    /** Portable settings for the backup file (nothing tied to this phone's folders or state). */
    fun export(s: SettingsData): Map<String, String> = mapOf(
        "autoRecord" to s.autoRecord.toString(), "mergeGapMin" to s.mergeGapMin.toString(),
        "minTripM" to s.minTripM.toString(), "miles" to s.miles.toString(), "gpsMode" to s.gpsMode.toString(),
        "brakeSensitivity" to s.brakeSensitivity.toString(), "placeLookup" to s.placeLookup.toString(),
        "tileCacheMb" to s.tileCacheMb.toString(), "summaryTiming" to s.summaryTiming.toString(),
        "widgetLastTrip" to s.widgetLastTrip.toString(), "theme" to s.theme.toString(),
        "locationPrompts" to s.locationPrompts.toString(),
        "updateAutoCheck" to s.updateAutoCheck.toString(), "updateWifiOnly" to s.updateWifiOnly.toString(),
        "updateNotify" to s.updateNotify.toString(), "zonesInApp" to s.zonesInApp.toString(),
        "routeBySpeed" to s.routeBySpeed.toString(), "backupKeep" to s.backupKeep.toString(),
    )

    suspend fun import(m: Map<String, String>) = update { s ->
        fun b(k: String, d: Boolean) = m[k]?.toBooleanStrictOrNull() ?: d
        fun i(k: String, d: Int) = m[k]?.toIntOrNull() ?: d
        s.copy(
            autoRecord = b("autoRecord", s.autoRecord), mergeGapMin = i("mergeGapMin", s.mergeGapMin),
            minTripM = i("minTripM", s.minTripM), miles = b("miles", s.miles), gpsMode = i("gpsMode", s.gpsMode),
            brakeSensitivity = i("brakeSensitivity", s.brakeSensitivity), placeLookup = b("placeLookup", s.placeLookup),
            tileCacheMb = i("tileCacheMb", s.tileCacheMb), summaryTiming = i("summaryTiming", s.summaryTiming),
            widgetLastTrip = b("widgetLastTrip", s.widgetLastTrip), theme = i("theme", s.theme),
            locationPrompts = b("locationPrompts", s.locationPrompts),
            updateAutoCheck = b("updateAutoCheck", s.updateAutoCheck), updateWifiOnly = b("updateWifiOnly", s.updateWifiOnly),
            updateNotify = b("updateNotify", s.updateNotify), zonesInApp = b("zonesInApp", s.zonesInApp),
            routeBySpeed = b("routeBySpeed", s.routeBySpeed), backupKeep = i("backupKeep", s.backupKeep),
        )
    }

    suspend fun reset() {
        state.value = read(store.edit { it.clear() })
    }
}
