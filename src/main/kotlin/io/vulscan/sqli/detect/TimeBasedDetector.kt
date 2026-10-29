package io.vulscan.sqli.detect

import io.vulscan.kit.contracts.DbmsHint
import io.vulscan.kit.contracts.Verdict
import io.vulscan.sqli.config.SqliProperties
import io.vulscan.sqli.http.Exchange
import io.vulscan.sqli.http.HttpText
import io.vulscan.sqli.http.ProbeSender
import io.vulscan.sqli.http.RequestBuilder
import io.vulscan.sqli.http.Sleeper
import io.vulscan.sqli.plan.ProbeTarget

/**
 * Time-based SQLi (Q-5, включается явно). Требует уже известную СУБД (`dbmsHint` либо
 * [DbmsFingerprint] из error-based) — «перебор всех СУБД» запрещён. Последовательность на параметр:
 * 5 замеров базовой латентности (без задержки) → проба `sleep(N)` → контроль `sleep(0)` →
 * проба `sleep(2N)`, строго последовательно, с паузой ≥ N между пробами; подтверждение требует
 * повторения всей последовательности — одна задержка сама по себе не доказательство.
 */
class TimeBasedDetector(
    private val sender: ProbeSender,
    private val sleeper: Sleeper,
    private val properties: SqliProperties = SqliProperties(),
    private val budget: TimeProbeBudget = TimeProbeBudget(Int.MAX_VALUE),
) {
    fun detect(target: ProbeTarget, dbms: DbmsHint?, onAttempt: () -> Unit = {}): DetectorResult {
        if (dbms == null || !TimeProbeCatalog.supports(dbms)) {
            return DetectorResult(DetectionMethod.TIME_BASED, verdict = null, inconclusiveReason = "DBMS_UNKNOWN_OR_UNSUPPORTED")
        }
        if (!budget.reserve(SLEEP_REQUESTS_PER_ROUND)) {
            return DetectorResult(DetectionMethod.TIME_BASED, verdict = null, inconclusiveReason = "TIME_PROBE_BUDGET_EXCEEDED")
        }

        val latencies = (1..properties.latencySamples).map {
            exchange(target, target.parameter.sampleValue ?: "1", onAttempt).elapsedMs
        }
        val profile = LatencyModel.profile(latencies)
        if (profile.highJitter) {
            return DetectorResult(DetectionMethod.TIME_BASED, verdict = null, inconclusiveReason = "HIGH_JITTER")
        }

        val delaySec = properties.timeDelaySec.coerceAtMost(properties.timeDelayMaxSec)
        val round1 = sleepSequence(target, dbms, delaySec, onAttempt) ?: return DetectorResult(DetectionMethod.TIME_BASED, verdict = null)
        if (!round1.satisfies(profile, delaySec)) {
            return DetectorResult(DetectionMethod.TIME_BASED, verdict = null)
        }

        if (!budget.reserve(SLEEP_REQUESTS_PER_ROUND)) {
            // Повтора не хватило бюджета — одного раунда недостаточно для finding.
            return DetectorResult(DetectionMethod.TIME_BASED, verdict = null, inconclusiveReason = "TIME_PROBE_BUDGET_EXCEEDED")
        }
        val round2 = sleepSequence(target, dbms, delaySec, onAttempt) ?: return DetectorResult(DetectionMethod.TIME_BASED, verdict = null)
        if (!round2.satisfies(profile, delaySec)) {
            return DetectorResult(DetectionMethod.TIME_BASED, verdict = null)
        }

        return DetectorResult(
            method = DetectionMethod.TIME_BASED,
            verdict = Verdict.LIKELY,
            dbms = dbms,
            candidateExchange = round1.sleepN,
            controlExchange = round1.control,
        )
    }

    private class SleepRound(val sleepN: Exchange, val control: Exchange, val sleep2N: Exchange) {
        fun satisfies(profile: LatencyModel.Profile, delaySec: Int): Boolean {
            val n1000 = delaySec * 1000.0
            val t1 = sleepN.elapsedMs
            val t0 = control.elapsedMs
            val t2 = sleep2N.elapsedMs
            return t1 >= profile.median + 0.8 * n1000 &&
                t1 > profile.median + 5 * profile.mad &&
                t0 <= profile.median + 3 * profile.mad &&
                t2 - t1 >= 0.6 * n1000
        }
    }

    /** null означает «прервано»: один из ответов 5xx — параметр больше не проверяется. */
    private fun sleepSequence(target: ProbeTarget, dbms: DbmsHint, delaySec: Int, onAttempt: () -> Unit): SleepRound? {
        val sleepN = sleepProbe(target, dbms, delaySec, onAttempt) ?: return null
        sleeper.sleep(delaySec * 1000L)
        val control = sleepProbe(target, dbms, 0, onAttempt) ?: return null
        sleeper.sleep(delaySec * 1000L)
        val sleep2N = sleepProbe(target, dbms, delaySec * 2, onAttempt) ?: return null
        return SleepRound(sleepN, control, sleep2N)
    }

    /** null означает 5xx/сбой — вызывающий код прерывает проверку параметра. */
    private fun sleepProbe(target: ProbeTarget, dbms: DbmsHint, delaySeconds: Int, onAttempt: () -> Unit): Exchange? {
        val value = TimeProbeCatalog.probe(dbms, target.valueType, target.parameter.sampleValue ?: "1", delaySeconds)
        val result = exchange(target, value, onAttempt)
        return if (result.status >= 500) null else result
    }

    private fun exchange(target: ProbeTarget, value: String, onAttempt: () -> Unit): Exchange {
        val request = RequestBuilder.build(target.endpoint, target.parameter, value)
        val response = sender.send(request, onAttempt)
        return Exchange(value, request, response, HttpText.decode(response))
    }

    companion object {
        const val SLEEP_REQUESTS_PER_ROUND = 3
    }
}
