package com.canis.hejje.domain

/** Compares release versions such as "v1.2.0", "1.10" or "1.2.0-beta1". */
object Version {
    private fun numbers(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    private fun isPreRelease(v: String): Boolean = v.substringBefore('+').contains('-')

    /** Negative if [a] is older than [b], positive if newer, zero if equal. */
    fun compare(a: String, b: String): Int {
        val x = numbers(a)
        val y = numbers(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        // Same numbers: a pre-release ("-beta") is older than the final release.
        return isPreRelease(b).compareTo(isPreRelease(a))
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0
}
