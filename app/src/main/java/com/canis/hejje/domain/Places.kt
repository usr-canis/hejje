package com.canis.hejje.domain

/** A place the user named. [icon] is one of [Places.ICONS]; [hide] makes it a privacy zone. */
data class NamedPlace(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Double,
    val icon: String = "pin",
    val hide: Boolean = true,
    val tag: String? = null,
)

/** A frequent parking spot that is not a named place yet. */
data class PlaceSuggestion(val lat: Double, val lon: Double, val visits: Int, val name: String?)

object Places {
    val ICONS = listOf("home", "work", "school", "gym", "shop", "family", "star", "pin")
    const val COMMUTE_TAG = "Commute"

    /** The named place at this spot (the nearest one if places overlap), or null. */
    fun placeAt(lat: Double, lon: Double, places: List<NamedPlace>): NamedPlace? =
        places.map { it to Geo.distance(lat, lon, it.lat, it.lon) }
            .filter { (p, d) -> d <= p.radiusM }
            .minByOrNull { it.second }?.first

    fun zones(places: List<NamedPlace>, privateOnly: Boolean = true): List<Zone> =
        places.filter { !privateOnly || it.hide }.map { Zone(it.lat, it.lon, it.radiusM) }

    /**
     * Tags a trip earns from its places: Home <-> Work is a commute, and a place can carry its own
     * tag for trips that end there.
     */
    fun autoTags(start: NamedPlace?, end: NamedPlace?): Set<String> {
        val tags = LinkedHashSet<String>()
        val icons = setOfNotNull(start?.icon, end?.icon)
        if (start != null && end != null && start.id != end.id && icons == setOf("home", "work")) tags += COMMUTE_TAG
        end?.tag?.takeIf { it.isNotBlank() }?.let { tags += it }
        return tags
    }

    /**
     * Spots where trips often end (at least [minVisits] times within [radiusM]) that are not inside
     * a named place yet, most visited first.
     * @param ends trip end points, with the street-level place name when known
     */
    fun suggest(
        ends: List<Triple<Double, Double, String?>>,
        existing: List<NamedPlace>,
        minVisits: Int = 3,
        radiusM: Double = 150.0,
        max: Int = 3,
    ): List<PlaceSuggestion> {
        val free = ends.filter { (lat, lon) -> existing.none { Geo.distance(lat, lon, it.lat, it.lon) <= it.radiusM + 50 } }
        val used = BooleanArray(free.size)
        val out = ArrayList<PlaceSuggestion>()
        // Densest first: each point's neighbour count decides the order clusters are taken in.
        val order = free.indices.sortedByDescending { i ->
            free.count { Geo.distance(free[i].first, free[i].second, it.first, it.second) <= radiusM }
        }
        for (i in order) {
            if (used[i]) continue
            val members = free.indices.filter { !used[it] && Geo.distance(free[i].first, free[i].second, free[it].first, free[it].second) <= radiusM }
            if (members.size < minVisits) continue
            members.forEach { used[it] = true }
            val name = members.mapNotNull { free[it].third?.takeIf(String::isNotBlank) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            out += PlaceSuggestion(members.map { free[it].first }.average(), members.map { free[it].second }.average(), members.size, name)
        }
        return out.sortedByDescending { it.visits }.take(max)
    }
}
