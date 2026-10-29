package io.vulscan.sqli

import io.vulscan.kit.worker.InvalidJobException
import io.vulscan.kit.contracts.SqliOptions
import io.vulscan.sqli.TestJobs.endpoint
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SqliDetectionEngineTest {
    @Test
    fun `invalid job is rejected with InvalidJobException`() {
        val job = TestJobs.job(endpoint(), options = SqliOptions(errorBased = false, booleanBased = false))
        val run = TestRun(job) { TestJobs.response("") }
        assertThatThrownBy { SqliDetectionEngine().run(run.ctx) }.isInstanceOf(InvalidJobException::class.java)
        assertThat(run.requests).isEmpty()
    }

    @Test
    fun `non idempotent endpoints receive no requests and are reported as skipped`() {
        val unsafe = endpoint(idempotent = false)
        val run = TestRun(TestJobs.job(unsafe, endpoint())) { TestJobs.response("ok") }
        SqliDetectionEngine().run(run.ctx)
        assertThat(run.requests.none { it.url.contains("sqli/search") && false }).isTrue()
        assertThat(run.stats.snapshot(0, 0).endpointsSkipped.map { it.reason }).containsExactly("NOT_IDEMPOTENT")
    }
}
