package io.vulscan.sqli.report

import io.vulscan.kit.contracts.Severity
import io.vulscan.kit.contracts.Verdict
import io.vulscan.kit.contracts.VulnType

/** Q-6: CONFIRMED/LIKELY → HIGH (error-/boolean-based) или MEDIUM (time-based); SUSPECTED → LOW.
 * CRITICAL никогда не назначается — извлечение данных модулем не выполняется и не доказывается. */
object SqliSeverityPolicy {
    fun severity(verdict: Verdict, vulnType: VulnType): Severity = when (verdict) {
        Verdict.SUSPECTED -> Severity.LOW
        Verdict.LIKELY, Verdict.CONFIRMED -> if (vulnType == VulnType.SQLI_TIME_BASED) Severity.MEDIUM else Severity.HIGH
    }
}
