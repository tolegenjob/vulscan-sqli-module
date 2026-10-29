package io.vulscan.sqli.plan

import io.vulscan.kit.contracts.EndpointRef
import io.vulscan.kit.contracts.HttpMethod
import io.vulscan.kit.contracts.ParamLocation
import io.vulscan.kit.contracts.ParameterRef
import io.vulscan.kit.contracts.SkippedEndpoint
import io.vulscan.kit.contracts.SqliOptions

/** Один проверяемый параметр одного endpoint. */
data class ProbeTarget(val endpoint: EndpointRef, val parameter: ParameterRef, val valueType: ValueType)

enum class Phase { ERROR, BOOLEAN }

/** [phases] — статические проверки, на которые хватило бюджета; time-based решается во время работы (нужна известная СУБД). */
data class PlannedParameter(val target: ProbeTarget, val phases: Set<Phase>)

data class PlanResult(val parameters: List<PlannedParameter>, val skipped: List<SkippedEndpoint>)

/** Верхние оценки числа запросов на параметр; по ним планируется бюджет. */
object RequestCosts {
    const val ERROR = 5
    const val BOOLEAN = 9
    const val TIME = 11
}

object ParameterSupport {
    /** null, если параметр поддерживается; иначе причина пропуска. */
    fun unsupportedReason(endpoint: EndpointRef, parameter: ParameterRef): String? = when (parameter.location) {
        ParamLocation.HEADER, ParamLocation.COOKIE -> "UNSUPPORTED_LOCATION"
        ParamLocation.BODY_FORM, ParamLocation.BODY_JSON ->
            if (endpoint.method == HttpMethod.GET || endpoint.method == HttpMethod.HEAD) "UNSUPPORTED_LOCATION" else null
        ParamLocation.PATH -> if (endpoint.url.contains("{${parameter.name}}")) null else "PATH_PLACEHOLDER_MISSING"
        ParamLocation.QUERY -> null
    }

    fun priority(location: ParamLocation): Int = when (location) {
        ParamLocation.QUERY -> 0
        ParamLocation.BODY_FORM -> 1
        ParamLocation.BODY_JSON -> 2
        ParamLocation.PATH -> 3
        else -> 9
    }
}

/**
 * Детерминированный план: при нехватке бюджета сначала каждому параметру достаётся baseline + error-based,
 * затем остаток тратится на boolean-based (приоритет error → boolean). Неидемпотентные endpoint не проверяются.
 */
class ParameterPlanner(
    private val maxParamsPerEndpoint: Int = 10,
    private val baselineSamples: Int = 3,
) {
    private data class Candidate(val endpointIndex: Int, val rank: Int, val target: ProbeTarget)

    fun plan(endpoints: List<EndpointRef>, options: SqliOptions, maxRequests: Int): PlanResult {
        val skipped = mutableListOf<SkippedEndpoint>()
        val candidates = mutableListOf<Candidate>()

        endpoints.forEachIndexed { endpointIndex, endpoint ->
            if (!endpoint.idempotent) {
                skipped += SkippedEndpoint(endpoint.endpointId, "NOT_IDEMPOTENT")
                return@forEachIndexed
            }
            val testable = endpoint.parameters.filter { ParameterSupport.unsupportedReason(endpoint, it) == null }
            if (testable.isEmpty()) {
                val reason = endpoint.parameters.firstNotNullOfOrNull { ParameterSupport.unsupportedReason(endpoint, it) } ?: "NO_PARAMETERS"
                skipped += SkippedEndpoint(endpoint.endpointId, reason)
                return@forEachIndexed
            }
            testable.sortedBy { ParameterSupport.priority(it.location) }.take(maxParamsPerEndpoint)
                .forEachIndexed { rank, parameter ->
                    candidates += Candidate(endpointIndex, rank, ProbeTarget(endpoint, parameter, ValueTypeInferer.infer(parameter.sampleValue)))
                }
        }

        val ordered = candidates.sortedWith(compareBy({ ParameterSupport.priority(it.target.parameter.location) }, { it.endpointIndex }, { it.rank }))
        val staticChecks = options.errorBased || options.booleanBased
        val granted = LinkedHashMap<Candidate, MutableSet<Phase>>()
        var used = 0

        // Проход 1: baseline + первая включённая проверка
        val first = if (options.errorBased) Phase.ERROR else Phase.BOOLEAN
        for (candidate in ordered) {
            val cost = if (staticChecks) baselineSamples + cost(first) else 0
            if (used + cost > maxRequests) continue
            used += cost
            granted[candidate] = if (staticChecks) mutableSetOf(first) else mutableSetOf()
        }
        // Проход 2: boolean-based на остаток бюджета
        if (options.errorBased && options.booleanBased) {
            for (candidate in ordered) {
                val phases = granted[candidate] ?: continue
                if (used + RequestCosts.BOOLEAN > maxRequests) break
                used += RequestCosts.BOOLEAN
                phases += Phase.BOOLEAN
            }
        }

        candidates.map { it.target.endpoint }.distinctBy { it.endpointId }.forEach { endpoint ->
            if (granted.keys.none { it.target.endpoint.endpointId == endpoint.endpointId }) {
                skipped += SkippedEndpoint(endpoint.endpointId, "BUDGET")
            }
        }

        val parameters = granted.entries.sortedWith(compareBy({ it.key.endpointIndex }, { it.key.rank }))
            .map { PlannedParameter(it.key.target, it.value.toSet()) }
        return PlanResult(parameters, skipped)
    }

    private fun cost(phase: Phase) = if (phase == Phase.ERROR) RequestCosts.ERROR else RequestCosts.BOOLEAN
}
