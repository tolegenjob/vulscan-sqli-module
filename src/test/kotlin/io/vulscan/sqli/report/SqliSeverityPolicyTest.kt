package io.vulscan.sqli.report

import io.vulscan.kit.contracts.Severity
import io.vulscan.kit.contracts.Verdict
import io.vulscan.kit.contracts.VulnType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Q-6: SUSPECTED → LOW; LIKELY/CONFIRMED → HIGH, кроме time-based (MEDIUM); CRITICAL никогда не назначается. */
class SqliSeverityPolicyTest {
    @Test
    fun `suspected is always low regardless of vuln type`() {
        assertThat(SqliSeverityPolicy.severity(Verdict.SUSPECTED, VulnType.SQLI_ERROR_BASED)).isEqualTo(Severity.LOW)
        assertThat(SqliSeverityPolicy.severity(Verdict.SUSPECTED, VulnType.SQLI_TIME_BASED)).isEqualTo(Severity.LOW)
    }

    @Test
    fun `likely or confirmed error or boolean based is high`() {
        assertThat(SqliSeverityPolicy.severity(Verdict.LIKELY, VulnType.SQLI_ERROR_BASED)).isEqualTo(Severity.HIGH)
        assertThat(SqliSeverityPolicy.severity(Verdict.CONFIRMED, VulnType.SQLI_ERROR_BASED)).isEqualTo(Severity.HIGH)
        assertThat(SqliSeverityPolicy.severity(Verdict.LIKELY, VulnType.SQLI_BOOLEAN_BASED)).isEqualTo(Severity.HIGH)
        assertThat(SqliSeverityPolicy.severity(Verdict.CONFIRMED, VulnType.SQLI_BOOLEAN_BASED)).isEqualTo(Severity.HIGH)
    }

    @Test
    fun `likely or confirmed time based is capped at medium`() {
        assertThat(SqliSeverityPolicy.severity(Verdict.LIKELY, VulnType.SQLI_TIME_BASED)).isEqualTo(Severity.MEDIUM)
        assertThat(SqliSeverityPolicy.severity(Verdict.CONFIRMED, VulnType.SQLI_TIME_BASED)).isEqualTo(Severity.MEDIUM)
    }

    @Test
    fun `critical is never returned`() {
        for (verdict in Verdict.entries) {
            for (vulnType in listOf(VulnType.SQLI_ERROR_BASED, VulnType.SQLI_BOOLEAN_BASED, VulnType.SQLI_TIME_BASED)) {
                assertThat(SqliSeverityPolicy.severity(verdict, vulnType)).isNotEqualTo(Severity.CRITICAL)
            }
        }
    }
}
