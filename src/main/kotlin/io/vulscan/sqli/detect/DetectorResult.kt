package io.vulscan.sqli.detect

import io.vulscan.kit.contracts.DbmsHint
import io.vulscan.kit.contracts.Verdict
import io.vulscan.sqli.http.Exchange

/** Какой метод обнаружения дал этот результат (используется Q-6 `SqliClassifier` для комбинирования). */
enum class DetectionMethod { ERROR_BASED, BOOLEAN_BASED, TIME_BASED }

/**
 * Итог одного метода обнаружения для одного параметра.
 * `verdict == null` значит «метод не дал находки» — либо нечего сообщать, либо `inconclusiveReason` заполнен
 * (сигнатура уже была в baseline, нестабильный baseline и т.п. — результат не считается отрицательным тестом,
 * только недоказанным).
 */
data class DetectorResult(
    val method: DetectionMethod,
    val verdict: Verdict?,
    val dbms: DbmsHint? = null,
    val matchedSignatureIds: List<String> = emptyList(),
    /** Проба, вызвавшая кандидат-сигнатуру (или первый T/F запрос для boolean-based). */
    val candidateExchange: Exchange? = null,
    /** «Экранированная пара»/противоположное условие — должна выглядеть как baseline. */
    val controlExchange: Exchange? = null,
    val baselineSimilarity: Double? = null,
    val similarityThreshold: Double? = null,
    val inconclusiveReason: String? = null,
    /** Проба вернула 500 без единой сигнатуры — не finding, но стоит учесть в статистике задания. */
    val http500WithoutSignature: Boolean = false,
)
