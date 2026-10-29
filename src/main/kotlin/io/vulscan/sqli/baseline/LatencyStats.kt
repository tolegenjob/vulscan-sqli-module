package io.vulscan.sqli.baseline

/** Устойчивые оценки латентности: медиана и медианное абсолютное отклонение (MAD). */
object LatencyStats {
    fun median(values: List<Long>): Long {
        require(values.isNotEmpty()) { "median of an empty series" }
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    fun mad(values: List<Long>): Long {
        val m = median(values)
        return median(values.map { kotlin.math.abs(it - m) })
    }
}
