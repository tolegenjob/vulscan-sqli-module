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

class BooleanBasedDetectorTest {
    // Общий «наполнитель» с запасом общих слов, чтобы единственный меняющийся счётчик просмотров
    // не утягивал Jaccard-сходство ниже порога (сценарий 5).
    private val filler = "это обычная карточка товара тестового лабораторного стенда номер один два три четыре пять шесть семь восемь девять десять"
    private fun page(body: String, counter: Int? = null) =
        "<html><body><h1>Товар</h1><p>$filler $body${counter?.let { " просмотров $it" } ?: ""}</p></body></html>"

    private val foundPage = page("Найден 1 товар: Клавиатура Lab 100")
    private val notFoundPage = page("Ничего не найдено")

    // Отдельная, совсем не похожая на found/notFound страница (без общего «наполнителя») — так контроль
    // гарантированно отличим от обеих веток, а не только чуть ниже порога.
    private val errorPage = "<html><body><h1>Ошибка 500</h1><p>Внутренняя ошибка сервера при обработке запроса.</p></body></html>"

    private fun idValue(request: ProbeRequest): String = request.url.toHttpUrl().queryParameter("id") ?: ""

    private fun run(app: (ProbeRequest) -> ProbeResponse): Pair<DetectorResult, TestRun> {
        val endpoint = TestJobs.endpoint()
        val target = ProbeTarget(endpoint, endpoint.parameters.first(), ValueType.NUMERIC)
        val run = TestRun(TestJobs.job(endpoint), app)
        val sender = ProbeSender(run.http, Sleeper { })
        val baseline = BaselineCollector(sender).collect(target)
        val result = BooleanBasedDetector(sender).detect(target, baseline)
        return result to run
    }

    @Test
    fun `genuine boolean behaviour on both pairs with a clean control -- LIKELY`() {
        val (result, run) = run { req ->
            when (idValue(req)) {
                "42", "42 AND 1=1", "42 AND 2=2" -> TestJobs.response(foundPage)
                "42 AND 1=2", "42 AND 2=3" -> TestJobs.response(notFoundPage)
                "42 AND 1=1x" -> TestJobs.response(errorPage, status = 500)
                else -> TestJobs.response(foundPage)
            }
        }
        assertThat(result.method).isEqualTo(DetectionMethod.BOOLEAN_BASED)
        assertThat(result.verdict).isEqualTo(Verdict.LIKELY)
        assertThat(run.requests.none { it.url.contains("OR", ignoreCase = true) }).isTrue()
        // 2 пары * 2 повтора * (T+F) + 1 контроль = 9, плюс 3 на baseline.
        assertThat(run.requests).hasSize(12)
    }

    @Test
    fun `an application that ignores the value entirely produces no finding`() {
        val (result, _) = run { TestJobs.response(foundPage) }
        assertThat(result.verdict).isNull()
    }

    @Test
    fun `an unstable baseline is inconclusive and spends no probe budget`() {
        val calls = AtomicInteger()
        val (result, run) = run {
            val n = calls.getAndIncrement()
            val block = (0 until 30).joinToString(" ") { "wrd${n * 30 + it}" }
            TestJobs.response("<html><body>$block</body></html>")
        }
        assertThat(result.verdict).isNull()
        assertThat(result.inconclusiveReason).isEqualTo("UNSTABLE_BASELINE")
        assertThat(run.requests).hasSize(3) // только baseline — ни одной boolean-пробы
    }

    @Test
    fun `an app that rejects quotes with the same input-error page for T and F produces no finding`() {
        val (result, _) = run { req ->
            if (idValue(req) == "42") TestJobs.response(foundPage) else TestJobs.response(errorPage, status = 400)
        }
        assertThat(result.verdict).isNull()
    }

    @Test
    fun `a view-counter difference on the true branch does not defeat detection`() {
        // baseline остаётся стабильным (фиксированный счётчик — минимальное сходство должно быть ≥ 0.98);
        // шум добавляется только на «истинной» ветке, которую нужно всё равно распознать как похожую на baseline.
        val noisyCounter = AtomicInteger(100)
        val (result, _) = run { req ->
            when (idValue(req)) {
                "42" -> TestJobs.response(page("Найден 1 товар: Клавиатура Lab 100", counter = 1))
                "42 AND 1=1", "42 AND 2=2" -> TestJobs.response(page("Найден 1 товар: Клавиатура Lab 100", noisyCounter.getAndIncrement()))
                "42 AND 1=2", "42 AND 2=3" -> TestJobs.response(notFoundPage)
                "42 AND 1=1x" -> TestJobs.response(errorPage, status = 500)
                else -> TestJobs.response(page("Найден 1 товар: Клавиатура Lab 100", counter = 1))
            }
        }
        assertThat(result.verdict).isEqualTo(Verdict.LIKELY)
    }

    @Test
    fun `a control that reproduces the false branch produces no finding`() {
        val (result, _) = run { req ->
            when (idValue(req)) {
                "42", "42 AND 1=1", "42 AND 2=2" -> TestJobs.response(foundPage)
                "42 AND 1=2", "42 AND 2=3" -> TestJobs.response(notFoundPage)
                "42 AND 1=1x" -> TestJobs.response(notFoundPage) // контроль выглядит точно как F
                else -> TestJobs.response(foundPage)
            }
        }
        assertThat(result.verdict).isNull()
    }

    @Test
    fun `only one of the two pairs confirms -- SUSPECTED`() {
        val (result, _) = run { req ->
            when (idValue(req)) {
                "42", "42 AND 1=1" -> TestJobs.response(foundPage)
                "42 AND 1=2" -> TestJobs.response(notFoundPage)
                // Вторая пара (2=2 / 2=3) приложением не поддерживается — всегда «найдено».
                "42 AND 2=2", "42 AND 2=3" -> TestJobs.response(foundPage)
                else -> TestJobs.response(foundPage)
            }
        }
        assertThat(result.verdict).isEqualTo(Verdict.SUSPECTED)
    }
}
