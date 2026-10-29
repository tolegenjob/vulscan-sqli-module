package io.vulscan.sqli.detect

import io.vulscan.kit.contracts.Verdict
import io.vulscan.kit.net.ProbeRequest
import io.vulscan.kit.net.ProbeResponse
import io.vulscan.sqli.TestJobs
import io.vulscan.sqli.TestRun
import io.vulscan.sqli.baseline.BaselineCollector
import io.vulscan.sqli.http.ProbeSender
import io.vulscan.sqli.http.Sleeper
import io.vulscan.sqli.plan.ProbeTarget
import io.vulscan.sqli.plan.ValueType
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ErrorBasedDetectorTest {
    private val normalPage = "<html><body><h1>Товар</h1><p>Обычная страница товара номер 42</p></body></html>"

    private fun idValue(request: ProbeRequest): String = request.url.toHttpUrl().queryParameter("id") ?: ""

    private fun run(app: (ProbeRequest) -> ProbeResponse): Pair<DetectorResult, TestRun> {
        val endpoint = TestJobs.endpoint()
        val target = ProbeTarget(endpoint, endpoint.parameters.first(), ValueType.NUMERIC)
        val run = TestRun(TestJobs.job(endpoint), app)
        val sender = ProbeSender(run.http, Sleeper { })
        val baseline = BaselineCollector(sender).collect(target)
        val result = ErrorBasedDetector(sender).detect(target, baseline)
        return result to run
    }

    @Test
    fun `genuine Postgres error, clean escaped-pair control, reproduced -- CONFIRMED`() {
        val (result, run) = run { req ->
            val id = idValue(req)
            when {
                id == "42" -> TestJobs.response(normalPage)
                id.endsWith("''") -> TestJobs.response(normalPage)
                id.endsWith("'") -> TestJobs.response("ERROR: syntax error at or near \"'\" at character 10", status = 500)
                else -> TestJobs.response(normalPage)
            }
        }
        assertThat(result.method).isEqualTo(DetectionMethod.ERROR_BASED)
        assertThat(result.verdict).isEqualTo(Verdict.CONFIRMED)
        assertThat(result.matchedSignatureIds).contains("postgres-syntax-error")
        assertThat(result.dbms?.name).isEqualTo("POSTGRESQL")
        assertThat(result.candidateExchange).isNotNull()
        assertThat(result.controlExchange).isNotNull()
        assertThat(run.requests).isNotEmpty()
    }

    @Test
    fun `500 without any known signature is not a finding`() {
        val (result, _) = run { req ->
            if (idValue(req) == "42") TestJobs.response(normalPage) else TestJobs.response("Internal Server Error", status = 500)
        }
        assertThat(result.verdict).isNull()
        assertThat(result.http500WithoutSignature).isTrue()
    }

    @Test
    fun `a signature already present in the baseline is inconclusive, not a finding`() {
        val staticPage = "Static demo: syntax error at or near something (this is just a demo page, always the same)"
        val (result, _) = run { TestJobs.response(staticPage) }
        assertThat(result.verdict).isNull()
        assertThat(result.inconclusiveReason).isEqualTo("SIGNATURE_IN_BASELINE")
    }

    @Test
    fun `signature on the probe but also on the escaped-pair control -- SUSPECTED`() {
        val (result, _) = run { req ->
            val id = idValue(req)
            if (id == "42") TestJobs.response(normalPage)
            else if (id.endsWith("'")) TestJobs.response("ERROR: syntax error at or near \"'\"", status = 500)
            else TestJobs.response(normalPage)
        }
        assertThat(result.verdict).isEqualTo(Verdict.SUSPECTED)
        assertThat(result.matchedSignatureIds).contains("postgres-syntax-error")
    }

    @Test
    fun `the error does not reproduce on the second request -- no finding`() {
        val callsOnQuote = AtomicInteger()
        val (result, _) = run { req ->
            val id = idValue(req)
            when {
                id == "42" -> TestJobs.response(normalPage)
                id.endsWith("''") -> TestJobs.response(normalPage)
                id.endsWith("'") -> {
                    val n = callsOnQuote.getAndIncrement()
                    if (n == 0) TestJobs.response("ERROR: syntax error at or near \"'\"", status = 500) else TestJobs.response(normalPage)
                }
                else -> TestJobs.response(normalPage)
            }
        }
        assertThat(result.verdict).isNull()
        assertThat(result.inconclusiveReason).isNull()
    }

    @Test
    fun `a WAF blocking quote characters with 403 produces no finding`() {
        val (result, run) = run { req ->
            if (idValue(req) == "42") TestJobs.response(normalPage) else TestJobs.response("Forbidden: request blocked", status = 403)
        }
        assertThat(result.verdict).isNull()
        assertThat(result.http500WithoutSignature).isFalse()
        // baseline (3) + одна проба на каждый из трёх символов, ни разу не найдено совпадение -> без control/repeat.
        assertThat(run.requests).hasSize(6)
    }

    @Test
    fun `two dbms matched with an equal weight leave dbms undetermined and cap the verdict at LIKELY`() {
        val (result, _) = run { req ->
            val id = idValue(req)
            when {
                id == "42" -> TestJobs.response(normalPage)
                id.endsWith("''") -> TestJobs.response(normalPage)
                id.endsWith("'") ->
                    TestJobs.response("pg_query() failed; check the manual that corresponds to your MySQL server version", status = 500)
                else -> TestJobs.response(normalPage)
            }
        }
        assertThat(result.dbms).isNull()
        assertThat(result.verdict).isEqualTo(Verdict.LIKELY)
    }
}
