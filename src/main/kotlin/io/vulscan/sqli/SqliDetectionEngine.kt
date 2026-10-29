package io.vulscan.sqli

import io.vulscan.kit.contracts.ModuleType
import io.vulscan.kit.contracts.SqliOptions
import io.vulscan.kit.worker.DetectionEngine
import io.vulscan.kit.worker.InvalidJobException
import io.vulscan.kit.worker.JobContext
import io.vulscan.sqli.baseline.BaselineCollector
import io.vulscan.sqli.config.SqliProperties
import io.vulscan.sqli.detect.BooleanBasedDetector
import io.vulscan.sqli.detect.DetectorResult
import io.vulscan.sqli.detect.ErrorBasedDetector
import io.vulscan.sqli.detect.TimeBasedDetector
import io.vulscan.sqli.detect.TimeProbeBudget
import io.vulscan.sqli.http.ProbeSender
import io.vulscan.sqli.http.Sleeper
import io.vulscan.sqli.job.SqliJobValidator
import io.vulscan.sqli.plan.ParameterPlanner
import io.vulscan.sqli.plan.Phase
import io.vulscan.sqli.plan.PlannedParameter
import io.vulscan.sqli.report.SqliFindingFactory

class SqliDetectionEngine(private val properties: SqliProperties = SqliProperties()) : DetectionEngine {
    override val moduleType = ModuleType.SQLI

    override fun run(ctx: JobContext) {
        val violations = SqliJobValidator.validate(ctx.job)
        if (violations.isNotEmpty()) throw InvalidJobException(violations.joinToString("; "))

        val options = requireNotNull(ctx.job.moduleOptions.sqli)
        val plan = ParameterPlanner(properties.maxParamsPerEndpoint, properties.baselineSamples)
            .plan(ctx.job.endpoints, options, ctx.job.limits.maxRequests)
        plan.skipped.forEach { ctx.stats.skipped(it.endpointId, it.reason) }

        val sender = ProbeSender(ctx.http, Sleeper { millis -> Thread.sleep(millis) })
        val timeBudget = TimeProbeBudget(properties.maxTimeProbesPerJob)

        plan.parameters.groupBy { it.target.endpoint.endpointId }.forEach { (_, parameters) ->
            if (ctx.isCancelled()) return
            parameters.forEach { planned ->
                if (ctx.isCancelled()) return
                checkParameter(ctx, planned, options, sender, timeBudget)
            }
            ctx.stats.endpointTested()
        }
    }

    private fun checkParameter(
        ctx: JobContext,
        planned: PlannedParameter,
        options: SqliOptions,
        sender: ProbeSender,
        timeBudget: TimeProbeBudget,
    ) {
        val target = planned.target
        val baseline = BaselineCollector(sender, properties.baselineSamples).collect(target)
        val results = mutableListOf<DetectorResult>()

        if (Phase.ERROR in planned.phases) {
            results += ErrorBasedDetector(sender).detect(target, baseline)
        }
        if (Phase.BOOLEAN in planned.phases) {
            results += BooleanBasedDetector(sender).detect(target, baseline)
        }
        if (options.timeBased) {
            // «Перебор всех СУБД» запрещён: time-based включается только когда СУБД уже известна —
            // из явного dbmsHint либо из DbmsFingerprint, определённого error-based пробой выше.
            val dbms = options.dbmsHint ?: results.firstNotNullOfOrNull { it.dbms }
            results += TimeBasedDetector(sender, Sleeper { millis -> Thread.sleep(millis) }, properties, timeBudget)
                .detect(target, dbms)
        }

        if (results.any { it.inconclusiveReason != null || it.http500WithoutSignature }) {
            ctx.stats.inconclusive()
        }
        SqliFindingFactory.create(target.endpoint, target.parameter, results, baseline)?.let { ctx.report(it) }
    }
}
