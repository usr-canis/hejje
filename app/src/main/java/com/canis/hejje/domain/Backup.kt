package com.canis.hejje.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The one-file backup. Stats are not stored: they are recomputed from the segments on import. */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long = 0,
    val appVersion: String = "",
    val settings: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
    val zones: List<BackupZone> = emptyList(),
    val trips: List<BackupTrip> = emptyList(),
) {
    companion object {
        const val FORMAT = "hejje-backup"
        const val VERSION = 1
    }
}

@Serializable
data class BackupZone(
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Double,
    val icon: String = "pin",
    val hide: Boolean = true,
    val tag: String? = null,
)

@Serializable
data class BackupTrip(
    val uuid: String,
    val note: String = "",
    val startPlace: String? = null,
    val endPlace: String? = null,
    val tags: List<String> = emptyList(),
    val segments: List<BackupSegment> = emptyList(),
)

@Serializable
class BackupSegment(val start: Long, val end: Long, val data: SegmentData)

val HejjeJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    allowSpecialFloatingPointValues = true
}
