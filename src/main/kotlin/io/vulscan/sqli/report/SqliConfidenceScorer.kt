package io.vulscan.sqli.report

import io.vulscan.kit.contracts.Verdict

/** Q-6: SUSPECTED=30, LIKELY=70, CONFIRMED=92; +5 известная СУБД; −10 нестабильный baseline у любого
 * из методов; −10, если контроль хотя бы частично воспроизвёл эффект. Итог — clamp 1..99. */
object SqliConfidenceScorer {
    fun score(verdict: Verdict, dbmsKnown: Boolean, anyUnstableBaseline: Boolean, anyPartialControlMatch: Boolean): Int {
        var value = when (verdict) {
            Verdict.SUSPECTED -> 30
            Verdict.LIKELY -> 70
            Verdict.CONFIRMED -> 92
        }
        if (dbmsKnown) value += 5
        if (anyUnstableBaseline) value -= 10
        if (anyPartialControlMatch) value -= 10
        return value.coerceIn(1, 99)
    }
}
