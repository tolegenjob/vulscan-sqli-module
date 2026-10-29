package io.vulscan.sqli.baseline

import io.vulscan.kit.net.ProbeRequest
import io.vulscan.kit.net.ProbeResponse
import io.vulscan.sqli.TestJobs
import io.vulscan.sqli.TestRun
import io.vulscan.sqli.http.ProbeSender
import io.vulscan.sqli.http.Sleeper
import io.vulscan.sqli.plan.ProbeTarget
import io.vulscan.sqli.plan.ValueType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class BaselineCollectorTest {
    private val stableBody = "<html><body><h1>Каталог</h1><ul><li>Ноутбук Acer Aspire</li><li>Мышь Logitech</li></ul><p>Обновлено 2026-10-22T10:00:00Z</p></body></html>"

    private fun collect(app: (ProbeRequest) -> ProbeResponse): Pair<Baseline, TestRun> {
        val endpoint = TestJobs.endpoint()
        val run = TestRun(TestJobs.job(endpoint), app)
        val baseline = BaselineCollector(ProbeSender(run.http, Sleeper { })).collect(ProbeTarget(endpoint, endpoint.parameters.first(), ValueType.NUMERIC))
        return baseline to run
    }

    @Test
    fun `a stable response gives a stable baseline built from three requests`() {
        val calls = AtomicInteger()
        val (baseline, run) = collect {
            val n = calls.getAndIncrement()
            TestJobs.response(stableBody.replace("2026-10-22T10:00:00Z", "2026-10-2${n}T10:00:00Z"))
        }
        assertThat(baseline.stable).isTrue()
        assertThat(baseline.minPairwiseSimilarity).isGreaterThanOrEqualTo(0.98)
        assertThat(baseline.theta).isEqualTo(0.90)
        assertThat(run.requests).hasSize(3)
        assertThat(baseline.sampleValue).isEqualTo("42")
        assertThat(run.requests.map { it.url }.distinct()).hasSize(1)
    }

    @Test
    fun `a response with a random block is not stable`() {
        val calls = AtomicInteger()
        val (baseline, _) = collect {
            val n = calls.getAndIncrement()
            val block = (0 until 30).joinToString(" ") { "wrd${n * 30 + it}" }
            TestJobs.response("<html><body><h1>Каталог</h1><p>Постоянный текст страницы магазина</p><div>$block</div></body></html>")
        }
        assertThat(baseline.stable).isFalse()
        assertThat(baseline.minPairwiseSimilarity).isLessThan(0.98)
        assertThat(baseline.theta).isLessThan(0.90)
    }

    @Test
    fun `a differing status makes the baseline unstable`() {
        val calls = AtomicInteger()
        val (baseline, _) = collect { TestJobs.response(stableBody, status = if (calls.getAndIncrement() == 2) 500 else 200) }
        assertThat(baseline.stable).isFalse()
    }

    @Test
    fun `latency median and mad come from the three samples`() {
        val latencies = listOf(100L, 120L, 110L)
        val calls = AtomicInteger()
        val (baseline, _) = collect { TestJobs.response(stableBody, elapsedMs = latencies[calls.getAndIncrement()]) }
        assertThat(baseline.medianLatencyMs).isEqualTo(110)
        assertThat(baseline.madLatencyMs).isEqualTo(10)
    }

    @Test
    fun `missing sample value falls back to one`() {
        val endpoint = TestJobs.endpoint(parameters = arrayOf(TestJobs.param("id", sample = null)))
        val run = TestRun(TestJobs.job(endpoint)) { TestJobs.response(stableBody) }
        val baseline = BaselineCollector(ProbeSender(run.http, Sleeper { })).collect(ProbeTarget(endpoint, endpoint.parameters.first(), ValueType.STRING))
        assertThat(baseline.sampleValue).isEqualTo("1")
        assertThat(run.requests.first().url).contains("id=1")
    }
}
