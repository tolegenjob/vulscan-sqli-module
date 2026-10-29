package io.vulscan.sqli.report

import io.vulscan.kit.contracts.EndpointRef
import io.vulscan.kit.contracts.FindingPayload
import io.vulscan.kit.contracts.Fingerprint
import io.vulscan.kit.contracts.ModuleType
import io.vulscan.kit.contracts.ParameterId
import io.vulscan.kit.contracts.ParameterRef
import io.vulscan.kit.contracts.SqliDetails
import io.vulscan.kit.contracts.Verdict
import io.vulscan.kit.contracts.VulnType
import io.vulscan.sqli.baseline.Baseline
import io.vulscan.sqli.detect.DetectorResult

private const val CWE_SQL_INJECTION = "CWE-89"
private const val RECOMMENDATION =
    "Используйте параметризованные запросы (PreparedStatement) или ORM вместо конкатенации строк для " +
        "построения SQL. Не формируйте запрос из пользовательского ввода даже для числовых параметров — " +
        "валидация типа не защищает от инъекции в текстовых местах запроса. Ограничьте права учётной записи " +
        "СУБД, используемой приложением, принципом наименьших привилегий."

/**
 * Собирает [FindingPayload] из результатов детекторов Q-3…Q-5 для одного (endpoint, parameter) (Q-6 ТЗ).
 * Возвращает `null`, если ни один метод не дал находки — см. [SqliClassifier].
 */
object SqliFindingFactory {
    fun create(endpoint: EndpointRef, parameter: ParameterRef, results: List<DetectorResult>, baseline: Baseline): FindingPayload? {
        val classification = SqliClassifier.classify(results) ?: return null

        val dbmsKnown = classification.contributing.any { it.dbms != null }
        val anyUnstableBaseline = results.any { it.inconclusiveReason == "UNSTABLE_BASELINE" }
        val anyPartialControlMatch = classification.contributing.any { it.verdict == Verdict.SUSPECTED }
        val confidence = SqliConfidenceScorer.score(classification.verdict, dbmsKnown, anyUnstableBaseline, anyPartialControlMatch)
        val severity = SqliSeverityPolicy.severity(classification.verdict, classification.vulnType)

        val fingerprint = Fingerprint.of("SQLI", endpoint.method.name, endpoint.url, parameter.location.name, parameter.name)
        val dbms = classification.contributing.firstNotNullOfOrNull { it.dbms }
        val methodsConfirmed = classification.contributing
            .filter { it.verdict == Verdict.LIKELY || it.verdict == Verdict.CONFIRMED }
            .map { it.method.name }

        return FindingPayload(
            moduleType = ModuleType.SQLI,
            vulnType = classification.vulnType,
            cwe = CWE_SQL_INJECTION,
            endpointId = endpoint.endpointId,
            method = endpoint.method,
            url = endpoint.url,
            parameter = ParameterId(parameter.name, parameter.location),
            verdict = classification.verdict,
            severity = severity,
            confidence = confidence,
            fingerprint = fingerprint,
            title = title(classification.vulnType),
            summary = summary(endpoint, parameter, classification.vulnType),
            recommendation = RECOMMENDATION,
            details = SqliDetails(
                type = classification.vulnType,
                dbms = dbms?.name,
                baselineSimilarity = classification.primary.baselineSimilarity,
                similarityThreshold = classification.primary.similarityThreshold ?: baseline.theta,
                matchedSignature = classification.primary.matchedSignatureIds.firstOrNull(),
                methodsConfirmed = methodsConfirmed,
            ),
            evidence = SqliEvidenceBuilder.build(classification, baseline),
        )
    }

    private fun title(vulnType: VulnType): String = when (vulnType) {
        VulnType.SQLI_ERROR_BASED -> "SQL-инъекция (error-based)"
        VulnType.SQLI_BOOLEAN_BASED -> "SQL-инъекция (boolean-based)"
        VulnType.SQLI_TIME_BASED -> "SQL-инъекция (time-based)"
        else -> "SQL-инъекция"
    }

    private fun summary(endpoint: EndpointRef, parameter: ParameterRef, vulnType: VulnType): String {
        val method = when (vulnType) {
            VulnType.SQLI_ERROR_BASED -> "ответ содержит сигнатуру ошибки СУБД, воспроизводимо зависящую от значения параметра"
            VulnType.SQLI_BOOLEAN_BASED -> "ответ воспроизводимо различается в зависимости от истинности внедрённого логического условия"
            VulnType.SQLI_TIME_BASED -> "время ответа воспроизводимо зависит от внедрённой задержки выполнения SQL"
            else -> "обнаружена SQL-инъекция"
        }
        return "Параметр «${parameter.name}» (${parameter.location}) эндпоинта ${endpoint.method} ${endpoint.url} " +
            "управляет синтаксисом SQL-запроса: $method."
    }
}
