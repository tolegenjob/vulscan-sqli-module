package io.vulscan.sqli.detect

import io.vulscan.kit.contracts.Verdict
import io.vulscan.sqli.baseline.Baseline
import io.vulscan.sqli.compare.ResponseComparator
import io.vulscan.sqli.compare.ResponseSignature
import io.vulscan.sqli.http.Exchange
import io.vulscan.sqli.http.HttpText
import io.vulscan.sqli.http.ProbeSender
import io.vulscan.sqli.http.RequestBuilder
import io.vulscan.sqli.plan.ProbeTarget

/**
 * Error-based SQLi (Q-3): ищет пробу, после которой в ответе появляется сигнатура ошибки СУБД,
 * которой не было в baseline, подтверждает «чистым» контролем (экранированная пара символа пробы)
 * и повторяет пробу — только тогда допускает `LIKELY`/`CONFIRMED`.
 *
 * Символы пробы — `'`, `"`, `\` — пробуются по очереди (план требует это для STRING и NUMERIC
 * одинаково: числовой контекст ломается кавычкой так же, как строковый). Контроль обобщён на все три
 * символа как «экранированная пара» (план описывает её явно только для `'`/STRING).
 */
class ErrorBasedDetector(private val sender: ProbeSender) {

    fun detect(target: ProbeTarget, baseline: Baseline, onAttempt: () -> Unit = {}): DetectorResult {
        val baselineMatches = baseline.bodies.flatMap { ErrorSignatureCatalog.matches(it) }
        if (baselineMatches.isNotEmpty()) {
            return DetectorResult(DetectionMethod.ERROR_BASED, verdict = null, inconclusiveReason = "SIGNATURE_IN_BASELINE")
        }

        var any500 = false
        for (suffix in PROBE_SUFFIXES) {
            val probe = exchange(target, baseline.sampleValue + suffix, onAttempt)
            if (probe.status == 500) any500 = true
            val matches = ErrorSignatureCatalog.matches(probe.body)
            if (matches.isEmpty()) continue

            val control = exchange(target, baseline.sampleValue + suffix + suffix, onAttempt)
            val controlMatches = ErrorSignatureCatalog.matches(control.body)
            val controlSignature = ResponseSignature.of(control.status, HttpText.contentType(control.response), control.body, control.value)
            val similarity = ResponseComparator.similarity(controlSignature, baseline.signature)
            val controlClean = controlMatches.isEmpty() && similarity >= baseline.theta

            if (!controlClean) {
                return DetectorResult(
                    method = DetectionMethod.ERROR_BASED,
                    verdict = Verdict.SUSPECTED,
                    dbms = DbmsFingerprint.of(matches),
                    matchedSignatureIds = matches.map { it.id },
                    candidateExchange = probe,
                    controlExchange = control,
                    baselineSimilarity = similarity,
                    similarityThreshold = baseline.theta,
                )
            }

            val repeat = exchange(target, baseline.sampleValue + suffix, onAttempt)
            val repeatMatches = ErrorSignatureCatalog.matches(repeat.body)
            if (repeatMatches.isEmpty()) {
                // Не воспроизвелось на втором запросе — слишком ненадёжно для finding.
                return DetectorResult(DetectionMethod.ERROR_BASED, verdict = null)
            }

            val dbms = DbmsFingerprint.of(matches)
            val weight = matches.sumOf { it.weight }
            val verdict = if (dbms != null && weight >= CONFIRMED_WEIGHT_THRESHOLD) Verdict.CONFIRMED else Verdict.LIKELY
            return DetectorResult(
                method = DetectionMethod.ERROR_BASED,
                verdict = verdict,
                dbms = dbms,
                matchedSignatureIds = matches.map { it.id },
                candidateExchange = probe,
                controlExchange = control,
                baselineSimilarity = similarity,
                similarityThreshold = baseline.theta,
            )
        }

        return DetectorResult(DetectionMethod.ERROR_BASED, verdict = null, http500WithoutSignature = any500)
    }

    private fun exchange(target: ProbeTarget, value: String, onAttempt: () -> Unit): Exchange {
        val request = RequestBuilder.build(target.endpoint, target.parameter, value)
        val response = sender.send(request, onAttempt)
        return Exchange(value, request, response, HttpText.decode(response))
    }

    companion object {
        val PROBE_SUFFIXES = listOf("'", "\"", "\\")
        /** Сумма весов сигнатур, начиная с которой при однозначно определённой СУБД verdict = CONFIRMED. */
        const val CONFIRMED_WEIGHT_THRESHOLD = 7
    }
}
