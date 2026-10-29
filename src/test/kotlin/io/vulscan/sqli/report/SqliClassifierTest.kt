package io.vulscan.sqli.report

import io.vulscan.kit.contracts.DbmsHint
import io.vulscan.kit.contracts.Verdict
import io.vulscan.kit.contracts.VulnType
import io.vulscan.sqli.detect.DetectionMethod
import io.vulscan.sqli.detect.DetectorResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Q-6: правила объединения результатов error/boolean/time-based детекторов в один finding. */
class SqliClassifierTest {
    private fun result(method: DetectionMethod, verdict: Verdict?, dbms: DbmsHint? = null) =
        DetectorResult(method = method, verdict = verdict, dbms = dbms)

    @Test
    fun `no method produced a signal means no finding`() {
        val results = listOf(
            result(DetectionMethod.ERROR_BASED, null),
            result(DetectionMethod.BOOLEAN_BASED, null),
        )
        assertThat(SqliClassifier.classify(results)).isNull()
    }

    @Test
    fun `a single suspected signal classifies as suspected`() {
        val results = listOf(result(DetectionMethod.BOOLEAN_BASED, Verdict.SUSPECTED))
        val classification = SqliClassifier.classify(results)!!
        assertThat(classification.verdict).isEqualTo(Verdict.SUSPECTED)
        assertThat(classification.vulnType).isEqualTo(VulnType.SQLI_BOOLEAN_BASED)
        assertThat(classification.primary.method).isEqualTo(DetectionMethod.BOOLEAN_BASED)
    }

    @Test
    fun `error-based alone can confirm without a second method`() {
        val results = listOf(result(DetectionMethod.ERROR_BASED, Verdict.CONFIRMED, DbmsHint.POSTGRESQL))
        val classification = SqliClassifier.classify(results)!!
        assertThat(classification.verdict).isEqualTo(Verdict.CONFIRMED)
        assertThat(classification.vulnType).isEqualTo(VulnType.SQLI_ERROR_BASED)
    }

    @Test
    fun `a single likely signal classifies as likely`() {
        val results = listOf(result(DetectionMethod.TIME_BASED, Verdict.LIKELY, DbmsHint.MYSQL))
        val classification = SqliClassifier.classify(results)!!
        assertThat(classification.verdict).isEqualTo(Verdict.LIKELY)
        assertThat(classification.vulnType).isEqualTo(VulnType.SQLI_TIME_BASED)
    }

    @Test
    fun `two independent likely signals escalate to confirmed`() {
        val results = listOf(
            result(DetectionMethod.BOOLEAN_BASED, Verdict.LIKELY),
            result(DetectionMethod.TIME_BASED, Verdict.LIKELY, DbmsHint.POSTGRESQL),
        )
        val classification = SqliClassifier.classify(results)!!
        assertThat(classification.verdict).isEqualTo(Verdict.CONFIRMED)
        assertThat(classification.contributing).hasSize(2)
    }

    @Test
    fun `when verdict ranks tie the stronger method wins as primary`() {
        val results = listOf(
            result(DetectionMethod.TIME_BASED, Verdict.LIKELY, DbmsHint.POSTGRESQL),
            result(DetectionMethod.ERROR_BASED, Verdict.LIKELY),
        )
        val classification = SqliClassifier.classify(results)!!
        assertThat(classification.primary.method).isEqualTo(DetectionMethod.ERROR_BASED)
        assertThat(classification.vulnType).isEqualTo(VulnType.SQLI_ERROR_BASED)
    }

    @Test
    fun `a suspected signal alongside a likely one does not escalate verdict but likely wins as primary`() {
        val results = listOf(
            result(DetectionMethod.ERROR_BASED, Verdict.SUSPECTED),
            result(DetectionMethod.BOOLEAN_BASED, Verdict.LIKELY),
        )
        val classification = SqliClassifier.classify(results)!!
        assertThat(classification.verdict).isEqualTo(Verdict.LIKELY)
        assertThat(classification.primary.method).isEqualTo(DetectionMethod.BOOLEAN_BASED)
        assertThat(classification.contributing).hasSize(2)
    }

    @Test
    fun `inconclusive results without a verdict do not count as signals`() {
        val results = listOf(
            result(DetectionMethod.ERROR_BASED, null).copy(inconclusiveReason = "SIGNATURE_IN_BASELINE"),
            result(DetectionMethod.BOOLEAN_BASED, Verdict.SUSPECTED),
        )
        val classification = SqliClassifier.classify(results)!!
        assertThat(classification.contributing).hasSize(1)
        assertThat(classification.primary.method).isEqualTo(DetectionMethod.BOOLEAN_BASED)
    }
}
