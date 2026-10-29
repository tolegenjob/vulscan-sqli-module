package io.vulscan.sqli.report

import io.vulscan.kit.contracts.Evidence
import io.vulscan.kit.contracts.HttpRequestEvidence
import io.vulscan.kit.contracts.HttpResponseEvidence
import io.vulscan.kit.contracts.Observation
import io.vulscan.kit.redact.Redactor
import io.vulscan.sqli.baseline.Baseline
import io.vulscan.sqli.http.Exchange

/**
 * Собирает [Evidence] из [SqliClassification] (Q-6). Контракт несёт только одну пару запрос/ответ —
 * ей становится решающая проба [SqliClassification.primary] (ошибка СУБД / T-ветка / sleep(N)-проба);
 * baseline, контроль и сведения об остальных подтвердивших методах — в [Observation]. Повторное
 * маскирование секретов и усечение тела делает [io.vulscan.kit.worker.FindingSanitizer] перед публикацией.
 */
object SqliEvidenceBuilder {
    private const val MAX_BODY_EXCERPT = 2048

    fun build(classification: SqliClassification, baseline: Baseline): Evidence {
        val exchange = classification.primary.candidateExchange
        val request = requestEvidence(exchange)
        val response = exchange?.let { responseEvidence(it) }
        return Evidence(request, response, observations(classification, baseline))
    }

    private fun requestEvidence(exchange: Exchange?): HttpRequestEvidence = HttpRequestEvidence(
        method = exchange?.request?.method ?: io.vulscan.kit.contracts.HttpMethod.GET,
        url = exchange?.request?.url ?: "",
        headers = Redactor.headers(exchange?.request?.headers ?: emptyMap()),
    )

    private fun responseEvidence(exchange: Exchange): HttpResponseEvidence = HttpResponseEvidence(
        status = exchange.status,
        headers = Redactor.headers(exchange.response.headers),
        bodyExcerpt = Redactor.excerpt(exchange.body, MAX_BODY_EXCERPT),
    )

    private fun observations(classification: SqliClassification, baseline: Baseline): List<Observation> {
        val primary = classification.primary
        val control = primary.controlExchange
        return listOf(
            Observation("vulnType", classification.vulnType.name),
            Observation("verdict", classification.verdict.name),
            Observation("contributingMethods", classification.contributing.joinToString(",") { it.method.name }),
            Observation("dbms", primary.dbms?.name ?: "unknown"),
            Observation("matchedSignatures", primary.matchedSignatureIds.joinToString(",").ifEmpty { "none" }),
            Observation("baselineStatus", baseline.signature.status.toString()),
            Observation("baselineSimilarity", primary.baselineSimilarity?.let { "%.2f".format(it) } ?: "n/a"),
            Observation("similarityThreshold", "%.2f".format(primary.similarityThreshold ?: baseline.theta)),
            Observation("controlStatus", control?.status?.toString() ?: "n/a"),
        )
    }
}
