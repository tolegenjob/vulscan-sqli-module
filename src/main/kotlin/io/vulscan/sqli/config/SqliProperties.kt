package io.vulscan.sqli.config

import io.vulscan.kit.worker.WorkerProperties

/** Настройки модуля (переменные окружения, см. README). */
data class SqliProperties(
    val maxParamsPerEndpoint: Int = 10,
    val maxTimeProbesPerJob: Int = 20,
    val timeDelaySec: Int = 3,
    val timeDelayMaxSec: Int = 5,
    val baselineSamples: Int = 3,
    val latencySamples: Int = 5,
    val healthPort: Int = 8081,
) {
    init {
        require(maxParamsPerEndpoint > 0) { "maxParamsPerEndpoint must be positive" }
        require(maxTimeProbesPerJob >= 0) { "maxTimeProbesPerJob must not be negative" }
        require(timeDelaySec in 1..timeDelayMaxSec) { "timeDelaySec must be within 1..timeDelayMaxSec" }
        require(baselineSamples >= 2) { "baselineSamples must be at least 2" }
        require(latencySamples >= 3) { "latencySamples must be at least 3" }
    }

    companion object {
        fun fromEnv(env: Map<String, String>) = SqliProperties(
            maxParamsPerEndpoint = env["SQLI_MAX_PARAMS_PER_ENDPOINT"]?.toIntOrNull() ?: 10,
            maxTimeProbesPerJob = env["SQLI_MAX_TIME_PROBES_PER_JOB"]?.toIntOrNull() ?: 20,
            timeDelaySec = env["SQLI_TIME_DELAY_SEC"]?.toIntOrNull() ?: 3,
            timeDelayMaxSec = env["SQLI_TIME_DELAY_MAX_SEC"]?.toIntOrNull() ?: 5,
            baselineSamples = env["SQLI_BASELINE_SAMPLES"]?.toIntOrNull() ?: 3,
            latencySamples = env["SQLI_LATENCY_SAMPLES"]?.toIntOrNull() ?: 5,
            healthPort = env["HEALTH_PORT"]?.toIntOrNull() ?: 8081,
        )

        fun workerFromEnv(env: Map<String, String>): WorkerProperties {
            fun required(name: String) = env[name]?.takeIf { it.isNotBlank() } ?: error("Environment variable $name is required")
            return WorkerProperties(
                coreBaseUrl = required("WORKER_CORE_BASE_URL"),
                serviceToken = required("WORKER_SERVICE_TOKEN"),
                bootstrapServers = env["KAFKA_BOOTSTRAP_SERVERS"] ?: "kafka:9092",
                consumerGroup = env["WORKER_CONSUMER_GROUP"] ?: "sqli-module",
                workerId = env["WORKER_ID"] ?: ("sqli-" + java.util.UUID.randomUUID().toString().take(8)),
                maxConcurrentJobs = env["WORKER_MAX_CONCURRENT_JOBS"]?.toIntOrNull() ?: 2,
                allowedPrivateCidrs = env["WORKER_ALLOWED_PRIVATE_CIDRS"]
                    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            )
        }
    }
}
