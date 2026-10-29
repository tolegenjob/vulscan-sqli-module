package io.vulscan.sqli.report

import io.vulscan.kit.contracts.Verdict
import io.vulscan.kit.contracts.VulnType
import io.vulscan.sqli.detect.DetectionMethod
import io.vulscan.sqli.detect.DetectorResult

/** [primary] — результат, чей метод определяет [vulnType] (сильнейший признак, см. [SqliClassifier]). */
data class SqliClassification(
    val vulnType: VulnType,
    val verdict: Verdict,
    val contributing: List<DetectorResult>,
    val primary: DetectorResult,
)

/**
 * Q-6: один finding на (endpoint, parameter), даже если сигнал дали несколько методов.
 * `vulnType` — по сильнейшему признаку: `ERROR_BASED` > `BOOLEAN_BASED` > `TIME_BASED`, при равенстве
 * вердиктов выбирается метод с более высоким приоритетом. `CONFIRMED`, если ≥ 2 независимых метода
 * дали `LIKELY`+ (или error-based сам по себе дал `CONFIRMED`); `LIKELY` — один метод `LIKELY`;
 * `SUSPECTED` — иначе (хотя бы один метод дал `SUSPECTED`, и больше ничего сильнее).
 */
object SqliClassifier {
    private val METHOD_PRIORITY = listOf(DetectionMethod.ERROR_BASED, DetectionMethod.BOOLEAN_BASED, DetectionMethod.TIME_BASED)
    private val VERDICT_RANK = mapOf(Verdict.SUSPECTED to 0, Verdict.LIKELY to 1, Verdict.CONFIRMED to 2)
    private val VULN_TYPE_OF = mapOf(
        DetectionMethod.ERROR_BASED to VulnType.SQLI_ERROR_BASED,
        DetectionMethod.BOOLEAN_BASED to VulnType.SQLI_BOOLEAN_BASED,
        DetectionMethod.TIME_BASED to VulnType.SQLI_TIME_BASED,
    )

    /** `null` — ни один метод не дал находки (нет finding для этого параметра). */
    fun classify(results: List<DetectorResult>): SqliClassification? {
        val signals = results.filter { it.verdict != null }
        if (signals.isEmpty()) return null

        val likelyPlus = signals.filter { it.verdict == Verdict.LIKELY || it.verdict == Verdict.CONFIRMED }
        val verdict = when {
            signals.any { it.verdict == Verdict.CONFIRMED } || likelyPlus.size >= 2 -> Verdict.CONFIRMED
            likelyPlus.size == 1 -> Verdict.LIKELY
            else -> Verdict.SUSPECTED
        }

        val primary = signals.maxWith(
            compareBy({ VERDICT_RANK.getValue(it.verdict!!) }, { -METHOD_PRIORITY.indexOf(it.method) }),
        )
        return SqliClassification(VULN_TYPE_OF.getValue(primary.method), verdict, signals, primary)
    }
}
