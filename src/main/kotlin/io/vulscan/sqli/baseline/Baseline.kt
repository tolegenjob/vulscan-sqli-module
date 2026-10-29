package io.vulscan.sqli.baseline

import io.vulscan.sqli.compare.ResponseSignature
import io.vulscan.sqli.http.Exchange

/**
 * Базовый уровень ответов параметра. [theta] — порог различимости: ответы с похожестью ниже него считаются разными.
 * Нестабильный baseline не позволяет делать выводы по различию ответов.
 */
class Baseline(
    val signature: ResponseSignature,
    val minPairwiseSimilarity: Double,
    val medianLatencyMs: Long,
    val madLatencyMs: Long,
    val stable: Boolean,
    val sampleValue: String,
    val first: Exchange,
    val bodies: List<String>,
) {
    val theta: Double get() = minOf(MAX_THETA, minPairwiseSimilarity - THETA_MARGIN).coerceAtLeast(0.0)

    companion object {
        const val STABLE_SIMILARITY = 0.98
        const val MAX_THETA = 0.90
        const val THETA_MARGIN = 0.03
    }
}
