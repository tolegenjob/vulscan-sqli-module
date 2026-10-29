package io.vulscan.sqli

import io.vulscan.kit.contracts.Verdict
import io.vulscan.kit.worker.InvalidJobException
import io.vulscan.kit.contracts.SqliOptions
import io.vulscan.kit.net.ProbeRequest
import io.vulscan.sqli.TestJobs.endpoint
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SqliDetectionEngineTest {
    private val normalPage = "<html><body><h1>Товар</h1><p>Обычная страница товара номер 42</p></body></html>"

    private fun idValue(request: ProbeRequest): String = request.url.toHttpUrl().queryParameter("id") ?: ""

    @Test
    fun `invalid job is rejected with InvalidJobException`() {
        val job = TestJobs.job(endpoint(), options = SqliOptions(errorBased = false, booleanBased = false))
        val run = TestRun(job) { TestJobs.response("") }
        assertThatThrownBy { SqliDetectionEngine().run(run.ctx) }.isInstanceOf(InvalidJobException::class.java)
        assertThat(run.requests).isEmpty()
    }

    @Test
    fun `non idempotent endpoints receive no requests and are reported as skipped`() {
        val unsafe = endpoint(url = "${TestJobs.BASE}/unsafe/update", idempotent = false)
        val run = TestRun(TestJobs.job(unsafe, endpoint())) { TestJobs.response("ok") }
        SqliDetectionEngine().run(run.ctx)
        assertThat(run.requests.filter { it.url.contains("/unsafe/") }).isEmpty()
        val skipped = run.stats.snapshot(0, 0).endpointsSkipped
        assertThat(skipped.map { it.reason }).containsExactly("NOT_IDEMPOTENT")
        assertThat(skipped.single().endpointId).isEqualTo(unsafe.endpointId)
    }

    @Test
    fun `a genuinely vulnerable endpoint produces a CONFIRMED error-based finding end to end`() {
        val vulnerable = endpoint()
        val run = TestRun(TestJobs.job(vulnerable)) { req ->
            val id = idValue(req)
            when {
                id == "42" -> TestJobs.response(normalPage)
                id.endsWith("''") -> TestJobs.response(normalPage)
                id.endsWith("'") -> TestJobs.response("ERROR: syntax error at or near \"'\" at character 10", status = 500)
                else -> TestJobs.response(normalPage)
            }
        }
        SqliDetectionEngine().run(run.ctx)
        assertThat(run.findings).hasSize(1)
        val finding = run.findings.single()
        assertThat(finding.verdict).isEqualTo(Verdict.CONFIRMED)
        assertThat(finding.vulnType.name).isEqualTo("SQLI_ERROR_BASED")
    }

    @Test
    fun `a clean endpoint produces no findings`() {
        val clean = endpoint()
        val run = TestRun(TestJobs.job(clean)) { TestJobs.response(normalPage) }
        SqliDetectionEngine().run(run.ctx)
        assertThat(run.findings).isEmpty()
    }
}
