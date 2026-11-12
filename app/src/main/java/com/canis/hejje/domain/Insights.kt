package com.canis.hejje.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class TripSummary(
    val id: Long,
    val start: Long,
    val end: Long,
    val distanceM: Double,
    val movingMs: Long,
    val maxSpeed: Float,
    val endPlace: String? = null,
    val tags: List<String> = emptyList(),
)

data class Totals(val trips: Int = 0, val distanceM: Double = 0.0, val movingMs: Long = 0, val durationMs: Long = 0)

data class TagStat(val name: String, val trips: Int, val distanceM: Double)

data class InsightsData(
    val week: Totals,
    val lastWeek: Totals,
    val month: Totals,
    val allTime: Totals,
    val longest: TripSummary?,
    val topSpeed: TripSummary?,
    val currentStreak: Int,
    val longestStreak: Int,
    val places: List<Pair<String, Int>>,
    val tags: List<TagStat>,
    val daily: Map<LocalDate, Double>,
)

object Insights {
    /** Weeks start on Monday. */
    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun dayOf(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    fun totals(trips: List<TripSummary>): Totals = Totals(
        trips.size, trips.sumOf { it.distanceM }, trips.sumOf { it.movingMs }, trips.sumOf { it.end - it.start },
    )

    fun compute(trips: List<TripSummary>, today: LocalDate, zone: ZoneId): InsightsData {
        val byDay = trips.groupBy { dayOf(it.start, zone) }
        val monday = weekStart(today)
        val inRange = { from: LocalDate, to: LocalDate -> trips.filter { dayOf(it.start, zone).let { d -> d >= from && d <= to } } }

        // Streaks: consecutive calendar days with at least one drive.
        val days = byDay.keys.sorted()
        var longest = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in days) {
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            longest = maxOf(longest, run)
            prev = d
        }
        var current = 0
        var cursor = if (today in byDay) today else today.minusDays(1)
        while (cursor in byDay) {
            current++
            cursor = cursor.minusDays(1)
        }

        return InsightsData(
            week = totals(inRange(monday, monday.plusDays(6))),
            lastWeek = totals(inRange(monday.minusDays(7), monday.minusDays(1))),
            month = totals(inRange(today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()))),
            allTime = totals(trips),
            longest = trips.maxByOrNull { it.distanceM },
            topSpeed = trips.maxByOrNull { it.maxSpeed },
            currentStreak = current,
            longestStreak = longest,
            places = trips.mapNotNull { it.endPlace?.takeIf(String::isNotBlank) }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
            tags = trips.flatMap { t -> t.tags.map { it to t } }.groupBy({ it.first }, { it.second })
                .map { (name, list) -> TagStat(name, list.size, list.sumOf { it.distanceM }) }
                .sortedByDescending { it.distanceM },
            daily = byDay.mapValues { (_, list) -> list.sumOf { it.distanceM } },
        )
    }
}
