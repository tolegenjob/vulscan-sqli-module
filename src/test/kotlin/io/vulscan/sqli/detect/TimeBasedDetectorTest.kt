package io.vulscan.sqli.detect

import io.vulscan.kit.contracts.DbmsHint
import io.vulscan.kit.contracts.Verdict
import io.vulscan.kit.net.ProbeRequest
import io.vulscan.kit.net.ProbeResponse
import io.vulscan.sqli.TestJobs
import io.vulscan.sqli.TestRun
import io.vulscan.sqli.config.SqliProperties
import io.vulscan.sqli.http.ProbeSender
import io.vulscan.sqli.http.Sleeper
import io.vulscan.sqli.plan.ProbeTarget
import io.vulscan.sqli.plan.ValueType
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class TimeBasedDetectorTest {
    private val n3 = TimeProbeCatalog.probe(DbmsHint.POSTGRESQL, ValueType.NUMERIC, "42", 3)
    private val n0 = TimeProbeCatalog.probe(DbmsHint.POSTGRESQL, ValueType.NUMERIC, "42", 0)
    private val n6 = TimeProbeCatalog.probe(DbmsHint.POSTGRESQL, ValueType.NUMERIC, "42", 6)

    private fun idValue(request: ProbeRequest): String = request.url.toHttpUrl().queryParameter("id") ?: ""

    private fun target(): ProbeTarget {
        val endpoint = TestJobs.endpoint()
        return ProbeTarget(endpoint, endpoint.parameters.first(), ValueType.NUMERIC)
    }

    private fun run(
        budget: TimeProbeBudget = TimeProbeBudget(Int.MAX_VALUE),
        app: (ProbeRequest) -> ProbeResponse,
    ): Pair<DetectorResult, TestRun> {
        val t = target()
        val run = TestRun(TestJobs.job(t.endpoint), app)
        val sender = ProbeSender(run.http, Sleeper { })
        val result = TimeBasedDetector(sender, Sleeper { }, SqliProperties(), budget).detect(t, DbmsHint.POSTGRESQL)
        return result to run
    }

    @Test
    fun `delay proportional to N -- LIKELY`() {
        val (result, run) = run { req ->
            when (idValue(req)) {
                n3 -> TestJobs.response("ok", elapsedMs = 3010)
                n0 -> TestJobs.response("ok", elapsedMs = 10)
                n6 -> TestJobs.response("ok", elapsedMs = 6010)
                else -> TestJobs.response("ok", elapsedMs = 10)
            }
        }
        assertThat(result.method).isEqualTo(DetectionMethod.TIME_BASED)
        assertThat(result.verdict).isEqualTo(Verdict.LIKELY)
        assertThat(result.dbms).isEqualTo(DbmsHint.POSTGRESQL)
        // 5 замеров латентности + 2 раунда * 3 пробы = 11.
        assertThat(run.requests).hasSize(11)
    }

    @Test
    fun `a constantly slow page, independent of input, is high jitter and produces no finding`() {
        val (result, _) = run { TestJobs.response("ok", elapsedMs = 3000) }
        assertThat(result.verdict).isNull()
        assertThat(result.inconclusiveReason).isEqualTo("HIGH_JITTER")
    }

    @Test
    fun `a single random 3s spike that does not reproduce on the second round is no finding`() {
        // Первый раунд выглядит как настоящая SQLi-задержка (проходит все условия), но второй раунд
        // (повтор) показывает обычную быструю реакцию — случайный всплеск, а не воспроизводимая задержка.
        val n3Calls = AtomicInteger()
        val (result, _) = run { req ->
            when (idValue(req)) {
                n3 -> if (n3Calls.getAndIncrement() == 0) TestJobs.response("ok", elapsedMs = 3010) else TestJobs.response("ok", elapsedMs = 10)
                n0 -> TestJobs.response("ok", elapsedMs = 10)
                n6 -> TestJobs.response("ok", elapsedMs = 6010)
                else -> TestJobs.response("ok", elapsedMs = 10)
            }
        }
        assertThat(result.verdict).isNull()
    }

    @Test
    fun `an unknown dbms skips the time-based check entirely`() {
        val t = target()
        val run = TestRun(TestJobs.job(t.endpoint)) { TestJobs.response("ok", elapsedMs = 10) }
        val sender = ProbeSender(run.http, Sleeper { })
        val result = TimeBasedDetector(sender, Sleeper { }).detect(t, dbms = null)
        assertThat(result.verdict).isNull()
        assertThat(result.inconclusiveReason).isEqualTo("DBMS_UNKNOWN_OR_UNSUPPORTED")
        assertThat(run.requests).isEmpty()
    }

    @Test
    fun `exceeding maxTimeProbesPerJob leaves the remaining parameter without a single request`() {
        val sharedBudget = TimeProbeBudget(3) // ровно один раунд из трёх проб
        val app: (ProbeRequest) -> ProbeResponse = { req ->
            when (idValue(req)) {
                n3 -> TestJobs.response("ok", elapsedMs = 3010)
                n0 -> TestJobs.response("ok", elapsedMs = 10)
                n6 -> TestJobs.response("ok", elapsedMs = 6010)
                else -> TestJobs.response("ok", elapsedMs = 10)
            }
        }

        val (firstResult, _) = run(sharedBudget, app)
        // Первый раунд уложился в лимит и прошёл проверку условий, но на повтор бюджета не осталось.
        assertThat(firstResult.verdict).isNull()
        assertThat(firstResult.inconclusiveReason).isEqualTo("TIME_PROBE_BUDGET_EXCEEDED")

        val (secondResult, secondRun) = run(sharedBudget, app)
        assertThat(secondResult.inconclusiveReason).isEqualTo("TIME_PROBE_BUDGET_EXCEEDED")
        assertThat(secondRun.requests).isEmpty() // второй параметр не отправил ни одного запроса вовсе
    }
}
