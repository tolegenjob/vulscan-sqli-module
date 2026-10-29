package io.vulscan.sqli.report

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.vulscan.kit.contracts.DbmsHint
import io.vulscan.kit.contracts.Envelope
import io.vulscan.kit.contracts.FindingPayload
import io.vulscan.kit.contracts.FindingReportedPayload
import io.vulscan.kit.contracts.HttpMethod
import io.vulscan.kit.contracts.Json
import io.vulscan.kit.contracts.MessageType
import io.vulscan.kit.contracts.SqliDetails
import io.vulscan.kit.contracts.Verdict
import io.vulscan.kit.net.ProbeRequest
import io.vulscan.kit.worker.FindingSanitizer
import io.vulscan.sqli.TestJobs
import io.vulscan.sqli.baseline.Baseline
import io.vulscan.sqli.compare.ResponseSignature
import io.vulscan.sqli.detect.DetectionMethod
import io.vulscan.sqli.detect.DetectorResult
import io.vulscan.sqli.http.Exchange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** Q-6: все findings модуля должны соответствовать JSON-схеме finding-reported.v1 из kit. */
class SqliFindingContractTest {
    private val endpoint = TestJobs.endpoint()
    private val parameter = endpoint.parameters.first()

    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

    private fun errors(finding: FindingPayload): List<String> {
        val envelope = Envelope(
            type = MessageType.FINDING_REPORTED,
            correlationId = UUID.randomUUID(),
            payload = FindingReportedPayload(UUID.randomUUID(), 1, FindingSanitizer.apply(finding)),
        )
        val schema = registry.getSchema(SchemaLocation.of("classpath:schemas/finding-reported.v1.json"))
        return schema.validate(Json.write(envelope), InputFormat.JSON).map { it.toString() }
    }

    private fun exchange(value: String, body: String, status: Int = 500): Exchange {
        val request = ProbeRequest(HttpMethod.GET, "${TestJobs.BASE}/sqli/search?id=$value", mapOf("Cookie" to "session=s3cr3t"))
        val response = TestJobs.response(body, status = status)
        return Exchange(value, request, response, body)
    }

    private fun baseline(first: Exchange, stable: Boolean = true) = Baseline(
        signature = ResponseSignature.of(first.status, "text/html", first.body, first.value),
        minPairwiseSimilarity = if (stable) 0.99 else 0.80,
        medianLatencyMs = 20,
        madLatencyMs = 2,
        stable = stable,
        sampleValue = "42",
        first = first,
        bodies = listOf(first.body),
    )

    @Test
    fun `confirmed error-based finding conforms to the schema`() {
        val candidate = exchange("42'", "ERROR: syntax error at or near \"'\"\n  PSQLException: ...")
        val results = listOf(
            DetectorResult(
                method = DetectionMethod.ERROR_BASED,
                verdict = Verdict.CONFIRMED,
                dbms = DbmsHint.POSTGRESQL,
                matchedSignatureIds = listOf("postgres-syntax-error", "postgres-exception-class"),
                candidateExchange = candidate,
                controlExchange = exchange("42''", "ok", status = 200),
                baselineSimilarity = 0.10,
                similarityThreshold = 0.87,
            ),
        )
        val finding = SqliFindingFactory.create(endpoint, parameter, results, baseline(candidate))
        assertThat(finding).isNotNull
        assertThat(errors(finding!!)).isEmpty()
        assertThat(finding.verdict.name).isEqualTo("CONFIRMED")
    }

    @Test
    fun `likely boolean-based finding conforms to the schema`() {
        val candidate = exchange("42' AND '1'='1", "<html>row 1 row 2</html>", status = 200)
        val results = listOf(
            DetectorResult(
                method = DetectionMethod.BOOLEAN_BASED,
                verdict = Verdict.LIKELY,
                candidateExchange = candidate,
                controlExchange = exchange("42' AND '1'='1'x", "<html>empty</html>", status = 200),
                baselineSimilarity = 0.95,
                similarityThreshold = 0.87,
            ),
        )
        val finding = SqliFindingFactory.create(endpoint, parameter, results, baseline(candidate))
        assertThat(finding).isNotNull
        assertThat(errors(finding!!)).isEmpty()
        assertThat(finding.verdict.name).isEqualTo("LIKELY")
    }

    @Test
    fun `suspected time-based finding conforms to the schema`() {
        val candidate = exchange("42; pg_sleep(3)", "<html>slow</html>", status = 200)
        val results = listOf(
            DetectorResult(
                method = DetectionMethod.TIME_BASED,
                verdict = Verdict.SUSPECTED,
                dbms = DbmsHint.POSTGRESQL,
                candidateExchange = candidate,
            ),
        )
        val finding = SqliFindingFactory.create(endpoint, parameter, results, baseline(candidate))
        assertThat(finding).isNotNull
        assertThat(errors(finding!!)).isEmpty()
        assertThat(finding.verdict.name).isEqualTo("SUSPECTED")
        assertThat(finding.evidence.response).isNotNull
    }

    @Test
    fun `combining two confirming methods still produces a single schema-valid finding`() {
        val candidate = exchange("42' AND '1'='1", "<html>row 1 row 2</html>", status = 200)
        val results = listOf(
            DetectorResult(method = DetectionMethod.BOOLEAN_BASED, verdict = Verdict.LIKELY, candidateExchange = candidate),
            DetectorResult(method = DetectionMethod.TIME_BASED, verdict = Verdict.LIKELY, dbms = DbmsHint.MYSQL, candidateExchange = candidate),
        )
        val finding = SqliFindingFactory.create(endpoint, parameter, results, baseline(candidate))
        assertThat(finding).isNotNull
        assertThat(errors(finding!!)).isEmpty()
        assertThat(finding.verdict.name).isEqualTo("CONFIRMED")
        assertThat((finding.details as SqliDetails).methodsConfirmed).containsExactlyInAnyOrder("BOOLEAN_BASED", "TIME_BASED")
    }

    @Test
    fun `no method produced a verdict means no finding`() {
        val candidate = exchange("42", "<html>normal</html>", status = 200)
        val results = listOf(
            DetectorResult(method = DetectionMethod.ERROR_BASED, verdict = null, inconclusiveReason = "SIGNATURE_IN_BASELINE"),
            DetectorResult(method = DetectionMethod.BOOLEAN_BASED, verdict = null, inconclusiveReason = "UNSTABLE_BASELINE"),
        )
        val finding = SqliFindingFactory.create(endpoint, parameter, results, baseline(candidate, stable = false))
        assertThat(finding).isNull()
    }
}
