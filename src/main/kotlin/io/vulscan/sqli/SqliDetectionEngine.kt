package io.vulscan.sqli

import io.vulscan.kit.contracts.ModuleType
import io.vulscan.kit.worker.DetectionEngine
import io.vulscan.kit.worker.InvalidJobException
import io.vulscan.kit.worker.JobContext
import io.vulscan.sqli.config.SqliProperties
import io.vulscan.sqli.job.SqliJobValidator
import io.vulscan.sqli.plan.ParameterPlanner

class SqliDetectionEngine(private val properties: SqliProperties = SqliProperties()) : DetectionEngine {
    override val moduleType = ModuleType.SQLI

    override fun run(ctx: JobContext) {
        val violations = SqliJobValidator.validate(ctx.job)
        if (violations.isNotEmpty()) throw InvalidJobException(violations.joinToString("; "))

        val options = requireNotNull(ctx.job.moduleOptions.sqli)
        val plan = ParameterPlanner(properties.maxParamsPerEndpoint, properties.baselineSamples)
            .plan(ctx.job.endpoints, options, ctx.job.limits.maxRequests)
        plan.skipped.forEach { ctx.stats.skipped(it.endpointId, it.reason) }
    }
}
