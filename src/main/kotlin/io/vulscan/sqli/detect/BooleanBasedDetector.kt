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
import kotlin.random.Random

/**
 * Boolean-based SQLi (Q-4): две независимые пары логически противоположных условий; каждая пара
 * отправляется дважды (T/F в случайном порядке — отсекает эффект кэширования/счётчиков). Обе пары
 * подтверждены → контроль «синтаксически невалидное, но не логическое» значение: должен быть
 * отличим и от T, и от F (иначе похоже, что приложение просто нормализует ввод или любое «сломанное»
 * значение превращает в generic-ошибку, а F — не настоящая логическая ветка SQL).
 */
class BooleanBasedDetector(
    private val sender: ProbeSender,
    private val random: Random = Random.Default,
) {
    fun detect(target: ProbeTarget, baseline: Baseline, onAttempt: () -> Unit = {}): DetectorResult {
        if (!baseline.stable) {
            return DetectorResult(DetectionMethod.BOOLEAN_BASED, verdict = null, inconclusiveReason = "UNSTABLE_BASELINE")
        }

        val pairs = BooleanProbeSets.pairs(target.valueType, baseline.sampleValue)
        val confirmations = pairs.map { confirmPair(target, baseline, it, onAttempt) }
        val confirmedCount = confirmations.count { it.confirmed }

        if (confirmedCount == 0) return DetectorResult(DetectionMethod.BOOLEAN_BASED, verdict = null)

        if (confirmedCount == 1) {
            val c = confirmations.first { it.confirmed }
            return DetectorResult(
                method = DetectionMethod.BOOLEAN_BASED,
                verdict = Verdict.SUSPECTED,
                candidateExchange = c.trueExchange,
                controlExchange = c.falseExchange,
                baselineSimilarity = c.trueSimilarity,
                similarityThreshold = baseline.theta,
            )
        }

        val controlValue = BooleanProbeSets.control(target.valueType, baseline.sampleValue)
        val controlExchange = exchange(target, controlValue, onAttempt)
        val controlSignature = signatureOf(controlExchange)
        val reference = confirmations.first()
        val controlVsT = ResponseComparator.similarity(controlSignature, reference.trueSignature)
        val controlVsF = ResponseComparator.similarity(controlSignature, reference.falseSignature)
        val controlOk = controlVsT < baseline.theta && controlVsF < baseline.theta

        if (!controlOk) return DetectorResult(DetectionMethod.BOOLEAN_BASED, verdict = null)

        return DetectorResult(
            method = DetectionMethod.BOOLEAN_BASED,
            verdict = Verdict.LIKELY,
            candidateExchange = reference.trueExchange,
            controlExchange = controlExchange,
            baselineSimilarity = reference.trueSimilarity,
            similarityThreshold = baseline.theta,
        )
    }

    private class PairConfirmation(
        val confirmed: Boolean,
        val trueExchange: Exchange,
        val falseExchange: Exchange,
        val trueSignature: ResponseSignature,
        val falseSignature: ResponseSignature,
        val trueSimilarity: Double,
    )

    /** Условие пары: оба повтора T похожи на baseline, оба повтора F — нет, и T отличим от F в обоих повторах. */
    private fun confirmPair(target: ProbeTarget, baseline: Baseline, pair: BooleanPair, onAttempt: () -> Unit): PairConfirmation {
        val round1 = sendBoth(target, pair, onAttempt)
        val round2 = sendBoth(target, pair, onAttempt)

        val t1 = signatureOf(round1.first)
        val f1 = signatureOf(round1.second)
        val t2 = signatureOf(round2.first)
        val f2 = signatureOf(round2.second)

        val simT1 = ResponseComparator.similarity(t1, baseline.signature)
        val simT2 = ResponseComparator.similarity(t2, baseline.signature)
        val simF1 = ResponseComparator.similarity(f1, baseline.signature)
        val simF2 = ResponseComparator.similarity(f2, baseline.signature)
        val simTF1 = ResponseComparator.similarity(t1, f1)
        val simTF2 = ResponseComparator.similarity(t2, f2)

        val confirmed = simT1 >= baseline.theta && simT2 >= baseline.theta &&
            simF1 < baseline.theta && simF2 < baseline.theta &&
            simTF1 < baseline.theta && simTF2 < baseline.theta

        return PairConfirmation(confirmed, round1.first, round1.second, t1, f1, simT1)
    }

    /** Порядок T/F рандомизируется на каждый повтор. */
    private fun sendBoth(target: ProbeTarget, pair: BooleanPair, onAttempt: () -> Unit): Pair<Exchange, Exchange> =
        if (random.nextBoolean()) {
            val t = exchange(target, pair.trueValue, onAttempt)
            val f = exchange(target, pair.falseValue, onAttempt)
            t to f
        } else {
            val f = exchange(target, pair.falseValue, onAttempt)
            val t = exchange(target, pair.trueValue, onAttempt)
            t to f
        }

    private fun signatureOf(exchange: Exchange): ResponseSignature =
        ResponseSignature.of(exchange.status, HttpText.contentType(exchange.response), exchange.body, exchange.value)

    private fun exchange(target: ProbeTarget, value: String, onAttempt: () -> Unit): Exchange {
        val request = RequestBuilder.build(target.endpoint, target.parameter, value)
        val response = sender.send(request, onAttempt)
        return Exchange(value, request, response, HttpText.decode(response))
    }
}
