package io.vulscan.sqli.job

import io.vulscan.kit.contracts.ScanJobPayload
import io.vulscan.sqli.plan.ParameterSupport

/** Проверки, специфичные для SQLi-модуля (общие проверки задания выполняет рантайм). */
object SqliJobValidator {
    /** Time-based проверки долгие: без достаточного срока задание отклоняется. */
    const val MIN_DURATION_FOR_TIME_SEC = 300

    fun validate(job: ScanJobPayload): List<String> {
        val sqli = job.moduleOptions.sqli ?: return listOf("moduleOptions.sqli is missing")
        val violations = mutableListOf<String>()
        if (!sqli.errorBased && !sqli.booleanBased && !sqli.timeBased) {
            violations += "at least one of sqli.errorBased / sqli.booleanBased / sqli.timeBased must be enabled"
        }
        if (sqli.timeBased && job.limits.maxDurationSec < MIN_DURATION_FOR_TIME_SEC) {
            violations += "sqli.timeBased requires limits.maxDurationSec >= $MIN_DURATION_FOR_TIME_SEC (got ${job.limits.maxDurationSec})"
        }
        val testable = job.endpoints.any { endpoint ->
            endpoint.parameters.any { ParameterSupport.unsupportedReason(endpoint, it) == null }
        }
        if (!testable) {
            violations += "no endpoint has a testable parameter (QUERY, BODY_FORM, BODY_JSON or PATH with a {placeholder})"
        }
        return violations
    }
}
