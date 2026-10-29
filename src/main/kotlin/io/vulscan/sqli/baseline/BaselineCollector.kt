package io.vulscan.sqli.baseline

import io.vulscan.sqli.compare.ResponseComparator
import io.vulscan.sqli.compare.ResponseSignature
import io.vulscan.sqli.http.Exchange
import io.vulscan.sqli.http.HttpText
import io.vulscan.sqli.http.ProbeSender
import io.vulscan.sqli.http.RequestBuilder
import io.vulscan.sqli.plan.ProbeTarget

/** Собирает несколько одинаковых запросов с примерным значением параметра и оценивает стабильность ответа. */
class BaselineCollector(private val sender: ProbeSender, private val samples: Int = 3) {
    fun collect(target: ProbeTarget, onAttempt: () -> Unit = {}): Baseline {
        val value = target.parameter.sampleValue?.takeIf { it.isNotEmpty() } ?: DEFAULT_VALUE
        val request = RequestBuilder.build(target.endpoint, target.parameter, value)
        val exchanges = List(samples) {
            val response = sender.send(request, onAttempt)
            Exchange(value, request, response, HttpText.decode(response))
        }
        val signatures = exchanges.map { ResponseSignature.of(it.status, HttpText.contentType(it.response), it.body, value) }

        var minimum = 1.0
        for (i in signatures.indices) {
            for (j in i + 1 until signatures.size) minimum = minOf(minimum, ResponseComparator.similarity(signatures[i], signatures[j]))
        }
        val sameStatus = signatures.all { it.status == signatures.first().status }
        val latencies = exchanges.map { it.elapsedMs }
        return Baseline(
            signature = signatures.first(),
            minPairwiseSimilarity = minimum,
            medianLatencyMs = LatencyStats.median(latencies),
            madLatencyMs = LatencyStats.mad(latencies),
            stable = sameStatus && minimum >= Baseline.STABLE_SIMILARITY,
            sampleValue = value,
            first = exchanges.first(),
            bodies = exchanges.map { it.body },
        )
    }

    companion object {
        const val DEFAULT_VALUE = "1"
    }
}
