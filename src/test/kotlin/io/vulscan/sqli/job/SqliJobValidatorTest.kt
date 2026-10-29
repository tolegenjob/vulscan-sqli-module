package io.vulscan.sqli.job

import io.vulscan.kit.contracts.ParamLocation
import io.vulscan.kit.contracts.SqliOptions
import io.vulscan.sqli.TestJobs
import io.vulscan.sqli.TestJobs.endpoint
import io.vulscan.sqli.TestJobs.param
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SqliJobValidatorTest {
    @Test
    fun `default job is valid`() {
        assertThat(SqliJobValidator.validate(TestJobs.job(endpoint()))).isEmpty()
    }

    @Test
    fun `job without sqli options is invalid`() {
        val base = TestJobs.job(endpoint())
        val job = base.copy(moduleOptions = base.moduleOptions.copy(sqli = null))
        assertThat(SqliJobValidator.validate(job)).anyMatch { it.contains("moduleOptions.sqli") }
    }

    @Test
    fun `at least one check must be enabled`() {
        val job = TestJobs.job(endpoint(), options = SqliOptions(errorBased = false, booleanBased = false, timeBased = false))
        assertThat(SqliJobValidator.validate(job)).anyMatch { it.contains("at least one") }
    }

    @Test
    fun `time based needs at least 300 seconds`() {
        val options = SqliOptions(timeBased = true)
        assertThat(SqliJobValidator.validate(TestJobs.job(endpoint(), options = options, maxDurationSec = 299))).anyMatch { it.contains("maxDurationSec") }
        assertThat(SqliJobValidator.validate(TestJobs.job(endpoint(), options = options, maxDurationSec = 300))).isEmpty()
    }

    @Test
    fun `endpoint with only header parameters is invalid`() {
        val job = TestJobs.job(endpoint(parameters = arrayOf(param("X-Id", ParamLocation.HEADER))))
        assertThat(SqliJobValidator.validate(job)).anyMatch { it.contains("testable parameter") }
    }

    @Test
    fun `non idempotent endpoints do not make the job invalid`() {
        assertThat(SqliJobValidator.validate(TestJobs.job(endpoint(idempotent = false)))).isEmpty()
    }
}
