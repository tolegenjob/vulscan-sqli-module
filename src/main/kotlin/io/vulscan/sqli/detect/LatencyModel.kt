package io.vulscan.sqli.detect

import io.vulscan.sqli.baseline.LatencyStats

/**
 * Профиль латентности параметра без каких-либо проб с задержкой (Q-5): [latencySamples] одинаковых
 * запросов подряд (без параллелизма), медиана и MAD. Слишком высокая или слишком шумная латентность
 * делает time-based проверку недоказуемой — `highJitter`.
 */
object LatencyModel {
    data class Profile(val median: Long, val mad: Long, val highJitter: Boolean)

    const val MAX_MEDIAN_MS = 2000L
    const val MAX_MAD_RATIO = 0.5

    fun profile(samples: List<Long>): Profile {
        val median = LatencyStats.median(samples)
        val mad = LatencyStats.mad(samples)
        val highJitter = median > MAX_MEDIAN_MS || mad > MAX_MAD_RATIO * median
        return Profile(median, mad, highJitter)
    }
}
